import { request } from '@/services/apiClient';
import type { CoachingPeriod } from '@/types/coaching';
import { loadEffectiveStudentPeriod } from './rescheduleQueries';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

const now = Date.parse('2026-11-19T00:00:00Z');
const future: CoachingPeriod = { id: 'future', mode: 'HUMAN_COACH', relationshipId: null, trainerId: null,
  startedAt: '2026-12-01T00:00:00Z', endedAt: null };
const current: CoachingPeriod = { id: 'current', mode: 'SELF_DIRECTED', relationshipId: null, trainerId: null,
  startedAt: '2026-11-01T00:00:00Z', endedAt: null };

beforeEach(() => jest.clearAllMocks());

it('reads beyond a full page of future periods to find the effective period', async () => {
  (request as jest.Mock)
    .mockResolvedValueOnce({ items: Array.from({ length: 100 }, (_, index) => ({ ...future, id: `future-${index}` })), page: 0, size: 100 })
    .mockResolvedValueOnce({ items: [current], page: 1, size: 100 });
  await expect(loadEffectiveStudentPeriod(now)).resolves.toEqual(current);
  expect(request).toHaveBeenNthCalledWith(1, '/coaching/periods/me?page=0&size=100', { method: 'GET' });
  expect(request).toHaveBeenNthCalledWith(2, '/coaching/periods/me?page=1&size=100', { method: 'GET' });
});

it('returns no effective period only after the history is exhausted', async () => {
  (request as jest.Mock).mockResolvedValue({ items: [future], page: 0, size: 100 });
  await expect(loadEffectiveStudentPeriod(now)).resolves.toBeNull();
  expect(request).toHaveBeenCalledTimes(1);
});
