import { useEffect, useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  fetchDocumentObjectUrl,
  useApproveRegistration,
  useClaimRegistration,
  useRegistrationDetail,
  useRejectRegistration,
  useReleaseRegistration,
  useReviewQueue,

} from '../api/onboarding';
import { useAuth } from '../auth/AuthContext';
import {
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
  statusTone,
} from '../components/ui';

/**
 * The registration review queue — the highest-volume admin surface in the system.
 *
 * Called "Registration verification" throughout the UI, never "KYC". The development plan is
 * explicit: use KYC in code where developers will find material on it, and plain language where
 * reviewers will read it.
 */
export function ReviewQueuePage() {
  const { t } = useTranslation();
  const { user } = useAuth();

  const queue = useReviewQueue();
  const [selectedId, setSelectedId] = useState<string | null>(null);
  const detail = useRegistrationDetail(selectedId);

  const claim = useClaimRegistration();
  const release = useReleaseRegistration();
  const approve = useApproveRegistration();
  const reject = useRejectRegistration();

  const [rejecting, setRejecting] = useState(false);
  const [approved, setApproved] = useState<string | null>(null);

  const registration = detail.data?.registration;
  const isMine = registration?.claimedBy === user?.id;
  const isOwnRegistration = registration?.userId === user?.id;

  return (
    <>
      <PageHeader
        title={t('Registration verification')}
        description={t('Claim a record before deciding on it. One reviewer per record, and never your own.')}
      />

      {approved && (
        <div className="mb-4 rounded border border-ok bg-oksoft px-4 py-3 text-sm text-ink">
          <p>
            {t('Approved. Business ID')}{' '}
            <span className="font-mono text-base font-semibold">{approved}</span>{' '}
            {t('has been allocated and is now permanent.')}
          </p>
          {/* The number is the point of the whole screen, and until now it was stated and left
              there. Whoever is at the desk has to give it to somebody, and saying so is the
              difference between a confirmation and an instruction. */}
          <p className="mt-1 text-xs text-ink2">
            {t('Give this ID to the five people they recruit — it is what each of them enters as their referrer.')}
          </p>
        </div>
      )}
      {(claim.error || approve.error || release.error) && (
        <div className="mb-4">
          <ErrorBanner error={claim.error ?? approve.error ?? release.error} />
        </div>
      )}

      <div className="grid gap-5 lg:grid-cols-[minmax(0,20rem)_minmax(0,1fr)]">
        <Card title={t('Queue')} subtitle={t('{{count}} awaiting review', { count: queue.data?.length ?? 0 })}>
          {queue.isLoading ? (
            <Spinner />
          ) : queue.error ? (
            <div className="p-4">
              <ErrorBanner error={queue.error} onRetry={() => void queue.refetch()} />
            </div>
          ) : (queue.data ?? []).length === 0 ? (
            <EmptyState message={t('Nothing waiting.')} hint={t('New submissions appear here.')} />
          ) : (
            <ul className="divide-y divide-rule">
              {(queue.data ?? []).map((item) => (
                <li key={item.id}>
                  <button
                    type="button"
                    onClick={() => setSelectedId(item.id ?? null)}
                    className={
                      'w-full px-4 py-3 text-left transition-colors hover:bg-panel2 ' +
                      (selectedId === item.id ? 'bg-brandsoft' : '')
                    }
                  >
                    <div className="flex items-center justify-between gap-2">
                      <span className="truncate text-sm text-ink">{item.applicantName}</span>
                      <Badge tone={statusTone(item.status ?? '')}>{item.status}</Badge>
                    </div>
                    <p className="mt-0.5 truncate text-[11px] text-ink3">{item.applicantEmail}</p>
                    {item.claimedByName && (
                      <p className="mt-1 text-[11px] text-warn">
                        {t('claimed by')} {item.claimedByName}
                      </p>
                    )}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </Card>

        {!selectedId ? (
          <Card>
            <EmptyState message={t('Select a record to review.')} />
          </Card>
        ) : detail.isLoading ? (
          <Card>
            <Spinner />
          </Card>
        ) : registration ? (
          <Card
            title={registration.applicantName ?? ''}
            subtitle={registration.applicantEmail ?? ''}
            actions={
              <>
                {!isMine && !isOwnRegistration && (
                  <Button
                    size="sm"
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
                      size="sm"
                      onClick={() => selectedId && release.mutate(selectedId)}
                      disabled={release.isPending}
                    >
                      {t('Release')}
                    </Button>
                    <Button size="sm" variant="danger" onClick={() => setRejecting(true)}>
                      {t('Reject')}
                    </Button>
                    <Button
                      size="sm"
                      variant="primary"
                      disabled={approve.isPending}
                      onClick={() =>
                        selectedId &&
                        approve.mutate(selectedId, {
                          onSuccess: (result) => setApproved(result.businessId),
                        })
                      }
                    >
                      {approve.isPending ? t('Approving…') : t('Approve')}
                    </Button>
                  </>
                )}
              </>
            }
          >
            {/* The two barred paths, said plainly rather than left as a mystery 403. */}
            {isOwnRegistration && (
              <div className="mx-4 mt-4 rounded border border-danger bg-dangersoft px-3 py-2 text-xs text-danger">
                {t('This is your own registration. Someone else has to review it.')}
              </div>
            )}
            {registration.claimedByName && !isMine && (
              <div className="mx-4 mt-4 rounded border border-warn bg-warnsoft px-3 py-2 text-xs text-ink2">
                {t('{{name}} is reviewing this record.', { name: registration.claimedByName })}
              </div>
            )}

            <dl className="grid gap-x-6 gap-y-3 p-4 sm:grid-cols-2">
              <Detail label={t('Referrer')} value={registration.referrerBusinessId} mono />
              <Detail label={t('NIC')} value={`•••• ${registration.nicLast4 ?? '????'}`} mono />
              <Detail label={t('Address')} value={registration.fullAddress} />
              <Detail label={t('Bank')} value={registration.bankName} />
              <Detail label={t('Branch')} value={registration.bankBranch} />
              <Detail label={t('Account')} value={registration.bankAccountNumber} mono />
            </dl>

            <div className="border-t border-rule p-4">
              <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
                {t('Documents')}
              </p>
              <div className="grid gap-4 sm:grid-cols-2">
                <DocumentViewer
                  label={t('NIC image')}
                  documentId={registration.nicDocumentId}
                  subjectUserId={registration.userId}
                />
                <DocumentViewer
                  label={t('Bank slip')}
                  documentId={registration.slipDocumentId}
                  subjectUserId={registration.userId}
                />
              </div>
              <p className="mt-3 text-[11px] text-ink3">
                {t('Every time you open a document it is recorded against your name, with the time and your IP address.')}
              </p>
            </div>

            <div className="border-t border-rule p-4">
              <p className="mb-2 text-xs font-semibold tracking-wide text-ink2 uppercase">
                {t('History')}
              </p>
              <ol className="flex flex-col gap-1.5">
                {(detail.data?.timeline ?? []).map((event, index) => (
                  <li key={index} className="flex flex-wrap items-baseline gap-2 text-xs">
                    <span className="font-mono text-[11px] text-ink3">
                      {event.createdAt
                        ? new Date(event.createdAt).toLocaleString(undefined, {
                            dateStyle: 'short',
                            timeStyle: 'short',
                          })
                        : ''}
                    </span>
                    <span className="text-ink">
                      {event.fromStatus ? `${event.fromStatus} → ` : ''}
                      {event.toStatus}
                    </span>
                    {event.actorName && <span className="text-ink3">{event.actorName}</span>}
                    {event.reason && <span className="text-danger">{event.reason}</span>}
                  </li>
                ))}
              </ol>
            </div>
          </Card>
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
              { onSuccess: () => setRejecting(false) },
            )
          }
        />
      )}
    </>
  );
}

function Detail({ label, value, mono }: { label: string; value?: string | null; mono?: boolean }) {
  return (
    <div>
      <dt className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">{label}</dt>
      <dd className={'mt-0.5 text-sm text-ink ' + (mono ? 'font-mono' : '')}>{value || '—'}</dd>
    </div>
  );
}

/**
 * Fetches a document only when asked, and revokes the object URL on unmount.
 *
 * Not eager-loaded on purpose: opening the record should not count as viewing someone's identity
 * document. The access log should reflect a decision a reviewer made, not a side effect of
 * scrolling past.
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

  // A different record is selected — drop the previous image rather than showing it under the
  // new applicant's name.
  useEffect(() => {
    setObjectUrl(null);
    setError(null);
  }, [documentId]);

  if (!documentId) {
    return (
      <div className="rounded border border-rule bg-panel2 p-3 text-xs text-ink3">
        {label} — {t('not provided')}
      </div>
    );
  }

  return (
    <div className="rounded border border-rule p-3">
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs font-medium text-ink">{label}</span>
        {!objectUrl && (
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
        )}
      </div>

      {error && <p className="mt-2 text-xs text-danger">{error}</p>}

      {objectUrl && (
        <div className="mt-2">
          {contentType.startsWith('image/') ? (
            <img
              src={objectUrl}
              alt={label}
              className="max-h-72 w-full rounded border border-rule object-contain"
            />
          ) : (
            <a href={objectUrl} target="_blank" rel="noreferrer" className="text-xs text-brand underline">
              {t('Open document')}
            </a>
          )}
        </div>
      )}
    </div>
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
