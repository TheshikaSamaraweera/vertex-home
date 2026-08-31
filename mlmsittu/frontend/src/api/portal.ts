import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];

export type PortalView = Schemas['PortalView'];
export type RegistrationStatus = Schemas['RegistrationStatus'];
export type TimelineEntry = Schemas['TimelineEntry'];
export type DistributorNode = Schemas['DistributorNode'];

/**
 * How far a distributor has got. Drives the whole portal: until it reads `ACTIVE`, the only screen
 * that exists is the registration one.
 */
export type PortalAccess = NonNullable<PortalView['access']>;

export const portalKeys = {
  me: ['portal', 'me'] as const,
  referrals: ['portal', 'referrals'] as const,
};

/**
 * The single call the portal is built on.
 *
 * Polled while an application is pending, because approval happens on somebody else's screen and
 * nothing pushes it. Thirty seconds is frequent enough that a distributor watching for a decision
 * sees it without refreshing, and rare enough to be invisible.
 */
export const usePortalMe = () =>
  useQuery({
    queryKey: portalKeys.me,
    queryFn: () => api.get<PortalView>('/api/v1/portal/me'),
    refetchInterval: (query) =>
      query.state.data?.access === 'ACTIVE' ? false : 30_000,
  });

export const usePortalReferrals = (enabled: boolean) =>
  useQuery({
    queryKey: portalKeys.referrals,
    queryFn: () =>
      api
        .get<PagedResponse<DistributorNode>>('/api/v1/portal/referrals')
        .then((page) => page.data),
    enabled,
  });

/** Submitting a registration changes what the portal may show, so it invalidates everything. */
export const useSubmitRegistration = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: unknown) =>
      api.post<{ registrationId: string; status: string }>('/api/v1/registrations', body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['portal'] });
      void queryClient.invalidateQueries({ queryKey: ['registrations'] });
    },
  });
};

/** Human wording for each gate state, and what to do about it. */
export const ACCESS_COPY: Record<PortalAccess, { title: string; body: string }> = {
  REGISTRATION_REQUIRED: {
    title: 'Register your business to continue',
    body: 'Your account exists, but nothing opens until a business registration has been approved. It takes a few minutes and needs your NIC and a bank slip.',
  },
  PENDING_REVIEW: {
    title: 'Your registration is being reviewed',
    body: 'Nothing is needed from you. This page updates on its own when a decision is made.',
  },
  CHANGES_REQUESTED: {
    title: 'Something needs correcting',
    body: 'The reviewer has asked for a change. Their comments are below — fix it and submit again.',
  },
  REJECTED: {
    title: 'Your registration was not accepted',
    body: 'The reason is below. Contact the office if you think this is wrong.',
  },
  ACTIVE: {
    title: 'Active',
    body: 'Your registration is approved.',
  },
};
