import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { useAuth } from '../auth/AuthContext';
import { usePortalMe } from '../api/portal';
import { Badge, Button, ErrorBanner, Spinner } from '../components/ui';

/**
 * The distributor's shell.
 *
 * <p>The navigation is <b>derived from the gate, not from roles</b>: until a registration is
 * approved there is exactly one place to be, and showing links to four locked pages would be a
 * menu of disappointments. Once approved, the full set appears at once.
 */
export function PortalShell() {
  const { t } = useTranslation();
  const { user, signOut } = useAuth();
  const me = usePortalMe();

  const active = me.data?.access === 'ACTIVE';

  const items = active
    ? [
        { to: '/portal', label: t('Dashboard'), end: true },
        { to: '/portal/details', label: t('My details') },
        { to: '/portal/stages', label: t('My stages') },
        { to: '/portal/referrals', label: t('My referrals') },
        { to: '/portal/registration', label: t('My registration') },
      ]
    : [{ to: '/portal/registration', label: t('Business registration'), end: false }];

  return (
    <div className="min-h-dvh bg-ground">
      <header className="border-b border-rule bg-panel">
        <div className="mx-auto flex max-w-[1100px] flex-wrap items-center gap-3 px-5 py-3">
          <span
            aria-hidden
            className="flex h-8 w-8 flex-none items-center justify-center rounded-lg bg-brand text-[13px] font-bold text-brandink"
          >
            MS
          </span>
          <div className="min-w-0">
            <p className="truncate text-sm font-bold tracking-tight text-ink">MLM Sittu</p>
            <p className="truncate font-mono text-[9.5px] tracking-wider text-ink3 uppercase">
              {t('Customer portal')}
            </p>
          </div>

          <div className="ml-auto flex items-center gap-3">
            <div className="text-right">
              <p className="truncate text-xs font-semibold text-ink">{user?.fullName}</p>
              {/* The Business ID is the distributor's identity in this business, so it sits in
                  the chrome where it is always to hand rather than on one page. */}
              {me.data?.businessId ? (
                <p className="font-mono text-[11px] text-brand">{me.data.businessId}</p>
              ) : (
                <p className="text-[11px] text-ink3">{t('Not yet registered')}</p>
              )}
            </div>
            <Button size="sm" variant="secondary" onClick={() => void signOut()}>
              {t('Sign out')}
            </Button>
          </div>
        </div>

        <nav className="mx-auto max-w-[1100px] px-5">
          <ul className="flex flex-wrap gap-1 pb-1">
            {items.map((item) => (
              <li key={item.to}>
                <NavLink
                  to={item.to}
                  end={item.end}
                  className={({ isActive }) =>
                    'block rounded-t-md border-b-2 px-3 py-2 text-[13px] transition-colors ' +
                    (isActive
                      ? 'border-brand font-semibold text-brand'
                      : 'border-transparent text-ink2 hover:text-brand')
                  }
                >
                  {item.label}
                </NavLink>
              </li>
            ))}
          </ul>
        </nav>
      </header>

      <main className="mx-auto max-w-[1100px] px-5 py-7">
        {me.isLoading ? (
          <Spinner label={t('Loading your account…')} />
        ) : me.error ? (
          <ErrorBanner error={me.error} onRetry={() => void me.refetch()} />
        ) : (
          <Outlet />
        )}
      </main>
    </div>
  );
}

/**
 * Wraps a page that only an approved distributor may see.
 *
 * Belt and braces: the server refuses these anyway, and the navigation does not link to them until
 * approval. This is what a typed URL hits.
 */
export function RequireActive({ children }: { children: React.ReactNode }) {
  const { t } = useTranslation();
  const me = usePortalMe();

  if (me.isLoading) return <Spinner />;
  if (me.data?.access === 'ACTIVE') return <>{children}</>;

  return (
    <div className="rounded-xl border border-rule bg-panel p-6 shadow-card">
      <Badge tone="warn">{t('Not available yet')}</Badge>
      <p className="mt-3 text-sm text-ink2">
        {t('This opens once your business registration has been approved.')}
      </p>
      <NavLink to="/portal/registration">
        <Button variant="primary" size="sm" className="mt-4">
          {t('Go to my registration')}
        </Button>
      </NavLink>
    </div>
  );
}
