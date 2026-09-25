import { useEffect, useState } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { Icon, type IconName } from './icons';
import { SignOutDialog } from './SignOutDialog';
import { NotificationBell } from './NotificationBell';
import { ROLE_LABELS, useAuth } from '../auth/AuthContext';
import type { Role } from '../api/types';
import { useRewardsWaiting } from '../api/queries';

/**
 * The application frame.
 *
 * <h2>Layout</h2>
 *
 * A white rail on a grey canvas, in the admin-dashboard idiom the client asked for. Each item has
 * an icon tile; the active item's tile fills with the brand and its row takes a soft teal wash, so
 * the current page is found by colour before its label is read. Section headings are small
 * capitals in real contrast (4.9:1), not the faintest grey on the screen — they organise
 * everything beneath them.
 *
 * Above the content, a bar carries a breadcrumb of where you are, the notification bell and your
 * account. On a narrow screen the rail becomes a drawer behind a menu button: stacked above the
 * page, it pushed the work itself below the fold on every visit.
 *
 * <h2>Roles</h2>
 *
 * Navigation is filtered by role — but only as a courtesy. Hiding a link somebody cannot use is
 * good manners; it is not a control. Every route below is also guarded server-side by
 * {@code @PreAuthorize}, and typing the URL directly still returns 403. Architecture 8.1 is
 * explicit that client-side gating is presentation only.
 */

type NavItem = {
  to: string;
  label: string;
  icon: IconName;
  roles?: Role[];
  badge?: 'rewards';
};

export function AppShell() {
  const { t } = useTranslation();
  const { user, hasRole } = useAuth();

  const sections: Array<{ heading: string; items: NavItem[] }> = [
    {
      heading: t('Overview'),
      items: [{ to: '/', label: t('Dashboard'), icon: 'dashboard' }],
    },
    {
      heading: t('Catalogue'),
      items: [
        { to: '/items', label: t('Items'), icon: 'package' },
        { to: '/item-sets', label: t('Item sets'), icon: 'layers' },
      ],
    },
    {
      heading: t('Inventory'),
      items: [
        { to: '/stock', label: t('Stock'), icon: 'warehouse' },
        { to: '/stores', label: t('Stores'), icon: 'store' },
      ],
    },
    {
      heading: t('Procurement'),
      items: [
        { to: '/suppliers', label: t('Suppliers'), icon: 'truck' },
        { to: '/purchase-orders', label: t('Create order'), icon: 'clipboard' },
        // After orders because that is the order things happen in: an order is raised, sent and
        // signed for there, and lands here waiting for a shelf.
        { to: '/receiving', label: t('Received orders'), icon: 'inbox' },
      ],
    },
    {
      heading: t('Sales'),
      items: [
        { to: '/announcements', label: t('Announcements'), roles: ['ADMIN'], icon: 'megaphone' },
        { to: '/hierarchy', label: t('Referral hierarchy'), roles: ['ADMIN'], icon: 'network' },
        {
          to: '/rewards',
          label: t('Reward packs'),
          roles: ['ADMIN'],
          badge: 'rewards',
          icon: 'gift',
        },
        // Right after Reward packs: an issued pack moves from there to here.
        { to: '/reward-tracking', label: t('Pack tracking'), roles: ['ADMIN'], icon: 'truck' },
      ],
    },
    {
      heading: t('Reporting'),
      items: [
        { to: '/reports', label: t('Stock and sales'), icon: 'chart' },
        {
          to: '/analytics',
          label: t('Buyer analytics'),
          roles: ['FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT'],
          icon: 'pie',
        },
      ],
    },
    {
      // The whole path a person takes to become a customer, in the order it happens: register
      // them, verify the paperwork, and then find them in the list. Customers sat under Sales,
      // which put the finished record two headings away from the screen that created it.
      heading: t('Onboarding'),
      items: [
        {
          to: '/my-registration',
          // An administrator uses this screen to register other people, so calling it "mine"
          // describes the wrong thing entirely for them.
          label: hasRole('ADMIN') ? t('User registration') : t('My registration'),
          icon: 'userPlus',
        },
        {
          to: '/registrations',
          label: t('Registration verification'),
          roles: ['KYC_REVIEWER', 'ADMIN'],
          icon: 'shieldCheck',
        },
        { to: '/distributors', label: t('Customers'), roles: ['ADMIN'], icon: 'users' },
      ],
    },
    {
      heading: t('Administration'),
      items: [
        { to: '/users', label: t('Users and roles'), roles: ['SUPER_ADMIN'], icon: 'key' },
      ],
    },
  ];

  const visible = sections
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => !item.roles || hasRole(...item.roles)),
    }))
    .filter((section) => section.items.length > 0);

  const location = useLocation();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [confirmingSignOut, setConfirmingSignOut] = useState(false);

  // The drawer closes whenever the page changes: a tap on a link should land you on the page, not
  // leave the menu covering it.
  useEffect(() => {
    setDrawerOpen(false);
  }, [location.pathname]);

  // Where you are, for the breadcrumb: the most specific navigation entry the path falls under,
  // so /stores/abc still reads as Inventory / Stores.
  const current = visible
    .flatMap((section) => section.items.map((item) => ({ section: section.heading, item })))
    .filter(({ item }) =>
      item.to === '/'
        ? location.pathname === '/'
        : location.pathname === item.to || location.pathname.startsWith(`${item.to}/`),
    )
    .sort((a, b) => b.item.to.length - a.item.to.length)[0];

  const rail = (
    <div className="flex h-full flex-col">
      <div className="flex items-center gap-3 px-5 pt-6 pb-5">
        <span
          aria-hidden
          className="flex h-10 w-10 flex-none items-center justify-center rounded-xl bg-linear-to-br from-brandbright to-brand text-[14px] font-extrabold text-white shadow-sm"
        >
          MS
        </span>
        <div className="min-w-0">
          <p className="truncate text-[15px] font-bold tracking-tight text-navink">MLM Sittu</p>
          <p className="truncate text-xs text-navink3">{t('Distribution & inventory')}</p>
        </div>
      </div>

      <div className="mx-5 h-px bg-linear-to-r from-transparent via-brand/20 to-transparent" />

      <div className="flex-1 overflow-y-auto px-3 py-4">
        {visible.map((section) => (
          <div key={section.heading} className="mb-4 last:mb-0">
            <p className="mb-1.5 px-3 text-[11px] font-semibold tracking-[0.08em] text-navink3 uppercase">
              {section.heading}
            </p>
            <ul className="flex flex-col gap-0.5">
              {section.items.map((item) => (
                <li key={item.to}>
                  <NavLink
                    to={item.to}
                    end={item.to === '/'}
                    className={({ isActive }) =>
                      'group flex items-center gap-3 rounded-xl px-2.5 py-1.5 text-sm transition-[background-color,box-shadow,color] duration-150 ' +
                      (isActive
                        ? // A white card lifted off the tinted rail — the brightest thing on it,
                          // so the current page is found before it is read.
                          'bg-white font-semibold text-navink shadow-[0_6px_16px_-8px_rgba(11,122,110,0.45)]'
                        : 'font-medium text-navink2 hover:bg-white/70 hover:text-navink')
                    }
                  >
                    {({ isActive }) => (
                      <>
                        {/* The tile carries the state: filled brand when active, a quiet grey
                            square otherwise, so the rail reads as a column of places rather than
                            a list of words. */}
                        <span
                          className={
                            'flex h-8 w-8 flex-none items-center justify-center rounded-lg transition-colors ' +
                            (isActive
                              ? 'bg-linear-to-br from-brandbright to-brand text-white shadow-sm'
                              : 'bg-white text-brand shadow-xs ring-1 ring-navedge group-hover:ring-brand/30')
                          }
                        >
                          <Icon name={item.icon} className="h-[17px] w-[17px]" />
                        </span>
                        <span className="truncate">{item.label}</span>
                        {item.badge === 'rewards' && <RewardBadge />}
                      </>
                    )}
                  </NavLink>
                </li>
              ))}
            </ul>
          </div>
        ))}
      </div>

      {user && (
        <div className="p-3">
          <div className="flex items-center gap-3 rounded-2xl bg-white/85 p-2.5 shadow-card ring-1 ring-white backdrop-blur">
            <Avatar name={user.fullName} />
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13.5px] font-semibold text-navink">{user.fullName}</p>
              <p className="truncate text-xs text-navink3">
                {(user.roles ?? [])
                  .map((role) => ROLE_LABELS[role as Role] ?? role)
                  .join(', ') ||
                  user.email ||
                  user.mobile}
              </p>
            </div>
            <button
              type="button"
              onClick={() => setConfirmingSignOut(true)}
              title={t('Sign out')}
              aria-label={t('Sign out')}
              className="flex h-9 w-9 flex-none items-center justify-center rounded-lg text-navink3 transition-colors hover:bg-dangersoft hover:text-danger"
            >
              <Icon name="logout" className="h-[18px] w-[18px]" />
            </button>
          </div>
        </div>
      )}
    </div>
  );

  return (
    <div className="min-h-dvh bg-ground lg:grid lg:grid-cols-[272px_minmax(0,1fr)]">
      {/* Desktop: a fixed rail. */}
      <nav
        aria-label={t('Main')}
        className="nav-surface hidden border-r border-navedge lg:sticky lg:top-0 lg:block lg:h-dvh"
      >
        {rail}
      </nav>

      {/* Narrow screens: the same rail as a drawer. */}
      {drawerOpen && (
        <div className="fixed inset-0 z-40 lg:hidden" role="dialog" aria-modal="true">
          <button
            type="button"
            aria-label={t('Close menu')}
            className="absolute inset-0 bg-ink/40 backdrop-blur-[2px]"
            onClick={() => setDrawerOpen(false)}
          />
          <nav
            aria-label={t('Main')}
            className="nav-surface absolute inset-y-0 left-0 w-[284px] max-w-[85vw] shadow-float"
          >
            <button
              type="button"
              aria-label={t('Close menu')}
              onClick={() => setDrawerOpen(false)}
              className="absolute top-6 right-3 flex h-9 w-9 items-center justify-center rounded-lg text-navink3 hover:bg-navraised hover:text-navink"
            >
              <Icon name="close" className="h-5 w-5" />
            </button>
            {rail}
          </nav>
        </div>
      )}

      <div className="flex min-w-0 flex-col">
        {/* The top bar. Translucent over the canvas so a long page scrolls under it without the
            bar reading as a second, heavier header. */}
        <header className="sticky top-0 z-30 flex h-16 items-center gap-3 border-b border-navedge/70 bg-ground/85 px-4 backdrop-blur-md sm:px-8">
          <button
            type="button"
            aria-label={t('Open menu')}
            onClick={() => setDrawerOpen(true)}
            className="flex h-10 w-10 items-center justify-center rounded-lg text-ink2 hover:bg-panel hover:text-ink lg:hidden"
          >
            <Icon name="menu" className="h-5 w-5" />
          </button>
          <nav aria-label={t('Breadcrumb')} className="min-w-0 flex-1">
            <ol className="flex items-center gap-1.5 text-[13px]">
              <li className="hidden text-ink3 sm:block">{current?.section ?? t('Overview')}</li>
              <li aria-hidden className="hidden text-ink3 sm:block">
                /
              </li>
              <li className="truncate font-semibold text-ink" aria-current="page">
                {current?.item.label ?? t('Dashboard')}
              </li>
            </ol>
          </nav>
          <NotificationBell historyPath="/notifications" />
          {user && (
            <span className="hidden sm:block">
              <Avatar name={user.fullName} />
            </span>
          )}
        </header>

        <main className="mx-auto w-full max-w-[1400px] min-w-0 px-4 py-6 sm:px-8 sm:py-8">
          <Outlet />
        </main>
        {confirmingSignOut && <SignOutDialog onClose={() => setConfirmingSignOut(false)} />}
      </div>
    </div>
  );
}

/** Initials in a tinted circle. Stands in for a photo the system does not hold. */
function Avatar({ name }: { name: string | null | undefined }) {
  const initials =
    (name ?? '')
      .split(/\s+/)
      .filter(Boolean)
      .slice(0, 2)
      .map((part) => part[0]?.toUpperCase())
      .join('') || '?';
  return (
    <span
      aria-hidden
      className="flex h-9 w-9 flex-none items-center justify-center rounded-full bg-brandsoft text-[13px] font-bold text-brand ring-2 ring-panel"
    >
      {initials}
    </span>
  );
}

/**
 * How many reward packs are waiting on an administrator.
 *
 * Only rendered inside a link that is already role-gated, so the request is never made by somebody
 * who would be refused it. Shown only when there is something to do — a "0" badge is noise that
 * trains people to ignore whatever sits next to it.
 */
function RewardBadge() {
  const waiting = useRewardsWaiting();
  const count = waiting.data?.waiting ?? 0;

  if (count === 0) {
    return null;
  }

  return (
    <span className="nums ml-auto rounded-full bg-danger px-2 py-0.5 text-[11px] font-bold text-white">
      {count}
    </span>
  );
}
