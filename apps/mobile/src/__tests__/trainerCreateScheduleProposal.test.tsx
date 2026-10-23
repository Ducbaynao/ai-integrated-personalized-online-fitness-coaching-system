import React from 'react';
import renderer from 'react-test-renderer';
import moment from 'moment-timezone';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { useCurrentWorkoutPlan, useWorkoutPlanVersion } from '@/features/workout-plan/workoutPlanQueries';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';
import { appointmentApi } from '@/services/appointmentApi';
import { TrainerCreateScheduleProposalScreen } from '@/features/workout-schedule-proposals/TrainerCreateScheduleProposalScreen';

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ replace: mockReplace, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn(), useSharingSummary: jest.fn() }));
jest.mock('@/features/workout-plan/workoutPlanQueries', () => ({
  useCurrentWorkoutPlan: jest.fn(), useWorkoutPlanVersion: jest.fn(),
  workoutPlanQueryKeys: { student: (id: string) => ['workout-plans', 'student', id] },
}));
jest.mock('@/services/workoutScheduleProposalApi', () => ({ workoutScheduleProposalApi: { create: jest.fn() } }));
jest.mock('@/services/appointmentApi', () => ({ appointmentApi: { appointments: jest.fn() } }));

const plan = { id: 'plan-1', studentId: 'student-1', name: 'Sức mạnh', status: 'ACTIVE', aggregateVersion: 7,
  currentVersionId: 'version-1', currentVersionLockedAt: '2026-10-01T00:00:00Z' };
const version = { id: 'version-1', versionNumber: 2, current: true, lockedAt: '2026-10-01T00:00:00Z', effectiveUntil: null };
const session = { id: 'session-1', name: 'Upper body', weekNumber: 1, dayNumber: 1, sequenceNumber: 1,
  focus: 'Strength', supervisionRequirement: 'SELF_ALLOWED' };

function setup(level = 'CONTRIBUTE', requirement = 'SELF_ALLOWED') {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
  (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE', counterparty: { displayName: 'An' } }, currentPeriod: { id: 'period-1' } }, isPending: false, isError: false, refetch: jest.fn() });
  (appointmentApi.appointments as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 0, items: [] });
  (useSharingSummary as jest.Mock).mockReturnValue({ data: { items: [{ dataScope: 'WORKOUT_PLAN', state: 'ALLOWED', accessLevel: level }] }, isPending: false, isError: false, refetch: jest.fn() });
  (useCurrentWorkoutPlan as jest.Mock).mockReturnValue({ data: { plan }, isPending: false, isError: false, refetch: jest.fn() });
  (useWorkoutPlanVersion as jest.Mock).mockReturnValue({ data: { version, sessions: [{ ...session, supervisionRequirement: requirement }] }, isPending: false, isError: false, refetch: jest.fn() });
}

const clients: QueryClient[] = [];
function render() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}><TrainerCreateScheduleProposalScreen relationshipId="relationship-1" /></QueryClientProvider>); });
  return tree;
}

async function press(tree: renderer.ReactTestRenderer, testID: string) {
  await renderer.act(async () => { tree.root.findByProps({ testID }).props.onPress(); });
}
function input(tree: renderer.ReactTestRenderer, testID: string, value: string) {
  renderer.act(() => { tree.root.findByProps({ testID }).props.onChangeText(value); });
}
async function fillValid(tree: renderer.ReactTestRenderer) {
  const monday = moment.utc().add(7, 'days').startOf('isoWeek').format('YYYY-MM-DD');
  const localStart = `${monday}T18:30`;
  const localEnd = `${monday}T19:30`;
  input(tree, 'timezone-search', 'Ho_Chi_Minh');
  await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Asia/Ho_Chi_Minh' }).props.onPress(); });
  input(tree, 'week-anchor', monday);
  await press(tree, 'select-session-session-1');
  input(tree, 'start-session-1', localStart);
  await renderer.act(async () => { tree.root.findAllByProps({ accessibilityLabel: 'UTC+07:00' }).find((node) => typeof node.props.onPress === 'function')!.props.onPress(); });
  input(tree, 'end-session-1', localEnd);
  await renderer.act(async () => { tree.root.findAllByProps({ accessibilityLabel: 'UTC+07:00' }).filter((node) => typeof node.props.onPress === 'function').at(-1)!.props.onPress(); });
  await press(tree, 'review-proposal');
  return { monday, localStart, localEnd };
}

describe('Trainer proposal create', () => {
  beforeEach(() => { jest.clearAllMocks(); setup(); });
  afterEach(() => clients.splice(0).forEach((client) => client.clear()));

  it('does not expose submit to VIEW-only Trainer and requires an Appointment for COACH_REQUIRED', async () => {
    setup('VIEW');
    const denied = render();
    expect(JSON.stringify(denied.toJSON())).toContain('Không thể tạo đề xuất');
    renderer.act(() => denied.unmount());
    setup('CONTRIBUTE', 'COACH_REQUIRED');
    const restricted = render();
    expect(restricted.root.findByProps({ testID: 'select-session-session-1' }).props.disabled).toBe(false);
    await press(restricted, 'select-session-session-1');
    expect(restricted.root.findByProps({ testID: 'review-proposal' }).props.disabled).toBe(true);
    renderer.act(() => restricted.unmount());
  });

  it('sends the selected confirmed Appointment ID for a coached proposal item', async () => {
    setup('CONTRIBUTE', 'COACH_REQUIRED');
    const monday = moment.utc().add(7, 'days').startOf('isoWeek').format('YYYY-MM-DD');
    (appointmentApi.appointments as jest.Mock).mockResolvedValue({ page: 0, size: 20, totalItems: 1, items: [{
      appointmentId: 'appointment-1', version: 3, studentId: 'student-1', trainerId: 'trainer-1',
      coachingPeriodId: 'period-1', plannedWorkoutId: null, status: 'CONFIRMED',
      startAt: `${monday}T11:00:00Z`, endAt: `${monday}T13:00:00Z`, timezone: 'Asia/Ho_Chi_Minh',
      localStart: `${monday}T18:00`, localEnd: `${monday}T20:00`,
    }] });
    (workoutScheduleProposalApi.create as jest.Mock).mockResolvedValue({ proposal: { proposalId: 'proposal-2' } });
    const tree = render();
    await fillValid(tree);
    await renderer.act(async () => { await Promise.resolve(); });
    await press(tree, 'proposal-appointment-appointment-1');
    await press(tree, 'review-proposal');
    await press(tree, 'confirm-create-proposal');
    expect((workoutScheduleProposalApi.create as jest.Mock).mock.calls[0][1].items[0].appointmentId).toBe('appointment-1');
    renderer.act(() => tree.unmount());
  });

  it('can search older confirmed Appointments instead of silently excluding a long interval', async () => {
    setup('CONTRIBUTE', 'COACH_REQUIRED');
    const tree = render();
    await press(tree, 'select-session-session-1');
    await renderer.act(async () => { await Promise.resolve(); });
    await press(tree, 'proposal-search-earlier-appointments');
    await renderer.act(async () => { await Promise.resolve(); });
    expect(appointmentApi.appointments).toHaveBeenCalledWith(
      '1970-01-01T00:00:00.000Z', expect.any(String), 0,
    );
    renderer.act(() => tree.unmount());
  });

  it('uses real plan/session identities, explicit timezone and offsets, then keeps one payload/key on network retry', async () => {
    (workoutScheduleProposalApi.create as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce({ proposal: { proposalId: 'proposal-1' } });
    const tree = render();
    const { monday, localStart, localEnd } = await fillValid(tree);
    expect(JSON.stringify(tree.toJSON())).toContain('Học viên sẽ xem múi giờ');
    await press(tree, 'confirm-create-proposal');
    expect(workoutScheduleProposalApi.create).toHaveBeenCalledTimes(1);
    const [studentId, body] = (workoutScheduleProposalApi.create as jest.Mock).mock.calls[0];
    expect(studentId).toBe('student-1');
    expect(body).toMatchObject({ planId: 'plan-1', sourcePlanVersionId: 'version-1', expectedPlanAggregateVersion: 7,
      weekAnchorDate: monday, timezone: 'Asia/Ho_Chi_Minh', items: [{ planSessionId: 'session-1', weekNumber: 1,
        dayNumber: 1, sequenceNumber: 1, localStart, localEnd, startUtcOffset: '+07:00', endUtcOffset: '+07:00' }] });
    expect(body.commandKey).toBeTruthy();
    expect(body.items[0].clientItemId).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'create-proposal-error' }).findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); });
    expect((workoutScheduleProposalApi.create as jest.Mock).mock.calls[1][1]).toBe(body);
    expect(mockReplace).toHaveBeenCalledWith('/coaching/relationship-1/schedule-proposals/proposal-1');
    renderer.act(() => tree.unmount());
  });

  it('drops the retry command on 409 and requires a fresh preview', async () => {
    (workoutScheduleProposalApi.create as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'stale'));
    const tree = render();
    await fillValid(tree);
    await press(tree, 'confirm-create-proposal');
    expect(JSON.stringify(tree.toJSON())).toContain('Dữ liệu đã thay đổi');
    expect(tree.root.findAllByProps({ testID: 'confirm-create-proposal' })).toHaveLength(0);
    expect((useCurrentWorkoutPlan as jest.Mock).mock.results.some((result) => result.value.refetch.mock.calls.length > 0)).toBe(true);
    renderer.act(() => tree.unmount());
  });
});
