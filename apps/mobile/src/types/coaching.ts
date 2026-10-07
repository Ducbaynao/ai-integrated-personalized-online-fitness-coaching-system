export type CoachingStatus = 'PENDING' | 'ACTIVE' | 'PAUSED' | 'ENDED' | 'REJECTED' | 'CANCELLED';
export type CoachingDirection = 'INCOMING' | 'OUTGOING';
export type CoachingMode = 'HUMAN_COACH' | 'SELF_DIRECTED';
export type ResumeStatus = 'PENDING' | 'ACCEPTED' | 'REJECTED' | 'CANCELLED';
export type DataAccessLevel = 'VIEW' | 'CONTRIBUTE' | 'MANAGE';
export type SharingDecision = 'ALLOW' | 'DENY';
export type PermissionState = 'NOT_CONFIGURED' | 'ALLOWED' | 'DENIED' | 'EXPIRED' | 'REVOKED';
export type DataScope =
  | 'FITNESS_GOAL'
  | 'WORKOUT_PLAN'
  | 'WORKOUT_HISTORY'
  | 'WORKOUT_PLAN_HISTORY'
  | 'BODY_METRICS'
  | 'PROGRESS_PHOTOS'
  | 'NUTRITION_LOGS'
  | 'AI_RECOMMENDATIONS';

export interface Page<T> { items: T[]; page: number; size: number }
export interface ParticipantSummary { userId: string; displayName: string }
export interface TrainerDirectoryItem { trainerId: string; displayName: string }
export interface StudentLookup { studentId: string; displayName: string }

export interface CoachingRelationship {
  id: string;
  studentId: string;
  trainerId: string;
  counterparty: ParticipantSummary;
  status: CoachingStatus;
  direction: CoachingDirection;
  requestedBy: string;
  requestedAt: string;
  acceptedAt: string | null;
  startedAt: string | null;
  endedAt: string | null;
  version: number;
}

export interface CoachingResumeRequest {
  id: string;
  relationshipId: string;
  requestedBy: string;
  status: ResumeStatus;
  version: number;
  requestedAt: string;
  decidedBy: string | null;
  decidedAt: string | null;
}

export interface CoachingPeriod {
  id: string;
  mode: CoachingMode;
  relationshipId: string | null;
  trainerId: string | null;
  startedAt: string;
  endedAt: string | null;
}

export interface CoachingStatusEvent {
  id: string;
  relationshipId: string;
  fromStatus: CoachingStatus | null;
  toStatus: CoachingStatus;
  changedBy: string | null;
  reason: string | null;
  changedAt: string;
}

export interface CoachingOutcome {
  relationship: CoachingRelationship | null;
  resume: CoachingResumeRequest | null;
  currentPeriod: CoachingPeriod | null;
}

export interface PermissionSummaryItem {
  dataScope: DataScope;
  state: PermissionState;
  decision: SharingDecision | null;
  accessLevel: DataAccessLevel | null;
  permissionId: string | null;
  version: number | null;
  historyFrom: string | null;
  historyUntil: string | null;
  validFrom: string | null;
  validUntil: string | null;
}

export interface PermissionSummary {
  relationshipId: string;
  relationshipStatus: CoachingStatus;
  evaluatedAt: string;
  items: PermissionSummaryItem[];
}

export interface SharingGrantInput {
  dataScope: DataScope;
  decision: SharingDecision;
  accessLevel: DataAccessLevel;
  expectedPermissionVersion?: number;
  historyFrom?: string;
  historyUntil?: string;
  validUntil?: string;
}

export type RelationshipAction = 'accept' | 'reject' | 'cancel' | 'pause' | 'end';
export type ResumeAction = 'accept' | 'reject' | 'cancel';

