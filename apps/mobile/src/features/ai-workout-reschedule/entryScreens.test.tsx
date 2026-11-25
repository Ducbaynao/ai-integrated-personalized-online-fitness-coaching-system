import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { useWorkoutScheduleDetail, useTrainerWorkoutSchedule } from '@/features/workout-schedule/workoutScheduleQueries';
import { useWorkoutPlan, useWorkoutPlanVersion } from '@/features/workout-plan/workoutPlanQueries';
import { StudentWorkoutScheduleDetailScreen } from '@/features/workout-schedule/StudentWorkoutScheduleDetailScreen';
import { TrainerWorkoutScheduleScreen } from '@/features/workout-schedule/TrainerWorkoutScheduleScreen';

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ back: jest.fn(), push: jest.fn(), replace: mockReplace }),
  useFocusEffect: (callback: () => void | (() => void)) => jest.requireActual<typeof import('react')>('react').useEffect(callback, [callback]),
}));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn(), useSharingSummary: jest.fn() }));
jest.mock('@/features/workout-schedule/workoutScheduleQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule/workoutScheduleQueries');
  return { ...actual, useWorkoutScheduleDetail: jest.fn(), useTrainerWorkoutSchedule: jest.fn() };
});
jest.mock('@/features/workout-plan/workoutPlanQueries', () => {
  const actual = jest.requireActual('@/features/workout-plan/workoutPlanQueries');
  return { ...actual, useWorkoutPlan: jest.fn(), useWorkoutPlanVersion: jest.fn() };
});
jest.mock('@/features/workout-plan/components/AiWorkoutAdjustmentModal', () => ({ AiWorkoutAdjustmentModal: () => null }));
jest.mock('./AiWorkoutRescheduleModal', () => ({
  AiWorkoutRescheduleModal: (props: object) => {
    const { View } = jest.requireActual('react-native');
    return <View testID="f05-mounted" {...props} />;
  },
}));

const occurrence = {
  occurrenceId: 'occ-1', occurrenceVersion: 2, studentId: 'student-1', status: 'SCHEDULED',
  plannedStartAt: '2026-11-20T08:00:00Z', originalPlannedStartAt: '2026-11-20T08:00:00Z',
  plannedEndAt: '2026-11-20T09:00:00Z', timezone: 'Asia/Ho_Chi_Minh',
  localStart: '2026-11-20T15:00', localEnd: '2026-11-20T16:00',
  startUtcOffset: '+07:00', endUtcOffset: '+07:00', planSummary: null, sessionSummary: null,
  rescheduledFromOccurrenceId: null, replacementOccurrenceId: null, linkedAppointmentId: null,
  linkedAppointmentVersion: null, availableActions: ['RESCHEDULE'],
};

async function render(element: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  let tree!: renderer.ReactTestRenderer;
  await renderer.act(async () => {
    tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>);
    await Promise.resolve();
  });
  return { tree, client };
}

describe('F05 entry screens', () => {
  beforeEach(() => jest.clearAllMocks());

  it('opens F05 only from a scheduled Student occurrence', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
    (useWorkoutScheduleDetail as jest.Mock).mockReturnValue({ data: occurrence, isPending: false, isError: false,
      isFetching: false, error: null, refetch: jest.fn() });
    (useWorkoutPlan as jest.Mock).mockReturnValue({ data: null });
    (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: null });
    const { tree, client } = await render(<StudentWorkoutScheduleDetailScreen occurrenceId="occ-1" />);
    expect(tree.root.findAllByProps({ testID: 'ai-workout-reschedule-button' }).length).toBeGreaterThan(0);
    await renderer.act(async () => { tree.root.findByProps({ testID: 'ai-workout-reschedule-button' }).props.onPress(); });
    expect(tree.root.findAllByProps({ testID: 'f05-mounted' }).length).toBeGreaterThan(0);
    await renderer.act(async () => { tree.root.findByProps({ testID: 'f05-mounted' }).props.onApplied({ resultingEntityId: 'successor-1' }); });
    await renderer.act(async () => { await tree.root.findByProps({ testID: 'f05-mounted' }).props.onClose(); });
    expect(mockReplace).toHaveBeenCalledWith('/workouts/schedule/successor-1');
    expect(tree.root.findAllByProps({ testID: 'f05-mounted' })).toHaveLength(0);
    renderer.act(() => tree.unmount()); client.clear();
  });

  it('offers F05 to current Trainer MANAGE, never VIEW', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    let level = 'VIEW';
    const relation = { data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE', counterparty: { displayName: 'An' } },
      currentPeriod: { mode: 'HUMAN_COACH' } }, isPending: false, isError: false, refetch: jest.fn() };
    relation.refetch.mockResolvedValue(relation);
    const sharing = { data: { items: [{ dataScope: 'WORKOUT_PLAN', state: 'ALLOWED', accessLevel: level }] },
      isPending: false, isError: false, refetch: jest.fn() };
    sharing.refetch.mockResolvedValue(sharing);
    (useRelationshipDetail as jest.Mock).mockReturnValue(relation);
    (useSharingSummary as jest.Mock).mockImplementation(() => sharing);
    (useTrainerWorkoutSchedule as jest.Mock).mockReturnValue({ data: { pages: [{ items: [occurrence], totalItems: 1 }] },
      isPending: false, isError: false, isFetching: false, hasNextPage: false, error: null, refetch: jest.fn() });
    const view = await render(<TrainerWorkoutScheduleScreen relationshipId="rel-1" />);
    expect(view.tree.root.findAllByProps({ testID: 'trainer-ai-reschedule-occ-1' })).toHaveLength(0);
    renderer.act(() => view.tree.unmount()); view.client.clear();

    level = 'MANAGE'; sharing.data.items[0].accessLevel = level;
    const manage = await render(<TrainerWorkoutScheduleScreen relationshipId="rel-1" />);
    expect(manage.tree.root.findAllByProps({ testID: 'trainer-ai-reschedule-occ-1' }).length).toBeGreaterThan(0);
    await renderer.act(async () => { manage.tree.root.findByProps({ testID: 'trainer-ai-reschedule-occ-1' }).props.onPress(); });
    expect(manage.tree.root.findAllByProps({ testID: 'f05-mounted' }).length).toBeGreaterThan(0);
    renderer.act(() => manage.tree.unmount()); manage.client.clear();
  });
});
