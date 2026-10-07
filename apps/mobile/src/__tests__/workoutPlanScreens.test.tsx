import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { useWorkoutPlan, useWorkoutPlans, useWorkoutPlanVersion, useWorkoutPlanVersions } from '@/features/workout-plan/workoutPlanQueries';
import { TrainerProgramScreen } from '@/features/workout-plan/TrainerProgramScreen';
import { WorkoutPlanBuilderScreen } from '@/features/workout-plan/WorkoutPlanBuilderScreen';
import { WorkoutPlanDetailScreen } from '@/features/workout-plan/WorkoutPlanDetailScreen';
import { workoutPlanApi } from '@/services/workoutPlanApi';

const mockPush = jest.fn(); const mockReplace = jest.fn(); const mockBack = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, replace: mockReplace, back: mockBack }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn(), useSharingSummary: jest.fn() }));
jest.mock('@/features/workout-plan/workoutPlanQueries', () => {
  const actual = jest.requireActual('@/features/workout-plan/workoutPlanQueries');
  return { ...actual, useWorkoutPlans: jest.fn(), useWorkoutPlan: jest.fn(), useWorkoutPlanVersions: jest.fn(), useWorkoutPlanVersion: jest.fn() };
});
jest.mock('@/features/exercise/exerciseQueries', () => ({ useExerciseDetail: jest.fn(() => ({ data: undefined, isPending: false, isError: false, refetch: jest.fn() })) }));
jest.mock('@/services/workoutPlanApi', () => ({ workoutPlanApi: { create: jest.fn(), updateDraft: jest.fn(), activate: jest.fn(), publish: jest.fn(), transition: jest.fn() } }));

const relationship = { id: 'relationship-1', studentId: 'student-1', trainerId: 'trainer-1', counterparty: { userId: 'student-1', displayName: 'Học viên An' }, status: 'ACTIVE', direction: 'INCOMING', requestedBy: 'student-1', requestedAt: '', acceptedAt: '', startedAt: '', endedAt: null, version: 1 };
const plan = { id: 'plan-1', studentId: 'student-1', fitnessGoalId: null, coachingPeriodId: 'period-1', name: 'Sức mạnh', description: null, source: 'TRAINER', status: 'DRAFT', aggregateVersion: 2, decisionOwnerType: 'TRAINER', decisionOwnerId: 'trainer-1', basedOnPlanId: null, basedOnPlanVersionId: null, createdAt: '', updatedAt: '', archivedAt: null, currentVersionId: 'version-1', currentVersionNumber: 1, currentVersionEffectiveFrom: '', currentVersionLockedAt: null, readContext: 'CURRENT' };
const version = { id: 'version-1', planId: 'plan-1', versionNumber: 1, effectiveFrom: '', effectiveUntil: null, changeLevel: 'INITIAL', changeReason: null, changeSummary: null, createdBy: 'trainer-1', createdAt: '', lockedAt: null, current: true, readContext: 'CURRENT' };
const clients: QueryClient[] = [];

function render(element: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } }); clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>); });
  return tree;
}
function permission(level: 'VIEW' | 'CONTRIBUTE' | 'MANAGE', state = 'ALLOWED') {
  return { data: { relationshipId: relationship.id, relationshipStatus: 'ACTIVE', evaluatedAt: '', items: [{ dataScope: 'WORKOUT_PLAN', state, decision: 'ALLOW', accessLevel: level, permissionId: 'p1', version: 1, historyFrom: null, historyUntil: null, validFrom: null, validUntil: null }, { dataScope: 'WORKOUT_PLAN_HISTORY', state: 'ALLOWED', decision: 'ALLOW', accessLevel: 'VIEW', permissionId: 'p2', version: 1, historyFrom: null, historyUntil: null, validFrom: null, validUntil: null }] }, isPending: false, isError: false, error: null, refetch: jest.fn() };
}

describe('Trainer workout plan screens', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ activeCapability: 'TRAINER', user: { id: 'trainer-1' } });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship, currentPeriod: { id: 'period-1', mode: 'HUMAN_COACH' } }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (useSharingSummary as jest.Mock).mockReturnValue(permission('MANAGE'));
    (useWorkoutPlans as jest.Mock).mockReturnValue({ data: { pages: [{ items: [plan] }] }, isPending: false, isError: false, error: null, hasNextPage: false, isFetchingNextPage: false, refetch: jest.fn(), fetchNextPage: jest.fn() });
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan, currentVersion: version }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (useWorkoutPlanVersions as jest.Mock).mockReturnValue({ data: { pages: [{ items: [version] }] }, isPending: false, isError: false, error: null, hasNextPage: false, isFetchingNextPage: false, refetch: jest.fn(), fetchNextPage: jest.fn() });
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: { version, sessions: [] }, isPending: false, isError: false, error: null, refetch: jest.fn() });
  });
  afterEach(() => { clients.splice(0).forEach((client) => client.clear()); });

  it('shows MANAGE entry and Student-scoped navigation from TR-03', () => {
    const tree = render(<TrainerProgramScreen relationshipId={relationship.id} />);
    renderer.act(() => tree.root.findByProps({ testID: 'create-plan-button' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/coaching/relationship-1/workout-plans/new');
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Xem kế hoạch' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/students/student-1/workout-plans/plan-1?relationshipId=relationship-1');
    renderer.act(() => tree.unmount());
  });

  it.each(['VIEW', 'CONTRIBUTE'] as const)('keeps %s read-only and never treats it as authoring authority', (level) => {
    (useSharingSummary as jest.Mock).mockReturnValue(permission(level));
    const tree = render(<TrainerProgramScreen relationshipId={relationship.id} />);
    expect(tree.root.findAllByProps({ testID: 'create-plan-button' })).toHaveLength(0);
    expect(tree.root.findByProps({ accessibilityLabel: 'Xem kế hoạch' })).toBeTruthy();
    renderer.act(() => tree.unmount());
  });

  it('renders an explicit empty state only after an allowed read returns no plans', () => {
    (useWorkoutPlans as jest.Mock).mockReturnValue({ data: { pages: [{ items: [] }] }, isPending: false, isError: false, error: null, hasNextPage: false, refetch: jest.fn() });
    const tree = render(<TrainerProgramScreen relationshipId={relationship.id} />);
    expect(tree.root.findByProps({ testID: 'program-empty' })).toBeTruthy();
    renderer.act(() => tree.unmount());
  });

  it('prevents duplicate draft submits while preserving one logical command', async () => {
    let finish!: (value: unknown) => void;
    const pending = new Promise((resolve) => { finish = resolve; });
    (workoutPlanApi.create as jest.Mock).mockReturnValue(pending);
    const tree = render(<WorkoutPlanBuilderScreen mode="create" relationshipId={relationship.id} studentId="student-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'plan-name-input' }).props.onChangeText('Kế hoạch mới'));
    const save = tree.root.findByProps({ testID: 'save-plan-button' });
    await renderer.act(async () => { save.props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(tree.root.findByProps({ testID: 'save-plan-button' }).props.disabled).toBe(true);
    expect(workoutPlanApi.create).toHaveBeenCalledTimes(1);
    expect(workoutPlanApi.create).toHaveBeenCalledWith('student-1', { name: 'Kế hoạch mới', description: null, sessions: [] }, expect.any(String));
    await renderer.act(async () => { finish({ planId: 'plan-1' }); await pending; });
    expect(mockReplace).toHaveBeenCalledWith('/students/student-1/workout-plans/plan-1?relationshipId=relationship-1');
    renderer.act(() => tree.unmount());
  });

  it('blocks builder when relationship is paused even if cached sharing says MANAGE', () => {
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { ...relationship, status: 'PAUSED' } }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    const tree = render(<WorkoutPlanBuilderScreen mode="create" relationshipId={relationship.id} studentId="student-1" />);
    expect(tree.root.findByProps({ testID: 'builder-denied' })).toBeTruthy();
    renderer.act(() => tree.unmount());
  });

  it('renders historical Student-owned plans read-only and preserves unavailable Exercise evidence', () => {
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan: { ...plan, status: 'COMPLETED', decisionOwnerType: 'STUDENT', decisionOwnerId: 'student-1', readContext: 'HISTORICAL' }, currentVersion: version }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: { version, sessions: [{ id: 's1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1, name: 'Ngày 1', focus: null, estimatedDurationMinutes: null, notes: null, prescriptions: [{ id: 'p1', exerciseVariationId: 'x1', sequenceNumber: 1, targetSets: 3, targetRepsMin: 8, targetRepsMax: 12, targetLoad: null, restSeconds: 60, durationSeconds: null, instructions: null, exercise: { variationId: 'x1', exerciseId: null, exerciseName: null, variationName: null, state: 'UNAVAILABLE', canonicalExerciseId: null, canonicalExerciseName: null } }] }] }, isPending: false, isError: false, error: null, refetch: jest.fn() });
    const tree = render(<WorkoutPlanDetailScreen studentId="student-1" planId="plan-1" relationshipId={relationship.id} />);
    expect(tree.root.findAllByProps({ testID: 'edit-draft-button' })).toHaveLength(0);
    expect(tree.root.findAll((node) => node.children.includes('Không còn khả dụng'))).not.toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('confirms activation and disables a duplicate command while pending', async () => {
    let finish!: (value: unknown) => void;
    const pending = new Promise((resolve) => { finish = resolve; });
    (workoutPlanApi.activate as jest.Mock).mockReturnValue(pending);
    const tree = render(<WorkoutPlanDetailScreen studentId="student-1" planId="plan-1" relationshipId={relationship.id} />);
    renderer.act(() => tree.root.findByProps({ testID: 'activate-plan-button' }).props.onPress());
    const confirm = tree.root.findByProps({ testID: 'confirm-plan-command' });
    await renderer.act(async () => { confirm.props.onPress(); await new Promise((resolve) => setTimeout(resolve, 0)); });
    expect(tree.root.findByProps({ testID: 'confirm-plan-command' }).props.disabled).toBe(true);
    expect(workoutPlanApi.activate).toHaveBeenCalledTimes(1);
    expect(workoutPlanApi.activate).toHaveBeenCalledWith('plan-1', 2, expect.any(String));
    await renderer.act(async () => { finish({}); await pending; await new Promise((resolve) => setTimeout(resolve, 0)); });
    renderer.act(() => tree.unmount());
  });
});
