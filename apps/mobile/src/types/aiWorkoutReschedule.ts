export type RescheduleRecommendationStatus =
  | 'PENDING'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'APPLIED'
  | 'EXPIRED'
  | 'CANCELLED';

export type RescheduleDecisionAuthority = 'STUDENT' | 'TRAINER';

export type SupervisionRequirement = 'SELF_PERFORMABLE' | 'COACH_OPTIONAL' | 'COACH_REQUIRED';

export interface F05RescheduleDestination {
  localStart: string;
  localEnd: string;
  timezone: string;
  startUtcOffset: string;
  endUtcOffset: string;
  proposedStartAt: string;
  proposedEndAt: string;
}

export interface F05RescheduleDetails {
  occurrenceId: string;
  planSessionId?: string | null;
  sessionName?: string | null;
  previousPlannedStartAt: string;
  originalPlannedStartAt?: string | null;
  supervisionRequirement: SupervisionRequirement;
  destination: F05RescheduleDestination;
}

export interface F05WorkoutReschedulePayload {
  recommendationType: 'WORKOUT_RESCHEDULE_PROPOSAL';
  summary: string;
  reasoning: string[];
  usedData: string[];
  missingData: string[];
  limitations?: string[];
  confidence: number;
  reschedule: F05RescheduleDetails;
}

export interface WorkoutRescheduleApiRequest {
  studentId?: string;
  occurrenceId: string;
  expectedOccurrenceVersion?: number;
  commandKey: string;
  reason?: string;
  preferredTimeOfDay?: 'MORNING' | 'AFTERNOON' | 'EVENING';
}

export interface WorkoutRescheduleApiResponse {
  recommendationId: string;
  runId: string;
  status: RescheduleRecommendationStatus;
  decisionAuthority: RescheduleDecisionAuthority;
  title: string;
  explanation: string;
  expiresAt: string;
  createdAt: string;
  structuredPayload: F05WorkoutReschedulePayload;
}

export interface AiWorkoutRescheduleDetail {
  recommendationId: string;
  aiRunId: string;
  studentId: string;
  coachingPeriodId?: string | null;
  recommendationType: string;
  title: string;
  explanation: string;
  status: RescheduleRecommendationStatus;
  decisionAuthority: RescheduleDecisionAuthority;
  targetEntityType: string;
  targetEntityId: string;
  decidedBy?: string | null;
  decidedAt?: string | null;
  decisionNote?: string | null;
  expiresAt: string;
  revision: number;
  isCustomized: boolean;
  lastModifiedBy?: string | null;
  createdAt: string;
  updatedAt: string;
  structuredPayload: F05WorkoutReschedulePayload;
  originalPayload?: F05WorkoutReschedulePayload | null;
  sharedWithStudent?: boolean;
  sharedRevision?: number | null;
  sharedAt?: string | null;
  sharedBy?: string | null;
}

export interface AcceptRescheduleApiRequest {
  commandKey: string;
  decisionNote?: string;
  expectedRevision?: number;
  expectedOccurrenceVersion?: number;
  expectedAppointmentVersion?: number;
  expectedAppointmentId?: string;
}

export interface AcceptRescheduleApiResponse {
  recommendationId: string;
  status: 'APPLIED';
  appliedBy: string;
  resultingEntityType: string;
  resultingEntityId: string;
  appliedAt: string;
  replayed: boolean;
  changeResult?: {
    changeType: string;
    sourceOccurrenceId: string;
    replacementOccurrenceId?: string | null;
    affectedAppointmentsCount?: number;
  } | null;
}

export interface RejectRescheduleApiRequest {
  decisionNote?: string;
}

export interface RejectRescheduleApiResponse {
  recommendationId: string;
  status: 'REJECTED';
  rejectedAt: string;
}
