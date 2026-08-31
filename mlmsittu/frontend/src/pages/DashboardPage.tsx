import { Link } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import {
  useItems,
  useReorderAlerts,
  useReservations,
  useSetAvailability,
  useStock,
} from '../api/queries';
import { Badge, Card, ErrorBanner, PageHeader, Spinner } from '../components/ui';
import { useAuth } from '../auth/AuthContext';

/** Summary before detail: what needs attention reads at a glance, the rest is a click away. */
export function DashboardPage() {
  const { t } = useTranslation();
  const { user } = useAuth();

  const items = useItems();
  const stock = useStock();
  const alerts = useReorderAlerts(true);
  const reservations = useReservations(true);
  const availability = useSetAvailability();

  const belowReorder = (stock.data ?? []).filter((level) => level.belowReorderLevel).length;
  const totalReserved = (stock.data ?? []).reduce((sum, level) => sum + (level.reserved ?? 0), 0);
  const contendedSets = (availability.data ?? []).filter((set) => set.contended).length;

  const error = items.error ?? stock.error ?? alerts.error ?? reservations.error;

  return (
    <>
      <PageHeader
        title={t('Dashboard')}
        description={t('Current position across catalogue, stock and reservations.')}
      />

      {error && (
        <div className="mb-5">
          <ErrorBanner error={error} onRetry={() => void stock.refetch()} />
        </div>
      )}

      <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
        <Stat
          label={t('Active items')}
          value={items.data?.length}
          loading={items.isLoading}
          to="/items"
        />
        {/* No `to`: the reservations screen was removed. The number still means something — this
            much stock is spoken for and cannot be sold twice — but there is nowhere to drill into
            any more, and a tile that looks clickable and bounces you to the dashboard is worse
            than one that plainly is not. */}
        <Stat
          label={t('Units reserved')}
          value={totalReserved}
          loading={stock.isLoading}
          hint={t('Held, not yet shipped')}
        />
        <Stat
          label={t('Below reorder level')}
          value={belowReorder}
          loading={stock.isLoading}
          to="/stock"
          tone={belowReorder > 0 ? 'warn' : 'ok'}
        />
        <Stat
          label={t('Open reorder alerts')}
          value={alerts.data?.length}
          loading={alerts.isLoading}
          to="/stock"
          tone={(alerts.data?.length ?? 0) > 0 ? 'danger' : 'ok'}
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
                <li key={set.setId} className="flex items-center justify-between gap-3 px-4 py-2.5">
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
            <p className="border-t border-rule px-4 py-2.5 text-xs text-ink2">
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
            <p className="px-4 py-8 text-center text-sm text-ink3">{t('Nothing reserved.')}</p>
          ) : (
            <ul className="divide-y divide-rule">
              {(reservations.data ?? []).slice(0, 6).map((reservation) => (
                <li key={reservation.id} className="flex items-center justify-between gap-3 px-4 py-2.5">
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

      <p className="mt-6 text-xs text-ink3">
        {t('Signed in as')} {user?.email}
      </p>
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
}: {
  label: string;
  value: number | undefined;
  hint?: string;
  loading: boolean;
  /** Omit for a figure with nowhere to drill into. The tile then renders without hover affordance
   *  rather than looking clickable and going nowhere. */
  to?: string;
  tone?: 'neutral' | 'ok' | 'warn' | 'danger';
}) {
  const accent = {
    neutral: 'text-ink',
    ok: 'text-ok',
    warn: 'text-warn',
    danger: 'text-danger',
  }[tone];

  const body = (
    <>
      <p className="text-[10px] font-semibold tracking-wider text-ink3 uppercase">{label}</p>
      <p className={`nums mt-1.5 text-2xl font-bold ${accent}`}>
        {loading ? '—' : (value ?? 0)}
      </p>
      {hint && <p className="mt-0.5 text-[11px] text-ink3">{hint}</p>}
    </>
  );

  const frame = 'rounded-lg border border-rule bg-panel p-4';

  if (!to) {
    return <div className={frame}>{body}</div>;
  }

  return (
    <Link to={to} className={`${frame} transition-colors hover:border-brand`}>
      {body}
    </Link>
  );
}

function formatWhen(iso: string): string {
  return new Date(iso).toLocaleString(undefined, { dateStyle: 'short', timeStyle: 'short' });
}
