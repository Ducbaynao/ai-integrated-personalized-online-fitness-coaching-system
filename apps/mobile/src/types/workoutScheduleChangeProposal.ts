import type { PlannedWorkoutChangeResult, WorkoutScheduleChangeDestination } from './workoutSchedule';

export type WorkoutScheduleChangeType = 'RESCHEDULE' | 'CANCEL';
export type WorkoutScheduleChangeProposalStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN';

export interface CreateWorkoutScheduleChangeProposalRequest {
  occurrenceId: string;
  expectedOccurrenceVersion: number;
  changeType: WorkoutScheduleChangeType;
  destination?: WorkoutScheduleChangeDestination;
  reason?: string | null;
  commandKey: string;
}

export interface WorkoutScheduleChangeProposal {
  proposalId: string;
  proposalVersion: number;
  status: WorkoutScheduleChangeProposalStatus;
  changeType: WorkoutScheduleChangeType;
  studentId: string;
  trainerId: string;
  trainerDisplayName: string | null;
  coachingPeriodId: string;
  occurrenceId: string;
  observedOccurrenceVersion: number;
  oldPlannedStartAt: string;
  oldPlannedEndAt: string | null;
  oldTimezone: string | null;
  oldLocalStart: string | null;
  oldLocalEnd: string | null;
  oldStartUtcOffset: string | null;
  oldEndUtcOffset: string | null;
  originalPlannedStartAt: string;
  sourcePlanId: string | null;
  sourcePlanVersionId: string | null;
  sourcePlanSessionId: string | null;
  sourcePlanName: string | null;
  sourceSessionName: string | null;
  destination: WorkoutScheduleChangeDestination | null;
  reason: string | null;
  proposedAt: string;
  decidedAt: string | null;
  decidedBy: string | null;
  withdrawnAt: string | null;
  withdrawnBy: string | null;
}

export interface WorkoutScheduleChangeProposalPage {
  items: WorkoutScheduleChangeProposal[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface WorkoutScheduleChangeProposalCommandResult {
  proposal: WorkoutScheduleChangeProposal;
  replayed: boolean;
}

export interface WithdrawWorkoutScheduleChangeProposalRequest {
  expectedProposalVersion: number;
  commandKey: string;
}

export type WorkoutScheduleChangeProposalDecision = 'ACCEPT' | 'REJECT';

export interface DecideWorkoutScheduleChangeProposalRequest {
  expectedProposalVersion: number;
  decision: WorkoutScheduleChangeProposalDecision;
  decisionReason?: string | null;
  commandKey: string;
}

export interface WorkoutScheduleChangeProposalDecisionResult {
  proposal: WorkoutScheduleChangeProposal;
  appliedChange: PlannedWorkoutChangeResult | null;
  replayed: boolean;
}
