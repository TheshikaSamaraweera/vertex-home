import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { api, ApiError } from '../api/client';
import {
  uploadDocument,
  useItemPackOptions,
  useMyRegistrations,
  useReferrerCheck,
} from '../api/onboarding';
import { inspectBusinessId } from '../lib/businessId';
import { useAuth } from '../auth/AuthContext';
import {
  Badge,
  Button,
  Card,
  ErrorBanner,
  Field,
  Input,
  PageHeader,
  Select,
  Spinner,
  statusTone,
} from '../components/ui';

/**
 * Business registration (F-03, step one of the applicant's side).
 *
 * The referrer field is the interesting part. The Business ID carries a check character precisely
 * so a typo is caught here, in the field, before it can attach someone to the wrong upline — so
 * validation runs as the user types and the server is only asked once the ID is arithmetically
 * sound.
 */
export function RegisterBusinessPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const existing = useMyRegistrations();

  // Administrators register other people; everybody else registers themselves, and never
  // sees the choice.
  const canRegisterOthers = hasRole('ADMIN');
  const [mode, setMode] = useState<'self' | 'other'>(canRegisterOthers ? 'other' : 'self');

  const [referrerInput, setReferrerInput] = useState('');
  const [nicNumber, setNicNumber] = useState('');
  const [fullAddress, setFullAddress] = useState('');
  const [bankName, setBankName] = useState('');
  const [bankBranch, setBankBranch] = useState('');
  const [bankAccountNumber, setBankAccountNumber] = useState('');
  const [itemSetId, setItemSetId] = useState('');

  // An administrator can fill this in for somebody else. The client asked for it because most of
  // their distributors are not comfortable with a signup form and an email link — so somebody at
  // a desk takes their details and does both steps for them.
  const forSomeoneElse = canRegisterOthers && mode === 'other';
  const [account, setAccount] = useState({
    fullName: '',
    email: '',
    mobile: '',
    password: '',
  });
  const [nicFile, setNicFile] = useState<File | null>(null);
  const [slipFile, setSlipFile] = useState<File | null>(null);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [submitted, setSubmitted] = useState(false);
  // The address the server assigned. Usually what was typed — but for a customer with no e-mail of
  // their own, the office address is entered and a distinct alias of it comes back. That alias is
  // the login, so it has to be shown rather than assumed.
  const [assignedEmail, setAssignedEmail] = useState<string | null>(null);

  const referrerState = inspectBusinessId(referrerInput);
  const referrerLookup = useReferrerCheck(
    referrerState.status === 'valid' ? referrerState.normalised : null,
  );

  const openRegistration = (existing.data ?? []).find((registration) =>
    ['submitted', 'under_review', 'resubmit_required'].includes(registration.status ?? ''),
  );

  const referrerUsable =
    referrerState.status === 'valid' &&
    referrerLookup.data?.valid === true &&
    referrerLookup.data?.hasCapacity === true;

  const accountReady =
    !forSomeoneElse ||
    (account.fullName.trim() && account.email.trim() && account.password.length >= 12);

  const canSubmit =
    referrerUsable &&
    nicNumber.trim() &&
    fullAddress.trim() &&
    nicFile &&
    slipFile &&
    accountReady &&
    !busy;

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true);
    setError(null);
    try {
      // Documents first: an upload that fails on magic bytes or size should not leave a
      // half-built registration behind.
      const nicDocumentId = await uploadDocument(nicFile!, 'nic');
      const slipDocumentId = await uploadDocument(slipFile!, 'bank_slip');

      const details = {
        nicNumber,
        nicDocumentId,
        slipDocumentId,
        referrerBusinessId: referrerState.status === 'valid' ? referrerState.normalised : '',
        fullAddress,
        bankName,
        bankBranch,
        bankAccountNumber,
        itemSetId: itemSetId || undefined,
      };

      if (forSomeoneElse) {
        // Account first, then the registration against it. Two calls rather than one endpoint,
        // because a duplicate email must not throw away a completed form.
        const created = await api.post<{ id: string; email: string }>('/api/v1/admin/users', {
          fullName: account.fullName,
          email: account.email,
          mobile: account.mobile || undefined,
          password: account.password,
        });
        setAssignedEmail(created.email);
        await api.post('/api/v1/admin/registrations', { userId: created.id, ...details });
      } else {
        await api.post('/api/v1/registrations', details);
      }

      setSubmitted(true);
      void existing.refetch();
    } catch (caught) {
      setError(caught);
    } finally {
      setBusy(false);
    }
  }

  if (existing.isLoading) return <Spinner />;

  // An administrator's own registration status says nothing about whether they may file one for
  // somebody else, so it must not lock the screen while they are doing that.
  if ((submitted && !forSomeoneElse) || (openRegistration && !canRegisterOthers)) {
    return (
      <>
        <PageHeader title={t('Registration verification')} />
        <Card title={t('Your registration')}>
          <div className="p-4">
            {(existing.data ?? []).map((registration) => (
              <div
                key={registration.id}
                className="mb-3 flex flex-wrap items-center justify-between gap-3 rounded border border-rule p-3"
              >
                <div>
                  <Badge tone={statusTone(registration.status ?? '')}>{registration.status}</Badge>
                  <p className="mt-1.5 text-xs text-ink2">
                    {t('Referrer')}{' '}
                    <span className="font-mono">{registration.referrerBusinessId}</span>
                  </p>
                  {registration.rejectionReason && (
                    <p className="mt-1 text-xs text-danger">
                      {registration.rejectionReason.replace(/_/g, ' ')}
                      {registration.rejectionNote ? ` — ${registration.rejectionNote}` : ''}
                    </p>
                  )}
                </div>
                <p className="text-[11px] text-ink3">
                  {registration.submittedAt
                    ? new Date(registration.submittedAt).toLocaleString()
                    : ''}
                </p>
              </div>
            ))}
            <p className="text-sm text-ink2">
              {t('A reviewer will check your documents. You will keep your place in the queue.')}
            </p>
          </div>
        </Card>
      </>
    );
  }

  return (
    <>
      <PageHeader
        title={forSomeoneElse ? t('User registration') : t('Business registration')}
        description={
          forSomeoneElse
            ? t('Register somebody in person. You create their account and file their registration; a reviewer still checks it by hand.')
            : t('Your referrer’s ID, your NIC, and a bank transfer slip. A reviewer checks it by hand.')
        }
        actions={
          canRegisterOthers && (
            <div className="flex overflow-hidden rounded-md border border-rule">
              <ModeTab active={mode === 'other'} onClick={() => setMode('other')}>
                {t('Register someone')}
              </ModeTab>
              <ModeTab active={mode === 'self'} onClick={() => setMode('self')}>
                {t('Register myself')}
              </ModeTab>
            </div>
          )
        }
      />

      {submitted && forSomeoneElse && (
        <div className="mb-4 rounded border border-ok bg-oksoft px-4 py-3 text-sm text-ink">
          <p>{t('Registered. It is in the review queue now — the next one can be started below.')}</p>
          {assignedEmail && (
            <>
              <p className="mt-2 text-xs text-ink2">
                {t('They sign in with this. Write it down before starting the next one — it is not shown again.')}
              </p>
              <p className="mt-1 font-mono text-sm break-all select-all">{assignedEmail}</p>
              {assignedEmail !== account.email.trim().toLowerCase() && (
                <p className="mt-1 text-xs text-ink2">
                  {t('This is a sub-address of the office inbox, generated because this customer has no e-mail of their own. Mail sent to it arrives in the office inbox as normal.')}
                </p>
              )}
            </>
          )}
        </div>
      )}

      <form onSubmit={submit} className="flex max-w-2xl flex-col gap-5">
        <Card title={t('Referrer')}>
          <div className="p-4">
            <Field
              label={t('Referrer Business ID')}
              hint={t('On their card, like 143 — the digits, nothing else')}
              error={
                referrerState.status === 'malformed' || referrerState.status === 'failed-check'
                  ? referrerState.message
                  : undefined
              }
            >
              <Input
                required
                autoFocus
                className="font-mono"
                placeholder="143"
                value={referrerInput}
                onChange={(event) => setReferrerInput(event.target.value)}
              />
            </Field>

            {/* The check character has already passed locally by this point, so anything shown
                here is a fact about the distributor, not about the typing. */}
            {referrerState.status === 'valid' && (
              <div className="mt-2 text-xs">
                {referrerLookup.isLoading && <span className="text-ink3">{t('Checking…')}</span>}
                {referrerLookup.data?.valid === false && (
                  <span className="text-danger">
                    {t('No active customer has that ID. The ID is well-formed, so check you have the right person.')}
                  </span>
                )}
                {referrerLookup.data?.valid && (
                  <div className="rounded border border-rule bg-panel2 px-3 py-2">
                    <p className="text-ink">{referrerLookup.data.name}</p>
                    {referrerLookup.data.hasCapacity ? (
                      <p className="mt-0.5 text-ink3">
                        {t('{{used}} of {{max}} referral places used', {
                          used: referrerLookup.data.currentReferrals,
                          max: referrerLookup.data.maxDirect,
                        })}
                      </p>
                    ) : (
                      <p className="mt-0.5 text-danger">
                        {t('This customer already has the maximum number of referrals. You will need a different referrer.')}
                      </p>
                    )}
                  </div>
                )}
              </div>
            )}
          </div>
        </Card>

        <Card title={t('Your details')}>
          <div className="grid gap-4 p-4 sm:grid-cols-2">
            <Field
              label={t('NIC number')}
              hint={t('Stored encrypted. Never shown in full to anyone.')}
            >
              <Input
                required
                maxLength={20}
                className="font-mono"
                value={nicNumber}
                onChange={(event) => setNicNumber(event.target.value)}
              />
            </Field>
            <Field label={t('Address')}>
              <Input
                required
                maxLength={500}
                value={fullAddress}
                onChange={(event) => setFullAddress(event.target.value)}
              />
            </Field>
            <Field label={t('Bank')}>
              <Input value={bankName} onChange={(event) => setBankName(event.target.value)} />
            </Field>
            <Field label={t('Branch')}>
              <Input value={bankBranch} onChange={(event) => setBankBranch(event.target.value)} />
            </Field>
            <Field label={t('Account number')}>
              <Input
                className="font-mono"
                value={bankAccountNumber}
                onChange={(event) => setBankAccountNumber(event.target.value)}
              />
            </Field>
          </div>
        </Card>

        {forSomeoneElse && (
          <Card
            title={t('Their account')}
            subtitle={t('Created straight away and already verified — no email link for them to follow')}
          >
            <div className="grid gap-4 p-4 sm:grid-cols-2">
              <Field label={t('Full name')}>
                <Input
                  required
                  value={account.fullName}
                  onChange={(event) =>
                    setAccount({ ...account, fullName: event.target.value })
                  }
                />
              </Field>
              <Field
                label={t('Email')}
                hint={t('They sign in with this. No e-mail of their own? Enter the office address and one will be generated from it.')}
              >
                <Input
                  required
                  type="email"
                  value={account.email}
                  onChange={(event) => setAccount({ ...account, email: event.target.value })}
                />
              </Field>
              <Field label={t('Mobile')}>
                <Input
                  value={account.mobile}
                  onChange={(event) => setAccount({ ...account, mobile: event.target.value })}
                />
              </Field>
              <Field
                label={t('Temporary password')}
                hint={t('At least 12 characters. Write it down for them — there is no self-service reset yet.')}
              >
                <Input
                  required
                  minLength={12}
                  value={account.password}
                  onChange={(event) => setAccount({ ...account, password: event.target.value })}
                />
              </Field>
            </div>
          </Card>
        )}

        <Card
          title={t('Your item pack')}
          subtitle={t('Yours to claim once you have referred four people')}
        >
          <div className="p-4">
            <ItemPackPicker value={itemSetId} onChange={setItemSetId} />
          </div>
        </Card>

        <Card title={t('Documents')} subtitle={t('JPEG, PNG or PDF, up to 10 MB each')}>
          <div className="grid gap-4 p-4 sm:grid-cols-2">
            <FilePicker label={t('NIC image')} file={nicFile} onPick={setNicFile} />
            <FilePicker label={t('Bank transfer slip')} file={slipFile} onPick={setSlipFile} />
          </div>
          <p className="px-4 pb-4 text-[11px] text-ink3">
            {t('Photos are re-saved on upload, which removes location data and anything else your camera embedded.')}
          </p>
        </Card>

        <ErrorBanner error={error} />

        <div className="flex justify-end">
          <Button type="submit" variant="primary" disabled={!canSubmit}>
            {busy ? t('Submitting…') : t('Submit for review')}
          </Button>
        </div>
      </form>
    </>
  );
}

function FilePicker({
  label,
  file,
  onPick,
}: {
  label: string;
  file: File | null;
  onPick: (file: File | null) => void;
}) {
  const { t } = useTranslation();
  const tooBig = file ? file.size > 10 * 1024 * 1024 : false;

  return (
    <Field
      label={label}
      error={tooBig ? t('That file is larger than 10 MB.') : undefined}
      hint={file && !tooBig ? `${(file.size / 1024).toFixed(0)} KB` : undefined}
    >
      <input
        type="file"
        accept="image/jpeg,image/png,application/pdf"
        required
        onChange={(event) => onPick(event.target.files?.[0] ?? null)}
        className="w-full rounded border border-rule bg-panel px-3 py-2 text-sm text-ink2 file:mr-3 file:rounded file:border-0 file:bg-panel2 file:px-3 file:py-1 file:text-xs file:text-ink"
      />
    </Field>
  );
}

/** Surfaces the machine code so a rejected upload is diagnosable. */
export function uploadErrorCode(error: unknown): string | null {
  if (error instanceof ApiError) return error.code;
  if (error && typeof error === 'object' && 'code' in error) return String(error.code);
  return null;
}

/**
 * Choosing the pack now, collecting it later.
 *
 * The choice is made at registration and settled at approval, and it is what completing all four
 * referral stages entitles somebody to. That is worth saying on the form: a dropdown labelled
 * "item pack" with no explanation is a decision made blind.
 *
 * Optional, deliberately. Somebody who does not want a pack should not be blocked from
 * registering, and the reward mechanic treats "chose none" as nothing owed rather than as an
 * error.
 */
function ItemPackPicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (id: string) => void;
}) {
  const { t } = useTranslation();
  const packs = useItemPackOptions();

  const chosen = (packs.data ?? []).find((pack) => pack.id === value);

  return (
    <div className="flex flex-col gap-3">
      <Field
        label={t('Item pack')}
        hint={t('Optional. You can be approved without one, but then there is nothing to claim later.')}
      >
        <Select value={value} onChange={(event) => onChange(event.target.value)}>
          <option value="">{t('No pack')}</option>
          {(packs.data ?? []).map((pack) => (
            <option key={pack.id} value={pack.id ?? ''}>
              {pack.code} — {pack.name}
            </option>
          ))}
        </Select>
      </Field>

      {chosen && (
        <div className="rounded-lg border border-brand/25 bg-brandsoft p-3 text-xs text-ink2">
          {t('Refer four people and {{name}} becomes yours to claim. An administrator hands it over — nothing is charged for it.', {
            name: chosen.name,
          })}
        </div>
      )}
    </div>
  );
}

/**
 * Which person this form is about.
 *
 * Only administrators see it. For everybody else there is one answer and offering a choice would
 * just be a control that does nothing.
 */
function ModeTab({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      aria-pressed={active}
      className={
        'px-3 py-1.5 text-[13px] transition-colors ' +
        (active ? 'bg-brand font-semibold text-brandink' : 'bg-panel text-ink2 hover:text-brand')
      }
    >
      {children}
    </button>
  );
}
