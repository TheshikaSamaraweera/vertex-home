import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';
import type { components } from './schema';

type Schemas = components['schemas'];

export type PortalView = Schemas['PortalView'];
export type RegistrationStatus = Schemas['RegistrationStatus'];
export type TimelineEntry = Schemas['TimelineEntry'];
export type DistributorNode = Schemas['DistributorNode'];
export type RewardSnapshot = Schemas['RewardSnapshot'];
export type PickupPoint = Schemas['PickupPoint'];

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
    // Pending applications poll for a decision; so does a pack on its way, since the office
    // moves it on from another screen. Live notifications refresh it sooner when they arrive.
    refetchInterval: (query) => {
      const data = query.state.data;
      if (data?.access === 'COMPLETED') return false;
      const tracking = data?.reward?.tracking;
      if (data?.access === 'ACTIVE') return tracking ? 60_000 : false;
      return 30_000;
    },
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
  EXPIRED: {
    title: 'Your membership has ended',
    body: 'Contact the office to renew it. Your Business ID, your place and your referrals are all kept — nothing is lost, and everything reopens the moment it is extended.',
  },
  REJECTED: {
    title: 'Your registration was not accepted',
    body: 'The reason is below. Contact the office if you think this is wrong.',
  },
  ACTIVE: {
    title: 'Active',
    body: 'Your registration is approved.',
  },
  COMPLETED: {
    title: 'This business account is complete',
    body: 'Your item pack has been handed over. The account is closed now — its history stays on your dashboard.',
  },
};

// ---------------------------------------------------------------- pack delivery

export const usePickupPoints = (enabled: boolean) =>
  useQuery({
    queryKey: ['portal', 'pickup-points'],
    queryFn: () =>
      api.get<PagedResponse<PickupPoint>>('/api/v1/portal/reward/pickup-points').then((page) => page.data),
    enabled,
  });

/** Once only: after this the office changes it. The answer is the refreshed portal view. */
export const useChooseReceiveMethod = () => {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (body: {
      method: 'pickup' | 'delivery';
      pickupLocationId?: string;
      deliveryAddress?: string;
      deliveryContact?: string;
    }) => api.put<PortalView>('/api/v1/portal/reward/receive-method', body),
    onSuccess: (view) => queryClient.setQueryData(portalKeys.me, view),
  });
};

// ---------------------------------------------------------------- item packs

export type PackCatalogue = Schemas['Catalogue'];
export type Pack = Schemas['Pack'];
export type PackItem = Schemas['PackItem'];

/**
 * Every item pack, as this customer may see it: all of them in full until their registration
 * names one, then that one in full and the rest locked (the server withholds their contents).
 * Open before registration is approved.
 */
export const usePortalItemPacks = () =>
  useQuery({
    queryKey: ['portal', 'item-packs'],
    queryFn: () => api.get<PackCatalogue>('/api/v1/portal/item-packs'),
  });

/** A pack or item picture, served by the portal for customers. */
export const portalPictureUrl = (id: string) => `/api/v1/portal/pictures/${id}`;
