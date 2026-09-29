import { Link, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  asPercent,
  useMyAssignedCustomers,
  useMyOfficerRecord,
  type AssignedCustomer,
  type MarketingOfficer,
} from '../api/officers';
import { expiryStatus } from '../lib/expiry';
import { Icon } from '../components/icons';
import { GlassCard, SectionTitle } from '../portal/portalUi';
import { StageDots } from './MarketingOfficersPage';
import { Badge, Spinner } from '../components/ui';

/**
 * The marketing officer's portal: three screens and no more.
 *
 * <p>A dashboard, what they have earned, and the customers allocated to them with where each has
 * got to. Nothing else — an officer is not staff: they approve nothing, see no NIC images, move no
 * stock, and have no way to reach another officer's figures.
 *
 * <p>Every query on all three is scoped to the signed-in officer server-side, with no id parameter
 * anywhere. There is no shape of any call on these pages that returns somebody else's figures.
 *
 * <p>Dressed like the customer portal rather than the staff app — soft canvas, rounded glass cards,
 * generous type. An officer is somebody the business is asking to go out and represent it, and this
 * is a screen they will open in front of a prospective customer.
 */

/**
 * The gate all three sit behind.
 *
 * <p>One place rather than a check at the top of each page. Three copies of a rule is three chances
 * for the fourth screen somebody adds later to forget it — and the screen that forgets is the one
 * that shows an unapproved applicant an empty table they will read as "approved, nobody assigned".
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
    <div className="mx-auto max-w-lg rounded-3xl border border-white/70 bg-white/85 p-8 text-center shadow-[0_10px_30px_-12px_rgba(16,24,40,0.18)]">
      <span
        className={
          'mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl ' +
          (rejected ? 'bg-dangersoft text-danger' : 'bg-warnsoft text-warn')
        }
      >
        <Icon name={rejected ? 'alert' : 'lock'} className="h-6 w-6" />
      </span>

      <Badge tone={rejected ? 'danger' : 'warn'}>
        {rejected ? t('Application declined') : t('Waiting for approval')}
      </Badge>

      <p className="mt-3 text-sm text-ink2">
        {rejected
          ? officer.rejectionReason
          : t(
              'Your account is ready and you are signed in. Once the office approves your application, the customers allocated to you appear here, along with what each has earned you.',
            )}
      </p>

      <p className="mt-4 text-xs text-ink3">
        {rejected
          ? t('Speak to the office if you think this is a mistake.')
          : t('There is nothing else for you to do.')}
      </p>
    </div>
  );
}

// ---------------------------------------------------------------- 1 · dashboard

/** Where they stand: the figures, and the two numbers behind them. */
export function OfficerPortalPage() {
  const { t } = useTranslation();
  const me = useMyOfficerRecord();
  const customers = useMyAssignedCustomers();

  if (me.isLoading || customers.isLoading) return <Spinner />;

  const rows = customers.data ?? [];
  const issued = rows.filter((row) => row.packIssued).length;
  const waiting = rows.length - issued;
  const rate = me.data?.commissionRate ?? 0;
  const firstName = (me.data?.fullName ?? '').split(' ')[0];

  return (
    <>
      <section className="relative overflow-hidden rounded-3xl bg-linear-to-br from-[#0b7a6e] via-[#0f8d80] to-[#3f5bd8] p-7 text-white shadow-[0_20px_40px_-18px_rgba(11,122,110,0.65)] sm:p-8">
        <div
          aria-hidden
          className="pointer-events-none absolute -top-20 -right-16 h-64 w-64 rounded-full bg-white/10"
        />
        <div
          aria-hidden
          className="pointer-events-none absolute -bottom-28 left-1/3 h-72 w-72 rounded-full bg-[#7ee0d3]/15 blur-2xl"
        />
        <div className="relative flex flex-col gap-6 sm:flex-row sm:items-end sm:justify-between">
          <div className="min-w-0">
            <p className="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-3 py-1 text-xs font-semibold ring-1 ring-white/20">
              <Icon name="sparkles" className="h-3.5 w-3.5" />
              {t('{{rate}} of every pack your customers earn', { rate: asPercent(rate) })}
            </p>
            <h1 className="mt-4 text-3xl leading-tight font-extrabold tracking-tight sm:text-[34px]">
              {firstName ? t('Welcome back, {{name}}', { name: firstName }) : t('Welcome back')}
            </h1>
            <p className="mt-2 max-w-md text-[15px] text-white/85">
              {rows.length === 0
                ? t('Nobody is allocated to you yet. The office decides who you look after.')
                : waiting === 0
                  ? t('Every customer allocated to you has had their pack handed over.')
                  : t('{{waiting}} of your {{total}} customers are still waiting on their pack.', {
                      waiting,
                      total: rows.length,
                    })}
            </p>
            <div className="mt-6 flex flex-wrap gap-2.5">
              <Link
                to="/officer/revenue"
                className="inline-flex h-10 items-center gap-2 rounded-full bg-white px-4 text-sm font-bold text-brand shadow-sm transition-transform hover:-translate-y-0.5"
              >
                {t('Revenue')}
                <Icon name="arrowRight" className="h-4 w-4" />
              </Link>
              <Link
                to="/officer/customers"
                className="inline-flex h-10 items-center gap-2 rounded-full bg-white/15 px-4 text-sm font-bold text-white ring-1 ring-white/25 transition-transform hover:-translate-y-0.5"
              >
                {t('My customers')}
              </Link>
            </div>
          </div>

          <div className="flex-none text-right">
            <p className="text-[11px] font-bold tracking-[0.12em] text-white/70 uppercase">
              {t('Earned so far')}
            </p>
            <p className="nums mt-1 text-4xl font-extrabold tracking-tight">
              {money(me.data?.earned ?? 0)}
            </p>
          </div>
        </div>
      </section>

      <div className="mt-5 grid gap-4 sm:grid-cols-3">
        <StatTile label={t('Customers')} value={String(rows.length)} icon="users" />
        <StatTile label={t('Packs handed over')} value={String(issued)} icon="gift" tone="ok" />
        {/* The gap between the two figures above, spelled out. A customer counts towards the first
            the day they are allocated and towards the second only when their pack is handed over,
            and that difference is the whole of an officer's pipeline. */}
        <StatTile label={t('Waiting on a pack')} value={String(waiting)} icon="clipboard" />
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
 * <p>Only issued packs appear, because only an issued pack has earned anything.
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
      <section className="relative overflow-hidden rounded-3xl bg-linear-to-br from-[#0f1b2d] via-[#123a44] to-[#0b5f56] p-7 text-white shadow-[0_20px_40px_-18px_rgba(15,27,45,0.7)]">
        <div
          aria-hidden
          className="pointer-events-none absolute -top-24 -right-12 h-64 w-64 rounded-full bg-white/8"
        />
        <div className="relative">
          <p className="text-[11px] font-bold tracking-[0.12em] text-white/70 uppercase">
            {t('Total earned')}
          </p>
          <p className="nums mt-1 text-4xl font-extrabold tracking-tight sm:text-5xl">
            {money(total)}
          </p>
          <p className="mt-2 text-sm text-white/80">
            {t('{{count}} packs handed over, at {{rate}} each', {
              count: earning.length,
              rate: asPercent(rate),
            })}
          </p>
        </div>
      </section>

      <div className="mt-5">
        <GlassCard className="p-0">
          <div className="p-6 pb-0">
            <SectionTitle eyebrow={t('Breakdown')} title={t('Where it came from')} />
          </div>

          {earning.length === 0 ? (
            <p className="px-6 pt-2 pb-10 text-center text-sm text-ink3">
              {t(
                'Nothing yet. A customer earns you your percentage when their item pack is handed over.',
              )}
            </p>
          ) : (
            <div className="overflow-x-auto px-2 pb-2">
              <table className="w-full min-w-[620px] border-separate border-spacing-y-1.5 px-4">
                <thead>
                  <tr className="text-left">
                    <SoftTh>{t('Customer')}</SoftTh>
                    <SoftTh>{t('Item pack')}</SoftTh>
                    <SoftTh align="right">{t('Pack price')}</SoftTh>
                    <SoftTh align="right">{t('You earned')}</SoftTh>
                    <SoftTh>{t('Handed over')}</SoftTh>
                  </tr>
                </thead>
                <tbody>
                  {earning.map((row) => (
                    <tr key={row.distributorId} className="bg-white/70">
                      <SoftTd className="rounded-l-2xl">
                        <p className="font-semibold text-ink">{row.fullName}</p>
                        <p className="font-mono text-[11px] text-brand">{row.businessId ?? '—'}</p>
                      </SoftTd>
                      <SoftTd>{row.packName ?? '—'}</SoftTd>
                      <SoftTd align="right">
                        {row.packPrice == null ? '—' : money(row.packPrice)}
                      </SoftTd>
                      <SoftTd align="right" className="font-bold text-ok">
                        {money(row.earned)}
                      </SoftTd>
                      <SoftTd className="rounded-r-2xl text-ink3">
                        {row.packIssuedAt
                          ? new Date(row.packIssuedAt).toLocaleDateString()
                          : '—'}
                      </SoftTd>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </GlassCard>
      </div>

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
      <SectionTitle eyebrow={t('Allocated to you')} title={t('My customers')} />

      {rows.length === 0 ? (
        <GlassCard>
          <p className="py-10 text-center text-sm text-ink3">
            {t('Nobody is allocated to you yet. The office decides who you look after.')}
          </p>
        </GlassCard>
      ) : (
        <div className="grid gap-3.5 sm:grid-cols-2">
          {rows.map((row) => (
            <CustomerCard key={row.distributorId} row={row} />
          ))}
        </div>
      )}
    </>
  );
}

/**
 * One customer, as a card rather than a table row.
 *
 * <p>Six columns of small text is a staff table. An officer looks after a handful of people and
 * wants to see at a glance where each one is, so each gets a card with their level, their
 * membership and their pack laid out with room to read.
 */
function CustomerCard({ row }: { row: AssignedCustomer }) {
  const { t } = useTranslation();
  const expiry = expiryStatus(row.expiresAt);

  return (
    <article className="rounded-3xl border border-white/70 bg-white/85 p-5 shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)]">
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="truncate text-[15px] font-bold text-ink">{row.fullName}</p>
          <p className="font-mono text-[11px] font-semibold text-brand">{row.businessId ?? '—'}</p>
        </div>
        {row.status === 'active' ? (
          <Badge tone="ok">{t('active')}</Badge>
        ) : (
          <Badge tone="warn">{row.status}</Badge>
        )}
      </div>

      <div className="mt-4 flex items-center gap-2">
        <span className="text-[11px] font-bold tracking-wider text-ink3 uppercase">
          {t('Level')}
        </span>
        <StageDots done={row.stagesCompleted} total={row.totalStages} />
      </div>

      <dl className="mt-4 grid grid-cols-2 gap-3 border-t border-rule pt-4 text-xs">
        <div>
          <dt className="text-ink3">{t('Membership')}</dt>
          <dd className="mt-0.5">
            {expiry.band === 'none' ? (
              <span className="text-ink3">—</span>
            ) : (
              <span
                className={`inline-flex rounded-full border px-2 py-0.5 text-[11px] whitespace-nowrap ${expiry.classes}`}
              >
                {expiry.label}
              </span>
            )}
          </dd>
        </div>
        <div>
          <dt className="text-ink3">{t('Item pack')}</dt>
          <dd className="mt-0.5 text-ink2">
            {row.packName ?? '—'}{' '}
            {row.packIssued ? (
              <Badge tone="ok">{t('issued')}</Badge>
            ) : (
              <span className="text-ink3">{t('not yet')}</span>
            )}
          </dd>
        </div>
      </dl>
    </article>
  );
}

// ---------------------------------------------------------------- shared

/** Said plainly, on every screen that shows a figure, so no column reads as a statement of account. */
function Informational({ rate }: { rate: number }) {
  const { t } = useTranslation();
  return (
    <p className="mt-4 text-center text-xs text-ink3">
      {t(
        'A customer earns you {{rate}} of their item pack when it is handed over — not before. These figures are for information; the office settles up separately.',
        { rate: asPercent(rate) },
      )}
    </p>
  );
}

function StatTile({
  label,
  value,
  icon,
  tone,
}: {
  label: string;
  value: string;
  icon: 'users' | 'gift' | 'clipboard';
  tone?: 'ok';
}) {
  return (
    <div className="flex items-center gap-3.5 rounded-3xl border border-white/70 bg-white/85 p-5 shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)]">
      <span
        className={
          'flex h-11 w-11 flex-none items-center justify-center rounded-2xl ' +
          (tone === 'ok' ? 'bg-oksoft text-ok' : 'bg-brandsoft text-brand')
        }
      >
        <Icon name={icon} className="h-5 w-5" />
      </span>
      <div className="min-w-0">
        <p className="text-[10.5px] font-bold tracking-wider text-ink3 uppercase">{label}</p>
        <p className="nums mt-0.5 text-2xl font-extrabold text-ink">{value}</p>
      </div>
    </div>
  );
}

function SoftTh({
  children,
  align = 'left',
}: {
  children: React.ReactNode;
  align?: 'left' | 'right';
}) {
  return (
    <th
      className={`px-4 pb-2 text-[10.5px] font-bold tracking-wider text-ink3 uppercase ${
        align === 'right' ? 'text-right' : 'text-left'
      }`}
    >
      {children}
    </th>
  );
}

function SoftTd({
  children,
  align = 'left',
  className = '',
}: {
  children: React.ReactNode;
  align?: 'left' | 'right';
  className?: string;
}) {
  return (
    <td
      className={`px-4 py-3 text-sm text-ink2 ${align === 'right' ? 'nums text-right' : ''} ${className}`}
    >
      {children}
    </td>
  );
}

function money(value: number): string {
  return value.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
