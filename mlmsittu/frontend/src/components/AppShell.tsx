import { NavLink, Outlet } from 'react-router-dom';
import { useTranslation } from 'react-i18next';
import { NotificationBell } from './NotificationBell';
import { ROLE_LABELS, useAuth } from '../auth/AuthContext';
import type { Role } from '../api/types';
import { useRewardsWaiting } from '../api/queries';

/**
 * The application frame.
 *
 * <h2>Why the rail looks the way it does</h2>
 *
 * It used to be a white panel on a near-white page, which gave it no edge and no presence — it
 * read as an absence rather than a region. The hierarchy was inverted too: the section headings,
 * the labels that organise the whole application, were the faintest marks on the screen at 9.5px
 * of pale grey, with 0.14em tracking that costs legibility at that size rather than adding it.
 *
 * Three changes, none of them darkness: a tinted ground so the rail is visibly a region; headings
 * in real ink at a readable size; and an active item filled with the brand rather than hinted at
 * with a wash of it. The type went from 13px in a 28px row to 14.5px in a 40px one, which is both
 * easier to read and easier to hit.
 *
 * <h2>Roles</h2>
 *
 * Navigation is filtered by role — but only as a courtesy. Hiding a link somebody cannot use is
 * good manners; it is not a control. Every route below is also guarded server-side by
 * {@code @PreAuthorize}, and typing the URL directly still returns 403. Architecture 8.1 is
 * explicit that client-side gating is presentation only.
 */

type NavItem = { to: string; label: string; roles?: Role[]; badge?: 'rewards' };

export function AppShell() {
  const { t } = useTranslation();
  const { user, signOut, hasRole } = useAuth();

  const sections: Array<{ heading: string; items: NavItem[] }> = [
    {
      heading: t('Overview'),
      items: [{ to: '/', label: t('Dashboard') }],
    },
    {
      heading: t('Catalogue'),
      items: [
        { to: '/items', label: t('Items') },
        { to: '/item-sets', label: t('Item sets') },
      ],
    },
    {
      heading: t('Inventory'),
      items: [
        { to: '/stock', label: t('Stock') },
        { to: '/stores', label: t('Stores') },
      ],
    },
    {
      heading: t('Procurement'),
      items: [
        { to: '/suppliers', label: t('Suppliers') },
        { to: '/purchase-orders', label: t('Create order') },
        // After orders because that is the order things happen in: an order is raised, sent and
        // signed for there, and lands here waiting for a shelf.
        { to: '/receiving', label: t('Received orders') },
      ],
    },
    {
      heading: t('Sales'),
      items: [
        { to: '/announcements', label: t('Announcements'), roles: ['ADMIN'] },
        { to: '/hierarchy', label: t('Referral hierarchy'), roles: ['ADMIN'] },
        { to: '/rewards', label: t('Reward packs'), roles: ['ADMIN'], badge: 'rewards' },
      ],
    },
    {
      heading: t('Reporting'),
      items: [
        { to: '/reports', label: t('Stock and sales') },
        {
          to: '/analytics',
          label: t('Buyer analytics'),
          roles: ['FINANCE_OFFICER', 'SUPER_ADMIN', 'SUPPORT_AGENT'],
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
        },
        {
          to: '/registrations',
          label: t('Registration verification'),
          roles: ['KYC_REVIEWER', 'ADMIN'],
        },
        { to: '/distributors', label: t('Customers'), roles: ['ADMIN'] },
      ],
    },
    {
      heading: t('Administration'),
      items: [{ to: '/users', label: t('Users and roles'), roles: ['SUPER_ADMIN'] }],
    },
  ];

  const visible = sections
    .map((section) => ({
      ...section,
      items: section.items.filter((item) => !item.roles || hasRole(...item.roles)),
    }))
    .filter((section) => section.items.length > 0);

  return (
    <div className="min-h-dvh bg-ground">
      <div className="mx-auto grid max-w-[1600px] grid-cols-1 lg:grid-cols-[268px_minmax(0,1fr)]">
        <nav className="border-r border-navedge bg-nav px-3 py-5 lg:sticky lg:top-0 lg:h-dvh lg:overflow-y-auto">
          {/* The mark. Larger and on a lighter green block, so the eye has a fixed anchor at the
              top of the rail rather than having to find one. */}
          <div className="mb-7 flex items-center gap-3 px-2">
            {/* The one place the brand runs at full strength while at rest, so the eye has a
                fixed anchor at the top of the rail instead of having to find one. */}
            {/* White on green, where it used to be green on pale. Full-strength brand on a
                brand-coloured rail would have disappeared into it. */}
            <span
              aria-hidden
              className="flex h-10 w-10 flex-none items-center justify-center rounded-xl bg-white text-[15px] font-bold text-brand shadow-sm"
            >
              MS
            </span>
            <div className="min-w-0">
              <p className="truncate text-[15px] font-bold tracking-tight text-navink">
                MLM Sittu
              </p>
              <p className="truncate text-[11px] text-navink3">{t('Distribution & inventory')}</p>
            </div>
          </div>

          <div className="flex flex-wrap gap-x-6 gap-y-4 lg:block">
            {visible.map((section) => (
              <div key={section.heading} className="lg:mb-6">
                {/* 11px in real ink, where it used to be 9.5px of the palest grey in the
                    palette. These labels organise everything below them and were the hardest
                    thing on the rail to read, which is exactly backwards. */}
                {/* The group label. Bold and wider-tracked, because it names everything
                    beneath it and a heading that is quieter than its own contents inverts the
                    hierarchy it exists to express. */}
                <p className="mb-1.5 px-3 text-[11px] font-bold tracking-[0.1em] text-navink3 uppercase">
                  {section.heading}
                </p>
                {/* Indented, with a rule down the left.
                    
                    Indentation alone leaves it to the eye to infer the grouping from a few pixels
                    of gap. The rule makes it a fact: everything to the right of this line belongs
                    to the heading above it, and where a group ends is visible rather than
                    deduced. */}
                <ul className="ml-3 flex flex-col gap-0.5 border-l border-white/15 pl-2">
                  {section.items.map((item) => (
                    <li key={item.to}>
                      <NavLink
                        to={item.to}
                        end={item.to === '/'}
                        className={({ isActive }) =>
                          'relative flex items-center gap-2 rounded-lg py-2.5 pr-3 pl-3 text-[14.5px] transition-colors duration-150 ' +
                          (isActive
                            ? // White block, green text: the inverse of everything around it, and
                              // the brightest thing on the rail. On a dark surface this is the
                              // strongest mark available — a tinted fill would have to compete
                              // with the green it sits on.
                              'bg-white font-bold text-brand shadow-sm'
                            : // Three distinct states, not two. Resting text is deliberately
                              // dimmer than white so the active item is found by brightness
                              // before it is read; hover lifts the row to a lighter green and
                              // the text to full white, so it is clear what is about to be
                              // clicked without it pretending to be selected.
                              'font-medium text-navink2 hover:bg-navraised hover:text-navink')
                        }
                      >
                        <span className="truncate">{item.label}</span>
                        {item.badge === 'rewards' && <RewardBadge />}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
          </div>

          {/* The user card is lifted out of the rail rather than dropped on top of it. A white
              card here would be the brightest block on the sidebar and would compete with the
              active nav item, which is the one thing that should win. */}
          {user && (
            <div className="mt-7 rounded-xl border border-white/15 bg-white/10 p-3.5">
              <p className="truncate text-[13.5px] font-semibold text-navink">{user.fullName}</p>
              <p className="truncate text-[11.5px] text-navink3">{user.email ?? user.mobile}</p>
              <div className="mt-2.5 flex flex-wrap gap-1">
                {(user.roles ?? []).map((role) => (
                  <span
                    key={role}
                    className="rounded-full border border-white/25 bg-white/15 px-2 py-0.5 text-[10.5px] font-semibold text-navink"
                  >
                    {ROLE_LABELS[role as Role] ?? role}
                  </span>
                ))}
              </div>
              {/* Not the shared Button: every variant it offers is drawn for a light surface, and
                  a white-bordered control on the green rail reads as a mistake. One button styled
                  where it lives beats a fifth variant that exists for one caller. */}
              <button
                type="button"
                onClick={() => void signOut()}
                className="mt-3 w-full rounded-md border border-white/30 py-1.5 text-xs font-semibold text-navink2 transition-colors hover:border-white hover:bg-white hover:text-brand"
              >
                {t('Sign out')}
              </button>
            </div>
          )}
        </nav>

        <main className="min-w-0 px-5 py-7 sm:px-8">
          {/* The bell sits above the content rather than in the dark rail: it belongs to whatever
              screen you are on, it needs a panel that opens downward into space, and the rail
              scrolls away on a narrow window. */}
          <div className="mb-4 flex justify-end">
            <NotificationBell historyPath="/notifications" />
          </div>
          <Outlet />
        </main>
      </div>
    </div>
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
    <span className="nums ml-auto rounded-full bg-warn px-1.5 py-0.5 text-[10.5px] font-bold text-white">
      {count}
    </span>
  );
}
