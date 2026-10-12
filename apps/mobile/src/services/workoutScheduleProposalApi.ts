import { request } from '@/services/apiClient';
import type {
  CreateWorkoutScheduleProposalRequest,
  DecideWorkoutScheduleProposalRequest,
  WorkoutScheduleProposal,
  WorkoutScheduleProposalDecisionResult,
  WorkoutScheduleProposalPage,
  WorkoutScheduleProposalStatus,
  WithdrawWorkoutScheduleProposalRequest,
  WorkoutScheduleProposalCommandResult,
} from '@/types/workoutScheduleProposal';

export const WORKOUT_SCHEDULE_PROPOSAL_PAGE_SIZE = 20;

export const workoutScheduleProposalApi = {
  create(studentId: string, body: CreateWorkoutScheduleProposalRequest) {
    return request<WorkoutScheduleProposalCommandResult>(`/students/${encodeURIComponent(studentId)}/planned-workout-schedule-proposals`, { method: 'POST', body });
  },
  listMine(page = 0, size = WORKOUT_SCHEDULE_PROPOSAL_PAGE_SIZE) {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    return request<WorkoutScheduleProposalPage>(`/planned-workout-schedule-proposals/me?${params}`, { method: 'GET' });
  },
  listTrainer(studentId: string, page = 0, status?: WorkoutScheduleProposalStatus, size = WORKOUT_SCHEDULE_PROPOSAL_PAGE_SIZE) {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    return request<WorkoutScheduleProposalPage>(`/students/${encodeURIComponent(studentId)}/planned-workout-schedule-proposals?${params}`, { method: 'GET' });
  },
  detail(proposalId: string) {
    return request<WorkoutScheduleProposal>(`/planned-workout-schedule-proposals/${encodeURIComponent(proposalId)}`, { method: 'GET' });
  },
  decide(proposalId: string, body: DecideWorkoutScheduleProposalRequest) {
    return request<WorkoutScheduleProposalDecisionResult>(
      `/planned-workout-schedule-proposals/${encodeURIComponent(proposalId)}/decisions`,
      { method: 'POST', body },
    );
  },
  withdraw(proposalId: string, body: WithdrawWorkoutScheduleProposalRequest) {
    return request<WorkoutScheduleProposalCommandResult>(`/planned-workout-schedule-proposals/${encodeURIComponent(proposalId)}/withdraw`, { method: 'POST', body });
  },
};
