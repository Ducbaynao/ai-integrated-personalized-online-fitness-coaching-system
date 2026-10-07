export type WorkoutPlanStatus = 'DRAFT' | 'ACTIVE' | 'PAUSED' | 'COMPLETED' | 'ARCHIVED';
export type WorkoutPlanOwnerType = 'STUDENT' | 'TRAINER';
export type WorkoutPlanReadContext = 'CURRENT' | 'HISTORICAL';
export type WorkoutExerciseState = 'ACTIVE' | 'ARCHIVED' | 'UNAVAILABLE';

export interface WorkoutPlanSummary {
  id: string;
  studentId: string;
  fitnessGoalId: string | null;
  coachingPeriodId: string | null;
  name: string;
  description: string | null;
  source: string;
  status: WorkoutPlanStatus;
  aggregateVersion: number;
  decisionOwnerType: WorkoutPlanOwnerType;
  decisionOwnerId: string;
  basedOnPlanId: string | null;
  basedOnPlanVersionId: string | null;
  createdAt: string;
  updatedAt: string;
  archivedAt: string | null;
  currentVersionId: string | null;
  currentVersionNumber: number | null;
  currentVersionEffectiveFrom: string | null;
  currentVersionLockedAt: string | null;
  readContext: WorkoutPlanReadContext;
}

export interface WorkoutPlanVersionSummary {
  id: string;
  planId: string;
  versionNumber: number;
  effectiveFrom: string;
  effectiveUntil: string | null;
  changeLevel: 'INITIAL' | 'MAJOR' | 'AI_ACCEPTED';
  changeReason: string | null;
  changeSummary: string | null;
  createdBy: string;
  createdAt: string;
  lockedAt: string | null;
  current: boolean;
  readContext: WorkoutPlanReadContext;
}

export interface WorkoutExercisePresentation {
  variationId: string;
  exerciseId: string | null;
  exerciseName: string | null;
  variationName: string | null;
  state: WorkoutExerciseState;
  canonicalExerciseId: string | null;
  canonicalExerciseName: string | null;
}

export interface WorkoutPrescription {
  id: string;
  exerciseVariationId: string;
  sequenceNumber: number;
  targetSets: number | null;
  targetRepsMin: number | null;
  targetRepsMax: number | null;
  targetLoad: number | null;
  restSeconds: number | null;
  durationSeconds: number | null;
  instructions: string | null;
  exercise: WorkoutExercisePresentation;
}

export interface WorkoutSession {
  id: string;
  weekNumber: number;
  dayNumber: number;
  sequenceNumber: number;
  name: string;
  focus: string | null;
  estimatedDurationMinutes: number | null;
  notes: string | null;
  prescriptions: WorkoutPrescription[];
}

export interface WorkoutPlanDetail { plan: WorkoutPlanSummary; currentVersion: WorkoutPlanVersionSummary | null }
export interface WorkoutPlanVersionDetail { version: WorkoutPlanVersionSummary; sessions: WorkoutSession[] }
export interface WorkoutPage<T> { items: T[]; page: number; size: number; totalItems: number; totalPages: number }

export interface WorkoutPrescriptionInput {
  exerciseVariationId: string;
  sequenceNumber: number;
  targetSets: number | null;
  targetRepsMin: number | null;
  targetRepsMax: number | null;
  targetLoad: number | null;
  restSeconds: number | null;
  durationSeconds: number | null;
  instructions: string | null;
}

export interface WorkoutSessionInput {
  weekNumber: number;
  dayNumber: number;
  sequenceNumber: number;
  name: string;
  focus: string | null;
  estimatedDurationMinutes: number | null;
  notes: string | null;
  prescriptions: WorkoutPrescriptionInput[];
}

export interface WorkoutDraftInput { name: string; description: string | null; sessions: WorkoutSessionInput[] }
export interface PublishWorkoutVersionInput { reason: string; summary: string | null; sessions: WorkoutSessionInput[] }
export interface WorkoutPlanCommandResponse {
  planId: string;
  planVersionId: string | null;
  planVersionNumber: number | null;
  status: WorkoutPlanStatus;
  aggregateVersion: number;
  effectiveAt: string;
  replayed: boolean;
}
