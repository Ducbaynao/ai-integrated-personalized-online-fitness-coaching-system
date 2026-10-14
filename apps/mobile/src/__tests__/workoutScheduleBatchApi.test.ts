import { request } from '@/services/apiClient';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

it('serializes the exact Student direct batch body to the confirmed endpoint', async () => {
  (request as jest.Mock).mockResolvedValue({ batchId: 'batch-1', items: [] });
  const body = { planId: 'plan-id', sourcePlanVersionId: 'version-id', expectedPlanAggregateVersion: 4,
    weekAnchorDate: '2026-10-12', timezone: 'Asia/Ho_Chi_Minh', commandKey: 'stable-key',
    items: [{ clientItemId: 'item-id', planSessionId: 'session-id', weekNumber: 1, dayNumber: 1, sequenceNumber: 1,
      localStart: '2026-10-12T18:30', localEnd: '2026-10-12T19:30', startUtcOffset: '+07:00', endUtcOffset: '+07:00' }] };
  await workoutScheduleApi.confirmBatch(body);
  expect(request).toHaveBeenCalledWith('/planned-workout-batches', { method: 'POST', body });
  expect(JSON.parse(JSON.stringify((request as jest.Mock).mock.calls[0][1].body))).toEqual(body);
});
