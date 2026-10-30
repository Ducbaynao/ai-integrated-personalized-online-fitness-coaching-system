export type AiRecommendationStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'EXPIRED' | 'APPLIED';
export type AiDecisionAuthority = 'STUDENT' | 'TRAINER';

export interface WorkoutProposalPrescription {
  exerciseVariationId: string;
  sequenceNumber: number;
  targetSets: number;
  targetRepsMin?: number;
  targetRepsMax?: number;
  targetLoad?: number;
  restSeconds?: number;
  durationSeconds?: number;
  instructions?: string;
}

export interface WorkoutProposalSession {
  weekNumber: number;
  dayNumber: number;
  sequenceNumber: number;
  name: string;
  focus?: string;
  estimatedDurationMinutes?: number;
  notes?: string;
  prescriptions: WorkoutProposalPrescription[];
}

export interface WorkoutProposalPayload {
  recommendationType: 'WORKOUT_PLAN_PROPOSAL';
  summary: string;
  reasoning: string[];
  usedData: string[];
  missingData: string[];
  limitations: string[];
  confidence: number;
  plan: {
    name: string;
    description?: string;
    durationWeeks: number;
    sessions: WorkoutProposalSession[];
  };
}

export interface WorkoutProposalPreferences {
  preferredDaysPerWeek?: number;
  daysPerWeek?: number;
  preferredSessionMinutes?: number;
  startDate?: string;
  equipment?: string[];
  allowBodyweight?: boolean;
  hasPhysicalLimitations?: boolean;
  limitationsConfirmed?: boolean;
  limitationsDescription?: string;
  limitations?: string;
  injuries?: string;
  note?: string;
  timezone?: string;
}

export interface WorkoutProposalRequest {
  studentId?: string;
  commandKey: string;
  preferences?: WorkoutProposalPreferences | Record<string, unknown>;
}

export interface WorkoutProposalResponse {
  recommendationId: string;
  aiRunId: string;
  status: AiRecommendationStatus;
  decisionAuthority: AiDecisionAuthority;
  title: string;
  explanation: string;
  expiresAt: string;
  createdAt: string;
  structuredPayload: WorkoutProposalPayload;
}

export interface AiRecommendationDetail {
  recommendationId: string;
  aiRunId: string;
  studentId: string;
  coachingPeriodId?: string;
  recommendationType: string;
  title: string;
  explanation: string;
  structuredPayload: WorkoutProposalPayload;
  originalPayload?: WorkoutProposalPayload;
  revision: number;
  isCustomized: boolean;
  lastModifiedBy?: string;
  status: AiRecommendationStatus;
  decisionAuthority: AiDecisionAuthority;
  targetEntityType?: string;
  targetEntityId?: string;
  decidedBy?: string;
  decidedAt?: string;
  decisionNote?: string;
  expiresAt: string;
  createdAt: string;
  updatedAt: string;
}

export interface UpdateRecommendationDraftRequest {
  expectedRevision: number;
  title?: string;
  explanation?: string;
  structuredPayload: WorkoutProposalPayload;
}

export interface UpdateRecommendationDraftResponse {
  recommendationId: string;
  revision: number;
  isCustomized: boolean;
  status: 'PENDING';
  title: string;
  explanation: string;
  updatedAt: string;
  structuredPayload: WorkoutProposalPayload;
  originalPayload: WorkoutProposalPayload;
}

export interface AcceptRecommendationRequest {
  commandKey: string;
  decisionNote?: string;
  expectedRevision?: number;
  expectedPlanId?: string;
  expectedPlanAggregateVersion?: number;
  expectedNoActivePlan?: boolean;
}

export interface AcceptRecommendationResponse {
  recommendationId: string;
  status: 'APPLIED';
  appliedBy: string;
  resultingEntityType: string;
  resultingEntityId: string;
  appliedAt: string;
  replayed: boolean;
}

export interface RejectRecommendationRequest {
  reason?: string;
}

export interface RejectRecommendationResponse {
  recommendationId: string;
  status: 'REJECTED';
  rejectedBy: string;
  rejectedAt: string;
  reason?: string;
}
