import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useReorderAlerts,
  useReservations,
  useSetAvailability,
  useStockSummary,
} from '../api/queries';
import { Badge, Card, EmptyState, ErrorBanner, Spinner } from '../components/ui';
import { Icon, type IconName } from '../components/icons';
import { useAuth } from '../auth/AuthContext';

/** Summary before detail: what needs attention reads at a glance, the rest is a click away. */
export function DashboardPage() {
  const { t } = useTranslation();
  const { user } = useAuth();

  // Counted on the server. The dashboard used to download the whole catalogue and every stock
  // level to count them here, on the one page everybody lands on.
  const summary = useStockSummary();
  const alerts = useReorderAlerts(true);
  const reservations = useReservations(true);
  const availability = useSetAvailability();

  const belowReorder = summary.data?.positionsBelowReorder ?? 0;
  const totalReserved = summary.data?.unitsReserved;
  const contendedSets = (availability.data ?? []).filter((set) => set.contended).length;

  const error = summary.error ?? alerts.error ?? reservations.error;

  return (
    <>
      {/* The greeting band. The one place the brand runs at full strength outside a button, so
          the landing screen has a clear top and says whose view this is. */}
      <section className="relative mb-6 overflow-hidden rounded-2xl bg-linear-to-br from-brand to-branddeep px-6 py-6 text-white shadow-card sm:px-8 sm:py-7">
        <div
          aria-hidden
          className="pointer-events-none absolute -top-16 -right-10 h-56 w-56 rounded-full bg-white/10"
        />
        <div
          aria-hidden
          className="pointer-events-none absolute -right-24 -bottom-24 h-64 w-64 rounded-full bg-brandbright/25"
        />
        <p className="relative text-[13px] font-medium text-white/80">{greeting(t)}</p>
        <h1 className="relative mt-1 text-2xl font-bold tracking-tight sm:text-[28px]">
          {user?.fullName ?? t('Dashboard')}
        </h1>
        <p className="relative mt-2 max-w-xl text-sm text-white/85">
          {t('Current position across catalogue, stock and reservations.')}
        </p>
      </section>

      {error && (
        <div className="mb-5">
          <ErrorBanner error={error} onRetry={() => void summary.refetch()} />
        </div>
      )}

      {/* Two up even on a phone: four full-width tiles was a screen of scrolling before anything
          below them was visible. */}
      <div className="grid grid-cols-2 gap-3 sm:gap-4 lg:grid-cols-4">
        <Stat
          label={t('Active items')}
          value={summary.data?.activeItems}
          loading={summary.isLoading}
          to="/items"
          icon="package"
        />
        {/* No `to`: the reservations screen was removed. The number still means something — this
            much stock is spoken for and cannot be sold twice — but there is nowhere to drill into
            any more, and a tile that looks clickable and bounces you to the dashboard is worse
            than one that plainly is not. */}
        <Stat
          label={t('Units reserved')}
          value={totalReserved}
          loading={summary.isLoading}
          hint={t('Held, not yet shipped')}
          icon="lock"
        />
        <Stat
          label={t('Below reorder level')}
          value={belowReorder}
          loading={summary.isLoading}
          to="/stock"
          tone={belowReorder > 0 ? 'warn' : 'ok'}
          icon="warehouse"
        />
        <Stat
          label={t('Open reorder alerts')}
          value={alerts.data?.length}
          loading={alerts.isLoading}
          to="/stock"
          tone={(alerts.data?.length ?? 0) > 0 ? 'danger' : 'ok'}
          icon="alert"
        />
      </div>

      <div className="mt-6 grid gap-5 lg:grid-cols-2">
        <Card
          title={t('Item set availability')}
          subtitle={t('Advisory — only a reservation is authoritative')}
        >
          {availability.isLoading ? (
            <Spinner />
          ) : (
            <ul className="divide-y divide-rule">
              {(availability.data ?? []).map((set) => (
                <li key={set.setId} className="flex items-center justify-between gap-3 px-5 py-3">
                  <div className="min-w-0">
                    <p className="truncate text-sm text-ink">{set.name}</p>
                    <p className="font-mono text-[11px] text-ink3">{set.code}</p>
                  </div>
                  <div className="flex items-center gap-2">
                    {set.contended && <Badge tone="warn">{t('shared stock')}</Badge>}
                    <span className="nums text-lg font-semibold text-ink">
                      {set.availableSets}
                    </span>
                  </div>
                </li>
              ))}
            </ul>
          )}
          {contendedSets > 0 && (
            <p className="border-t border-rule px-5 py-3 text-xs text-ink2">
              {t(
                'Sets marked "shared stock" draw on the same components. Their figures can be individually true and jointly impossible.',
              )}
            </p>
          )}
        </Card>

        <Card title={t('Active reservations')} subtitle={t('Stock held against an order')}>
          {reservations.isLoading ? (
            <Spinner />
          ) : (reservations.data ?? []).length === 0 ? (
            <EmptyState message={t('Nothing reserved.')} />
          ) : (
            <ul className="divide-y divide-rule">
              {(reservations.data ?? []).slice(0, 6).map((reservation) => (
                <li key={reservation.id} className="flex items-center justify-between gap-3 px-5 py-3">
                  <div className="min-w-0">
                    <p className="truncate font-mono text-xs text-ink">
                      {reservation.referenceType ?? t('standalone')}
                    </p>
                    <p className="text-[11px] text-ink3">
                      {reservation.lines?.length ?? 0} {t('components')}
                      {reservation.expiresAt && ` · ${t('expires')} ${formatWhen(reservation.expiresAt)}`}
                    </p>
                  </div>
                  <Badge tone="brand">{reservation.status}</Badge>
                </li>
              ))}
            </ul>
          )}
        </Card>
      </div>

    </>
  );
}

function Stat({
  label,
  value,
  hint,
  loading,
  to,
  tone = 'neutral',
  icon,
}: {
  label: string;
  value: number | undefined;
  hint?: string;
  loading: boolean;
  /** Omit for a figure with nowhere to drill into. The tile then renders without hover affordance
   *  rather than looking clickable and going nowhere. */
  to?: string;
  tone?: 'neutral' | 'ok' | 'warn' | 'danger';
  icon: IconName;
}) {
  const { t } = useTranslation();
  // The figure takes the tone only when it is a warning. A green "0 alerts" does not need to
  // shout that nothing is wrong; the icon tile carries the colour instead.
  const accent = { neutral: 'text-ink', ok: 'text-ink', warn: 'text-warn', danger: 'text-danger' }[
    tone
  ];
  const tile = {
    neutral: 'bg-brand text-white',
    ok: 'bg-brand text-white',
    warn: 'bg-warn text-white',
    danger: 'bg-danger text-white',
  }[tone];

  const body = (
    <div className="flex items-start justify-between gap-3">
      <div className="min-w-0">
        <p className="text-[13px] font-medium text-ink3">{label}</p>
        <p
          className={`nums mt-1.5 text-2xl leading-none font-bold tracking-tight sm:text-[28px] ${accent}`}
        >
          {loading ? '—' : (value ?? 0)}
        </p>
        {hint && <p className="mt-2 text-xs text-ink3">{hint}</p>}
        {to && (
          <p className="mt-2 inline-flex items-center gap-1 text-xs font-semibold text-brand opacity-0 transition-opacity group-hover:opacity-100 group-focus-visible:opacity-100">
            {t('View')} <Icon name="arrowRight" className="h-3 w-3" />
          </p>
        )}
      </div>
      <span
        className={`flex h-10 w-10 flex-none items-center justify-center rounded-xl shadow-sm sm:h-12 sm:w-12 ${tile}`}
      >
        <Icon name={icon} className="h-5 w-5 sm:h-[22px] sm:w-[22px]" />
      </span>
    </div>
  );

  const frame = 'rounded-2xl border border-rule bg-panel p-4 shadow-card sm:p-5';

  if (!to) {
    return <div className={frame}>{body}</div>;
  }

  return (
    <Link
      to={to}
      className={`group ${frame} transition-[box-shadow,transform] hover:-translate-y-0.5 hover:shadow-float`}
    >
      {body}
    </Link>
  );
}

/** Good morning / afternoon / evening, by the viewer's own clock. */
function greeting(t: (key: string) => string): string {
  const hour = new Date().getHours();
  if (hour < 12) return t('Good morning');
  if (hour < 17) return t('Good afternoon');
  return t('Good evening');
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' });
}
