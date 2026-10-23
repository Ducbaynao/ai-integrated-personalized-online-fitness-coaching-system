import type { WorkoutExecutionSupervisionRequirement } from './workoutExecution';

export type PlannedWorkoutStatus =
  | 'SCHEDULED' | 'COMPLETED' | 'ATTEMPTED' | 'MISSED'
  | 'SKIPPED' | 'RESCHEDULED' | 'CANCELLED';
export type PlannedWorkoutAction = 'START' | 'SKIP' | 'RESCHEDULE' | 'CANCEL';

export interface PlannedWorkoutPlanSummary {
  planId: string;
  planVersionId: string;
  planVersionNumber: number;
  planName: string;
}

export interface PlannedWorkoutSessionSummary {
  planSessionId: string;
  weekNumber: number;
  dayNumber: number;
  sequenceNumber: number;
  name: string | null;
  focus: string | null;
  estimatedDurationMinutes: number | null;
  supervisionRequirement: WorkoutExecutionSupervisionRequirement | null;
}

export interface PlannedWorkoutOccurrence {
  occurrenceId: string;
  occurrenceVersion: number;
  studentId: string;
  status: PlannedWorkoutStatus;
  plannedStartAt: string;
  originalPlannedStartAt: string;
  rescheduledFromOccurrenceId: string | null;
  replacementOccurrenceId: string | null;
  plannedEndAt: string | null;
  timezone: string | null;
  localStart: string | null;
  localEnd: string | null;
  startUtcOffset: string | null;
  endUtcOffset: string | null;
  planSummary: PlannedWorkoutPlanSummary | null;
  sessionSummary: PlannedWorkoutSessionSummary | null;
  availableActions: PlannedWorkoutAction[];
  linkedAppointmentId: string | null;
  linkedAppointmentVersion: number | null;
}

export interface PlannedWorkoutOccurrencePage {
  items: PlannedWorkoutOccurrence[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface SkipPlannedWorkoutResponse {
  occurrenceId: string;
  status: 'SKIPPED';
  occurrenceVersion: number;
  replayed: boolean;
}

export interface WorkoutScheduleChangeDestination {
  localStart: string;
  localEnd: string;
  timezone: string;
  startUtcOffset: string;
  endUtcOffset: string;
}

export interface ReschedulePlannedWorkoutRequest {
  expectedOccurrenceVersion: number;
  destination: WorkoutScheduleChangeDestination;
  reason?: string | null;
  commandKey: string;
}

export interface CancelPlannedWorkoutRequest {
  expectedOccurrenceVersion: number;
  reason?: string | null;
  commandKey: string;
}

export interface PlannedWorkoutChangeResult {
  changeType: 'RESCHEDULE' | 'CANCEL';
  previousOccurrenceId: string;
  previousOccurrenceVersion: number;
  previousStatus: 'RESCHEDULED' | 'CANCELLED';
  originalPlannedStartAt: string;
  oldPlannedStartAt: string;
  oldPlannedEndAt: string | null;
  replacementOccurrenceId: string | null;
  replacementOccurrenceVersion: number | null;
  newPlannedStartAt: string | null;
  newPlannedEndAt: string | null;
  rescheduledFromOccurrenceId: string | null;
  effectiveAt: string;
  replayed: boolean;
}
