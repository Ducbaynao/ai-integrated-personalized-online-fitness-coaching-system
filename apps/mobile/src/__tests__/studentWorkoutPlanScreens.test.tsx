import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { StudentWorkoutPlansScreen } from '@/features/workout-plan/StudentWorkoutPlansScreen';
import { WorkoutPlanDetailScreen } from '@/features/workout-plan/WorkoutPlanDetailScreen';
import { WorkoutPlanHistoryScreen } from '@/features/workout-plan/WorkoutPlanHistoryScreen';
import { WorkoutPlanBuilderScreen } from '@/features/workout-plan/WorkoutPlanBuilderScreen';
import { useCurrentWorkoutPlan, useWorkoutPlan, useWorkoutPlans, useWorkoutPlanVersion, useWorkoutPlanVersions } from '@/features/workout-plan/workoutPlanQueries';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { useExerciseDetail } from '@/features/exercise/exerciseQueries';

const mockPush = jest.fn(); const mockReplace = jest.fn(); const mockBack = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, replace: mockReplace, back: mockBack }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/workout-plan/workoutPlanQueries', () => {
  const actual = jest.requireActual('@/features/workout-plan/workoutPlanQueries');
  return { ...actual, useCurrentWorkoutPlan: jest.fn(), useWorkoutPlans: jest.fn(), useWorkoutPlan: jest.fn(), useWorkoutPlanVersion: jest.fn(), useWorkoutPlanVersions: jest.fn() };
});
jest.mock('@/features/exercise/exerciseQueries', () => ({ useExerciseDetail: jest.fn(() => ({ data: undefined, isPending: false, isError: false, refetch: jest.fn() })) }));
jest.mock('@/features/exercise/ExercisePicker', () => {
  // Jest hoists factories, so these dependencies must be resolved lazily inside the factory.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const MockReact = require('react');
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const { Pressable: MockPressable, Text: MockText } = require('react-native');
  return { ExercisePicker: ({ onConfirm }: { onConfirm: (exercise: unknown) => void }) => MockReact.createElement(MockPressable, { accessibilityRole: 'button', accessibilityLabel: 'Chọn Exercise thử nghiệm', onPress: () => onConfirm({ id: 'exercise-1', name: 'Barbell Squat' }) }, MockReact.createElement(MockText, null, 'Barbell Squat')) };
});
jest.mock('@/services/workoutPlanApi', () => ({ workoutPlanApi: { create: jest.fn(), updateDraft: jest.fn(), activate: jest.fn(), transition: jest.fn(), publish: jest.fn(), successor: jest.fn() } }));

const user = { id: 'student-1', capabilities: { hasStudentProfile: true, hasTrainerProfile: false } };
const response = (errorCode: string) => ({ errorCode, message: errorCode, timestamp: '', requestId: '', fieldErrors: [] });
const plan = { id: 'plan-1', studentId: 'student-1', fitnessGoalId: null, coachingPeriodId: 'period-1', name: 'Strength Base', description: 'Base plan', source: 'MANUAL', status: 'ACTIVE', aggregateVersion: 2, decisionOwnerType: 'STUDENT', decisionOwnerId: 'student-1', basedOnPlanId: null, basedOnPlanVersionId: null, createdAt: '', updatedAt: '', archivedAt: null, currentVersionId: 'version-1', currentVersionNumber: 1, currentVersionEffectiveFrom: '2026-10-01T00:00:00Z', currentVersionLockedAt: '2026-10-01T00:00:00Z', readContext: 'CURRENT' };
const version = { id: 'version-1', planId: 'plan-1', versionNumber: 1, effectiveFrom: '2026-10-01T00:00:00Z', effectiveUntil: null, changeLevel: 'INITIAL', changeReason: null, changeSummary: null, createdBy: 'student-1', createdAt: '', lockedAt: '2026-10-01T00:00:00Z', current: true, readContext: 'CURRENT' };
const session = { id: 'session-1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1, name: 'Upper A', focus: null, estimatedDurationMinutes: 45, notes: null, prescriptions: [{ id: 'rx-1', exerciseVariationId: 'variation-1', sequenceNumber: 1, targetSets: 3, targetRepsMin: 0, targetRepsMax: 0, targetLoad: 0, restSeconds: 0, durationSeconds: 0, instructions: null, exercise: { variationId: 'variation-1', exerciseId: null, exerciseName: null, variationName: null, state: 'UNAVAILABLE', canonicalExerciseId: 'canonical-1', canonicalExerciseName: 'Barbell Squat' } }] };
const clients: QueryClient[] = [];
function render(element: React.ReactElement) { const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } }); clients.push(client); let tree!: renderer.ReactTestRenderer; renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>); }); return tree; }

describe('B04 Student Workout Plan screens', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user, activeCapability: 'STUDENT' });
    (useCurrentWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan, currentVersion: version }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (useWorkoutPlans as jest.Mock).mockReturnValue({ data: { pages: [{ items: [plan] }] }, isPending: false, isError: false, error: null, hasNextPage: false, isFetchingNextPage: false, refetch: jest.fn(), fetchNextPage: jest.fn() });
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan, currentVersion: version }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: { version, sessions: [session] }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (useWorkoutPlanVersions as jest.Mock).mockReturnValue({ data: { pages: [{ items: [version] }] }, isPending: false, isError: false, error: null, hasNextPage: false, isFetchingNextPage: false, refetch: jest.fn(), fetchNextPage: jest.fn() });
  });
  afterEach(() => { clients.splice(0).forEach((client) => client.clear()); });

  it('renders authoritative current plan and opens its detail', () => {
    const tree = render(<StudentWorkoutPlansScreen />);
    expect(JSON.stringify(tree.toJSON())).toContain('buổi trong phiên bản hiện tại');
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Xem kế hoạch hiện tại' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/workout-plans/plan-1');
    renderer.act(() => tree.unmount());
  });

  it('separates current 404 empty from retryable server errors', () => {
    (useCurrentWorkoutPlan as jest.Mock).mockReturnValue({ data: undefined, isPending: false, isError: true, error: new ApiError(404, 'missing'), refetch: jest.fn() });
    let tree = render(<StudentWorkoutPlansScreen />); expect(tree.root.findByProps({ testID: 'current-plan-empty' })).toBeTruthy(); renderer.act(() => tree.unmount());
    const refetch = jest.fn(); (useCurrentWorkoutPlan as jest.Mock).mockReturnValue({ data: undefined, isPending: false, isError: true, error: new ApiError(500, 'server'), refetch });
    tree = render(<StudentWorkoutPlansScreen />); renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress()); expect(refetch).toHaveBeenCalled(); renderer.act(() => tree.unmount());
  });

  it('renders and retries current-version summary loading failures independently', () => {
    const refetch = jest.fn();
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: undefined, isPending: false, isError: true, error: new ApiError(500, 'server'), refetch });
    const tree = render(<StudentWorkoutPlansScreen />);
    expect(tree.root.findByProps({ testID: 'current-plan-content-error' })).toBeTruthy();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress());
    expect(refetch).toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('renders unavailable historical Exercise without replacing the original reference', () => {
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan: { ...plan, status: 'COMPLETED', readContext: 'HISTORICAL' }, currentVersion: version }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    const tree = render(<WorkoutPlanDetailScreen planId="plan-1" />);
    expect(tree.root.findAll((node) => node.children.includes('Không còn khả dụng'))).not.toHaveLength(0);
    expect(JSON.stringify(tree.toJSON())).toContain('tham khảo, không thay thế bản ghi gốc.');
    expect(tree.root.findAllByProps({ testID: 'edit-plan-button' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('confirms activation with authoritative aggregate version and blocks duplicate submit', async () => {
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan: { ...plan, status: 'DRAFT' }, currentVersion: { ...version, lockedAt: null } }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    let finish!: (value: unknown) => void; const pending = new Promise((resolve) => { finish = resolve; }); (workoutPlanApi.activate as jest.Mock).mockReturnValue(pending);
    const tree = render(<WorkoutPlanDetailScreen planId="plan-1" />); renderer.act(() => tree.root.findByProps({ testID: 'activate-plan-button' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-plan-command' }).props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(tree.root.findByProps({ testID: 'confirm-plan-command' }).props.disabled).toBe(true);
    expect(workoutPlanApi.activate).toHaveBeenCalledWith('plan-1', 2, expect.any(String));
    await renderer.act(async () => { finish({}); await pending; await new Promise((resolve) => setTimeout(resolve, 0)); }); renderer.act(() => tree.unmount());
  });

  it('shows pause/complete/archive for a Student-owned active plan', () => {
    const tree = render(<WorkoutPlanDetailScreen planId="plan-1" />);
    expect(tree.root.findByProps({ accessibilityLabel: 'Tạm dừng kế hoạch' })).toBeTruthy();
    expect(tree.root.findByProps({ accessibilityLabel: 'Hoàn thành kế hoạch' })).toBeTruthy();
    expect(tree.root.findByProps({ accessibilityLabel: 'Lưu trữ kế hoạch' })).toBeTruthy();
    renderer.act(() => tree.unmount());
  });

  it('resumes a paused Student-owned plan with authoritative aggregate version', async () => {
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan: { ...plan, status: 'PAUSED' }, currentVersion: version }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (workoutPlanApi.transition as jest.Mock).mockResolvedValue({ planId: 'plan-1' });
    const tree = render(<WorkoutPlanDetailScreen planId="plan-1" />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tiếp tục kế hoạch' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-plan-command' }).props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(workoutPlanApi.transition).toHaveBeenCalledWith('plan-1', 2, 'ACTIVE', null, expect.any(String));
    renderer.act(() => tree.unmount());
  });

  it('refreshes authoritative plan state after a stale lifecycle conflict', async () => {
    const refetch = jest.fn();
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan, currentVersion: version }, isPending: false, isError: false, error: null, refetch });
    (workoutPlanApi.transition as jest.Mock).mockRejectedValue(new ApiError(409, 'stale', response('WORKOUT_PLAN_VERSION_CONFLICT')));
    const tree = render(<WorkoutPlanDetailScreen planId="plan-1" />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tạm dừng kế hoạch' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-plan-command' }).props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(refetch).toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('creates a complete draft snapshot and preserves one command while pending', async () => {
    let finish!: (value: unknown) => void; const pending = new Promise((resolve) => { finish = resolve; }); (workoutPlanApi.create as jest.Mock).mockReturnValue(pending);
    const tree = render(<WorkoutPlanBuilderScreen mode="create" />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.onChangeText('My Plan'));
    await renderer.act(async () => { tree.root.findByProps({ testID: 'save-workout-plan-button' }).props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(tree.root.findByProps({ testID: 'save-workout-plan-button' }).props.disabled).toBe(true);
    expect(workoutPlanApi.create).toHaveBeenCalledWith('student-1', { name: 'My Plan', description: null, sessions: [] }, expect.any(String));
    await renderer.act(async () => { finish({ planId: 'new-plan' }); await pending; }); expect(mockReplace).toHaveBeenCalledWith('/workout-plans/new-plan'); renderer.act(() => tree.unmount());
  });

  it('requires reason and preserves the materialized-workout warning for publication', () => {
    const tree = render(<WorkoutPlanBuilderScreen mode="publish" planId="plan-1" versionId="version-1" />);
    expect(JSON.stringify(tree.toJSON())).toContain('Các buổi tập đã được tạo trước đó sẽ không tự động bị thay đổi.');
    expect(tree.root.findByProps({ testID: 'save-workout-plan-button' }).props.disabled).toBe(true);
    renderer.act(() => tree.unmount());
  });

  it('publishes a complete ACTIVE-exercise snapshot with reason and expected version', async () => {
    const activeSession = { ...session, prescriptions: session.prescriptions.map((item) => ({ ...item, exercise: { ...item.exercise, exerciseId: 'exercise-1', exerciseName: 'Barbell Squat', variationName: 'High Bar', state: 'ACTIVE' } })) };
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: { version, sessions: [activeSession] }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (workoutPlanApi.publish as jest.Mock).mockResolvedValue({ planId: 'plan-1' });
    const tree = render(<WorkoutPlanBuilderScreen mode="publish" planId="plan-1" versionId="version-1" />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Lý do thay đổi' }).props.onChangeText('Tăng tiến tập luyện'));
    await renderer.act(async () => { tree.root.findByProps({ testID: 'save-workout-plan-button' }).props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(workoutPlanApi.publish).toHaveBeenCalledWith('plan-1', 2, expect.objectContaining({ reason: 'Tăng tiến tập luyện', sessions: [expect.objectContaining({ name: 'Upper A', prescriptions: [expect.objectContaining({ exerciseVariationId: 'variation-1' })] })] }), expect.any(String));
    renderer.act(() => tree.unmount());
  });

  it('reuses Exercise Picker then requires an explicit Variation selection', () => {
    (useExerciseDetail as jest.Mock).mockReturnValue({ data: { variations: [{ id: 'variation-1', name: 'High Bar', defaultVariation: true }] }, isPending: false, isError: false, refetch: jest.fn() });
    const tree = render(<WorkoutPlanBuilderScreen mode="create" />);
    renderer.act(() => tree.root.findByProps({ testID: 'add-session-button' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thêm bài tập' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Chọn Exercise thử nghiệm' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'variation-picker' })).toBeTruthy();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Chọn biến thể High Bar' }).props.onPress());
    expect(JSON.stringify(tree.toJSON())).toContain('Barbell Squat');
    expect(JSON.stringify(tree.toJSON())).toContain('High Bar');
    renderer.act(() => tree.unmount());
  });

  it('keeps history immutable and creates an explicit successor from a Trainer version', async () => {
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan: { ...plan, decisionOwnerType: 'TRAINER', decisionOwnerId: 'trainer-1' }, currentVersion: version }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (workoutPlanApi.successor as jest.Mock).mockResolvedValue({ planId: 'successor-1' });
    const tree = render(<WorkoutPlanHistoryScreen planId="plan-1" />);
    expect(JSON.stringify(tree.toJSON())).toContain('không có thao tác chỉnh sửa trực tiếp.');
    renderer.act(() => tree.root.findByProps({ testID: 'history-successor-button' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-history-successor' }).props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(workoutPlanApi.successor).toHaveBeenCalledWith('plan-1', 'version-1', 'Strength Base - tự tập', expect.any(String));
    expect(mockReplace).toHaveBeenCalledWith('/workout-plans/successor-1');
    renderer.act(() => tree.unmount());
  });
});
