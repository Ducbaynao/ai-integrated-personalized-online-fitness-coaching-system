import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useWorkoutScheduleDetail } from '@/features/workout-schedule/workoutScheduleQueries';
import { StudentWorkoutScheduleDetailScreen } from '@/features/workout-schedule/StudentWorkoutScheduleDetailScreen';
import { validateScheduleDestination } from '@/features/workout-schedule/scheduleChange';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';

const mockReplace = jest.fn();
const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ replace: mockReplace, push: mockPush, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/workout-schedule/workoutScheduleQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule/workoutScheduleQueries');
  return { ...actual, useWorkoutScheduleDetail: jest.fn() };
});
jest.mock('@/services/workoutScheduleApi', () => ({ workoutScheduleApi: { detail: jest.fn(), reschedule: jest.fn(), cancel: jest.fn() } }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));

const occurrence = {
  occurrenceId: 'old-1', occurrenceVersion: 8, studentId: 'student-1', status: 'SCHEDULED',
  plannedStartAt: '2026-10-20T11:00:00Z', originalPlannedStartAt: '2026-10-20T11:00:00Z',
  plannedEndAt: '2026-10-20T12:00:00Z', timezone: 'Asia/Ho_Chi_Minh',
  localStart: '2026-10-20T18:00', localEnd: '2026-10-20T19:00', startUtcOffset: '+07:00', endUtcOffset: '+07:00',
  rescheduledFromOccurrenceId: null, replacementOccurrenceId: null,
  planSummary: null, sessionSummary: null, availableActions: ['RESCHEDULE', 'CANCEL'],
};

const clients: QueryClient[] = [];
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  const invalidate = jest.spyOn(client, 'invalidateQueries');
  const remove = jest.spyOn(client, 'removeQueries');
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}><StudentWorkoutScheduleDetailScreen occurrenceId="old-1" /></QueryClientProvider>); });
  return { tree, invalidate, remove };
}

function detail(value: typeof occurrence | Record<string, unknown> = occurrence, extras: Record<string, unknown> = {}) {
  (useWorkoutScheduleDetail as jest.Mock).mockReturnValue({ data: value, isPending: false, isError: false, isFetching: false, error: null, refetch: jest.fn(), ...extras });
}

describe('Student direct schedule change', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Date, 'now').mockReturnValue(new Date('2026-10-13T00:00:00Z').getTime());
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
    (createCommandKey as jest.Mock).mockReturnValue('stable-key');
    detail();
  });
  afterEach(() => { jest.restoreAllMocks(); clients.splice(0).forEach((client) => client.clear()); });

  it('shows nullable summary and one-to-one lineage; only backend actions become buttons', () => {
    detail({ ...occurrence, status: 'RESCHEDULED', availableActions: [], replacementOccurrenceId: 'new-1', rescheduledFromOccurrenceId: 'older-1' });
    const { tree } = render();
    const json = JSON.stringify(tree.toJSON());
    expect(json).toContain('Chưa có tên kế hoạch');
    expect(json).toContain('Đã đổi lịch');
    expect(tree.root.findAllByProps({ testID: 'schedule-reschedule' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'schedule-cancel' })).toHaveLength(0);
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-predecessor' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-replacement' }).props.onPress());
    expect(mockPush).toHaveBeenNthCalledWith(1, '/workouts/schedule/older-1');
    expect(mockPush).toHaveBeenNthCalledWith(2, '/workouts/schedule/new-1');
    renderer.act(() => tree.unmount());
  });

  it('does not render stale detail after a concealed read error', async () => {
    detail(occurrence, { isError: true, error: new ApiError(404, 'concealed') });
    const { tree, remove } = render();
    await renderer.act(async () => { await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'schedule-detail-concealed' })).toBeTruthy();
    expect(tree.root.findAllByProps({ testID: 'schedule-reschedule' })).toHaveLength(0);
    expect(remove).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    renderer.act(() => tree.unmount());
  });

  it('checks DST gaps and repeated-hour offsets without guessing', () => {
    expect(validateScheduleDestination({ timezone: 'America/New_York', localStart: '2027-03-14T02:30', localEnd: '2027-03-14T04:00', startUtcOffset: '-05:00', endUtcOffset: '-04:00' })).toContain('không tồn tại');
    expect(validateScheduleDestination({ timezone: 'America/New_York', localStart: '2026-11-01T01:30', localEnd: '2026-11-01T02:30', startUtcOffset: '', endUtcOffset: '-05:00' })).toContain('offset');
  });

  it('sends real version and explicit destination, then navigates to server replacement', async () => {
    (workoutScheduleApi.reschedule as jest.Mock).mockResolvedValue({ changeType: 'RESCHEDULE', replacementOccurrenceId: 'new-1' });
    const { tree, invalidate } = render();
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-reschedule' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Dùng múi giờ hiện tại: Asia/Ho_Chi_Minh' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-local-start' }).props.onChangeText('2026-10-21T18:00'));
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-local-end' }).props.onChangeText('2026-10-21T19:00'));
    const offsetButtons = tree.root.findAllByProps({ accessibilityLabel: 'UTC+07:00' }).filter((node) => typeof node.props.onPress === 'function');
    renderer.act(() => offsetButtons[0].props.onPress());
    renderer.act(() => offsetButtons[1].props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-review-reschedule' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'schedule-reschedule-preview' })).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'schedule-confirm-reschedule' }).props.onPress(); await Promise.resolve(); });
    expect(workoutScheduleApi.reschedule).toHaveBeenCalledWith('old-1', {
      expectedOccurrenceVersion: 8, destination: {
        timezone: 'Asia/Ho_Chi_Minh', localStart: '2026-10-21T18:00', localEnd: '2026-10-21T19:00', startUtcOffset: '+07:00', endUtcOffset: '+07:00',
      }, commandKey: 'stable-key',
    });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    expect(mockReplace).toHaveBeenCalledWith('/workouts/schedule/new-1');
    renderer.act(() => tree.unmount());
  });

  it('requires a reason for late cancel and retries uncertain network result with identical command', async () => {
    detail({ ...occurrence, plannedStartAt: '2026-10-12T11:00:00Z', availableActions: ['CANCEL'] });
    (workoutScheduleApi.cancel as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'offline')).mockResolvedValueOnce({ changeType: 'CANCEL', replacementOccurrenceId: null });
    const { tree } = render();
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-cancel' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-review-cancel' }).props.onPress());
    expect(tree.root.findAllByProps({ testID: 'schedule-confirm-cancel' })).toHaveLength(0);
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-cancel-reason' }).props.onChangeText('Bị ốm'));
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-review-cancel' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'schedule-confirm-cancel' }).props.onPress(); await Promise.resolve(); });
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); await Promise.resolve(); });
    expect(workoutScheduleApi.cancel).toHaveBeenCalledTimes(2);
    expect((workoutScheduleApi.cancel as jest.Mock).mock.calls[0]).toEqual((workoutScheduleApi.cancel as jest.Mock).mock.calls[1]);
    expect(workoutScheduleApi.cancel).toHaveBeenCalledWith('old-1', { expectedOccurrenceVersion: 8, reason: 'Bị ốm', commandKey: 'stable-key' });
    renderer.act(() => tree.unmount());
  });

  it('never replays a 409 using a new token, and removes sensitive cache after 404', async () => {
    (workoutScheduleApi.cancel as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'conflict')).mockRejectedValueOnce(new ApiError(404, 'concealed'));
    const { tree, invalidate, remove } = render();
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-cancel' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-review-cancel' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'schedule-confirm-cancel' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'schedule-change-review' })).toBeTruthy();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    expect(workoutScheduleApi.cancel).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-cancel' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-review-cancel' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'schedule-confirm-cancel' }).props.onPress(); await Promise.resolve(); });
    expect(remove).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    expect(tree.root.findByProps({ testID: 'schedule-detail-concealed' })).toBeTruthy();
    renderer.act(() => tree.unmount());
  });
});
