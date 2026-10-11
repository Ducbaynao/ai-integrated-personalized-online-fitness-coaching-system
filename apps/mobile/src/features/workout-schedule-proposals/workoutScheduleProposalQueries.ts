import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';

export const workoutScheduleProposalQueryKeys = {
  all: ['workout-schedule-proposals'] as const,
  student: (studentId: string) => [...workoutScheduleProposalQueryKeys.all, 'student', studentId] as const,
  detail: (studentId: string, proposalId: string) =>
    [...workoutScheduleProposalQueryKeys.student(studentId), 'detail', proposalId] as const,
};

export const workoutScheduleProposalsOptions = (studentId: string, enabled = true) =>
  infiniteQueryOptions({
    queryKey: workoutScheduleProposalQueryKeys.student(studentId),
    queryFn: ({ pageParam }) => workoutScheduleProposalApi.listMine(pageParam),
    initialPageParam: 0,
    getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
    enabled: Boolean(studentId) && enabled,
    staleTime: 5_000,
  });

export const workoutScheduleProposalDetailOptions = (studentId: string, proposalId: string, enabled = true) =>
  queryOptions({
    queryKey: workoutScheduleProposalQueryKeys.detail(studentId, proposalId),
    queryFn: () => workoutScheduleProposalApi.detail(proposalId),
    enabled: Boolean(studentId && proposalId) && enabled,
    staleTime: 5_000,
  });

export const useWorkoutScheduleProposals = (studentId: string, enabled = true) =>
  useInfiniteQuery(workoutScheduleProposalsOptions(studentId, enabled));

export const useWorkoutScheduleProposalDetail = (studentId: string, proposalId: string, enabled = true) =>
  useQuery(workoutScheduleProposalDetailOptions(studentId, proposalId, enabled));
