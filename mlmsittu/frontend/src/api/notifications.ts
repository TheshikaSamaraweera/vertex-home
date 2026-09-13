import { useEffect, useRef } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from './client';

export type Notification = {
  id: string;
  kind: string;
  title: string;
  body: string | null;
  link: string | null;
  readAt: string | null;
  createdAt: string;
};

export type NotificationList = { data: Notification[]; unreadCount: number };

const KEY = ['notifications'] as const;

/**
 * The bell's contents and its badge.
 *
 * <p>Both come from one request because the UI always wants both, and two requests would let the
 * count and the list disagree for as long as it took the second to arrive.
 *
 * <p>`refetchInterval` is the safety net rather than the mechanism. The live stream below is what
 * makes a notification appear at once; this catches anything raised while the stream was
 * reconnecting, and covers a browser where EventSource never connected at all.
 */
export function useNotifications() {
  return useQuery({
    queryKey: KEY,
    queryFn: () => api.get<NotificationList>('/api/v1/notifications'),
    refetchInterval: 60_000,
    refetchOnWindowFocus: true,
  });
}

export function useMarkNotificationRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => api.post<NotificationList>(`/api/v1/notifications/${id}/read`),
    // The response is the new list, so use it rather than asking again.
    onSuccess: (fresh) => queryClient.setQueryData(KEY, fresh),
  });
}

export function useMarkAllNotificationsRead() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: () => api.post<NotificationList>('/api/v1/notifications/read-all'),
    onSuccess: (fresh) => queryClient.setQueryData(KEY, fresh),
  });
}

/**
 * Opens the live connection and refetches when something arrives.
 *
 * <p><b>The event is a nudge, not the data.</b> It carries enough for a toast, and then the list
 * is refetched from the database — so what the bell shows is always what the server has, and a
 * missed or duplicated event cannot leave the UI describing something that is not there.
 *
 * <p>`EventSource` reconnects on its own when the connection drops, with no loop to write here.
 * Nothing is lost in the gap: every notification is written before it is pushed, so the refetch
 * on reconnect picks up whatever was raised meanwhile.
 *
 * @param onArrive called for each live notification, for the toast
 */
export function useNotificationStream(onArrive: (notification: Notification) => void) {
  const queryClient = useQueryClient();

  // Held in a ref so the effect does not re-run — and therefore does not tear down and rebuild the
  // connection — every time the caller re-renders with a new closure.
  const handler = useRef(onArrive);
  handler.current = onArrive;

  useEffect(() => {
    if (typeof EventSource === 'undefined') return;

    const source = new EventSource('/api/v1/notifications/stream', { withCredentials: true });

    source.addEventListener('notification', (event) => {
      try {
        handler.current(JSON.parse((event as MessageEvent).data) as Notification);
      } catch {
        // A malformed event must not stop the refetch below, which is the part that matters.
      }
      void queryClient.invalidateQueries({ queryKey: KEY });
    });

    // No onerror handler that closes the connection. EventSource retries by itself, and closing it
    // here would turn a momentary blip — a deploy, a dropped wifi packet — into a tab that never
    // sees another notification until it is reloaded.

    return () => source.close();
  }, [queryClient]);
}
