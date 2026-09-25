import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Icon, type IconName } from '../components/icons';
import { NotificationBell } from '../components/NotificationBell';
import { SignOutDialog } from '../components/SignOutDialog';
import { PersonAvatar } from './portalUi';
import { useAuth } from '../auth/AuthContext';
import { usePortalMe } from '../api/portal';
import { Badge, Button, ErrorBanner, Spinner } from '../components/ui';

/**
 * The distributor's shell.
 *
 * <p>The navigation is <b>derived from the gate, not from roles</b>: until a registration is
 * approved there is exactly one place to be, and showing links to four locked pages would be a
 * menu of disappointments. Once approved, the full set appears at once.
 *
 * <p>Warmer than the staff app on purpose: a soft gradient canvas, a frosted header and pill
 * navigation. This is the screen a customer opens to show the person they are about to refer, so
 * it has to look like something worth joining.
 */
export function PortalShell() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const me = usePortalMe();
  const [confirmingSignOut, setConfirmingSignOut] = useState(false);

  const active = me.data?.access === 'ACTIVE';
  const completed = me.data?.access === 'COMPLETED';

  const items: Array<{ to: string; label: string; icon: IconName; end?: boolean }> = active
    ? [
        { to: '/portal', label: t('Dashboard'), icon: 'dashboard', end: true },
        { to: '/portal/stages', label: t('My stages'), icon: 'trophy' },
        { to: '/portal/item-packs', label: t('Item packs'), icon: 'gift' },
        { to: '/portal/referrals', label: t('My referrals'), icon: 'network' },
        { to: '/portal/details', label: t('My details'), icon: 'user' },
        { to: '/portal/registration', label: t('Registration'), icon: 'clipboard' },
      ]
    : completed
      ? [
          // The account is closed: its record, and the packs for whoever registers next.
          { to: '/portal', label: t('Dashboard'), icon: 'dashboard', end: true },
          { to: '/portal/item-packs', label: t('Item packs'), icon: 'gift' },
        ]
      : [
        { to: '/portal/registration', label: t('Business registration'), icon: 'clipboard' },
        // Open before approval, so a new customer can see what each pack holds before choosing.
        { to: '/portal/item-packs', label: t('Item packs'), icon: 'gift' },
      ];

  const nav = (
    <ul className="flex gap-1 overflow-x-auto rounded-full bg-white/70 p-1 ring-1 ring-white shadow-[0_4px_16px_-8px_rgba(16,24,40,0.2)] backdrop-blur">
      {items.map((item) => (
        <li key={item.to} className="flex-none">
          <NavLink
            to={item.to}
            end={item.end}
            className={({ isActive }) =>
              'flex items-center gap-1.5 rounded-full px-3.5 py-2 text-[13px] font-semibold whitespace-nowrap transition-all duration-200 ' +
              (isActive
                ? 'bg-linear-to-r from-brandbright to-brand text-white shadow-[0_6px_14px_-6px_rgba(11,122,110,0.7)]'
                : 'text-ink2 hover:bg-white hover:text-brand')
            }
          >
            <Icon name={item.icon} className="h-4 w-4" />
            {item.label}
          </NavLink>
        </li>
      ))}
    </ul>
  );

  return (
    <div className="portal-canvas min-h-dvh">
      <header className="sticky top-0 z-30 border-b border-white/60 bg-white/55 backdrop-blur-xl">
        <div className="mx-auto flex h-16 max-w-[1180px] items-center gap-4 px-4 sm:px-6">
          <div className="flex items-center gap-2.5">
            <span
              aria-hidden
              className="flex h-10 w-10 flex-none items-center justify-center rounded-xl bg-linear-to-br from-brandbright to-brand text-[14px] font-extrabold text-white shadow-[0_6px_14px_-6px_rgba(11,122,110,0.7)]"
            >
              MS
            </span>
            <div className="hidden min-w-0 sm:block">
              <p className="truncate text-[15px] font-extrabold tracking-tight text-ink">MLM Sittu</p>
              <p className="truncate text-[11px] font-medium text-ink3">{t('Customer portal')}</p>
            </div>
          </div>

          <nav aria-label={t('Portal')} className="hidden flex-1 justify-center lg:flex">
            {nav}
          </nav>

          <div className="ml-auto flex items-center gap-2 lg:ml-0">
            <NotificationBell historyPath="/portal/notifications" />
            <div className="hidden items-center gap-2.5 rounded-full bg-white/80 py-1 pr-3 pl-1 ring-1 ring-white shadow-xs sm:flex">
              <PersonAvatar name={user?.fullName} size="sm" />
              <div className="min-w-0 leading-tight">
                <p className="max-w-[140px] truncate text-xs font-semibold text-ink">{user?.fullName}</p>
                {/* The Business ID is the distributor's identity in this business, so it sits in
                    the chrome where it is always to hand rather than on one page. */}
                {me.data?.businessId ? (
                  <p className="font-mono text-[11px] font-semibold text-brand">
                    ID {me.data.businessId}
                  </p>
                ) : (
                  <p className="text-[11px] text-ink3">{t('Not yet registered')}</p>
                )}
              </div>
            </div>
            <button
              type="button"
              onClick={() => setConfirmingSignOut(true)}
              aria-label={t('Sign out')}
              title={t('Sign out')}
              className="flex h-10 w-10 items-center justify-center rounded-full bg-white/80 text-ink3 ring-1 ring-white shadow-xs transition-colors hover:bg-dangersoft hover:text-danger"
            >
              <Icon name="logout" className="h-[18px] w-[18px]" />
            </button>
          </div>
        </div>

        {/* On narrower screens the pills move under the bar and scroll sideways. */}
        <nav aria-label={t('Portal')} className="mx-auto max-w-[1180px] px-4 pb-3 sm:px-6 lg:hidden">
          {nav}
        </nav>
      </header>

      <main className="mx-auto max-w-[1180px] px-4 py-6 sm:px-6 sm:py-8">
        {me.isLoading ? (
          <Spinner label={t('Loading your account…')} />
        ) : me.error ? (
          <ErrorBanner error={me.error} onRetry={() => void me.refetch()} />
        ) : (
          <Outlet />
        )}
      </main>

      <footer className="mx-auto max-w-[1180px] px-4 pb-8 text-center text-xs text-ink3 sm:px-6">
        © MLM Sittu · {t('Distribution and inventory platform')}
      </footer>

      {confirmingSignOut && <SignOutDialog onClose={() => setConfirmingSignOut(false)} />}
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

  // Expired is not the same as never admitted, and saying "this opens once your registration has
  // been approved" to somebody whose registration was approved months ago is both wrong and
  // insulting. They need to know their time ran out and that nothing was lost.
  const expired = me.data?.access === 'EXPIRED';
  const completed = me.data?.access === 'COMPLETED';

  if (completed) {
    return (
      <div className="mx-auto max-w-lg rounded-3xl border border-white/70 bg-white/85 p-8 text-center shadow-[0_10px_30px_-12px_rgba(16,24,40,0.18)]">
        <span className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-oksoft text-ok">
          <Icon name="check" className="h-6 w-6" />
        </span>
        <Badge tone="ok">{t('Business account complete')}</Badge>
        <p className="mt-3 text-sm text-ink2">
          {t('Your item pack has been handed over, so this account is closed. Its history is on your dashboard.')}
        </p>
        <NavLink to="/portal">
          <Button variant="primary" size="sm" className="mt-4">
            {t('Go to my dashboard')}
          </Button>
        </NavLink>
      </div>
    );
  }

  return (
    <div className="mx-auto max-w-lg rounded-3xl border border-white/70 bg-white/85 p-8 text-center shadow-[0_10px_30px_-12px_rgba(16,24,40,0.18)]">
      <span className="mx-auto mb-4 flex h-14 w-14 items-center justify-center rounded-2xl bg-warnsoft text-warn">
        <Icon name="lock" className="h-6 w-6" />
      </span>
      <Badge tone={expired ? 'danger' : 'warn'}>
        {expired ? t('Membership ended') : t('Not available yet')}
      </Badge>
      <p className="mt-3 text-sm text-ink2">
        {expired
          ? t('Contact the office to renew. Your Business ID, your place and your referrals are all kept — everything reopens the moment it is extended.')
          : t('This opens once your business registration has been approved.')}
      </p>
      <NavLink to="/portal/registration">
        <Button variant="primary" size="sm" className="mt-4">
          {t('Go to my registration')}
        </Button>
      </NavLink>
    </div>
  );
}
