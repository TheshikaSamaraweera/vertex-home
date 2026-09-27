import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  asPercent,
  useMarketingOfficers,
  useOfficerCustomers,
  useSetOfficerRate,
  type MarketingOfficer,
} from '../api/officers';
import { useUsers } from '../api/queries';
import { useEnrolOfficer } from '../api/officers';
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

  const [enrolling, setEnrolling] = useState(false);
  const [viewing, setViewing] = useState<MarketingOfficer | null>(null);
  const [rateFor, setRateFor] = useState<MarketingOfficer | null>(null);

  if (officers.isLoading) return <Spinner />;

  return (
    <>
      <PageHeader
        title={t('Marketing officers')}
        description={t('Who brings customers in, and what the packs those customers earned have come to.')}
        actions={
          <Button variant="primary" onClick={() => setEnrolling(true)}>
            {t('Add a marketing officer')}
          </Button>
        }
      />

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

function money(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
