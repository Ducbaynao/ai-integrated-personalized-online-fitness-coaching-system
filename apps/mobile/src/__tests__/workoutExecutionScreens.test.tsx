import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import {
  CurrentWorkoutExecutionScreen,
  WorkoutExecutionDetailScreen,
} from '@/features/workout-execution/WorkoutExecutionScreen';
import { WorkoutHistoryScreen } from '@/features/workout-execution/WorkoutHistoryScreen';
import {
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
    const save = tree.root.findByProps({ testID: `save-workout-set-${set.clientSetId}` });
    await renderer.act(async () => {
      save.props.onPress();
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
      tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.onPress();
      await new Promise((resolve) => setTimeout(resolve, 0));
    });
    expect(workoutExecutionApi.complete).toHaveBeenCalledWith(
      detail.execution.executionId,
      7,
      { overallRpe: null, sessionNote: null },
      expect.any(String)
    );
    expect(tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.disabled).toBe(true);
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
      tree.root.findByProps({ testID: 'confirm-workout-terminal' }).props.onPress();
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

  it('renders terminal history detail as read-only and preserves planned/performed timestamps', () => {
    const terminal = {
      ...detail,
      execution: {
        ...detail.execution,
        status: 'PARTIALLY_COMPLETED' as const,
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
    expect(JSON.stringify(tree.toJSON())).toContain('Hoàn thành một phần');
    expect(JSON.stringify(tree.toJSON())).toContain('Bắt đầu thực tế');
    expect(JSON.stringify(tree.toJSON())).toContain('Thời gian dự kiến');
    expect(JSON.stringify(tree.toJSON())).toContain('mức tải 60 kg');
    expect(tree.root.findByProps({ accessibilityLabel: 'Mức tải, lb' })).toBeTruthy();
    expect(tree.root.findAllByProps({ testID: 'complete-workout-button' })).toHaveLength(0);
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
});
