import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  announcementImageUrl,
  announcementState,
  CATEGORY_META,
  CTA_TARGETS,
  uploadAnnouncementImage,
  useAllAnnouncements,
  useCreateAnnouncement,
  useDeleteAnnouncement,
  usePublishAnnouncement,
  useUpdateAnnouncement,
  useWithdrawAnnouncement,
  type Announcement,
  type AnnouncementAudience,
  type AnnouncementCategory,
  type CtaTarget,
} from '../api/announcements';
import { useAuth } from '../auth/AuthContext';
import { Icon, type IconName } from '../components/icons';
import { AnnouncementDetail, CategoryChip, OfferCard, PromoBanner } from '../components/Promotions';
import { RichTextEditor } from '../components/RichTextEditor';
import {
  Badge,
  Button,
  Card,
  ErrorBanner,
  Field,
  Input,
  Instructions,
  Modal,
  PageHeader,
  Select,
  Spinner,
} from '../components/ui';

/** An empty document, in the shape the editor and the renderer both expect. */
const EMPTY_BODY = JSON.stringify({ type: 'doc', content: [{ type: 'paragraph' }] });

type StateFilter = '' | 'live' | 'draft' | 'ended' | 'featured';

const percent = (part: number, whole: number) => (whole > 0 ? Math.round((part / whole) * 100) : 0);

/**
 * Announcements, as the business's marketing channel.
 *
 * <p>Three states a post can be in: draft, live and ended. Saving never publishes — an
 * administrator writing over two sittings should not broadcast the half-finished version — and each
 * post is sent once. On top of that, each post says how it is presented (a banner at the top of the
 * dashboard, or a card), who it is for (every customer, or approved members only), and what its
 * button opens; and the list shows how many people each one reached and how many pressed the button.
 */
export function AnnouncementsPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const announcements = useAllAnnouncements();

  const [editing, setEditing] = useState<Announcement | 'new' | null>(null);
  const [confirmingPublish, setConfirmingPublish] = useState<Announcement | null>(null);
  const [previewing, setPreviewing] = useState<Announcement | null>(null);
  const [filter, setFilter] = useState<StateFilter>('');
  const [search, setSearch] = useState('');

  const publish = usePublishAnnouncement();
  const withdraw = useWithdrawAnnouncement();
  const remove = useDeleteAnnouncement();

  if (announcements.isLoading) return <Spinner />;

  const all = announcements.data ?? [];
  const live = all.filter((item) => announcementState(item) === 'live');
  const reach = all.reduce((sum, item) => sum + (item.views ?? 0), 0);
  const clicks = all.reduce((sum, item) => sum + (item.clicks ?? 0), 0);
  const needle = search.trim().toLowerCase();
  const rows = all.filter((item) => {
    const state = announcementState(item);
    const matchesFilter =
      !filter || (filter === 'featured' ? item.featured && state === 'live' : state === filter);
    return matchesFilter && (!needle || `${item.title} ${item.subtitle ?? ''}`.toLowerCase().includes(needle));
  });

  const tiles: Array<{ key: StateFilter; label: string; value: string; icon: IconName; tile: string }> = [
    { key: 'live', label: t('Live now'), value: String(live.length), icon: 'megaphone', tile: 'from-[#38b2a3] to-[#0b7a6e]' },
    { key: 'featured', label: t('In the banner'), value: String(live.filter((item) => item.featured).length), icon: 'star', tile: 'from-[#f6b35c] to-[#e07a2b]' },
    { key: 'draft', label: t('Drafts'), value: String(all.filter((item) => announcementState(item) === 'draft').length), icon: 'clipboard', tile: 'from-[#7c8cff] to-[#4f5bd5]' },
    { key: '', label: t('People reached · clicks'), value: `${reach} · ${clicks}`, icon: 'users', tile: 'from-[#f472b6] to-[#c0266d]' },
  ];

  return (
    <>
      <PageHeader
        title={t('Announcements & offers')}
        description={t('Your marketing channel to customers: banners at the top of their dashboard, offer cards, and a button that takes them straight to what you are promoting.')}
        actions={
          <Button variant="primary" onClick={() => setEditing('new')}>
            <Icon name="megaphone" className="h-4 w-4" />
            {t('Create a post')}
          </Button>
        }
      />

      <div className="mb-5 grid grid-cols-2 gap-4 lg:grid-cols-4">
        {tiles.map((tile) => (
          <button
            key={tile.label}
            type="button"
            aria-pressed={filter === tile.key && tile.key !== ''}
            onClick={() => setFilter(filter === tile.key ? '' : tile.key)}
            className={
              'flex items-center gap-3 rounded-2xl border bg-panel p-4 text-left shadow-card transition-all hover:-translate-y-0.5 ' +
              (filter === tile.key && tile.key ? 'border-brand ring-2 ring-brand/25' : 'border-rule')
            }
          >
            <span className={`flex h-11 w-11 flex-none items-center justify-center rounded-xl bg-linear-to-br text-white shadow-sm ${tile.tile}`}>
              <Icon name={tile.icon} className="h-5 w-5" />
            </span>
            <span className="min-w-0">
              <span className="block text-xs font-medium text-ink3">{tile.label}</span>
              <span className="nums block text-2xl leading-tight font-extrabold text-ink">{tile.value}</span>
            </span>
          </button>
        ))}
      </div>

      <ErrorBanner error={publish.error ?? withdraw.error ?? remove.error} />

      <Card
        title={t('Posts')}
        subtitle={t('{{count}} shown', { count: rows.length })}
        actions={
          <>
            <Input
              className="w-56"
              type="search"
              aria-label={t('Search posts')}
              placeholder={t('Search by title…')}
              value={search}
              onChange={(event) => setSearch(event.target.value)}
            />
            <Select
              className="w-40"
              aria-label={t('State')}
              value={filter}
              onChange={(event) => setFilter(event.target.value as StateFilter)}
            >
              <option value="">{t('All posts')}</option>
              <option value="live">{t('Live')}</option>
              <option value="featured">{t('In the banner')}</option>
              <option value="draft">{t('Drafts')}</option>
              <option value="ended">{t('Ended')}</option>
            </Select>
          </>
        }
      >
        {rows.length === 0 ? (
          <div className="px-5 py-14 text-center">
            <span className="mx-auto mb-3 flex h-14 w-14 items-center justify-center rounded-2xl bg-brandsoft text-brand">
              <Icon name="megaphone" className="h-7 w-7" />
            </span>
            <p className="font-semibold text-ink">
              {all.length ? t('No posts match these filters.') : t('No posts yet.')}
            </p>
            {!all.length && (
              <p className="mt-1 text-sm text-ink3">
                {t('Create an offer or a piece of news. Featured posts appear as a banner at the top of every customer’s dashboard.')}
              </p>
            )}
          </div>
        ) : (
          <ul className="divide-y divide-rule">
            {rows.map((item) => {
              const state = announcementState(item);
              const views = item.views ?? 0;
              const clicked = item.clicks ?? 0;
              return (
                <li key={item.id} className="flex flex-col gap-4 p-5 lg:flex-row lg:items-center">
                  <button
                    type="button"
                    onClick={() => setPreviewing(item)}
                    className="relative h-24 w-full flex-none overflow-hidden rounded-xl lg:w-40"
                    title={t('Preview as a customer')}
                  >
                    {item.imageId ? (
                      <img src={announcementImageUrl(item.imageId)} alt="" className="h-full w-full object-cover" />
                    ) : (
                      <span className={`flex h-full w-full items-center justify-center bg-linear-to-br ${CATEGORY_META[item.category].gradient}`}>
                        <Icon name={CATEGORY_META[item.category].icon} className="h-8 w-8 text-white/85" />
                      </span>
                    )}
                    {item.featured && (
                      <span className="absolute top-1.5 left-1.5 inline-flex items-center gap-1 rounded-full bg-[#facc15] px-2 py-0.5 text-[10px] font-extrabold text-ink uppercase">
                        <Icon name="star" className="h-3 w-3" />
                        {t('Banner')}
                      </span>
                    )}
                  </button>

                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <CategoryChip category={item.category} />
                      <Badge tone={state === 'live' ? 'ok' : state === 'draft' ? 'warn' : 'neutral'}>
                        {state === 'live' ? t('live') : state === 'draft' ? t('draft') : t('ended')}
                      </Badge>
                      <span className="inline-flex items-center gap-1 text-xs text-ink3">
                        <Icon name="users" className="h-3.5 w-3.5" />
                        {item.audience === 'everyone' ? t('All customers') : t('Members only')}
                      </span>
                    </div>
                    <h2 className="mt-1.5 truncate text-base font-bold text-ink">{item.title}</h2>
                    {item.subtitle && <p className="truncate text-sm text-ink2">{item.subtitle}</p>}
                    <p className="mt-1 text-xs text-ink3">
                      {t('By {{name}}', { name: item.authorName })} ·{' '}
                      {state === 'draft' ? t('not sent') : new Date(item.publishedAt!).toLocaleDateString()}
                      {item.expiresAt && state !== 'draft' && ` → ${new Date(item.expiresAt).toLocaleDateString()}`}
                      {item.ctaTarget && (
                        <>
                          {' · '}
                          {t('Button “{{label}}” → {{page}}', {
                            label: item.ctaLabel,
                            page: t(CTA_TARGETS[item.ctaTarget].label),
                          })}
                        </>
                      )}
                    </p>
                  </div>

                  {/* Reach and response. Drafts have neither, so the space stays quiet. */}
                  <div className="flex flex-none gap-5 lg:w-60">
                    {state === 'draft' ? (
                      <p className="text-xs text-ink3">{t('Stats appear once it is published.')}</p>
                    ) : (
                      <>
                        <Stat label={t('Reached')} value={views} />
                        <Stat label={t('Clicked')} value={clicked} />
                        <div className="min-w-0 flex-1">
                          <p className="text-[11px] text-ink3">{t('Click rate')}</p>
                          <p className="nums text-lg font-extrabold text-ink">
                            {item.ctaTarget ? `${percent(clicked, views)}%` : '—'}
                          </p>
                          <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-panel2">
                            <div
                              className="h-full rounded-full bg-linear-to-r from-brandbright to-brand"
                              style={{ width: `${item.ctaTarget ? percent(clicked, views) : 0}%` }}
                            />
                          </div>
                        </div>
                      </>
                    )}
                  </div>

                  <div className="flex flex-none flex-wrap gap-2 lg:justify-end">
                    <Button size="sm" onClick={() => setEditing(item)}>
                      {t('Edit')}
                    </Button>
                    <Button
                      size="sm"
                      variant="primary"
                      onClick={() => setConfirmingPublish(item)}
                      disabled={state !== 'draft' || publish.isPending}
                      title={
                        state === 'draft'
                          ? t('Send it to customers')
                          : t('Already published — each post is sent once')
                      }
                    >
                      {state === 'draft' ? t('Publish') : t('Published')}
                    </Button>
                    {state === 'live' && (
                      <Button size="sm" onClick={() => withdraw.mutate(item.id)}>
                        {t('Take down')}
                      </Button>
                    )}
                    {hasRole('SUPER_ADMIN') && state !== 'live' && (
                      <Button size="sm" variant="danger" onClick={() => remove.mutate(item.id)}>
                        {t('Delete')}
                      </Button>
                    )}
                  </div>
                </li>
              );
            })}
          </ul>
        )}
      </Card>

      {confirmingPublish && (
        <Modal title={t('Publish this post?')} onClose={() => setConfirmingPublish(null)}>
          <div className="flex flex-col gap-4">
            <p className="text-sm text-ink2">
              {confirmingPublish.audience === 'everyone'
                ? t('“{{title}}” will be shown to every customer, registered or not, and each of them is notified.', {
                    title: confirmingPublish.title,
                  })
                : t('“{{title}}” will be shown to every approved member, and each of them is notified.', {
                    title: confirmingPublish.title,
                  })}
              {confirmingPublish.featured && ` ${t('It appears in the banner at the top of their dashboard.')}`}
            </p>
            <p className="text-xs text-ink3">
              {t('This cannot be undone and it is only sent once. You can still edit it afterwards, or take it down.')}
            </p>
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setConfirmingPublish(null)}>
                {t('Cancel')}
              </Button>
              <Button
                variant="primary"
                disabled={publish.isPending}
                onClick={() =>
                  publish.mutate(confirmingPublish.id, {
                    onSettled: () => setConfirmingPublish(null),
                  })
                }
              >
                {publish.isPending ? t('Publishing…') : t('Publish')}
              </Button>
            </div>
          </div>
        </Modal>
      )}

      {previewing && <AnnouncementDetail announcement={{ ...previewing, id: 'preview' }} onClose={() => setPreviewing(null)} />}

      {editing && (
        <AnnouncementEditor existing={editing === 'new' ? null : editing} onClose={() => setEditing(null)} />
      )}
    </>
  );
}

function Stat({ label, value }: { label: string; value: number }) {
  return (
    <div>
      <p className="text-[11px] text-ink3">{label}</p>
      <p className="nums text-lg font-extrabold text-ink">{value}</p>
    </div>
  );
}

function AnnouncementEditor({
  existing,
  onClose,
}: {
  existing: Announcement | null;
  onClose: () => void;
}) {
  const { t } = useTranslation();
  const create = useCreateAnnouncement();
  const update = useUpdateAnnouncement();

  const [title, setTitle] = useState(existing?.title ?? '');
  const [subtitle, setSubtitle] = useState(existing?.subtitle ?? '');
  const [body, setBody] = useState(existing?.body ?? EMPTY_BODY);
  const [imageId, setImageId] = useState<string | null>(existing?.imageId ?? null);
  const [expiresAt, setExpiresAt] = useState(existing?.expiresAt ? existing.expiresAt.slice(0, 10) : '');
  const [category, setCategory] = useState<AnnouncementCategory>(existing?.category ?? 'offer');
  const [featured, setFeatured] = useState(existing?.featured ?? false);
  const [audience, setAudience] = useState<AnnouncementAudience>(existing?.audience ?? 'everyone');
  const [ctaTarget, setCtaTarget] = useState<CtaTarget | ''>(existing?.ctaTarget ?? '');
  const [ctaLabel, setCtaLabel] = useState(existing?.ctaLabel ?? '');
  const [preview, setPreview] = useState<'banner' | 'card'>(existing?.featured ? 'banner' : 'card');
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<unknown>(null);

  const mutation = existing ? update : create;
  const state = existing ? announcementState(existing) : 'draft';
  const buttonIncomplete = Boolean(ctaTarget) !== Boolean(ctaLabel.trim());

  async function pickImage(file: File | undefined) {
    if (!file) return;
    setUploading(true);
    setUploadError(null);
    try {
      setImageId(await uploadAnnouncementImage(file));
    } catch (caught) {
      setUploadError(caught);
    } finally {
      setUploading(false);
    }
  }

  function save() {
    const draft = {
      title,
      subtitle: subtitle.trim() || undefined,
      body,
      imageId,
      // A date, read as the end of that day in the browser's zone. Somebody typing the 30th means
      // the post should still be up on the 30th, not vanish as the 29th ends.
      expiresAt: expiresAt ? new Date(`${expiresAt}T23:59:59`).toISOString() : null,
      category,
      featured,
      audience,
      ctaLabel: ctaTarget ? ctaLabel.trim() : null,
      ctaTarget: ctaTarget || null,
    };
    if (existing) {
      update.mutate({ id: existing.id, ...draft }, { onSuccess: onClose });
    } else {
      create.mutate(draft, { onSuccess: onClose });
    }
  }

  const draftForPreview: Announcement = {
    id: 'preview',
    title: title || t('Your headline'),
    subtitle: subtitle || null,
    body,
    imageId,
    publishedAt: new Date().toISOString(),
    expiresAt: expiresAt ? new Date(`${expiresAt}T23:59:59`).toISOString() : null,
    authorName: '',
    createdAt: new Date().toISOString(),
    category,
    featured,
    audience,
    ctaLabel: ctaTarget ? ctaLabel || t('Button text') : null,
    ctaTarget: ctaTarget || null,
  };

  return (
    <Modal title={existing ? t('Edit post') : t('Create a post')} onClose={onClose} wide>
      <div className="flex flex-col gap-5">
        {state === 'draft' ? (
          <Instructions title={t('Saved as a draft until you publish')}>
            {t('Nothing reaches anybody until you press Publish on the post, and each post is only ever sent once.')}
          </Instructions>
        ) : state === 'live' ? (
          <Instructions title={t('This post is live')}>
            {t('Saving does not send it again, but customers see your changes as soon as you save.')}
          </Instructions>
        ) : (
          <Instructions title={t('This post has been taken down')}>
            {t('You can correct it for the record. Saving will not put it back up.')}
          </Instructions>
        )}

        {/* ---- what kind of post */}
        <section>
          <SectionLabel>{t('Type of post')}</SectionLabel>
          <div className="grid grid-cols-2 gap-2 sm:grid-cols-4">
            {(Object.keys(CATEGORY_META) as AnnouncementCategory[]).map((key) => (
              <button
                key={key}
                type="button"
                aria-pressed={category === key}
                onClick={() => setCategory(key)}
                className={
                  'flex flex-col items-center gap-1.5 rounded-xl border-2 p-3 text-sm font-semibold transition-all ' +
                  (category === key ? 'border-brand bg-brandsoft text-brand' : 'border-rule text-ink2 hover:border-brand/40')
                }
              >
                <span className={`flex h-9 w-9 items-center justify-center rounded-lg bg-linear-to-br text-white ${CATEGORY_META[key].gradient}`}>
                  <Icon name={CATEGORY_META[key].icon} className="h-4 w-4" />
                </span>
                {t(CATEGORY_META[key].label)}
              </button>
            ))}
          </div>
        </section>

        {/* ---- the words and the picture */}
        <section className="flex flex-col gap-4">
          <SectionLabel>{t('Content')}</SectionLabel>
          <Field label={t('Headline')} required>
            <Input
              required
              autoFocus
              maxLength={200}
              value={title}
              onChange={(event) => setTitle(event.target.value)}
              placeholder={t('20% off the Living room starter pack')}
            />
          </Field>
          <Field label={t('Short line')} hint={t('Optional — one line under the headline. Shown on the banner and the card.')}>
            <Input
              maxLength={300}
              value={subtitle}
              onChange={(event) => setSubtitle(event.target.value)}
              placeholder={t('This month only, for every new registration')}
            />
          </Field>
          <div>
            <p className="mb-1 text-xs font-semibold tracking-wide text-ink2 uppercase">
              {t('Full message')}
              <span aria-hidden className="ml-0.5 text-danger">
                *
              </span>
            </p>
            <RichTextEditor value={body} onChange={setBody} />
          </div>
          <Field label={t('Picture')} hint={t('Wide pictures work best (about 1600 × 600). JPEG or PNG, up to 10 MB.')}>
            <div className="flex flex-wrap items-center gap-3">
              <input
                type="file"
                accept="image/jpeg,image/png"
                onChange={(event) => void pickImage(event.target.files?.[0])}
                className="w-full rounded border-2 border-rulestrong bg-panel px-3 py-2 text-sm text-ink2 file:mr-3 file:rounded file:border-0 file:bg-brandsoft file:px-3 file:py-1 file:text-brand"
              />
              {uploading && <span className="text-xs text-ink3">{t('Uploading…')}</span>}
              {imageId && !uploading && (
                <div className="flex items-center gap-2">
                  <img src={announcementImageUrl(imageId)} alt="" className="h-14 w-24 rounded border-2 border-rulestrong object-cover" />
                  <Button size="sm" variant="danger" onClick={() => setImageId(null)}>
                    {t('Remove')}
                  </Button>
                </div>
              )}
            </div>
          </Field>
        </section>

        {/* ---- how and to whom */}
        <section className="grid gap-4 sm:grid-cols-2">
          <div>
            <SectionLabel>{t('Where it shows')}</SectionLabel>
            <label
              className={
                'flex cursor-pointer items-start gap-3 rounded-xl border-2 p-3 transition-colors ' +
                (featured ? 'border-[#e07a2b] bg-[#fff8e8]' : 'border-rule')
              }
            >
              <input
                type="checkbox"
                checked={featured}
                onChange={(event) => {
                  setFeatured(event.target.checked);
                  setPreview(event.target.checked ? 'banner' : 'card');
                }}
                className="mt-1 h-4 w-4 accent-[#e07a2b]"
              />
              <span>
                <span className="block text-sm font-semibold text-ink">{t('Feature in the banner')}</span>
                <span className="block text-xs text-ink3">
                  {t('Large banner at the top of every customer’s dashboard. Keep it for your best offers — several featured posts rotate.')}
                </span>
              </span>
            </label>
          </div>
          <div>
            <SectionLabel>{t('Who sees it')}</SectionLabel>
            <div className="flex flex-col gap-2">
              {(
                [
                  ['everyone', t('All customers'), t('Including people who have not registered yet — good for offers that get them to join.')],
                  ['members', t('Members only'), t('Only customers whose business registration is approved.')],
                ] as const
              ).map(([key, label, hint]) => (
                <label
                  key={key}
                  className={
                    'flex cursor-pointer items-start gap-3 rounded-xl border-2 p-3 transition-colors ' +
                    (audience === key ? 'border-brand bg-brandsoft' : 'border-rule')
                  }
                >
                  <input
                    type="radio"
                    name="audience"
                    checked={audience === key}
                    onChange={() => setAudience(key)}
                    className="mt-1 h-4 w-4 accent-brand"
                  />
                  <span>
                    <span className="block text-sm font-semibold text-ink">{label}</span>
                    <span className="block text-xs text-ink3">{hint}</span>
                  </span>
                </label>
              ))}
            </div>
          </div>
        </section>

        {/* ---- the button */}
        <section>
          <SectionLabel>{t('Button')}</SectionLabel>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field label={t('Opens')} hint={t('A page of the customer portal. Links to other websites are not allowed.')}>
              <Select value={ctaTarget} onChange={(event) => setCtaTarget(event.target.value as CtaTarget | '')}>
                <option value="">{t('No button')}</option>
                {(Object.keys(CTA_TARGETS) as CtaTarget[]).map((key) => (
                  <option key={key} value={key}>
                    {t(CTA_TARGETS[key].label)}
                  </option>
                ))}
              </Select>
            </Field>
            <Field label={t('Button text')} hint={t('Short and active, e.g. “See the packs”')}>
              <Input
                maxLength={40}
                disabled={!ctaTarget}
                value={ctaLabel}
                onChange={(event) => setCtaLabel(event.target.value)}
                placeholder={ctaTarget ? t('See the packs') : ''}
              />
            </Field>
          </div>
        </section>

        <Field
          label={t('Take down on')}
          hint={
            state === 'ended'
              ? t('Already taken down — the date is kept as a record.')
              : t('Optional. Customers see “Ends soon” in the last week. Leave blank to keep it up until you take it down.')
          }
        >
          <Input
            type="date"
            // The server keeps a taken-down post down whatever is sent; disabling the field says
            // so before anybody tries.
            disabled={state === 'ended'}
            value={expiresAt}
            onChange={(event) => setExpiresAt(event.target.value)}
          />
        </Field>

        <ErrorBanner error={uploadError ?? mutation.error} />

        {/* What the customer will see, from the same components the portal uses — so this is a
            preview rather than an approximation of one. */}
        <section>
          <div className="mb-2 flex items-center justify-between gap-2">
            <SectionLabel>{t('How customers will see it')}</SectionLabel>
            <div className="flex rounded-lg bg-panel2 p-0.5">
              {(['banner', 'card'] as const).map((key) => (
                <button
                  key={key}
                  type="button"
                  onClick={() => setPreview(key)}
                  className={
                    'rounded-md px-3 py-1 text-xs font-semibold ' +
                    (preview === key ? 'bg-panel text-brand shadow-xs' : 'text-ink3')
                  }
                >
                  {key === 'banner' ? t('Banner') : t('Card')}
                </button>
              ))}
            </div>
          </div>
          <div className="rounded-2xl bg-linear-to-br from-[#eef7f5] to-[#eef1ff] p-4">
            {preview === 'banner' ? (
              <>
                <PromoBanner announcement={draftForPreview} />
                {!featured && (
                  <p className="mt-2 text-center text-xs text-ink3">
                    {t('Only shown as a banner when “Feature in the banner” is ticked.')}
                  </p>
                )}
              </>
            ) : (
              <div className="mx-auto max-w-sm">
                <OfferCard announcement={draftForPreview} />
              </div>
            )}
          </div>
        </section>

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            variant="primary"
            onClick={save}
            disabled={mutation.isPending || !title.trim() || uploading || buttonIncomplete}
            title={buttonIncomplete ? t('Give the button both a page and its text') : undefined}
          >
            {mutation.isPending ? t('Saving…') : existing ? t('Save changes') : t('Save as draft')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

function SectionLabel({ children }: { children: React.ReactNode }) {
  return <p className="mb-2 text-xs font-bold tracking-[0.12em] text-ink2 uppercase">{children}</p>;
}
