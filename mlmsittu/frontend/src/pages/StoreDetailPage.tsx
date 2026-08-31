import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { useMovements, useStore } from '../api/queries';
import type { StoreItem } from '../api/types';
import {
  AvailabilityBox,
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  Modal,
  PageHeader,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * One store, and everything in it.
 *
 * The counterpart to the stock screen: that one answers "how much of this do we have", totalled
 * across the business; this one answers "what is in this building".
 *
 * History is per item and **scoped to this store**. A single list of everything that ever moved
 * through the building sounds useful and is not — it is the wrong grain for every question anybody
 * actually asks here, which are all of the form "why does this shelf hold this number".
 */
export function StoreDetailPage() {
  const { t } = useTranslation();
  const { id } = useParams<{ id: string }>();

  const store = useStore(id ?? null);
  const [inspecting, setInspecting] = useState<StoreItem | null>(null);

  if (store.isLoading) {
    return <Spinner label={t('Loading store…')} />;
  }

  if (store.error) {
    return (
      <>
        <PageHeader title={t('Store')} />
        <ErrorBanner error={store.error} onRetry={() => void store.refetch()} />
      </>
    );
  }

  const detail = store.data;
  const rows = detail?.items ?? [];

  return (
    <>
      <PageHeader
        title={detail?.name ?? t('Store')}
        description={detail?.address ?? t('No location recorded for this store.')}
        actions={
          <Link
            to="/stores"
            className="rounded-md border border-rule bg-panel px-3 py-1.5 text-[13px] font-medium text-ink2 transition-colors hover:border-brand hover:text-brand"
          >
            {t('All stores')}
          </Link>
        }
      />

      <div className="mb-5 flex flex-wrap items-center gap-2">
        <span className="rounded border border-rule bg-panel2 px-2 py-1 font-mono text-[11px] text-ink2">
          {detail?.code}
        </span>
        {detail?.isDefault && <Badge tone="brand">{t('default store')}</Badge>}
        {detail?.active ? (
          <Badge tone="ok">{t('active')}</Badge>
        ) : (
          <Badge tone="danger">{t('deactivated')}</Badge>
        )}
      </div>

      <Card
        title={t('What is in this store')}
        subtitle={t('{{count}} item(s)', { count: rows.length })}
      >
        {rows.length === 0 ? (
          <EmptyState
            message={t('Nothing is held here yet.')}
            hint={t('Assign a delivery to this store on the Received orders screen.')}
          />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Item code')}</Th>
                  <Th>{t('Item')}</Th>
                  <Th align="right">{t('On hand')}</Th>
                  <Th align="right">{t('Reserved')}</Th>
                  <Th align="right">{t('Available here')}</Th>
                  <Th align="right">{t('Reorder at')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.itemId} className="hover:bg-panel2">
                    <Td className="font-mono text-xs">{row.sku}</Td>
                    <Td>{row.itemName}</Td>
                    <Td align="right">{row.onHand}</Td>
                    <Td align="right" className="text-ink3">
                      {row.reserved}
                    </Td>
                    <Td align="right">
                      <AvailabilityBox available={row.available} size="sm" />
                    </Td>
                    <Td align="right" className="text-ink3">
                      {/* The threshold is the item's, judged on its total everywhere — shown here
                          for reference, not as a verdict on this store's share. */}
                      {row.reorderLevel || '—'}
                    </Td>
                    <Td>
                      <div className="flex justify-end">
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

      {inspecting && detail && (
        <StoreItemHistoryModal
          item={inspecting}
          storeId={detail.id ?? ''}
          storeName={detail.name ?? ''}
          onClose={() => setInspecting(null)}
        />
      )}
    </>
  );
}

/**
 * One item's movements, in this store only.
 *
 * The same item on the stock screen shows every movement everywhere; this shows the subset that
 * happened here. Both are legitimate readings of the same ledger, and the filter is the whole
 * difference between "why do we hold this much" and "why does this shelf hold this much".
 */
function StoreItemHistoryModal({
  item,
  storeId,
  storeName,
  onClose,
}: {
  item: StoreItem;
  storeId: string;
  storeName: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const movements = useMovements(item.itemId ?? undefined, storeId);

  const ledgerSum = (movements.data ?? []).reduce((sum, m) => sum + (m.qtyDelta ?? 0), 0);
  const agrees = ledgerSum === (item.onHand ?? 0);

  return (
    <Modal title={`${item.sku} ${t('in')} ${storeName}`} onClose={onClose} wide>
      {movements.isLoading ? (
        <Spinner />
      ) : movements.error ? (
        <ErrorBanner error={movements.error} onRetry={() => void movements.refetch()} />
      ) : (
        <div className="flex flex-col gap-4">
          {/* These movements are exactly what produced this store's figure, so they must add up to
              it. Showing the comparison makes drift visible here rather than only in a
              reconciliation report nobody runs. */}
          <div
            className={
              'nums flex items-center justify-between rounded border px-3 py-2 text-sm ' +
              (agrees ? 'border-ok bg-oksoft' : 'border-danger bg-dangersoft')
            }
          >
            <span className="text-ink2">{t('Movements here vs on hand here')}</span>
            <span className="font-semibold text-ink">
              {ledgerSum} / {item.onHand} {agrees ? '✓' : `— ${t('drift')}`}
            </span>
          </div>

          {(movements.data ?? []).length === 0 ? (
            <EmptyState message={t('Nothing has moved through this store for this item.')} />
          ) : (
            <TableWrap>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('Type')}</Th>
                    <Th align="right">{t('Change')}</Th>
                    <Th>{t('Detail')}</Th>
                  </tr>
                </thead>
                <tbody>
                  {(movements.data ?? []).map((movement) => (
                    <tr key={movement.id}>
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
                        {/* Note first: the sentence written when the stock moved. Reason next, for
                            adjustments. The raw reference is the last resort, and only movements
                            recorded before notes existed still fall through to it. */}
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
          )}

          <div className="flex justify-end">
            <Button type="button" variant="ghost" onClick={onClose}>
              {t('Close')}
            </Button>
          </div>
        </div>
      )}
    </Modal>
  );
}
