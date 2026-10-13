import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { workoutScheduleChangeProposalApi, WORKOUT_CHANGE_PROPOSAL_PAGE_SIZE } from '@/services/workoutScheduleChangeProposalApi';
import type { WorkoutScheduleChangeProposalStatus } from '@/types/workoutScheduleChangeProposal';

export const workoutScheduleChangeProposalQueryKeys = {
  all: ['workout-schedule-change-proposals'] as const,
  student: (studentId: string) => [...workoutScheduleChangeProposalQueryKeys.all, 'student', studentId] as const,
  studentList: (studentId: string, status?: WorkoutScheduleChangeProposalStatus) =>
    [...workoutScheduleChangeProposalQueryKeys.student(studentId), 'list', status ?? 'ALL'] as const,
  studentDetail: (studentId: string, proposalId: string) =>
    [...workoutScheduleChangeProposalQueryKeys.student(studentId), 'detail', proposalId] as const,
  trainer: (trainerId: string, studentId: string) =>
    [...workoutScheduleChangeProposalQueryKeys.all, 'trainer', trainerId, studentId] as const,
  list: (trainerId: string, studentId: string, status?: WorkoutScheduleChangeProposalStatus) =>
    [...workoutScheduleChangeProposalQueryKeys.trainer(trainerId, studentId), 'list', status ?? 'ALL'] as const,
  detail: (trainerId: string, studentId: string, proposalId: string) =>
    [...workoutScheduleChangeProposalQueryKeys.trainer(trainerId, studentId), 'detail', proposalId] as const,
};

export const useStudentWorkoutScheduleChangeProposals = (
  studentId: string, status?: WorkoutScheduleChangeProposalStatus, enabled = true,
) => useInfiniteQuery(infiniteQueryOptions({
  queryKey: workoutScheduleChangeProposalQueryKeys.studentList(studentId, status),
  queryFn: ({ pageParam }) => workoutScheduleChangeProposalApi.listMine(pageParam, status, WORKOUT_CHANGE_PROPOSAL_PAGE_SIZE),
  initialPageParam: 0,
  getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  enabled: Boolean(studentId) && enabled,
  staleTime: 0,
  refetchOnMount: 'always',
}));

export const useStudentWorkoutScheduleChangeProposalDetail = (studentId: string, proposalId: string, enabled = true) =>
  useQuery(queryOptions({
    queryKey: workoutScheduleChangeProposalQueryKeys.studentDetail(studentId, proposalId),
    queryFn: () => workoutScheduleChangeProposalApi.detail(proposalId),
    enabled: Boolean(studentId && proposalId) && enabled,
    staleTime: 0,
    refetchOnMount: 'always',
  }));

export const useTrainerWorkoutScheduleChangeProposals = (
  trainerId: string, studentId: string, status?: WorkoutScheduleChangeProposalStatus, enabled = true,
) => useInfiniteQuery(infiniteQueryOptions({
  queryKey: workoutScheduleChangeProposalQueryKeys.list(trainerId, studentId, status),
  queryFn: ({ pageParam }) => workoutScheduleChangeProposalApi.listTrainer(studentId, pageParam, status, WORKOUT_CHANGE_PROPOSAL_PAGE_SIZE),
  initialPageParam: 0,
  getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
  enabled: Boolean(trainerId && studentId) && enabled,
  staleTime: 0,
  refetchOnMount: 'always',
}));

export const useTrainerWorkoutScheduleChangeProposalDetail = (
  trainerId: string, studentId: string, proposalId: string, enabled = true,
) => useQuery(queryOptions({
  queryKey: workoutScheduleChangeProposalQueryKeys.detail(trainerId, studentId, proposalId),
  queryFn: () => workoutScheduleChangeProposalApi.detail(proposalId),
  enabled: Boolean(trainerId && studentId && proposalId) && enabled,
  staleTime: 0,
  refetchOnMount: 'always',
}));
