import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  asPercent,
  useApproveOfficer,
  useEnrolOfficer,
  useMarketingOfficers,
  useOfficerApplications,
  useOfficerCustomers,
  useRegisterOfficer,
  useRejectOfficer,
  useSetOfficerRate,
  type MarketingOfficer,
} from '../api/officers';
import { useUsers } from '../api/queries';
import {
  isAcceptablePassword,
  MAX_LENGTH,
  MIN_LENGTH,
  PASSWORD_RULE,
  passwordProblem,
} from '../lib/password';
import { Avatar } from './MyAccountPage';
import { expiryStatus } from '../lib/expiry';
import {
  Badge,
  Button,
  Card,
  ErrorBanner,
  Field,
  Input,
  Instructions,
  Modal,
  PageHeader,
  PasswordInput,
  Select,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * Marketing officers, and what the customers under them have earned.
 *
 * <p>The earnings here are informational — nothing records a payment or tracks a balance. The
 * figure says what one per cent of the issued packs comes to, and settling up is the client's
 * accounting rather than this application's.
 */
export function MarketingOfficersPage() {
  const { t } = useTranslation();
  const officers = useMarketingOfficers();
  const applications = useOfficerApplications();

  const [enrolling, setEnrolling] = useState(false);
  const [registering, setRegistering] = useState(false);
  const [viewing, setViewing] = useState<MarketingOfficer | null>(null);
  const [rateFor, setRateFor] = useState<MarketingOfficer | null>(null);
  const [decidingOn, setDecidingOn] = useState<MarketingOfficer | null>(null);

  if (officers.isLoading) return <Spinner />;

  const waiting = applications.data ?? [];

  return (
    <>
      <PageHeader
        title={t('Marketing officers')}
        description={t('Who brings customers in, and what the packs those customers earned have come to.')}
        actions={
          <div className="flex gap-2">
            <Button onClick={() => setEnrolling(true)}>{t('Use an existing account')}</Button>
            <Button variant="primary" onClick={() => setRegistering(true)}>
              {t('Register an officer')}
            </Button>
          </div>
        }
      />

      {/* The queue first, and only when there is something in it. An application that nobody has
          looked at is the one thing on this screen that is waiting on a person. */}
      {waiting.length > 0 && (
        <Card title={t('Waiting for approval ({{count}})', { count: waiting.length })}>
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Name')}</Th>
                  <Th>{t('Contact')}</Th>
                  <Th>{t('Applied')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {waiting.map((applicant) => (
                  <tr key={applicant.userId}>
                    <Td>
                      <span className="flex items-center gap-2.5">
                        <Avatar
                          photoId={applicant.profilePhotoId}
                          name={applicant.fullName}
                          size={30}
                        />
                        <span className="text-ink">{applicant.fullName}</span>
                      </span>
                    </Td>
                    <Td className="text-xs">
                      <p>{applicant.email ?? '—'}</p>
                      <p className="text-ink3">{applicant.mobile ?? ''}</p>
                    </Td>
                    <Td className="text-xs text-ink2">{whenApplied(applicant.appliedAt)}</Td>
                    <Td>
                      <div className="flex justify-end">
                        <Button
                          size="sm"
                          variant="primary"
                          onClick={() => setDecidingOn(applicant)}
                        >
                          {t('Review')}
                        </Button>
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        </Card>
      )}

      {waiting.length > 0 && <div className="h-5" />}

      {(officers.data ?? []).length === 0 ? (
        <Card>
          <p className="px-5 py-12 text-center text-sm text-ink3">
            {t('Nobody yet. Create an account under Users and roles first, then add them here.')}
          </p>
        </Card>
      ) : (
        <Card title={t('Officers')}>
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Name')}</Th>
                  <Th>{t('Contact')}</Th>
                  <Th align="right">{t('Customers')}</Th>
                  <Th align="right">{t('Rate')}</Th>
                  <Th align="right">{t('Earned')}</Th>
                  <Th>{''}</Th>
                </tr>
              </thead>
              <tbody>
                {(officers.data ?? []).map((officer) => (
                  <tr key={officer.userId}>
                    <Td>
                      <span className="flex items-center gap-2.5">
                        <Avatar
                          photoId={officer.profilePhotoId}
                          name={officer.fullName}
                          size={30}
                        />
                        <span className="text-ink">{officer.fullName}</span>
                      </span>
                    </Td>
                    <Td className="text-xs">
                      <p>{officer.email ?? '—'}</p>
                      <p className="text-ink3">{officer.mobile ?? ''}</p>
                    </Td>
                    <Td align="right">{officer.customerCount}</Td>
                    <Td align="right" className="font-semibold text-brand">
                      {asPercent(officer.commissionRate)}
                    </Td>
                    <Td align="right" className="font-semibold">
                      {money(officer.earned)}
                    </Td>
                    <Td>
                      <div className="flex justify-end gap-2">
                        <Button size="sm" onClick={() => setViewing(officer)}>
                          {t('Their customers')}
                        </Button>
                        <Button size="sm" onClick={() => setRateFor(officer)}>
                          {t('Change rate')}
                        </Button>
                      </div>
                    </Td>
                  </tr>
                ))}
              </tbody>
            </Table>
          </TableWrap>
        </Card>
      )}

      {enrolling && <EnrolModal onClose={() => setEnrolling(false)} />}
      {registering && <RegisterOfficerModal onClose={() => setRegistering(false)} />}
      {decidingOn && (
        <DecisionModal applicant={decidingOn} onClose={() => setDecidingOn(null)} />
      )}
      {rateFor && <RateModal officer={rateFor} onClose={() => setRateFor(null)} />}
      {viewing && (
        <Modal
          title={t('{{name}} — customers', { name: viewing.fullName })}
          onClose={() => setViewing(null)}
          wide
        >
          <AssignedCustomerTable userId={viewing.userId} />
        </Modal>
      )}
    </>
  );
}

/** The officer's customers, used by the admin modal and by the officer's own portal. */
export function AssignedCustomerTable({ userId }: { userId: string | null }) {
  const { t } = useTranslation();
  const customers = useOfficerCustomers(userId);

  if (customers.isLoading) return <Spinner />;
  if ((customers.data ?? []).length === 0) {
    return (
      <p className="px-5 py-10 text-center text-sm text-ink3">
        {t('No customers assigned yet.')}
      </p>
    );
  }

  return (
    <TableWrap>
      <Table>
        <thead>
          <tr>
            <Th>{t('Business ID')}</Th>
            <Th>{t('Customer')}</Th>
            <Th>{t('Level')}</Th>
            <Th>{t('Membership')}</Th>
            <Th>{t('Item pack')}</Th>
            <Th align="right">{t('Earned')}</Th>
          </tr>
        </thead>
        <tbody>
          {(customers.data ?? []).map((row) => {
            const expiry = expiryStatus(row.expiresAt);
            return (
              <tr key={row.distributorId}>
                <Td className="font-mono text-xs text-brand">{row.businessId ?? '—'}</Td>
                <Td className="text-ink">{row.fullName}</Td>
                <Td>
                  <StageDots done={row.stagesCompleted} total={row.totalStages} />
                </Td>
                <Td>
                  {expiry.band === 'none' ? (
                    <span className="text-xs text-ink3">—</span>
                  ) : (
                    <span
                      className={`inline-flex rounded-full border px-2 py-0.5 text-[11px] whitespace-nowrap ${expiry.classes}`}
                    >
                      {expiry.label}
                    </span>
                  )}
                </Td>
                <Td className="text-xs">
                  {row.packName ?? '—'}
                  {row.packIssued ? (
                    <Badge tone="ok">{t('issued')}</Badge>
                  ) : (
                    <span className="ml-1.5 text-ink3">{t('not yet')}</span>
                  )}
                </Td>
                <Td align="right" className={row.earned > 0 ? 'font-semibold text-ok' : 'text-ink3'}>
                  {/* Zero until the pack is actually handed over. A customer part-way through
                      their referrals has earned their officer nothing yet, and showing a figure
                      would read as money owed. */}
                  {row.earned > 0 ? money(row.earned) : '—'}
                </Td>
              </tr>
            );
          })}
        </tbody>
      </Table>
    </TableWrap>
  );
}

/** Five dots: how many referral stages are done. Readable at a glance across a long list. */
export function StageDots({ done, total }: { done: number; total: number }) {
  const { t } = useTranslation();
  return (
    <span
      className="flex items-center gap-1"
      title={t('{{done}} of {{total}} stages complete', { done, total })}
    >
      {Array.from({ length: total }, (_, index) => (
        <span
          key={index}
          aria-hidden
          className={
            'h-2.5 w-2.5 rounded-full ' + (index < done ? 'bg-brand' : 'bg-rule')
          }
        />
      ))}
      <span className="ml-1 text-xs text-ink2">
        {done}/{total}
      </span>
    </span>
  );
}

function EnrolModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const users = useUsers();
  const enrol = useEnrolOfficer();

  const [userId, setUserId] = useState('');
  const [rate, setRate] = useState('1');

  return (
    <Modal title={t('Add a marketing officer')} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <Instructions title={t('The account has to exist first')}>
          {t('Create it under Users and roles, then choose it here. An officer signs in to their own portal and sees the customers assigned to them — they have no business registration and no Business ID.')}
        </Instructions>

        <Field label={t('Account')} required>
          <Select required value={userId} onChange={(event) => setUserId(event.target.value)}>
            <option value="">{t('Choose an account…')}</option>
            {(users.data ?? []).map((user) => (
              <option key={user.id} value={user.id ?? ''}>
                {user.fullName} — {user.email ?? user.mobile}
              </option>
            ))}
          </Select>
        </Field>

        <Field
          label={t('Commission rate')}
          required
          hint={t('A percentage. 1 means one per cent of each issued pack.')}
        >
          <Input
            type="number"
            step="0.01"
            min="0"
            max="100"
            value={rate}
            onChange={(event) => setRate(event.target.value)}
          />
        </Field>

        <ErrorBanner error={enrol.error} />

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            variant="primary"
            disabled={!userId || enrol.isPending}
            onClick={() =>
              enrol.mutate(
                // Stored as a fraction; typed as a percentage, because nobody writes 0.01 when
                // they mean one per cent.
                { userId, commissionRate: Number(rate) / 100 },
                { onSuccess: onClose },
              )
            }
          >
            {enrol.isPending ? t('Adding…') : t('Add')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function RateModal({ officer, onClose }: { officer: MarketingOfficer; onClose: () => void }) {
  const { t } = useTranslation();
  const setRate = useSetOfficerRate();
  const [rate, setRateValue] = useState(String(officer.commissionRate * 100));

  return (
    <Modal title={t('{{name}} — commission rate', { name: officer.fullName })} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <Field
          label={t('Commission rate')}
          required
          hint={t('A percentage of each issued pack. The default is 1.')}
        >
          <Input
            type="number"
            step="0.01"
            min="0"
            max="100"
            autoFocus
            value={rate}
            onChange={(event) => setRateValue(event.target.value)}
          />
        </Field>

        <p className="text-xs text-ink2">
          {/* Said plainly, because it is the surprising part: the figure was never a debt, so
              changing the rate changes what every past pack is shown as having earned. */}
          {t('This changes what is shown for packs already issued as well as future ones — the figure is a statement about a percentage, not a record of money owed.')}
        </p>

        <ErrorBanner error={setRate.error} />

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            variant="primary"
            disabled={setRate.isPending}
            onClick={() =>
              setRate.mutate(
                { userId: officer.userId, commissionRate: Number(rate) / 100 },
                { onSuccess: onClose },
              )
            }
          >
            {setRate.isPending ? t('Saving…') : t('Save')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/**
 * Approve at a rate, or decline with a reason.
 *
 * <p>One screen for both, because they are the same decision. The rate is settled here rather than
 * afterwards: an officer approved without one earns the default from that moment, and the first
 * anybody would hear of it is a figure on the cost analysis.
 */
function DecisionModal({
  applicant,
  onClose,
}: {
  applicant: MarketingOfficer;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const approve = useApproveOfficer();
  const reject = useRejectOfficer();

  const [percent, setPercent] = useState('1');
  const [reason, setReason] = useState('');
  const [declining, setDeclining] = useState(false);

  const rate = Number(percent) / 100;
  const rateIsSound = Number.isFinite(rate) && rate >= 0 && rate <= 1;

  return (
    <Modal title={t('{{name}} — application', { name: applicant.fullName })} onClose={onClose}>
      <div className="flex flex-col gap-4">
        <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-sm">
          <dt className="text-ink3">{t('Email')}</dt>
          <dd className="text-ink">{applicant.email ?? '—'}</dd>
          <dt className="text-ink3">{t('Phone')}</dt>
          <dd className="text-ink">{applicant.mobile ?? '—'}</dd>
          <dt className="text-ink3">{t('Applied')}</dt>
          <dd className="text-ink">{whenApplied(applicant.appliedAt)}</dd>
        </dl>

        {!declining ? (
          <>
            <Field
              label={t('Commission rate')}
              hint={t('A percentage of each pack this officer’s customers earn. One per cent is the default.')}
            >
              <div className="flex items-center gap-2">
                <Input
                  type="number"
                  step="0.25"
                  min="0"
                  max="100"
                  value={percent}
                  onChange={(event) => setPercent(event.target.value)}
                  className="max-w-[8rem]"
                />
                <span className="text-sm text-ink2">%</span>
              </div>
            </Field>

            <ErrorBanner error={approve.error} />

            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setDeclining(true)}>
                {t('Decline')}
              </Button>
              <Button
                variant="primary"
                disabled={approve.isPending || !rateIsSound}
                onClick={() =>
                  approve.mutate(
                    { userId: applicant.userId, commissionRate: rate },
                    { onSuccess: onClose },
                  )
                }
              >
                {approve.isPending ? t('Approving…') : t('Approve')}
              </Button>
            </div>
          </>
        ) : (
          <>
            <Field
              label={t('Why')}
              required
              hint={t('The applicant is shown this, so write it for them to read.')}
            >
              <Input
                autoFocus
                value={reason}
                onChange={(event) => setReason(event.target.value)}
                placeholder={t('We are not taking on new officers in this area.')}
              />
            </Field>

            <ErrorBanner error={reject.error} />

            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setDeclining(false)}>
                {t('Back')}
              </Button>
              <Button
                variant="danger"
                disabled={reject.isPending || reason.trim() === ''}
                onClick={() =>
                  reject.mutate(
                    { userId: applicant.userId, reason: reason.trim() },
                    { onSuccess: onClose },
                  )
                }
              >
                {reject.isPending ? t('Declining…') : t('Decline application')}
              </Button>
            </div>
          </>
        )}
      </div>
    </Modal>
  );
}

/**
 * Creates the account and the officer together, the way an administrator registers a customer.
 *
 * <p>No approval step. The administrator filling this in is the person who would have approved it.
 */
function RegisterOfficerModal({ onClose }: { onClose: () => void }) {
  const { t } = useTranslation();
  const register = useRegisterOfficer();

  const [fullName, setFullName] = useState('');
  const [email, setEmail] = useState('');
  const [mobile, setMobile] = useState('');
  const [password, setPassword] = useState('');
  const [percent, setPercent] = useState('1');

  const hasIdentifier = email.trim() !== '' || mobile.trim() !== '';
  const rate = Number(percent) / 100;
  const ready =
    fullName.trim() !== '' &&
    hasIdentifier &&
    isAcceptablePassword(password) &&
    Number.isFinite(rate) &&
    rate >= 0 &&
    rate <= 1;

  return (
    <Modal title={t('Register a marketing officer')} onClose={onClose}>
      <form
        className="flex flex-col gap-4"
        onSubmit={(event) => {
          event.preventDefault();
          register.mutate(
            {
              fullName: fullName.trim(),
              email: email.trim() || undefined,
              mobile: mobile.trim() || undefined,
              password,
              commissionRate: rate,
            },
            { onSuccess: onClose },
          );
        }}
      >
        <Instructions title={t('Approved on the spot')}>
          {t('An officer you create here does not go into the approval queue — you are the approval. They can be assigned customers straight away.')}
        </Instructions>

        <Field label={t('Full name')} required>
          <Input
            required
            autoFocus
            value={fullName}
            onChange={(event) => setFullName(event.target.value)}
          />
        </Field>

        <Field label={t('Email')} hint={t('An email address or a phone number — at least one.')}>
          <Input
            type="email"
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </Field>

        <Field label={t('Phone number')}>
          <Input
            type="tel"
            value={mobile}
            onChange={(event) => setMobile(event.target.value)}
            placeholder="077 123 4567"
          />
        </Field>

        <Field
          label={t('Password')}
          required
          hint={t(PASSWORD_RULE)}
          error={passwordProblem(password) ?? undefined}
        >
          <PasswordInput
            required
            minLength={MIN_LENGTH}
            maxLength={MAX_LENGTH}
            value={password}
            onChange={(event) => setPassword(event.target.value)}
          />
        </Field>

        <Field label={t('Commission rate')}>
          <div className="flex items-center gap-2">
            <Input
              type="number"
              step="0.25"
              min="0"
              max="100"
              value={percent}
              onChange={(event) => setPercent(event.target.value)}
              className="max-w-[8rem]"
            />
            <span className="text-sm text-ink2">%</span>
          </div>
        </Field>

        <ErrorBanner error={register.error} />

        <div className="flex justify-end gap-2">
          <Button type="button" variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button type="submit" variant="primary" disabled={register.isPending || !ready}>
            {register.isPending ? t('Registering…') : t('Register officer')}
          </Button>
        </div>
      </form>
    </Modal>
  );
}

/** A date somebody can read, or a dash. The queue is sorted by this, so it has to be legible. */
function whenApplied(at: string | null): string {
  if (!at) return '—';
  return new Date(at).toLocaleDateString(undefined, {
    year: 'numeric',
    month: 'short',
    day: 'numeric',
  });
}

function money(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
