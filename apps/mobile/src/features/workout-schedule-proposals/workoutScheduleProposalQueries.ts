import { infiniteQueryOptions, queryOptions, useInfiniteQuery, useQuery } from '@tanstack/react-query';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';
import type { WorkoutScheduleProposalStatus } from '@/types/workoutScheduleProposal';

export const workoutScheduleProposalQueryKeys = {
  all: ['workout-schedule-proposals'] as const,
  student: (studentId: string) => [...workoutScheduleProposalQueryKeys.all, 'student', studentId] as const,
  detail: (studentId: string, proposalId: string) =>
    [...workoutScheduleProposalQueryKeys.student(studentId), 'detail', proposalId] as const,
  trainer: (trainerId: string, studentId: string) => [...workoutScheduleProposalQueryKeys.all, 'trainer', trainerId, studentId] as const,
  trainerList: (trainerId: string, studentId: string, status?: WorkoutScheduleProposalStatus) =>
    [...workoutScheduleProposalQueryKeys.trainer(trainerId, studentId), 'list', status ?? 'ALL'] as const,
  trainerDetail: (trainerId: string, studentId: string, proposalId: string) =>
    [...workoutScheduleProposalQueryKeys.trainer(trainerId, studentId), 'detail', proposalId] as const,
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

export const trainerWorkoutScheduleProposalsOptions = (trainerId: string, studentId: string, status?: WorkoutScheduleProposalStatus, enabled = true) =>
  infiniteQueryOptions({
    queryKey: workoutScheduleProposalQueryKeys.trainerList(trainerId, studentId, status),
    queryFn: ({ pageParam }) => workoutScheduleProposalApi.listTrainer(studentId, pageParam, status),
    initialPageParam: 0,
    getNextPageParam: (page) => page.page + 1 < page.totalPages ? page.page + 1 : undefined,
    enabled: Boolean(trainerId && studentId) && enabled,
    staleTime: 5_000,
  });

export const useTrainerWorkoutScheduleProposals = (trainerId: string, studentId: string, status?: WorkoutScheduleProposalStatus, enabled = true) =>
  useInfiniteQuery(trainerWorkoutScheduleProposalsOptions(trainerId, studentId, status, enabled));

export const useTrainerWorkoutScheduleProposalDetail = (trainerId: string, studentId: string, proposalId: string, enabled = true) =>
  useQuery(queryOptions({
    queryKey: workoutScheduleProposalQueryKeys.trainerDetail(trainerId, studentId, proposalId),
    queryFn: () => workoutScheduleProposalApi.detail(proposalId),
    enabled: Boolean(trainerId && studentId && proposalId) && enabled,
    staleTime: 5_000,
  }));
