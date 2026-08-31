import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];

export type Customer = Schemas['CustomerResponse'];
export type SalesOrder = Schemas['SalesOrderResponse'];
export type SalesOrderLine = Schemas['SalesOrderLineResponse'];
export type Payment = Schemas['PaymentResponse'];
export type Invoice = Schemas['InvoiceResponse'];
export type Fulfilment = Schemas['FulfilmentResponse'];

const list = <T>(path: string, query?: Record<string, string | number | boolean | undefined>) =>
  api.get<PagedResponse<T>>(path, query).then((page) => page.data);

export const salesKeys = {
  customers: ['customers'] as const,
  orders: ['sales-orders'] as const,
  payments: ['payments'] as const,
  invoices: ['invoices'] as const,
};

/**
 * Everything a sale touches.
 *
 * Verifying a payment changes the payment, its order and the pending queue; fulfilling changes all
 * of those plus stock, availability and reorder alerts. Listing them once here is what stops a
 * screen quietly showing a figure that stopped being true two clicks ago.
 */
const SALES_TOUCHING = [
  salesKeys.orders,
  salesKeys.payments,
  salesKeys.invoices,
  ['stock'],
  ['movements'],
  ['availability'],
  ['reorder-alerts'],
  ['reservations'],
] as const;

// ---------------------------------------------------------------- reads

export const useCustomers = (search?: string, includeInactive = false) =>
  useQuery({
    queryKey: [...salesKeys.customers, search ?? '', includeInactive],
    queryFn: () => list<Customer>('/api/v1/customers', { search, includeInactive }),
  });

export const useCustomer = (id: string | null) =>
  useQuery({
    queryKey: [...salesKeys.customers, id],
    queryFn: () => api.get<Customer>(`/api/v1/customers/${id}`),
    enabled: Boolean(id),
  });

export const useSalesOrders = (status?: string) =>
  useQuery({
    queryKey: [...salesKeys.orders, status ?? 'all'],
    queryFn: () => list<SalesOrder>('/api/v1/sales-orders', { status }),
  });

export const useSalesOrder = (id: string | null) =>
  useQuery({
    queryKey: [...salesKeys.orders, id],
    queryFn: () => api.get<SalesOrder>(`/api/v1/sales-orders/${id}`),
    enabled: Boolean(id),
  });

export const useOrderPayments = (orderId: string | null) =>
  useQuery({
    queryKey: [...salesKeys.payments, 'for-order', orderId],
    queryFn: () => list<Payment>(`/api/v1/sales-orders/${orderId}/payments`),
    enabled: Boolean(orderId),
  });

export const useOrderInvoice = (orderId: string | null) =>
  useQuery({
    // 204 when the order has not been fulfilled, which the client turns into undefined.
    queryKey: [...salesKeys.invoices, 'for-order', orderId],
    queryFn: () => api.get<Invoice | undefined>(`/api/v1/sales-orders/${orderId}/invoice`),
    enabled: Boolean(orderId),
  });

export const usePayments = (status?: string) =>
  useQuery({
    queryKey: [...salesKeys.payments, status ?? 'all'],
    queryFn: () => list<Payment>('/api/v1/payments', { status }),
  });

export const useInvoices = () =>
  useQuery({ queryKey: salesKeys.invoices, queryFn: () => list<Invoice>('/api/v1/invoices') });

// ---------------------------------------------------------------- mutations

function useSalesMutation<TArgs, TResult>(
  mutationFn: (args: TArgs) => Promise<TResult>,
  affected: readonly (readonly string[])[],
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      affected.forEach((key) => void queryClient.invalidateQueries({ queryKey: key }));
    },
  });
}

export const useCreateCustomer = () =>
  useSalesMutation((body: unknown) => api.post<Customer>('/api/v1/customers', body), [
    salesKeys.customers,
  ]);

export const useUpdateCustomer = () =>
  useSalesMutation(
    ({ id, body }: { id: string; body: unknown }) =>
      api.put<Customer>(`/api/v1/customers/${id}`, body),
    [salesKeys.customers],
  );

export const useSetCustomerActive = () =>
  useSalesMutation(
    ({ id, active }: { id: string; active: boolean }) =>
      api.post<Customer>(`/api/v1/customers/${id}/${active ? 'activate' : 'deactivate'}`),
    [salesKeys.customers],
  );

/**
 * P5-03 · the idempotency key rides in a header.
 *
 * It describes the *request*, not the order, which is why it is not a body field: resending the
 * same body with the same key is a retry and returns the first order; the same body with a fresh
 * key is a genuine second order, which people legitimately place.
 *
 * The caller supplies the key rather than this hook generating one, because a key generated here
 * would be new on every render — and a retry that invents a new key is not a retry at all.
 */
export const useCreateSalesOrder = () =>
  useSalesMutation(
    ({ body, idempotencyKey }: { body: unknown; idempotencyKey?: string }) =>
      api.post<SalesOrder>(
        '/api/v1/sales-orders',
        body,
        undefined,
        idempotencyKey ? { 'Idempotency-Key': idempotencyKey } : undefined,
      ),
    SALES_TOUCHING,
  );

export const useCancelSalesOrder = () =>
  useSalesMutation(
    ({ id, reason }: { id: string; reason?: string }) =>
      api.post<SalesOrder>(`/api/v1/sales-orders/${id}/cancel`, undefined, { reason }),
    SALES_TOUCHING,
  );

export const useRecordPayment = () =>
  useSalesMutation((body: unknown) => api.post<Payment>('/api/v1/payments', body), SALES_TOUCHING);

export const useVerifyPayment = () =>
  useSalesMutation(
    (id: string) => api.post<Payment>(`/api/v1/payments/${id}/verify`),
    SALES_TOUCHING,
  );

export const useRejectPayment = () =>
  useSalesMutation(
    ({ id, reason }: { id: string; reason: string }) =>
      api.post<Payment>(`/api/v1/payments/${id}/reject`, { reason }),
    SALES_TOUCHING,
  );

export const useFulfilOrder = () =>
  useSalesMutation(
    (id: string) => api.post<Fulfilment>(`/api/v1/sales-orders/${id}/fulfil`),
    SALES_TOUCHING,
  );

// ---------------------------------------------------------------- slip viewing

/**
 * Fetches a bank slip for display.
 *
 * Same two-step dance as a KYC document: ask for a token, which is where the server authorises and
 * logs the access, then redeem it. The token lasts 60 seconds and works once, so the object URL is
 * the only copy — reloading the image means asking again, and that ask is logged again.
 */
export async function fetchSlipObjectUrl(paymentId: string) {
  const grant = await api.post<{ token: string; url: string; expiresInSeconds: number }>(
    `/api/v1/payments/${paymentId}/slip-access`,
  );

  const response = await fetch(grant.url, { credentials: 'include' });
  if (!response.ok) {
    throw new Error('That slip link expired before it could be opened.');
  }
  const blob = await response.blob();
  return { objectUrl: URL.createObjectURL(blob), contentType: blob.type };
}

/** Sales order status values, in lifecycle order. */
export const ORDER_STATUSES = [
  'awaiting_payment',
  'payment_review',
  'paid',
  'fulfilled',
  'payment_rejected',
  'cancelled',
] as const;
