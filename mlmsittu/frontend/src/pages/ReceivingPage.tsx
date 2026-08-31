import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  useGoodsReceipts,
  useItems,
  useLocations,
  useOrdersAwaitingStoring,
  usePurchaseOrder,
  useReceiveGoods,
  useReceiveGoodsManually,
  useSuppliers,
} from '../api/queries';
import type { GoodsReceipt, PurchaseOrder } from '../api/types';
import { useAuth } from '../auth/AuthContext';
import { ItemPicker } from '../components/ItemPicker';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  humanStatus,
  Input,
  Modal,
  PageHeader,
  Select,
  Spinner,
  statusTone,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Receiving: the two halves of a delivery.
 *
 * The client's warehouse signs for a lorry at the door and unpacks it later, sometimes hours
 * later, sometimes into two different stores. The system used to collapse both into one button,
 * which meant stock was reported on a shelf while it was still in a box on the receiving bay.
 *
 * So there are two tabs, and they are two different questions:
 *
 * - **Received orders** — signed for, not yet put away. Every row here is work outstanding.
 * - **Added to stores** — done. Stock moved when the row appeared, and not before.
 *
 * Nothing on the first tab has changed a single stock figure. That is the point of it.
 */
export function ReceivingPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canStore = hasRole('INVENTORY_CLERK');

  const [tab, setTab] = useState<'awaiting' | 'stored'>('awaiting');
  const [storingOrderId, setStoringOrderId] = useState<string | null>(null);
  const [manual, setManual] = useState(false);

  const awaiting = useOrdersAwaitingStoring();
  const receipts = useGoodsReceipts();

  const awaitingCount = (awaiting.data ?? []).length;

  return (
    <>
      <PageHeader
        title={t('Received orders')}
        description={t('Deliveries that have been signed for, and deliveries that have been put away. Stock moves at the second step, never the first.')}
        actions={
          canStore && (
            <Button variant="primary" size="sm" onClick={() => setManual(true)}>
              {t('Add stock manually')}
            </Button>
          )
        }
      />

      {/* Tabs, not two pages. The whole value is being able to see the backlog and the history
          side by side without losing your place. */}
      <div className="mb-5 flex gap-1 border-b border-rule">
        <TabButton active={tab === 'awaiting'} onClick={() => setTab('awaiting')} count={awaitingCount}>
          {t('Received orders')}
        </TabButton>
        <TabButton active={tab === 'stored'} onClick={() => setTab('stored')}>
          {t('Added to stores')}
        </TabButton>
      </div>

      {tab === 'awaiting' ? (
        <Card
          title={t('Waiting to be put into a store')}
          subtitle={t('Nothing here has changed a stock figure yet.')}
        >
          {awaiting.isLoading ? (
            <Spinner />
          ) : awaiting.error ? (
            <div className="p-4">
              <ErrorBanner error={awaiting.error} onRetry={() => void awaiting.refetch()} />
            </div>
          ) : awaitingCount === 0 ? (
            <EmptyState
              message={t('No deliveries waiting.')}
              hint={t('An order appears here once somebody confirms on the Create order screen that it arrived.')}
            />
          ) : (
            <AwaitingTable
              orders={awaiting.data ?? []}
              canStore={canStore}
              onStore={setStoringOrderId}
            />
          )}
        </Card>
      ) : (
        <Card title={t('Added to stores')} subtitle={t('Newest first. Includes manual entries.')}>
          {receipts.isLoading ? (
            <Spinner />
          ) : receipts.error ? (
            <div className="p-4">
              <ErrorBanner error={receipts.error} onRetry={() => void receipts.refetch()} />
            </div>
          ) : (receipts.data ?? []).length === 0 ? (
            <EmptyState message={t('Nothing has been put into a store yet.')} />
          ) : (
            <StoredTable receipts={receipts.data ?? []} />
          )}
        </Card>
      )}

      {storingOrderId && (
        <AddToStoresModal
          purchaseOrderId={storingOrderId}
          onClose={() => setStoringOrderId(null)}
        />
      )}
      {manual && <ManualEntryModal onClose={() => setManual(false)} />}
    </>
  );
}

function TabButton({
  active,
  count,
  onClick,
  children,
}: {
  active: boolean;
  count?: number;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-current={active ? 'page' : undefined}
      className={
        'relative -mb-px flex items-center gap-2 px-4 py-2.5 text-[13px] transition-colors ' +
        (active
          ? 'border-b-2 border-brand font-semibold text-brand'
          : 'border-b-2 border-transparent text-ink2 hover:text-brand')
      }
    >
      {children}
      {/* The count is only ever shown when there is work to do. A "0" badge is noise. */}
      {count != null && count > 0 && (
        <span className="nums rounded-full bg-warnsoft px-1.5 py-0.5 text-[10px] font-semibold text-warn">
          {count}
        </span>
      )}
    </button>
  );
}

function AwaitingTable({
  orders,
  canStore,
  onStore,
}: {
  orders: PurchaseOrder[];
  canStore: boolean;
  onStore: (id: string) => void;
}) {
  const { t } = useTranslation();
  const suppliers = useSuppliers(true);

  const supplierName = (id: string | undefined) =>
    (suppliers.data ?? []).find((supplier) => supplier.id === id)?.name ?? '—';

  return (
    <TableWrap>
      <Table>
        <thead>
          <tr>
            <Th>{t('Number')}</Th>
            <Th>{t('Supplier')}</Th>
            <Th>{t('Status')}</Th>
            <Th>{t('Signed for by')}</Th>
            <Th align="right">{t('Still to store')}</Th>
            <Th>{''}</Th>
          </tr>
        </thead>
        <tbody>
          {orders.map((order) => {
            const outstanding = (order.lines ?? []).reduce(
              (sum, line) => sum + (line.outstanding ?? 0),
              0,
            );
            return (
              <tr key={order.id} className="hover:bg-panel2">
                <Td className="font-mono text-xs">{order.poNumber}</Td>
                <Td>{supplierName(order.supplierId)}</Td>
                <Td>
                  <Badge tone={statusTone(order.status ?? '')}>{humanStatus(order.status)}</Badge>
                </Td>
                <Td className="text-xs">
                  <p className="text-ink">{order.arrivalAttestedName ?? '—'}</p>
                  <p className="text-ink3">
                    {order.arrivedAt ? new Date(order.arrivedAt).toLocaleString() : ''}
                  </p>
                </Td>
                <Td align="right" className="font-semibold">
                  {outstanding}
                </Td>
                <Td>
                  <div className="flex justify-end">
                    {canStore && (
                      <Button size="sm" variant="primary" onClick={() => order.id && onStore(order.id)}>
                        {t('Add to stores')}
                      </Button>
                    )}
                  </div>
                </Td>
              </tr>
            );
          })}
        </tbody>
      </Table>
    </TableWrap>
  );
}

function StoredTable({ receipts }: { receipts: GoodsReceipt[] }) {
  const { t } = useTranslation();
  const suppliers = useSuppliers(true);
  const items = useItems();
  const locations = useLocations();

  const supplierName = (id: string | undefined) =>
    (suppliers.data ?? []).find((supplier) => supplier.id === id)?.name ?? '—';
  const itemLabel = (id: string | undefined) => {
    const item = (items.data ?? []).find((candidate) => candidate.id === id);
    return item ? `${item.sku} — ${item.name}` : '—';
  };
  const storeName = (id: string | undefined) =>
    (locations.data ?? []).find((location) => location.id === id)?.name ?? '—';

  return (
    <TableWrap>
      <Table>
        <thead>
          <tr>
            <Th>{t('Receipt')}</Th>
            <Th>{t('Source')}</Th>
            <Th>{t('Supplier')}</Th>
            <Th>{t('What went where')}</Th>
            <Th>{t('When')}</Th>
          </tr>
        </thead>
        <tbody>
          {receipts.map((receipt) => (
            <tr key={receipt.id} className="align-top hover:bg-panel2">
              <Td className="font-mono text-xs">{receipt.receiptNumber}</Td>
              <Td>
                <Badge tone={receipt.source === 'manual' ? 'neutral' : 'brand'}>
                  {receipt.source === 'manual' ? t('manual') : t('purchase order')}
                </Badge>
              </Td>
              <Td>{supplierName(receipt.supplierId)}</Td>
              <Td className="text-xs">
                {/* One row per line, because the store is a per-line fact. Rolling it up to the
                    receipt is exactly the collapse this feature exists to undo. */}
                <ul className="flex flex-col gap-0.5">
                  {(receipt.lines ?? []).map((line) => (
                    <li key={line.id} className="flex flex-wrap items-baseline gap-x-2">
                      <span className="nums font-semibold text-ink">{line.quantityReceived}</span>
                      <span className="text-ink2">{itemLabel(line.itemId)}</span>
                      <span className="text-ink3">→ {storeName(line.locationId)}</span>
                    </li>
                  ))}
                </ul>
                {receipt.supplierNote && (
                  <p className="mt-1 text-ink3 italic">{receipt.supplierNote}</p>
                )}
              </Td>
              <Td className="text-xs text-ink3">
                {receipt.receivedAt ? new Date(receipt.receivedAt).toLocaleString() : '—'}
              </Td>
            </tr>
          ))}
        </tbody>
      </Table>
    </TableWrap>
  );
}

/**
 * Putting an arrived order away.
 *
 * Two things are set per line: how many actually made it, and which store they went into. The
 * store defaults to one chosen for the whole delivery, because most of the time every line goes to
 * the same place and typing it once is the honest shape of the task.
 */
function AddToStoresModal({
  purchaseOrderId,
  onClose,
}: {
  purchaseOrderId: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const order = usePurchaseOrder(purchaseOrderId);
  const items = useItems();
  const locations = useLocations();
  const store = useReceiveGoods();

  const activeStores = (locations.data ?? []).filter((location) => location.active);
  const defaultStoreId =
    activeStores.find((location) => location.isDefault)?.id ?? activeStores[0]?.id ?? '';

  const [receiptStore, setReceiptStore] = useState('');
  const [quantities, setQuantities] = useState<Record<string, string>>({});
  const [lineStores, setLineStores] = useState<Record<string, string>>({});

  const fallbackStore = receiptStore || defaultStoreId;

  const itemLabel = (id: string | undefined) => {
    const item = (items.data ?? []).find((candidate) => candidate.id === id);
    return item ? `${item.sku} — ${item.name}` : (id ?? '').slice(0, 8);
  };

  const open = ((order.data as PurchaseOrder | undefined)?.lines ?? []).filter(
    (line) => (line.outstanding ?? 0) > 0,
  );

  const entered = open
    .map((line) => ({ line, quantity: Number(quantities[line.id ?? ''] ?? 0) }))
    .filter((row) => row.quantity > 0);

  const overReceipt = entered.some((row) => row.quantity > (row.line.outstanding ?? 0));

  return (
    <Modal title={t('Add to stores')} onClose={onClose} wide>
      {order.isLoading ? (
        <Spinner />
      ) : (
        <form
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            store.mutate(
              {
                purchaseOrderId,
                locationId: fallbackStore || undefined,
                lines: entered.map((row) => ({
                  purchaseOrderLineId: row.line.id,
                  quantity: row.quantity,
                  locationId: lineStores[row.line.id ?? ''] || undefined,
                })),
              },
              { onSuccess: onClose },
            );
          }}
        >
          <p className="text-xs text-ink2">
            {t('This is the step that raises stock. Enter what actually went onto a shelf — a part delivery is normal, and the line stays open until it is complete.')}
          </p>

          <Field
            label={t('Store for this delivery')}
            hint={t('Used for any line that does not pick its own below')}
          >
            <Select
              value={fallbackStore}
              onChange={(event) => setReceiptStore(event.target.value)}
            >
              {activeStores.map((location) => (
                <option key={location.id} value={location.id ?? ''}>
                  {location.name}
                  {location.isDefault ? ` (${t('default')})` : ''}
                </option>
              ))}
            </Select>
          </Field>

          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Item')}</Th>
                  <Th align="right">{t('Ordered')}</Th>
                  <Th align="right">{t('Already stored')}</Th>
                  <Th align="right">{t('Outstanding')}</Th>
                  <Th align="right">{t('Storing now')}</Th>
                  <Th>{t('Into store')}</Th>
                </tr>
              </thead>
              <tbody>
                {open.map((line) => {
                  const id = line.id ?? '';
                  const value = quantities[id] ?? '';
                  const tooMany = Number(value) > (line.outstanding ?? 0);
                  return (
                    <tr key={id}>
                      <Td className="text-xs">{itemLabel(line.itemId)}</Td>
                      <Td align="right">{line.quantityOrdered}</Td>
                      <Td align="right">{line.quantityReceived}</Td>
                      <Td align="right" className="font-semibold">
                        {line.outstanding}
                      </Td>
                      <Td align="right">
                        <Input
                          type="number"
                          min="0"
                          max={line.outstanding ?? 0}
                          className={'nums w-24 ' + (tooMany ? 'border-danger' : '')}
                          aria-label={t('Quantity going into a store')}
                          value={value}
                          onChange={(event) =>
                            setQuantities({ ...quantities, [id]: event.target.value })
                          }
                        />
                      </Td>
                      <Td>
                        <Select
                          className="w-44"
                          aria-label={t('Store for this line')}
                          value={lineStores[id] ?? ''}
                          onChange={(event) =>
                            setLineStores({ ...lineStores, [id]: event.target.value })
                          }
                        >
                          <option value="">{t('Same as delivery')}</option>
                          {activeStores.map((location) => (
                            <option key={location.id} value={location.id ?? ''}>
                              {location.name}
                            </option>
                          ))}
                        </Select>
                      </Td>
                    </tr>
                  );
                })}
              </tbody>
            </Table>
          </TableWrap>

          <ErrorBanner error={store.error} />

          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={onClose}>
              {t('Cancel')}
            </Button>
            <Button
              type="submit"
              variant="primary"
              disabled={store.isPending || entered.length === 0 || overReceipt}
            >
              {store.isPending ? t('Adding…') : t('Add to stores')}
            </Button>
          </div>
        </form>
      )}
    </Modal>
  );
}

/**
 * Stock that arrived without an order.
 *
 * Supplier and store are both required, and so is a real item from the catalogue. The temptation
 * with a manual form is to let people type a name; that produces stock nobody can sell, attached
 * to nothing, and it is the exact hole the ledger exists to close.
 */
function ManualEntryModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const suppliers = useSuppliers();
  const locations = useLocations();
  const items = useItems();
  const receive = useReceiveGoodsManually();

  const activeStores = useMemo(
    () => (locations.data ?? []).filter((location) => location.active),
    [locations.data],
  );
  const defaultStoreId =
    activeStores.find((location) => location.isDefault)?.id ?? activeStores[0]?.id ?? '';

  const [supplierId, setSupplierId] = useState('');
  const [storeId, setStoreId] = useState('');
  const [note, setNote] = useState('');
  const [lines, setLines] = useState([{ itemId: '', quantity: '1', locationId: '' }]);

  const chosenStore = storeId || defaultStoreId;
  const usable = lines.filter((line) => line.itemId && Number(line.quantity) > 0);
  const complete = Boolean(supplierId) && Boolean(chosenStore) && usable.length > 0;

  const update = (index: number, patch: Partial<(typeof lines)[number]>) =>
    setLines(lines.map((line, i) => (i === index ? { ...line, ...patch } : line)));

  return (
    <Modal title={t('Add stock manually')} onClose={onClose} wide>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          receive.mutate(
            {
              supplierId,
              locationId: chosenStore,
              supplierNote: note || undefined,
              lines: usable.map((line) => ({
                itemId: line.itemId,
                quantity: Number(line.quantity),
                locationId: line.locationId || undefined,
              })),
            },
            { onSuccess: onClose },
          );
        }}
      >
        <p className="text-xs text-ink2">
          {t('For goods that turned up with no purchase order behind them. This raises stock immediately, and the entry appears in “Added to stores” alongside the ordered ones.')}
        </p>

        <div className="grid gap-4 sm:grid-cols-2">
          <Field label={t('Supplier')} hint={t('Who it came from — required')}>
            <Select
              required
              value={supplierId}
              onChange={(event) => setSupplierId(event.target.value)}
            >
              <option value="">{t('Select a supplier…')}</option>
              {(suppliers.data ?? []).map((supplier) => (
                <option key={supplier.id} value={supplier.id ?? ''}>
                  {supplier.code} — {supplier.name}
                </option>
              ))}
            </Select>
          </Field>
          <Field label={t('Store')} hint={t('Used for any line that does not pick its own')}>
            <Select required value={chosenStore} onChange={(event) => setStoreId(event.target.value)}>
              {activeStores.map((location) => (
                <option key={location.id} value={location.id ?? ''}>
                  {location.name}
                  {location.isDefault ? ` (${t('default')})` : ''}
                </option>
              ))}
            </Select>
          </Field>
        </div>

        <div>
          <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('What arrived')}
          </p>
          <div className="flex flex-col gap-2">
            {lines.map((line, index) => (
              <div key={index} className="flex flex-wrap items-start gap-2">
                <div className="min-w-[16rem] flex-1">
                  <ItemPicker
                    items={items.data ?? []}
                    value={line.itemId}
                    onChange={(itemId) => update(index, { itemId })}
                    placeholder={t('Search by name or item code…')}
                    autoFocus={index === 0}
                  />
                </div>
                <Input
                  type="number"
                  min="1"
                  className="nums w-20"
                  aria-label={t('Quantity')}
                  value={line.quantity}
                  onChange={(event) => update(index, { quantity: event.target.value })}
                />
                <Select
                  className="w-44"
                  aria-label={t('Store for this line')}
                  value={line.locationId}
                  onChange={(event) => update(index, { locationId: event.target.value })}
                >
                  <option value="">{t('Same as above')}</option>
                  {activeStores.map((location) => (
                    <option key={location.id} value={location.id ?? ''}>
                      {location.name}
                    </option>
                  ))}
                </Select>
                <Button
                  type="button"
                  variant="ghost"
                  aria-label={t('Remove')}
                  onClick={() => setLines(lines.filter((_, i) => i !== index))}
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
            onClick={() => setLines([...lines, { itemId: '', quantity: '1', locationId: '' }])}
          >
            {t('Add item')}
          </Button>
        </div>

        <Field label={t('Note')} hint={t('Optional — a delivery note number, or why this was manual')}>
          <Input value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>

        <ErrorBanner error={receive.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={receive.isPending || !complete}>
            {receive.isPending ? t('Adding…') : t('Add to stores')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
