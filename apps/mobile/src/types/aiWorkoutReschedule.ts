export type WorkoutRescheduleStatus =
  | 'PENDING'
  | 'ACCEPTED'
  | 'REJECTED'
  | 'APPLIED'
  | 'EXPIRED'
  | 'CANCELLED';

export type WorkoutRescheduleDecisionAuthority = 'STUDENT' | 'TRAINER';

/** A command key identifies one intent. Reuse the same request on a retry. */
export interface WorkoutRescheduleApiRequest {
  occurrenceId: string;
  commandKey: string;
  studentId?: string;
  expectedOccurrenceVersion?: number;
  reason?: string;
  preferredTimeOfDay?: string;
}

// OpenAPI leaves this payload open. F05 AI schema is not the shared Mobile API schema.
export type WorkoutRescheduleStructuredPayload = Record<string, unknown>;

export interface WorkoutRescheduleApiResponse {
  recommendationId: string;
  runId: string;
  status: WorkoutRescheduleStatus;
  decisionAuthority: WorkoutRescheduleDecisionAuthority;
  title: string;
  explanation?: string | null;
  expiresAt: string;
  createdAt: string;
  structuredPayload: WorkoutRescheduleStructuredPayload;
}

export interface WorkoutRescheduleDetailApiResponse {
  id: string;
  recommendationId: string;
  runId: string;
  aiRunId: string;
  studentId: string;
  coachingPeriodId: string | null;
  recommendationType: string;
  title: string;
  explanation: string | null;
  status: WorkoutRescheduleStatus;
  decisionAuthority: WorkoutRescheduleDecisionAuthority;
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
  structuredPayload: WorkoutRescheduleStructuredPayload | null;
  originalPayload: WorkoutRescheduleStructuredPayload | null;
  sharedWithStudent: boolean;
  sharedRevision: number | null;
  sharedAt: string | null;
  sharedBy: string | null;
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
  changeResult?: Record<string, unknown> | null;
}

export interface RejectRescheduleApiRequest {
  decisionNote?: string;
}

export interface RejectRescheduleApiResponse {
  recommendationId: string;
  status: 'REJECTED';
  rejectedAt: string;
}

export type WorkoutRescheduleFailureKind =
  | 'VALIDATION'
  | 'AUTHENTICATION'
  | 'AUTHORITY'
  | 'NOT_FOUND'
  | 'CONFLICT'
  | 'TIMEOUT'
  | 'NETWORK'
  | 'CANCELLED'
  | 'OTHER';
