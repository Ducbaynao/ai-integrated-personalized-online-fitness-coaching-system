import { request } from '@/services/apiClient';
import type {
  CreateWorkoutScheduleChangeProposalRequest,
  DecideWorkoutScheduleChangeProposalRequest,
  WithdrawWorkoutScheduleChangeProposalRequest,
  WorkoutScheduleChangeProposal,
  WorkoutScheduleChangeProposalCommandResult,
  WorkoutScheduleChangeProposalDecisionResult,
  WorkoutScheduleChangeProposalPage,
  WorkoutScheduleChangeProposalStatus,
} from '@/types/workoutScheduleChangeProposal';

export const WORKOUT_CHANGE_PROPOSAL_PAGE_SIZE = 20;

export const workoutScheduleChangeProposalApi = {
  listTrainer(studentId: string, page = 0, status?: WorkoutScheduleChangeProposalStatus, size = WORKOUT_CHANGE_PROPOSAL_PAGE_SIZE) {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    return request<WorkoutScheduleChangeProposalPage>(
      `/students/${encodeURIComponent(studentId)}/planned-workout-change-proposals?${params}`, { method: 'GET' },
    );
  },
  listMine(page = 0, status?: WorkoutScheduleChangeProposalStatus, size = WORKOUT_CHANGE_PROPOSAL_PAGE_SIZE) {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    if (status) params.set('status', status);
    return request<WorkoutScheduleChangeProposalPage>(`/planned-workout-change-proposals/me?${params}`, { method: 'GET' });
  },
  detail(proposalId: string) {
    return request<WorkoutScheduleChangeProposal>(`/planned-workout-change-proposals/${encodeURIComponent(proposalId)}`, { method: 'GET' });
  },
  create(studentId: string, body: CreateWorkoutScheduleChangeProposalRequest) {
    return request<WorkoutScheduleChangeProposalCommandResult>(
      `/students/${encodeURIComponent(studentId)}/planned-workout-change-proposals`, { method: 'POST', body },
    );
  },
  withdraw(proposalId: string, body: WithdrawWorkoutScheduleChangeProposalRequest) {
    return request<WorkoutScheduleChangeProposalCommandResult>(
      `/planned-workout-change-proposals/${encodeURIComponent(proposalId)}/withdraw`, { method: 'POST', body },
    );
  },
  decide(proposalId: string, body: DecideWorkoutScheduleChangeProposalRequest) {
    return request<WorkoutScheduleChangeProposalDecisionResult>(
      `/planned-workout-change-proposals/${encodeURIComponent(proposalId)}/decisions`, { method: 'POST', body },
    );
  },
};
