export type AdjustmentRecommendationStatus =
  | 'PENDING'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'EXPIRED'
  | 'CANCELLED'
  | 'APPLIED';

export type AdjustmentChangeType = 'EXERCISE_SUBSTITUTION' | 'LOAD_ADJUSTMENT' | 'VOLUME_ADJUSTMENT';

export type AdjustmentTargetScope = 'ONE_MATERIALIZED_OCCURRENCE' | 'TEMPLATE_OR_FUTURE_OCCURRENCES';

export type AdjustmentDecisionAuthority = 'STUDENT' | 'TRAINER';

export interface AdjustmentTargetRef {
  scope: AdjustmentTargetScope;
  plannedWorkoutId?: string;
  plannedSessionExerciseId?: string;
  planId?: string;
  planVersionNumber?: number;
  dayNumber?: number;
  exercisePrescriptionId?: string;
}

export interface WorkoutAdjustmentApiRequest {
  studentId?: string;
  commandKey: string;
  scope: AdjustmentTargetScope;
  target: AdjustmentTargetRef;
  requestedChangeType: AdjustmentChangeType;
  userReason?: string;
  physicalLimitations?: string[];
  equipmentAvailable?: string[];
  allowLoadIncrease?: boolean;
}

export interface SubstitutionBefore {
  exerciseVariationId?: string;
  exerciseName?: string;
  movementPattern?: string;
  equipmentType?: string;
}

export interface SubstitutionAfter {
  replacementVariationId?: string;
  replacementExerciseName?: string;
  movementPattern?: string;
  equipmentType?: string;
  substitutionCategory?: string;
}

export interface LoadBefore {
  targetLoad?: number | null;
  loadUnit?: string | null;
  loadUnitId?: number | null;
}

export interface LoadAfter {
  targetLoad?: number | null;
  loadUnit?: string | null;
  loadUnitId?: number | null;
  direction?: 'INCREASE' | 'DECREASE' | 'MAINTAIN' | string;
  deltaLoad?: number | null;
  deltaPercent?: number | null;
}

export interface VolumeBefore {
  targetSets?: number | null;
  targetRepsMin?: number | null;
  targetRepsMax?: number | null;
  durationSeconds?: number | null;
}

export interface VolumeAfter {
  targetSets?: number | null;
  targetRepsMin?: number | null;
  targetRepsMax?: number | null;
  durationSeconds?: number | null;
  deltaSets?: number | null;
  deltaRepsMin?: number | null;
  deltaRepsMax?: number | null;
  deltaDurationSeconds?: number | null;
}

export interface AdjustmentEvidence {
  movementPatternMatch?: boolean | null;
  equipmentAvailable?: boolean | null;
  rpeWindowCoverage?: string | null;
  continuityState?: string | null;
  knowledgeCitations?: string[];
}

export interface AdjustmentVersionGuards {
  expectedOccurrenceVersion?: number | null;
  expectedPlanAggregateVersion?: number | null;
}

export interface TypedChange {
  changeId: string;
  changeType: AdjustmentChangeType;
  scope: AdjustmentTargetScope;
  targetRef: AdjustmentTargetRef;
  before: SubstitutionBefore | LoadBefore | VolumeBefore | Record<string, unknown>;
  after: SubstitutionAfter | LoadAfter | VolumeAfter | Record<string, unknown>;
  reason: string;
  evidence?: AdjustmentEvidence;
  versionGuards?: AdjustmentVersionGuards;
}

export interface F03WorkoutAdjustmentPayload {
  recommendationType: string;
  summary: string;
  reasoning: string[];
  usedData: string[];
  missingData: string[];
  limitations: string[];
  confidence: number | null;
  changes: TypedChange[];
}

export interface WorkoutAdjustmentApiResponse {
  recommendationId: string;
  runId: string;
  status: AdjustmentRecommendationStatus;
  decisionAuthority: AdjustmentDecisionAuthority;
  title: string;
  explanation: string;
  expiresAt: string;
  createdAt: string;
  structuredPayload: F03WorkoutAdjustmentPayload;
}

export interface AcceptAdjustmentApiRequest {
  commandKey?: string;
  decisionNote?: string;
  expectedRevision?: number;
  expectedTargetVersion?: number;
}

export interface AcceptAdjustmentApiResponse {
  recommendationId: string;
  status: 'APPLIED';
  appliedBy: string;
  resultingEntityType: 'WORKOUT_OCCURRENCE' | 'WORKOUT_PLAN';
  resultingEntityId: string;
  appliedAt: string;
  replayed: boolean;
}

export interface RejectAdjustmentApiRequest {
  reason?: string;
}

export interface RejectAdjustmentApiResponse {
  recommendationId: string;
  status: 'REJECTED';
  rejectedBy: string;
  reason?: string;
  rejectedAt: string;
}

export interface AiWorkoutAdjustmentDetail {
  id: string;
  aiRunId: string;
  studentId: string;
  coachingPeriodId: string | null;
  recommendationType: string;
  title: string;
  explanation: string;
  status: AdjustmentRecommendationStatus;
  decisionAuthority: AdjustmentDecisionAuthority;
  targetEntityType: string;
  targetEntityId: string;
  decidedBy: string | null;
  decidedAt: string | null;
  decisionNote: string | null;
  expiresAt: string | null;
  revision: number;
  isCustomized: boolean;
  lastModifiedBy: string | null;
  createdAt: string;
  updatedAt: string;
  payload: F03WorkoutAdjustmentPayload;
  originalPayload: F03WorkoutAdjustmentPayload | null;
}
