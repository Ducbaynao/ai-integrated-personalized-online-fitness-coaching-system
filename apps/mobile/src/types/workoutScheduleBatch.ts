import type { CreateWorkoutScheduleProposalRequest } from './workoutScheduleProposal';

// Student direct confirmation may link an existing confirmed Appointment; Trainer proposals cannot persist this link.
export type ConfirmWorkoutScheduleBatchRequest = Omit<CreateWorkoutScheduleProposalRequest, 'items'> & {
  items: (CreateWorkoutScheduleProposalRequest['items'][number] & { appointmentId?: string | null })[];
};

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
