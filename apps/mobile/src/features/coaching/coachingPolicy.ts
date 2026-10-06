import { CoachingRelationship, CoachingResumeRequest, RelationshipAction, ResumeAction } from '@/types/coaching';

export interface AvailableCoachingActions {
  relationship: RelationshipAction[];
  resume: ResumeAction[];
  canRequestResume: boolean;
}

export function availableCoachingActions(
  relationship: CoachingRelationship,
  actorId: string,
  resume: CoachingResumeRequest | null
): AvailableCoachingActions {
  if (relationship.status === 'PENDING') {
    return relationship.requestedBy === actorId
      ? { relationship: ['cancel'], resume: [], canRequestResume: false }
      : { relationship: ['accept', 'reject'], resume: [], canRequestResume: false };
  }
  if (relationship.status === 'ACTIVE') {
    return { relationship: ['pause', 'end'], resume: [], canRequestResume: false };
  }
  if (relationship.status === 'PAUSED') {
    if (resume?.status === 'PENDING') {
      return {
        relationship: ['end'],
        resume: resume.requestedBy === actorId ? ['cancel'] : ['accept', 'reject'],
        canRequestResume: false,
      };
    }
    return { relationship: ['end'], resume: [], canRequestResume: true };
  }
  return { relationship: [], resume: [], canRequestResume: false };
}

export function canEditSharing(relationship: CoachingRelationship, actorId: string): boolean {
  return relationship.studentId === actorId && ['ACTIVE', 'PAUSED'].includes(relationship.status);
}

