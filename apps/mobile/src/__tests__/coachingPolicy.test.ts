import { availableCoachingActions, canEditSharing } from '@/features/coaching/coachingPolicy';
import { CoachingRelationship, CoachingResumeRequest } from '@/types/coaching';

const relationship: CoachingRelationship = {
  id: 'relationship-1', studentId: 'student-1', trainerId: 'trainer-1',
  counterparty: { userId: 'trainer-1', displayName: 'Huấn luyện viên' },
  status: 'PENDING', direction: 'OUTGOING', requestedBy: 'student-1',
  requestedAt: '2026-10-06T00:00:00Z', acceptedAt: null, startedAt: null,
  endedAt: null, version: 1,
};

const resume: CoachingResumeRequest = {
  id: 'resume-1', relationshipId: relationship.id, requestedBy: 'student-1',
  status: 'PENDING', version: 1, requestedAt: '2026-10-06T00:00:00Z',
  decidedBy: null, decidedAt: null,
};

describe('coaching lifecycle presentation policy', () => {
  it('allows only the initiator to cancel a pending relationship', () => {
    expect(availableCoachingActions(relationship, 'student-1', null).relationship).toEqual(['cancel']);
    expect(availableCoachingActions(relationship, 'trainer-1', null).relationship).toEqual(['accept', 'reject']);
  });

  it('offers pause/end for active relationships and two-party resume while paused', () => {
    const active = { ...relationship, status: 'ACTIVE' as const };
    expect(availableCoachingActions(active, 'student-1', null)).toEqual({
      relationship: ['pause', 'end'], resume: [], canRequestResume: false,
    });
    const paused = { ...relationship, status: 'PAUSED' as const };
    expect(availableCoachingActions(paused, 'trainer-1', null).canRequestResume).toBe(true);
    expect(availableCoachingActions(paused, 'student-1', resume).resume).toEqual(['cancel']);
    expect(availableCoachingActions(paused, 'trainer-1', resume).resume).toEqual(['accept', 'reject']);
  });

  it('does not expose lifecycle commands after terminal states', () => {
    expect(availableCoachingActions({ ...relationship, status: 'ENDED' }, 'student-1', null)).toEqual({
      relationship: [], resume: [], canRequestResume: false,
    });
  });

  it('allows only the Student owner to edit sharing in active or paused states', () => {
    expect(canEditSharing({ ...relationship, status: 'ACTIVE' }, 'student-1')).toBe(true);
    expect(canEditSharing({ ...relationship, status: 'PAUSED' }, 'student-1')).toBe(true);
    expect(canEditSharing({ ...relationship, status: 'ACTIVE' }, 'trainer-1')).toBe(false);
    expect(canEditSharing({ ...relationship, status: 'ENDED' }, 'student-1')).toBe(false);
  });
});
