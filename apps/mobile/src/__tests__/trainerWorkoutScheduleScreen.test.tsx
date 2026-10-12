import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { useTrainerWorkoutSchedule, workoutScheduleQueryKeys } from '@/features/workout-schedule/workoutScheduleQueries';
import { TrainerWorkoutScheduleScreen } from '@/features/workout-schedule/TrainerWorkoutScheduleScreen';
import { calendarWindow } from '@/features/workout-schedule/scheduleDisplay';

const mockBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ back: mockBack }),
  useFocusEffect: (callback: () => void | (() => void)) => jest.requireActual<typeof import('react')>('react').useEffect(callback, [callback]),
}));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn(), useSharingSummary: jest.fn() }));
jest.mock('@/features/workout-schedule/workoutScheduleQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule/workoutScheduleQueries');
  return { ...actual, useTrainerWorkoutSchedule: jest.fn() };
});

const legacy = {
  occurrenceId: 'occurrence-legacy', occurrenceVersion: 4, studentId: 'student-1', status: 'SCHEDULED',
  plannedStartAt: '2026-10-12T03:00:00Z', originalPlannedStartAt: '2026-10-12T03:00:00Z', plannedEndAt: null,
  timezone: null, localStart: null, localEnd: null, startUtcOffset: null, endUtcOffset: null,
  planSummary: null, sessionSummary: null, availableActions: ['START', 'SKIP'],
};
const detailed = {
  ...legacy, occurrenceId: 'occurrence-new', plannedStartAt: '2026-10-13T03:00:00Z',
  plannedEndAt: '2026-10-13T04:00:00Z', timezone: 'Asia/Ho_Chi_Minh',
  localStart: '2026-10-13T10:00', localEnd: '2026-10-13T11:00', startUtcOffset: '+07:00', endUtcOffset: '+07:00',
  planSummary: { planId: 'plan-1', planVersionId: 'version-1', planVersionNumber: 2, planName: 'Sức mạnh' },
  sessionSummary: { planSessionId: 'session-1', weekNumber: 1, dayNumber: 2, sequenceNumber: 1,
    name: 'Upper body', focus: 'Strength', estimatedDurationMinutes: null, supervisionRequirement: 'SELF_PERFORMABLE' },
};

let relationshipResult: any;
let sharingResult: any;
let calendarResult: any;
const clients: QueryClient[] = [];

function setup(level = 'VIEW') {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
  relationshipResult = { data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE', counterparty: { displayName: 'An' } } },
    isPending: false, isError: false, refetch: jest.fn() };
  relationshipResult.refetch.mockResolvedValue(relationshipResult);
  sharingResult = { data: { items: [{ dataScope: 'WORKOUT_PLAN', state: 'ALLOWED', accessLevel: level }] },
    isPending: false, isError: false, refetch: jest.fn() };
  sharingResult.refetch.mockResolvedValue(sharingResult);
  calendarResult = { data: { pages: [{ items: [legacy, detailed], page: 0, size: 20, totalItems: 2, totalPages: 1 }] },
    isPending: false, isFetching: false, isFetchingNextPage: false, isError: false, error: null,
    hasNextPage: false, fetchNextPage: jest.fn(), refetch: jest.fn() };
  (useRelationshipDetail as jest.Mock).mockImplementation(() => relationshipResult);
  (useSharingSummary as jest.Mock).mockImplementation(() => sharingResult);
  (useTrainerWorkoutSchedule as jest.Mock).mockImplementation(() => calendarResult);
}

async function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 } } });
  clients.push(client);
  const remove = jest.spyOn(client, 'removeQueries');
  let tree!: renderer.ReactTestRenderer;
  await renderer.act(async () => {
    tree = renderer.create(<QueryClientProvider client={client}><TrainerWorkoutScheduleScreen relationshipId="relationship-1" /></QueryClientProvider>);
    await Promise.resolve();
  });
  return { tree, remove };
}

describe('Trainer read-only Planned Workout calendar', () => {
  beforeEach(() => { jest.clearAllMocks(); setup(); });
  afterEach(() => clients.splice(0).forEach((client) => client.clear()));

  it('VIEW shows sorted API occurrences, legacy nulls and explicit timezone but never execution actions', async () => {
    const { tree } = await render();
    const output = JSON.stringify(tree.toJSON());
    expect(output.indexOf('occurrence-legacy')).toBeLessThan(output.indexOf('occurrence-new'));
    expect(output).toContain('Chưa có tên kế hoạch trong bản ghi cũ');
    expect(output).toContain('múi giờ của bản ghi cũ chưa xác định');
    expect(output).toContain('Asia/Ho_Chi_Minh');
    expect(output).toContain('UTC+07:00');
    expect(output).toContain('Upper body');
    expect(output).not.toContain('Bắt đầu tập');
    expect(output).not.toContain('Bỏ qua buổi này');
    expect(tree.root.findAllByProps({ testID: 'schedule-start-occurrence-legacy' })).toHaveLength(0);
    expect(useTrainerWorkoutSchedule).toHaveBeenLastCalledWith('trainer-1', 'student-1', expect.any(String), expect.any(String), true);
    renderer.act(() => tree.unmount());
  });

  it.each([403, 404])('conceals cached count and occurrences when grant is revoked or backend returns %i', async (status) => {
    const { tree, remove } = await render();
    expect(JSON.stringify(tree.toJSON())).toContain('occurrence-legacy');
    sharingResult.data.items[0].state = 'DENIED';
    await renderer.act(async () => { tree.update(<QueryClientProvider client={clients.at(-1)!}><TrainerWorkoutScheduleScreen relationshipId="relationship-1" /></QueryClientProvider>); });
    expect(JSON.stringify(tree.toJSON())).not.toContain('occurrence-legacy');
    expect(JSON.stringify(tree.toJSON())).not.toContain('Có 2 buổi');
    expect(remove).toHaveBeenCalledWith({ queryKey: workoutScheduleQueryKeys.trainer('trainer-1', 'student-1') });
    sharingResult.data.items[0].state = 'ALLOWED';
    calendarResult.isError = true; calendarResult.error = new ApiError(status, 'concealed');
    await renderer.act(async () => { tree.update(<QueryClientProvider client={clients.at(-1)!}><TrainerWorkoutScheduleScreen relationshipId="relationship-1" /></QueryClientProvider>); });
    expect(JSON.stringify(tree.toJSON())).not.toContain('occurrence-legacy');
    renderer.act(() => tree.unmount());
  });

  it('changes a 30-day window and loads the next backend page without client-side action sorting', async () => {
    calendarResult.hasNextPage = true;
    const { tree } = await render();
    const first = (useTrainerWorkoutSchedule as jest.Mock).mock.calls.at(-1)!;
    expect(Date.parse(first[3]) - Date.parse(first[2])).toBe(30 * 24 * 60 * 60 * 1000);
    expect(Date.parse(first[3]) - Date.parse(first[2])).toBeLessThan(93 * 24 * 60 * 60 * 1000);
    await renderer.act(async () => { tree.root.findByProps({ testID: 'trainer-calendar-next' }).props.onPress(); });
    const next = (useTrainerWorkoutSchedule as jest.Mock).mock.calls.at(-1)!;
    expect(Date.parse(next[2]) - Date.parse(first[2])).toBe(30 * 24 * 60 * 60 * 1000);
    renderer.act(() => tree.root.findByProps({ testID: 'trainer-calendar-load-more' }).props.onPress());
    expect(calendarResult.fetchNextPage).toHaveBeenCalledTimes(1);
    expect(calendarWindow(Date.parse(first[2]), 0).until).toBe(first[3]);
    renderer.act(() => tree.unmount());
  });

  it('does not query for a non-Trainer capability', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'STUDENT' });
    const { tree } = await render();
    expect(JSON.stringify(tree.toJSON())).toContain('Chỉ dành cho huấn luyện viên');
    expect((useTrainerWorkoutSchedule as jest.Mock).mock.calls.at(-1)![4]).toBe(false);
    renderer.act(() => tree.unmount());
  });
});
