import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import {
  announcementImageUrl,
  announcementState,
  uploadAnnouncementImage,
  useAllAnnouncements,
  useCreateAnnouncement,
  useDeleteAnnouncement,
  usePublishAnnouncement,
  useUpdateAnnouncement,
  useWithdrawAnnouncement,
  type Announcement,
} from '../api/announcements';
import { useAuth } from '../auth/AuthContext';
import { Icon } from '../components/icons';
import { RichText } from '../components/RichText';
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
  Spinner,
} from '../components/ui';

/** An empty document, in the shape the editor and the renderer both expect. */
const EMPTY_BODY = JSON.stringify({ type: 'doc', content: [{ type: 'paragraph' }] });

/**
 * Writing and sending announcements.
 *
 * <p>Two states a notice can be in and one it drifts into: draft, live, and ended. Drafts exist
 * because an administrator writing a long notice over two sittings should not be broadcasting the
 * half-finished version in between — which is why saving and publishing are separate buttons, and
 * why saving never publishes. Publish sits on every card, enabled only while the notice is still a
 * draft: each announcement goes out once.
 */
export function AnnouncementsPage() {
  const { t } = useTranslation();
  const { hasRole } = useAuth();
  const announcements = useAllAnnouncements();

  const [editing, setEditing] = useState<Announcement | 'new' | null>(null);
  const [confirmingPublish, setConfirmingPublish] = useState<Announcement | null>(null);

  const publish = usePublishAnnouncement();
  const withdraw = useWithdrawAnnouncement();
  const remove = useDeleteAnnouncement();

  if (announcements.isLoading) return <Spinner />;

  return (
    <>
      <PageHeader
        title={t('Announcements')}
        description={t('What every customer sees when they open the portal.')}
        actions={
          <Button variant="primary" onClick={() => setEditing('new')}>
            {t('Write an announcement')}
          </Button>
        }
      />

      <ErrorBanner error={publish.error ?? withdraw.error ?? remove.error} />

      {(announcements.data ?? []).length === 0 ? (
        <Card>
          <p className="px-5 py-12 text-center text-sm text-ink3">
            {t('Nothing written yet. An announcement appears on every customer’s home page until you take it down.')}
          </p>
        </Card>
      ) : (
        <div className="flex flex-col gap-4">
          {(announcements.data ?? []).map((item) => {
            const state = announcementState(item);
            return (
              <Card key={item.id}>
                <div className="flex flex-wrap items-start gap-4 p-5">
                  {item.imageId && (
                    <img
                      src={announcementImageUrl(item.imageId)}
                      alt=""
                      className="h-20 w-32 flex-none rounded-lg border-2 border-rulestrong object-cover"
                    />
                  )}
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center gap-2">
                      <h2 className="text-base font-bold text-ink">{item.title}</h2>
                      <Badge
                        tone={state === 'live' ? 'ok' : state === 'draft' ? 'warn' : 'neutral'}
                      >
                        {state === 'live'
                          ? t('live')
                          : state === 'draft'
                            ? t('draft')
                            : t('ended')}
                      </Badge>
                    </div>
                    {item.subtitle && <p className="mt-0.5 text-sm text-ink2">{item.subtitle}</p>}
                    <p className="mt-1.5 text-xs text-ink3">
                      {t('By {{name}}', { name: item.authorName })} ·{' '}
                      {state === 'draft'
                        ? t('not sent')
                        : new Date(item.publishedAt!).toLocaleDateString()}
                    </p>
                  </div>

                  <div className="flex flex-none flex-wrap gap-2">
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
                          ? t('Send it to every customer')
                          : t('Already published — each announcement is sent once')
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
                      <Button
                        size="sm"
                        variant="danger"
                        onClick={() => remove.mutate(item.id)}
                      >
                        {t('Delete')}
                      </Button>
                    )}
                  </div>
                </div>
              </Card>
            );
          })}
        </div>
      )}

      {confirmingPublish && (
        <Modal title={t('Publish this announcement?')} onClose={() => setConfirmingPublish(null)}>
          <div className="flex flex-col gap-4">
            <p className="text-sm text-ink2">
              {t('“{{title}}” will appear on every customer’s home page, and each of them is notified.', {
                title: confirmingPublish.title,
              })}
            </p>
            <p className="text-xs text-ink3">
              {t('This cannot be undone and it is only sent once. You can still edit the wording afterwards, or take it down.')}
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

      {editing && (
        <AnnouncementEditor
          existing={editing === 'new' ? null : editing}
          onClose={() => setEditing(null)}
        />
      )}
    </>
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
  const [expiresAt, setExpiresAt] = useState(
    existing?.expiresAt ? existing.expiresAt.slice(0, 10) : '',
  );
  const [uploading, setUploading] = useState(false);
  const [uploadError, setUploadError] = useState<unknown>(null);

  const mutation = existing ? update : create;
  const state = existing ? announcementState(existing) : 'draft';

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
      // the notice should still be up on the 30th, not vanish as the 29th ends.
      expiresAt: expiresAt ? new Date(`${expiresAt}T23:59:59`).toISOString() : null,
    };
    if (existing) {
      update.mutate({ id: existing.id, ...draft }, { onSuccess: onClose });
    } else {
      create.mutate(draft, { onSuccess: onClose });
    }
  }

  return (
    <Modal
      title={existing ? t('Edit announcement') : t('Write an announcement')}
      onClose={onClose}
      wide
    >
      <div className="flex flex-col gap-4">
        {state === 'draft' ? (
          <Instructions title={t('This goes to every customer')}>
            {t('Saving keeps it as a draft. Nothing reaches anybody until you press Publish on the announcement, and each announcement is only ever sent once.')}
          </Instructions>
        ) : state === 'live' ? (
          <Instructions title={t('This announcement is live')}>
            {t('Saving does not send it again, but customers see your changes as soon as you save.')}
          </Instructions>
        ) : (
          <Instructions title={t('This announcement has been taken down')}>
            {t('You can correct the wording for the record. Saving will not put it back up.')}
          </Instructions>
        )}

        <Field label={t('Topic')} required>
          <Input
            required
            autoFocus
            maxLength={200}
            value={title}
            onChange={(event) => setTitle(event.target.value)}
            placeholder={t('New furniture range in stock')}
          />
        </Field>

        <Field label={t('Sub-topic')} hint={t('Optional — one line under the topic')}>
          <Input
            maxLength={300}
            value={subtitle}
            onChange={(event) => setSubtitle(event.target.value)}
          />
        </Field>

        <div>
          <p className="mb-1 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('Message')}
            <span aria-hidden className="ml-0.5 text-danger">
              *
            </span>
          </p>
          <RichTextEditor value={body} onChange={setBody} />
        </div>

        <Field label={t('Picture')} hint={t('Optional. JPEG or PNG, up to 10 MB.')}>
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
                <img
                  src={announcementImageUrl(imageId)}
                  alt=""
                  className="h-14 w-24 rounded border-2 border-rulestrong object-cover"
                />
                <Button size="sm" variant="danger" onClick={() => setImageId(null)}>
                  {t('Remove')}
                </Button>
              </div>
            )}
          </div>
        </Field>

        <Field
          label={t('Take down on')}
          hint={
            state === 'ended'
              ? t('Already taken down — the date is kept as a record.')
              : t('Optional. Leave blank to keep it up until you take it down yourself.')
          }
        >
          <Input
            type="date"
            // The server keeps a taken-down notice down whatever is sent; disabling the field says
            // so before anybody tries.
            disabled={state === 'ended'}
            value={expiresAt}
            onChange={(event) => setExpiresAt(event.target.value)}
          />
        </Field>

        <ErrorBanner error={uploadError ?? mutation.error} />

        {/* What the customer will see, from the same renderer the portal uses — so this is a
            preview rather than an approximation of one. */}
        <div>
          <p className="mb-1 text-xs font-semibold tracking-wide text-ink2 uppercase">
            {t('How it will look')}
          </p>
          <AnnouncementCard
            announcement={{
              id: 'preview',
              title: title || t('Your topic'),
              subtitle: subtitle || null,
              body,
              imageId,
              publishedAt: new Date().toISOString(),
              expiresAt: null,
              authorName: '',
              createdAt: new Date().toISOString(),
            }}
          />
        </div>

        <div className="flex justify-end gap-2">
          <Button variant="ghost" onClick={onClose}>
            {t('Cancel')}
          </Button>
          <Button
            variant="primary"
            onClick={save}
            disabled={mutation.isPending || !title.trim() || uploading}
          >
            {mutation.isPending ? t('Saving…') : existing ? t('Save changes') : t('Save as draft')}
          </Button>
        </div>
      </div>
    </Modal>
  );
}

/**
 * One announcement as a customer sees it.
 *
 * <p>Exported so the composer's preview and the portal render the identical component. A preview
 * that is a separate implementation is a preview that eventually lies.
 */
export function AnnouncementCard({ announcement }: { announcement: Announcement }) {
  const { t } = useTranslation();
  return (
    <article className="overflow-hidden rounded-3xl border border-white/70 bg-white/90 shadow-[0_10px_30px_-14px_rgba(16,24,40,0.2)]">
      {announcement.imageId && (
        <img
          src={announcementImageUrl(announcement.imageId)}
          alt=""
          // Fixed aspect, cover: a notice board where every card is a different height reads as
          // broken rather than as varied, and the author cannot be expected to crop.
          className="h-48 w-full object-cover"
        />
      )}
      <div className="p-6">
        <span className="mb-3 inline-flex items-center gap-1.5 rounded-full bg-brandsoft px-2.5 py-1 text-[11px] font-bold text-brand">
          <Icon name="megaphone" className="h-3.5 w-3.5" />
          {t('Announcement')}
        </span>
        <h2 className="text-lg font-bold tracking-tight text-ink">{announcement.title}</h2>
        {announcement.subtitle && (
          <p className="mt-1 text-sm font-medium text-brand">{announcement.subtitle}</p>
        )}
        <div className="mt-3">
          <RichText body={announcement.body} />
        </div>
      </div>
    </article>
  );
}
