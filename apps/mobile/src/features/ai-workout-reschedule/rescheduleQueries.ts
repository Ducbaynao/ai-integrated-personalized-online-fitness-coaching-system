import { queryOptions, useQuery } from '@tanstack/react-query';
import { request } from '@/services/apiClient';
import { aiWorkoutRescheduleApi } from '@/services/aiWorkoutRescheduleApi';
import type { CoachingPeriod, Page } from '@/types/coaching';

export type RescheduleActorCapability = 'STUDENT' | 'TRAINER';

export const rescheduleQueryKeys = {
  all: ['ai-workout-reschedule'] as const,
  scope: (actorId: string, capability: RescheduleActorCapability, studentId: string) =>
    [...rescheduleQueryKeys.all, actorId, capability, studentId] as const,
  occurrence: (actorId: string, capability: RescheduleActorCapability, studentId: string, occurrenceId: string) =>
    [...rescheduleQueryKeys.scope(actorId, capability, studentId), occurrenceId] as const,
  detail: (actorId: string, capability: RescheduleActorCapability, studentId: string, occurrenceId: string, recommendationId: string) =>
    [...rescheduleQueryKeys.occurrence(actorId, capability, studentId, occurrenceId), recommendationId] as const,
  studentPeriod: (actorId: string) => [...rescheduleQueryKeys.scope(actorId, 'STUDENT', actorId), 'current-period'] as const,
};

export function useRescheduleDetail(actorId: string, capability: RescheduleActorCapability, studentId: string,
  occurrenceId: string, recommendationId: string | null, enabled: boolean) {
  return useQuery(queryOptions({
    queryKey: rescheduleQueryKeys.detail(actorId, capability, studentId, occurrenceId, recommendationId ?? ''),
    queryFn: () => aiWorkoutRescheduleApi.getRecommendation(recommendationId!),
    enabled: enabled && Boolean(actorId && studentId && occurrenceId && recommendationId),
    staleTime: 0,
    retry: false,
    refetchOnMount: 'always',
  }));
}

export function useCurrentStudentPeriod(actorId: string, enabled: boolean) {
  return useQuery(queryOptions({
    queryKey: rescheduleQueryKeys.studentPeriod(actorId),
    queryFn: async () => {
      const page = await request<Page<CoachingPeriod>>('/coaching/periods/me?page=0&size=1', { method: 'GET' });
      return page.items[0] ?? null;
    },
    enabled: enabled && Boolean(actorId),
    staleTime: 0,
    retry: false,
    refetchOnMount: 'always',
  }));
}
