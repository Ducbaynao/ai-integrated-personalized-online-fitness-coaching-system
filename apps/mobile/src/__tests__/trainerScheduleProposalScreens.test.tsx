import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';
import { useTrainerWorkoutScheduleProposals, useTrainerWorkoutScheduleProposalDetail } from '@/features/workout-schedule-proposals/workoutScheduleProposalQueries';
import { TrainerScheduleProposalsScreen } from '@/features/workout-schedule-proposals/TrainerScheduleProposalsScreen';
import { TrainerScheduleProposalDetailScreen } from '@/features/workout-schedule-proposals/TrainerScheduleProposalDetailScreen';
import type { WorkoutScheduleProposal } from '@/types/workoutScheduleProposal';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn(), useSharingSummary: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/services/workoutScheduleProposalApi', () => ({ workoutScheduleProposalApi: { listTrainer: jest.fn(), detail: jest.fn(), withdraw: jest.fn() } }));
jest.mock('@/features/workout-schedule-proposals/workoutScheduleProposalQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule-proposals/workoutScheduleProposalQueries');
  return { ...actual, useTrainerWorkoutScheduleProposals: jest.fn(), useTrainerWorkoutScheduleProposalDetail: jest.fn() };
});

const proposal: WorkoutScheduleProposal = {
  proposalId: 'proposal-1', proposalVersion: 4, status: 'PENDING', studentId: 'student-1', trainerId: 'trainer-1',
  trainerDisplayName: 'Huấn luyện viên An', coachingPeriodId: 'period-1', planId: 'plan-1', sourcePlanName: 'Sức mạnh',
  sourcePlanVersionId: 'version-1', sourcePlanVersionNumber: 2, observedPlanAggregateVersion: 8,
  weekAnchorDate: '2026-10-12', timezone: 'Asia/Ho_Chi_Minh', proposedAt: '2026-10-11T01:00:00Z',
  items: [{ clientItemId: 'item-1', planSessionId: 'session-1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1,
    localStart: '2026-10-12T08:00:00', localEnd: '2026-10-12T09:00:00', startUtcOffset: '+07:00', endUtcOffset: '+07:00',
    sourceSessionName: 'Ngày thân trên', sourceSessionFocus: null }],
};

function setup(accessLevel = 'CONTRIBUTE') {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
  (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE', counterparty: { displayName: 'Học viên An' } } }, isPending: false, isError: false, refetch: jest.fn() });
  (useSharingSummary as jest.Mock).mockReturnValue({ data: { items: [{ dataScope: 'WORKOUT_PLAN', state: 'ALLOWED', accessLevel }] }, isPending: false, isError: false, refetch: jest.fn() });
  (useTrainerWorkoutScheduleProposals as jest.Mock).mockReturnValue({ data: { pages: [{ items: [proposal], page: 0, size: 20, totalPages: 1 }] }, isPending: false, isError: false, hasNextPage: false });
  (useTrainerWorkoutScheduleProposalDetail as jest.Mock).mockReturnValue({ data: proposal, isPending: false, isError: false, isFetching: false });
  (createCommandKey as jest.Mock).mockReturnValue('stable-key');
}

const clients: QueryClient[] = [];
function render(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{node}</QueryClientProvider>); });
  return { tree, client };
}

describe('Trainer schedule proposals', () => {
  beforeEach(() => { jest.clearAllMocks(); setup(); });
  afterEach(() => clients.splice(0).forEach((client) => client.clear()));

  it('opens own Student-scoped list and filters without a create action', () => {
    const { tree } = render(<TrainerScheduleProposalsScreen relationshipId="relationship-1" />);
    expect(JSON.stringify(tree.toJSON())).toContain('Sức mạnh');
    expect(tree.root.findAllByProps({ testID: 'trainer-create-proposal' })).toHaveLength(0);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Đang chờ' }).props.onPress());
    expect(useTrainerWorkoutScheduleProposals).toHaveBeenLastCalledWith('trainer-1', 'student-1', 'PENDING', true);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Xem chi tiết' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/coaching/relationship-1/schedule-proposals/proposal-1');
    renderer.act(() => tree.unmount());
  });

  it('VIEW can read detail but cannot withdraw', () => {
    setup('VIEW');
    const { tree } = render(<TrainerScheduleProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    expect(JSON.stringify(tree.toJSON())).toContain('Ngày thân trên');
    expect(tree.root.findAllByProps({ testID: 'trainer-proposal-withdraw' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('loads the next page when the Trainer collection has more results', () => {
    const fetchNextPage = jest.fn();
    (useTrainerWorkoutScheduleProposals as jest.Mock).mockReturnValue({ data: { pages: [{ items: [proposal], page: 0, size: 20, totalPages: 2 }] },
      isPending: false, isError: false, hasNextPage: true, isFetchingNextPage: false, fetchNextPage });
    const { tree } = render(<TrainerScheduleProposalsScreen relationshipId="relationship-1" />);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Tải thêm' }).props.onPress());
    expect(fetchNextPage).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('withdraws with actual version and stable retry key', async () => {
    (workoutScheduleProposalApi.withdraw as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce({ proposal: { ...proposal, status: 'WITHDRAWN', proposalVersion: 5 }, replayed: true });
    const { tree } = render(<TrainerScheduleProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'trainer-proposal-withdraw' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'trainer-proposal-confirm-withdraw' }).props.onPress(); await Promise.resolve(); });
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); await Promise.resolve(); });
    expect(workoutScheduleProposalApi.withdraw).toHaveBeenNthCalledWith(1, 'proposal-1', { expectedProposalVersion: 4, commandKey: 'stable-key' });
    expect(workoutScheduleProposalApi.withdraw).toHaveBeenNthCalledWith(2, 'proposal-1', { expectedProposalVersion: 4, commandKey: 'stable-key' });
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('409 clears retry intent and requires a fresh review', async () => {
    (workoutScheduleProposalApi.withdraw as jest.Mock).mockRejectedValue(new ApiError(409, 'stale'));
    const { tree, client } = render(<TrainerScheduleProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    const invalidate = jest.spyOn(client, 'invalidateQueries');
    renderer.act(() => tree.root.findByProps({ testID: 'trainer-proposal-withdraw' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'trainer-proposal-confirm-withdraw' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findAllByProps({ testID: 'trainer-proposal-confirm-withdraw' })).toHaveLength(0);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workout-schedule-proposals', 'trainer', 'trainer-1', 'student-1'] });
    expect(workoutScheduleProposalApi.withdraw).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('403 hides cached Trainer list content immediately', () => {
    (useTrainerWorkoutScheduleProposals as jest.Mock).mockReturnValue({ data: { pages: [{ items: [proposal] }] }, isPending: false, isError: true, error: new ApiError(403, 'denied') });
    const { tree } = render(<TrainerScheduleProposalsScreen relationshipId="relationship-1" />);
    expect(JSON.stringify(tree.toJSON())).not.toContain('Sức mạnh');
    expect(tree.root.findAllByProps({ testID: 'trainer-proposal-proposal-1' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('404 from detail conceals cached source names and disables withdrawal', () => {
    (useTrainerWorkoutScheduleProposalDetail as jest.Mock).mockReturnValue({ data: proposal, isPending: false, isError: true,
      error: new ApiError(404, 'hidden'), isFetching: false });
    const { tree } = render(<TrainerScheduleProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    expect(JSON.stringify(tree.toJSON())).not.toContain('Sức mạnh');
    expect(tree.root.findAllByProps({ testID: 'trainer-proposal-withdraw' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('conceals data when current relationship is no longer effective', () => {
    (useRelationshipDetail as jest.Mock).mockReturnValue({ data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ENDED' } }, isPending: false, isError: false });
    const { tree } = render(<TrainerScheduleProposalsScreen relationshipId="relationship-1" />);
    expect(JSON.stringify(tree.toJSON())).not.toContain('Sức mạnh');
    expect(useTrainerWorkoutScheduleProposals).toHaveBeenLastCalledWith('trainer-1', 'student-1', undefined, false);
    renderer.act(() => tree.unmount());
  });
});
