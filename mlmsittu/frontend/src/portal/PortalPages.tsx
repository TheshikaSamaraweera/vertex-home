import { useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router-dom';
import { usePortalMe, usePortalReferrals, type DistributorNode } from '../api/portal';
import { ErrorBanner, Spinner } from '../components/ui';
import { Icon, type IconName } from '../components/icons';
import { useLiveAnnouncements } from '../api/announcements';
import { AnnouncementCard } from '../pages/AnnouncementsPage';
import { expiryStatus } from '../lib/expiry';
import { REFERRAL_STAGES } from '../lib/stages';
import { CopyButton, GlassCard, PersonAvatar, ProgressRing, SectionTitle } from './portalUi';
import { CompletedAccount, PackJourneyCard } from './PortalPackJourney';

/**
 * The four screens a distributor gets once their registration is approved.
 *
 * Every one of them reads the same `/portal/me` response, which is served behind the gate — so
 * none of these components can accidentally show something the server would have withheld.
 *
 * The look is deliberately richer than the staff app: a customer opens this to see how they are
 * doing and to show the person they are about to refer, so progress is drawn (a ring, a journey,
 * faces) rather than listed.
 */

const dateOf = (iso?: string | null) =>
  iso ? new Date(iso).toLocaleDateString(undefined, { dateStyle: 'medium' }) : '—';

/**
 * What the office is saying, newest first.
 *
 * <p>Renders nothing at all when there is nothing to say — an empty "no announcements" panel is a
 * permanent hole in the page in exchange for information nobody needed.
 */
function Announcements() {
  const { t } = useTranslation();
  const { data } = useLiveAnnouncements();
  if (!data || data.length === 0) return null;

  return (
    <section className="mt-8">
      <SectionTitle eyebrow={t('News')} title={t('From the office')} />
      <div className="grid gap-5 md:grid-cols-2">
        {data.map((announcement) => (
          <AnnouncementCard key={announcement.id} announcement={announcement} />
        ))}
      </div>
    </section>
  );
}

// ================================================================== dashboard

export function PortalDashboard() {
  const { t } = useTranslation();
  const me = usePortalMe();

  // Until the registration is approved there is exactly one thing to do, so land on it.
  //
  // The redirect waits for the first load rather than guessing: sending an approved customer to
  // the registration page for half a second, every time they open the portal, is worse than a
  // spinner.
  if (me.isLoading) {
    return <Spinner />;
  }
  // Once the pack has been handed over the account is closed, and the dashboard becomes its record.
  if (me.data?.access === 'COMPLETED') {
    return (
      <>
        <CompletedAccount me={me.data} />
        <Announcements />
      </>
    );
  }
  if (me.data && me.data.access !== 'ACTIVE') {
    return <Navigate to="/portal/registration" replace />;
  }

  const stages = me.data?.stages;
  const completed = stages?.stagesCompleted ?? 0;
  const total = stages?.totalStages ?? REFERRAL_STAGES;
  const children = me.data?.children ?? [];
  const placesLeft = Math.max(0, REFERRAL_STAGES - children.length);
  const firstName = (me.data?.fullName ?? '').split(' ')[0];

  return (
    <>
      <div className="grid gap-5 lg:grid-cols-[minmax(0,1.55fr)_minmax(0,1fr)]">
        {/* The hero: greeting, where they stand, and the two things they are likely to do next. */}
        <section className="relative overflow-hidden rounded-3xl bg-linear-to-br from-[#0b7a6e] via-[#0f8d80] to-[#3f5bd8] p-7 text-white shadow-[0_20px_40px_-18px_rgba(11,122,110,0.65)] sm:p-8">
          <div aria-hidden className="pointer-events-none absolute -top-20 -right-16 h-64 w-64 rounded-full bg-white/10" />
          <div aria-hidden className="pointer-events-none absolute -bottom-28 left-1/3 h-72 w-72 rounded-full bg-[#7ee0d3]/15 blur-2xl" />
          <div className="relative flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
            <div className="min-w-0">
              <p className="inline-flex items-center gap-1.5 rounded-full bg-white/15 px-3 py-1 text-xs font-semibold ring-1 ring-white/20">
                <Icon name="sparkles" className="h-3.5 w-3.5" />
                {completed >= total ? t('All levels complete') : t('Level {{n}} of {{total}}', { n: completed, total })}
              </p>
              <h1 className="mt-4 text-3xl leading-tight font-extrabold tracking-tight sm:text-[34px]">
                {t('Welcome back, {{name}}', { name: firstName || me.data?.fullName || '' })}
              </h1>
              <p className="mt-2 max-w-md text-[15px] text-white/85">
                {completed >= total
                  ? t('You have completed every level. Your item pack is on its way to you.')
                  : t('Refer {{left}} more to complete every level and earn your item pack.', {
                      left: total - completed,
                    })}
              </p>
              {/* On a phone the ring would sit under the buttons; a bar says the same in one line. */}
              <div className="mt-4 h-2 w-full overflow-hidden rounded-full bg-white/20 sm:hidden" aria-hidden>
                <div className="h-full rounded-full bg-white" style={{ width: `${(Math.min(completed, total) / total) * 100}%` }} />
              </div>
              <div className="mt-6 flex flex-wrap gap-2.5">
                <Link
                  to="/portal/referrals"
                  className="inline-flex h-10 items-center gap-2 rounded-full bg-white px-4 text-sm font-bold text-brand shadow-sm transition-transform hover:-translate-y-0.5"
                >
                  {t('My referrals')}
                  <Icon name="arrowRight" className="h-4 w-4" />
                </Link>
                <Link
                  to="/portal/stages"
                  className="inline-flex h-10 items-center rounded-full bg-white/15 px-4 text-sm font-semibold ring-1 ring-white/25 transition-colors hover:bg-white/25"
                >
                  {t('See my stages')}
                </Link>
              </div>
            </div>
            <div className="hidden sm:block">
              <ProgressRing value={completed} total={total} label={t('levels')} />
            </div>
          </div>
        </section>

        <MembershipCard
          name={me.data?.fullName ?? ''}
          businessId={me.data?.businessId ?? ''}
          since={me.data?.approvedAt ?? me.data?.joinedAt}
          expires={me.data?.expiresAt}
        />
      </div>

      <div className="mt-5 grid grid-cols-2 gap-4 lg:grid-cols-4">
        <Stat icon="trophy" tone="teal" label={t('Current level')} value={`${completed}/${total}`} />
        <Stat icon="users" tone="indigo" label={t('Direct referrals')} value={String(children.length)} />
        <Stat icon="userPlus" tone="amber" label={t('Places left')} value={String(placesLeft)} />
        <Stat icon="calendar" tone="pink" label={t('Member since')} value={dateOf(me.data?.approvedAt ?? me.data?.joinedAt)} small />
      </div>

      {me.data && <PackJourneyCard me={me.data} />}

      <div className="mt-5 grid gap-5 lg:grid-cols-[minmax(0,1.55fr)_minmax(0,1fr)]">
        <GlassCard>
          <div className="flex items-start justify-between gap-3">
            <SectionTitle eyebrow={t('Progress')} title={t('Your journey')} />
            <Link to="/portal/stages" className="text-sm font-semibold text-brand hover:underline">
              {t('Details')}
            </Link>
          </div>
          <Journey completed={completed} total={total} bonus={stages?.bonusStageEligible} />
        </GlassCard>

        <GlassCard>
          <SectionTitle eyebrow={t('Upline')} title={t('Who referred you')} />
          {me.data?.parent ? (
            <div className="flex items-center gap-4 rounded-2xl bg-panel2 p-4">
              <PersonAvatar name={me.data.parent.fullName} size="lg" />
              <div className="min-w-0">
                <p className="truncate font-semibold text-ink">{me.data.parent.fullName}</p>
                <p className="font-mono text-sm font-semibold text-brand">
                  ID {me.data.parent.businessId}
                </p>
              </div>
            </div>
          ) : (
            <div className="flex items-center gap-4 rounded-2xl bg-linear-to-r from-brandsoft to-[#eef1ff] p-4">
              <span className="flex h-14 w-14 flex-none items-center justify-center rounded-full bg-white text-brand shadow-sm">
                <Icon name="star" className="h-6 w-6" />
              </span>
              <p className="text-sm font-medium text-ink2">
                {t('Nobody — you are at the top of your own tree.')}
              </p>
            </div>
          )}

          <div className="mt-6 flex items-center justify-between">
            <p className="text-sm font-semibold text-ink">{t('Your team')}</p>
            <Link to="/portal/referrals" className="text-sm font-semibold text-brand hover:underline">
              {t('View all')}
            </Link>
          </div>
          {children.length === 0 ? (
            <p className="mt-2 text-sm text-ink3">
              {t('Share your Business ID — each person who registers with it unlocks a level.')}
            </p>
          ) : (
            <div className="mt-3 flex -space-x-2">
              {children.slice(0, 5).map((child) => (
                <span key={child.id} title={child.fullName ?? ''}>
                  <PersonAvatar name={child.fullName} className="ring-2 ring-white" />
                </span>
              ))}
              {placesLeft > 0 &&
                Array.from({ length: placesLeft }, (_, index) => (
                  <span
                    key={`open-${index}`}
                    className="flex h-10 w-10 items-center justify-center rounded-full border-2 border-dashed border-rulestrong bg-white text-ink3"
                    title={t('Open place')}
                  >
                    <Icon name="userPlus" className="h-4 w-4" />
                  </span>
                ))}
            </div>
          )}
        </GlassCard>
      </div>

      {/* Below the figures now: the figures are theirs, the notices are the office's. */}
      <Announcements />
    </>
  );
}

/**
 * The Business ID as a membership card — the thing a customer reads out to the person they are
 * referring, so it is the largest text on the card and has a copy button beside it.
 */
function MembershipCard({
  name,
  businessId,
  since,
  expires,
}: {
  name: string;
  businessId: string;
  since?: string | null;
  /** When the membership lapses; null for one with no end date. */
  expires?: string | null;
}) {
  const { t } = useTranslation();
  // The date goes amber inside 30 days and red inside 7, so a renewal is asked for before the
  // portal closes rather than discovered after.
  const expiry = expiryStatus(expires);
  const expiryTone =
    expiry.band === 'urgent' || expiry.band === 'expired'
      ? 'text-[#ffb4a8]'
      : expiry.band === 'soon'
        ? 'text-[#ffd98a]'
        : 'text-white';
  return (
    <section className="relative flex min-h-[240px] flex-col justify-between overflow-hidden rounded-3xl bg-linear-to-br from-[#0f1b2d] via-[#123a44] to-[#0b5f56] p-6 text-white shadow-[0_20px_40px_-18px_rgba(15,27,45,0.7)]">
      <div aria-hidden className="pointer-events-none absolute -top-24 -right-24 h-64 w-64 rounded-full bg-[#38b2a3]/25 blur-2xl" />
      <div aria-hidden className="pointer-events-none absolute right-6 bottom-6 h-24 w-24 rounded-full border border-white/10" />
      <div aria-hidden className="pointer-events-none absolute right-12 bottom-12 h-24 w-24 rounded-full border border-white/10" />

      <div className="relative flex items-start justify-between">
        <div>
          <p className="text-[11px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Member')}</p>
          <p className="mt-0.5 text-sm font-semibold">MLM Sittu</p>
        </div>
        {/* The chip: pure decoration, and the thing that makes it read as a card at a glance. */}
        <span aria-hidden className="h-8 w-11 rounded-md bg-linear-to-br from-[#f6d58e] to-[#c99a3c] shadow-inner" />
      </div>

      <div className="relative">
        <p className="text-[11px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Business ID')}</p>
        <div className="mt-1 flex flex-wrap items-center gap-3">
          <p className="font-mono text-4xl font-bold tracking-[0.12em]">{businessId || '—'}</p>
          <CopyButton value={businessId} variant="glass" label={t('Copy ID')} />
        </div>
      </div>

      <div className="relative flex items-end justify-between gap-3">
        <div className="min-w-0">
          <p className="text-[10px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Name')}</p>
          <p className="truncate text-sm font-semibold">{name}</p>
        </div>
        <div className="flex gap-5 text-right">
          <div>
            <p className="text-[10px] font-bold tracking-[0.16em] text-white/60 uppercase">{t('Since')}</p>
            <p className="text-sm font-semibold">{dateOf(since)}</p>
          </div>
          <div>
            <p className="text-[10px] font-bold tracking-[0.16em] text-white/60 uppercase">
              {t('Valid until')}
            </p>
            <p className={`text-sm font-semibold ${expiryTone}`}>
              {expires ? dateOf(expires) : t('No end date')}
            </p>
            {(expiry.band === 'soon' || expiry.band === 'urgent') && (
              <p className={`text-[11px] font-semibold ${expiryTone}`}>
                {t('{{days}} days left', { days: expiry.days })}
              </p>
            )}
          </div>
        </div>
      </div>
    </section>
  );
}

const STAT_TONES = {
  teal: 'from-[#38b2a3] to-[#0b7a6e]',
  indigo: 'from-[#7c8cff] to-[#4f5bd5]',
  amber: 'from-[#f6b35c] to-[#e07a2b]',
  pink: 'from-[#f472b6] to-[#c0266d]',
} as const;

function Stat({
  icon,
  tone,
  label,
  value,
  small,
}: {
  icon: IconName;
  tone: keyof typeof STAT_TONES;
  label: string;
  value: string;
  small?: boolean;
}) {
  return (
    <div className="flex items-center gap-3 rounded-3xl border border-white/70 bg-white/85 p-3.5 shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)] sm:gap-3.5 sm:p-5">
      <span
        className={`flex h-10 w-10 flex-none items-center justify-center rounded-2xl bg-linear-to-br text-white shadow-sm sm:h-12 sm:w-12 ${STAT_TONES[tone]}`}
      >
        <Icon name={icon} className="h-[22px] w-[22px]" />
      </span>
      <div className="min-w-0">
        <p className="text-[11px] leading-tight font-medium text-ink3 sm:text-xs">{label}</p>
        <p
          className={`nums mt-0.5 leading-tight font-extrabold tracking-tight text-ink ${small ? 'text-sm sm:text-base' : 'text-2xl'}`}
        >
          {value}
        </p>
      </div>
    </div>
  );
}

/** Five levels and the bonus as a stepper: filled up to where you are, a pulse on what is next. */
function Journey({ completed, total, bonus }: { completed: number; total: number; bonus?: boolean }) {
  const { t } = useTranslation();
  const steps = Array.from({ length: total }, (_, index) => index + 1);

  return (
    <div>
      <ol className="relative flex items-start justify-between">
        <span aria-hidden className="absolute top-5 right-5 left-5 h-1 rounded-full bg-rulestrong" />
        <span
          aria-hidden
          className="absolute top-5 left-5 h-1 rounded-full bg-linear-to-r from-brandbright to-brand transition-all duration-700"
          style={{ width: `calc((100% - 2.5rem) * ${Math.min(completed, total) / total})` }}
        />
        {steps.map((level) => {
          const done = level <= completed;
          const next = level === completed + 1;
          return (
            <li key={level} className="relative flex flex-col items-center gap-2">
              <span
                className={
                  'relative flex h-10 w-10 items-center justify-center rounded-full text-sm font-bold transition-colors ' +
                  (done
                    ? 'bg-linear-to-br from-brandbright to-brand text-white shadow-[0_6px_14px_-6px_rgba(11,122,110,0.8)]'
                    : next
                      ? 'bg-white text-brand ring-2 ring-brand'
                      : 'bg-white text-ink3 ring-1 ring-rulestrong')
                }
              >
                {next && <span aria-hidden className="absolute inset-0 animate-ping rounded-full bg-brand/20" />}
                {done ? <Icon name="check" className="h-5 w-5" /> : level}
              </span>
              <span className={`text-xs font-semibold ${done ? 'text-ink' : 'text-ink3'}`}>
                {t('Level {{level}}', { level })}
              </span>
            </li>
          );
        })}
      </ol>
      <div
        className={
          'mt-6 flex items-center gap-3 rounded-2xl p-4 ' +
          (bonus ? 'bg-linear-to-r from-[#fff4d6] to-[#ffe9c2]' : 'bg-panel2')
        }
      >
        <span
          className={
            'flex h-10 w-10 flex-none items-center justify-center rounded-full ' +
            (bonus ? 'bg-linear-to-br from-[#f6c453] to-[#e09a1a] text-white shadow-sm' : 'bg-white text-ink3')
          }
        >
          <Icon name="star" className="h-5 w-5" />
        </span>
        <div>
          <p className="text-sm font-bold text-ink">{t('Bonus stage')}</p>
          <p className="text-xs text-ink2">
            {bonus
              ? t('You are eligible. The office will be in touch about what it involves.')
              : t('Unlocks when all five levels are complete.')}
          </p>
        </div>
      </div>
    </div>
  );
}

// ================================================================== my details

export function PortalDetails() {
  const { t } = useTranslation();
  const me = usePortalMe();

  const rows: Array<{ icon: IconName; label: string; value?: string | null; mono?: boolean }> = [
    { icon: 'idCard', label: t('Business ID'), value: me.data?.businessId, mono: true },
    { icon: 'mail', label: t('Email'), value: me.data?.email },
    { icon: 'phone', label: t('Mobile'), value: me.data?.mobile },
    { icon: 'calendar', label: t('Account created'), value: dateOf(me.data?.joinedAt) },
    { icon: 'shieldCheck', label: t('Approved as a customer'), value: dateOf(me.data?.approvedAt) },
    {
      icon: 'lock',
      label: t('Registered with NIC ending'),
      value: me.data?.registration?.nicLast4 ? `••••${me.data.registration.nicLast4}` : null,
      mono: true,
    },
    { icon: 'users', label: t('Referred by'), value: me.data?.registration?.referrerBusinessId, mono: true },
  ];

  return (
    <>
      <section className="relative overflow-hidden rounded-3xl border border-white/70 bg-white/85 shadow-[0_10px_30px_-12px_rgba(16,24,40,0.18)]">
        <div className="h-28 bg-linear-to-r from-[#0b7a6e] via-[#1f9e8f] to-[#5b6fe0]" />
        <div className="flex flex-col gap-4 px-6 pb-6 sm:flex-row sm:items-end">
          <PersonAvatar name={me.data?.fullName} size="xl" className="-mt-10 ring-4 ring-white" />
          <div className="min-w-0 flex-1">
            <h1 className="truncate text-2xl font-extrabold tracking-tight text-ink">{me.data?.fullName}</h1>
            <p className="text-sm text-ink3">{t('What we hold about you.')}</p>
          </div>
          {me.data?.businessId && <CopyButton value={me.data.businessId} label={t('Copy Business ID')} />}
        </div>
      </section>

      <div className="mt-5 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {rows.map((row) => (
          <div
            key={row.label}
            className="flex items-center gap-3.5 rounded-2xl border border-white/70 bg-white/85 p-4 shadow-[0_8px_24px_-14px_rgba(16,24,40,0.2)]"
          >
            <span className="flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-brandsoft text-brand">
              <Icon name={row.icon} className="h-5 w-5" />
            </span>
            <div className="min-w-0">
              <p className="text-xs font-medium text-ink3">{row.label}</p>
              <p className={`truncate text-sm font-semibold text-ink ${row.mono ? 'font-mono' : ''}`}>
                {row.value || '—'}
              </p>
            </div>
          </div>
        ))}
      </div>

      <p className="mt-5 flex items-start gap-2 text-xs text-ink3">
        <Icon name="lock" className="mt-0.5 h-3.5 w-3.5 flex-none" />
        {t('To change any of this, contact the office. Your NIC and bank details are held encrypted and are not shown here.')}
      </p>
    </>
  );
}

// ================================================================== stages

export function PortalStages() {
  const { t } = useTranslation();
  const me = usePortalMe();

  const stages = me.data?.stages;
  const completed = stages?.stagesCompleted ?? 0;
  const total = stages?.totalStages ?? REFERRAL_STAGES;
  const children = me.data?.children ?? [];

  return (
    <>
      <section className="relative overflow-hidden rounded-3xl bg-linear-to-br from-[#0b7a6e] via-[#0f8d80] to-[#3f5bd8] p-7 text-white shadow-[0_20px_40px_-18px_rgba(11,122,110,0.65)]">
        <div aria-hidden className="pointer-events-none absolute -top-16 -right-10 h-56 w-56 rounded-full bg-white/10" />
        <div className="relative flex flex-col gap-6 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <p className="text-xs font-bold tracking-[0.14em] text-white/70 uppercase">{t('My stages')}</p>
            <h1 className="mt-2 text-3xl font-extrabold tracking-tight">
              {t('Level {{completed}} of {{total}}', { completed, total })}
            </h1>
            <p className="mt-2 max-w-md text-[15px] text-white/85">
              {t('One level unlocks for each customer you refer, up to {{total}}.', { total })}
            </p>
          </div>
          <ProgressRing value={completed} total={total} label={t('levels')} />
        </div>
      </section>

      <div className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
        {Array.from({ length: total }, (_, index) => {
          const level = index + 1;
          const unlocked = level <= completed;
          const referral = children[index];
          return (
            <div
              key={level}
              className={
                'relative overflow-hidden rounded-3xl p-5 transition-transform hover:-translate-y-0.5 ' +
                (unlocked
                  ? 'bg-linear-to-br from-white to-[#e6f6f3] shadow-[0_10px_30px_-14px_rgba(11,122,110,0.45)] ring-1 ring-brand/15'
                  : 'border border-dashed border-rulestrong bg-white/60')
              }
            >
              <div className="flex items-center justify-between">
                <span
                  className={
                    'flex h-12 w-12 items-center justify-center rounded-2xl text-lg font-extrabold ' +
                    (unlocked
                      ? 'bg-linear-to-br from-brandbright to-brand text-white shadow-sm'
                      : 'bg-panel2 text-ink3')
                  }
                >
                  {unlocked ? <Icon name="check" className="h-6 w-6" /> : level}
                </span>
                <span
                  className={
                    'rounded-full px-2.5 py-1 text-[11px] font-bold ' +
                    (unlocked ? 'bg-oksoft text-ok' : 'bg-panel2 text-ink3')
                  }
                >
                  {unlocked ? t('Unlocked') : t('Locked')}
                </span>
              </div>
              <p className="mt-4 text-base font-bold text-ink">{t('Level {{level}}', { level })}</p>
              {unlocked && referral ? (
                <div className="mt-2 flex items-center gap-2.5">
                  <PersonAvatar name={referral.fullName} size="sm" />
                  <p className="min-w-0 truncate text-sm text-ink2">
                    {referral.fullName}{' '}
                    <span className="font-mono text-xs text-brand">{referral.businessId}</span>
                  </p>
                </div>
              ) : (
                <p className="mt-2 text-sm text-ink3">
                  {unlocked ? t('Unlocked') : t('Refer one more customer to unlock')}
                </p>
              )}
            </div>
          );
        })}

        <div
          className={
            'relative overflow-hidden rounded-3xl p-5 ' +
            (stages?.bonusStageEligible
              ? 'bg-linear-to-br from-[#fff6dd] to-[#ffe2a8] shadow-[0_10px_30px_-14px_rgba(224,154,26,0.6)]'
              : 'border border-dashed border-rulestrong bg-white/60')
          }
        >
          <span
            className={
              'flex h-12 w-12 items-center justify-center rounded-2xl ' +
              (stages?.bonusStageEligible
                ? 'bg-linear-to-br from-[#f6c453] to-[#e09a1a] text-white shadow-sm'
                : 'bg-panel2 text-ink3')
            }
          >
            <Icon name="star" className="h-6 w-6" />
          </span>
          <p className="mt-4 text-base font-bold text-ink">{t('Bonus stage')}</p>
          <p className="mt-2 text-sm text-ink2">
            {stages?.bonusStageEligible
              ? t('You are eligible. The office will be in touch about what it involves.')
              : t('Unlocks when all five levels are complete.')}
          </p>
        </div>
      </div>

      {/* Said plainly rather than implied. The mechanic exists and is tracked; what a level is
          worth has not been decided, and inventing a figure here would be a promise the system
          cannot keep. */}
      <p className="mt-6 rounded-2xl bg-white/70 p-4 text-sm text-ink2 ring-1 ring-white">
        {t('Levels record how many customers you have referred. What each level entitles you to is set by the office and is not shown here.')}
      </p>
    </>
  );
}

// ================================================================== referrals

export function PortalReferrals() {
  const { t } = useTranslation();
  const me = usePortalMe();
  const referrals = usePortalReferrals(me.data?.access === 'ACTIVE');

  const children = referrals.data ?? me.data?.children ?? [];
  // One place per stage. This used to be a literal 4 while the rule was five, so a customer with
  // five referrals saw a diagram with no room for the fifth.
  const capacity = REFERRAL_STAGES;

  return (
    <>
      <div className="mb-6">
        <p className="text-xs font-bold tracking-[0.14em] text-brand uppercase">{t('Network')}</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight text-ink">{t('My referrals')}</h1>
        <p className="mt-1.5 text-sm text-ink3">
          {t('Who referred you, and who you have referred. You can see one level in each direction.')}
        </p>
      </div>

      <GlassCard>
        {referrals.isLoading ? (
          <Spinner />
        ) : referrals.error ? (
          <ErrorBanner error={referrals.error} />
        ) : (
          <ReferralDiagram
            parent={me.data?.parent ?? null}
            self={{ businessId: me.data?.businessId ?? '', fullName: me.data?.fullName ?? '' }}
            children={children}
            capacity={capacity}
          />
        )}
      </GlassCard>

      <section className="mt-8">
        <SectionTitle
          eyebrow={t('{{count}} of {{total}} places filled', { count: children.length, total: capacity })}
          title={t('Your direct referrals')}
        />
        {children.length === 0 ? (
          <GlassCard className="text-center">
            <span className="mx-auto flex h-14 w-14 items-center justify-center rounded-2xl bg-brandsoft text-brand">
              <Icon name="userPlus" className="h-6 w-6" />
            </span>
            <p className="mt-3 font-semibold text-ink">{t('Nobody yet.')}</p>
            <p className="mt-1 text-sm text-ink3">
              {t('Share your Business ID — each person who registers with it unlocks a level.')}
            </p>
          </GlassCard>
        ) : (
          <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {children.map((child, index) => (
              <div
                key={child.id}
                className="flex items-center gap-4 rounded-3xl border border-white/70 bg-white/85 p-5 shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)] transition-transform hover:-translate-y-0.5"
              >
                <PersonAvatar name={child.fullName} size="lg" />
                <div className="min-w-0 flex-1">
                  <p className="truncate font-bold text-ink">{child.fullName}</p>
                  <p className="font-mono text-sm font-semibold text-brand">ID {child.businessId}</p>
                  <p className="mt-1 text-xs text-ink3">
                    {t('Joined {{date}}', { date: dateOf(child.approvedAt) })} ·{' '}
                    {/* A count, not a link. How their downline is doing is their business. */}
                    {t('{{count}} referrals', { count: child.directChildCount ?? 0 })}
                  </p>
                </div>
                <span className="flex h-8 w-8 flex-none items-center justify-center rounded-full bg-oksoft text-xs font-bold text-ok">
                  {index + 1}
                </span>
              </div>
            ))}
          </div>
        )}
      </section>
    </>
  );
}

/**
 * Three rows: who referred you, you, and who you referred.
 *
 * Drawn rather than listed because the shape is the information — a distributor wants to see how
 * many of their places are filled, and a row of faces with visible gaps says that instantly where
 * a table does not. Empty places are drawn as dashed outlines for the same reason.
 */
function ReferralDiagram({
  parent,
  self,
  children,
  capacity,
}: {
  parent: DistributorNode | null;
  self: { businessId: string; fullName: string };
  children: DistributorNode[];
  capacity: number;
}) {
  const { t } = useTranslation();
  const empty = Math.max(0, capacity - children.length);

  return (
    <div className="flex flex-col items-center py-2">
      {parent ? (
        <>
          <PersonNode label={t('Referred you')} businessId={parent.businessId ?? ''} name={parent.fullName ?? ''} tone="muted" />
          <Connector />
        </>
      ) : (
        <p className="mb-4 rounded-full bg-panel2 px-3 py-1 text-xs font-semibold text-ink3">
          {t('You are at the top of your own tree')}
        </p>
      )}

      <PersonNode label={t('You')} businessId={self.businessId} name={self.fullName} tone="self" />

      {(children.length > 0 || empty > 0) && <Connector />}

      <div className="flex flex-wrap justify-center gap-3">
        {children.map((child) => (
          <PersonNode key={child.id} businessId={child.businessId ?? ''} name={child.fullName ?? ''} tone="child" />
        ))}
        {Array.from({ length: empty }, (_, index) => (
          <div
            key={`empty-${index}`}
            className="flex w-[150px] flex-col items-center justify-center gap-2 rounded-2xl border-2 border-dashed border-rulestrong bg-white/50 px-3 py-4 text-center"
          >
            <span className="flex h-10 w-10 items-center justify-center rounded-full bg-panel2 text-ink3">
              <Icon name="userPlus" className="h-4 w-4" />
            </span>
            <p className="text-xs font-semibold text-ink3">
              {t('Place {{n}} open', { n: children.length + index + 1 })}
            </p>
          </div>
        ))}
      </div>
    </div>
  );
}

function Connector() {
  return <span aria-hidden className="my-2 h-7 w-0.5 rounded-full bg-linear-to-b from-brand/40 to-brand/10" />;
}

function PersonNode({
  label,
  businessId,
  name,
  tone,
}: {
  label?: string;
  businessId: string;
  name: string;
  tone: 'muted' | 'self' | 'child';
}) {
  if (tone === 'self') {
    return (
      <div className="flex w-[210px] flex-col items-center rounded-3xl bg-linear-to-br from-[#0b7a6e] to-[#3f5bd8] px-4 py-4 text-center text-white shadow-[0_14px_30px_-14px_rgba(11,122,110,0.8)]">
        {label && <p className="text-[10px] font-bold tracking-[0.16em] text-white/70 uppercase">{label}</p>}
        <PersonAvatar name={name} size="lg" className="mt-2 ring-4 ring-white/30" />
        <p className="mt-2 w-full truncate font-bold">{name}</p>
        <p className="font-mono text-sm font-semibold text-white/85">ID {businessId || '—'}</p>
      </div>
    );
  }
  return (
    <div
      className={
        'flex w-[150px] flex-col items-center rounded-2xl px-3 py-4 text-center ' +
        (tone === 'muted' ? 'bg-panel2' : 'bg-white shadow-[0_8px_20px_-12px_rgba(16,24,40,0.3)] ring-1 ring-rule')
      }
    >
      {label && <p className="text-[10px] font-bold tracking-[0.14em] text-ink3 uppercase">{label}</p>}
      <PersonAvatar name={name} className="mt-1.5" />
      <p className="mt-2 w-full truncate text-sm font-semibold text-ink">{name}</p>
      <p className="font-mono text-xs font-semibold text-brand">{businessId || '—'}</p>
    </div>
  );
}
