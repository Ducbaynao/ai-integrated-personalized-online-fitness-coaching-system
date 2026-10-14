import React from 'react';
import renderer from 'react-test-renderer';
import moment from 'moment-timezone';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { useWorkoutPlan, useWorkoutPlanVersion } from '@/features/workout-plan/workoutPlanQueries';
import { workoutScheduleApi } from '@/services/workoutScheduleApi';
import { StudentConfirmBatchScreen } from '@/features/workout-schedule/StudentConfirmBatchScreen';
import { suggestedSessionDate, validateDirectBatch } from '@/features/workout-schedule/directBatchValidation';
import type { WorkoutSession } from '@/types/workoutPlan';

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ replace: mockReplace, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/workout-plan/workoutPlanQueries', () => ({
  useWorkoutPlan: jest.fn(), useWorkoutPlanVersion: jest.fn(),
  workoutPlanQueryKeys: { student: (id: string) => ['workout-plans', 'student', id] },
}));
jest.mock('@/services/workoutScheduleApi', () => ({ workoutScheduleApi: { confirmBatch: jest.fn() } }));

const plan = { id: 'plan-1', name: 'Sức mạnh', status: 'ACTIVE', readContext: 'CURRENT',
  aggregateVersion: 7, currentVersionId: 'version-1' };
const version = { id: 'version-1', versionNumber: 2, current: true, lockedAt: '2026-10-01T00:00:00Z', effectiveUntil: null };
const session = (id: string, requirement = 'SELF_ALLOWED'): WorkoutSession => ({
  id, weekNumber: 1, dayNumber: 1, sequenceNumber: id === 'session-1' ? 1 : 2,
  name: id === 'session-1' ? 'Upper body' : 'Lower body', focus: null,
  supervisionRequirement: requirement as WorkoutSession['supervisionRequirement'],
  estimatedDurationMinutes: 60, notes: null, prescriptions: [],
});
const clients: QueryClient[] = [];

function setup(requirement = 'SELF_ALLOWED') {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
  (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan, currentVersion: version }, isPending: false, isError: false, refetch: jest.fn() });
  (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: { version, sessions: [session('session-1', requirement), session('session-2')] }, isPending: false, isError: false, refetch: jest.fn() });
}
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}><StudentConfirmBatchScreen planId="plan-1" /></QueryClientProvider>); });
  return tree;
}
async function press(tree: renderer.ReactTestRenderer, testID: string) {
  await renderer.act(async () => { tree.root.findByProps({ testID }).props.onPress(); });
}
function input(tree: renderer.ReactTestRenderer, testID: string, value: string) {
  renderer.act(() => { tree.root.findByProps({ testID }).props.onChangeText(value); });
}
async function fill(tree: renderer.ReactTestRenderer, count = 1, monday = moment.utc().add(7, 'days').startOf('isoWeek').format('YYYY-MM-DD'), review = true) {
  input(tree, 'timezone-search', 'Ho_Chi_Minh');
  await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Asia/Ho_Chi_Minh' }).props.onPress(); });
  input(tree, 'week-anchor', monday);
  for (let n = 1; n <= count; n++) {
    const id = `session-${n}`;
    await press(tree, `select-session-${id}`);
    input(tree, `start-${id}`, `${monday}T${n === 1 ? '18:00' : '20:00'}`);
    await renderer.act(async () => { tree.root.findAllByProps({ accessibilityLabel: 'UTC+07:00' }).filter((node) => typeof node.props.onPress === 'function').at(-1)!.props.onPress(); });
    input(tree, `end-${id}`, `${monday}T${n === 1 ? '19:00' : '21:00'}`);
    await renderer.act(async () => { tree.root.findAllByProps({ accessibilityLabel: 'UTC+07:00' }).filter((node) => typeof node.props.onPress === 'function').at(-1)!.props.onPress(); });
  }
  if (review) await press(tree, 'review-batch');
  return monday;
}

describe('Student direct scheduling', () => {
  jest.setTimeout(20_000);
  beforeEach(() => { jest.resetAllMocks(); setup(); });
  afterEach(() => clients.splice(0).forEach((client) => client.clear()));

  it('confirms multiple real plan sessions atomically and retains body/key on network retry', async () => {
    (workoutScheduleApi.confirmBatch as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'network')).mockResolvedValueOnce({ batchId: 'batch-1', items: [{ occurrenceId: 'occurrence-1' }] });
    const tree = render();
    const monday = await fill(tree, 2);
    expect(JSON.stringify(tree.toJSON())).toContain('Hoặc toàn bộ buổi được tạo');
    await press(tree, 'confirm-batch');
    const body = (workoutScheduleApi.confirmBatch as jest.Mock).mock.calls[0][0];
    expect(body).toMatchObject({ planId: 'plan-1', sourcePlanVersionId: 'version-1', expectedPlanAggregateVersion: 7,
      weekAnchorDate: monday, timezone: 'Asia/Ho_Chi_Minh', items: [
        { planSessionId: 'session-1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1, startUtcOffset: '+07:00', endUtcOffset: '+07:00' },
        { planSessionId: 'session-2', weekNumber: 1, dayNumber: 1, sequenceNumber: 2, startUtcOffset: '+07:00', endUtcOffset: '+07:00' },
      ] });
    expect(body.commandKey).toBeTruthy();
    expect(body.items[0].clientItemId).not.toBe(body.items[1].clientItemId);
    await renderer.act(async () => { tree.root.findByProps({ testID: 'batch-error' }).findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); });
    expect((workoutScheduleApi.confirmBatch as jest.Mock).mock.calls[1][0]).toBe(body);
    expect(mockReplace).toHaveBeenCalledWith('/workouts/schedule');
    renderer.act(() => tree.unmount());
  });

  it('blocks COACH_REQUIRED and missing supervision metadata', () => {
    setup('COACH_REQUIRED');
    const tree = render();
    expect(tree.root.findByProps({ testID: 'select-session-session-1' }).props.disabled).toBe(true);
    renderer.act(() => tree.unmount());
    setup(undefined);
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: { version, sessions: [{ ...session('session-1'), supervisionRequirement: null }] }, isPending: false, isError: false, refetch: jest.fn() });
    const legacy = render();
    expect(legacy.root.findByProps({ testID: 'select-session-session-1' }).props.disabled).toBe(true);
    renderer.act(() => legacy.unmount());
  });

  it('drops stale command after 409, refetches source and requires fresh preview', async () => {
    (workoutScheduleApi.confirmBatch as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'stale'));
    const tree = render();
    await fill(tree);
    await press(tree, 'confirm-batch');
    expect(tree.root.findAllByProps({ testID: 'confirm-batch' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'start-session-1' })).toHaveLength(0);
    expect((useWorkoutPlan as jest.Mock).mock.results.some((value) => value.value.refetch.mock.calls.length > 0)).toBe(true);
    expect(JSON.stringify(tree.toJSON())).toContain('lệnh cũ sẽ không tự gửi lại');
    renderer.act(() => tree.unmount());
  });

  it('shows item errors, clears old command, and conceals data after authority loss', async () => {
    const itemError = { errorCode: 'VALIDATION_FAILED', message: 'invalid', timestamp: '', requestId: '', fieldErrors: [],
      itemErrors: [{ clientItemId: '', field: 'localStart', code: 'VALIDATION_FAILED', message: 'Giờ không hợp lệ' }] };
    (workoutScheduleApi.confirmBatch as jest.Mock).mockImplementationOnce((body) => Promise.reject(new ApiError(400, 'invalid', {
      ...itemError, itemErrors: [{ ...itemError.itemErrors[0], clientItemId: body.items[0].clientItemId }],
    })));
    const tree = render();
    await fill(tree);
    await press(tree, 'confirm-batch');
    expect(JSON.stringify(tree.toJSON())).toContain('Upper body (localStart): Giờ không hợp lệ');
    expect(tree.root.findAllByProps({ testID: 'confirm-batch' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
    (workoutScheduleApi.confirmBatch as jest.Mock).mockRejectedValueOnce(new ApiError(403, 'denied'));
    const denied = render();
    await fill(denied);
    await press(denied, 'confirm-batch');
    expect(denied.root.findAllByProps({ testID: 'source-session-session-1' })).toHaveLength(0);
    renderer.act(() => denied.unmount());
  });

  it('does not load Student plan data for a Trainer capability', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'TRAINER' });
    const tree = render();
    expect(tree.root.findAllByProps({ testID: 'student-confirm-batch-screen' })).toHaveLength(0);
    expect(useWorkoutPlan).toHaveBeenCalledWith('trainer-1', 'plan-1', false);
    renderer.act(() => tree.unmount());
  });

  it('shows a usable empty state when no published source version exists', () => {
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan: { ...plan, status: 'DRAFT' }, currentVersion: null }, isPending: false, isError: false, refetch: jest.fn() });
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: undefined, isPending: true, isError: false, refetch: jest.fn() });
    const tree = render();
    expect(JSON.stringify(tree.toJSON())).toContain('Chưa có kế hoạch phù hợp');
    expect(JSON.stringify(tree.toJSON())).not.toContain('Đang tải kế hoạch');
    renderer.act(() => tree.unmount());
  });

  it('warns before preview when the selected session starts beyond the tentative 90-day window', async () => {
    const tree = render();
    const farMonday = moment.utc().add(98, 'days').startOf('isoWeek').format('YYYY-MM-DD');
    await fill(tree, 1, farMonday, false);
    await press(tree, 'review-batch');
    expect(JSON.stringify(tree.toJSON())).toContain('90 ngày');
    expect(tree.root.findAllByProps({ testID: 'batch-preview' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });
});

describe('Student batch time validation', () => {
  const entry = (localStart: string, localEnd: string, startOffset: string, endOffset: string) => ({
    session: session('session-1'), localStart, localEnd, startOffset, endOffset,
  });
  it('rejects DST gap and requires explicit offset for an ambiguous hour', () => {
    expect(validateDirectBatch('2026-03-02', 'America/New_York', [entry('2026-03-08T02:30', '2026-03-08T04:00', '-05:00', '-04:00')], 0)).toContain('DST');
    const now = Date.parse('2026-10-01T00:00:00Z');
    expect(validateDirectBatch('2026-10-26', 'America/New_York', [entry('2026-11-01T01:30', '2026-11-01T02:30', '', '-05:00')], now)).toContain('UTC offset');
    expect(validateDirectBatch('2026-10-26', 'America/New_York', [entry('2026-11-01T01:30', '2026-11-01T02:30', '-04:00', '-05:00')], now)).toBeNull();
  });
  it('rejects past, 90-day overflow, overlap and invalid week anchor', () => {
    const now = Date.parse('2026-10-01T00:00:00Z');
    expect(validateDirectBatch('2026-09-28', 'Asia/Ho_Chi_Minh', [entry('2026-09-28T18:00', '2026-09-28T19:00', '+07:00', '+07:00')], now)).toContain('90 ngày');
    expect(validateDirectBatch('2027-01-04', 'Asia/Ho_Chi_Minh', [entry('2027-01-04T18:00', '2027-01-04T19:00', '+07:00', '+07:00')], now)).toContain('90 ngày');
    expect(validateDirectBatch('2026-10-03', 'Asia/Ho_Chi_Minh', [entry('2026-10-03T18:00', '2026-10-03T19:00', '+07:00', '+07:00')], now)).toContain('thứ Hai');
  });
  it('uses each planned start instant, not the week anchor, at the 90-day boundary', () => {
    const now = Date.parse('2026-10-05T00:00:00Z');
    const base = {
      session: { ...session('session-1'), weekNumber: 13, dayNumber: 7 },
      localStart: '2027-01-03T00:00', localEnd: '2027-01-03T00:30',
      startOffset: '+00:00', endOffset: '+00:00',
    };
    expect(validateDirectBatch('2026-10-05', 'Etc/UTC', [base], now)).toBeNull();
    expect(validateDirectBatch('2026-10-05', 'Etc/UTC', [
      { ...base, localStart: '2027-01-03T00:01' },
    ], now)).toContain('90 ngày');
    expect(validateDirectBatch('2027-01-04', 'Etc/UTC', [{
      ...base, session: { ...base.session, weekNumber: 1, dayNumber: 1 },
      localStart: '2027-01-04T18:00', localEnd: '2027-01-04T19:00',
    }], now)).toContain('90 ngày');
  });
  it('suggests the source session date without fixing the Student-selected day', () => {
    expect(suggestedSessionDate('2026-10-12', { ...session('session-1'), weekNumber: 2, dayNumber: 3 })).toBe('2026-10-21');
    expect(suggestedSessionDate('2026-10-13', session('session-1'))).toBeNull();
  });
});
