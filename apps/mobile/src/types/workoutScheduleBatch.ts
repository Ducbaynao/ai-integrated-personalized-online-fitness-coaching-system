import type { CreateWorkoutScheduleProposalRequest } from './workoutScheduleProposal';

// Student confirmation and Trainer proposal share the published batch input schema.
export type ConfirmWorkoutScheduleBatchRequest = CreateWorkoutScheduleProposalRequest;

export interface ConfirmedWorkoutScheduleBatch {
  batchId: string;
  studentId: string;
  planId: string;
  planVersionId: string;
  planVersionNumber: number;
  weekAnchorDate: string;
  timezone: string;
  confirmationSource: 'STUDENT_DIRECT' | 'TRAINER_PROPOSAL';
  sourceProposalId: string | null;
  confirmedBy: string;
  confirmedAt: string;
  items: (ConfirmWorkoutScheduleBatchRequest['items'][number] & {
    occurrenceId: string;
    occurrenceVersion: number;
    plannedStartAt: string;
    plannedEndAt: string;
    supervisionRequirement: 'SELF_PERFORMABLE' | 'COACH_OPTIONAL' | 'COACH_REQUIRED';
  })[];
  replayed: boolean;
}
