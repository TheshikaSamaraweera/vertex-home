import { Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  asPercent,
  useMyAssignedCustomers,
  useMyOfficerRecord,
  type AssignedCustomer,
  type MarketingOfficer,
} from '../api/officers';
import { expiryStatus } from '../lib/expiry';
import { StageDots } from './MarketingOfficersPage';
import {
  Badge,
  Card,
  PageHeader,
  Spinner,
  Table,
  TableWrap,
  Td,
  Th,
} from '../components/ui';

/**
 * The marketing officer's portal: three screens and no more.
 *
 * <p>A dashboard, what they have earned, and the customers allocated to them with where each has
 * got to. Nothing else — an officer is not staff: they approve nothing, see no NIC images, move no
 * stock, and have no way to reach another officer's customers.
 *
 * <p>Every query on all three is scoped to the signed-in officer server-side, with no id parameter
 * anywhere. There is no shape of any call on these pages that returns somebody else's figures.
 */

/**
 * The gate all three sit behind.
 *
 * <p>One place rather than a check at the top of each page. Three copies of a rule is three chances
 * for the fourth screen somebody adds later to forget it — and the screen that forgets is the one
 * that shows an unapproved applicant an empty table they will read as "approved, nobody assigned".
 *
 * <p>My account and notifications are deliberately outside this gate. Somebody waiting on a decision
 * still has to be able to change the password an administrator gave them.
 */
export function OfficerGate() {
  const me = useMyOfficerRecord();

  if (me.isLoading) return <Spinner />;
  if (me.data && me.data.status !== 'approved') return <AwaitingDecision officer={me.data} />;

  return <Outlet />;
}

/**
 * What an applicant sees until somebody decides.
 *
 * <p>The account can sign in from the moment it is created — this is the page behind that. The
 * alternative was refusing the login, which tells an applicant "this account cannot sign in at the
 * moment" and leaves them unable to tell a pending application from a suspended account.
 */
function AwaitingDecision({ officer }: { officer: MarketingOfficer }) {
  const { t } = useTranslation();
  const rejected = officer.status === 'rejected';

  return (
    <>
      <PageHeader
        title={rejected ? t('Application declined') : t('Application received')}
        description={
          rejected
            ? t('The office has reviewed your application.')
            : t('An administrator will review your application.')
        }
      />

      <Card title={rejected ? t('What the office said') : t('What happens next')}>
        <div className="px-5 py-8">
          <Badge tone={rejected ? 'danger' : 'warn'}>
            {rejected ? t('Declined') : t('Waiting for approval')}
          </Badge>

          {rejected ? (
            <p className="mt-4 max-w-prose text-sm text-ink2">{officer.rejectionReason}</p>
          ) : (
            <p className="mt-4 max-w-prose text-sm text-ink2">
              {t(
                'Your account is ready and you are signed in. Once the office approves your ' +
                  'application, the customers allocated to you appear here, along with what each ' +
                  'has earned you.',
              )}
            </p>
          )}

          <p className="mt-4 text-sm text-ink3">
            {rejected
              ? t('Speak to the office if you think this is a mistake.')
              : t('There is nothing else for you to do.')}
          </p>
        </div>
      </Card>
    </>
  );
}

// ---------------------------------------------------------------- 1 · dashboard

/** The figures, and what they mean. The one screen that answers "how am I doing". */
export function OfficerPortalPage() {
  const { t } = useTranslation();
  const me = useMyOfficerRecord();
  const customers = useMyAssignedCustomers();

  if (me.isLoading || customers.isLoading) return <Spinner />;

  const rows = customers.data ?? [];
  const issued = rows.filter((row) => row.packIssued).length;
  const rate = me.data?.commissionRate ?? 0;

  return (
    <>
      <PageHeader
        title={t('Dashboard')}
        description={t('Where you stand: who you look after, and what it has come to.')}
      />

      <div className="mb-5 grid gap-4 sm:grid-cols-3">
        <Figure label={t('Customers')} value={String(rows.length)} />
        <Figure label={t('Packs issued')} value={String(issued)} />
        <Figure
          label={t('Earned at {{rate}}', { rate: asPercent(rate) })}
          value={money(me.data?.earned ?? 0)}
          strong
        />
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        <Card title={t('Waiting on a pack')}>
          {/* The gap between the two figures above, spelled out. A customer counts towards the
              first the day they are allocated and towards the third only when their pack is
              handed over, and that difference is the whole of an officer's pipeline. */}
          <div className="px-5 py-6">
            <p className="nums text-3xl font-bold text-ink">{rows.length - issued}</p>
            <p className="mt-1 text-sm text-ink2">
              {t(
                'Customers allocated to you whose item pack has not been handed over yet. Nothing is earned until it is.',
              )}
            </p>
          </div>
        </Card>

        <Card title={t('Your rate')}>
          <div className="px-5 py-6">
            <p className="nums text-3xl font-bold text-brand">{asPercent(rate)}</p>
            <p className="mt-1 text-sm text-ink2">
              {t(
                'Of each item pack your customers earn. The office sets this; ask them if it looks wrong.',
              )}
            </p>
          </div>
        </Card>
      </div>

      <Informational rate={rate} />
    </>
  );
}

// ---------------------------------------------------------------- 2 · revenue

/**
 * What each customer has earned, and what that adds up to.
 *
 * <p>Separate from the customer list because it answers a different question. The customer list is
 * "who am I looking after and how are they doing"; this is "what has that come to, and from whom" —
 * and a single table trying to be both is one an officer has to read twice.
 *
 * <p>Only issued packs appear as earnings, because only an issued pack has earned anything.
 */
export function OfficerRevenuePage() {
  const { t } = useTranslation();
  const me = useMyOfficerRecord();
  const customers = useMyAssignedCustomers();

  if (me.isLoading || customers.isLoading) return <Spinner />;

  const rows = customers.data ?? [];
  const earning = rows.filter((row) => row.packIssued && row.earned > 0);
  const total = earning.reduce((sum, row) => sum + row.earned, 0);
  const rate = me.data?.commissionRate ?? 0;

  return (
    <>
      <PageHeader
        title={t('Revenue')}
        description={t('What you have earned, and which customer and pack it came from.')}
      />

      <div className="mb-5 grid gap-4 sm:grid-cols-3">
        <Figure label={t('Packs handed over')} value={String(earning.length)} />
        <Figure label={t('Your rate')} value={asPercent(rate)} />
        <Figure label={t('Total earned')} value={money(total)} strong />
      </div>

      <Card title={t('Earnings')}>
        {earning.length === 0 ? (
          <p className="px-5 py-12 text-center text-sm text-ink3">
            {t('Nothing yet. A customer earns you your percentage when their item pack is handed over.')}
          </p>
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Business ID')}</Th>
                  <Th>{t('Customer')}</Th>
                  <Th>{t('Item pack')}</Th>
                  <Th align="right">{t('Pack price')}</Th>
                  <Th align="right">{t('Your rate')}</Th>
                  <Th align="right">{t('You earned')}</Th>
                  <Th>{t('Handed over')}</Th>
                </tr>
              </thead>
              <tbody>
                {earning.map((row) => (
                  <tr key={row.distributorId}>
                    <Td className="font-mono text-xs text-brand">{row.businessId ?? '—'}</Td>
                    <Td className="text-ink">{row.fullName}</Td>
                    <Td className="text-xs">{row.packName ?? '—'}</Td>
                    <Td align="right" className="nums">
                      {row.packPrice == null ? '—' : money(row.packPrice)}
                    </Td>
                    <Td align="right" className="nums text-ink2">
                      {asPercent(rate)}
                    </Td>
                    <Td align="right" className="nums font-semibold text-ok">
                      {money(row.earned)}
                    </Td>
                    <Td className="text-xs text-ink2">
                      {row.packIssuedAt ? new Date(row.packIssuedAt).toLocaleDateString() : '—'}
                    </Td>
                  </tr>
                ))}
              </tbody>
              <tfoot>
                <tr className="border-t-2 border-rulestrong">
                  <Td className="font-bold text-ink" colSpan={5}>
                    {t('Total')}
                  </Td>
                  <Td align="right" className="nums font-bold text-ok">
                    {money(total)}
                  </Td>
                  <Td>{''}</Td>
                </tr>
              </tfoot>
            </Table>
          </TableWrap>
        )}
      </Card>

      <Informational rate={rate} />
    </>
  );
}

// ---------------------------------------------------------------- 3 · allocated customers

/** Who the office has allocated to this officer, and where each one has got to. */
export function OfficerCustomersPage() {
  const { t } = useTranslation();
  const customers = useMyAssignedCustomers();

  if (customers.isLoading) return <Spinner />;

  const rows = customers.data ?? [];

  return (
    <>
      <PageHeader
        title={t('My customers')}
        description={t('The customers allocated to you, and how far each has got.')}
      />

      <Card title={t('Allocated to you')}>
        {rows.length === 0 ? (
          <p className="px-5 py-12 text-center text-sm text-ink3">
            {t('Nobody is allocated to you yet. The office decides who you look after.')}
          </p>
        ) : (
          <TableWrap>
            <Table>
              <thead>
                <tr>
                  <Th>{t('Business ID')}</Th>
                  <Th>{t('Customer')}</Th>
                  <Th>{t('Status')}</Th>
                  <Th>{t('Level')}</Th>
                  <Th>{t('Membership')}</Th>
                  <Th>{t('Item pack')}</Th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <CustomerRow key={row.distributorId} row={row} />
                ))}
              </tbody>
            </Table>
          </TableWrap>
        )}
      </Card>
    </>
  );
}

function CustomerRow({ row }: { row: AssignedCustomer }) {
  const { t } = useTranslation();
  const expiry = expiryStatus(row.expiresAt);

  return (
    <tr>
      <Td className="font-mono text-xs text-brand">{row.businessId ?? '—'}</Td>
      <Td className="text-ink">{row.fullName}</Td>
      <Td>
        {row.status === 'active' ? (
          <Badge tone="ok">{t('active')}</Badge>
        ) : (
          <Badge tone="warn">{row.status}</Badge>
        )}
      </Td>
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
        {row.packName ?? '—'}{' '}
        {row.packIssued ? (
          <Badge tone="ok">{t('issued')}</Badge>
        ) : (
          <span className="text-ink3">{t('not yet')}</span>
        )}
      </Td>
    </tr>
  );
}

// ---------------------------------------------------------------- shared

/** Said plainly, on every screen that shows a figure, so no column reads as a statement of account. */
function Informational({ rate }: { rate: number }) {
  const { t } = useTranslation();
  return (
    <p className="mt-3 text-xs text-ink3">
      {t(
        'A customer earns you {{rate}} of their item pack when it is handed over — not before. These figures are for information; the office settles up separately.',
        { rate: asPercent(rate) },
      )}
    </p>
  );
}

function Figure({ label, value, strong }: { label: string; value: string; strong?: boolean }) {
  return (
    <div className="rounded-xl border-2 border-rulestrong bg-panel p-4 shadow-card">
      <p className="text-[10.5px] font-bold tracking-wider text-ink2 uppercase">{label}</p>
      <p className={`nums mt-1 text-2xl font-bold ${strong ? 'text-ok' : 'text-ink'}`}>{value}</p>
    </div>
  );
}

function money(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
