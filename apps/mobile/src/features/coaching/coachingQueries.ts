import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { coachingApi, COACHING_PAGE_SIZE } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { CoachingQrRole } from '@/types/coaching';

export const coachingQueryKeys = {
  all: ['coaching'] as const,
  trainers: (query: string) => [...coachingQueryKeys.all, 'trainers', query.trim().toLocaleLowerCase('vi-VN')] as const,
  relationships: () => [...coachingQueryKeys.all, 'relationships'] as const,
  detail: (relationshipId: string) => [...coachingQueryKeys.all, 'relationship', relationshipId] as const,
  history: (relationshipId: string) => [...coachingQueryKeys.all, 'relationship', relationshipId, 'history'] as const,
  sharing: (relationshipId: string) => [...coachingQueryKeys.all, 'sharing', relationshipId] as const,
  qr: () => [...coachingQueryKeys.all, 'qr'] as const,
  myQr: (userId: string | undefined, role: CoachingQrRole) =>
    [...coachingQueryKeys.qr(), 'me', userId ?? 'anonymous', role] as const,
  qrPreview: (userId: string | undefined, publicCode: string, expectedTargetRole: CoachingQrRole) =>
    [...coachingQueryKeys.qr(), 'resolve', userId ?? 'anonymous', expectedTargetRole, publicCode] as const,
};

export function trainerDirectoryOptions(query: string) {
  const normalized = query.trim().toLocaleLowerCase('vi-VN');
  return infiniteQueryOptions({
    queryKey: coachingQueryKeys.trainers(normalized),
    queryFn: ({ pageParam }) => coachingApi.getTrainers(normalized, pageParam, COACHING_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last) => last.items.length === last.size ? last.page + 1 : undefined,
    staleTime: 30_000,
  });
}

export function relationshipCollectionOptions() {
  return infiniteQueryOptions({
    queryKey: coachingQueryKeys.relationships(),
    queryFn: ({ pageParam }) => coachingApi.getRelationships(pageParam, COACHING_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last) => last.items.length === last.size ? last.page + 1 : undefined,
    staleTime: 15_000,
  });
}

export function relationshipDetailOptions(relationshipId: string) {
  return queryOptions({
    queryKey: coachingQueryKeys.detail(relationshipId),
    queryFn: () => coachingApi.getRelationship(relationshipId),
    enabled: Boolean(relationshipId),
    staleTime: 10_000,
  });
}

export function sharingSummaryOptions(relationshipId: string) {
  return queryOptions({
    queryKey: coachingQueryKeys.sharing(relationshipId),
    queryFn: () => coachingApi.getSharingSummary(relationshipId),
    enabled: Boolean(relationshipId),
    staleTime: 10_000,
  });
}

export function relationshipHistoryOptions(relationshipId: string) {
  return infiniteQueryOptions({
    queryKey: coachingQueryKeys.history(relationshipId),
    queryFn: ({ pageParam }) => coachingApi.getRelationshipHistory(relationshipId, pageParam, COACHING_PAGE_SIZE),
    initialPageParam: 0,
    getNextPageParam: (last) => last.items.length === last.size ? last.page + 1 : undefined,
    enabled: Boolean(relationshipId),
    staleTime: 15_000,
  });
}

export const useTrainerDirectory = (query: string) => useInfiniteQuery(trainerDirectoryOptions(query));
export const useRelationships = () => useInfiniteQuery(relationshipCollectionOptions());
export const useRelationshipDetail = (id: string, requireFreshRead = false) => useQuery({
  ...relationshipDetailOptions(id),
  refetchOnMount: requireFreshRead ? 'always' : undefined,
});
export const useRelationshipHistory = (id: string) => useInfiniteQuery(relationshipHistoryOptions(id));
export const useSharingSummary = (id: string) => useQuery(sharingSummaryOptions(id));

export function myCoachingQrOptions(userId: string | undefined, role: CoachingQrRole) {
  return queryOptions({
    queryKey: coachingQueryKeys.myQr(userId, role),
    queryFn: async () => {
      try {
        return await coachingApi.getMyQr(role);
      } catch (error) {
        if (
          error instanceof ApiError &&
          error.status === 404 &&
          error.errorResponse?.errorCode === 'COACHING_QR_NOT_FOUND'
        ) {
          return coachingApi.issueMyQr(role);
        }
        throw error;
      }
    },
    staleTime: 60_000,
  });
}

export const useMyCoachingQr = (userId: string | undefined, role: CoachingQrRole, enabled = true) =>
  useQuery({ ...myCoachingQrOptions(userId, role), enabled: enabled && Boolean(userId) });

export function resolveCoachingQrOptions(
  userId: string | undefined,
  publicCode: string,
  expectedTargetRole: CoachingQrRole,
  payloadVersion = 1
) {
  return queryOptions({
    queryKey: coachingQueryKeys.qrPreview(userId, publicCode, expectedTargetRole),
    queryFn: () => coachingApi.resolveQr(publicCode, expectedTargetRole, payloadVersion),
    enabled: Boolean(userId) && Boolean(publicCode),
    staleTime: 0,
    gcTime: 0,
    retry: false,
  });
}

export const useResolveCoachingQr = (
  userId: string | undefined,
  publicCode: string,
  expectedTargetRole: CoachingQrRole,
  payloadVersion = 1
) => useQuery(resolveCoachingQrOptions(userId, publicCode, expectedTargetRole, payloadVersion));

