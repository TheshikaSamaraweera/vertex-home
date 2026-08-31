import { useMemo, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { api, type PagedResponse } from '../api/client';
import type { components } from '../api/schema';
import {
  fetchDocumentObjectUrl,
  useDistributorDetail,
  useIssueReferralCards,
  useReferralCardBatches,
} from '../api/onboarding';
import { useItemSets } from '../api/queries';
import { useAuth } from '../auth/AuthContext';
import { REFERRAL_STAGES, stageIndexes } from '../lib/stages';
import {
  Badge,
  Button,
  Card,
  EmptyState,
  ErrorBanner,
  humanStatus,
  Input,
  PageHeader,
  Spinner,
  statusTone,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

type DistributorRow = components['schemas']['DistributorRow'];

/**
 * Everyone who has signed up, for administrators.
 *
 * This is the view that deliberately ignores the one-level boundary the distributor portal
 * enforces — an admin needs to see the whole population, and the endpoint behind it is admin-only
 * for exactly that reason.
 */

const useDistributors = (search: string, includeApplicants: boolean) =>
  useQuery({
    queryKey: ['admin', 'distributors', search, includeApplicants],
    queryFn: () =>
      api
        .get<PagedResponse<DistributorRow>>('/api/v1/admin/distributors', {
          search: search || undefined,
          includeApplicants,
        })
        .then((page) => page.data),
  });

export function DistributorsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();

  const [search, setSearch] = useState('');
  const [includeApplicants, setIncludeApplicants] = useState(true);
  const people = useDistributors(search, includeApplicants);

  const counts = useMemo(() => {
    const rows = people.data ?? [];
    return {
      total: rows.length,
      active: rows.filter((row) => row.distributorStatus === 'active').length,
      waiting: rows.filter((row) =>
        ['submitted', 'under_review', 'resubmit_required'].includes(row.registrationStatus ?? ''),
      ).length,
    };
  }, [people.data]);

  return (
    <>
      <PageHeader
        title={t('Customers')}
        description={t('Everyone who has signed up, whether or not their registration has been approved. Administrators only.')}
      />

      <Card
        title={t('People')}
        subtitle={t('{{active}} active · {{waiting}} awaiting a decision · {{total}} in total', counts)}
        actions={
          <>
            <Input
              className="w-56"
              placeholder={t('Search name, email or Business ID…')}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <Button size="sm" onClick={() => setIncludeApplicants(!includeApplicants)}>
              {includeApplicants ? t('Approved only') : t('Include applicants')}
            </Button>
          </>
        }
      >
        {people.isLoading ? (
          <Spinner />
        ) : people.error ? (
          <div className="p-4">
            <ErrorBanner error={people.error} onRetry={() => void people.refetch()} />
          </div>
        ) : (people.data ?? []).length === 0 ? (
          <EmptyState message={t('Nobody matches.')} />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Business ID')}</Th>
                  <Th>{t('Name')}</Th>
                  <Th>{t('Contact')}</Th>
                  <Th>{t('Status')}</Th>
                  <Th>{t('Level')}</Th>
                  <Th>{t('Referred by')}</Th>
                  <Th align="right">{t('Places used')}</Th>
                  <Th>{t('Joined')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {(people.data ?? []).map((row) => (
                  <tr key={row.userId} className="hover:bg-panel2">
                    <Td className="font-mono text-xs text-brand">{row.businessId ?? '—'}</Td>
                    <Td className="text-ink">{row.fullName}</Td>
                    <Td className="text-xs">
                      {/* Email and mobile together: an admin chasing somebody about their
                          registration needs whichever one actually reaches them. */}
                      <p>{row.email}</p>
                      <p className="text-ink3">{row.mobile ?? '—'}</p>
                    </Td>
                    <Td>
                      {row.distributorStatus === 'active' ? (
                        <Badge tone="ok">{t('active')}</Badge>
                      ) : row.registrationStatus ? (
                        <Badge tone={statusTone(row.registrationStatus)}>
                          {humanStatus(row.registrationStatus)}
                        </Badge>
                      ) : (
                        <Badge tone="warn">{t('not registered')}</Badge>
                      )}
                    </Td>
                    <Td>
                      <LevelCell row={row} />
                    </Td>
                    <Td className="font-mono text-xs">{row.referrerBusinessId ?? '—'}</Td>
                    <Td align="right">
                      {/* Places used, not "referrals" — four is the cap, and how many are left is
                          the number somebody is actually looking for. */}
                      {row.distributorId ? (
                        <span
                          className={
                            'nums ' + ((row.directReferrals ?? 0) >= 4 ? 'font-semibold text-warn' : '')
                          }
                        >
                          {row.directReferrals}/{REFERRAL_STAGES}
                        </span>
                      ) : (
                        '—'
                      )}
                    </Td>
                    <Td className="text-xs">
                      {row.joinedAt ? new Date(row.joinedAt).toLocaleDateString() : '—'}
                    </Td>
                    <Td>
                      <div className="flex justify-end">
                        {/* Only an approved distributor has a profile — an applicant has a
                            registration, which lives in the review queue. */}
                        {row.distributorId ? (
                          <Button
                            size="sm"
                            onClick={() => navigate(`/distributors/${row.distributorId}`)}
                          >
                            {t('Open profile')}
                          </Button>
                        ) : (
                          <span className="text-[11px] text-ink3">{t('no profile yet')}</span>
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
    </>
  );
}

/**
 * How far through the four §0.2 stages somebody is.
 *
 * A number on its own ("2/5") reads as a score without saying what it measures, so the dots carry
 * the shape and the label names the level. The names describe progression and promise nothing:
 * what a completed stage is *worth* is still undefined, and the backend grants no entitlement.
 */
function LevelCell({ row }: { row: DistributorRow }) {
  const { t } = useTranslation();

  if (!row.distributorId) {
    return <span className="text-xs text-ink3">{t('not placed yet')}</span>;
  }

  const completed = row.stagesCompleted ?? 0;
  const label =
    completed >= 4
      ? t('Level 4 · complete')
      : completed === 0
        ? t('Level 0 · starting')
        : t('Level {{n}} · building', { n: completed });

  return (
    <span className="flex flex-col gap-1">
      <span className="flex items-center gap-1">
        {stageIndexes().map((index) => (
          <span
            key={index}
            aria-hidden
            className={
              'h-2 w-2 rounded-full ' + (index < completed ? 'bg-brand' : 'border border-rule')
            }
          />
        ))}
        {row.bonusEligible && (
          <span className="ml-1 rounded-full border border-ok bg-oksoft px-1.5 text-[10px] text-ok">
            {t('bonus')}
          </span>
        )}
      </span>
      <span className="text-[11px] whitespace-nowrap text-ink3">{label}</span>
    </span>
  );
}

// ================================================================== profile

/** Everything about one distributor: account, placement, application history and activity. */
export function DistributorProfilePage() {
  const { t } = useTranslation();
  const { id } = useParams();
  const navigate = useNavigate();
  const { hasRole } = useAuth();
  const detail = useDistributorDetail(id ?? null);

  const canViewDocuments = hasRole('KYC_REVIEWER', 'ADMIN');
  const [viewing, setViewing] = useState<string | null>(null);

  if (detail.isLoading) return <Spinner />;
  if (detail.error) return <ErrorBanner error={detail.error} />;

  const data = detail.data;
  const account = data?.account;

  return (
    <>
      <PageHeader
        title={data?.distributor?.fullName ?? t('Customer')}
        description={data?.distributor?.businessId ?? ''}
        actions={
          <Button size="sm" onClick={() => navigate('/distributors')}>
            {t('Back to the list')}
          </Button>
        }
      />

      <div className="grid gap-5 lg:grid-cols-2">
        <Card title={t('Account')}>
          <dl className="grid gap-x-6 gap-y-3 p-5 sm:grid-cols-2">
            <Row label={t('Business ID')} value={data?.distributor?.businessId} mono />
            <Row label={t('Status')} value={data?.distributor?.status} />
            <Row label={t('Email')} value={account?.email} />
            <Row label={t('Mobile')} value={account?.mobile ?? '—'} />
            <Row
              label={t('Email confirmed')}
              value={account?.emailVerified ? t('yes') : t('no')}
            />
            <Row label={t('Account status')} value={account?.status} />
            <Row
              label={t('Joining date')}
              value={account?.joinedAt ? new Date(account.joinedAt).toLocaleString() : '—'}
            />
            <Row
              label={t('Approved as customer')}
              value={
                data?.distributor?.approvedAt
                  ? new Date(data.distributor.approvedAt).toLocaleString()
                  : '—'
              }
            />
          </dl>
        </Card>

        <Card title={t('Placement')}>
          <dl className="grid gap-x-6 gap-y-3 p-5 sm:grid-cols-2">
            <Row
              label={t('Referred by')}
              value={
                data?.parent
                  ? `${data.parent.businessId} · ${data.parent.fullName}`
                  : t('Nobody — a root')
              }
            />
            <Row
              label={t('Referral places used')}
              value={`${data?.referralsUsed ?? 0} / ${data?.referralCapacity ?? 4}`}
            />
            <Row
              label={t('Level')}
              value={`${data?.stages?.stagesCompleted ?? 0} / ${data?.stages?.totalStages ?? REFERRAL_STAGES}${
                data?.stages?.bonusStageEligible ? ' ★ bonus' : ''
              }`}
            />
            <Row label={t('Path')} value={data?.readablePath} />
          </dl>
          {data?.documents && (
            <div className="border-t border-rule px-5 py-4">
              <p className="mb-2 text-[10px] font-semibold tracking-wider text-ink3 uppercase">
                {t('KYC documents')}
              </p>
              <p className="mb-2 text-xs text-ink2">
                {t('NIC ending {{last4}}. Opening one is logged before any bytes are sent.', {
                  last4: data.documents.nicLast4 ?? '••••',
                })}
              </p>
              {canViewDocuments ? (
                <div className="flex flex-wrap gap-2">
                  {data.documents.nicDocumentId && (
                    <Button size="sm" onClick={() => setViewing(data.documents!.nicDocumentId!)}>
                      {t('View NIC scan')}
                    </Button>
                  )}
                  {data.documents.slipDocumentId && (
                    <Button size="sm" onClick={() => setViewing(data.documents!.slipDocumentId!)}>
                      {t('View bank slip')}
                    </Button>
                  )}
                </div>
              ) : (
                <p className="text-xs text-ink3">{t('Reviewers and admins only.')}</p>
              )}
            </div>
          )}
        </Card>
      </div>

      <div className="mt-5 grid gap-5 lg:grid-cols-2">
        <Card title={t('Direct referrals')}>
          {(data?.children ?? []).length === 0 ? (
            <EmptyState message={t('None yet.')} />
          ) : (
            <TableWrap>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('Business ID')}</Th>
                    <Th>{t('Name')}</Th>
                    <Th align="right">{t('Theirs')}</Th>
                    <Th>{''}</Th>
                  </tr>
                </thead>
                <tbody>
                  {(data?.children ?? []).map((child) => (
                    <tr key={child.id}>
                      <Td className="font-mono text-xs">{child.businessId ?? '—'}</Td>
                      <Td className="text-ink">{child.fullName}</Td>
                      <Td align="right">{child.directChildCount}</Td>
                      <Td>
                        <div className="flex justify-end">
                          <Button
                            size="sm"
                            variant="ghost"
                            onClick={() => navigate(`/distributors/${child.id}`)}
                          >
                            {t('Open')}
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

        <Card title={t('Registration history')}>
          {(data?.registrationTimeline ?? []).length === 0 ? (
            <EmptyState message={t('No registration on file — this customer was seeded.')} />
          ) : (
            <ol className="flex flex-col gap-3 p-5">
              {(data?.registrationTimeline ?? []).map((entry, index) => (
                <li key={index} className="text-sm">
                  <p className="text-ink">
                    {humanStatus(entry.fromStatus)} →{' '}
                    <strong>{humanStatus(entry.toStatus)}</strong>
                    {entry.actorName ? ` · ${entry.actorName}` : ''}
                  </p>
                  {(entry.note || entry.reason) && (
                    <p className="text-xs text-ink2">{entry.note ?? humanStatus(entry.reason)}</p>
                  )}
                  <p className="text-[11px] text-ink3">
                    {entry.createdAt ? new Date(entry.createdAt).toLocaleString() : ''}
                  </p>
                </li>
              ))}
            </ol>
          )}
        </Card>
      </div>

      <ReferralCards
        distributorId={id ?? ''}
        businessId={data?.distributor?.businessId}
        referralsUsed={data?.referralsUsed ?? 0}
        capacity={data?.referralCapacity ?? REFERRAL_STAGES}
      />

      <Card className="mt-5" title={t('Activity')} subtitle={t('The 50 most recent actions by this account')}>
        {(data?.activity ?? []).length === 0 ? (
          <EmptyState message={t('Nothing recorded.')} />
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Action')}</Th>
                  <Th>{t('On')}</Th>
                  <Th>{t('When')}</Th>
                </tr>
              </thead>
              <tbody>
                {(data?.activity ?? []).map((entry, index) => (
                  <tr key={index}>
                    <Td className="font-mono text-xs text-ink">{entry.action}</Td>
                    <Td className="text-xs">{entry.entityType}</Td>
                    <Td className="text-xs">
                      {entry.at ? new Date(entry.at).toLocaleString() : ''}
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>

      {viewing && (
        <DocumentPeek
          documentId={viewing}
          subjectUserId={data?.distributor?.userId}
          onClose={() => setViewing(null)}
        />
      )}
    </>
  );
}

function Row({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div>
      <dt className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">{label}</dt>
      <dd className={'mt-0.5 text-sm text-ink2 ' + (mono ? 'font-mono' : '')}>{value || '—'}</dd>
    </div>
  );
}

/** Same authorise-then-redeem path as everywhere else, so the access is logged. */
function DocumentPeek({
  documentId,
  subjectUserId,
  onClose,
}: {
  documentId: string;
  subjectUserId?: string;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const [state, setState] = useState<{ url?: string; error?: unknown }>({});

  useMemo(() => {
    void fetchDocumentObjectUrl(documentId, subjectUserId)
      .then((served) => setState({ url: served.objectUrl }))
      .catch((error) => setState({ error }));
  }, [documentId, subjectUserId]);

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/35 p-6 backdrop-blur-[2px]"
      onClick={onClose}
      role="dialog"
      aria-modal="true"
    >
      <div
        className="max-h-[85vh] w-full max-w-2xl overflow-auto rounded-xl border border-rule bg-panel p-5 shadow-float"
        onClick={(event) => event.stopPropagation()}
      >
        <div className="mb-3 flex items-center justify-between">
          <p className="text-sm font-semibold text-ink">{t('Document')}</p>
          <Button size="sm" variant="ghost" onClick={onClose}>
            ✕
          </Button>
        </div>
        {state.error ? (
          <ErrorBanner error={state.error} />
        ) : state.url ? (
          <img src={state.url} alt={t('Document')} className="w-full object-contain" />
        ) : (
          <Spinner label={t('Requesting access…')} />
        )}
      </div>
    </div>
  );
}


/**
 * Printed referral cards for one customer.
 *
 * A customer needs five referrals to finish their stages, so an administrator prints five cards
 * and hands them over — one for each person they recruit. The card carries the referrer's Business
 * ID, which is the only thing an applicant actually types at registration.
 *
 * The cards are a handout, not a credential: nothing here is redeemed, and the code on a card is
 * how the office refers to that piece of paper, not how anybody signs up.
 */
function ReferralCards({
  distributorId,
  businessId,
  referralsUsed,
  capacity,
}: {
  distributorId: string;
  businessId?: string;
  referralsUsed: number;
  capacity: number;
}) {
  const { t } = useTranslation();
  const batches = useReferralCardBatches(distributorId || null);
  const issue = useIssueReferralCards(distributorId);
  const itemSets = useItemSets();

  const [packId, setPackId] = useState('');
  const [count, setCount] = useState(String(REFERRAL_STAGES));
  const [note, setNote] = useState('');

  // A Business ID is allocated at approval. Without one the card's whole purpose — naming the
  // referrer — would print blank, so the server refuses and this says why before you try.
  const approved = Boolean(businessId);

  return (
    <Card
      className="mt-5"
      title={t('Referral cards')}
      subtitle={t('Printed cards this customer hands to the people they refer')}
    >
      {!approved ? (
        <EmptyState
          message={t('No Business ID yet. Cards can be printed once this customer is approved.')}
        />
      ) : (
        <div className="p-5">
          <p className="mb-4 text-xs text-ink2">
            {t('{{used}} of {{capacity}} referral places filled.', {
              used: referralsUsed,
              capacity,
            })}{' '}
            {t('Each card prints this customer as the parent')}{' '}
            <span className="font-mono">{businessId}</span>
            {t(', plus the child ID that seat will receive — 12 becomes 121, 122 and so on. Only free seats are printed.')}
          </p>

          <div className="flex flex-wrap items-end gap-3">
            <label className="flex flex-col gap-1">
              <span className="text-[11px] font-medium text-ink2">{t('Item pack')}</span>
              <select
                value={packId}
                onChange={(event) => setPackId(event.target.value)}
                className="rounded-md border border-rule bg-panel px-3 py-2 text-sm text-ink"
              >
                <option value="">{t('No pack on the card')}</option>
                {(itemSets.data ?? []).map((set) => (
                  <option key={set.id} value={set.id}>
                    {set.name}
                  </option>
                ))}
              </select>
            </label>

            <label className="flex w-24 flex-col gap-1">
              <span className="text-[11px] font-medium text-ink2">{t('Cards')}</span>
              <Input
                value={count}
                onChange={(event) => setCount(event.target.value)}
                inputMode="numeric"
              />
            </label>

            <label className="flex min-w-48 flex-1 flex-col gap-1">
              <span className="text-[11px] font-medium text-ink2">{t('Note (optional)')}</span>
              <Input
                value={note}
                onChange={(event) => setNote(event.target.value)}
                placeholder={t('Why this batch was printed')}
              />
            </label>

            <Button
              onClick={() =>
                issue.mutate({
                  itemSetId: packId || undefined,
                  count: Number(count) || REFERRAL_STAGES,
                  note: note.trim() || undefined,
                })
              }
              disabled={issue.isPending}
            >
              {issue.isPending ? t('Printing…') : t('Print cards')}
            </Button>
          </div>

          {issue.error && (
            <div className="mt-3">
              <ErrorBanner error={issue.error} />
            </div>
          )}

          {/* Every batch is kept, not just the latest. Paper gets lost and printers jam, so
              reprinting is normal — and afterwards somebody has to be able to say which physical
              cards exist and when they were made. */}
          {(batches.data ?? []).length === 0 ? (
            <p className="mt-5 text-xs text-ink3">{t('No cards printed yet.')}</p>
          ) : (
            <div className="mt-5">
            <TableWrap>
              <Table>
                <thead>
                  <tr>
                    <Th>{t('Printed')}</Th>
                    <Th>{t('Child IDs')}</Th>
                    <Th>{t('Pack')}</Th>
                    <Th>{t('By')}</Th>
                    <Th>{t('Note')}</Th>
                    <Th>{''}</Th>
                  </tr>
                </thead>
                <tbody>
                  {(batches.data ?? []).map((batch) => (
                    <tr key={batch.id}>
                      <Td className="text-xs whitespace-nowrap">
                        {batch.issuedAt ? new Date(batch.issuedAt).toLocaleDateString() : ''}
                      </Td>
                      {/* The IDs themselves, not a count. Five cards go out to five different
                          people, and the question afterwards is always "which one did they get",
                          which a number cannot answer. */}
                      <Td>
                        <div className="flex flex-wrap gap-1">
                          {(batch.cards ?? []).map((card) => (
                            <span
                              key={card.id}
                              className="rounded bg-panel2 px-1.5 py-0.5 font-mono text-[11px] text-ink"
                            >
                              {card.code}
                            </span>
                          ))}
                        </div>
                      </Td>
                      <Td className="text-xs">{batch.itemSetName ?? '—'}</Td>
                      <Td className="text-xs">{batch.issuedByName}</Td>
                      <Td className="text-xs text-ink2">{batch.note ?? ''}</Td>
                      <Td>
                        <Link
                          to={`/referral-cards/${batch.id}`}
                          className="text-xs font-medium text-brand underline"
                        >
                          {t('View and print')}
                        </Link>
                      </Td>
                    </tr>
                  ))}
                </tbody>
              </Table>
            </TableWrap>
            </div>
          )}
        </div>
      )}
    </Card>
  );
}
