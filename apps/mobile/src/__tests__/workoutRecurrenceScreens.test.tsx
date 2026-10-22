import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';
import { workoutRecurrenceApi } from '@/services/workoutRecurrenceApi';
import { StudentRecurrenceListScreen, TrainerRecurrenceListScreen } from '@/features/workout-recurrence/RecurrenceListScreen';
import { StudentRecurrenceDetailScreen, TrainerRecurrenceDetailScreen } from '@/features/workout-recurrence/RecurrenceDetailScreen';
import { useSeriesDetail, useSeriesPeriods, useStudentSeries, useTrainerSeries } from '@/features/workout-recurrence/workoutRecurrenceQueries';
import type { RecurrenceSeries } from '@/types/workoutRecurrence';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn(), useSharingSummary: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/services/workoutRecurrenceApi', () => ({ workoutRecurrenceApi: {
  decide: jest.fn(), stop: jest.fn(), withdraw: jest.fn(),
} }));
jest.mock('@/features/workout-recurrence/workoutRecurrenceQueries', () => {
  const actual = jest.requireActual('@/features/workout-recurrence/workoutRecurrenceQueries');
  return { ...actual, useStudentSeries: jest.fn(), useTrainerSeries: jest.fn(), useSeriesDetail: jest.fn(), useSeriesPeriods: jest.fn() };
});

const series: RecurrenceSeries = {
  seriesId: 'series-1', studentId: 'student-1', planId: 'plan-1', sourcePlanVersionId: 'version-1',
  sourcePlanVersionNumber: 2, observedPlanAggregateVersion: 4, planWeeks: 3,
  mondayAnchorDate: '2026-11-02', timezone: 'Asia/Ho_Chi_Minh', status: 'PENDING', seriesVersion: 5,
  initiatedBy: 'trainer-1', proposingTrainerId: 'trainer-1', coachingPeriodId: 'period-1', confirmedBy: null,
  createdAt: '2026-10-22T00:00:00Z', confirmedAt: null, stoppedAt: null, replayed: false,
  items: [{ planSessionId: 'session-1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1,
    firstLocalStart: '2026-11-02T10:00', firstLocalEnd: '2026-11-02T11:00' }],
};
const clients: QueryClient[] = [];
function render(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{node}</QueryClientProvider>); });
  return tree;
}
beforeEach(() => {
  jest.clearAllMocks();
  (createCommandKey as jest.Mock).mockReturnValue('stable-key');
  (useStudentSeries as jest.Mock).mockReturnValue({ data: { pages: [{ items: [series] }] }, isPending: false,
    isError: false, error: null, hasNextPage: false });
  (useTrainerSeries as jest.Mock).mockReturnValue({ data: { pages: [{ items: [series] }] }, isPending: false,
    isError: false, error: null, hasNextPage: false });
  (useSeriesDetail as jest.Mock).mockReturnValue({ data: series, isPending: false, isError: false, error: null });
  (useSeriesPeriods as jest.Mock).mockReturnValue({ data: { pages: [{ items: [] }] }, isPending: false,
    isError: false, error: null, hasNextPage: false });
  (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE' } }, isPending: false, isError: false });
  (useSharingSummary as jest.Mock).mockReturnValue({ data: { items: [{ dataScope: 'WORKOUT_PLAN', state: 'ALLOWED', accessLevel: 'VIEW' }] }, isPending: false, isError: false });
});
afterEach(() => clients.splice(0).forEach(client => client.clear()));

it('Student opens own series and decides with actual series version', async () => {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
  const list = render(<StudentRecurrenceListScreen />);
  renderer.act(() => list.root.findByProps({ accessibilityLabel: 'Xem series và từng kỳ' }).props.onPress());
  expect(mockPush).toHaveBeenCalledWith('/planned-workout-series/series-1');
  renderer.act(() => list.unmount());
  (workoutRecurrenceApi.decide as jest.Mock).mockResolvedValue({ ...series, status: 'ACTIVE', seriesVersion: 6 });
  const detail = render(<StudentRecurrenceDetailScreen seriesId="series-1" />);
  renderer.act(() => detail.root.findByProps({ testID: 'series-accept' }).props.onPress());
  await renderer.act(async () => { detail.root.findByProps({ testID: 'series-confirm-command' }).props.onPress(); await Promise.resolve(); });
  expect(workoutRecurrenceApi.decide).toHaveBeenCalledWith('series-1', {
    expectedSeriesVersion: 5, decision: 'ACCEPT', commandKey: 'stable-key',
  });
  renderer.act(() => detail.unmount());
});

it('Trainer VIEW can inspect own series but cannot create or withdraw; ended period conceals list', () => {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
  const list = render(<TrainerRecurrenceListScreen relationshipId="relationship-1" />);
  expect(list.root.findAllByProps({ testID: 'recurrence-new-proposal' })).toHaveLength(0);
  renderer.act(() => list.unmount());
  const detail = render(<TrainerRecurrenceDetailScreen relationshipId="relationship-1" seriesId="series-1" />);
  expect(detail.root.findAllByProps({ testID: 'series-withdraw' })).toHaveLength(0);
  renderer.act(() => detail.unmount());
  (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ENDED' } }, isPending: false, isError: false });
  const denied = render(<TrainerRecurrenceListScreen relationshipId="relationship-1" />);
  expect(denied.root.findAllByProps({ testID: 'recurrence-series-1' })).toHaveLength(0);
  renderer.act(() => denied.unmount());
});

it('Student can resolve an ambiguous period through explicit direct scheduling, without a fake occurrence', () => {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
  (useSeriesPeriods as jest.Mock).mockReturnValue({ data: { pages: [{ items: [{
    periodId: 'period-1', seriesId: 'series-1', blockIteration: 0, planSessionId: 'session-1',
    localStart: '2026-11-01T01:30:00', localEnd: '2026-11-01T02:30:00', timezone: 'America/New_York',
    startUtcOffset: null, endUtcOffset: null, plannedStartAt: null, plannedEndAt: null,
    outcome: 'DST_AMBIGUOUS', occurrenceId: null, processedAt: '2026-10-22T00:00:00Z',
  }] }] }, isPending: false, isError: false, error: null, hasNextPage: false });
  const detail = render(<StudentRecurrenceDetailScreen seriesId="series-1" />);
  expect(detail.root.findAllByProps({ accessibilityLabel: 'Xem buổi đã tạo' })).toHaveLength(0);
  renderer.act(() => detail.root.findByProps({ testID: 'series-manual-time-period-1' }).props.onPress());
  expect(mockPush).toHaveBeenCalledWith('/workout-plans/plan-1/schedule');
  renderer.act(() => detail.unmount());
});
