import { useState } from 'react';
import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Icon, type IconName } from '../components/icons';
import { SignOutDialog } from '../components/SignOutDialog';
import { PersonAvatar } from '../portal/portalUi';
import { useAuth } from '../auth/AuthContext';
import { asPercent, useMyOfficerRecord } from '../api/officers';
import { ErrorBanner, Spinner } from '../components/ui';

/**
 * The marketing officer's shell.
 *
 * <h2>Why not the staff shell</h2>
 *
 * <p>Officers used to sign in to {@code AppShell}, which lists a navigation item to everybody unless
 * that item names the roles allowed to see it. Catalogue, Inventory, Stores and the rest name none,
 * so an officer saw the whole staff menu — every link leading to a page the router would bounce them
 * off. A menu of dead ends is worse than no menu.
 *
 * <p>Fixing it by adding {@code roles} to a dozen entries would have left the same trap set for the
 * next entry somebody adds without them. An officer is not staff, so they get a shell whose
 * navigation is a closed list rather than an open one with exceptions.
 *
 * <h2>Four pills, derived from the gate</h2>
 *
 * <p>Dashboard, Revenue, My customers, My account — and, until an administrator approves them,
 * only the last. Linking to three pages that would all show the same "waiting for approval" notice
 * is a menu of disappointments; the customer portal reached the same conclusion for the same reason.
 *
 * <p>The look is the customer portal's on purpose: soft canvas, frosted header, pill navigation. An
 * officer is somebody the business is asking to go out and represent it, and the screen they open in
 * front of a prospective customer should look like something worth joining.
 */
export function OfficerShell() {
  const { t } = useTranslation();
  const { user } = useAuth();
  const me = useMyOfficerRecord();
  const [confirmingSignOut, setConfirmingSignOut] = useState(false);

  const approved = me.data?.status === 'approved';

  const items: Array<{ to: string; label: string; icon: IconName; end?: boolean }> = approved
    ? [
        { to: '/officer', label: t('Dashboard'), icon: 'dashboard', end: true },
        { to: '/officer/revenue', label: t('Revenue'), icon: 'pie' },
        { to: '/officer/customers', label: t('My customers'), icon: 'users' },
        { to: '/officer/account', label: t('My account'), icon: 'user' },
      ]
    : [
        // Waiting on a decision, or turned down. The one thing they can still do is change the
        // password the office gave them, so it is the one thing the menu offers.
        { to: '/officer', label: t('My application'), icon: 'clipboard', end: true },
        { to: '/officer/account', label: t('My account'), icon: 'user' },
      ];

  const nav = (
    <ul className="flex gap-1 overflow-x-auto rounded-full bg-white/70 p-1 ring-1 shadow-[0_4px_16px_-8px_rgba(16,24,40,0.2)] ring-white backdrop-blur">
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
              VH
            </span>
            <div className="hidden min-w-0 sm:block">
              <p className="truncate text-[15px] font-extrabold tracking-tight text-ink">
                Vertex Home Solutions
              </p>
              <p className="truncate text-[11px] font-medium text-ink3">
                {t('Marketing officer')}
              </p>
            </div>
          </div>

          <nav aria-label={t('Portal')} className="hidden flex-1 justify-center lg:flex">
            {nav}
          </nav>

          <div className="ml-auto flex items-center gap-2 lg:ml-0">
            <div className="hidden items-center gap-2.5 rounded-full bg-white/80 py-1 pr-3 pl-1 ring-1 shadow-xs ring-white sm:flex">
              <PersonAvatar name={user?.fullName} size="sm" />
              <div className="min-w-0 leading-tight">
                <p className="max-w-[140px] truncate text-xs font-semibold text-ink">
                  {user?.fullName}
                </p>
                {/* Their rate, where the customer portal carries a Business ID: it is the number
                    that decides what every figure on these screens comes to, so it belongs in the
                    chrome rather than on one page. */}
                {approved ? (
                  <p className="text-[11px] font-semibold text-brand">
                    {t('{{rate}} commission', { rate: asPercent(me.data?.commissionRate ?? 0) })}
                  </p>
                ) : (
                  <p className="text-[11px] text-ink3">{t('Application pending')}</p>
                )}
              </div>
            </div>
            <button
              type="button"
              onClick={() => setConfirmingSignOut(true)}
              aria-label={t('Sign out')}
              title={t('Sign out')}
              className="flex h-10 w-10 items-center justify-center rounded-full bg-white/80 text-ink3 ring-1 shadow-xs ring-white transition-colors hover:bg-dangersoft hover:text-danger"
            >
              <Icon name="logout" className="h-[18px] w-[18px]" />
            </button>
          </div>
        </div>

        {/* On narrower screens the pills move under the bar and scroll sideways. */}
        <nav
          aria-label={t('Portal')}
          className="mx-auto max-w-[1180px] px-4 pb-3 sm:px-6 lg:hidden"
        >
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
        © Vertex Home Solutions · {t('Distribution and inventory platform')}
      </footer>

      {confirmingSignOut && <SignOutDialog onClose={() => setConfirmingSignOut(false)} />}
    </div>
  );
}
