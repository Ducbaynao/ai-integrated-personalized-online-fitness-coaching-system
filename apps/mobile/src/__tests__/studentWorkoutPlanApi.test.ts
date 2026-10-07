import { request } from '@/services/apiClient';
import { normalizeWorkoutPlanId, workoutPlanApi } from '@/services/workoutPlanApi';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));
const sessions = [{ weekNumber: 1, dayNumber: 1, sequenceNumber: 1, name: 'Upper', focus: null, estimatedDurationMinutes: 45, notes: null, prescriptions: [{ exerciseVariationId: '20000000-0000-4000-8000-000000000001', sequenceNumber: 1, targetSets: 3, targetRepsMin: 0, targetRepsMax: 0, targetLoad: 0, restSeconds: 0, durationSeconds: 0, instructions: null }] }];

describe('Student workoutPlanApi', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });
  it('uses authoritative self current and list endpoints', async () => {
    await workoutPlanApi.current(); await workoutPlanApi.list(2, 20);
    expect(request).toHaveBeenNthCalledWith(1, '/workout-plans/current', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-plans?page=2&size=20', { method: 'GET' });
  });
  it('serializes complete draft snapshots with expectedVersion and stable keys', async () => {
    await workoutPlanApi.create('student-1', { name: 'Plan', description: null, sessions }, 'create-key');
    await workoutPlanApi.updateDraft('plan-1', 3, { name: 'Plan 2', description: null, sessions }, 'update-key');
    expect(request).toHaveBeenNthCalledWith(1, '/workout-plans', { method: 'POST', body: { studentId: 'student-1', name: 'Plan', description: null, sessions, commandKey: 'create-key' } });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-plans/plan-1/draft', { method: 'PUT', body: { expectedVersion: 3, name: 'Plan 2', description: null, sessions, commandKey: 'update-key' } });
  });
  it('serializes activation, lifecycle, significant version, and successor commands', async () => {
    await workoutPlanApi.activate('plan-1', 1, 'a');
    await workoutPlanApi.transition('plan-1', 2, 'PAUSED', null, 'b');
    await workoutPlanApi.publish('plan-1', 3, { reason: 'Phase 2', summary: 'Progression', sessions }, 'c');
    await workoutPlanApi.successor('plan-1', 'version-1', 'My successor', 'd');
    expect(request).toHaveBeenNthCalledWith(1, '/workout-plans/plan-1/activate', { method: 'POST', body: { expectedVersion: 1, commandKey: 'a' } });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-plans/plan-1/transitions', { method: 'POST', body: { expectedVersion: 2, target: 'PAUSED', reason: null, commandKey: 'b' } });
    expect(request).toHaveBeenNthCalledWith(3, '/workout-plans/plan-1/versions', { method: 'POST', body: { expectedVersion: 3, reason: 'Phase 2', summary: 'Progression', sessions, commandKey: 'c' } });
    expect(request).toHaveBeenNthCalledWith(4, '/workout-plans/successors', { method: 'POST', body: { sourcePlanId: 'plan-1', sourceVersionId: 'version-1', name: 'My successor', commandKey: 'd' } });
  });
  it('normalizes UUID route params without issuing requests for malformed values', () => {
    expect(normalizeWorkoutPlanId(' 10000000-0000-4000-8000-00000000000A ')).toBe('10000000-0000-4000-8000-00000000000a');
    expect(normalizeWorkoutPlanId('plan-1')).toBeNull();
  });
});
