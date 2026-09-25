import { createContext, useContext, useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import {
  announcementImageUrl,
  CATEGORY_META,
  CTA_TARGETS,
  recordEngagement,
  type Announcement,
  type CtaTarget,
} from '../api/announcements';
import { Icon } from './icons';
import { RichText } from './RichText';
import { Modal } from './ui';

/**
 * How announcements look to customers — as marketing, not as notices.
 *
 * Shared with the office's editor, so its preview is these exact components rather than an
 * approximation that drifts. Every piece records a "view" when it is actually on screen and a
 * "click" when its button is pressed; the server counts each person once.
 */

/**
 * Which button destinations make sense for whoever is looking. A "Register your business" button
 * shown to somebody already registered is noise, and a link to referrals for somebody who cannot
 * open them is a dead end — so the portal says which pages its reader can use, and a button for
 * any other page is simply not drawn. Everything is allowed by default (the office's preview).
 */
export const CtaAudienceContext = createContext<(target: CtaTarget) => boolean>(() => true);

/** Records a view once the element is at least half visible. */
function useViewTracking(id: string, enabled = true) {
  const ref = useRef<HTMLElement | null>(null);
  useEffect(() => {
    const element = ref.current;
    if (!element || !enabled || id === 'preview') return;
    const observer = new IntersectionObserver(
      (entries) => {
        if (entries.some((entry) => entry.isIntersecting)) {
          recordEngagement(id, 'view');
          observer.disconnect();
        }
      },
      { threshold: 0.5 },
    );
    observer.observe(element);
    return () => observer.disconnect();
  }, [id, enabled]);
  return ref;
}

/** The button: records the click, then opens the portal page it names. */
function CtaButton({
  announcement,
  variant,
}: {
  announcement: Announcement;
  variant: 'light' | 'solid';
}) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const canOpen = useContext(CtaAudienceContext);
  if (!announcement.ctaLabel || !announcement.ctaTarget || !canOpen(announcement.ctaTarget)) return null;
  const target = CTA_TARGETS[announcement.ctaTarget];
  return (
    <button
      type="button"
      onClick={(event) => {
        event.stopPropagation();
        if (announcement.id === 'preview') return;
        recordEngagement(announcement.id, 'click');
        navigate(target.path);
      }}
      className={
        'inline-flex h-11 items-center gap-2 rounded-full px-5 text-sm font-bold shadow-sm transition-transform hover:-translate-y-0.5 ' +
        (variant === 'light'
          ? 'bg-white text-ink'
          : `bg-linear-to-r text-white ${CATEGORY_META[announcement.category].gradient}`)
      }
    >
      {t(announcement.ctaLabel)}
      <Icon name="arrowRight" className="h-4 w-4" />
    </button>
  );
}

export function CategoryChip({
  category,
  onDark,
}: {
  category: Announcement['category'];
  onDark?: boolean;
}) {
  const { t } = useTranslation();
  const meta = CATEGORY_META[category];
  return (
    <span
      className={
        'inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-[11px] font-bold tracking-wide uppercase ' +
        (onDark ? 'bg-white/20 text-white ring-1 ring-white/30 backdrop-blur' : meta.chip)
      }
    >
      <Icon name={meta.icon} className="h-3.5 w-3.5" />
      {t(meta.label)}
    </span>
  );
}

// ================================================================== the banner

/**
 * One featured post as a full-width banner: the picture behind, the words over a gradient, and the
 * button. Without a picture it is a bold gradient in the category's colours, so a text-only offer
 * still looks like an advertisement.
 */
export function PromoBanner({
  announcement,
  onOpen,
  compact,
}: {
  announcement: Announcement;
  onOpen?: () => void;
  compact?: boolean;
}) {
  const { t } = useTranslation();
  const ref = useViewTracking(announcement.id);
  const meta = CATEGORY_META[announcement.category];

  return (
    <article
      ref={ref as React.RefObject<HTMLElement>}
      onClick={onOpen}
      className={
        'group relative isolate flex overflow-hidden rounded-3xl text-white shadow-[0_24px_48px_-24px_rgba(16,24,40,0.55)] ' +
        (onOpen ? 'cursor-pointer ' : '') +
        (compact ? 'min-h-[200px]' : 'min-h-[260px] sm:min-h-[300px]')
      }
    >
      {announcement.imageId ? (
        <>
          <img
            src={announcementImageUrl(announcement.imageId)}
            alt=""
            className="absolute inset-0 -z-20 h-full w-full object-cover transition-transform duration-700 group-hover:scale-[1.04]"
          />
          <div className="absolute inset-0 -z-10 bg-linear-to-r from-black/75 via-black/45 to-black/5" />
        </>
      ) : (
        <div className={`absolute inset-0 -z-10 bg-linear-to-br ${meta.gradient}`}>
          <div className="absolute -top-20 -right-16 h-72 w-72 rounded-full bg-white/15" />
          <div className="absolute -bottom-24 left-1/3 h-72 w-72 rounded-full bg-white/10 blur-2xl" />
          <Icon name={meta.icon} className="absolute right-10 bottom-8 h-28 w-28 text-white/15 sm:h-40 sm:w-40" />
        </div>
      )}

      <div className={'flex max-w-2xl flex-col justify-end ' + (compact ? 'p-6' : 'p-7 sm:p-10')}>
        <div className="flex flex-wrap items-center gap-2">
          <CategoryChip category={announcement.category} onDark />
          {announcement.seen === false && (
            <span className="rounded-full bg-[#facc15] px-2.5 py-0.5 text-[11px] font-extrabold text-ink uppercase">
              {t('New')}
            </span>
          )}
        </div>
        <h2
          className={
            'mt-3 leading-[1.1] font-extrabold tracking-tight drop-shadow-sm ' +
            (compact ? 'text-2xl' : 'text-3xl sm:text-[40px]')
          }
        >
          {announcement.title}
        </h2>
        {announcement.subtitle && (
          <p className={'mt-2 text-white/90 ' + (compact ? 'text-sm' : 'text-base sm:text-lg')}>
            {announcement.subtitle}
          </p>
        )}
        <div className="mt-5 flex flex-wrap items-center gap-3">
          <CtaButton announcement={announcement} variant="light" />
          {onOpen && (
            <span className="text-sm font-semibold text-white/85 underline-offset-4 group-hover:underline">
              {t('Read more')}
            </span>
          )}
        </div>
      </div>
    </article>
  );
}

/**
 * The featured posts, one at a time: moves on by itself every six seconds, pauses while the pointer
 * is over it, and has dots and arrows for doing it by hand.
 */
export function PromoCarousel({
  items,
  onOpen,
  compact,
}: {
  items: Announcement[];
  onOpen?: (announcement: Announcement) => void;
  compact?: boolean;
}) {
  const { t } = useTranslation();
  const [index, setIndex] = useState(0);
  const [paused, setPaused] = useState(false);
  const count = items.length;

  useEffect(() => {
    if (count < 2 || paused) return;
    const timer = window.setInterval(() => setIndex((current) => (current + 1) % count), 6000);
    return () => window.clearInterval(timer);
  }, [count, paused]);

  if (count === 0) return null;
  const current = items[Math.min(index, count - 1)]!;

  return (
    <section
      aria-roledescription="carousel"
      aria-label={t('Featured offers')}
      className="relative"
      onMouseEnter={() => setPaused(true)}
      onMouseLeave={() => setPaused(false)}
    >
      {/* Keyed so each slide mounts fresh: the view is recorded for the slide actually shown. */}
      <div key={current.id} className="animate-[promo-in_500ms_ease-out]">
        <PromoBanner announcement={current} compact={compact} onOpen={onOpen ? () => onOpen(current) : undefined} />
      </div>
      <style>{`@keyframes promo-in { from { opacity: 0; transform: translateY(6px); } to { opacity: 1; transform: none; } }`}</style>

      {count > 1 && (
        <>
          <div className="absolute right-5 bottom-5 flex items-center gap-1.5">
            {items.map((item, dot) => (
              <button
                key={item.id}
                type="button"
                aria-label={t('Show offer {{n}}', { n: dot + 1 })}
                aria-current={dot === index}
                onClick={() => setIndex(dot)}
                className={
                  'h-2 rounded-full transition-all ' + (dot === index ? 'w-7 bg-white' : 'w-2 bg-white/50 hover:bg-white/80')
                }
              />
            ))}
          </div>
          <div className="absolute top-5 right-5 flex gap-2">
            {(['prev', 'next'] as const).map((direction) => (
              <button
                key={direction}
                type="button"
                aria-label={direction === 'prev' ? t('Previous offer') : t('Next offer')}
                onClick={() => setIndex((current) => (current + (direction === 'prev' ? count - 1 : 1)) % count)}
                className="flex h-9 w-9 items-center justify-center rounded-full bg-white/20 text-white ring-1 ring-white/30 backdrop-blur transition-colors hover:bg-white/35"
              >
                <Icon name="arrowRight" className={'h-4 w-4 ' + (direction === 'prev' ? 'rotate-180' : '')} />
              </button>
            ))}
          </div>
        </>
      )}
    </section>
  );
}

// ================================================================== the card

/** One post in a grid: picture, chip, headline, the button, and a "New" badge until seen. */
export function OfferCard({
  announcement,
  onOpen,
}: {
  announcement: Announcement;
  onOpen?: () => void;
}) {
  const { t } = useTranslation();
  const ref = useViewTracking(announcement.id);
  const meta = CATEGORY_META[announcement.category];
  const published = announcement.publishedAt
    ? new Date(announcement.publishedAt).toLocaleDateString(undefined, { dateStyle: 'medium' })
    : '';
  const endsSoon =
    announcement.expiresAt &&
    new Date(announcement.expiresAt).getTime() - Date.now() < 7 * 24 * 3600 * 1000;

  return (
    <article
      ref={ref as React.RefObject<HTMLElement>}
      onClick={onOpen}
      className={
        'group flex flex-col overflow-hidden rounded-3xl border border-white/70 bg-white/90 shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)] transition-all duration-200 ' +
        (onOpen ? 'cursor-pointer hover:-translate-y-1 hover:shadow-[0_18px_36px_-16px_rgba(16,24,40,0.3)]' : '')
      }
    >
      <div className="relative h-44 overflow-hidden">
        {announcement.imageId ? (
          <img
            src={announcementImageUrl(announcement.imageId)}
            alt=""
            className="h-full w-full object-cover transition-transform duration-500 group-hover:scale-[1.05]"
          />
        ) : (
          <div className={`flex h-full items-center justify-center bg-linear-to-br ${meta.gradient}`}>
            <Icon name={meta.icon} className="h-14 w-14 text-white/80" />
          </div>
        )}
        <div className="absolute top-3 left-3 flex gap-1.5">
          <CategoryChip category={announcement.category} />
        </div>
        {announcement.seen === false && (
          <span className="absolute top-3 right-3 rounded-full bg-[#facc15] px-2.5 py-0.5 text-[11px] font-extrabold text-ink uppercase shadow-sm">
            {t('New')}
          </span>
        )}
      </div>
      <div className="flex flex-1 flex-col p-5">
        <h3 className="text-lg leading-snug font-bold tracking-tight text-ink">{announcement.title}</h3>
        {announcement.subtitle && <p className="mt-1 line-clamp-2 text-sm text-ink2">{announcement.subtitle}</p>}
        <div className="mt-auto flex flex-wrap items-center justify-between gap-3 pt-4">
          <p className="text-xs text-ink3">
            {published}
            {endsSoon && (
              <span className="ml-2 rounded-full bg-dangersoft px-2 py-0.5 font-semibold text-danger">
                {t('Ends soon')}
              </span>
            )}
          </p>
          {announcement.ctaLabel ? (
            <CtaButton announcement={announcement} variant="solid" />
          ) : (
            onOpen && <span className="text-sm font-semibold text-brand">{t('Read more')} →</span>
          )}
        </div>
      </div>
    </article>
  );
}

// ================================================================== the full post

export function AnnouncementDetail({
  announcement,
  onClose,
}: {
  announcement: Announcement;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const meta = CATEGORY_META[announcement.category];
  return (
    <Modal title={t(meta.label)} onClose={onClose} wide>
      <div className="-m-6">
        {announcement.imageId ? (
          <img src={announcementImageUrl(announcement.imageId)} alt="" className="max-h-80 w-full object-cover" />
        ) : (
          <div className={`h-28 bg-linear-to-br ${meta.gradient}`} />
        )}
        <div className="p-6">
          <CategoryChip category={announcement.category} />
          <h2 className="mt-3 text-2xl font-extrabold tracking-tight text-ink">{announcement.title}</h2>
          {announcement.subtitle && <p className="mt-1 text-base font-medium text-brand">{announcement.subtitle}</p>}
          <div className="mt-4">
            <RichText body={announcement.body} />
          </div>
          {(announcement.ctaLabel || announcement.expiresAt) && (
            <div className="mt-6 flex flex-wrap items-center justify-between gap-3 rounded-2xl bg-panel2 p-4">
              <p className="text-sm text-ink2">
                {announcement.expiresAt
                  ? t('Available until {{date}}', {
                      date: new Date(announcement.expiresAt).toLocaleDateString(undefined, { dateStyle: 'medium' }),
                    })
                  : ''}
              </p>
              <CtaButton announcement={announcement} variant="solid" />
            </div>
          )}
        </div>
      </div>
    </Modal>
  );
}
