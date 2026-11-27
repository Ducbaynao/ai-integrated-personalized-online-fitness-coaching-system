import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { WorkoutPlanBuilderScreen } from '@/features/workout-plan/WorkoutPlanBuilderScreen';
import { StudentWorkoutPlansScreen } from '@/features/workout-plan/StudentWorkoutPlansScreen';
import { coachingApi } from '@/services/coachingApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { ApiError } from '@/types/auth';

const mockPush = jest.fn(), mockReplace = jest.fn();
let mockUser = { id: 'student-1', capabilities: { hasStudentProfile: true } };
let mockCapability = 'STUDENT';
let mockEpoch = 0;
let mockKeyCount = 0;
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, replace: mockReplace, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: () => ({ user: mockUser, activeCapability: mockCapability }) }));
jest.mock('@/services/apiClient', () => ({ getAuthSessionBinding: () => ({ authEpoch: mockEpoch, capabilityEpoch: mockEpoch }) }));
jest.mock('@/services/coachingApi', () => ({ coachingApi: { getCurrentContext: jest.fn(), getPeriods: jest.fn(), startSelfDirected: jest.fn() }, createCommandKey: () => `command-${++mockKeyCount}` }));
jest.mock('@/services/workoutPlanApi', () => ({ workoutPlanApi: { create: jest.fn() } }));
jest.mock('@/features/workout-plan/workoutPlanQueries', () => ({
  useWorkoutPlan: () => ({}), useWorkoutPlanVersion: () => ({}),
  useCurrentWorkoutPlan: () => ({}), useWorkoutPlans: () => ({}),
  workoutPlanQueryKeys: { student: (id: string) => ['plan', id] },
}));
jest.mock('@/features/exercise/exerciseQueries', () => ({ useExerciseDetail: () => ({}) }));
jest.mock('@/features/exercise/ExercisePicker', () => ({ ExercisePicker: () => null }));
jest.mock('@/features/workout-plan/components/AiWorkoutProposalModal', () => ({ AiWorkoutProposalModal: () => null }));

const empty = { relationship: null, resume: null, currentPeriod: null };
const self = { ...empty, currentPeriod: { id: 'period-1', mode: 'SELF_DIRECTED', relationshipId: null, trainerId: null, startedAt: '2020-01-01T00:00:00Z', endedAt: null } };
const network = new ApiError(0, 'offline');
const response = (errorCode: string) => ({ errorCode, message: errorCode, timestamp: '', requestId: '', fieldErrors: [] });
function deferred<T>() { let resolve!: (value: T) => void; let reject!: (error: unknown) => void; const promise = new Promise<T>((yes, no) => { resolve = yes; reject = no; }); return { promise, resolve, reject }; }
const flush = async () => { await renderer.act(async () => { await new Promise(resolve => setTimeout(resolve, 10)); }); };
let tree: renderer.ReactTestRenderer;
let client: QueryClient;
function render(element = <WorkoutPlanBuilderScreen mode="create" />) {
  client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>); });
}
const press = (testID: string) => renderer.act(() => tree.root.findByProps({ testID }).props.onPress());
const text = () => JSON.stringify(tree.toJSON());
async function chooseSelf() { press('choose-self-directed'); press('confirm-self-directed'); await flush(); }

describe('Student plan context setup and draft recovery', () => {
  beforeEach(() => {
    jest.clearAllMocks(); mockEpoch = 0; mockKeyCount = 0; mockCapability = 'STUDENT';
    mockUser = { id: 'student-1', capabilities: { hasStudentProfile: true } };
    (coachingApi.getCurrentContext as jest.Mock).mockReset().mockResolvedValue(empty);
    (coachingApi.getPeriods as jest.Mock).mockReset().mockResolvedValue({ items: [], page: 0, size: 1 });
    (coachingApi.startSelfDirected as jest.Mock).mockReset().mockResolvedValue(self);
    (workoutPlanApi.create as jest.Mock).mockReset().mockResolvedValue({ planId: 'plan-1' });
  });
  afterEach(() => { renderer.act(() => tree?.unmount()); client?.clear(); });

  it('guides a Student without a period in the hub and never opens the form or creates a plan', async () => {
    render(<StudentWorkoutPlansScreen />); await flush();
    expect(text()).toContain('Chọn chế độ trước khi tạo kế hoạch');
    expect(tree.root.findByProps({ testID: 'create-workout-plan-button' }).props.disabled).toBe(true);
    press('create-workout-plan-button'); expect(mockPush).not.toHaveBeenCalled();
    expect(coachingApi.startSelfDirected).not.toHaveBeenCalled(); expect(workoutPlanApi.create).not.toHaveBeenCalled();
  });

  it('requires explicit confirmation and a successful fresh context read before showing the form', async () => {
    render(); await flush();
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Tên kế hoạch' })).toHaveLength(0);
    press('choose-self-directed'); expect(coachingApi.startSelfDirected).not.toHaveBeenCalled();
    const reload = deferred<typeof self>();
    (coachingApi.getCurrentContext as jest.Mock).mockReturnValueOnce(reload.promise);
    press('confirm-self-directed'); await flush();
    expect(coachingApi.startSelfDirected).toHaveBeenCalledTimes(1);
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Tên kế hoạch' })).toHaveLength(0);
    await renderer.act(async () => reload.resolve(self)); await flush();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.onChangeText('My plan'));
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue(self);
    press('save-workout-plan-button'); await flush();
    expect(workoutPlanApi.create).toHaveBeenCalledWith('student-1', { name: 'My plan', description: null, sessions: [] }, expect.any(String));
    expect(mockReplace).toHaveBeenCalledWith('/workout-plans/plan-1');
  });

  it('never treats loading or a network/permission read failure as self-directed', async () => {
    const load = deferred<typeof empty>(); (coachingApi.getCurrentContext as jest.Mock).mockReturnValueOnce(load.promise);
    render(); expect(text()).toContain('Đang kiểm tra chế độ tập');
    await renderer.act(async () => load.reject(network)); await flush();
    expect(text()).toContain('Không thể kết nối mạng');
    expect(tree.root.findAllByProps({ testID: 'choose-self-directed' })).toHaveLength(0);
    (coachingApi.getCurrentContext as jest.Mock).mockRejectedValueOnce(new ApiError(403, 'denied'));
    press('refresh-workout-context'); await flush(); expect(text()).toContain('không có quyền');
    expect(coachingApi.getPeriods).not.toHaveBeenCalled(); expect(workoutPlanApi.create).not.toHaveBeenCalled();
  });

  it.each(['HUMAN_COACH', 'RELATIONSHIP', 'FUTURE'])('blocks %s without changing coaching authority', async kind => {
    if (kind === 'HUMAN_COACH') (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue({ ...self, currentPeriod: { ...self.currentPeriod, mode: 'HUMAN_COACH', trainerId: 'pt', relationshipId: 'rel' } });
    if (kind === 'RELATIONSHIP') (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue({ ...empty, relationship: { id: 'rel', status: 'ACTIVE' } });
    if (kind === 'FUTURE') (coachingApi.getPeriods as jest.Mock).mockResolvedValue({ items: [{ ...self.currentPeriod, startedAt: '2099-01-01T00:00:00Z' }] });
    render(); await flush();
    expect(tree.root.findAllByProps({ testID: 'choose-self-directed' })).toHaveLength(0);
    press('open-context-coaching'); expect(mockPush).toHaveBeenCalledWith('/coaching');
    expect(coachingApi.startSelfDirected).not.toHaveBeenCalled(); expect(workoutPlanApi.create).not.toHaveBeenCalled();
  });

  it('explains expired history and offers an explicit new self-directed period', async () => {
    (coachingApi.getPeriods as jest.Mock).mockResolvedValue({ items: [{ ...self.currentPeriod, endedAt: '2021-01-01T00:00:00Z' }] });
    render(); await flush(); expect(text()).toContain('Kỳ huấn luyện trước đã kết thúc');
    expect(tree.root.findByProps({ testID: 'choose-self-directed' })).toBeTruthy();
  });

  it('preserves the entered draft after server context loss and setup failure, then retries the same setup intent', async () => {
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue(self);
    (workoutPlanApi.create as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'missing', response('COACHING_PERIOD_REQUIRED')));
    render(); await flush();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.onChangeText('Safe draft'));
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Mô tả kế hoạch' }).props.onChangeText('Draft details'));
    press('add-session-button');
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tên buổi 1' }).props.onChangeText('Recovery session'));
    // Preflight succeeds; context disappears between that read and the domain command.
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValueOnce(self).mockResolvedValue(empty);
    press('save-workout-plan-button'); await flush(); await flush();
    expect(tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.value).toBe('Safe draft');
    expect(text()).toContain('được giữ trong màn hình này');
    (coachingApi.startSelfDirected as jest.Mock).mockRejectedValueOnce(network);
    await chooseSelf(); expect(text()).toContain('Không thể kết nối mạng');
    expect(tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.value).toBe('Safe draft');
    expect(tree.root.findByProps({ accessibilityLabel: 'Mô tả kế hoạch' }).props.value).toBe('Draft details');
    expect(tree.root.findByProps({ accessibilityLabel: 'Tên buổi 1' }).props.value).toBe('Recovery session');
    press('refresh-workout-context'); await flush();
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue(self);
    await chooseSelf();
    const calls = (coachingApi.startSelfDirected as jest.Mock).mock.calls;
    expect(calls[0][0]).toBe(calls[1][0]);
    press('save-workout-plan-button'); await flush();
    expect(workoutPlanApi.create).toHaveBeenLastCalledWith('student-1', expect.objectContaining({ name: 'Safe draft', description: 'Draft details', sessions: [expect.objectContaining({ name: 'Recovery session' })] }), (workoutPlanApi.create as jest.Mock).mock.calls[0][2]);
  });

  it('preserves the form and blocks create if the save preflight cannot read context', async () => {
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue(self); render(); await flush();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.onChangeText('Offline draft'));
    (coachingApi.getCurrentContext as jest.Mock).mockRejectedValue(network);
    press('save-workout-plan-button'); await flush();
    expect(workoutPlanApi.create).not.toHaveBeenCalled();
    expect(tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.value).toBe('Offline draft');
  });

  it('blocks duplicate setup and never opens a form when the post-command context reload fails', async () => {
    const setup = deferred<typeof self>();
    (coachingApi.startSelfDirected as jest.Mock).mockReturnValueOnce(setup.promise);
    render(); await flush(); press('choose-self-directed');
    const confirm = tree.root.findByProps({ testID: 'confirm-self-directed' }).props.onPress;
    renderer.act(() => { confirm(); confirm(); }); await flush();
    expect(coachingApi.startSelfDirected).toHaveBeenCalledTimes(1);
    (coachingApi.getCurrentContext as jest.Mock).mockRejectedValueOnce(network);
    await renderer.act(async () => setup.resolve(self)); await flush();
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Tên kế hoạch' })).toHaveLength(0);
    expect(text()).toContain('Không thể kết nối mạng');
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue(self);
    press('refresh-workout-context'); await flush();
    expect(tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' })).toBeTruthy();
    expect(coachingApi.startSelfDirected).toHaveBeenCalledTimes(1);
  });

  it('does not navigate or publish old mutation results into a new account', async () => {
    const save = deferred<{ planId: string }>();
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue(self);
    (workoutPlanApi.create as jest.Mock).mockReturnValueOnce(save.promise);
    render(); await flush();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tên kế hoạch' }).props.onChangeText('Old account draft'));
    press('save-workout-plan-button'); await flush();
    mockEpoch++; mockUser = { ...mockUser, id: 'student-2' };
    (coachingApi.getCurrentContext as jest.Mock).mockResolvedValue(empty);
    renderer.act(() => tree.update(<QueryClientProvider client={client}><WorkoutPlanBuilderScreen mode="create" /></QueryClientProvider>));
    await renderer.act(async () => save.resolve({ planId: 'old-plan' })); await flush();
    expect(mockReplace).not.toHaveBeenCalled(); expect(text()).not.toContain('Old account draft');
  });

  it.each(['load-account', 'load-capability', 'setup-account', 'setup-capability'])('ignores an old %s response', async scenario => {
    const [phase, change] = scenario.split('-');
    const old = deferred<typeof self>();
    if (phase === 'load') (coachingApi.getCurrentContext as jest.Mock).mockReturnValueOnce(old.promise);
    else (coachingApi.startSelfDirected as jest.Mock).mockReturnValueOnce(old.promise);
    render(); await flush();
    if (phase === 'setup') { press('choose-self-directed'); press('confirm-self-directed'); await flush(); }
    mockEpoch += 1;
    if (change === 'account') mockUser = { ...mockUser, id: 'student-2' };
    else mockCapability = 'TRAINER';
    renderer.act(() => tree.update(<QueryClientProvider client={client}><WorkoutPlanBuilderScreen mode="create" /></QueryClientProvider>));
    const reads = (coachingApi.getCurrentContext as jest.Mock).mock.calls.length;
    await renderer.act(async () => old.resolve(self)); await flush();
    expect(coachingApi.getCurrentContext).toHaveBeenCalledTimes(reads);
    if (change === 'capability') expect(text()).toContain('Chỉ dành cho Học viên');
    else expect(text()).toContain('Chọn chế độ trước khi tạo kế hoạch');
    expect(mockReplace).not.toHaveBeenCalled();
    mockCapability = 'STUDENT';
    renderer.act(() => tree.update(<QueryClientProvider client={client}><WorkoutPlanBuilderScreen mode="create" /></QueryClientProvider>));
    await flush(); expect(tree.root.findAllByProps({ accessibilityLabel: 'Tên kế hoạch' })).toHaveLength(0);
  });
});
