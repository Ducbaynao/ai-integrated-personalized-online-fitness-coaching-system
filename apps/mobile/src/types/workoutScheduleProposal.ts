export type WorkoutScheduleProposalStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'WITHDRAWN';
export type WorkoutScheduleDecision = 'ACCEPT' | 'REJECT';

export interface WorkoutScheduleProposalItem {
  clientItemId: string;
  planSessionId: string;
  weekNumber: number;
  dayNumber: number;
  sequenceNumber: number;
  localStart: string;
  localEnd: string;
  startUtcOffset: string;
  endUtcOffset: string;
  sourceSessionName: string | null;
  sourceSessionFocus: string | null;
}

export interface WorkoutScheduleProposal {
  proposalId: string;
  proposalVersion: number;
  status: WorkoutScheduleProposalStatus;
  studentId: string;
  trainerId: string;
  trainerDisplayName: string | null;
  coachingPeriodId: string;
  planId: string;
  sourcePlanName: string | null;
  sourcePlanVersionId: string;
  sourcePlanVersionNumber: number;
  observedPlanAggregateVersion: number;
  weekAnchorDate: string;
  timezone: string;
  items: WorkoutScheduleProposalItem[];
  proposedAt: string;
  decidedAt?: string | null;
  decidedBy?: string | null;
  decisionNote?: string | null;
  withdrawnAt?: string | null;
  withdrawnBy?: string | null;
}

export interface WithdrawWorkoutScheduleProposalRequest {
  expectedProposalVersion: number;
  commandKey: string;
}

export interface WorkoutScheduleProposalCommandResult {
  proposal: WorkoutScheduleProposal;
  replayed: boolean;
}

export interface WorkoutScheduleProposalPage {
  items: WorkoutScheduleProposal[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface DecideWorkoutScheduleProposalRequest {
  expectedProposalVersion: number;
  decision: WorkoutScheduleDecision;
  decisionNote?: string | null;
  commandKey: string;
}

export interface WorkoutScheduleProposalDecisionResult {
  proposal: WorkoutScheduleProposal;
  confirmedBatch: {
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
    items: (Omit<WorkoutScheduleProposalItem, 'sourceSessionName' | 'sourceSessionFocus'> & {
      occurrenceId: string;
      occurrenceVersion: number;
      plannedStartAt: string;
      plannedEndAt: string;
      supervisionRequirement: 'SELF_PERFORMABLE' | 'COACH_OPTIONAL' | 'COACH_REQUIRED';
    })[];
    replayed: boolean;
  } | null;
  replayed: boolean;
}
