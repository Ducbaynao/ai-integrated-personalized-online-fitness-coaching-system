import { coachingApi, normalizeCoachingId } from '@/services/coachingApi';
import { request } from '@/services/apiClient';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

describe('coachingApi', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

  it('uses privacy-safe discovery and self-scoped collection endpoints', async () => {
    await coachingApi.getTrainers('  An  ', 2, 20);
    await coachingApi.lookupStudent(' student@example.com ');
    await coachingApi.getRelationships(1, 20);
    expect(request).toHaveBeenNthCalledWith(1, '/coaching/trainers?query=An&page=2&size=20', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(2, '/coaching/students/lookup?email=student%40example.com', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(3, '/coaching/relationships/me?page=1&size=20', { method: 'GET' });
  });

  it('loads append-only relationship history through the documented paginated endpoint', async () => {
    await coachingApi.getRelationshipHistory('r-1', 2, 20);
    expect(request).toHaveBeenCalledWith('/coaching/relationships/r-1/history?page=2&size=20', { method: 'GET' });
  });

  it('sends versions and the caller-provided operation identity for lifecycle retries', async () => {
    await coachingApi.relationshipAction('r-1', 'end', 7, '  Hoàn tất chương trình  ', 'command-1');
    await coachingApi.resumeAction('r-1', 'resume-1', 'accept', 8, 2, 'command-2');
    expect(request).toHaveBeenNthCalledWith(1, '/coaching/relationships/r-1/end', {
      method: 'POST', body: { expectedVersion: 7, commandKey: 'command-1', reason: 'Hoàn tất chương trình' },
    });
    expect(request).toHaveBeenNthCalledWith(2, '/coaching/relationships/r-1/resume-requests/resume-1/accept', {
      method: 'POST', body: { expectedRelationshipVersion: 8, expectedRequestVersion: 2, commandKey: 'command-2' },
    });
  });

  it('serializes sharing levels and historical boundaries without changing them', async () => {
    await coachingApi.setSharing('r-1', {
      dataScope: 'WORKOUT_HISTORY', decision: 'ALLOW', accessLevel: 'CONTRIBUTE',
      expectedPermissionVersion: 4, historyFrom: '2026-01-01T00:00:00Z',
      historyUntil: '2026-06-01T00:00:00Z', validUntil: '2026-12-01T00:00:00Z',
    }, 'command-3');
    expect(request).toHaveBeenCalledWith('/coaching/relationships/r-1/sharing-permissions', {
      method: 'POST', body: {
        dataScope: 'WORKOUT_HISTORY', decision: 'ALLOW', accessLevel: 'CONTRIBUTE',
        expectedPermissionVersion: 4, historyFrom: '2026-01-01T00:00:00Z',
        historyUntil: '2026-06-01T00:00:00Z', validUntil: '2026-12-01T00:00:00Z', commandKey: 'command-3',
      },
    });
  });

  it('normalizes route UUIDs and rejects malformed values locally', () => {
    expect(normalizeCoachingId(' 10000000-0000-4000-8000-00000000000A ')).toBe('10000000-0000-4000-8000-00000000000a');
    expect(normalizeCoachingId('relationship-1')).toBeNull();
  });
});
