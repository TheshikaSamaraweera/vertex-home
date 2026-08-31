import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { useItems, useItemSets } from '../api/queries';
import { uploadDocument } from '../api/onboarding';
import {
  fetchSlipObjectUrl,
  useCancelSalesOrder,
  useCustomers,
  useCreateSalesOrder,
  useFulfilOrder,
  useInvoices,
  useOrderInvoice,
  useOrderPayments,
  usePayments,
  useRecordPayment,
  useRejectPayment,
  useSalesOrder,
  useSalesOrders,
  useVerifyPayment,
  type Payment,
  type SalesOrder,
} from '../api/sales';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  Input,
  Modal,
  money,
  PageHeader,
  Select,
  Spinner,
  statusTone,
  humanStatus,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * The whole sale on one screen: order, slip, decision, goods out, invoice.
 *
 * The role split from architecture §8.1 is visible in the buttons. A finance officer sells and
 * records the money; a *different* finance officer decides whether the slip is real — the server
 * returns `SELF_VERIFICATION_FORBIDDEN` if the same person tries both, and the UI does not hide
 * that button, because seeing the refusal is how a tester confirms the control exists.
 */
export function SalesPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canSell = hasRole('FINANCE_OFFICER', 'SUPER_ADMIN');
  const canFulfil = hasRole('INVENTORY_CLERK', 'SUPER_ADMIN');

  const orders = useSalesOrders();
  const customers = useCustomers(undefined, true);
  const fulfil = useFulfilOrder();
  const cancel = useCancelSalesOrder();

  const [creating, setCreating] = useState(false);
  const [payingFor, setPayingFor] = useState<SalesOrder | null>(null);
  const [viewing, setViewing] = useState<string | null>(null);

  const customerName = (id: string | undefined) =>
    (customers.data ?? []).find((customer) => customer.id === id)?.name ?? '—';

  return (
    <>
      <PageHeader
        title={t('Sales')}
        description={t('An order holds its stock the moment it is placed, and only lets go when it is fulfilled, rejected or cancelled.')}
        actions={
          canSell && (
            <Button variant="primary" size="sm" onClick={() => setCreating(true)}>
              {t('New order')}
            </Button>
          )
        }
      />

      {(fulfil.error || cancel.error) && (
        <div className="mb-4">
          <ErrorBanner error={fulfil.error ?? cancel.error} />
        </div>
      )}

      <div className="flex flex-col gap-5">
        <Card title={t('Sales orders')}>
          {orders.isLoading ? (
            <Spinner />
          ) : orders.error ? (
            <div className="p-4">
              <ErrorBanner error={orders.error} onRetry={() => void orders.refetch()} />
            </div>
          ) : (orders.data ?? []).length === 0 ? (
            <EmptyState
              message={t('No sales orders yet.')}
              hint={canSell ? t('Start one with “New order”.') : undefined}
            />
          ) : (
            <TableWrap>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('Number')}</Th>
                    <Th>{t('Buyer')}</Th>
                    <Th>{t('Status')}</Th>
                    <Th align="right">{t('Lines')}</Th>
                    <Th align="right">{t('Total')}</Th>
                    <Th>{''}</Th>
                  </tr>
                </thead>
                <tbody>
                  {(orders.data ?? []).map((order) => (
                    <tr key={order.id} className="hover:bg-panel2">
                      <Td className="font-mono text-xs">{order.orderNumber}</Td>
                      <Td>{customerName(order.customerId)}</Td>
                      <Td>
                        <Badge tone={statusTone(order.orderStatus ?? '')}>
                          {humanStatus(order.orderStatus)}
                        </Badge>
                      </Td>
                      <Td align="right">{order.lines?.length ?? 0}</Td>
                      <Td align="right">{money(order.total)}</Td>
                      <Td>
                        <div className="flex justify-end gap-1">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => order.id && setViewing(order.id)}
                          >
                            {t('Open')}
                          </Button>

                          {canSell &&
                            (order.orderStatus === 'awaiting_payment' ||
                              order.orderStatus === 'payment_rejected') && (
                              <Button size="sm" onClick={() => setPayingFor(order)}>
                                {t('Record payment')}
                              </Button>
                            )}

                          {canFulfil && order.orderStatus === 'paid' && (
                            <Button
                              size="sm"
                              variant="primary"
                              disabled={fulfil.isPending}
                              onClick={() => order.id && fulfil.mutate(order.id)}
                            >
                              {t('Fulfil')}
                            </Button>
                          )}

                          {canSell &&
                            order.orderStatus !== 'fulfilled' &&
                            order.orderStatus !== 'cancelled' && (
                              <Button
                                size="sm"
                                variant="ghost"
                                disabled={cancel.isPending}
                                onClick={() =>
                                  order.id && cancel.mutate({ id: order.id, reason: 'cancelled in UI' })
                                }
                              >
                                {t('Cancel')}
                              </Button>
                            )}
                        </div>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </TableWrap>
          )}
        </Card>

        <PaymentQueue />
        <InvoiceRegister />
      </div>

      {creating && <CreateOrderModal onClose={() => setCreating(false)} />}
      {payingFor && <RecordPaymentModal order={payingFor} onClose={() => setPayingFor(null)} />}
      {viewing && <OrderDetailModal orderId={viewing} onClose={() => setViewing(null)} />}
    </>
  );
}

// ================================================================== payment queue

/** P5-06 · what finance has to decide on. */
function PaymentQueue() {
  const { t } = useTranslation();
  const { hasRole, user } = useAuth();
  const canDecide = hasRole('FINANCE_OFFICER', 'SUPER_ADMIN');

  const pending = usePayments('pending');
  const verify = useVerifyPayment();
  const [rejecting, setRejecting] = useState<Payment | null>(null);
  const [slipFor, setSlipFor] = useState<Payment | null>(null);

  if (!canDecide && !hasRole('SUPPORT_AGENT')) {
    return null;
  }

  return (
    <>
      <Card
        title={t('Payments awaiting verification')}
        subtitle={t('A payment must be checked by someone other than whoever recorded it')}
      >
        {pending.isLoading ? (
          <Spinner />
        ) : pending.error ? (
          <div className="p-4">
            <ErrorBanner error={pending.error} onRetry={() => void pending.refetch()} />
          </div>
        ) : (pending.data ?? []).length === 0 ? (
          <EmptyState message={t('Nothing waiting.')} />
        ) : (
          <>
            {verify.error && (
              <div className="p-4 pb-0">
                <ErrorBanner error={verify.error} />
              </div>
            )}
            <TableWrap>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('Bank reference')}</Th>
                    <Th align="right">{t('Amount')}</Th>
                    <Th>{t('Paid on')}</Th>
                    <Th>{t('Recorded by')}</Th>
                    <Th>{''}</Th>
                  </tr>
                </thead>
                <tbody>
                  {(pending.data ?? []).map((payment) => {
                    const ownEntry = payment.recordedBy === user?.id;
                    return (
                      <tr key={payment.id} className="hover:bg-panel2">
                        <Td className="font-mono text-xs">{payment.bankRef ?? '—'}</Td>
                        <Td align="right">{money(payment.amount)}</Td>
                        <Td className="text-xs">{payment.paidOn ?? '—'}</Td>
                        <Td className="text-xs">
                          {ownEntry ? (
                            <Badge tone="warn">{t('you')}</Badge>
                          ) : (
                            <span className="text-ink3">{t('another officer')}</span>
                          )}
                        </Td>
                        <Td>
                          <div className="flex justify-end gap-1">
                            <Button size="sm" variant="ghost" onClick={() => setSlipFor(payment)}>
                              {t('View slip')}
                            </Button>
                            {canDecide && (
                              <>
                                <Button
                                  size="sm"
                                  variant="primary"
                                  disabled={verify.isPending}
                                  onClick={() => payment.id && verify.mutate(payment.id)}
                                  // Deliberately not hidden for your own entry: the server refuses
                                  // it, and seeing that refusal is how the control is checked.
                                  title={
                                    ownEntry
                                      ? t('You recorded this one — the server will refuse')
                                      : undefined
                                  }
                                >
                                  {t('Verify')}
                                </Button>
                                <Button
                                  size="sm"
                                  variant="danger"
                                  onClick={() => setRejecting(payment)}
                                >
                                  {t('Reject')}
                                </Button>
                              </>
                            )}
                          </div>
                        </Td>
                      </tr>
                    );
                  })}
                </tbody>
              </Table>
            </TableWrap>
          </>
        )}
      </Card>

      {rejecting && <RejectModal payment={rejecting} onClose={() => setRejecting(null)} />}
      {slipFor && <SlipModal payment={slipFor} onClose={() => setSlipFor(null)} />}
    </>
  );
}

function RejectModal({ payment, onClose }: { payment: Payment; onClose: () => void }) {
  const { t } = useTranslation();
  const reject = useRejectPayment();
  const [reason, setReason] = useState('');

  return (
    <Modal title={t('Reject this payment')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (payment.id) reject.mutate({ id: payment.id, reason }, { onSuccess: onClose });
        }}
      >
        <p className="text-xs text-ink2">
          {t('Rejecting releases the stock this order was holding. The order stays open — the buyer can submit a corrected slip, and the stock is taken again at that point.')}
        </p>
        <Field label={t('Reason')} hint={t('The buyer will be told this')}>
          <Input
            required
            autoFocus
            value={reason}
            onChange={(event) => setReason(event.target.value)}
            placeholder={t('Amount does not match the slip')}
          />
        </Field>
        <ErrorBanner error={reject.error} />
        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="danger" disabled={reject.isPending || !reason}>
            {t('Reject and release stock')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** Two steps behind the scenes: authorise (logged), then redeem a 60-second single-use token. */
function SlipModal({ payment, onClose }: { payment: Payment; onClose: () => void }) {
  const { t } = useTranslation();
  const [state, setState] = useState<{ url?: string; type?: string; error?: unknown }>({});

  useEffect(() => {
    let objectUrl: string | undefined;
    let cancelled = false;

    void (async () => {
      try {
        const served = await fetchSlipObjectUrl(payment.id!);
        if (cancelled) {
          URL.revokeObjectURL(served.objectUrl);
          return;
        }
        objectUrl = served.objectUrl;
        setState({ url: served.objectUrl, type: served.contentType });
      } catch (error) {
        if (!cancelled) setState({ error });
      }
    })();

    return () => {
      cancelled = true;
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [payment.id]);

  return (
    <Modal title={t('Bank slip')} onClose={onClose} wide>
      <p className="mb-3 text-xs text-ink3">
        {t('This view was authorised and logged before the image was fetched. The link works once and expires in 60 seconds.')}
      </p>
      {state.error ? (
        <ErrorBanner error={state.error} />
      ) : !state.url ? (
        <Spinner label={t('Requesting access…')} />
      ) : state.type === 'application/pdf' ? (
        <a
          className="text-sm text-brand underline"
          href={state.url}
          download={`slip-${payment.bankRef ?? payment.id}.pdf`}
        >
          {t('Download the slip (PDF)')}
        </a>
      ) : (
        <img src={state.url} alt={t('Bank slip')} className="max-h-[70vh] w-full object-contain" />
      )}
      <dl className="nums mt-4 grid gap-x-6 gap-y-1 text-xs text-ink2 sm:grid-cols-2">
        <div className="flex gap-2">
          <dt className="text-ink3">{t('Bank reference')}:</dt>
          <dd className="font-mono">{payment.bankRef ?? '—'}</dd>
        </div>
        <div className="flex gap-2">
          <dt className="text-ink3">{t('Amount')}:</dt>
          <dd>{money(payment.amount)}</dd>
        </div>
      </dl>
    </Modal>
  );
}

// ================================================================== invoices

/** P5-10 · the register, in number order. Gaps would be visible here at a glance. */
function InvoiceRegister() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const invoices = useInvoices();

  if (!hasRole('FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT')) {
    return null;
  }

  return (
    <Card
      title={t('Invoices')}
      subtitle={t('Numbered consecutively. A gap would mean a sale went missing, so there are none.')}
    >
      {invoices.isLoading ? (
        <Spinner />
      ) : (invoices.data ?? []).length === 0 ? (
        <EmptyState message={t('No invoices yet — one is issued when an order is fulfilled.')} />
      ) : (
        <TableWrap>
          <Table>
            <thead>
              <tr>
                <Th>{t('Number')}</Th>
                <Th align="right">{t('Sequence')}</Th>
                <Th align="right">{t('Total')}</Th>
                <Th>{t('Issued')}</Th>
              </tr>
            </thead>
            <tbody>
              {(invoices.data ?? []).map((invoice) => (
                <tr key={invoice.id}>
                  <Td className="font-mono text-xs text-ink">{invoice.invoiceNumber}</Td>
                  <Td align="right">{invoice.sequenceNo}</Td>
                  <Td align="right">{money(invoice.total)}</Td>
                  <Td className="text-xs">
                    {invoice.issuedAt ? new Date(invoice.issuedAt).toLocaleString() : '—'}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </TableWrap>
      )}
    </Card>
  );
}

// ================================================================== create order

function CreateOrderModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const create = useCreateSalesOrder();
  const customers = useCustomers();
  const items = useItems();
  const sets = useItemSets();

  const [customerId, setCustomerId] = useState('');
  const [discount, setDiscount] = useState('');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState([{ product: '', quantity: '1', unitPrice: '' }]);

  /**
   * One key per open form, not per submit.
   *
   * A key regenerated on every click would make a double-click two orders, which is exactly the
   * failure P5-03 exists to prevent. Generated once when the form opens, it survives a retry after
   * a timeout and dies when the form closes.
   */
  const [idempotencyKey] = useState(() => crypto.randomUUID());

  const usable = lines.filter((line) => line.product);

  /** The select carries `item:<id>` or `set:<id>`, so one control covers both kinds. */
  const split = (product: string) => {
    const [kind, id] = product.split(':');
    return kind === 'set' ? { setId: id } : { itemId: id };
  };

  const catalogPrice = (product: string) => {
    const [kind, id] = product.split(':');
    if (kind === 'set') {
      return (sets.data ?? []).find((entry) => entry.id === id)?.setPrice;
    }
    return (items.data ?? []).find((entry) => entry.id === id)?.sellingPrice;
  };

  const total = usable.reduce(
    (sum, line) => sum + Number(line.unitPrice || 0) * Number(line.quantity || 0),
    0,
  );

  return (
    <Modal title={t('New sales order')} onClose={onClose} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          create.mutate(
            {
              idempotencyKey,
              body: {
                customerId,
                discount: discount ? Number(discount) : undefined,
                note: note || undefined,
                lines: usable.map((line) => ({
                  ...split(line.product),
                  quantity: Number(line.quantity),
                  unitPrice: line.unitPrice ? Number(line.unitPrice) : undefined,
                })),
              },
            },
            { onSuccess: onClose },
          );
        }}
      >
        <Field label={t('Buyer')} hint={t('Deactivated buyers are not listed, and are refused server-side too')}>
          <Select required value={customerId} onChange={(event) => setCustomerId(event.target.value)}>
            <option value="">{t('Select a buyer…')}</option>
            {(customers.data ?? []).map((customer) => (
              <option key={customer.id} value={customer.id ?? ''}>
                {customer.code} — {customer.name}
              </option>
            ))}
          </Select>
        </Field>

        <div>
          <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('Lines')}
          </p>
          <div className="flex flex-col gap-2">
            {lines.map((line, index) => (
              <div key={index} className="flex gap-2">
                <Select
                  className="flex-1"
                  value={line.product}
                  onChange={(event) => {
                    const next = [...lines];
                    next[index] = {
                      ...line,
                      product: event.target.value,
                      unitPrice: String(catalogPrice(event.target.value) ?? ''),
                    };
                    setLines(next);
                  }}
                >
                  <option value="">{t('Select a product…')}</option>
                  <optgroup label={t('Items')}>
                    {(items.data ?? []).map((item) => (
                      <option key={item.id} value={`item:${item.id}`}>
                        {item.sku} — {item.name}
                      </option>
                    ))}
                  </optgroup>
                  <optgroup label={t('Sets')}>
                    {(sets.data ?? []).map((set) => (
                      <option key={set.id} value={`set:${set.id}`}>
                        {set.code} — {set.name}
                      </option>
                    ))}
                  </optgroup>
                </Select>
                <Input
                  type="number"
                  min="1"
                  className="nums w-20"
                  title={t('Quantity')}
                  value={line.quantity}
                  onChange={(event) => {
                    const next = [...lines];
                    next[index] = { ...line, quantity: event.target.value };
                    setLines(next);
                  }}
                />
                <Input
                  type="number"
                  step="0.01"
                  min="0"
                  className="nums w-28"
                  title={t('Unit price')}
                  value={line.unitPrice}
                  onChange={(event) => {
                    const next = [...lines];
                    next[index] = { ...line, unitPrice: event.target.value };
                    setLines(next);
                  }}
                />
                <Button
                  type="button"
                  variant="ghost"
                  onClick={() => setLines(lines.filter((_, i) => i !== index))}
                  aria-label={t('Remove')}
                >
                  ✕
                </Button>
              </div>
            ))}
          </div>
          <Button
            type="button"
            size="sm"
            className="mt-2"
            onClick={() => setLines([...lines, { product: '', quantity: '1', unitPrice: '' }])}
          >
            {t('Add line')}
          </Button>
        </div>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('Discount')} hint={t('Comes off the total, never below zero')}>
            <Input
              type="number"
              step="0.01"
              min="0"
              className="nums"
              value={discount}
              onChange={(event) => setDiscount(event.target.value)}
            />
          </Field>
          <Field label={t('Note')}>
            <Input value={note} onChange={(event) => setNote(event.target.value)} />
          </Field>
        </div>

        <div className="nums flex justify-between rounded border border-rule bg-panel2 px-3 py-2 text-sm">
          <span className="text-ink2">{t('Total')}</span>
          <span className="font-semibold text-ink">
            {money(Math.max(0, total - Number(discount || 0)))}
          </span>
        </div>

        <ErrorBanner error={create.error} />

        <p className="text-[11px] text-ink3">
          {t('Placing the order reserves the stock immediately. If any component falls short, nothing is reserved and the order is not created.')}
        </p>

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={create.isPending || !customerId || usable.length === 0}
          >
            {create.isPending ? t('Placing…') : t('Place order and reserve stock')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

// ================================================================== record payment

function RecordPaymentModal({ order, onClose }: { order: SalesOrder; onClose: () => void }) {
  const { t } = useTranslation();
  const record = useRecordPayment();

  const [amount, setAmount] = useState(String(order.total ?? ''));
  const [bankRef, setBankRef] = useState('');
  const [paidOn, setPaidOn] = useState(new Date().toISOString().slice(0, 10));
  const [file, setFile] = useState<File | null>(null);
  const [uploadError, setUploadError] = useState<unknown>(null);
  const [uploading, setUploading] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!file) return;

    setUploading(true);
    setUploadError(null);
    try {
      // Upload first, then reference the stored document. The slip is sanitised on the way in —
      // magic bytes checked, metadata stripped — exactly like a KYC scan.
      const slipDocumentId = await uploadDocument(file, 'bank_slip');
      record.mutate(
        {
          salesOrderId: order.id,
          amount: Number(amount),
          bankRef: bankRef || undefined,
          paidOn,
          slipDocumentId,
        },
        { onSuccess: onClose },
      );
    } catch (error) {
      setUploadError(error);
    } finally {
      setUploading(false);
    }
  }

  return (
    <Modal title={t('Record a payment')} onClose={onClose}>
      <form className="flex flex-col gap-4" onSubmit={(event) => void submit(event)}>
        <p className="text-xs text-ink2">
          {t('Order')} <span className="font-mono text-ink">{order.orderNumber}</span> ·{' '}
          {t('Total')} <span className="nums text-ink">{money(order.total)}</span>
        </p>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('Amount')}>
            <Input
              required
              type="number"
              step="0.01"
              min="0.01"
              className="nums"
              value={amount}
              onChange={(event) => setAmount(event.target.value)}
            />
          </Field>
          <Field label={t('Paid on')}>
            <Input type="date" value={paidOn} onChange={(event) => setPaidOn(event.target.value)} />
          </Field>
        </div>

        <Field
          label={t('Bank reference')}
          hint={t('Type it off the slip. One reference can only ever pay for one order.')}
        >
          <Input
            className="font-mono"
            value={bankRef}
            onChange={(event) => setBankRef(event.target.value)}
            placeholder="TXN99887"
          />
        </Field>

        <Field label={t('Slip image')} hint={t('JPEG, PNG or PDF, up to 10 MB')}>
          <Input
            required
            type="file"
            accept="image/jpeg,image/png,application/pdf"
            onChange={(event) => setFile(event.target.files?.[0] ?? null)}
          />
        </Field>

        <ErrorBanner error={uploadError ?? record.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={uploading || record.isPending || !file}
          >
            {uploading ? t('Uploading…') : t('Record payment')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

// ================================================================== order detail

function OrderDetailModal({ orderId, onClose }: { orderId: string; onClose: () => void }) {
  const { t } = useTranslation();
  const order = useSalesOrder(orderId);
  const orderPayments = useOrderPayments(orderId);
  const invoice = useOrderInvoice(orderId);

  return (
    <Modal title={t('Sales order')} onClose={onClose} wide>
      {order.isLoading ? (
        <Spinner />
      ) : order.error ? (
        <ErrorBanner error={order.error} />
      ) : (
        <div className="flex flex-col gap-5">
          <div className="flex flex-wrap items-center gap-3">
            <span className="font-mono text-sm text-ink">{order.data?.orderNumber}</span>
            <Badge tone={statusTone(order.data?.orderStatus ?? '')}>
              {humanStatus(order.data?.orderStatus)}
            </Badge>
            {order.data?.reservationId ? (
              <span className="text-xs text-ink3">{t('holding stock')}</span>
            ) : (
              <span className="text-xs text-ink3">{t('holding nothing')}</span>
            )}
          </div>

          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('#')}</Th>
                  <Th>{t('Description')}</Th>
                  <Th align="right">{t('Qty')}</Th>
                  <Th align="right">{t('Unit price')}</Th>
                  <Th align="right">{t('Line total')}</Th>
                </tr>
              </thead>
              <tbody>
                {(order.data?.lines ?? []).map((line) => (
                  <tr key={line.id}>
                    <Td className="text-xs text-ink3">{line.lineNo}</Td>
                    <Td className="text-ink">{line.description}</Td>
                    <Td align="right">{line.quantity}</Td>
                    <Td align="right">{money(line.unitPrice)}</Td>
                    <Td align="right">{money(line.lineTotal)}</Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>

          <dl className="nums ml-auto flex w-56 flex-col gap-1 text-sm">
            <div className="flex justify-between">
              <dt className="text-ink2">{t('Subtotal')}</dt>
              <dd>{money(order.data?.subtotal)}</dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-ink2">{t('Discount')}</dt>
              <dd>−{money(order.data?.discount)}</dd>
            </div>
            <div className="flex justify-between border-t border-rule pt-1 font-semibold text-ink">
              <dt>{t('Total')}</dt>
              <dd>{money(order.data?.total)}</dd>
            </div>
          </dl>

          <div>
            <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
              {t('Payments')}
            </p>
            {(orderPayments.data ?? []).length === 0 ? (
              <p className="text-sm text-ink3">{t('None recorded.')}</p>
            ) : (
              <TableWrap>
                <Table>
                  <thead>
                    <tr>
                      <Th>{t('Bank reference')}</Th>
                      <Th align="right">{t('Amount')}</Th>
                      <Th>{t('Status')}</Th>
                      <Th>{t('Reason')}</Th>
                    </tr>
                  </thead>
                  <tbody>
                    {(orderPayments.data ?? []).map((payment) => (
                      <tr key={payment.id}>
                        <Td className="font-mono text-xs">{payment.bankRef ?? '—'}</Td>
                        <Td align="right">{money(payment.amount)}</Td>
                        <Td>
                          <Badge tone={statusTone(payment.paymentStatus ?? '')}>
                            {humanStatus(payment.paymentStatus)}
                          </Badge>
                        </Td>
                        <Td className="text-xs">{payment.rejectionReason ?? '—'}</Td>
                      </tr>
                    ))}
                  </tbody>
                </Table>
              </TableWrap>
            )}
          </div>

          {invoice.data && (
            <div className="rounded border border-ok bg-oksoft px-3 py-2.5">
              <p className="text-sm font-semibold text-ok">
                {t('Invoice')} {invoice.data.invoiceNumber}
              </p>
              <p className="nums mt-0.5 text-xs text-ink2">
                {t('Total')} {money(invoice.data.total)} ·{' '}
                {invoice.data.issuedAt ? new Date(invoice.data.issuedAt).toLocaleString() : ''}
              </p>
            </div>
          )}
        </div>
      )}
    </Modal>
  );
}
