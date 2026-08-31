import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import type { UseQueryOptions } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';
import type {
  Category,
  GoodsReceipt,
  Item,
  ItemSet,
  LocationSummary,
  PurchaseOrder,
  ReorderAlert,
  Reservation,
  SetAvailability,
  StockByItem,
  StockLevel,
  StockMovement,
  RewardEntitlement,
  StoreDetail,
  Supplier,
  SupplierPrice,
  UserSummary,
} from './types';

/**
 * Server state, all in one place.
 *
 * Every list endpoint returns the frozen `{ data, nextCursor }` envelope (P0-05), so unwrapping is
 * uniform here — and because it is uniform, cursors are handled entirely in this file. No screen
 * has ever had to know they exist, which is what freezing the shape in P0-05 bought.
 */

/**
 * Fetch a list endpoint in full.
 *
 * Follows the cursor until the server stops offering one, so a screen always receives every row.
 *
 * There is no paging in the interface, by decision. The alternative — showing the first page and
 * a "load more" — meant a stock count or a customer list could be quietly incomplete, and a total
 * you cannot trust is worse than a slower screen. Keyset pagination still exists on the server and
 * still does its job: it keeps each request bounded and indexed. It is simply not something anyone
 * using this has to think about.
 *
 * If a list ever grows large enough for this to hurt, the fix is a narrower query — a filter, a
 * date range — not making the user click through pages to find out what they own.
 */
const list = async <T>(
  path: string,
  query?: Record<string, string | number | boolean | undefined>,
) => {
  const all: T[] = [];
  let cursor: string | undefined;

  // A bound, not a belief. A server bug that returned the same cursor forever would otherwise spin
  // here until the tab died, and the symptom would look like a hung screen rather than a bug.
  for (let request = 0; request < 50; request++) {
    const page: PagedResponse<T> = await api.get<PagedResponse<T>>(path, { ...query, cursor });
    all.push(...page.data);
    if (!page.nextCursor) {
      return all;
    }
    cursor = page.nextCursor;
  }
  return all;
};

/**
 * One request, newest first — for history that grows without bound.
 *
 * Not pagination: there is no "next" anywhere in the interface, and nothing here is hidden behind
 * a click. It is a ceiling on how far back a screen looks. `list` above would follow the cursor to
 * the beginning of time, and fetching a year of stock movements to render twenty rows would be
 * absurd — the server caps each page at 200 anyway, so it would take fifty requests to do it.
 */
const recent = <T>(
  path: string,
  query?: Record<string, string | number | boolean | undefined>,
) => api.get<PagedResponse<T>>(path, query).then((page) => page.data);

/** Query keys, centralised so invalidation after a mutation cannot miss a screen. */
export const keys = {
  items: ['items'] as const,
  categories: ['categories'] as const,
  itemSets: ['item-sets'] as const,
  availability: ['availability'] as const,
  stock: ['stock'] as const,
  movements: ['movements'] as const,
  locations: ['locations'] as const,
  reorderAlerts: ['reorder-alerts'] as const,
  reservations: ['reservations'] as const,
  suppliers: ['suppliers'] as const,
  purchaseOrders: ['purchase-orders'] as const,
  goodsReceipts: ['goods-receipts'] as const,
  users: ['users'] as const,
  supplierPrices: ['supplier-prices'] as const,
  rewards: ['rewards'] as const,
};

type Options<T> = Omit<UseQueryOptions<T, Error, T>, 'queryKey' | 'queryFn'>;

/** Everything a stock change invalidates. Declared here because several mutations below need it. */
const STOCK_TOUCHING = [
  keys.stock,
  keys.movements,
  keys.availability,
  keys.reorderAlerts,
] as const;

// ---------------------------------------------------------------- catalogue

export const useItems = (includeInactive = false) =>
  useQuery({
    queryKey: [...keys.items, includeInactive],
    queryFn: () => list<Item>('/api/v1/items', { includeInactive }),
  });

export const useCategories = () =>
  useQuery({ queryKey: keys.categories, queryFn: () => list<Category>('/api/v1/categories') });

export const useItemSets = (includeInactive = false) =>
  useQuery({
    queryKey: [...keys.itemSets, includeInactive],
    queryFn: () => list<ItemSet>('/api/v1/item-sets', { includeInactive }),
  });

// ---------------------------------------------------------------- inventory

export const useStock = (locationId?: string) =>
  useQuery({
    queryKey: [...keys.stock, locationId ?? 'all'],
    queryFn: () => list<StockLevel>('/api/v1/stock', { locationId }),
  });

/**
 * Stock totalled per item, with the per-store breakdown attached.
 *
 * The screen's main view. One row per item, not one per item-and-store — the old shape made the
 * same item appear several times with a slice of its quantity in each.
 */
export const useStockByItem = () =>
  useQuery({
    queryKey: [...keys.stock, 'by-item'],
    queryFn: () => list<StockByItem>('/api/v1/stock/by-item'),
  });

export const useStore = (id: string | null) =>
  useQuery({
    queryKey: [...keys.locations, id],
    queryFn: () => api.get<StoreDetail>(`/api/v1/locations/${id}`),
    enabled: Boolean(id),
  });

/**
 * Movement history, newest first.
 *
 * The newest 200 only, via `recent` rather than `list`. History grows forever and nobody reads to
 * the bottom of it; those entries answer every question this screen is opened to answer. Nothing
 * is hidden behind a page control — there simply is not an infinite scroll of ledger here.
 *
 * Either filter may be omitted to mean "any".
 */
export const useMovements = (
  itemId?: string,
  locationId?: string,
  options?: Options<StockMovement[]>,
) =>
  useQuery({
    queryKey: [...keys.movements, itemId ?? 'all', locationId ?? 'all'],
    queryFn: () => recent<StockMovement>('/api/v1/stock/movements', { itemId, locationId }),
    ...options,
  });

export const useLocations = () =>
  useQuery({ queryKey: keys.locations, queryFn: () => list<LocationSummary>('/api/v1/locations') });

export const useReorderAlerts = (openOnly = true) =>
  useQuery({
    queryKey: [...keys.reorderAlerts, openOnly],
    queryFn: () => list<ReorderAlert>('/api/v1/stock/reorder-alerts', { openOnly }),
  });

export const useSetAvailability = (locationId?: string) =>
  useQuery({
    queryKey: [...keys.availability, locationId ?? 'default'],
    queryFn: () => list<SetAvailability>('/api/v1/item-sets/availability', { locationId }),
  });

export const useReservations = (activeOnly = false) =>
  useQuery({
    queryKey: [...keys.reservations, activeOnly],
    queryFn: () => list<Reservation>('/api/v1/reservations', { activeOnly }),
  });

// ---------------------------------------------------------------- procurement

export const useSuppliers = (includeInactive = false) =>
  useQuery({
    queryKey: [...keys.suppliers, includeInactive],
    queryFn: () => list<Supplier>('/api/v1/suppliers', { includeInactive }),
  });

export const usePurchaseOrders = () =>
  useQuery({
    queryKey: keys.purchaseOrders,
    queryFn: () => list<PurchaseOrder>('/api/v1/purchase-orders'),
  });

export const usePurchaseOrder = (id: string | null) =>
  useQuery({
    queryKey: [...keys.purchaseOrders, id],
    queryFn: () => api.get<PurchaseOrder>(`/api/v1/purchase-orders/${id}`),
    enabled: Boolean(id),
  });

/**
 * Orders whose goods are in the building but not yet on a shelf.
 *
 * Filtered server-side rather than here. The receiving screen must not be able to miss a delivery
 * because it only fetched the first page of orders.
 */
export const useOrdersAwaitingStoring = () =>
  useQuery({
    queryKey: [...keys.purchaseOrders, 'awaiting-storing'],
    queryFn: () => list<PurchaseOrder>('/api/v1/purchase-orders/awaiting-storing'),
  });

export const useGoodsReceipts = () =>
  useQuery({
    queryKey: keys.goodsReceipts,
    queryFn: () => list<GoodsReceipt>('/api/v1/goods-receipts'),
  });

// ---------------------------------------------------------------- reward packs

/** @param status `eligible`, `issued`, or omitted for everything */
export const useRewardEntitlements = (status?: string) =>
  useQuery({
    queryKey: [...keys.rewards, status ?? 'all'],
    queryFn: () => list<RewardEntitlement>('/api/v1/admin/rewards', { status }),
  });

/** The badge count. Cheap enough to poll with the rest of the screen. */
export const useRewardsWaiting = (enabled = true) =>
  useQuery({
    queryKey: [...keys.rewards, 'waiting'],
    queryFn: () => api.get<{ waiting: number }>('/api/v1/admin/rewards/waiting-count'),
    enabled,
  });

// ---------------------------------------------------------------- identity

export const useUsers = () =>
  useQuery({ queryKey: keys.users, queryFn: () => list<UserSummary>('/api/v1/admin/users') });

// ---------------------------------------------------------------- mutations

/**
 * Wraps a mutation and refreshes the query keys it affects.
 *
 * Stock is the reason this is centralised. A goods receipt changes stock levels, set availability,
 * reorder alerts and the purchase order all at once — anything that forgets one of those leaves a
 * screen quietly showing a number that is no longer true.
 */
function useInvalidatingMutation<TArgs, TResult>(
  mutationFn: (args: TArgs) => Promise<TResult>,
  affected: readonly (readonly string[])[],
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      affected.forEach((key) => {
        void queryClient.invalidateQueries({ queryKey: key });
      });
    },
  });
}

export const useCreateItem = () =>
  useInvalidatingMutation(
    (body: unknown) => api.post<Item>('/api/v1/items', body),
    // Creating an item now establishes a stock position too, so the stock screens are stale the
    // moment it succeeds.
    [keys.items, ...STOCK_TOUCHING],
  );

export const useCreateCategory = () =>
  useInvalidatingMutation((body: unknown) => api.post<Category>('/api/v1/categories', body), [
    keys.categories,
    keys.items,
  ]);

/** What each supplier charges for one item. Empty until somebody quotes. */
export const useSupplierPrices = (itemId: string | null) =>
  useQuery({
    queryKey: [...keys.supplierPrices, itemId],
    queryFn: () => list<SupplierPrice>(`/api/v1/items/${itemId}/supplier-prices`),
    enabled: Boolean(itemId),
  });

export const useSetSupplierPrice = () =>
  useInvalidatingMutation(
    ({ itemId, body }: { itemId: string; body: unknown }) =>
      api.put<SupplierPrice>(`/api/v1/items/${itemId}/supplier-prices`, body),
    [keys.supplierPrices, keys.purchaseOrders],
  );

export const useUpdateItem = () =>
  useInvalidatingMutation(
    ({ id, body }: { id: string; body: unknown }) => api.put<Item>(`/api/v1/items/${id}`, body),
    [keys.items, keys.availability],
  );

export const useSetItemActive = () =>
  useInvalidatingMutation(
    ({ id, active }: { id: string; active: boolean }) =>
      api.post<Item>(`/api/v1/items/${id}/${active ? 'activate' : 'deactivate'}`),
    [keys.items, keys.availability],
  );

export const useAdjustStock = () =>
  useInvalidatingMutation(
    (body: unknown) => api.post<StockLevel>('/api/v1/stock/adjustments', body),
    STOCK_TOUCHING,
  );

export const useReconcileStock = () =>
  useInvalidatingMutation(() => api.post<unknown>('/api/v1/stock/reconcile'), STOCK_TOUCHING);

export const useRunReorderScan = () =>
  useInvalidatingMutation(() => api.post<unknown>('/api/v1/stock/reorder-scan'), [
    keys.reorderAlerts,
  ]);

export const useCreateItemSet = () =>
  useInvalidatingMutation((body: unknown) => api.post<ItemSet>('/api/v1/item-sets', body), [
    keys.itemSets,
    keys.availability,
  ]);

export const useUpdateItemSet = () =>
  useInvalidatingMutation(
    ({ id, body }: { id: string; body: unknown }) =>
      api.put<ItemSet>(`/api/v1/item-sets/${id}`, body),
    [keys.itemSets, keys.availability],
  );

/** Refused server-side once the set has been sold — see `ITEM_SET_IN_USE`. */
export const useDeleteItemSet = () =>
  useInvalidatingMutation(
    (id: string) => api.del<void>(`/api/v1/item-sets/${id}`),
    [keys.itemSets, keys.availability],
  );

export const useSetItemSetActive = () =>
  useInvalidatingMutation(
    ({ id, active }: { id: string; active: boolean }) =>
      api.post<ItemSet>(`/api/v1/item-sets/${id}/${active ? 'activate' : 'deactivate'}`),
    [keys.itemSets, keys.availability],
  );

export const useCreateReservation = () =>
  useInvalidatingMutation((body: unknown) => api.post<Reservation>('/api/v1/reservations', body), [
    keys.reservations,
    ...STOCK_TOUCHING,
  ]);

export const useReleaseReservation = () =>
  useInvalidatingMutation(
    ({ id, reason }: { id: string; reason: string }) =>
      api.post<Reservation>(`/api/v1/reservations/${id}/release`, undefined, { reason }),
    [keys.reservations, ...STOCK_TOUCHING],
  );

export const useExpireReservations = () =>
  useInvalidatingMutation(() => api.post<unknown>('/api/v1/reservations/expire-scan'), [
    keys.reservations,
    ...STOCK_TOUCHING,
  ]);

export const useCreateSupplier = () =>
  useInvalidatingMutation((body: unknown) => api.post<Supplier>('/api/v1/suppliers', body), [
    keys.suppliers,
  ]);

export const useUpdateSupplier = () =>
  useInvalidatingMutation(
    ({ id, body }: { id: string; body: unknown }) =>
      api.put<Supplier>(`/api/v1/suppliers/${id}`, body),
    [keys.suppliers, keys.purchaseOrders],
  );

/** Refused server-side once anything points at them — see `SUPPLIER_IN_USE`. */
export const useDeleteSupplier = () =>
  useInvalidatingMutation((id: string) => api.del<void>(`/api/v1/suppliers/${id}`), [
    keys.suppliers,
  ]);

export const useSetSupplierActive = () =>
  useInvalidatingMutation(
    ({ id, active }: { id: string; active: boolean }) =>
      api.post<Supplier>(`/api/v1/suppliers/${id}/${active ? 'activate' : 'deactivate'}`),
    [keys.suppliers],
  );

export const useCreatePurchaseOrder = () =>
  useInvalidatingMutation(
    (body: unknown) => api.post<PurchaseOrder>('/api/v1/purchase-orders', body),
    [keys.purchaseOrders],
  );

/** Draft only. A sent order's lines are frozen server-side. */
export const useReplacePurchaseOrderLines = () =>
  useInvalidatingMutation(
    ({ id, lines }: { id: string; lines: unknown[] }) =>
      api.put<PurchaseOrder>(`/api/v1/purchase-orders/${id}/lines`, { lines }),
    [keys.purchaseOrders],
  );

/** Emails the order to the supplier and freezes it. Refused if they have no address on file. */
export const useSendPurchaseOrder = () =>
  useInvalidatingMutation(
    (id: string) => api.post<PurchaseOrder>(`/api/v1/purchase-orders/${id}/send`),
    [keys.purchaseOrders],
  );

/** Signing for a delivery. Moves no stock — see `useReceiveGoods` for that. */
export const useConfirmArrival = () =>
  useInvalidatingMutation(
    ({ id, attestedName }: { id: string; attestedName: string }) =>
      api.post<PurchaseOrder>(`/api/v1/purchase-orders/${id}/confirm-arrival`, { attestedName }),
    [keys.purchaseOrders],
  );

/** Putting an arrived delivery into stores. This is where stock actually goes up. */
export const useReceiveGoods = () =>
  useInvalidatingMutation(
    (body: unknown) => api.post<GoodsReceipt>('/api/v1/goods-receipts', body),
    [keys.purchaseOrders, keys.goodsReceipts, ...STOCK_TOUCHING],
  );

/** Goods that turned up with no purchase order behind them. */
export const useReceiveGoodsManually = () =>
  useInvalidatingMutation(
    (body: unknown) => api.post<GoodsReceipt>('/api/v1/goods-receipts/manual', body),
    [keys.goodsReceipts, ...STOCK_TOUCHING],
  );

// ---------------------------------------------------------------- stores

export const useCreateStore = () =>
  useInvalidatingMutation((body: unknown) => api.post<LocationSummary>('/api/v1/locations', body), [
    keys.locations,
  ]);

export const useUpdateStore = () =>
  useInvalidatingMutation(
    ({ id, body }: { id: string; body: unknown }) =>
      api.put<LocationSummary>(`/api/v1/locations/${id}`, body),
    [keys.locations],
  );

export const useSetStoreActive = () =>
  useInvalidatingMutation(
    ({ id, active }: { id: string; active: boolean }) =>
      api.post<LocationSummary>(`/api/v1/locations/${id}/${active ? 'activate' : 'deactivate'}`),
    [keys.locations],
  );

/**
 * Hands a pack over, and takes the stock out with it.
 *
 * Invalidates the stock keys as well as the reward ones — this is a stock movement, and leaving
 * the stock screen showing the old figure would be a lie the user has no reason to suspect.
 */
export const useIssueRewardPack = () =>
  useInvalidatingMutation(
    ({ id, locationId, note }: { id: string; locationId: string; note?: string }) =>
      api.post<RewardEntitlement>(`/api/v1/admin/rewards/${id}/issue`, { locationId, note }),
    [keys.rewards, ...STOCK_TOUCHING],
  );

export const useReplaceUserRoles = () =>
  useInvalidatingMutation(
    ({ id, roleCodes }: { id: string; roleCodes: string[] }) =>
      api.put<UserSummary>(`/api/v1/admin/users/${id}/roles`, { roleCodes }),
    [keys.users],
  );
