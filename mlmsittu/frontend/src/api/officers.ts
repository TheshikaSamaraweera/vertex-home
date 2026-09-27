import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, type PagedResponse } from './client';

export type MarketingOfficer = {
  userId: string;
  fullName: string;
  email: string | null;
  mobile: string | null;
  profilePhotoId: string | null;
  /** A fraction: 0.01 is one per cent. */
  commissionRate: number;
  customerCount: number;
  earned: number;
  /** applied | approved | rejected. Only an approved officer may hold customers. */
  status: 'applied' | 'approved' | 'rejected';
  appliedAt: string | null;
  decidedAt: string | null;
  rejectionReason: string | null;
};

export type AssignedCustomer = {
  distributorId: string;
  businessId: string | null;
  fullName: string;
  status: string;
  stagesCompleted: number;
  totalStages: number;
  approvedAt: string | null;
  expiresAt: string | null;
  packName: string | null;
  packPrice: number | null;
  packIssued: boolean;
  packIssuedAt: string | null;
  /** What this customer has earned their officer. Zero until the pack is actually issued. */
  earned: number;
};

/** A fraction to a readable percentage. 0.0125 → "1.25%". */
export const asPercent = (rate: number) => `${(rate * 100).toFixed(2).replace(/\.00$/, '')}%`;

export const useMarketingOfficers = () =>
  useQuery({
    queryKey: ['marketing-officers'],
    queryFn: () =>
      api.get<PagedResponse<MarketingOfficer>>('/api/v1/admin/marketing-officers'),
    select: (page) => page.data,
  });

/** Everybody waiting on a decision. Deliberately a separate list from the officers themselves. */
export const useOfficerApplications = () =>
  useQuery({
    queryKey: ['marketing-officers', 'applications'],
    queryFn: () =>
      api.get<PagedResponse<MarketingOfficer>>(
        '/api/v1/admin/marketing-officers/applications',
      ),
    select: (page) => page.data,
  });

export const useOfficerCustomers = (userId: string | null) =>
  useQuery({
    queryKey: ['marketing-officers', userId, 'customers'],
    queryFn: () =>
      api.get<PagedResponse<AssignedCustomer>>(
        `/api/v1/admin/marketing-officers/${userId}/customers`,
      ),
    select: (page) => page.data,
    enabled: Boolean(userId),
  });

function useOfficerMutation<TArgs>(fn: (args: TArgs) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: fn,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['marketing-officers'] });
      // The customer list carries the officer's name, so it goes stale on every assignment.
      void queryClient.invalidateQueries({ queryKey: ['distributors'] });
      void queryClient.invalidateQueries({ queryKey: ['distributor'] });
    },
  });
}

export const useEnrolOfficer = () =>
  useOfficerMutation((body: { userId: string; commissionRate?: number }) =>
    api.post<MarketingOfficer>('/api/v1/admin/marketing-officers', body),
  );

export const useSetOfficerRate = () =>
  useOfficerMutation(({ userId, commissionRate }: { userId: string; commissionRate: number }) =>
    api.put<MarketingOfficer>(`/api/v1/admin/marketing-officers/${userId}/rate`, {
      commissionRate,
    }),
  );

export const useApproveOfficer = () =>
  useOfficerMutation(({ userId, commissionRate }: { userId: string; commissionRate?: number }) =>
    api.post<MarketingOfficer>(`/api/v1/admin/marketing-officers/${userId}/approve`, {
      commissionRate,
    }),
  );

export const useRejectOfficer = () =>
  useOfficerMutation(({ userId, reason }: { userId: string; reason: string }) =>
    api.post<MarketingOfficer>(`/api/v1/admin/marketing-officers/${userId}/reject`, { reason }),
  );

/** Creates the account and the officer in one go, approved on the spot. */
export const useRegisterOfficer = () =>
  useOfficerMutation(
    (body: {
      fullName: string;
      email?: string | null;
      mobile?: string | null;
      password: string;
      commissionRate?: number;
    }) => api.post<MarketingOfficer>('/api/v1/admin/marketing-officers/register', body),
  );

export const useAssignOfficer = () =>
  useOfficerMutation(
    ({
      distributorId,
      marketingOfficerId,
    }: {
      distributorId: string;
      marketingOfficerId: string | null;
    }) =>
      api.put(`/api/v1/admin/distributors/${distributorId}/marketing-officer`, {
        marketingOfficerId,
      }),
  );

// ------------------------------------------------------------------ the officer's own portal

export const useMyOfficerRecord = () =>
  useQuery({
    queryKey: ['officer', 'me'],
    queryFn: () => api.get<MarketingOfficer>('/api/v1/officer/me'),
  });

export const useMyAssignedCustomers = () =>
  useQuery({
    queryKey: ['officer', 'customers'],
    queryFn: () => api.get<PagedResponse<AssignedCustomer>>('/api/v1/officer/customers'),
    select: (page) => page.data,
  });
