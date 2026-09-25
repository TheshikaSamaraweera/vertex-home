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
  category: AnnouncementCategory;
  /** Shown in the banner carousel at the top of the customer's dashboard. */
  featured: boolean;
  audience: AnnouncementAudience;
  ctaLabel: string | null;
  ctaTarget: CtaTarget | null;
  /** People reached, once each. Office view only. */
  views?: number | null;
  clicks?: number | null;
  /** Whether this customer has seen it. Customer view only. */
  seen?: boolean | null;
};

export type AnnouncementCategory = 'news' | 'offer' | 'new_arrival' | 'event';
export type AnnouncementAudience = 'everyone' | 'members';
/** A portal page by name. The server accepts nothing else — a button is never a URL. */
export type CtaTarget = 'item_packs' | 'registration' | 'referrals' | 'stages' | 'offers';

export const CATEGORY_META: Record<
  AnnouncementCategory,
  { label: string; chip: string; gradient: string; icon: 'megaphone' | 'gift' | 'sparkles' | 'calendar' }
> = {
  offer: {
    label: 'Offer',
    chip: 'bg-[#fff1e6] text-[#c2410c]',
    gradient: 'from-[#f97316] to-[#e11d48]',
    icon: 'gift',
  },
  new_arrival: {
    label: 'New arrival',
    chip: 'bg-[#eef1ff] text-[#4f5bd5]',
    gradient: 'from-[#6366f1] to-[#0ea5e9]',
    icon: 'sparkles',
  },
  event: {
    label: 'Event',
    chip: 'bg-[#fdf2f8] text-[#be185d]',
    gradient: 'from-[#db2777] to-[#9333ea]',
    icon: 'calendar',
  },
  news: {
    label: 'News',
    chip: 'bg-brandsoft text-brand',
    gradient: 'from-[#0b7a6e] to-[#3f5bd8]',
    icon: 'megaphone',
  },
};

export const CTA_TARGETS: Record<CtaTarget, { label: string; path: string }> = {
  item_packs: { label: 'Item packs', path: '/portal/item-packs' },
  registration: { label: 'Business registration', path: '/portal/registration' },
  referrals: { label: 'My referrals', path: '/portal/referrals' },
  stages: { label: 'My stages', path: '/portal/stages' },
  offers: { label: 'Offers & news', path: '/portal/offers' },
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
  category: AnnouncementCategory;
  featured: boolean;
  audience: AnnouncementAudience;
  ctaLabel?: string | null;
  ctaTarget?: CtaTarget | null;
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

/**
 * Tells the office a customer saw a post, or pressed its button. Fire and forget: counting must
 * never get in the way of reading. The server counts each person once, so repeats are harmless.
 */
export function recordEngagement(id: string, kind: 'view' | 'click') {
  if (id === 'preview') return;
  void api.post(`/api/v1/announcements/${id}/engagement`, { kind }).catch(() => undefined);
}
