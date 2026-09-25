import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import {
  portalPictureUrl,
  usePortalItemPacks,
  usePortalMe,
  type Pack,
  type PackItem,
} from '../api/portal';
import { Icon } from '../components/icons';
import { ErrorBanner, Modal, money, Spinner } from '../components/ui';
import { GlassCard } from './portalUi';

/**
 * The item packs the company offers, for customers.
 *
 * Open to every signed-in customer, registered or not: choosing a pack is part of registering, and
 * nobody can choose well from a list of names. Once their registration names a pack, that pack
 * stays open and the others are shown locked and faded — the server no longer sends what is in
 * them, so this page cannot reveal it either.
 */
export function PortalItemPacks() {
  const { t } = useTranslation();
  const me = usePortalMe();
  const catalogue = usePortalItemPacks();
  const [viewing, setViewing] = useState<Pack | null>(null);

  const packs = catalogue.data?.packs ?? [];
  const chosen = packs.find((pack) => pack.selected);
  // Only offer "register with this pack" to someone who can still submit a registration.
  const canChoose =
    me.data?.access === 'REGISTRATION_REQUIRED' || me.data?.access === 'CHANGES_REQUESTED';

  return (
    <>
      <div className="mb-6">
        <p className="text-xs font-bold tracking-[0.14em] text-brand uppercase">{t('Catalogue')}</p>
        <h1 className="mt-1 text-3xl font-extrabold tracking-tight text-ink">{t('Item packs')}</h1>
        <p className="mt-1.5 max-w-2xl text-sm text-ink3">
          {chosen
            ? t('Your registration chose {{name}}. Its full contents are below; the other packs are locked.', {
                name: chosen.name,
              })
            : t('Every pack the company offers, and exactly what is in each. Choose one when you register — it becomes yours once you complete every level.')}
        </p>
      </div>

      {chosen && (
        <section className="relative mb-6 flex flex-col gap-4 overflow-hidden rounded-3xl bg-linear-to-br from-[#0b7a6e] via-[#0f8d80] to-[#3f5bd8] p-6 text-white shadow-[0_20px_40px_-18px_rgba(11,122,110,0.65)] sm:flex-row sm:items-center">
          <div aria-hidden className="pointer-events-none absolute -top-16 -right-10 h-48 w-48 rounded-full bg-white/10" />
          <span className="relative flex h-14 w-14 flex-none items-center justify-center rounded-2xl bg-white/15 ring-1 ring-white/25">
            <Icon name="gift" className="h-7 w-7" />
          </span>
          <div className="relative min-w-0 flex-1">
            <p className="text-xs font-bold tracking-[0.14em] text-white/70 uppercase">{t('Your pack')}</p>
            <p className="text-xl font-extrabold tracking-tight">{chosen.name}</p>
            <p className="text-sm text-white/85">
              {t('{{count}} items · {{price}}', {
                count: (chosen.items ?? []).length,
                price: money(chosen.price),
              })}
            </p>
          </div>
          <button
            type="button"
            onClick={() => setViewing(chosen)}
            className="relative inline-flex h-10 items-center gap-2 self-start rounded-full bg-white px-4 text-sm font-bold text-brand shadow-sm transition-transform hover:-translate-y-0.5 sm:self-center"
          >
            {t('See what is inside')}
            <Icon name="arrowRight" className="h-4 w-4" />
          </button>
        </section>
      )}

      {catalogue.isLoading ? (
        <Spinner />
      ) : catalogue.error ? (
        <ErrorBanner error={catalogue.error} onRetry={() => void catalogue.refetch()} />
      ) : packs.length === 0 ? (
        <GlassCard className="text-center">
          <p className="font-semibold text-ink">{t('No packs are on offer at the moment.')}</p>
        </GlassCard>
      ) : (
        <div className="grid gap-5 sm:grid-cols-2 xl:grid-cols-3">
          {packs.map((pack) => (
            <PackCard key={pack.id} pack={pack} onOpen={() => setViewing(pack)} />
          ))}
        </div>
      )}

      {viewing && (
        <PackDetails pack={viewing} canChoose={canChoose} onClose={() => setViewing(null)} />
      )}
    </>
  );
}

function PackCard({ pack, onOpen }: { pack: Pack; onOpen: () => void }) {
  const { t } = useTranslation();
  const locked = Boolean(pack.locked);

  return (
    <article
      className={
        'group relative flex flex-col overflow-hidden rounded-3xl border bg-white/85 transition-all duration-200 ' +
        (pack.selected
          ? 'border-brand/40 shadow-[0_16px_36px_-16px_rgba(11,122,110,0.55)] ring-2 ring-brand/30'
          : locked
            ? 'border-white/70 opacity-55 grayscale-[70%]'
            : 'border-white/70 shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)] hover:-translate-y-1 hover:shadow-[0_18px_36px_-16px_rgba(16,24,40,0.3)]')
      }
    >
      <div className="relative h-44 overflow-hidden bg-linear-to-br from-brandsoft to-[#eef1ff]">
        {pack.imageId ? (
          <img
            src={portalPictureUrl(pack.imageId)}
            alt=""
            className="h-full w-full object-cover transition-transform duration-300 group-hover:scale-[1.03]"
          />
        ) : (
          <div className="flex h-full items-center justify-center text-brand/60">
            <Icon name="gift" className="h-12 w-12" />
          </div>
        )}
        {pack.selected && (
          <span className="absolute top-3 left-3 inline-flex items-center gap-1.5 rounded-full bg-linear-to-r from-brandbright to-brand px-3 py-1 text-xs font-bold text-white shadow-sm">
            <Icon name="check" className="h-3.5 w-3.5" />
            {t('Your pack')}
          </span>
        )}
        {locked && (
          <span className="absolute top-3 left-3 inline-flex items-center gap-1.5 rounded-full bg-ink/75 px-3 py-1 text-xs font-bold text-white backdrop-blur">
            <Icon name="lock" className="h-3.5 w-3.5" />
            {t('Locked')}
          </span>
        )}
      </div>

      <div className="flex flex-1 flex-col p-5">
        <p className="font-mono text-[11px] font-semibold text-ink3">{pack.code}</p>
        <h2 className="mt-0.5 text-lg font-bold tracking-tight text-ink">{pack.name}</h2>
        {pack.description && (
          <p className="mt-1.5 line-clamp-2 text-sm text-ink2">{pack.description}</p>
        )}
        <div className="mt-auto flex items-end justify-between gap-3 pt-5">
          <div>
            <p className="text-[11px] font-semibold tracking-wide text-ink3 uppercase">{t('Pack value')}</p>
            <p className="nums text-xl font-extrabold tracking-tight text-ink">{money(pack.price)}</p>
          </div>
          {locked ? (
            <span className="text-xs font-semibold text-ink3">{t('Not your pack')}</span>
          ) : (
            <button
              type="button"
              onClick={onOpen}
              className="inline-flex h-9 items-center gap-1.5 rounded-full bg-brandsoft px-4 text-sm font-bold text-brand transition-colors hover:bg-brand hover:text-white"
            >
              {t('{{count}} items', { count: (pack.items ?? []).length })}
              <Icon name="arrowRight" className="h-3.5 w-3.5" />
            </button>
          )}
        </div>
      </div>
    </article>
  );
}

function PackDetails({
  pack,
  canChoose,
  onClose,
}: {
  pack: Pack;
  canChoose: boolean;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const items = pack.items ?? [];

  return (
    <Modal title={pack.name ?? ''} onClose={onClose} wide>
      <div className="flex flex-col gap-5">
        <div className="flex flex-col gap-4 sm:flex-row">
          {pack.imageId && (
            <img
              src={portalPictureUrl(pack.imageId)}
              alt=""
              className="h-40 w-full flex-none rounded-2xl object-cover sm:w-56"
            />
          )}
          <div className="min-w-0">
            <p className="font-mono text-xs font-semibold text-ink3">{pack.code}</p>
            <p className="nums mt-1 text-2xl font-extrabold tracking-tight text-ink">{money(pack.price)}</p>
            {pack.description && <p className="mt-2 text-sm text-ink2">{pack.description}</p>}
            <p className="mt-3 inline-flex items-center gap-1.5 rounded-full bg-brandsoft px-3 py-1 text-xs font-bold text-brand">
              <Icon name="package" className="h-3.5 w-3.5" />
              {t('{{count}} items in this pack', { count: items.length })}
            </p>
          </div>
        </div>

        <ul className="grid gap-3 sm:grid-cols-2">
          {items.map((item) => (
            <PackItemRow key={item.itemId} item={item} />
          ))}
        </ul>

        {canChoose && !pack.selected && (
          <div className="flex flex-col items-start justify-between gap-3 rounded-2xl bg-panel2 p-4 sm:flex-row sm:items-center">
            <p className="text-sm text-ink2">
              {t('Want this one? Choose it on your registration — after that, the other packs lock.')}
            </p>
            <Link
              to={`/portal/registration?pack=${pack.id}`}
              className="inline-flex h-10 flex-none items-center gap-2 rounded-full bg-brand px-4 text-sm font-bold text-white shadow-sm hover:bg-branddeep"
            >
              {t('Register with this pack')}
              <Icon name="arrowRight" className="h-4 w-4" />
            </Link>
          </div>
        )}
      </div>
    </Modal>
  );
}

function PackItemRow({ item }: { item: PackItem }) {
  const { t } = useTranslation();
  return (
    <li className="flex gap-3.5 rounded-2xl border border-rule bg-white p-3">
      {item.imageId ? (
        <img
          src={portalPictureUrl(item.imageId)}
          alt=""
          className="h-20 w-20 flex-none rounded-xl object-cover"
        />
      ) : (
        <span className="flex h-20 w-20 flex-none items-center justify-center rounded-xl bg-panel2 text-ink3">
          <Icon name="package" className="h-7 w-7" />
        </span>
      )}
      <div className="min-w-0 flex-1">
        <div className="flex items-start justify-between gap-2">
          <p className="font-semibold text-ink">{item.name}</p>
          <span className="nums flex-none rounded-full bg-brandsoft px-2.5 py-0.5 text-xs font-bold text-brand">
            ×{item.quantity}
          </span>
        </div>
        <p className="font-mono text-[11px] text-ink3">{item.sku}</p>
        <p className="mt-1 text-xs text-ink2">{item.description || t('No description yet.')}</p>
      </div>
    </li>
  );
}
