import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  fetchDocumentObjectUrl,
  revealRegistrationDetails,
  useApproveRegistration,
  useClaimRegistration,
  useItemPackOptions,
  useRegistrationDetail,
  useRejectRegistration,
  useReleaseRegistration,
  useReviewQueue,
  type Registration,
} from '../api/onboarding';
import { Icon, type IconName } from '../components/icons';
import { useAuth } from '../auth/AuthContext';
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
} from '../components/ui';

/** Submitted within this long, and still unclaimed, counts as new. */
const NEW_WINDOW_MS = 24 * 60 * 60 * 1000;

const isNew = (item: Registration) =>
  !item.claimedBy &&
  item.status === 'submitted' &&
  Boolean(item.submittedAt) &&
  Date.now() - new Date(item.submittedAt!).getTime() < NEW_WINDOW_MS;

/** "just now", "12 min ago", "3 h ago", "2 d ago" — the age of an application at a glance. */
function timeAgo(iso?: string | null): string {
  if (!iso) return '';
  const minutes = Math.floor((Date.now() - new Date(iso).getTime()) / 60000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  return `${Math.floor(hours / 24)} d ago`;
}

type QueueFilter = 'all' | 'new' | 'unclaimed' | 'mine' | 'returned';

/**
 * The registration review queue — the highest-volume admin surface in the system.
 *
 * Called "Registration verification" throughout the UI, never "KYC". The development plan is
 * explicit: use KYC in code where developers will find material on it, and plain language where
 * reviewers will read it.
 *
 * Built around what a reviewer does first — find what is new and nobody has picked up — so new
 * applications are counted at the top and marked in the list, newest first. The two numbers that
 * identify a person (NIC and bank account) stay masked until the reviewer asks to see them, and
 * each such request is recorded against their name.
 */
export function ReviewQueuePage() {
  const { t } = useTranslation();
  const { user } = useAuth();

  const queue = useReviewQueue();
  const packs = useItemPackOptions();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const detail = useRegistrationDetail(selectedId);

  const claim = useClaimRegistration();
  const release = useReleaseRegistration();
  const approve = useApproveRegistration();
  const reject = useRejectRegistration();

  const [rejecting, setRejecting] = useState(false);
  const [approved, setApproved] = useState<string | null>(null);

  const [search, setSearch] = useState('');
  const [filter, setFilter] = useState<QueueFilter>('all');
  const needle = search.trim().toLowerCase();

  const all = [...(queue.data ?? [])].sort(
    (a, b) => new Date(b.submittedAt ?? 0).getTime() - new Date(a.submittedAt ?? 0).getTime(),
  );
  const counts = {
    new: all.filter(isNew).length,
    unclaimed: all.filter((item) => !item.claimedBy).length,
    mine: all.filter((item) => item.claimedBy === user?.id).length,
    returned: all.filter((item) => item.status === 'resubmit_required').length,
  };
  const shown = all.filter(
    (item) =>
      (!needle ||
        [item.applicantName, item.applicantEmail, item.referrerBusinessId].some((field) =>
          (field ?? '').toLowerCase().includes(needle),
        )) &&
      (filter === 'all' ||
        (filter === 'new' && isNew(item)) ||
        (filter === 'unclaimed' && !item.claimedBy) ||
        (filter === 'mine' && item.claimedBy === user?.id) ||
        (filter === 'returned' && item.status === 'resubmit_required')),
  );

  const registration = detail.data?.registration;
  const isMine = registration?.claimedBy === user?.id;
  const isOwnRegistration = registration?.userId === user?.id;
  const packName = (packs.data ?? []).find((pack) => pack.id === registration?.itemSetId)?.name;

  return (
    <>
      <PageHeader
        title={t('Registration verification')}
        description={t('Claim a record before deciding on it. One reviewer per record, and never your own.')}
      />

      {/* The four numbers a reviewer opens this page to learn. Each one is also a filter. */}
      <div className="mb-5 grid grid-cols-2 gap-3 lg:grid-cols-4">
        <QueueStat
          label={t('New today')}
          value={counts.new}
          tone="new"
          active={filter === 'new'}
          onClick={() => setFilter(filter === 'new' ? 'all' : 'new')}
        />
        <QueueStat
          label={t('Not claimed yet')}
          value={counts.unclaimed}
          tone="waiting"
          active={filter === 'unclaimed'}
          onClick={() => setFilter(filter === 'unclaimed' ? 'all' : 'unclaimed')}
        />
        <QueueStat
          label={t('Claimed by me')}
          value={counts.mine}
          tone="mine"
          active={filter === 'mine'}
          onClick={() => setFilter(filter === 'mine' ? 'all' : 'mine')}
        />
        <QueueStat
          label={t('Returned for changes')}
          value={counts.returned}
          tone="returned"
          active={filter === 'returned'}
          onClick={() => setFilter(filter === 'returned' ? 'all' : 'returned')}
        />
      </div>

      {approved && (
        <div className="mb-4 flex items-start gap-3 rounded-2xl border border-ok/25 bg-oksoft px-4 py-3 text-sm text-ink">
          <Icon name="check" className="mt-0.5 h-5 w-5 flex-none text-ok" />
          <div>
            <p>
              {t('Approved. Business ID')}{' '}
              <span className="font-mono text-base font-semibold">{approved}</span>{' '}
              {t('has been allocated and is now permanent.')}
            </p>
            {/* The number is the point of the whole screen: whoever is at the desk has to give it
                to somebody, and saying so turns a confirmation into an instruction. */}
            <p className="mt-1 text-xs text-ink2">
              {t('Give this ID to the five people they recruit — it is what each of them enters as their referrer.')}
            </p>
          </div>
        </div>
      )}
      {(claim.error || approve.error || release.error) && (
        <div className="mb-4">
          <ErrorBanner error={claim.error ?? approve.error ?? release.error} />
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,24rem)_minmax(0,1fr)]">
        <Card
          title={t('Queue')}
          subtitle={t('{{count}} awaiting review', { count: all.length })}
          className="self-start"
        >
          {queue.isLoading ? (
            <Spinner />
          ) : queue.error ? (
            <div className="p-4">
              <ErrorBanner error={queue.error} onRetry={() => void queue.refetch()} />
            </div>
          ) : all.length === 0 ? (
            <EmptyState message={t('Nothing waiting.')} hint={t('New submissions appear here.')} />
          ) : (
            <>
              <div className="border-b border-rule p-3">
                <Input
                  type="search"
                  aria-label={t('Search the queue')}
                  placeholder={t('Name, email or referrer ID…')}
                  value={search}
                  onChange={(event) => setSearch(event.target.value)}
                />
              </div>
              {shown.length === 0 && <EmptyState message={t('Nothing matches these filters.')} />}
              <ul className="max-h-[70vh] overflow-y-auto">
                {shown.map((item) => (
                  <QueueRow
                    key={item.id}
                    item={item}
                    selected={selectedId === item.id}
                    mine={item.claimedBy === user?.id}
                    onSelect={() => setSelectedId(item.id ?? null)}
                  />
                ))}
              </ul>
            </>
          )}
        </Card>

        {!selectedId ? (
          <Card>
            <div className="flex flex-col items-center px-6 py-16 text-center">
              <span className="flex h-14 w-14 items-center justify-center rounded-2xl bg-brandsoft text-brand">
                <Icon name="shieldCheck" className="h-7 w-7" />
              </span>
              <p className="mt-4 font-semibold text-ink">{t('Select an application to review')}</p>
              <p className="mt-1 max-w-sm text-sm text-ink3">
                {t('New ones are marked in the list. Claim one before deciding on it.')}
              </p>
            </div>
          </Card>
        ) : detail.isLoading ? (
          <Card>
            <Spinner />
          </Card>
        ) : registration ? (
          <div className="flex flex-col gap-5">
            {/* Who, where they are in the process, and what can be done about it. */}
            <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
              <div className="flex flex-wrap items-start gap-4">
                <Initials name={registration.applicantName} size="lg" />
                <div className="min-w-0 flex-1">
                  <div className="flex flex-wrap items-center gap-2">
                    <h2 className="text-xl font-bold tracking-tight text-ink">
                      {registration.applicantName}
                    </h2>
                    <Badge tone={statusTone(registration.status ?? '')}>
                      {humanStatus(registration.status)}
                    </Badge>
                    {isNew(registration) && <NewBadge />}
                  </div>
                  <p className="mt-0.5 text-sm text-ink3">{registration.applicantEmail}</p>
                  <p className="mt-1 text-xs text-ink3">
                    {t('Submitted {{when}}', {
                      when: registration.submittedAt
                        ? `${new Date(registration.submittedAt).toLocaleString()} · ${timeAgo(registration.submittedAt)}`
                        : '—',
                    })}
                  </p>
                </div>
                <div className="flex flex-wrap gap-2">
                  {!isMine && !isOwnRegistration && (
                    <Button
                      variant="primary"
                      disabled={claim.isPending}
                      onClick={() => selectedId && claim.mutate(selectedId)}
                    >
                      {t('Claim')}
                    </Button>
                  )}
                  {isMine && (
                    <>
                      <Button
                        onClick={() => selectedId && release.mutate(selectedId)}
                        disabled={release.isPending}
                      >
                        {t('Release')}
                      </Button>
                      <Button variant="danger" onClick={() => setRejecting(true)}>
                        {t('Reject')}
                      </Button>
                      <Button
                        variant="primary"
                        disabled={approve.isPending}
                        onClick={() =>
                          selectedId &&
                          approve.mutate(selectedId, {
                            onSuccess: (result) => {
                              setApproved(result.businessId);
                              // The record has left the queue; leaving it selected showed a
                              // decided registration with Claim and Approve still on it.
                              setSelectedId(null);
                            },
                          })
                        }
                      >
                        {approve.isPending ? t('Approving…') : t('Approve')}
                      </Button>
                    </>
                  )}
                </div>
              </div>

              {/* The two barred paths, said plainly rather than left as a mystery 403. */}
              {isOwnRegistration && (
                <div className="mt-4 rounded-xl border border-danger/25 bg-dangersoft px-3 py-2 text-sm text-danger">
                  {t('This is your own registration. Someone else has to review it.')}
                </div>
              )}
              {registration.claimedByName && !isMine && (
                <div className="mt-4 rounded-xl border border-warn/25 bg-warnsoft px-3 py-2 text-sm text-ink2">
                  {t('{{name}} is reviewing this record.', { name: registration.claimedByName })}
                </div>
              )}
              {isMine && (
                <div className="mt-4 rounded-xl border border-brand/20 bg-brandsoft px-3 py-2 text-sm text-ink2">
                  {t('You have claimed this record. Check the details and documents, then approve or reject.')}
                </div>
              )}
            </section>

            <SensitiveDetails
              key={selectedId}
              registrationId={selectedId}
              canReveal={!isOwnRegistration}
              nicLast4={registration.nicLast4}
              bankName={registration.bankName}
              bankBranch={registration.bankBranch}
              maskedAccount={registration.bankAccountNumber}
            />

            <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
              <SectionHeading icon="clipboard" title={t('Application')} />
              <dl className="grid gap-x-6 gap-y-4 sm:grid-cols-2">
                <Detail label={t('Referrer')} value={registration.referrerBusinessId ?? t('None — a root')} mono />
                <Detail label={t('Item pack')} value={packName ?? (registration.itemSetId ? '—' : t('No pack chosen'))} />
                <Detail label={t('Address')} value={registration.fullAddress} wide />
              </dl>
            </section>

            <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
              <SectionHeading icon="idCard" title={t('Documents')} />
              <div className="grid gap-4 sm:grid-cols-2">
                <DocumentViewer
                  key={`nic-${registration.nicDocumentId}`}
                  label={t('NIC image')}
                  documentId={registration.nicDocumentId}
                  subjectUserId={registration.userId}
                />
                <DocumentViewer
                  key={`slip-${registration.slipDocumentId}`}
                  label={t('Bank slip')}
                  documentId={registration.slipDocumentId}
                  subjectUserId={registration.userId}
                />
              </div>
              <p className="mt-3 flex items-start gap-1.5 text-xs text-ink3">
                <Icon name="lock" className="mt-0.5 h-3.5 w-3.5 flex-none" />
                {t('Every time you open a document it is recorded against your name, with the time and your IP address.')}
              </p>
            </section>

            <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
              <SectionHeading icon="calendar" title={t('History')} />
              <ol className="relative ml-2 border-l-2 border-rule pl-5">
                {(detail.data?.timeline ?? []).map((event, index) => (
                  <li key={index} className="relative pb-4 last:pb-0">
                    <span className="absolute top-1 -left-[27px] h-3 w-3 rounded-full border-2 border-panel bg-brand" />
                    <p className="text-sm text-ink">
                      {event.fromStatus ? `${humanStatus(event.fromStatus)} → ` : ''}
                      <strong>{humanStatus(event.toStatus)}</strong>
                    </p>
                    <p className="text-xs text-ink3">
                      {event.createdAt ? new Date(event.createdAt).toLocaleString() : ''}
                      {event.actorName ? ` · ${event.actorName}` : ''}
                    </p>
                    {event.reason && <p className="mt-0.5 text-xs text-danger">{humanStatus(event.reason)}</p>}
                  </li>
                ))}
              </ol>
            </section>
          </div>
        ) : null}
      </div>

      {rejecting && selectedId && (
        <RejectModal
          onClose={() => setRejecting(false)}
          pending={reject.isPending}
          error={reject.error}
          onSubmit={(reason, note, allowResubmit) =>
            reject.mutate(
              { id: selectedId, reason, note, allowResubmit },
              {
                onSuccess: () => {
                  setRejecting(false);
                  // Decided is decided: the record has left the queue either way.
                  setSelectedId(null);
                },
              },
            )
          }
        />
      )}
    </>
  );
}

const STAT_TONES = {
  new: { tile: 'from-[#38b2a3] to-[#0b7a6e]', icon: 'sparkles' },
  waiting: { tile: 'from-[#f6b35c] to-[#e07a2b]', icon: 'inbox' },
  mine: { tile: 'from-[#7c8cff] to-[#4f5bd5]', icon: 'user' },
  returned: { tile: 'from-[#f472b6] to-[#c0266d]', icon: 'arrowRight' },
} as const;

function QueueStat({
  label,
  value,
  tone,
  active,
  onClick,
}: {
  label: string;
  value: number;
  tone: keyof typeof STAT_TONES;
  active: boolean;
  onClick: () => void;
}) {
  const style = STAT_TONES[tone];
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={
        'flex items-center gap-3 rounded-2xl border bg-panel p-4 text-left shadow-card transition-all hover:-translate-y-0.5 ' +
        (active ? 'border-brand ring-2 ring-brand/25' : 'border-rule')
      }
    >
      <span
        className={`flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-linear-to-br text-white shadow-sm ${style.tile}`}
      >
        <Icon name={style.icon} className="h-5 w-5" />
      </span>
      <span className="min-w-0">
        <span className="block text-xs font-medium text-ink3">{label}</span>
        <span className="nums block text-2xl leading-tight font-extrabold text-ink">{value}</span>
      </span>
    </button>
  );
}

function QueueRow({
  item,
  selected,
  mine,
  onSelect,
}: {
  item: Registration;
  selected: boolean;
  mine: boolean;
  onSelect: () => void;
}) {
  const { t } = useTranslation();
  const fresh = isNew(item);
  return (
    <li className="border-b border-rule last:border-b-0">
      <button
        type="button"
        onClick={onSelect}
        className={
          'relative flex w-full items-start gap-3 px-4 py-3.5 text-left transition-colors ' +
          (selected ? 'bg-brandsoft' : fresh ? 'bg-[#f2fbf9] hover:bg-brandsoft/70' : 'hover:bg-panel2')
        }
      >
        {/* A coloured edge on anything new, so it is found by scanning the left margin. */}
        {(fresh || selected) && (
          <span aria-hidden className={`absolute inset-y-0 left-0 w-1 ${selected ? 'bg-brand' : 'bg-brandbright'}`} />
        )}
        <Initials name={item.applicantName} />
        <span className="min-w-0 flex-1">
          <span className="flex items-center justify-between gap-2">
            <span className={`truncate text-sm ${fresh ? 'font-bold text-ink' : 'font-medium text-ink'}`}>
              {item.applicantName}
            </span>
            <span className="flex-none text-[11px] text-ink3">{timeAgo(item.submittedAt)}</span>
          </span>
          <span className="block truncate text-xs text-ink3">{item.applicantEmail}</span>
          <span className="mt-1.5 flex flex-wrap items-center gap-1.5">
            {fresh && <NewBadge />}
            <Badge tone={statusTone(item.status ?? '')}>{humanStatus(item.status)}</Badge>
            {item.claimedByName && (
              <span className={`text-[11px] font-medium ${mine ? 'text-brand' : 'text-warn'}`}>
                {mine ? t('claimed by you') : t('claimed by {{name}}', { name: item.claimedByName })}
              </span>
            )}
          </span>
        </span>
      </button>
    </li>
  );
}

function NewBadge() {
  const { t } = useTranslation();
  return (
    <span className="inline-flex items-center gap-1.5 rounded-full bg-linear-to-r from-brandbright to-brand px-2 py-0.5 text-[11px] font-bold text-white">
      <span className="relative flex h-1.5 w-1.5">
        <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-white opacity-75" />
        <span className="relative inline-flex h-1.5 w-1.5 rounded-full bg-white" />
      </span>
      {t('New')}
    </span>
  );
}

/** Initials on a colour picked from the name, so the same applicant keeps the same colour. */
function Initials({ name, size = 'md' }: { name?: string | null; size?: 'md' | 'lg' }) {
  const palette = [
    'from-[#38b2a3] to-[#0b7a6e]',
    'from-[#7c8cff] to-[#4f5bd5]',
    'from-[#f6a35c] to-[#e0672b]',
    'from-[#f472b6] to-[#c0266d]',
    'from-[#60a5fa] to-[#2563eb]',
  ];
  const text = name ?? '';
  let hash = 0;
  for (const char of text) hash = (hash * 31 + char.charCodeAt(0)) | 0;
  const initials =
    text
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join('') || '?';
  return (
    <span
      aria-hidden
      className={`flex flex-none items-center justify-center rounded-full bg-linear-to-br font-bold text-white ${palette[Math.abs(hash) % palette.length]} ${size === 'lg' ? 'h-14 w-14 text-lg' : 'h-10 w-10 text-[13px]'}`}
    >
      {initials}
    </span>
  );
}

function SectionHeading({ icon, title }: { icon: IconName; title: string }) {
  return (
    <h3 className="mb-4 flex items-center gap-2 text-sm font-semibold text-ink">
      <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-brandsoft text-brand">
        <Icon name={icon} className="h-4 w-4" />
      </span>
      {title}
    </h3>
  );
}

function Detail({
  label,
  value,
  mono,
  wide,
}: {
  label: string;
  value?: string | null;
  mono?: boolean;
  wide?: boolean;
}) {
  return (
    <div className={wide ? 'sm:col-span-2' : ''}>
      <dt className="text-xs font-medium text-ink3">{label}</dt>
      <dd className={'mt-0.5 text-sm font-medium text-ink ' + (mono ? 'font-mono' : '')}>{value || '—'}</dd>
    </div>
  );
}

/**
 * The NIC and bank details, masked until the reviewer asks.
 *
 * One Show button reveals both numbers with a single audited request; Hide forgets them. They are
 * held only in this component's state, which is thrown away when another record is selected (the
 * parent keys it by record), so a revealed number never survives into the next applicant's view.
 */
function SensitiveDetails({
  registrationId,
  canReveal,
  nicLast4,
  bankName,
  bankBranch,
  maskedAccount,
}: {
  registrationId: string;
  canReveal: boolean;
  nicLast4?: string | null;
  bankName?: string | null;
  bankBranch?: string | null;
  maskedAccount?: string | null;
}) {
  const { t } = useTranslation();
  const [revealed, setRevealed] = useState<{ nicNumber?: string; bankAccountNumber?: string } | null>(
    null,
  );
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState<unknown>(null);

  async function reveal() {
    setLoading(true);
    setError(null);
    try {
      setRevealed(await revealRegistrationDetails(registrationId));
    } catch (caught) {
      setError(caught);
    } finally {
      setLoading(false);
    }
  }

  return (
    <section className="rounded-2xl border border-rule bg-panel p-5 shadow-card">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <SectionHeading icon="shieldCheck" title={t('Identity and bank')} />
        {canReveal &&
          (revealed ? (
            <Button size="sm" onClick={() => setRevealed(null)}>
              <Icon name="lock" className="h-3.5 w-3.5" />
              {t('Hide numbers')}
            </Button>
          ) : (
            <Button size="sm" variant="primary" disabled={loading} onClick={() => void reveal()}>
              <Icon name="key" className="h-3.5 w-3.5" />
              {loading ? t('Revealing…') : t('Show NIC and account number')}
            </Button>
          ))}
      </div>

      <dl className="grid gap-3 sm:grid-cols-2">
        <SecretField
          label={t('NIC number')}
          value={revealed?.nicNumber ?? `•••• •••• ${nicLast4 ?? '????'}`}
          revealed={Boolean(revealed)}
        />
        <SecretField
          label={t('Bank account number')}
          value={revealed?.bankAccountNumber ?? maskedAccount ?? '—'}
          revealed={Boolean(revealed)}
        />
        <Detail label={t('Bank')} value={bankName} />
        <Detail label={t('Branch')} value={bankBranch} />
      </dl>

      <ErrorBanner error={error} />
      <p className="mt-3 flex items-start gap-1.5 text-xs text-ink3">
        <Icon name="lock" className="mt-0.5 h-3.5 w-3.5 flex-none" />
        {t('Numbers are hidden until you show them. Each time you do, it is recorded against your name.')}
      </p>
    </section>
  );
}

function SecretField({ label, value, revealed }: { label: string; value: string; revealed: boolean }) {
  return (
    <div
      className={
        'rounded-xl border px-4 py-3 transition-colors ' +
        (revealed ? 'border-warn/40 bg-warnsoft' : 'border-rule bg-panel2')
      }
    >
      <dt className="text-xs font-medium text-ink3">{label}</dt>
      <dd className="mt-0.5 font-mono text-base font-semibold tracking-wider text-ink select-all">{value}</dd>
    </div>
  );
}

/**
 * One identity document: a tile until opened, then a full-size viewer with a Close button.
 *
 * Fetched only when the reviewer asks, never on selection — opening a record must not count as
 * viewing somebody's identity document, because every fetch is written to the access log. The
 * object URL is revoked when the viewer closes or the component goes away, so the image does not
 * linger in memory after the reviewer has moved on.
 */
function DocumentViewer({
  label,
  documentId,
  subjectUserId,
}: {
  label: string;
  documentId?: string | null;
  subjectUserId?: string | null;
}) {
  const { t } = useTranslation();
  const [objectUrl, setObjectUrl] = useState<string | null>(null);
  const [contentType, setContentType] = useState<string>('');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    return () => {
      if (objectUrl) URL.revokeObjectURL(objectUrl);
    };
  }, [objectUrl]);

  const close = () => setObjectUrl(null);

  if (!documentId) {
    return (
      <div className="flex items-center gap-3 rounded-xl border border-dashed border-rulestrong p-4 text-sm text-ink3">
        <Icon name="idCard" className="h-5 w-5" />
        {label} — {t('not provided')}
      </div>
    );
  }

  return (
    <>
      <div className="flex items-center gap-3 rounded-xl border border-rule bg-panel2 p-4">
        <span className="flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-white text-brand shadow-xs">
          <Icon name="idCard" className="h-5 w-5" />
        </span>
        <div className="min-w-0 flex-1">
          <p className="text-sm font-semibold text-ink">{label}</p>
          <p className="text-xs text-ink3">{t('Opens in a viewer; opening is logged')}</p>
        </div>
        <Button
          size="sm"
          disabled={loading}
          onClick={async () => {
            setLoading(true);
            setError(null);
            try {
              const result = await fetchDocumentObjectUrl(documentId, subjectUserId ?? undefined);
              setObjectUrl(result.objectUrl);
              setContentType(result.contentType);
            } catch (caught) {
              setError(caught instanceof Error ? caught.message : 'Could not open the document.');
            } finally {
              setLoading(false);
            }
          }}
        >
          {loading ? t('Opening…') : t('View')}
        </Button>
      </div>
      {error && <p className="mt-2 text-xs text-danger">{error}</p>}

      {objectUrl && (
        <Modal title={label} onClose={close} wide>
          <div className="flex flex-col gap-4">
            {contentType.startsWith('image/') ? (
              <img
                src={objectUrl}
                alt={label}
                className="max-h-[70vh] w-full rounded-xl border border-rule bg-panel2 object-contain"
              />
            ) : (
              <a href={objectUrl} target="_blank" rel="noreferrer" className="text-sm font-semibold text-brand underline">
                {t('Open document')}
              </a>
            )}
            <div className="flex justify-end">
              <Button onClick={close}>{t('Close')}</Button>
            </div>
          </div>
        </Modal>
      )}
    </>
  );
}

/** Structured reason plus free text, per architecture §3.3. */
const REJECTION_REASONS = [
  'nic_unreadable',
  'nic_mismatch',
  'slip_unreadable',
  'slip_amount_wrong',
  'details_incomplete',
  'suspected_fraud',
  'other',
];

function RejectModal({
  onClose,
  onSubmit,
  pending,
  error,
}: {
  onClose: () => void;
  onSubmit: (reason: string, note: string, allowResubmit: boolean) => void;
  pending: boolean;
  error: unknown;
}) {
  const { t } = useTranslation();
  const [reason, setReason] = useState(REJECTION_REASONS[0]!);
  const [note, setNote] = useState('');
  const [allowResubmit, setAllowResubmit] = useState(true);

  return (
    <Modal title={t('Reject registration')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          onSubmit(reason, note, allowResubmit);
        }}
      >
        <Field label={t('Reason')} hint={t('The applicant sees this, so it has to be actionable.')}>
          <Select value={reason} onChange={(event) => setReason(event.target.value)}>
            {REJECTION_REASONS.map((code) => (
              <option key={code} value={code}>
                {t(code.replace(/_/g, ' '))}
              </option>
            ))}
          </Select>
        </Field>

        <Field label={t('Note')}>
          <Input
            maxLength={1000}
            placeholder={t('What specifically needs fixing?')}
            value={note}
            onChange={(event) => setNote(event.target.value)}
          />
        </Field>

        <label className="flex items-center gap-2 text-sm text-ink2">
          <input
            type="checkbox"
            checked={allowResubmit}
            onChange={(event) => setAllowResubmit(event.target.checked)}
          />
          {t('Let them fix it and resubmit')}
        </label>

        <ErrorBanner error={error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="danger" disabled={pending}>
            {pending ? t('Rejecting…') : t('Reject')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}
