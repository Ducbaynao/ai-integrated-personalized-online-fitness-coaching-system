import { request } from '@/services/apiClient';
import { workoutRecurrenceApi } from '@/services/workoutRecurrenceApi';
import { buildRecurrenceRequest, recurrencePreview } from '@/features/workout-recurrence/recurrenceForm';
import type { WorkoutSession } from '@/types/workoutPlan';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));

const session = (id: string, weekNumber = 1, supervisionRequirement: WorkoutSession['supervisionRequirement'] = 'SELF_ALLOWED'): WorkoutSession => ({
  id, weekNumber, dayNumber: 1, sequenceNumber: 1, name: id, focus: null,
  estimatedDurationMinutes: null, notes: null, supervisionRequirement, prescriptions: [],
});

beforeEach(() => jest.clearAllMocks());

it('posts exact version-bound multi-session JSON and preserves caller commandKey on retry', async () => {
  (request as jest.Mock).mockResolvedValue({ seriesId: 'series-1' });
  const body = buildRecurrenceRequest('plan-1', 'version-1', 7, '2026-11-02', 'Asia/Ho_Chi_Minh', [
    { session: session('session-1'), firstLocalStart: '2026-11-02T10:00', firstLocalEnd: '2026-11-02T11:00' },
    { session: session('session-2', 2), firstLocalStart: '2026-11-09T10:00', firstLocalEnd: '2026-11-09T11:00' },
  ], 'same-key');
  await workoutRecurrenceApi.confirm(body);
  await workoutRecurrenceApi.confirm(body);
  expect(request).toHaveBeenNthCalledWith(1, '/planned-workout-series', { method: 'POST', body });
  expect(request).toHaveBeenNthCalledWith(2, '/planned-workout-series', { method: 'POST', body });
  expect(JSON.parse(JSON.stringify(body))).toEqual({
    planId: 'plan-1', sourcePlanVersionId: 'version-1', expectedPlanAggregateVersion: 7,
    mondayAnchorDate: '2026-11-02', timezone: 'Asia/Ho_Chi_Minh', commandKey: 'same-key',
    items: [
      { planSessionId: 'session-1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1, firstLocalStart: '2026-11-02T10:00', firstLocalEnd: '2026-11-02T11:00' },
      { planSessionId: 'session-2', weekNumber: 2, dayNumber: 1, sequenceNumber: 1, firstLocalStart: '2026-11-09T10:00', firstLocalEnd: '2026-11-09T11:00' },
    ],
  });
});

it('uses Trainer endpoint for proposal and real series versions for decisions and stop', async () => {
  (request as jest.Mock).mockResolvedValue({});
  const body = buildRecurrenceRequest('plan', 'version', 2, '2026-11-02', 'Etc/UTC', [
    { session: session('s'), firstLocalStart: '2026-11-02T10:00', firstLocalEnd: '2026-11-02T11:00' },
  ], 'proposal-key');
  await workoutRecurrenceApi.propose('student-1', body);
  await workoutRecurrenceApi.decide('series-1', { expectedSeriesVersion: 4, decision: 'ACCEPT', commandKey: 'decision-key' });
  await workoutRecurrenceApi.stop('series-1', { expectedSeriesVersion: 5, commandKey: 'stop-key' });
  expect(request).toHaveBeenNthCalledWith(1, '/students/student-1/planned-workout-series', { method: 'POST', body });
  expect(request).toHaveBeenNthCalledWith(2, '/planned-workout-series/series-1/decisions', { method: 'POST',
    body: { expectedSeriesVersion: 4, decision: 'ACCEPT', commandKey: 'decision-key' } });
  expect(request).toHaveBeenNthCalledWith(3, '/planned-workout-series/series-1/stop', { method: 'POST',
    body: { expectedSeriesVersion: 5, commandKey: 'stop-key' } });
});

it('disables COACH_REQUIRED and flags DST gap/ambiguity without inventing offset', () => {
  const required = { session: session('required', 1, 'COACH_REQUIRED'), firstLocalStart: '2026-11-02T10:00', firstLocalEnd: '2026-11-02T11:00' };
  expect(recurrencePreview('2026-11-02', 'Etc/UTC', [required]).error).toBeTruthy();
  const gap = { session: { ...session('gap'), dayNumber: 7 }, firstLocalStart: '2027-03-28T02:30', firstLocalEnd: '2027-03-28T03:30' };
  expect(recurrencePreview('2027-03-22', 'Europe/Paris', [gap]).warnings).toHaveLength(1);
  const ambiguous = { session: { ...session('ambiguous'), dayNumber: 7 }, firstLocalStart: '2027-10-31T02:30', firstLocalEnd: '2027-10-31T03:30' };
  expect(recurrencePreview('2027-10-25', 'Europe/Paris', [ambiguous]).warnings).toHaveLength(1);
});
