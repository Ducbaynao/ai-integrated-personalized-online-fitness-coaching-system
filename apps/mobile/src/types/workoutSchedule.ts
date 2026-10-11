import type { WorkoutExecutionSupervisionRequirement } from './workoutExecution';

export type PlannedWorkoutStatus =
  | 'SCHEDULED' | 'COMPLETED' | 'ATTEMPTED' | 'MISSED'
  | 'SKIPPED' | 'RESCHEDULED' | 'CANCELLED';
export type PlannedWorkoutAction = 'START' | 'SKIP';

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
  plannedEndAt: string | null;
  timezone: string | null;
  localStart: string | null;
  localEnd: string | null;
  startUtcOffset: string | null;
  endUtcOffset: string | null;
  planSummary: PlannedWorkoutPlanSummary | null;
  sessionSummary: PlannedWorkoutSessionSummary | null;
  availableActions: PlannedWorkoutAction[];
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
