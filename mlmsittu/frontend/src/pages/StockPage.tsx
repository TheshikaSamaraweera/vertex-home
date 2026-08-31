import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  useAdjustStock,
  useMovements,
  useReconcileStock,
  useReorderAlerts,
  useRunReorderScan,
  useStockByItem,
} from '../api/queries';
import type { StockByItem, StockInStore } from '../api/types';
import {
  AvailabilityBox,
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Field,
  Input,
  Modal,
  PageHeader,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';
import { useAuth } from '../auth/AuthContext';

/**
 * Stock, one row per item.
 *
 * It used to be one row per item **and store**, which was tolerable with a single store and wrong
 * the moment there were two: the same item appeared several times, each showing a slice of its
 * quantity, and a delivery split across two stores read as a duplicate entry that was somehow
 * short in both places. "How much of this do we have" is a question about the business; where it
 * sits is a different question, and **Stores** on each row is where that one gets answered.
 *
 * On hand, reserved and available remain genuinely different quantities, and conflating them is
 * how a warehouse promises the same units twice. Reservation increments `reserved` and leaves
 * `on hand` alone until fulfilment (architecture 4.5).
 */
export function StockPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const canWrite = hasRole('INVENTORY_CLERK');
  const canReconcile = hasRole('SUPER_ADMIN');

  const stock = useStockByItem();
  const alerts = useReorderAlerts(true);
  const reconcile = useReconcileStock();
  const reorderScan = useRunReorderScan();

  const [search, setSearch] = useState('');
  const [viewingStores, setViewingStores] = useState<StockByItem | null>(null);
  const [inspecting, setInspecting] = useState<StockByItem | null>(null);
  const [reconcileReport, setReconcileReport] = useState<string | null>(null);

  const rows = (stock.data ?? []).filter((row) => {
    if (!search) return true;
    const needle = search.toLowerCase();
    return (
      (row.sku ?? '').toLowerCase().includes(needle) ||
      (row.itemName ?? '').toLowerCase().includes(needle)
    );
  });

  return (
    <>
      <PageHeader
        title={t('Stock')}
        description={t('Totals across every store. On hand is what is physically present; available is what is left after anything already promised to an order.')}
        actions={
          <>
            <Button
              size="sm"
              onClick={() => reorderScan.mutate(undefined)}
              disabled={reorderScan.isPending}
            >
              {t('Run reorder scan')}
            </Button>
            {canReconcile && (
              <Button
                size="sm"
                onClick={() =>
                  reconcile.mutate(undefined, {
                    onSuccess: (report) => {
                      const typed = report as { rowsChecked: number; discrepanciesFound: number };
                      setReconcileReport(
                        t('Checked {{rows}} rows, repaired {{found}}.', {
                          rows: typed.rowsChecked,
                          found: typed.discrepanciesFound,
                        }),
                      );
                    },
                  })
                }
                disabled={reconcile.isPending}
              >
                {t('Rebuild from ledger')}
              </Button>
            )}
          </>
        }
      />

      {reconcile.error && (
        <div className="mb-4">
          <ErrorBanner error={reconcile.error} />
        </div>
      )}
      {reconcileReport && (
        <div className="mb-4 rounded border border-ok bg-oksoft px-4 py-2.5 text-sm text-ink">
          {reconcileReport}
        </div>
      )}

      {(alerts.data ?? []).length > 0 && (
        <div className="mb-5 rounded border border-danger bg-dangersoft px-4 py-3">
          <p className="text-sm font-semibold text-danger">
            {t('{{count}} item(s) at or below reorder level', { count: alerts.data?.length ?? 0 })}
          </p>
          {/* Counted across every store. An item with three in one place and four in another is
              not short, and used to be listed twice here as though it were short twice. */}
          <ul className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-xs text-ink2">
            {(alerts.data ?? []).map((alert) => (
              <li key={alert.id} className="nums">
                <span className="font-mono">{alert.sku}</span> — {alert.onHandAtDetection}{' '}
                {t('of')} {alert.reorderLevel}
              </li>
            ))}
          </ul>
        </div>
      )}

      <Card
        title={t('Stock levels')}
        subtitle={t('{{count}} item(s), totalled across all stores', { count: rows.length })}
        actions={
          <Input
            className="w-64"
            type="search"
            aria-label={t('Search stock')}
            placeholder={t('Search Item code or item name…')}
            value={search}
            onChange={(event) => setSearch(event.target.value)}
          />
        }
      >
        {stock.isLoading ? (
          <Spinner />
        ) : stock.error ? (
          <div className="p-4">
            <ErrorBanner error={stock.error} onRetry={() => void stock.refetch()} />
          </div>
        ) : rows.length === 0 ? (
          <EmptyState
            message={search ? t('No items match.') : t('No stock positions yet.')}
            hint={search ? undefined : t('Receive a purchase order or post an adjustment.')}
          />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Item code')}</Th>
                  <Th>{t('Item')}</Th>
                  <Th align="right">{t('On hand')}</Th>
                  {/* Reserved is not shown: available is already on hand less reserved, so the
                      figure that matters is here and the subtraction is not the reader's job. */}
                  <Th align="right">{t('Available')}</Th>
                  <Th align="right">{t('Reorder at')}</Th>
                  <Th>{t('Status')}</Th>
                  <Th align="right">{t('Stores')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.itemId} className="hover:bg-panel2">
                    <Td className="font-mono text-xs">{row.sku}</Td>
                    <Td>{row.itemName}</Td>
                    <Td align="right">{row.onHand}</Td>
                    <Td align="right">
                      <AvailabilityBox available={row.available} />
                    </Td>
                    <Td align="right" className="text-ink3">
                      {row.reorderLevel || '—'}
                    </Td>
                    <Td>
                      {row.belowReorderLevel ? (
                        <Badge tone="danger">{t('reorder')}</Badge>
                      ) : (
                        <Badge tone="ok">{t('ok')}</Badge>
                      )}
                    </Td>
                    <Td align="right" className="nums text-ink3">
                      {row.storeCount}
                    </Td>
                    <Td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" onClick={() => setViewingStores(row)}>
                          {t('View stores')}
                        </Button>
                        <Button size="sm" variant="ghost" onClick={() => setInspecting(row)}>
                          {t('History')}
                        </Button>
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      {viewingStores && (
        <StoreBreakdownModal
          row={viewingStores}
          canAdjust={canWrite}
          onClose={() => setViewingStores(null)}
        />
      )}
      {inspecting && <MovementsModal row={inspecting} onClose={() => setInspecting(null)} />}
    </>
  );
}

/**
 * Where one item actually sits.
 *
 * Adjustment lives here rather than on the row, and that is not a demotion — an adjustment has to
 * name a store. Offering it beside a total would mean guessing which shelf the correction belongs
 * to, which is exactly the mistake that produces a second wrong number.
 */
function StoreBreakdownModal({
  row,
  canAdjust,
  onClose,
}: {
  row: StockByItem;
  canAdjust: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [adjusting, setAdjusting] = useState<StockInStore | null>(null);

  return (
    <>
      <Modal title={`${t('Where it is')} — ${row.sku}`} onClose={onClose} wide>
        <div className="flex flex-col gap-4">
          <div className="nums flex flex-wrap items-center justify-between gap-3 rounded border border-rule bg-panel2 px-3 py-2 text-sm">
            <span className="text-ink2">{row.itemName}</span>
            <span className="text-ink">
              {t('{{onHand}} on hand across {{stores}} store(s)', {
                onHand: row.onHand,
                stores: row.storeCount,
              })}
            </span>
          </div>

          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Store')}</Th>
                  <Th align="right">{t('On hand')}</Th>
                  <Th align="right">{t('Reserved')}</Th>
                  <Th align="right">{t('Available')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {(row.stores ?? []).map((store) => (
                  <tr key={store.locationId} className={store.locationActive ? '' : 'opacity-60'}>
                    <Td>
                      <Link
                        to={`/stores/${store.locationId}`}
                        className="font-medium text-brand hover:underline"
                      >
                        {store.locationName}
                      </Link>
                      <span className="ml-2 font-mono text-[11px] text-ink3">
                        {store.locationCode}
                      </span>
                      {/* Stock in a deactivated store is real but is not counted as cover — it
                          would be a lie to call it available to fulfil anything. */}
                      {!store.locationActive && (
                        <span className="ml-2">
                          <Badge tone="danger">{t('deactivated')}</Badge>
                        </span>
                      )}
                    </Td>
                    <Td align="right">{store.onHand}</Td>
                    <Td align="right" className="text-ink3">
                      {store.reserved}
                    </Td>
                    <Td align="right">
                      <AvailabilityBox available={store.available} size="sm" />
                    </Td>
                    <Td>
                      <div className="flex justify-end">
                        {canAdjust && (
                          <Button size="sm" onClick={() => setAdjusting(store)}>
                            {t('Adjust')}
                          </Button>
                        )}
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>

          <div className="flex justify-end">
            <Button type="button" variant="ghost" onClick={onClose}>
              {t('Close')}
            </Button>
          </div>
        </div>
      </Modal>

      {adjusting && (
        <AdjustModal
          itemId={row.itemId ?? ''}
          sku={row.sku ?? ''}
          store={adjusting}
          onClose={() => setAdjusting(null)}
        />
      )}
    </>
  );
}

function AdjustModal({
  itemId,
  sku,
  store,
  onClose,
}: {
  itemId: string;
  sku: string;
  store: StockInStore;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const adjust = useAdjustStock();
  const [qtyDelta, setQtyDelta] = useState('');
  const [reason, setReason] = useState('');
  const [note, setNote] = useState('');

  const parsed = Number(qtyDelta);
  const resulting = (store.onHand ?? 0) + (Number.isFinite(parsed) ? parsed : 0);

  return (
    <Modal title={`${t('Adjust stock')} — ${sku} @ ${store.locationName}`} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          adjust.mutate(
            {
              itemId,
              locationId: store.locationId,
              qtyDelta: parsed,
              reason,
              note: note || undefined,
            },
            { onSuccess: onClose },
          );
        }}
      >
        <Field
          label={t('Quantity change')}
          hint={t('Negative to reduce. Zero is rejected — it records nothing.')}
        >
          <Input
            type="number"
            required
            autoFocus
            className="nums"
            value={qtyDelta}
            onChange={(event) => setQtyDelta(event.target.value)}
          />
        </Field>

        {/* The result is shown before submitting because a negative balance is refused outright,
            and finding that out after typing is a poor way to learn it. */}
        <div className="nums flex items-center justify-between rounded border border-rule bg-panel2 px-3 py-2 text-sm">
          <span className="text-ink2">{t('Resulting on hand in this store')}</span>
          <span className={resulting < 0 ? 'font-bold text-danger' : 'font-semibold text-ink'}>
            {store.onHand} → {resulting}
          </span>
        </div>
        {resulting < 0 && (
          <p className="text-xs text-danger">{t('Stock cannot go below zero. This will be refused.')}</p>
        )}
        {resulting >= 0 && resulting < (store.reserved ?? 0) && (
          <p className="text-xs text-danger">
            {t('That is below the {{reserved}} already reserved. Release reservations first.', {
              reserved: store.reserved,
            })}
          </p>
        )}

        <Field
          label={t('Reason')}
          hint={t('Required. An unexplained change to a financial record is not acceptable.')}
        >
          <Input
            required
            maxLength={64}
            placeholder={t('damage, stock count, restock…')}
            value={reason}
            onChange={(event) => setReason(event.target.value)}
          />
        </Field>

        <Field label={t('Note')}>
          <Input maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>

        <ErrorBanner error={adjust.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={adjust.isPending}>
            {adjust.isPending ? t('Posting…') : t('Post adjustment')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/**
 * Every movement of one item, across every store.
 *
 * The **Detail** column is the point of it: a receipt now writes its own description into the
 * ledger at the moment the stock moves, so a row reads "GRN-000004 · Received on PO-000012 from
 * Acme Traders" rather than a document type and eight characters of a UUID.
 */
export function MovementsModal({ row, onClose }: { row: StockByItem; onClose: () => void }) {
  const { t } = useTranslation();
  const movements = useMovements(row.itemId ?? undefined);

  const storeName = (locationId: string | undefined) =>
    (row.stores ?? []).find((store) => store.locationId === locationId)?.locationName ?? '—';

  const ledgerSum = (movements.data ?? []).reduce((sum, m) => sum + (m.qtyDelta ?? 0), 0);
  // Compared against the sum over every store, because that is what these movements add up to.
  const stored = (row.stores ?? []).reduce((sum, store) => sum + (store.onHand ?? 0), 0);
  const agrees = ledgerSum === stored;

  return (
    <Modal title={`${t('Movement history')} — ${row.sku}`} onClose={onClose} wide>
      {movements.isLoading ? (
        <Spinner />
      ) : (
        <>
          {/* The ledger is the source of truth and stock_level is a projection of it. Showing the
              replay total next to the stored figure means drift is visible here rather than only
              in a reconciliation report nobody runs. */}
          <div
            className={
              'nums mb-4 flex items-center justify-between rounded border px-3 py-2 text-sm ' +
              (agrees ? 'border-ok bg-oksoft' : 'border-danger bg-dangersoft')
            }
          >
            <span className="text-ink2">{t('Ledger sum vs stored level, all stores')}</span>
            <span className="font-semibold text-ink">
              {ledgerSum} / {stored} {agrees ? '✓' : `— ${t('drift')}`}
            </span>
          </div>

          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Store')}</Th>
                  <Th>{t('Type')}</Th>
                  <Th align="right">{t('Change')}</Th>
                  <Th>{t('Detail')}</Th>
                </tr>
              </thead>
              <tbody>
                {(movements.data ?? []).map((movement) => (
                  <tr key={movement.id}>
                    <Td className="text-xs">{storeName(movement.locationId)}</Td>
                    <Td>
                      <span className="font-mono text-[11px]">{movement.type}</span>
                    </Td>
                    <Td align="right">
                      <span
                        className={
                          (movement.qtyDelta ?? 0) > 0
                            ? 'font-medium text-ok'
                            : 'font-medium text-danger'
                        }
                      >
                        {(movement.qtyDelta ?? 0) > 0 ? '+' : ''}
                        {movement.qtyDelta}
                      </span>
                    </Td>
                    <Td className="text-xs">
                      {/* Note first: it is the sentence written when the stock moved. Reason next,
                          for adjustments. The reference is the last resort, and only older rows
                          still fall through to it. */}
                      {movement.note ??
                        movement.reason ??
                        (movement.referenceType
                          ? `${movement.referenceType} ${String(movement.referenceId ?? '').slice(0, 8)}`
                          : '—')}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        </>
      )}
    </Modal>
  );
}
