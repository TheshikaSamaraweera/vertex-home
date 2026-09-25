import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  CATEGORY_META,
  useLiveAnnouncements,
  type Announcement,
  type AnnouncementCategory,
} from '../api/announcements';
import { Icon } from '../components/icons';
import { AnnouncementDetail, OfferCard, PromoCarousel } from '../components/Promotions';
import { ErrorBanner, Spinner } from '../components/ui';
import { GlassCard, SectionTitle } from './portalUi';

/**
 * Offers and news, for customers.
 *
 * Open to every signed-in customer, registered or not — the server decides which posts each one
 * may see (a members-only offer never reaches somebody who has not been approved), so this page
 * can show whatever comes back.
 */
export function PortalOffers() {
  const { t } = useTranslation();
  const live = useLiveAnnouncements();
  const [category, setCategory] = useState<AnnouncementCategory | ''>('');
  const [open, setOpen] = useState<Announcement | null>(null);

  const all = live.data ?? [];
  const featured = all.filter((item) => item.featured);
  const shown = all.filter((item) => !category || item.category === category);
  const present = (Object.keys(CATEGORY_META) as AnnouncementCategory[]).filter((key) =>
    all.some((item) => item.category === key),
  );

  return (
    <>
      <div className="mb-6">
        <p className="text-xs font-bold tracking-[0.14em] text-brand uppercase">{t('From MLM Sittu')}</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight text-ink">{t('Offers & news')}</h1>
        <p className="mt-1.5 max-w-2xl text-sm text-ink3">
          {t('Special offers, new arrivals and everything happening at MLM Sittu.')}
        </p>
      </div>

      {live.isLoading ? (
        <Spinner />
      ) : live.error ? (
        <ErrorBanner error={live.error} onRetry={() => void live.refetch()} />
      ) : all.length === 0 ? (
        <GlassCard className="text-center">
          <span className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brandsoft text-brand">
            <Icon name="megaphone" className="h-7 w-7" />
          </span>
          <p className="font-semibold text-ink">{t('Nothing new right now.')}</p>
          <p className="mt-1 text-sm text-ink3">{t('Offers and news appear here as soon as they are announced.')}</p>
        </GlassCard>
      ) : (
        <>
          {featured.length > 0 && (
            <div className="mb-7">
              <PromoCarousel items={featured} onOpen={setOpen} />
            </div>
          )}

          {present.length > 1 && (
            <div className="mb-5 flex flex-wrap gap-2" role="tablist" aria-label={t('Filter by category')}>
              <FilterChip active={!category} onClick={() => setCategory('')} label={t('All')} count={all.length} />
              {present.map((key) => (
                <FilterChip
                  key={key}
                  active={category === key}
                  onClick={() => setCategory(key)}
                  label={t(CATEGORY_META[key].label)}
                  count={all.filter((item) => item.category === key).length}
                />
              ))}
            </div>
          )}

          <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
            {shown.map((item) => (
              <OfferCard key={item.id} announcement={item} onOpen={() => setOpen(item)} />
            ))}
          </div>
        </>
      )}

      {open && <AnnouncementDetail announcement={open} onClose={() => setOpen(null)} />}
    </>
  );
}

function FilterChip({
  active,
  onClick,
  label,
  count,
}: {
  active: boolean;
  onClick: () => void;
  label: string;
  count: number;
}) {
  return (
    <button
      type="button"
      role="tab"
      aria-selected={active}
      onClick={onClick}
      className={
        'inline-flex h-9 items-center gap-2 rounded-full px-4 text-sm font-semibold transition-all ' +
        (active
          ? 'bg-linear-to-r from-brandbright to-brand text-white shadow-[0_6px_14px_-6px_rgba(11,122,110,0.7)]'
          : 'bg-white/80 text-ink2 ring-1 ring-white hover:text-brand')
      }
    >
      {label}
      <span className={'rounded-full px-1.5 text-[11px] ' + (active ? 'bg-white/25' : 'bg-panel2 text-ink3')}>{count}</span>
    </button>
  );
}

/** The featured banner at the top of the dashboard. Renders nothing when nothing is featured. */
export function DashboardPromotions({ compact }: { compact?: boolean }) {
  const live = useLiveAnnouncements();
  const [open, setOpen] = useState<Announcement | null>(null);
  const featured = (live.data ?? []).filter((item) => item.featured);
  if (featured.length === 0) return null;
  return (
    <div className="mb-5">
      <PromoCarousel items={featured} onOpen={setOpen} compact={compact} />
      {open && <AnnouncementDetail announcement={open} onClose={() => setOpen(null)} />}
    </div>
  );
}

/**
 * The latest posts under the dashboard, three at most, with a way to the rest. Replaces the old
 * list of notices: a customer glances at the dashboard, and three good cards are read where ten
 * plain ones are scrolled past.
 */
export function OffersStrip() {
  const { t } = useTranslation();
  const live = useLiveAnnouncements();
  const [open, setOpen] = useState<Announcement | null>(null);
  const all = live.data ?? [];
  if (all.length === 0) return null;
  const latest = [...all]
    .sort((a, b) => (b.publishedAt ?? '').localeCompare(a.publishedAt ?? ''))
    .slice(0, 3);

  return (
    <section className="mt-8">
      <div className="flex items-end justify-between gap-3">
        <SectionTitle eyebrow={t('Offers & news')} title={t('What’s new at MLM Sittu')} />
        <Link to="/portal/offers" className="mb-4 text-sm font-semibold text-brand hover:underline">
          {t('See all ({{count}})', { count: all.length })} →
        </Link>
      </div>
      <div className="grid gap-5 sm:grid-cols-2 lg:grid-cols-3">
        {latest.map((item) => (
          <OfferCard key={item.id} announcement={item} onOpen={() => setOpen(item)} />
        ))}
      </div>
      {open && <AnnouncementDetail announcement={open} onClose={() => setOpen(null)} />}
    </section>
  );
}

/** How many posts this customer has not seen yet — the badge on the navigation. */
export function useUnseenOffers(): number {
  const live = useLiveAnnouncements();
  return (live.data ?? []).filter((item) => item.seen === false).length;
}
