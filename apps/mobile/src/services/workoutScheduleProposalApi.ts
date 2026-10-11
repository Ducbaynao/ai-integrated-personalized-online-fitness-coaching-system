import { request } from '@/services/apiClient';
import type {
  DecideWorkoutScheduleProposalRequest,
  WorkoutScheduleProposal,
  WorkoutScheduleProposalDecisionResult,
  WorkoutScheduleProposalPage,
} from '@/types/workoutScheduleProposal';

export const WORKOUT_SCHEDULE_PROPOSAL_PAGE_SIZE = 20;

export const workoutScheduleProposalApi = {
  listMine(page = 0, size = WORKOUT_SCHEDULE_PROPOSAL_PAGE_SIZE) {
    const params = new URLSearchParams({ page: String(page), size: String(size) });
    return request<WorkoutScheduleProposalPage>(`/planned-workout-schedule-proposals/me?${params}`, { method: 'GET' });
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
};
