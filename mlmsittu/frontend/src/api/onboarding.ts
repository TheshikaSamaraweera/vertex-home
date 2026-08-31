import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];

export type Registration = Schemas['RegistrationRow'];
export type RegistrationEvent = Schemas['EventRow'];
export type DistributorNode = Schemas['DistributorNode'];
export type StageProgress = Schemas['StageProgress'];

export const onboardingKeys = {
  queue: ['registrations', 'queue'] as const,
  detail: (id: string) => ['registrations', id] as const,
  mine: ['registrations', 'mine'] as const,
  roots: ['distributors', 'roots'] as const,
  children: (id: string) => ['distributors', id, 'children'] as const,
};

const list = <T>(path: string, query?: Record<string, string | number | boolean | undefined>) =>
  api.get<PagedResponse<T>>(path, query).then((page) => page.data);

// ---------------------------------------------------------------- review queue

export const useReviewQueue = () =>
  useQuery({
    queryKey: onboardingKeys.queue,
    queryFn: () => list<Registration>('/api/v1/admin/registrations'),
    // A claim taken by someone else changes what this reviewer can act on, and nothing pushes
    // that. Polling is the honest fix until the outbox and websockets exist.
    refetchInterval: 20_000,
  });

export const useRegistrationDetail = (id: string | null) =>
  useQuery({
    queryKey: onboardingKeys.detail(id ?? ''),
    queryFn: () =>
      api.get<{ registration: Registration; timeline: RegistrationEvent[] }>(
        `/api/v1/admin/registrations/${id}`,
      ),
    enabled: Boolean(id),
  });

export const useMyRegistrations = () =>
  useQuery({
    queryKey: onboardingKeys.mine,
    queryFn: () => list<Registration>('/api/v1/registrations/mine'),
  });

function useQueueMutation<TArgs, TResult>(mutationFn: (args: TArgs) => Promise<TResult>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['registrations'] });
      void queryClient.invalidateQueries({ queryKey: ['distributors'] });
    },
  });
}

export const useClaimRegistration = () =>
  useQueueMutation((id: string) => api.post<Registration>(`/api/v1/admin/registrations/${id}/claim`));

export const useReleaseRegistration = () =>
  useQueueMutation((id: string) => api.post<void>(`/api/v1/admin/registrations/${id}/release`));

export const useApproveRegistration = () =>
  useQueueMutation((id: string) =>
    api.post<{ registrationId: string; distributorId: string; businessId: string }>(
      `/api/v1/admin/registrations/${id}/approve`,
    ),
  );

export const useRejectRegistration = () =>
  useQueueMutation(
    ({
      id,
      reason,
      note,
      allowResubmit,
    }: {
      id: string;
      reason: string;
      note?: string;
      allowResubmit: boolean;
    }) => api.post<Registration>(`/api/v1/admin/registrations/${id}/reject`, { reason, note, allowResubmit }),
  );

// ---------------------------------------------------------------- referrer lookup

export type ReferrerCheck = {
  valid: boolean;
  reason?: string;
  businessId?: string;
  name?: string;
  hasCapacity?: boolean;
  maxDirect?: number;
  currentReferrals?: number;
};

/**
 * Server-side referrer lookup. Only called once the check character has already passed locally —
 * the point of the check digit is that a typo never becomes a request.
 */
export const useReferrerCheck = (businessId: string | null) =>
  useQuery({
    queryKey: ['referrer', businessId],
    queryFn: () => api.get<ReferrerCheck>(`/api/v1/referrers/${businessId}`),
    enabled: Boolean(businessId),
    retry: false,
  });

// ---------------------------------------------------------------- documents

/** Multipart, so it bypasses the JSON client. Cookies still travel with `credentials`. */
export async function uploadDocument(file: File, kind: 'nic' | 'bank_slip'): Promise<string> {
  const form = new FormData();
  form.append('file', file);
  form.append('kind', kind);

  const response = await fetch('/api/v1/documents', {
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
  return payload.documentId as string;
}

/**
 * Fetches a KYC document for display.
 *
 * Two steps on purpose: ask for a token — which is where the server authorises and logs the
 * access — then redeem it. The token lasts 60 seconds and works once, so the resulting object URL
 * is the only copy; reloading the image means asking again, and that ask is logged again.
 */
export async function fetchDocumentObjectUrl(documentId: string, subjectUserId?: string) {
  const grant = await api.post<{ token: string; url: string; expiresInSeconds: number }>(
    `/api/v1/admin/documents/${documentId}/access`,
    undefined,
    subjectUserId ? { subjectUserId } : undefined,
  );

  const response = await fetch(grant.url, { credentials: 'include' });
  if (!response.ok) {
    throw new Error('That document link expired before it could be opened.');
  }
  const blob = await response.blob();
  return { objectUrl: URL.createObjectURL(blob), contentType: blob.type };
}

// ---------------------------------------------------------------- hierarchy

export const useDistributorRoots = () =>
  useQuery({
    queryKey: onboardingKeys.roots,
    queryFn: () => list<DistributorNode>('/api/v1/distributors/roots'),
  });

/**
 * One level, fetched only when a node is expanded.
 *
 * Architecture §6.4: render depth 2 and expand on demand; never fetch the whole tree. A downline
 * of a few thousand is perfectly ordinary and would be several megabytes of JSON to draw the top
 * three rows of.
 */
/**
 * Every root and its descendants to a bounded depth, in one request.
 *
 * The drawn tree needs the whole shape before it can lay anything out, so this is the one place
 * that departs from the expand-on-demand rule above. It pays for that with a hard depth cap
 * server-side; the outline view remains the right tool for walking a very large network.
 */
export type ItemPackOption = Schemas['ItemPackOption'];

/**
 * The packs an applicant may choose from.
 *
 * Not the catalogue's item-set endpoint: that one is staff-only, and an applicant has an account
 * and nothing else. This returns a name and a price and stops there.
 */
/**
 * Creates an account for somebody who cannot create one themselves.
 *
 * Separate from submitting their registration, because they are separate things: a duplicate email
 * must not lose a completed form, and somebody who already has an account still needs a
 * registration filed.
 */
export const useCreateUserOnBehalf = () =>
  useMutation({
    mutationFn: (body: {
      fullName: string;
      email: string;
      mobile?: string;
      password: string;
    }) => api.post<{ id: string; email: string }>('/api/v1/admin/users', body),
  });

export const useItemPackOptions = () =>
  useQuery({
    queryKey: ['registrations', 'item-packs'],
    queryFn: () => list<ItemPackOption>('/api/v1/registrations/item-packs'),
  });

export const useDistributorForest = (depth: number, enabled = true) =>
  useQuery({
    queryKey: ['distributors', 'forest', depth],
    queryFn: () => list<DistributorNode>('/api/v1/distributors/tree', { depth }),
    enabled,
  });

export const useDistributorChildren = (id: string | null) =>
  useQuery({
    queryKey: onboardingKeys.children(id ?? ''),
    queryFn: () => list<DistributorNode>(`/api/v1/distributors/${id}/referrals`, { depth: 1 }),
    enabled: Boolean(id),
  });


// ---------------------------------------------------------------- distributor detail (P6-06)

export type DistributorDetail = Schemas['DistributorDetail'];

/**
 * The admin detail view.
 *
 * Documents arrive as ids only. Opening one still goes through `fetchDocumentObjectUrl`, which
 * authorises and logs the access — so this screen cannot become a second, unlogged way to read
 * somebody's NIC.
 */
export const useDistributorDetail = (id: string | null) =>
  useQuery({
    queryKey: ['distributors', id, 'detail'],
    queryFn: () => api.get<DistributorDetail>(`/api/v1/distributors/${id}/detail`),
    enabled: Boolean(id),
  });


// ---------------------------------------------------------------- referral cards

export type ReferralCardBatch = Schemas['ReferralCardBatch'];
export type ReferralCard = Schemas['ReferralCard'];

const referralCardKeys = {
  forDistributor: (id: string) => ['referral-cards', 'distributor', id] as const,
  batch: (id: string) => ['referral-cards', 'batch', id] as const,
};

/** Every batch printed for one customer, newest first. */
export const useReferralCardBatches = (distributorId: string | null) =>
  useQuery({
    queryKey: referralCardKeys.forDistributor(distributorId ?? ''),
    queryFn: () =>
      api
        .get<PagedResponse<ReferralCardBatch>>(
          `/api/v1/admin/distributors/${distributorId}/referral-cards`,
        )
        .then((page) => page.data),
    enabled: Boolean(distributorId),
  });

/** One batch, for the print view. */
export const useReferralCardBatch = (batchId: string | null) =>
  useQuery({
    queryKey: referralCardKeys.batch(batchId ?? ''),
    queryFn: () => api.get<ReferralCardBatch>(`/api/v1/admin/referral-cards/${batchId}`),
    enabled: Boolean(batchId),
  });

/**
 * Prints a new batch.
 *
 * Deliberately not idempotent, and the button that calls it says so: printing twice is a
 * legitimate thing to want when paper is lost or a printer jams, and the second batch is a
 * genuinely separate set of cards with its own codes.
 */
export const useIssueReferralCards = (distributorId: string) => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: { itemSetId?: string; count?: number; note?: string }) =>
      api.post<ReferralCardBatch>(
        `/api/v1/admin/distributors/${distributorId}/referral-cards`,
        body,
      ),
    onSuccess: () =>
      queryClient.invalidateQueries({
        queryKey: referralCardKeys.forDistributor(distributorId),
      }),
  });
};
