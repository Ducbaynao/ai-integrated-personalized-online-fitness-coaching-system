import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { coachingApi, COACHING_PAGE_SIZE } from '@/services/coachingApi';

export const coachingQueryKeys = {
  all: ['coaching'] as const,
  trainers: (query: string) => [...coachingQueryKeys.all, 'trainers', query.trim().toLocaleLowerCase('vi-VN')] as const,
  relationships: () => [...coachingQueryKeys.all, 'relationships'] as const,
  detail: (relationshipId: string) => [...coachingQueryKeys.all, 'relationship', relationshipId] as const,
  history: (relationshipId: string) => [...coachingQueryKeys.all, 'relationship', relationshipId, 'history'] as const,
  sharing: (relationshipId: string) => [...coachingQueryKeys.all, 'sharing', relationshipId] as const,
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
export const useRelationshipDetail = (id: string) => useQuery(relationshipDetailOptions(id));
export const useRelationshipHistory = (id: string) => useInfiniteQuery(relationshipHistoryOptions(id));
export const useSharingSummary = (id: string) => useQuery(sharingSummaryOptions(id));

