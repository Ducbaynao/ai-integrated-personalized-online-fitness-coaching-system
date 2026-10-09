export type WorkoutExecutionStatus =
  | 'IN_PROGRESS'
  | 'COMPLETED'
  | 'PARTIALLY_COMPLETED'
  | 'ABORTED';

export type WorkoutExecutionSnapshotMode = 'FROZEN' | 'LEGACY_REFERENCE_ONLY';
export type WorkoutExecutionSupervisionRequirement =
  | 'SELF_PERFORMABLE'
  | 'COACH_OPTIONAL'
  | 'COACH_REQUIRED';
export type WorkoutExecutionExerciseState = 'ACTIVE' | 'ARCHIVED' | 'UNAVAILABLE';
export type WorkoutExecutionSetType =
  | 'WARMUP'
  | 'WORKING'
  | 'DROP'
  | 'FAILURE'
  | 'AMRAP'
  | 'COOLDOWN';
export type WorkoutExecutionSetCompletionStatus =
  | 'PLANNED'
  | 'COMPLETED'
  | 'SKIPPED'
  | 'FAILED';

export interface WorkoutExecutionSummary {
  executionId: string;
  studentId: string;
  plannedWorkoutId: string | null;
  planId: string | null;
  planVersionId: string | null;
  planSessionId: string | null;
  coachingPeriodId: string | null;
  snapshotMode: WorkoutExecutionSnapshotMode;
  plannedStartAt: string | null;
  originalPlannedStartAt: string | null;
  plannedEndAt: string | null;
  supervisionRequirement: WorkoutExecutionSupervisionRequirement | null;
  snapshotFrozenAt: string | null;
  sourceOccurrenceVersion: number | null;
  performedStartedAt: string;
  performedEndedAt: string | null;
  status: WorkoutExecutionStatus;
  overallRpe: number | null;
  sessionNote: string | null;
  version: number;
}

export interface WorkoutExecutionExercisePresentation {
  variationId: string;
  exerciseId: string | null;
  exerciseName: string | null;
  variationName: string | null;
  state: WorkoutExecutionExerciseState;
  canonicalExerciseId: string | null;
  canonicalExerciseName: string | null;
}

export interface WorkoutSetExecution {
  clientSetId: string;
  baselineSetNumber: number | null;
  setNumber: number;
  setType: WorkoutExecutionSetType;
  completionStatus: WorkoutExecutionSetCompletionStatus;
  repetitions: number | null;
  loadValue: number | null;
  loadUnitId: number | null;
  durationSeconds: number | null;
  distanceValue: number | null;
  distanceUnitId: number | null;
  rpe: number | null;
  rir: number | null;
  tempo: string | null;
  restAfterSeconds: number | null;
  note: string | null;
  completedAt: string | null;
}

export interface WorkoutExerciseExecution {
  exerciseExecutionId: string;
  sourcePrescriptionId: string | null;
  prescribedVariationId: string | null;
  prescribedVariationPresentation: WorkoutExecutionExercisePresentation | null;
  actualVariationId: string | null;
  actualVariationPresentation: WorkoutExecutionExercisePresentation | null;
  substitutionReason: string | null;
  sequence: number | null;
  baselineSetCount: number | null;
  targetRepsMin: number | null;
  targetRepsMax: number | null;
  targetLoad: number | null;
  loadUnitId: number | null;
  targetRpe: number | null;
  targetRir: number | null;
  restSeconds: number | null;
  tempo: string | null;
  durationSeconds: number | null;
  distanceValue: number | null;
  distanceUnitId: number | null;
  instructions: string | null;
  note: string | null;
  sets: WorkoutSetExecution[];
}

export interface WorkoutExecutionDetail {
  execution: WorkoutExecutionSummary;
  exercises: WorkoutExerciseExecution[];
}

export interface WorkoutExecutionPage {
  items: WorkoutExecutionSummary[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export interface UpsertWorkoutSetInput {
  exerciseExecutionId: string;
  baselineSetNumber: number | null;
  setNumber: number;
  setType: WorkoutExecutionSetType;
  completionStatus: WorkoutExecutionSetCompletionStatus;
  repetitions: number | null;
  loadValue: number | null;
  loadUnitId: number | null;
  durationSeconds: number | null;
  distanceValue: number | null;
  distanceUnitId: number | null;
  rpe: number | null;
  rir: number | null;
  tempo: string | null;
  restAfterSeconds: number | null;
  note: string | null;
}

export interface SubstituteWorkoutExerciseInput {
  actualExerciseVariationId: string;
  substitutionReason: string | null;
}

export interface FinishWorkoutExecutionInput {
  overallRpe: number | null;
  sessionNote: string | null;
}
