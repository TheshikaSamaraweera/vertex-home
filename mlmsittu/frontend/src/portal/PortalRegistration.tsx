import { useTranslation } from 'react-i18next';
import { ACCESS_COPY, usePortalMe, type PortalAccess } from '../api/portal';
import { RegisterBusinessPage } from '../pages/RegisterBusinessPage';
import { Badge, Card, humanStatus, PageHeader, Spinner, statusTone } from '../components/ui';

/**
 * The one screen every distributor sees, and for most of them the only one for a while.
 *
 * It answers three questions in order: where am I, what did the reviewer say, and what do I do
 * next. The form itself is the existing registration form — reused rather than rebuilt, so the
 * check-digit validation, the referrer capacity lookup and the upload sanitising all behave
 * identically wherever somebody registers from.
 */
export function PortalRegistration() {
  const { t } = useTranslation();
  const me = usePortalMe();

  if (me.isLoading) return <Spinner />;

  const access = (me.data?.access ?? 'REGISTRATION_REQUIRED') as PortalAccess;
  const copy = ACCESS_COPY[access];
  const registration = me.data?.registration;

  // The form is offered when there is something to submit — a first application, or a corrected
  // one. A pending or finally-rejected application has nothing for the applicant to do.
  const canSubmit = access === 'REGISTRATION_REQUIRED' || access === 'CHANGES_REQUESTED';

  // Nothing else in the portal opens until this is approved, and somebody who has just created an
  // account has no way to know that. The rest of the page explains where an application is; this
  // says, once and plainly, that there is one thing to do and this is it.
  const notStarted = access === 'REGISTRATION_REQUIRED';

  return (
    <>
      <PageHeader
        title={notStarted ? t('Finish setting up your account') : t('Business registration')}
        // The banner below carries this when there is nothing submitted yet. Saying it twice, once
        // quietly and once loudly, reads as two different messages that happen to agree.
        description={notStarted ? undefined : copy.body}
      />

      {notStarted && (
        <div className="mb-5 rounded-lg border-2 border-brand bg-brandsoft px-5 py-4">
          <p className="text-sm font-semibold text-ink">
            {t('One step left: your business registration')}
          </p>
          <p className="mt-1 text-sm text-ink2">
            {t('Your dashboard, your referrals and your reward stages all open once this is approved. Until then there is nothing else to do here.')}
          </p>
          <p className="mt-2 text-xs text-ink2">
            {t('You will need: the Business ID of whoever referred you, your NIC and a photo of it, and your bank transfer slip.')}
          </p>
        </div>
      )}

      <Card>
        <div className="flex flex-wrap items-start gap-4 p-5">
          <StepTrail access={access} />
        </div>
      </Card>

      {registration && (
        <Card className="mt-5" title={t('Your application')}>
          <div className="flex flex-wrap items-center gap-3 border-b border-rule px-5 py-4">
            <Badge tone={statusTone(registration.status ?? '')}>
              {humanStatus(registration.status)}
            </Badge>
            {registration.underReview && (
              <span className="text-xs text-ink2">{t('A reviewer has picked this up')}</span>
            )}
            <span className="ml-auto text-[11px] text-ink3">
              {registration.submittedAt
                ? t('Submitted {{when}}', {
                    when: new Date(registration.submittedAt).toLocaleString(),
                  })
                : ''}
            </span>
          </div>

          {registration.rejectionReason && (
            <div className="border-b border-rule bg-dangersoft px-5 py-4">
              <p className="text-sm font-semibold text-danger">
                {humanStatus(registration.rejectionReason)}
              </p>
              {registration.rejectionNote && (
                <p className="mt-1 text-sm text-ink2">{registration.rejectionNote}</p>
              )}
            </div>
          )}

          <div className="p-5">
            <p className="mb-3 text-xs font-semibold tracking-wide text-ink2 uppercase">
              {t('History')}
            </p>
            {(registration.timeline ?? []).length === 0 ? (
              <p className="text-sm text-ink3">{t('Nothing yet.')}</p>
            ) : (
              <ol className="flex flex-col gap-0">
                {(registration.timeline ?? []).map((entry, index) => (
                  <li key={index} className="flex gap-3">
                    <div className="flex flex-col items-center">
                      <span className="mt-1.5 h-2 w-2 flex-none rounded-full bg-brand" />
                      {index < (registration.timeline ?? []).length - 1 && (
                        <span className="w-px flex-1 bg-rule" />
                      )}
                    </div>
                    <div className="pb-4">
                      <p className="text-sm text-ink">
                        {humanStatus(entry.fromStatus)} → <strong>{humanStatus(entry.toStatus)}</strong>
                      </p>
                      {entry.comment && (
                        <p className="mt-0.5 text-xs text-ink2">{entry.comment}</p>
                      )}
                      <p className="text-[11px] text-ink3">
                        {entry.at ? new Date(entry.at).toLocaleString() : ''}
                      </p>
                    </div>
                  </li>
                ))}
              </ol>
            )}
          </div>
        </Card>
      )}

      {canSubmit && (
        <div className="mt-5">
          <RegisterBusinessPage />
        </div>
      )}
    </>
  );
}

/**
 * The three steps, with the current one marked.
 *
 * Shown because the client's rule — nothing opens until approval — is otherwise experienced as a
 * blank application with no explanation of what is being waited for.
 */
function StepTrail({ access }: { access: PortalAccess }) {
  const { t } = useTranslation();

  const steps = [
    { label: t('Account created'), done: true },
    {
      label: t('Business registered'),
      done: access !== 'REGISTRATION_REQUIRED',
      current: access === 'REGISTRATION_REQUIRED' || access === 'CHANGES_REQUESTED',
    },
    {
      label: t('Approved by the office'),
      done: access === 'ACTIVE',
      current: access === 'PENDING_REVIEW',
      failed: access === 'REJECTED',
    },
  ];

  return (
    <ol className="flex w-full flex-wrap items-center gap-2">
      {steps.map((step, index) => (
        <li key={step.label} className="flex flex-1 items-center gap-2">
          <span
            className={
              'flex h-7 w-7 flex-none items-center justify-center rounded-full text-xs font-bold ' +
              (step.failed
                ? 'bg-danger text-panel'
                : step.done
                  ? 'bg-ok text-panel'
                  : step.current
                    ? 'bg-brand text-brandink'
                    : 'bg-panel2 text-ink3')
            }
          >
            {step.failed ? '!' : step.done ? '✓' : index + 1}
          </span>
          <span
            className={
              'text-xs ' + (step.done || step.current ? 'font-medium text-ink' : 'text-ink3')
            }
          >
            {step.label}
          </span>
          {index < steps.length - 1 && <span className="h-px flex-1 bg-rule" />}
        </li>
      ))}
    </ol>
  );
}
