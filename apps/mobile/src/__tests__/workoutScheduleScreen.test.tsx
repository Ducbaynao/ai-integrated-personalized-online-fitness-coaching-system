import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { useWorkoutSchedule } from '@/features/workout-schedule/workoutScheduleQueries';
import { StudentWorkoutScheduleScreen, formatScheduleTime } from '@/features/workout-schedule/StudentWorkoutScheduleScreen';
import { workoutExecutionApi } from '@/services/workoutExecutionApi';
import { createCommandKey } from '@/services/coachingApi';

const mockPush = jest.fn();
const mockBack = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, back: mockBack }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/workout-schedule/workoutScheduleQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule/workoutScheduleQueries');
  return { ...actual, useWorkoutSchedule: jest.fn() };
});
jest.mock('@/services/workoutExecutionApi', () => ({ workoutExecutionApi: { start: jest.fn(), skip: jest.fn() } }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));

const student = { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh', capabilities: { hasStudentProfile: true } };
const occurrence = {
  occurrenceId: 'occurrence-1', occurrenceVersion: 8, studentId: 'student-1', status: 'SCHEDULED',
  plannedStartAt: '2026-10-12T03:00:00Z', originalPlannedStartAt: '2026-10-12T03:00:00Z',
  plannedEndAt: null, timezone: null, localStart: null, localEnd: null, startUtcOffset: null,
  endUtcOffset: null, planSummary: null, sessionSummary: null, availableActions: ['START', 'SKIP'],
};

const clients: QueryClient[] = [];
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  const invalidate = jest.spyOn(client, 'invalidateQueries');
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}><StudentWorkoutScheduleScreen /></QueryClientProvider>); });
  return { tree, invalidate };
}

function schedule(items: unknown[] = [occurrence], extras: Record<string, unknown> = {}) {
  (useWorkoutSchedule as jest.Mock).mockReturnValue({
    data: { pages: [{ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 }] },
    isPending: false, isError: false, error: null, refetch: jest.fn(),
    hasNextPage: false, isFetchingNextPage: false, fetchNextPage: jest.fn(), ...extras,
  });
}

describe('Student Planned Workout calendar', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: student, activeCapability: 'STUDENT' });
    (createCommandKey as jest.Mock).mockReturnValue('stable-command-key');
    schedule();
  });
  afterEach(() => clients.splice(0).forEach((client) => client.clear()));

  it('renders legacy nullable summaries and only backend-provided actions', () => {
    schedule([{ ...occurrence, availableActions: [] }]);
    const { tree } = render();
    expect(JSON.stringify(tree.toJSON())).toContain('Không có tên kế hoạch trong bản ghi cũ');
    expect(JSON.stringify(tree.toJSON())).toContain('múi giờ hồ sơ');
    expect(tree.root.findAllByProps({ testID: 'schedule-start-occurrence-1' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'schedule-skip-occurrence-1' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
    expect(formatScheduleTime(occurrence.plannedStartAt, 'Etc/UTC', student.timezone)).toContain('Etc/UTC');
  });

  it('opens the real occurrence detail even for terminal history', () => {
    schedule([{ ...occurrence, status: 'RESCHEDULED', availableActions: [], replacementOccurrenceId: 'new-1' }]);
    const { tree } = render();
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-detail-occurrence-1' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/workouts/schedule/occurrence-1');
    renderer.act(() => tree.unmount());
  });

  it('starts with the actual occurrence version, prevents double submit and opens B05 Current', async () => {
    let finish!: (value: unknown) => void;
    (workoutExecutionApi.start as jest.Mock).mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const { tree, invalidate } = render();
    const start = tree.root.findByProps({ testID: 'schedule-start-occurrence-1' });
    await renderer.act(async () => { start.props.onPress(); start.props.onPress(); await Promise.resolve(); });
    expect(workoutExecutionApi.start).toHaveBeenCalledTimes(1);
    expect(workoutExecutionApi.start).toHaveBeenCalledWith('occurrence-1', 8, 'stable-command-key');
    await renderer.act(async () => { await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(tree.root.findByProps({ testID: 'schedule-start-occurrence-1' }).props.disabled).toBe(true);
    await renderer.act(async () => { finish({ execution: { executionId: 'execution-1' }, exercises: [] }); await Promise.resolve(); });
    expect(mockPush).toHaveBeenCalledWith('/workouts/current');
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workout-executions', 'student', 'student-1', 'current'] });
    renderer.act(() => tree.unmount());
  });

  it('requires confirmation for SKIP and updates the calendar with its original token', async () => {
    (workoutExecutionApi.skip as jest.Mock).mockResolvedValue({ occurrenceId: 'occurrence-1', status: 'SKIPPED', occurrenceVersion: 9, replayed: false });
    const { tree, invalidate } = render();
    renderer.act(() => tree.root.findByProps({ testID: 'schedule-skip-occurrence-1' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'schedule-skip-confirmation' })).toBeTruthy();
    expect(workoutExecutionApi.skip).not.toHaveBeenCalled();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'schedule-confirm-skip' }).props.onPress(); await Promise.resolve(); });
    expect(workoutExecutionApi.skip).toHaveBeenCalledWith('occurrence-1', 8, 'stable-command-key');
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    renderer.act(() => tree.unmount());
  });

  it('retries a network failure with the same version and command key', async () => {
    (workoutExecutionApi.start as jest.Mock)
      .mockRejectedValueOnce(new ApiError(0, 'offline'))
      .mockResolvedValueOnce({ execution: { executionId: 'execution-1' }, exercises: [] });
    const { tree } = render();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'schedule-start-occurrence-1' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'schedule-action-error' })).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); await Promise.resolve(); });
    expect(workoutExecutionApi.start).toHaveBeenNthCalledWith(1, 'occurrence-1', 8, 'stable-command-key');
    expect(workoutExecutionApi.start).toHaveBeenNthCalledWith(2, 'occurrence-1', 8, 'stable-command-key');
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('does not replay a stale conflict and refetches calendar/current authority', async () => {
    (workoutExecutionApi.start as jest.Mock).mockRejectedValue(new ApiError(409, 'stale', {
      errorCode: 'WORKOUT_EXECUTION_VERSION_CONFLICT', message: 'stale', timestamp: '', requestId: '', fieldErrors: [],
    }));
    const { tree, invalidate } = render();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'schedule-start-occurrence-1' }).props.onPress(); await Promise.resolve(); });
    expect(workoutExecutionApi.start).toHaveBeenCalledTimes(1);
    expect(tree.root.findByProps({ testID: 'schedule-action-error' })).toBeTruthy();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workout-executions', 'student', 'student-1', 'current'] });
    renderer.act(() => tree.unmount());
  });

  it('handles loading, empty, server error and pagination without showing phantom actions', () => {
    schedule([], { data: undefined, isPending: true });
    let view = render();
    expect(view.tree.root.findAllByProps({ busy: true }).length).toBeGreaterThan(0);
    renderer.act(() => view.tree.unmount());
    schedule([]);
    view = render();
    expect(view.tree.root.findByProps({ testID: 'schedule-empty' })).toBeTruthy();
    renderer.act(() => view.tree.unmount());
    const refetch = jest.fn();
    schedule([], { data: undefined, isError: true, error: new ApiError(500, 'server'), refetch });
    view = render();
    renderer.act(() => view.tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress());
    expect(refetch).toHaveBeenCalled();
    renderer.act(() => view.tree.unmount());
    const fetchNextPage = jest.fn();
    schedule([occurrence], { hasNextPage: true, fetchNextPage });
    view = render();
    renderer.act(() => view.tree.root.findByProps({ testID: 'schedule-load-more' }).props.onPress());
    expect(fetchNextPage).toHaveBeenCalled();
    renderer.act(() => view.tree.unmount());
  });
});
