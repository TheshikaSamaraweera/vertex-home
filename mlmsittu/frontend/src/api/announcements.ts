import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';

export type Announcement = {
  id: string;
  title: string;
  subtitle: string | null;
  /** The typed-block document, as JSON text. Rendered by RichText — never as HTML. */
  body: string;
  imageId: string | null;
  publishedAt: string | null;
  expiresAt: string | null;
  authorName: string;
  createdAt: string;
};

/** Where an announcement picture is fetched from. No token: see the controller. */
export const announcementImageUrl = (imageId: string) =>
  `/api/v1/announcements/image/${imageId}`;

/**
 * Whether an announcement is a draft, live, or finished.
 *
 * <p>Derived rather than stored, because "live" is two dates compared to the clock and a column
 * would be a second answer that lags the first.
 */
export function announcementState(a: Announcement): 'draft' | 'live' | 'ended' {
  if (!a.publishedAt) return 'draft';
  if (a.expiresAt && new Date(a.expiresAt).getTime() <= Date.now()) return 'ended';
  return 'live';
}

const LIVE = ['announcements', 'live'] as const;
const ALL = ['announcements', 'all'] as const;

/** What a customer sees on the portal home page. */
export const useLiveAnnouncements = () =>
  useQuery({
    queryKey: LIVE,
    queryFn: () => api.get<PagedResponse<Announcement>>('/api/v1/announcements'),
    select: (page) => page.data,
  });

/** Everything, drafts included, for the administrator's list. */
export const useAllAnnouncements = () =>
  useQuery({
    queryKey: ALL,
    queryFn: () => api.get<PagedResponse<Announcement>>('/api/v1/admin/announcements'),
    select: (page) => page.data,
  });

export type AnnouncementDraft = {
  title: string;
  subtitle?: string;
  body: string;
  imageId?: string | null;
  expiresAt?: string | null;
};

function useAnnouncementMutation<TArgs>(fn: (args: TArgs) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    // Both lists, always. A publish moves a row from one to the other, and refreshing only the
    // one that was open leaves the other stale behind a tab nobody looked at yet.
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['announcements'] });
      void queryClient.invalidateQueries({ queryKey: ['notifications'] });
    },
  });
}

export const useCreateAnnouncement = () =>
  useAnnouncementMutation((draft: AnnouncementDraft) =>
    api.post<Announcement>('/api/v1/admin/announcements', draft),
  );

export const useUpdateAnnouncement = () =>
  useAnnouncementMutation(({ id, ...draft }: AnnouncementDraft & { id: string }) =>
    api.put<Announcement>(`/api/v1/admin/announcements/${id}`, draft),
  );

export const usePublishAnnouncement = () =>
  useAnnouncementMutation((id: string) =>
    api.post<Announcement>(`/api/v1/admin/announcements/${id}/publish`),
  );

export const useWithdrawAnnouncement = () =>
  useAnnouncementMutation((id: string) =>
    api.post<Announcement>(`/api/v1/admin/announcements/${id}/withdraw`),
  );

export const useDeleteAnnouncement = () =>
  useAnnouncementMutation((id: string) => api.del(`/api/v1/admin/announcements/${id}`));

/**
 * Uploads a picture and returns its id.
 *
 * <p>Not a hook: it is called from inside a form submit rather than from a component, and the id
 * it returns is held in the form's own state until the announcement is saved.
 */
export async function uploadAnnouncementImage(file: File): Promise<string> {
  const form = new FormData();
  form.append('file', file);

  // Raw fetch rather than the api helper, which sets a JSON content type — the browser has to set
  // its own multipart boundary here, and overriding it makes the request unparseable server-side.
  const response = await fetch('/api/v1/admin/announcements/image', {
    method: 'POST',
    credentials: 'include',
    body: form,
  });

  const payload = await response.json().catch(() => undefined);
  if (!response.ok) {
    throw Object.assign(new Error(payload?.detail ?? 'Upload failed'), {
      code: payload?.code ?? 'UPLOAD_FAILED',
    });
  }
  return payload.id as string;
}
