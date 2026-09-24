import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useNavigate } from 'react-router-dom';
import { Icon } from './icons';
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  useNotificationStream,
  type Notification,
} from '../api/notifications';
import { useToasts } from './Toasts';

/**
 * The bell, its badge, and the panel behind it.
 *
 * <p>Used by both shells unchanged. Staff and customers get different notifications but the same
 * mechanism, and every query is scoped to the caller server-side, so there is nothing here that
 * needs to know which application it is in.
 */
export function NotificationBell({ historyPath }: { historyPath: string }) {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { show } = useToasts();
  const { data } = useNotifications();
  const markRead = useMarkNotificationRead();
  const markAllRead = useMarkAllNotificationsRead();

  const [open, setOpen] = useState(false);
  const panelRef = useRef<HTMLDivElement>(null);

  // Live arrivals become toasts. The list itself is refetched by the hook, so this only decides
  // what is shown in the corner.
  useNotificationStream((notification) =>
    show({
      title: notification.title,
      body: notification.body,
      link: notification.link || undefined,
      tone: notification.kind.includes('REJECTED') ? 'danger' : 'ok',
    }),
  );

  // Click outside, and Escape. A panel that can only be closed by clicking the button again is a
  // panel people leave open by accident and then lose their click to.
  useEffect(() => {
    if (!open) return;
    const onPointerDown = (event: PointerEvent) => {
      if (!panelRef.current?.contains(event.target as Node)) setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') setOpen(false);
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const unread = data?.unreadCount ?? 0;
  const recent = (data?.data ?? []).slice(0, 8);

  function openNotification(notification: Notification) {
    if (!notification.readAt) markRead.mutate(notification.id);
    setOpen(false);
    if (notification.link) navigate(notification.link);
  }

  return (
    <div ref={panelRef} className="relative">
      <button
        type="button"
        onClick={() => setOpen(!open)}
        aria-label={
          unread > 0
            ? t('Notifications, {{count}} unread', { count: unread })
            : t('Notifications')
        }
        aria-expanded={open}
        className="relative flex h-10 w-10 items-center justify-center rounded-full border border-rulestrong bg-panel text-ink2 shadow-xs transition-colors hover:text-brand"
      >
        <span aria-hidden className="text-base">
          <Icon name="bell" className="h-[18px] w-[18px]" />
        </span>
        {unread > 0 && (
          <span
            aria-hidden
            className="absolute -top-1 -right-1 flex h-5 min-w-5 items-center justify-center rounded-full bg-danger px-1 text-[10px] font-bold text-white"
          >
            {/* Past a point the exact number stops being useful and starts being unreadable. */}
            {unread > 99 ? '99+' : unread}
          </span>
        )}
      </button>

      {open && (
        <div className="absolute right-0 z-40 mt-2 w-[min(24rem,calc(100vw-2rem))] overflow-hidden rounded-lg border-2 border-rulestrong bg-panel shadow-xl">
          <div className="flex items-center justify-between border-b border-rule px-4 py-2.5">
            <p className="text-sm font-semibold text-ink">{t('Notifications')}</p>
            {unread > 0 && (
              <button
                type="button"
                onClick={() => markAllRead.mutate()}
                className="text-xs text-brand underline"
              >
                {t('Mark all as read')}
              </button>
            )}
          </div>

          {recent.length === 0 ? (
            <p className="px-4 py-8 text-center text-sm text-ink3">{t('Nothing yet.')}</p>
          ) : (
            <ul className="max-h-[24rem] overflow-y-auto">
              {recent.map((notification) => (
                <li key={notification.id} className="border-b border-rule last:border-b-0">
                  <button
                    type="button"
                    onClick={() => openNotification(notification)}
                    className={
                      'flex w-full gap-3 px-4 py-3 text-left hover:bg-panel2 ' +
                      (notification.readAt ? '' : 'bg-brandsoft')
                    }
                  >
                    {/* An unread marker that is not only colour: a dot is visible to somebody who
                        cannot distinguish the background tint. */}
                    <span
                      aria-hidden
                      className={
                        'mt-1.5 h-2 w-2 flex-none rounded-full ' +
                        (notification.readAt ? 'bg-transparent' : 'bg-brand')
                      }
                    />
                    <span className="min-w-0 flex-1">
                      <span className="block text-sm font-medium text-ink">
                        {notification.title}
                      </span>
                      {notification.body && (
                        <span className="mt-0.5 block text-xs text-ink2">{notification.body}</span>
                      )}
                      <span className="mt-1 block text-[11px] text-ink3">
                        {relativeTime(notification.createdAt, t)}
                      </span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          )}

          <div className="border-t border-rule px-4 py-2.5 text-center">
            <Link
              to={historyPath}
              onClick={() => setOpen(false)}
              className="text-xs text-brand underline"
            >
              {t('See all notifications')}
            </Link>
          </div>
        </div>
      )}
    </div>
  );
}

/**
 * "4 minutes ago", not a timestamp.
 *
 * <p>For something that arrived while you were looking at the screen, the distance is the useful
 * part — nobody reads 14:32 and subtracts. The full timestamp is on the history page, where the
 * question is "when did this happen" rather than "is this new".
 */
export function relativeTime(
  iso: string,
  t: ReturnType<typeof useTranslation>['t'],
): string {
  const seconds = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (seconds < 60) return t('just now');
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return t('{{count}} min ago', { count: minutes });
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return t('{{count}} h ago', { count: hours });
  const days = Math.floor(hours / 24);
  if (days < 7) return t('{{count}} d ago', { count: days });
  return new Date(iso).toLocaleDateString();
}
