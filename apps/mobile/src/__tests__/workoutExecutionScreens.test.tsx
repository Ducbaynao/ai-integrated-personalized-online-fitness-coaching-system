import { expectTimeDisplay, TIME_BOUNDARY, VIETNAM_MOMENT } from '@/test-utils/timeDisplayRegression';
import React from 'react';
import renderer from 'react-test-renderer';
import { AccessibilityInfo } from 'react-native';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { WorkoutExecutionDetail } from '@/types/workoutExecution';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import {
  CurrentWorkoutExecutionScreen,
  WorkoutExecutionDetailScreen,
} from '@/features/workout-execution/WorkoutExecutionScreen';
import { WorkoutHistoryScreen } from '@/features/workout-execution/WorkoutHistoryScreen';
import {
  workoutExecutionQueryKeys,
  useCurrentWorkoutExecution,
  useWorkoutExecutionDetail,
  useWorkoutExecutionHistory,
} from '@/features/workout-execution/workoutExecutionQueries';
import { useExerciseDetail } from '@/features/exercise/exerciseQueries';
import { workoutExecutionApi } from '@/services/workoutExecutionApi';

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush, replace: mockReplace, back: mockBack }),
}));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/workout-execution/workoutExecutionQueries', () => {
  const actual = jest.requireActual('@/features/workout-execution/workoutExecutionQueries');
  return {
    ...actual,
    useCurrentWorkoutExecution: jest.fn(),
    useWorkoutExecutionDetail: jest.fn(),
    useWorkoutExecutionHistory: jest.fn(),
  };
});
jest.mock('@/features/exercise/exerciseQueries', () => ({
  useExerciseDetail: jest.fn(),
}));
jest.mock('@/features/exercise/ExercisePicker', () => {
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const MockReact = require('react');
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { Pressable: MockPressable, Text: MockText } = require('react-native');
  return {
    ExercisePicker: ({ onConfirm }: { onConfirm: (exercise: unknown) => void }) =>
      MockReact.createElement(
        MockPressable,
        {
          accessibilityRole: 'button',
          accessibilityLabel: 'Chọn bài thay thế thử nghiệm',
          onPress: () => onConfirm({ id: 'exercise-2', name: 'Dumbbell Squat' }),
        },
        MockReact.createElement(MockText, null, 'Dumbbell Squat')
      ),
  };
});
jest.mock('@/services/workoutExecutionApi', () => ({
  workoutExecutionApi: {
    upsertSet: jest.fn(),
    substitute: jest.fn(),
    complete: jest.fn(),
    abort: jest.fn(),
  },
}));

const user = {
  id: 'student-1',
  status: 'ACTIVE',
  timezone: 'Asia/Ho_Chi_Minh',
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false },
};
const presentation = {
  variationId: 'variation-1',
  exerciseId: 'exercise-1',
  exerciseName: 'Barbell Squat',
  variationName: 'High Bar',
  state: 'ACTIVE' as const,
  canonicalExerciseId: null,
  canonicalExerciseName: null,
};
const kilograms = { id: 1, code: 'KG', symbol: 'kg', dimension: 'MASS' };
const pounds = { id: 2, code: 'LB', symbol: 'lb', dimension: 'MASS' };
const kilometres = { id: 3, code: 'KM', symbol: 'km', dimension: 'LENGTH' };
const metres = { id: 4, code: 'M', symbol: 'm', dimension: 'LENGTH' };
const set = {
  clientSetId: '10000000-0000-4000-8000-000000000001',
  baselineSetNumber: 1,
  setNumber: 1,
  setType: 'WORKING' as const,
  completionStatus: 'PLANNED' as const,
  repetitions: null,
  loadValue: null,
  loadUnitId: 2,
  loadUnit: pounds,
  durationSeconds: null,
  distanceValue: null,
  distanceUnitId: 4,
  distanceUnit: metres,
  rpe: null,
  rir: null,
  tempo: null,
  restAfterSeconds: 90,
  note: null,
  completedAt: null,
};
const detail = {
  execution: {
    executionId: '20000000-0000-4000-8000-000000000001',
    studentId: 'student-1',
    plannedWorkoutId: 'occurrence-1',
    planId: 'plan-1',
    planVersionId: 'version-1',
    planSessionId: 'session-1',
    coachingPeriodId: null,
    snapshotMode: 'FROZEN' as const,
    plannedStartAt: '2026-10-09T01:00:00Z',
    originalPlannedStartAt: '2026-10-09T01:00:00Z',
    plannedEndAt: null,
    supervisionRequirement: 'SELF_PERFORMABLE' as const,
    snapshotFrozenAt: '2026-10-09T01:05:00Z',
    sourceOccurrenceVersion: 3,
    performedStartedAt: '2026-10-09T01:05:00Z',
    performedEndedAt: null,
    status: 'IN_PROGRESS' as const,
    overallRpe: null,
    sessionNote: null,
    version: 7,
  },
  exercises: [
    {
      exerciseExecutionId: 'exercise-execution-1',
      sourcePrescriptionId: 'prescription-1',
      prescribedVariationId: 'variation-1',
      prescribedVariationPresentation: presentation,
      actualVariationId: 'variation-1',
      actualVariationPresentation: presentation,
      substitutionReason: null,
      sequence: 1,
      baselineSetCount: 1,
      targetRepsMin: 8,
      targetRepsMax: 10,
      targetLoad: 60,
      loadUnitId: 1,
      loadUnit: kilograms,
      targetRpe: null,
      targetRir: null,
      restSeconds: 90,
      tempo: null,
      durationSeconds: null,
      distanceValue: 5,
      distanceUnitId: 3,
      distanceUnit: kilometres,
      instructions: 'Giữ lưng trung lập.',
      note: null,
      sets: [set],
    },
  ],
};

const clients: QueryClient[] = [];
function render(element: React.ReactElement) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false, gcTime: 0 },
      mutations: { retry: false, gcTime: 0 },
    },
  });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => {
    tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
  });
  return tree;
}

describe('B05 Workout Execution screens', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user, activeCapability: 'STUDENT' });
    (useCurrentWorkoutExecution as jest.Mock).mockReturnValue({
      data: detail,
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    (useWorkoutExecutionDetail as jest.Mock).mockReturnValue({
      data: detail,
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    (useWorkoutExecutionHistory as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [detail.execution] }] },
      isPending: false,
      isError: false,
      error: null,
      hasNextPage: false,
      isFetchingNextPage: false,
      refetch: jest.fn(),
      fetchNextPage: jest.fn(),
    });
    (useExerciseDetail as jest.Mock).mockReturnValue({
      data: { variations: [{ id: 'variation-2', name: 'Goblet', defaultVariation: true }] },
      isPending: false,
      isError: false,
      refetch: jest.fn(),
    });
  });

  afterEach(() => clients.splice(0).forEach((client) => client.clear()));

  it('separates a missing current execution from retryable errors', () => {
    (useCurrentWorkoutExecution as jest.Mock).mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: new ApiError(404, 'missing'),
      refetch: jest.fn(),
    });
    let tree = render(<CurrentWorkoutExecutionScreen />);
    expect(tree.root.findByProps({ testID: 'current-workout-empty' })).toBeTruthy();
    renderer.act(() => tree.unmount());

    const refetch = jest.fn();
    (useCurrentWorkoutExecution as jest.Mock).mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: new ApiError(500, 'server'),
      refetch,
    });
    tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress());
    expect(refetch).toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('logs a set with the latest backend version', async () => {
    (workoutExecutionApi.upsertSet as jest.Mock).mockResolvedValue(detail);
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Mức tải, lb' }).props.onChangeText('42.5');
      tree.root.findByProps({ accessibilityLabel: 'Quãng đường, m' }).props.onChangeText('100');
    });
    const save = tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).findByProps({ accessibilityRole: 'button' });
    await renderer.act(async () => {
      save.props.onPress({ nativeEvent: { timestamp: 123, pageX: 20, pageY: 20 } });
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(workoutExecutionApi.upsertSet).toHaveBeenCalledWith(
      detail.execution.executionId,
      set.clientSetId,
      7,
      expect.objectContaining({
        exerciseExecutionId: 'exercise-execution-1',
        setNumber: 1,
        loadValue: 42.5,
        loadUnitId: 2,
        distanceValue: 100,
        distanceUnitId: 4,
      })
    );
    renderer.act(() => tree.unmount());
  });

  it('shows exercise targets and set inputs with their distinct backend units', () => {
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const json = JSON.stringify(tree.toJSON());

    expect(json).toContain('mức tải 60 kg');
    expect(json).toContain('quãng đường 5 km');
    expect(tree.root.findByProps({ accessibilityLabel: 'Mức tải, lb' }).props.editable).toBe(true);
    expect(tree.root.findByProps({ accessibilityLabel: 'Quãng đường, m' }).props.editable).toBe(true);
    renderer.act(() => tree.unmount());
  });

  it('keeps editing an already completed set available while the execution is IN_PROGRESS', async () => {
    const logged = { ...detail, exercises: [{ ...detail.exercises[0], sets: [{ ...set,
      completionStatus: 'COMPLETED' as const, repetitions: 8, rpe: 7, note: 'Đã ghi',
    }] }] };
    (useCurrentWorkoutExecution as jest.Mock).mockReturnValue({ data: logged, isPending: false, isError: false, refetch: jest.fn() });
    (workoutExecutionApi.upsertSet as jest.Mock).mockResolvedValue(logged);
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Số lần' }).props.onChangeText('9'));
    await renderer.act(async () => {
      tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(workoutExecutionApi.upsertSet).toHaveBeenCalledWith(detail.execution.executionId, set.clientSetId, 7,
      expect.objectContaining({ completionStatus: 'COMPLETED', repetitions: 9, rpe: 7, note: 'Đã ghi', loadValue: null }));
    renderer.act(() => tree.unmount());
  });

  it('blocks unknown-unit measurement edits while preserving IDs and existing values', async () => {
    const unknownSet = {
      ...set,
      loadValue: 75,
      loadUnitId: 91,
      loadUnit: null,
      distanceValue: 250,
      distanceUnitId: 92,
      distanceUnit: null,
    };
    const unknownDetail = {
      ...detail,
      exercises: [{ ...detail.exercises[0], sets: [unknownSet] }],
    };
    (useCurrentWorkoutExecution as jest.Mock).mockReturnValue({
      data: unknownDetail,
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    (workoutExecutionApi.upsertSet as jest.Mock).mockResolvedValue(unknownDetail);
    const tree = render(<CurrentWorkoutExecutionScreen />);

    const loadInput = tree.root.findByProps({
      accessibilityLabel: 'Mức tải, Đơn vị không xác định',
    });
    const distanceInput = tree.root.findByProps({
      accessibilityLabel: 'Quãng đường, Đơn vị không xác định',
    });
    expect(loadInput.props.editable).toBe(false);
    expect(distanceInput.props.editable).toBe(false);
    expect(JSON.stringify(tree.toJSON())).toContain('Giá trị hiện có được giữ nguyên');

    await renderer.act(async () => {
      tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(workoutExecutionApi.upsertSet).toHaveBeenCalledWith(
      detail.execution.executionId,
      set.clientSetId,
      7,
      expect.objectContaining({
        loadValue: 75,
        loadUnitId: 91,
        distanceValue: 250,
        distanceUnitId: 92,
      })
    );
    renderer.act(() => tree.unmount());
  });

  it('reuses Exercise Picker and sends only the selected actual variation', async () => {
    (workoutExecutionApi.substitute as jest.Mock).mockResolvedValue(detail);
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thay bài tập' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Chọn bài thay thế thử nghiệm' }).props.onPress());
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Goblet · Mặc định' }).props.onPress();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(workoutExecutionApi.substitute).toHaveBeenCalledWith(
      detail.execution.executionId,
      'exercise-execution-1',
      7,
      { actualExerciseVariationId: 'variation-2', substitutionReason: null }
    );
    renderer.act(() => tree.unmount());
  });

  it('reloads authoritative execution after a stale set conflict', async () => {
    const refetch = jest.fn();
    (useCurrentWorkoutExecution as jest.Mock).mockReturnValue({
      data: detail,
      isPending: false,
      isError: false,
      error: null,
      refetch,
    });
    (workoutExecutionApi.upsertSet as jest.Mock).mockRejectedValue(
      new ApiError(409, 'stale', {
        errorCode: 'WORKOUT_EXECUTION_VERSION_CONFLICT',
        message: 'stale',
        timestamp: '',
        requestId: '',
        fieldErrors: [],
      })
    );
    const tree = render(<CurrentWorkoutExecutionScreen />);
    await renderer.act(async () => {
      tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(refetch).toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('đã được cập nhật ở nơi khác');
    renderer.act(() => tree.unmount());
  });

  it('submits complete with a stable command key and blocks duplicate submit', async () => {
    let finish!: (value: unknown) => void;
    const pending = new Promise((resolve) => { finish = resolve; });
    (workoutExecutionApi.complete as jest.Mock).mockReturnValue(pending);
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ testID: 'complete-workout-button' }).props.onPress());
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'confirm-workout-terminal' }).findByProps({ accessibilityRole: 'button' }).props.onPress({ nativeEvent: { timestamp: 123, pageX: 20, pageY: 20 } });
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(workoutExecutionApi.complete).toHaveBeenCalledWith(
      detail.execution.executionId,
      7,
      { overallRpe: null, sessionNote: null },
      expect.any(String)
    );
    expect(tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.disabled).toBe(true);
    const pendingConfirm = tree.root.findByProps({ testID: 'confirm-workout-terminal' }).findByProps({ accessibilityRole: 'button' });
    expect(pendingConfirm.props.accessibilityState).toEqual({ disabled: true, busy: true });
    renderer.act(() => pendingConfirm.props.onPress({ nativeEvent: { timestamp: 123, pageX: 20, pageY: 20 } }));
    expect(workoutExecutionApi.complete).toHaveBeenCalledTimes(1);
    await renderer.act(async () => {
      finish({ ...detail, execution: { ...detail.execution, status: 'COMPLETED', version: 8 } });
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(mockReplace).toHaveBeenCalledWith(`/workouts/${detail.execution.executionId}`);
    renderer.act(() => tree.unmount());
  });

  it('submits abort with the latest version and lets the backend enforce eligibility', async () => {
    (workoutExecutionApi.abort as jest.Mock).mockResolvedValue({
      ...detail,
      execution: { ...detail.execution, status: 'ABORTED', version: 8 },
    });
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ testID: 'abort-workout-button' }).props.onPress());
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'confirm-workout-terminal' }).findByProps({ accessibilityRole: 'button' }).props.onPress({ nativeEvent: { timestamp: 123, pageX: 20, pageY: 20 } });
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(workoutExecutionApi.abort).toHaveBeenCalledWith(
      detail.execution.executionId,
      7,
      { overallRpe: null, sessionNote: null },
      expect.any(String)
    );
    renderer.act(() => tree.unmount());
  });

  it.each([
    ['COMPLETED', 'Đã hoàn thành'],
    ['PARTIALLY_COMPLETED', 'Hoàn thành một phần'],
    ['ABORTED', 'Đã hủy'],
  ] as const)('renders %s detail as read-only and preserves planned/performed timestamps', (status, statusLabel) => {
    const terminal = {
      ...detail,
      execution: {
        ...detail.execution,
        status,
        performedEndedAt: '2026-10-09T02:00:00Z',
      },
    };
    (useWorkoutExecutionDetail as jest.Mock).mockReturnValue({
      data: terminal,
      isPending: false,
      isError: false,
      error: null,
      refetch: jest.fn(),
    });
    const tree = render(<WorkoutExecutionDetailScreen executionId={detail.execution.executionId} />);
    expect(tree.root.findAllByProps({ testID: `save-workout-set-${set.clientSetId}` })).toHaveLength(0);
    expect(JSON.stringify(tree.toJSON())).toContain('Buổi tập đã kết thúc');
    expect(JSON.stringify(tree.toJSON())).toContain(statusLabel);
    expect(JSON.stringify(tree.toJSON())).toContain('Bắt đầu thực tế');
    expect(JSON.stringify(tree.toJSON())).toContain('Thời gian dự kiến');
    expect(JSON.stringify(tree.toJSON())).toContain('mức tải 60 kg');
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Mức tải, lb' })).toHaveLength(0);
    expect(JSON.stringify(tree.toJSON())).toContain('Số lần: Chưa ghi');
    expect(JSON.stringify(tree.toJSON())).toContain('Nghỉ sau hiệp: 90 giây');
    expect(tree.root.findAllByProps({ testID: 'complete-workout-button' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  const tick = () => new Promise((resolve) => setTimeout(resolve, 0));
  const rerenderCurrent = (tree: renderer.ReactTestRenderer, next: WorkoutExecutionDetail) => {
    (useCurrentWorkoutExecution as jest.Mock).mockReturnValue({
      data: next, isPending: false, isError: false, refetch: jest.fn(),
    });
    renderer.act(() => tree.update(<QueryClientProvider client={clients[clients.length - 1]}><CurrentWorkoutExecutionScreen /></QueryClientProvider>));
  };

  it.each(['COMPLETED', 'PARTIALLY_COMPLETED', 'ABORTED'] as const)('blocks old save/add/finish callbacks after %s arrives', async (status) => {
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const save = tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress;
    const add = tree.root.findByProps({ accessibilityLabel: 'Thêm hiệp' }).props.onPress;
    const substitute = tree.root.findByProps({ accessibilityLabel: 'Thay bài tập' }).props.onPress;
    const finish = tree.root.findByProps({ testID: 'complete-workout-button' }).props.onPress;
    renderer.act(() => finish());
    const confirm = tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.onPress;
    rerenderCurrent(tree, { ...detail, execution: { ...detail.execution, status, version: 8 } });
    await renderer.act(async () => { save(); add(); substitute(); finish(); confirm(); await tick(); });
    expect(workoutExecutionApi.upsertSet).not.toHaveBeenCalled();
    expect(workoutExecutionApi.substitute).not.toHaveBeenCalled();
    expect(workoutExecutionApi.complete).not.toHaveBeenCalled();
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Thêm hiệp' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: `save-workout-set-${set.clientSetId}` })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it.each(['capability', 'profile', 'account', 'owner', 'version', 'suspended'])('blocks an old save after %s changes', async (change) => {
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const save = tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress;
    if (change === 'capability') (useAuth as jest.Mock).mockReturnValue({ user, activeCapability: 'TRAINER' });
    if (change === 'profile') (useAuth as jest.Mock).mockReturnValue({ user: { ...user, capabilities: { hasStudentProfile: false } }, activeCapability: 'STUDENT' });
    if (change === 'suspended') (useAuth as jest.Mock).mockReturnValue({ user: { ...user, status: 'SUSPENDED' }, activeCapability: 'STUDENT' });
    if (change === 'account') (useAuth as jest.Mock).mockReturnValue({ user: { ...user, id: 'student-2' }, activeCapability: 'STUDENT' });
    rerenderCurrent(tree, { ...detail, execution: { ...detail.execution,
      studentId: change === 'owner' ? 'student-2' : 'student-1', version: change === 'version' ? 8 : 7 } });
    await renderer.act(async () => { save(); await tick(); });
    expect(workoutExecutionApi.upsertSet).not.toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('serializes set writes and disables all set fields while a request is pending', async () => {
    let resolve!: (value: unknown) => void;
    (workoutExecutionApi.upsertSet as jest.Mock).mockReturnValue(new Promise((done) => { resolve = done; }));
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const save = tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress;
    await renderer.act(async () => { save(); save(); await tick(); });
    expect(workoutExecutionApi.upsertSet).toHaveBeenCalledTimes(1);
    expect(tree.root.findByProps({ accessibilityLabel: 'Số lần' }).props.editable).toBe(false);
    expect(tree.root.findByProps({ testID: 'complete-workout-button' }).props.disabled).toBe(true);
    await renderer.act(async () => { resolve(detail); await tick(); });
    renderer.act(() => tree.unmount());
  });

  it('does not reopen terminal data or announce a save when an older set response arrives', async () => {
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
    let resolve!: (value: unknown) => void;
    (workoutExecutionApi.upsertSet as jest.Mock).mockReturnValue(new Promise((done) => { resolve = done; }));
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const client = clients[clients.length - 1];
    client.setQueryDefaults(workoutExecutionQueryKeys.detail(user.id, detail.execution.executionId), { gcTime: Infinity });
    await renderer.act(async () => { tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress(); await tick(); });
    const ended: WorkoutExecutionDetail = { ...detail, execution: { ...detail.execution, status: 'COMPLETED', version: 9 } };
    client.setQueryData(workoutExecutionQueryKeys.detail(user.id, detail.execution.executionId), ended);
    rerenderCurrent(tree, ended);
    await renderer.act(async () => { resolve({ ...detail, execution: { ...detail.execution, version: 8 } }); await tick(); });
    expect(client.getQueryData(workoutExecutionQueryKeys.detail(user.id, detail.execution.executionId))).toEqual(ended);
    expect(announce).not.toHaveBeenCalled();
    announce.mockRestore();
    expect(client.getQueryData(workoutExecutionQueryKeys.current(user.id))).toBeUndefined();
    expect(tree.root.findAllByProps({ testID: `save-workout-set-${set.clientSetId}` })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('ignores a pending response after the Student capability is removed', async () => {
    let resolve!: (value: unknown) => void;
    (workoutExecutionApi.upsertSet as jest.Mock).mockReturnValue(new Promise((done) => { resolve = done; }));
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const client = clients[clients.length - 1];
    client.setQueryDefaults(workoutExecutionQueryKeys.detail(user.id, detail.execution.executionId), { gcTime: Infinity });
    await renderer.act(async () => { tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress(); await tick(); });
    (useAuth as jest.Mock).mockReturnValue({ user, activeCapability: 'TRAINER' });
    rerenderCurrent(tree, detail);
    await renderer.act(async () => { resolve(detail); await tick(); });
    expect(client.getQueryData(workoutExecutionQueryKeys.current(user.id))).toBeUndefined();
    renderer.act(() => tree.unmount());
  });

  it('retries an uncertain set write with the identical client identity, body and expected version', async () => {
    (workoutExecutionApi.upsertSet as jest.Mock).mockRejectedValue(new ApiError(0, 'network'));
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const save = () => tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress();
    await renderer.act(async () => { save(); await tick(); });
    const first = (workoutExecutionApi.upsertSet as jest.Mock).mock.calls[0];
    await renderer.act(async () => { save(); await tick(); });
    expect(workoutExecutionApi.upsertSet).toHaveBeenCalledTimes(2);
    expect((workoutExecutionApi.upsertSet as jest.Mock).mock.calls[1]).toEqual(first);
    renderer.act(() => tree.unmount());
  });

  it('retries terminal intent with its original command key, body and version after a refresh', async () => {
    (workoutExecutionApi.complete as jest.Mock).mockRejectedValue(new ApiError(0, 'network'));
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ testID: 'complete-workout-button' }).props.onPress());
    const confirm = () => tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.onPress();
    await renderer.act(async () => { confirm(); await tick(); });
    const first = (workoutExecutionApi.complete as jest.Mock).mock.calls[0];
    rerenderCurrent(tree, { ...detail, execution: { ...detail.execution, version: 8 } });
    await renderer.act(async () => { confirm(); await tick(); });
    expect(workoutExecutionApi.complete).toHaveBeenCalledTimes(2);
    expect((workoutExecutionApi.complete as jest.Mock).mock.calls[1]).toEqual(first);
    renderer.act(() => tree.unmount());
  });

  it('preserves recorded zero, missing values, set units and unavailable units in terminal results', () => {
    const result = { ...detail, execution: { ...detail.execution, status: 'ABORTED' as const }, exercises: [{
      ...detail.exercises[0], sets: [{ ...set, completionStatus: 'FAILED' as const, repetitions: 0,
        loadValue: 0, distanceValue: 250, distanceUnitId: 92, distanceUnit: null, rpe: 8, rir: 0, tempo: '3010', note: 'Giữ nguyên', completedAt: TIME_BOUNDARY }],
    }] };
    (useWorkoutExecutionDetail as jest.Mock).mockReturnValue({ data: result, isPending: false, isError: false, refetch: jest.fn() });
    const tree = render(<WorkoutExecutionDetailScreen executionId={detail.execution.executionId} />);
    const json = JSON.stringify(tree.toJSON());
    ['Số lần: 0', 'Mức tải: 0 lb', 'Quãng đường: 250 · đơn vị không xác định', 'Thời lượng: Chưa ghi', 'RPE: 8', 'RIR: 0', '3010', 'Giữ nguyên', 'Không hoàn thành'].forEach((text) => expect(json).toContain(text));
    expect(tree.root.findAllByProps({ accessibilityRole: 'radio' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('does not infer actual measurement units from the prescription when the set unit is missing', () => {
    const result = { ...detail, execution: { ...detail.execution, status: 'COMPLETED' as const }, exercises: [{
      ...detail.exercises[0], sets: [{ ...set, loadValue: 75, loadUnitId: null, loadUnit: null, distanceValue: 250, distanceUnitId: null, distanceUnit: null }],
    }] };
    (useWorkoutExecutionDetail as jest.Mock).mockReturnValue({ data: result, isPending: false, isError: false, refetch: jest.fn() });
    const tree = render(<WorkoutExecutionDetailScreen executionId={detail.execution.executionId} />);
    const json = JSON.stringify(tree.toJSON());
    expect(json).toContain('Mức tải: 75 · đơn vị không xác định');
    expect(json).toContain('Quãng đường: 250 · đơn vị không xác định');
    expect(json).not.toContain('Mức tải: 75 kg');
    renderer.act(() => tree.unmount());
  });

  it('blocks a retained substitution callback after terminal data arrives', async () => {
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thay bài tập' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Chọn bài thay thế thử nghiệm' }).props.onPress());
    const substitute = tree.root.findByProps({ accessibilityLabel: 'Goblet · Mặc định' }).props.onPress;
    rerenderCurrent(tree, { ...detail, execution: { ...detail.execution, status: 'COMPLETED', version: 8 } });
    await renderer.act(async () => { substitute(); await tick(); });
    expect(workoutExecutionApi.substitute).not.toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('ignores a late terminal response after switching accounts', async () => {
    let resolve!: (value: unknown) => void;
    (workoutExecutionApi.complete as jest.Mock).mockReturnValue(new Promise((done) => { resolve = done; }));
    const tree = render(<CurrentWorkoutExecutionScreen />);
    renderer.act(() => tree.root.findByProps({ testID: 'complete-workout-button' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.onPress(); await tick(); });
    (useAuth as jest.Mock).mockReturnValue({ user: { ...user, id: 'student-2' }, activeCapability: 'STUDENT' });
    rerenderCurrent(tree, { ...detail, execution: { ...detail.execution, studentId: 'student-2', executionId: 'other-execution' } });
    await renderer.act(async () => { resolve({ ...detail, execution: { ...detail.execution, status: 'COMPLETED', version: 8 } }); await tick(); });
    expect(mockReplace).not.toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('does not replace a newer current workout with a delayed response for the previous workout', async () => {
    let resolve!: (value: unknown) => void;
    (workoutExecutionApi.upsertSet as jest.Mock).mockReturnValue(new Promise((done) => { resolve = done; }));
    const tree = render(<WorkoutExecutionDetailScreen executionId={detail.execution.executionId} />);
    const client = clients[clients.length - 1];
    client.setQueryDefaults(workoutExecutionQueryKeys.current(user.id), { gcTime: Infinity });
    await renderer.act(async () => { tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` }).props.onPress(); await tick(); });
    const next = { ...detail, execution: { ...detail.execution, executionId: 'next-workout', version: 1 } };
    client.setQueryData(workoutExecutionQueryKeys.current(user.id), next);
    await renderer.act(async () => { resolve({ ...detail, execution: { ...detail.execution, version: 8 } }); await tick(); });
    expect(client.getQueryData(workoutExecutionQueryKeys.current(user.id))).toEqual(next);
    renderer.act(() => tree.unmount());
  });

  it.each(['capability', 'suspended'])('does not navigate or announce if %s changes while success invalidation is pending', async (change) => {
    (workoutExecutionApi.complete as jest.Mock).mockResolvedValue({ ...detail, execution: { ...detail.execution, status: 'COMPLETED', version: 8 } });
    const tree = render(<CurrentWorkoutExecutionScreen />);
    const client = clients[clients.length - 1];
    let resolve!: () => void;
    jest.spyOn(client, 'invalidateQueries').mockReturnValue(new Promise<void>((done) => { resolve = done; }));
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility');
    renderer.act(() => tree.root.findByProps({ testID: 'complete-workout-button' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.onPress(); await tick(); });
    (useAuth as jest.Mock).mockReturnValue(change === 'suspended' ? { user: { ...user, status: 'SUSPENDED' }, activeCapability: 'STUDENT' } : { user, activeCapability: 'TRAINER' });
    rerenderCurrent(tree, detail);
    await renderer.act(async () => { resolve(); await tick(); });
    expect(mockReplace).not.toHaveBeenCalled();
    expect(announce).not.toHaveBeenCalled();
    announce.mockRestore();
    renderer.act(() => tree.unmount());
  });

  it('renders history and opens the selected execution', () => {
    const tree = render(<WorkoutHistoryScreen />);
    const open = tree.root.findByProps({ accessibilityLabel: 'Xem chi tiết buổi tập' });
    expect(open.props.accessibilityRole).toBe('button');
    renderer.act(() => open.props.onPress());
    expect(mockPush).toHaveBeenCalledWith(`/workouts/${detail.execution.executionId}`);
    renderer.act(() => tree.unmount());
  });

  it('provides retry for history network failures', () => {
    const refetch = jest.fn();
    (useWorkoutExecutionHistory as jest.Mock).mockReturnValue({
      data: undefined,
      isPending: false,
      isError: true,
      error: new ApiError(0, 'network'),
      hasNextPage: false,
      isFetchingNextPage: false,
      refetch,
      fetchNextPage: jest.fn(),
    });
    const tree = render(<WorkoutHistoryScreen />);
    expect(JSON.stringify(tree.toJSON())).toContain('Không thể kết nối mạng');
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress());
    expect(refetch).toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });
  it.each([TIME_BOUNDARY, null, '', 'bad', '2026-11-27', '2026-11-27T08:00:00'])('time display: performed instants use Vietnam, frozen planned instants retain unknown context for %s', async (instant) => {
    const execution = { ...detail.execution, status: 'COMPLETED', performedStartedAt: instant, performedEndedAt: instant, plannedStartAt: instant };
    (useAuth as jest.Mock).mockReturnValue({ user: { ...user, timezone: 'America/New_York' }, activeCapability: 'STUDENT' });
    (useWorkoutExecutionDetail as jest.Mock).mockReturnValue({ data: { ...detail, execution }, isPending: false, isError: false });
    (useWorkoutExecutionHistory as jest.Mock).mockReturnValue({ data: { pages: [{ items: [execution] }] }, isPending: false, isError: false });
    const expected = instant === TIME_BOUNDARY ? [`Bắt đầu thực tế: ${VIETNAM_MOMENT}`, 'Thời gian dự kiến: 17:00 26 thg 11, 2026', 'UTC', 'múi giờ của bản ghi cũ chưa xác định'] : ['Chưa có dữ liệu'];
    await expectTimeDisplay(() => render(<WorkoutExecutionDetailScreen executionId={execution.executionId} />), instant && instant !== TIME_BOUNDARY ? ['Bắt đầu thực tế: Chưa rõ thời điểm', 'Thời gian dự kiến: Chưa rõ thời điểm'] : expected);
    await expectTimeDisplay(() => render(<WorkoutHistoryScreen />), instant && instant !== TIME_BOUNDARY ? ['Bắt đầu thực tế: Chưa rõ thời điểm', 'Thời gian dự kiến: Chưa rõ thời điểm'] : expected);
  });
  it.each([
    ['2026-03-08T06:59:00Z', '13:59 8 thg 3, 2026', '06:59 8 thg 3, 2026'],
    ['2026-03-08T07:00:00Z', '14:00 8 thg 3, 2026', '07:00 8 thg 3, 2026'],
  ])('time display: account DST does not reinterpret frozen planned history at %s', async (instant, actual, planned) => {
    (useAuth as jest.Mock).mockReturnValue({ user: { ...user, timezone: 'America/New_York' }, activeCapability: 'STUDENT' });
    (useWorkoutExecutionHistory as jest.Mock).mockReturnValue({ data: { pages: [{ items: [{ ...detail.execution, performedStartedAt: instant, plannedStartAt: instant }] }] }, isPending: false, isError: false });
    await expectTimeDisplay(() => render(<WorkoutHistoryScreen />), [`Bắt đầu thực tế: ${actual}`, `Thời gian dự kiến: ${planned} · Giờ chuẩn quốc tế (UTC) (UTC+00:00)`, 'múi giờ của bản ghi cũ chưa xác định']);
  });

});
