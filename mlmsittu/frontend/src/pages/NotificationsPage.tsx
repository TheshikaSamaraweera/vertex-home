import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import {
  useMarkAllNotificationsRead,
  useMarkNotificationRead,
  useNotifications,
  type Notification,
} from '../api/notifications';
import { Button, Card, PageHeader, Spinner } from '../components/ui';

/**
 * Everything the bell has ever shown, and the history behind it.
 *
 * <p>One page for both applications. The list is scoped to the signed-in user server-side, so a
 * customer and an administrator open the same route and see their own.
 */
export function NotificationsPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const { data, isLoading } = useNotifications();
  const markRead = useMarkNotificationRead();
  const markAllRead = useMarkAllNotificationsRead();

  const [filter, setFilter] = useState<'all' | 'unread'>('all');

  if (isLoading) return <Spinner />;

  const all = data?.data ?? [];
  const shown = filter === 'unread' ? all.filter((n) => !n.readAt) : all;
  const unread = data?.unreadCount ?? 0;

  function open(notification: Notification) {
    if (!notification.readAt) markRead.mutate(notification.id);
    if (notification.link) navigate(notification.link);
  }

  return (
    <>
      <PageHeader
        title={t('Notifications')}
        description={t('Everything the system has told you, newest first.')}
        actions={
          unread > 0 && (
            <Button type="button" onClick={() => markAllRead.mutate()}>
              {t('Mark all as read')}
            </Button>
          )
        }
      />

      <div className="mb-4 flex gap-2">
        <FilterTab active={filter === 'all'} onClick={() => setFilter('all')}>
          {t('All')} ({all.length})
        </FilterTab>
        <FilterTab active={filter === 'unread'} onClick={() => setFilter('unread')}>
          {t('Unread')} ({unread})
        </FilterTab>
      </div>

      <Card>
        {shown.length === 0 ? (
          <p className="px-5 py-12 text-center text-sm text-ink3">
            {filter === 'unread' ? t('Nothing unread.') : t('Nothing yet.')}
          </p>
        ) : (
          <ul>
            {shown.map((notification) => (
              <li key={notification.id} className="border-b border-rule last:border-b-0">
                <button
                  type="button"
                  onClick={() => open(notification)}
                  disabled={!notification.link && Boolean(notification.readAt)}
                  className={
                    'flex w-full items-start gap-3 px-5 py-4 text-left ' +
                    (notification.link || !notification.readAt ? 'hover:bg-panel2 ' : '') +
                    (notification.readAt ? '' : 'bg-brandsoft')
                  }
                >
                  <span
                    aria-hidden
                    className={
                      'mt-1.5 h-2 w-2 flex-none rounded-full ' +
                      (notification.readAt ? 'bg-rule' : 'bg-brand')
                    }
                  />
                  <span className="min-w-0 flex-1">
                    <span className="block text-sm font-medium text-ink">{notification.title}</span>
                    {notification.body && (
                      <span className="mt-0.5 block text-sm text-ink2">{notification.body}</span>
                    )}
                  </span>
                  {/* The full timestamp here, not "4 minutes ago". On a history page the question
                      is when something happened, not whether it is new. */}
                  <span className="flex-none text-xs whitespace-nowrap text-ink3">
                    {new Date(notification.createdAt).toLocaleString()}
                  </span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Card>

      {all.length >= 50 && (
        <p className="mt-3 text-xs text-ink3">
          {t('Showing the 50 most recent. Older notifications are not kept on this screen — the audit log is the permanent record.')}
        </p>
      )}
    </>
  );
}

function FilterTab({
  active,
  onClick,
  children,
}: {
  active: boolean;
  onClick: () => void;
  children: React.ReactNode;
}) {
  return (
    <button
      type="button"
      onClick={onClick}
      className={
        'rounded-md border px-3 py-1.5 text-sm ' +
        (active
          ? 'border-brand bg-brandsoft font-semibold text-brand'
          : 'border-rule bg-panel text-ink2 hover:text-ink')
      }
    >
      {children}
    </button>
  );
}
