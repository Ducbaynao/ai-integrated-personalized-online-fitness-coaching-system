import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { request } from '@/services/apiClient';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleChangeProposalApi } from '@/services/workoutScheduleChangeProposalApi';
import { TrainerChangeProposalsScreen } from '@/features/workout-schedule-change-proposals/TrainerChangeProposalsScreen';
import { TrainerChangeProposalDetailScreen } from '@/features/workout-schedule-change-proposals/TrainerChangeProposalDetailScreen';
import { TrainerCreateChangeProposalScreen } from '@/features/workout-schedule-change-proposals/TrainerCreateChangeProposalScreen';
import { useTrainerChangeProposalAuthority } from '@/features/workout-schedule-change-proposals/useTrainerChangeProposalAuthority';
import { useTrainerWorkoutScheduleChangeProposals, useTrainerWorkoutScheduleChangeProposalDetail } from '@/features/workout-schedule-change-proposals/workoutScheduleChangeProposalQueries';
import { useTrainerWorkoutScheduleDetail } from '@/features/workout-schedule/workoutScheduleQueries';

const mockPush = jest.fn();
const mockReplace = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, replace: mockReplace, back: jest.fn() }) }));
jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/features/workout-schedule-change-proposals/useTrainerChangeProposalAuthority', () => ({ useTrainerChangeProposalAuthority: jest.fn() }));
jest.mock('@/features/workout-schedule-change-proposals/workoutScheduleChangeProposalQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule-change-proposals/workoutScheduleChangeProposalQueries');
  return { ...actual, useTrainerWorkoutScheduleChangeProposals: jest.fn(), useTrainerWorkoutScheduleChangeProposalDetail: jest.fn() };
});
jest.mock('@/features/workout-schedule/workoutScheduleQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule/workoutScheduleQueries');
  return { ...actual, useTrainerWorkoutScheduleDetail: jest.fn() };
});

const proposal = {
  proposalId: 'proposal-1', proposalVersion: 3, status: 'PENDING', changeType: 'CANCEL',
  studentId: 'student-1', trainerId: 'trainer-1', trainerDisplayName: null,
  coachingPeriodId: 'period-1', occurrenceId: 'occurrence-1', observedOccurrenceVersion: 7,
  oldPlannedStartAt: '2026-10-20T11:00:00Z', oldPlannedEndAt: null, oldTimezone: null,
  oldLocalStart: null, oldLocalEnd: null, oldStartUtcOffset: null, oldEndUtcOffset: null,
  originalPlannedStartAt: '2026-10-20T11:00:00Z', sourcePlanId: null,
  sourcePlanVersionId: null, sourcePlanSessionId: null, sourcePlanName: null,
  sourceSessionName: 'Upper body', destination: null, reason: null,
  proposedAt: '2026-10-13T01:00:00Z', decidedAt: null, decidedBy: null,
  withdrawnAt: null, withdrawnBy: null,
};
const occurrence = {
  occurrenceId: 'occurrence-1', occurrenceVersion: 7, studentId: 'student-1', status: 'SCHEDULED',
  plannedStartAt: '2026-10-20T11:00:00Z', plannedEndAt: '2026-10-20T12:00:00Z',
  originalPlannedStartAt: '2026-10-20T11:00:00Z', timezone: 'Asia/Ho_Chi_Minh',
  localStart: '2026-10-20T18:00', localEnd: '2026-10-20T19:00',
  startUtcOffset: '+07:00', endUtcOffset: '+07:00', availableActions: [],
  planSummary: { planId: 'plan-1', planVersionId: 'version-1', planVersionNumber: 2, planName: 'Strength' },
  sessionSummary: { planSessionId: 'session-1', weekNumber: 1, dayNumber: 1, sequenceNumber: 1,
    name: 'Upper body', focus: 'Strength', supervisionRequirement: 'SELF_PERFORMABLE' },
};

const clients: QueryClient[] = [];
function render(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{node}</QueryClientProvider>); });
  return { tree, client };
}
function setup(canContribute = true) {
  (useTrainerChangeProposalAuthority as jest.Mock).mockReturnValue({
    activeCapability: 'TRAINER', trainerId: 'trainer-1', studentId: 'student-1',
    relationship: { counterparty: { displayName: 'Student An' } },
    relationshipQuery: { isPending: false, isError: false }, sharingQuery: { isPending: false, isError: false },
    verifying: false, canView: true, canContribute, deny: jest.fn(), retry: jest.fn(),
  });
  (useTrainerWorkoutScheduleChangeProposals as jest.Mock).mockReturnValue({
    data: { pages: [{ items: [proposal], page: 0, size: 20, totalItems: 1, totalPages: 1 }] },
    isPending: false, isError: false, isFetchingNextPage: false, hasNextPage: false, refetch: jest.fn(),
  });
  (useTrainerWorkoutScheduleChangeProposalDetail as jest.Mock).mockReturnValue({
    data: proposal, isPending: false, isError: false, isFetching: false, refetch: jest.fn(),
  });
  (useTrainerWorkoutScheduleDetail as jest.Mock).mockReturnValue({
    data: occurrence, isPending: false, isError: false, isFetching: false, refetch: jest.fn(),
  });
  (createCommandKey as jest.Mock).mockReturnValue('stable-key');
}

describe('Trainer change proposal HTTP mapping', () => {
  beforeEach(() => jest.clearAllMocks());
  it('uses Student-scoped collection and actual JSON command fields', async () => {
    (request as jest.Mock).mockResolvedValue({});
    const body = { occurrenceId: 'occurrence-1', expectedOccurrenceVersion: 7, changeType: 'RESCHEDULE',
      destination: { timezone: 'America/New_York', localStart: '2026-11-01T01:30', localEnd: '2026-11-01T02:30',
        startUtcOffset: '-04:00', endUtcOffset: '-05:00' }, reason: 'Đổi giờ', commandKey: 'stable-key' } as const;
    await workoutScheduleChangeProposalApi.create('student-1', body);
    expect(request).toHaveBeenCalledWith('/students/student-1/planned-workout-change-proposals', { method: 'POST', body });
    await workoutScheduleChangeProposalApi.listTrainer('student-1', 2, 'PENDING');
    expect(request).toHaveBeenCalledWith('/students/student-1/planned-workout-change-proposals?page=2&size=20&status=PENDING', { method: 'GET' });
    await workoutScheduleChangeProposalApi.withdraw('proposal-1', { expectedProposalVersion: 3, commandKey: 'stable-key' });
    expect(request).toHaveBeenCalledWith('/planned-workout-change-proposals/proposal-1/withdraw', {
      method: 'POST', body: { expectedProposalVersion: 3, commandKey: 'stable-key' },
    });
  });
});

describe('Trainer change proposal screens', () => {
  beforeEach(() => {
    jest.clearAllMocks(); setup();
    jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-13T00:00:00Z'));
  });
  afterEach(() => { jest.restoreAllMocks(); clients.splice(0).forEach((client) => client.clear()); });

  it('VIEW can list own proposals and read detail but cannot create or withdraw', () => {
    setup(false);
    const list = render(<TrainerChangeProposalsScreen relationshipId="relationship-1" />);
    expect(JSON.stringify(list.tree.toJSON())).toContain('Upper body');
    expect(list.tree.root.findAllByProps({ testID: 'change-proposals-open-calendar' })).toHaveLength(0);
    renderer.act(() => list.tree.root.findByProps({ testID: 'open-change-proposal-proposal-1' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/coaching/relationship-1/change-proposals/proposal-1');
    renderer.act(() => list.tree.unmount());
    const detail = render(<TrainerChangeProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    expect(detail.tree.root.findAllByProps({ testID: 'change-proposal-withdraw' })).toHaveLength(0);
    renderer.act(() => detail.tree.unmount());
  });

  it('withdraws PENDING with real proposal version and stable key after uncertain network result', async () => {
    const withdraw = jest.spyOn(workoutScheduleChangeProposalApi, 'withdraw')
      .mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce({ proposal: { ...proposal, status: 'WITHDRAWN' }, replayed: true } as never);
    const { tree } = render(<TrainerChangeProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'change-proposal-withdraw' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'change-proposal-confirm-withdraw' }).props.onPress(); await Promise.resolve(); });
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); await Promise.resolve(); });
    expect(withdraw).toHaveBeenNthCalledWith(1, 'proposal-1', { expectedProposalVersion: 3, commandKey: 'stable-key' });
    expect(withdraw).toHaveBeenNthCalledWith(2, 'proposal-1', { expectedProposalVersion: 3, commandKey: 'stable-key' });
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount()); withdraw.mockRestore();
  });

  it('409 requires a fresh review; 403 hides cached detail', async () => {
    const withdraw = jest.spyOn(workoutScheduleChangeProposalApi, 'withdraw').mockRejectedValue(new ApiError(409, 'stale'));
    const { tree } = render(<TrainerChangeProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'change-proposal-withdraw' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'change-proposal-confirm-withdraw' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'change-proposal-review' })).toBeTruthy();
    expect(withdraw).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount()); withdraw.mockRestore();
    (useTrainerWorkoutScheduleChangeProposalDetail as jest.Mock).mockReturnValue({ data: proposal,
      isPending: false, isError: true, isFetching: false, error: new ApiError(403, 'denied') });
    const concealed = render(<TrainerChangeProposalDetailScreen relationshipId="relationship-1" proposalId="proposal-1" />);
    expect(JSON.stringify(concealed.tree.toJSON())).not.toContain('Upper body');
    expect(concealed.tree.root.findByProps({ testID: 'change-proposal-detail-denied' })).toBeTruthy();
    renderer.act(() => concealed.tree.unmount());
  });

  it('creates CANCEL from actual occurrence version, shows preview and reuses body/key on retry', async () => {
    const create = jest.spyOn(workoutScheduleChangeProposalApi, 'create')
      .mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce({ proposal, replayed: true } as never);
    const { tree } = render(<TrainerCreateChangeProposalScreen relationshipId="relationship-1" occurrenceId="occurrence-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'select-change-cancel' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'review-change-proposal' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'change-proposal-preview' })).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-create-change-proposal' }).props.onPress(); await Promise.resolve(); });
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); await Promise.resolve(); });
    expect(create).toHaveBeenNthCalledWith(1, 'student-1', {
      occurrenceId: 'occurrence-1', expectedOccurrenceVersion: 7, changeType: 'CANCEL', reason: null, commandKey: 'stable-key',
    });
    expect(create.mock.calls[1]).toEqual(create.mock.calls[0]);
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    expect(mockReplace).toHaveBeenCalledWith('/coaching/relationship-1/change-proposals/proposal-1');
    renderer.act(() => tree.unmount()); create.mockRestore();
  });

  it('blocks COACH_REQUIRED RESCHEDULE and DST gap/ambiguous offset without guessing', () => {
    (useTrainerWorkoutScheduleDetail as jest.Mock).mockReturnValue({ data: { ...occurrence,
      sessionSummary: { ...occurrence.sessionSummary, supervisionRequirement: 'COACH_REQUIRED' } },
      isPending: false, isError: false, isFetching: false });
    const blocked = render(<TrainerCreateChangeProposalScreen relationshipId="relationship-1" occurrenceId="occurrence-1" />);
    expect(blocked.tree.root.findByProps({ testID: 'select-change-reschedule' }).props.disabled).toBe(true);
    renderer.act(() => blocked.tree.unmount());
    setup();
    const { tree } = render(<TrainerCreateChangeProposalScreen relationshipId="relationship-1" occurrenceId="occurrence-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'select-change-reschedule' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'change-timezone-search' }).props.onChangeText('America/New_York'));
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'America/New_York' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'change-local-start' }).props.onChangeText('2027-03-14T02:30'));
    expect(JSON.stringify(tree.toJSON())).toContain('Giờ không tồn tại');
    renderer.act(() => tree.root.findByProps({ testID: 'change-local-start' }).props.onChangeText('2026-11-01T01:30'));
    expect(JSON.stringify(tree.toJSON())).toContain('Giờ lặp');
    renderer.act(() => tree.root.findByProps({ testID: 'review-change-proposal' }).props.onPress());
    expect(tree.root.findAllByProps({ testID: 'change-proposal-preview' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('sends selected IANA timezone and explicit offsets in a RESCHEDULE command', async () => {
    const create = jest.spyOn(workoutScheduleChangeProposalApi, 'create')
      .mockResolvedValue({ proposal: { ...proposal, changeType: 'RESCHEDULE' }, replayed: false } as never);
    const { tree } = render(<TrainerCreateChangeProposalScreen relationshipId="relationship-1" occurrenceId="occurrence-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'select-change-reschedule' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Dùng múi giờ occurrence: Asia/Ho_Chi_Minh' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'change-local-start' }).props.onChangeText('2026-10-21T18:00'));
    renderer.act(() => tree.root.findByProps({ testID: 'change-local-end' }).props.onChangeText('2026-10-21T19:00'));
    const offsets = tree.root.findAllByProps({ accessibilityLabel: 'UTC+07:00' })
      .filter((node) => typeof node.props.onPress === 'function');
    renderer.act(() => offsets[0].props.onPress());
    renderer.act(() => offsets[1].props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'review-change-proposal' }).props.onPress());
    expect(JSON.stringify(tree.toJSON())).toContain('Asia/Ho_Chi_Minh');
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-create-change-proposal' }).props.onPress(); await Promise.resolve(); });
    expect(create).toHaveBeenCalledWith('student-1', {
      occurrenceId: 'occurrence-1', expectedOccurrenceVersion: 7, changeType: 'RESCHEDULE',
      destination: { timezone: 'Asia/Ho_Chi_Minh', localStart: '2026-10-21T18:00', localEnd: '2026-10-21T19:00',
        startUtcOffset: '+07:00', endUtcOffset: '+07:00' }, reason: null, commandKey: 'stable-key',
    });
    renderer.act(() => tree.unmount()); create.mockRestore();
  });

  it('does not auto-submit a stale create command after 409', async () => {
    const create = jest.spyOn(workoutScheduleChangeProposalApi, 'create').mockRejectedValue(new ApiError(409, 'stale'));
    const { tree } = render(<TrainerCreateChangeProposalScreen relationshipId="relationship-1" occurrenceId="occurrence-1" />);
    const refetch = (useTrainerWorkoutScheduleDetail as jest.Mock).mock.results.at(-1)?.value.refetch;
    renderer.act(() => tree.root.findByProps({ testID: 'select-change-cancel' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'review-change-proposal' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'confirm-create-change-proposal' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'create-change-review' })).toBeTruthy();
    expect(tree.root.findAllByProps({ testID: 'confirm-create-change-proposal' })).toHaveLength(0);
    expect(create).toHaveBeenCalledTimes(1);
    expect(refetch).toHaveBeenCalled();
    renderer.act(() => tree.unmount()); create.mockRestore();
  });
});
