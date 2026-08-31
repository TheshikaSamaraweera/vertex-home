import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { useIssueRewardPack, useRewardEntitlements } from '../api/queries';
import type { RewardEntitlement, RewardStoreOption } from '../api/types';
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
  Select,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Item packs earned by completing all four referral stages.
 *
 * This is the whole of what §0.2's mechanic grants: refer four people, and the pack you chose when
 * you registered becomes yours to collect. No money moves, and nothing is handed over
 * automatically — an administrator issues it out of a named store, which is what keeps the
 * separation of duties real once the mechanic actually pays out.
 *
 * The screen exists to answer one question before that decision: **can this pack actually be
 * filled, and from where.** A queue that says somebody is owed something without saying whether it
 * is on a shelf just moves the problem to the warehouse door.
 */
export function RewardsPage() {
  const { t } = useTranslation();

  const [tab, setTab] = useState<'eligible' | 'issued'>('eligible');
  const waiting = useRewardEntitlements('eligible');
  const issued = useRewardEntitlements('issued');

  const [issuing, setIssuing] = useState<RewardEntitlement | null>(null);
  const [viewing, setViewing] = useState<RewardEntitlement | null>(null);

  const source = tab === 'eligible' ? waiting : issued;
  const rows = source.data ?? [];

  return (
    <>
      <PageHeader
        title={t('Reward packs')}
        description={t('Customers who have completed all four referral stages, and the pack each one chose when they registered.')}
      />

      <div className="mb-5 flex gap-1 border-b border-rule">
        <TabButton
          active={tab === 'eligible'}
          count={(waiting.data ?? []).length}
          onClick={() => setTab('eligible')}
        >
          {t('Waiting to be issued')}
        </TabButton>
        <TabButton active={tab === 'issued'} onClick={() => setTab('issued')}>
          {t('Already issued')}
        </TabButton>
      </div>

      <Card
        title={tab === 'eligible' ? t('Earned, not yet collected') : t('Handed over')}
        subtitle={
          tab === 'eligible'
            ? t('No stock has moved for any of these.')
            : t('Stock left a store when each of these was issued.')
        }
      >
        {source.isLoading ? (
          <Spinner />
        ) : source.error ? (
          <div className="p-4">
            <ErrorBanner error={source.error} onRetry={() => void source.refetch()} />
          </div>
        ) : rows.length === 0 ? (
          <EmptyState
            message={
              tab === 'eligible' ? t('Nobody is waiting for a pack.') : t('No packs issued yet.')
            }
            hint={
              tab === 'eligible'
                ? t('A customer appears here the moment their fourth referral is approved.')
                : undefined
            }
          />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Business ID')}</Th>
                  <Th>{t('Customer')}</Th>
                  <Th>{t('Pack')}</Th>
                  {tab === 'eligible' ? (
                    <>
                      <Th>{t('Can be filled')}</Th>
                      <Th>{t('Eligible since')}</Th>
                    </>
                  ) : (
                    <>
                      <Th>{t('Issued from')}</Th>
                      <Th>{t('Issued')}</Th>
                    </>
                  )}
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <tr key={row.id} className="hover:bg-panel2">
                    <Td className="font-mono text-xs text-brand">{row.businessId ?? '—'}</Td>
                    <Td>
                      <p className="text-ink">{row.distributorName ?? '—'}</p>
                      <p className="text-xs text-ink3">{row.distributorEmail ?? ''}</p>
                    </Td>
                    <Td className="text-xs">
                      <p className="font-mono">{row.itemSetCode ?? '—'}</p>
                      <p className="text-ink3">{row.itemSetName ?? ''}</p>
                    </Td>
                    {tab === 'eligible' ? (
                      <>
                        <Td>
                          {row.anyStoreCanFulfil ? (
                            <Badge tone="ok">
                              {t('{{count}} store(s)', {
                                count: (row.stores ?? []).filter((s) => s.canFulfilWholePack)
                                  .length,
                              })}
                            </Badge>
                          ) : (
                            <Badge tone="danger">{t('not in stock')}</Badge>
                          )}
                        </Td>
                        <Td className="text-xs text-ink3">
                          {row.becameEligibleAt
                            ? new Date(row.becameEligibleAt).toLocaleDateString()
                            : '—'}
                        </Td>
                      </>
                    ) : (
                      <>
                        <Td className="text-xs">{row.issuedFromLocationName ?? '—'}</Td>
                        <Td className="text-xs text-ink3">
                          <p>{row.issuedAt ? new Date(row.issuedAt).toLocaleString() : '—'}</p>
                          <p>{row.issuedByName ? t('by {{who}}', { who: row.issuedByName }) : ''}</p>
                        </Td>
                      </>
                    )}
                    <Td>
                      <div className="flex justify-end gap-1">
                        <Button size="sm" variant="ghost" onClick={() => setViewing(row)}>
                          {t('View details')}
                        </Button>
                        {tab === 'eligible' && (
                          <Button
                            size="sm"
                            variant="primary"
                            disabled={!row.anyStoreCanFulfil}
                            title={
                              row.anyStoreCanFulfil
                                ? undefined
                                : t('No single store holds the whole pack')
                            }
                            onClick={() => setIssuing(row)}
                          >
                            {t('Issue pack')}
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

      {viewing && <DetailModal entitlement={viewing} onClose={() => setViewing(null)} />}
      {issuing && <IssueModal entitlement={issuing} onClose={() => setIssuing(null)} />}
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
      {count != null && count > 0 && (
        <span className="nums rounded-full bg-warnsoft px-1.5 py-0.5 text-[10px] font-semibold text-warn">
          {count}
        </span>
      )}
    </button>
  );
}

/** Who earned it, what is in it, and where it can be found. */
function DetailModal({
  entitlement,
  onClose,
}: {
  entitlement: RewardEntitlement;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();

  return (
    <Modal
      title={`${entitlement.itemSetCode ?? t('Pack')} — ${entitlement.distributorName ?? ''}`}
      onClose={onClose}
      wide
    >
      <div className="flex flex-col gap-5">
        <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2">
          <Row label={t('Business ID')} value={entitlement.businessId ?? '—'} />
          <Row label={t('Name')} value={entitlement.distributorName ?? '—'} />
          <Row label={t('Email')} value={entitlement.distributorEmail ?? '—'} />
          <Row label={t('Mobile')} value={entitlement.distributorMobile ?? '—'} />
          <Row label={t('Pack')} value={entitlement.itemSetName ?? '—'} />
          <Row
            label={t('Eligible since')}
            value={
              entitlement.becameEligibleAt
                ? new Date(entitlement.becameEligibleAt).toLocaleString()
                : '—'
            }
          />
        </dl>

        {entitlement.status === 'issued' ? (
          <div className="rounded-lg border border-ok bg-oksoft p-3 text-sm text-ink">
            {t('Issued from {{store}} on {{when}}{{by}}.', {
              store: entitlement.issuedFromLocationName ?? '—',
              when: entitlement.issuedAt
                ? new Date(entitlement.issuedAt).toLocaleString()
                : '—',
              by: entitlement.issuedByName ? t(' by {{who}}', { who: entitlement.issuedByName }) : '',
            })}
            {entitlement.note && <p className="mt-1 text-xs text-ink2 italic">{entitlement.note}</p>}
          </div>
        ) : (
          <StoreAvailability stores={entitlement.stores ?? []} />
        )}

        <div className="flex justify-between gap-2">
          <Button
            type="button"
            onClick={() =>
              entitlement.distributorId && navigate(`/distributors/${entitlement.distributorId}`)
            }
            disabled={!entitlement.distributorId}
          >
            {t('View user details')}
          </Button>
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Close')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/**
 * Every active store, including the ones that fall short.
 *
 * Showing only the stores that can supply the pack would leave "why can I not issue this" with no
 * answer on the screen — and the answer is usually one component being two units short somewhere.
 */
function StoreAvailability({ stores }: { stores: RewardStoreOption[] }) {
  const { t } = useTranslation();

  if (stores.length === 0) {
    return <EmptyState message={t('No active stores.')} />;
  }

  return (
    <div className="flex flex-col gap-3">
      <p className="text-xs font-semibold tracking-wide text-ink2 uppercase">
        {t('Where the pack can be filled')}
      </p>
      {stores.map((store) => (
        <div
          key={store.locationId}
          className={
            'rounded-lg border p-3 ' +
            (store.canFulfilWholePack ? 'border-ok bg-oksoft' : 'border-rule bg-panel2')
          }
        >
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <span className="text-sm font-semibold text-ink">
              {store.locationName}
              <span className="ml-2 font-mono text-[11px] text-ink3">{store.locationCode}</span>
            </span>
            {store.canFulfilWholePack ? (
              <Badge tone="ok">{t('has the whole pack')}</Badge>
            ) : (
              <Badge tone="danger">{t('short')}</Badge>
            )}
          </div>
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Item code')}</Th>
                  <Th>{t('Item')}</Th>
                  <Th align="right">{t('Needed')}</Th>
                  <Th align="right">{t('Available here')}</Th>
                </tr>
              </thead>
              <tbody>
                {(store.components ?? []).map((component) => (
                  <tr key={component.itemId}>
                    <Td className="font-mono text-xs">{component.sku}</Td>
                    <Td className="text-xs">{component.itemName}</Td>
                    <Td align="right" className="nums">
                      {component.required}
                    </Td>
                    <Td align="right">
                      <span className={component.enough ? '' : 'font-semibold text-danger'}>
                        <AvailabilityBox available={component.available} size="sm" />
                      </span>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        </div>
      ))}
    </div>
  );
}

/**
 * Handing the pack over.
 *
 * Only stores that hold the whole pack are offered. A pack filled from two stores would mean
 * somebody collecting from two places, and the server refuses it anyway — offering the choice
 * would just be a button that fails.
 */
function IssueModal({
  entitlement,
  onClose,
}: {
  entitlement: RewardEntitlement;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const issue = useIssueRewardPack();

  const usable = (entitlement.stores ?? []).filter((store) => store.canFulfilWholePack);
  const [locationId, setLocationId] = useState(usable[0]?.locationId ?? '');
  const [note, setNote] = useState('');

  return (
    <Modal title={t('Issue this pack?')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          if (!entitlement.id || !locationId) return;
          issue.mutate(
            { id: entitlement.id, locationId, note: note || undefined },
            { onSuccess: onClose },
          );
        }}
      >
        <p className="text-sm text-ink2">
          {t('{{pack}} goes to {{who}} ({{businessId}}).', {
            pack: entitlement.itemSetName ?? t('The pack'),
            who: entitlement.distributorName ?? '—',
            businessId: entitlement.businessId ?? '—',
          })}
        </p>
        <p className="text-xs text-ink3">
          {t('This takes every component out of the store below, straight away. It cannot be undone — a mistake has to be corrected with a stock adjustment.')}
        </p>

        <Field label={t('Issue from')} hint={t('Only stores holding the whole pack are listed')}>
          <Select
            required
            value={locationId}
            onChange={(event) => setLocationId(event.target.value)}
          >
            {usable.map((store) => (
              <option key={store.locationId} value={store.locationId ?? ''}>
                {store.locationName}
              </option>
            ))}
          </Select>
        </Field>

        <Field label={t('Note')} hint={t('Optional — how it was collected, or who signed for it')}>
          <Input maxLength={500} value={note} onChange={(event) => setNote(event.target.value)} />
        </Field>

        <ErrorBanner error={issue.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={issue.isPending || !locationId}>
            {issue.isPending ? t('Issuing…') : t('Issue pack')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-[11px] tracking-wide text-ink3 uppercase">{label}</dt>
      <dd className="text-ink">{value}</dd>
    </div>
  );
}
