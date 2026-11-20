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

export function isPeriodEffective(period: CoachingPeriod | null | undefined, now = Date.now()): boolean {
  if (!period) return false;
  const started = Date.parse(period.startedAt);
  if (Number.isNaN(started) || started > now) return false;
  if (period.endedAt) {
    const ended = Date.parse(period.endedAt);
    if (Number.isNaN(ended) || ended <= now) return false;
  }
  return true;
}

export function findEffectivePeriod(periods: readonly CoachingPeriod[], now = Date.now()): CoachingPeriod | null {
  for (const period of periods) {
    if (isPeriodEffective(period, now)) return period;
  }
  return null;
}

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
      // Query recent periods (size=20) to find the current effective period (startedAt <= now and (endedAt is null or endedAt > now)).
      // Limitation: if a student has more than 20 future periods scheduled, older effective period will not be on page 0. Backend enforces final authority.
      const page = await request<Page<CoachingPeriod>>('/coaching/periods/me?page=0&size=20', { method: 'GET' });
      return findEffectivePeriod(page.items);
    },
    enabled: enabled && Boolean(actorId),
    staleTime: 0,
    retry: false,
    refetchOnMount: 'always',
  }));
}
