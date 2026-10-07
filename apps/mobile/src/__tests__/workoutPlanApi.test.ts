import { workoutPlanApi } from '@/services/workoutPlanApi';
import { request } from '@/services/apiClient';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

const sessions = [{
  weekNumber: 1, dayNumber: 1, sequenceNumber: 1, name: 'Upper A', focus: null,
  estimatedDurationMinutes: 45, notes: null, prescriptions: [{
    exerciseVariationId: '20000000-0000-4000-8000-000000000001', sequenceNumber: 1,
    targetSets: 3, targetRepsMin: 8, targetRepsMax: 12, targetLoad: 0,
    restSeconds: 60, durationSeconds: null, instructions: null,
  }],
}];

describe('workoutPlanApi', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

  it('scopes Trainer list/current reads to the selected Student', async () => {
    await workoutPlanApi.list(1, 20, 'student id');
    await workoutPlanApi.current('student id');
    expect(request).toHaveBeenNthCalledWith(1, '/workout-plans?studentId=student%20id&page=1&size=20', { method: 'GET' });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-plans/current?studentId=student%20id', { method: 'GET' });
  });

  it('sends complete draft snapshot and stable caller command identity', async () => {
    await workoutPlanApi.create('student-1', { name: 'Plan', description: null, sessions }, 'command-1');
    await workoutPlanApi.updateDraft('plan-1', 3, { name: 'Plan 2', description: 'Next', sessions }, 'command-2');
    expect(request).toHaveBeenNthCalledWith(1, '/workout-plans', { method: 'POST', body: { studentId: 'student-1', name: 'Plan', description: null, sessions, commandKey: 'command-1' } });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-plans/plan-1/draft', { method: 'PUT', body: { expectedVersion: 3, name: 'Plan 2', description: 'Next', sessions, commandKey: 'command-2' } });
  });

  it('uses optimistic version and command key for activate, publish, and lifecycle', async () => {
    await workoutPlanApi.activate('plan-1', 4, 'activate-key');
    await workoutPlanApi.publish('plan-1', 5, { reason: 'New phase', summary: null, sessions }, 'publish-key');
    await workoutPlanApi.transition('plan-1', 6, 'PAUSED', 'Recovery', 'transition-key');
    expect(request).toHaveBeenNthCalledWith(1, '/workout-plans/plan-1/activate', { method: 'POST', body: { expectedVersion: 4, commandKey: 'activate-key' } });
    expect(request).toHaveBeenNthCalledWith(2, '/workout-plans/plan-1/versions', { method: 'POST', body: { expectedVersion: 5, reason: 'New phase', summary: null, sessions, commandKey: 'publish-key' } });
    expect(request).toHaveBeenNthCalledWith(3, '/workout-plans/plan-1/transitions', { method: 'POST', body: { expectedVersion: 6, target: 'PAUSED', reason: 'Recovery', commandKey: 'transition-key' } });
  });
});
