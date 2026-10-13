import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { request } from '@/services/apiClient';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleChangeProposalApi } from '@/services/workoutScheduleChangeProposalApi';
import { useStudentWorkoutScheduleChangeProposals, useStudentWorkoutScheduleChangeProposalDetail, workoutScheduleChangeProposalQueryKeys } from '@/features/workout-schedule-change-proposals/workoutScheduleChangeProposalQueries';
import { StudentChangeProposalsScreen } from '@/features/workout-schedule-change-proposals/StudentChangeProposalsScreen';
import { StudentChangeProposalDetailScreen } from '@/features/workout-schedule-change-proposals/StudentChangeProposalDetailScreen';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/features/workout-schedule-change-proposals/workoutScheduleChangeProposalQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule-change-proposals/workoutScheduleChangeProposalQueries');
  return { ...actual, useStudentWorkoutScheduleChangeProposals: jest.fn(), useStudentWorkoutScheduleChangeProposalDetail: jest.fn() };
});

const proposal = {
  proposalId: 'proposal-1', proposalVersion: 4, status: 'PENDING', changeType: 'RESCHEDULE',
  studentId: 'student-1', trainerId: 'trainer-1', trainerDisplayName: null,
  coachingPeriodId: 'period-1', occurrenceId: 'occurrence-1', observedOccurrenceVersion: 8,
  oldPlannedStartAt: '2026-10-20T11:00:00Z', oldPlannedEndAt: null, oldTimezone: null,
  oldLocalStart: null, oldLocalEnd: null, oldStartUtcOffset: null, oldEndUtcOffset: null,
  originalPlannedStartAt: '2026-10-20T11:00:00Z', sourcePlanId: null,
  sourcePlanVersionId: null, sourcePlanSessionId: null, sourcePlanName: null,
  sourceSessionName: 'Upper body', destination: { timezone: 'America/New_York',
    localStart: '2026-10-21T09:00', localEnd: '2026-10-21T10:00',
    startUtcOffset: '-04:00', endUtcOffset: '-04:00' }, reason: 'Đổi giờ',
  proposedAt: '2026-10-13T01:00:00Z', decidedAt: null, decidedBy: null,
  withdrawnAt: null, withdrawnBy: null,
};
const clients: QueryClient[] = [];
function render(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{node}</QueryClientProvider>); });
  return { tree, client };
}
function setup(extras: Record<string, unknown> = {}) {
  (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
  (useStudentWorkoutScheduleChangeProposals as jest.Mock).mockReturnValue({
    data: { pages: [{ items: [proposal], page: 0, size: 20, totalItems: 1, totalPages: 1 }] },
    isPending: false, isError: false, error: null, isFetchingNextPage: false, hasNextPage: false,
    refetch: jest.fn(), fetchNextPage: jest.fn(), ...extras,
  });
  (useStudentWorkoutScheduleChangeProposalDetail as jest.Mock).mockReturnValue({
    data: proposal, isPending: false, isError: false, isFetching: false, error: null, refetch: jest.fn(), ...extras,
  });
  (createCommandKey as jest.Mock).mockReturnValue('stable-key');
}

describe('Student change proposal HTTP mapping', () => {
  beforeEach(() => jest.clearAllMocks());
  it('uses Student /me and sends proposal version, decision reason and command key', async () => {
    (request as jest.Mock).mockResolvedValue({});
    await workoutScheduleChangeProposalApi.listMine(2, 'PENDING');
    expect(request).toHaveBeenCalledWith('/planned-workout-change-proposals/me?page=2&size=20&status=PENDING', { method: 'GET' });
    const body = { expectedProposalVersion: 4, decision: 'ACCEPT' as const, decisionReason: 'Lý do của tôi', commandKey: 'stable-key' };
    await workoutScheduleChangeProposalApi.decide('proposal-1', body);
    expect(request).toHaveBeenCalledWith('/planned-workout-change-proposals/proposal-1/decisions', { method: 'POST', body });
  });
});

describe('Student change proposal screens', () => {
  beforeEach(() => { jest.clearAllMocks(); setup(); jest.spyOn(Date, 'now').mockReturnValue(Date.parse('2026-10-13T00:00:00Z')); });
  afterEach(() => { jest.restoreAllMocks(); clients.splice(0).forEach((client) => client.clear()); });

  it('lists nullable source data and terminal proposals with filter, pagination and real-ID navigation', () => {
    (useStudentWorkoutScheduleChangeProposals as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [proposal, { ...proposal, proposalId: 'terminal-1', status: 'WITHDRAWN' }], totalPages: 2 }] },
      isPending: false, isError: false, hasNextPage: true, isFetchingNextPage: false, fetchNextPage: jest.fn(), refetch: jest.fn(),
    });
    const { tree } = render(<StudentChangeProposalsScreen />);
    const json = JSON.stringify(tree.toJSON());
    expect(json).toContain('Upper body');
    expect(json).toContain('Huấn luyện viên chưa có tên hiển thị');
    expect(json).toContain('Đã rút');
    renderer.act(() => tree.root.findByProps({ testID: 'student-open-change-proposal-proposal-1' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/workout-change-proposals/proposal-1');
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Đang chờ' }).props.onPress());
    expect(useStudentWorkoutScheduleChangeProposals).toHaveBeenLastCalledWith('student-1', 'PENDING', true);
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-proposals-load-more' }).props.onPress());
    expect((useStudentWorkoutScheduleChangeProposals as jest.Mock).mock.results.at(-1)?.value.fetchNextPage).toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('shows original and proposed times, accepts PENDING with real proposal version, then links the server replacement', async () => {
    const decide = jest.spyOn(workoutScheduleChangeProposalApi, 'decide').mockResolvedValue({
      proposal: { ...proposal, status: 'ACCEPTED', proposalVersion: 5 },
      appliedChange: { replacementOccurrenceId: 'replacement-1' }, replayed: false,
    } as never);
    const { tree, client } = render(<StudentChangeProposalDetailScreen proposalId="proposal-1" />);
    const invalidate = jest.spyOn(client, 'invalidateQueries');
    const json = JSON.stringify(tree.toJSON());
    expect(json).toContain('Chưa có tên kế hoạch');
    expect(json).toContain('Chưa xác định trong bản ghi cũ');
    expect(json).toContain('America/New_York');
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-accept' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'student-change-confirmation' })).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ testID: 'student-change-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    expect(decide).toHaveBeenCalledWith('proposal-1', { expectedProposalVersion: 4, decision: 'ACCEPT', decisionReason: null, commandKey: 'stable-key' });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: workoutScheduleChangeProposalQueryKeys.student('student-1') });
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-open-replacement' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/workouts/schedule/replacement-1');
    renderer.act(() => tree.unmount()); decide.mockRestore();
  });

  it('retains exact REJECT body and key after an uncertain network result, even when Trainer authority is no longer current', async () => {
    const decide = jest.spyOn(workoutScheduleChangeProposalApi, 'decide')
      .mockRejectedValueOnce(new ApiError(0, 'network'))
      .mockResolvedValueOnce({ proposal: { ...proposal, status: 'REJECTED' }, appliedChange: null, replayed: true } as never);
    const { tree } = render(<StudentChangeProposalDetailScreen proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-reject' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'student-change-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); await Promise.resolve(); });
    expect(decide).toHaveBeenNthCalledWith(1, 'proposal-1', { expectedProposalVersion: 4, decision: 'REJECT', decisionReason: null, commandKey: 'stable-key' });
    expect(decide.mock.calls[1]).toEqual(decide.mock.calls[0]);
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount()); decide.mockRestore();
  });

  it('409 refetches proposal and calendar but never auto-ACCEPTs a stale source', async () => {
    const decide = jest.spyOn(workoutScheduleChangeProposalApi, 'decide').mockRejectedValue(new ApiError(409, 'stale'));
    const { tree, client } = render(<StudentChangeProposalDetailScreen proposalId="proposal-1" />);
    const invalidate = jest.spyOn(client, 'invalidateQueries');
    const refetch = (useStudentWorkoutScheduleChangeProposalDetail as jest.Mock).mock.results.at(-1)?.value.refetch;
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-accept' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'student-change-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'student-change-review' })).toBeTruthy();
    expect(tree.root.findAllByProps({ testID: 'student-change-confirm-decision' })).toHaveLength(0);
    expect(refetch).toHaveBeenCalled();
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    expect(decide).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount()); decide.mockRestore();
  });

  it('requires Student reason for late ACCEPT CANCEL; Trainer reason is not reused', async () => {
    (useStudentWorkoutScheduleChangeProposalDetail as jest.Mock).mockReturnValue({ data: { ...proposal,
      changeType: 'CANCEL', destination: null, oldPlannedStartAt: '2026-10-12T11:00:00Z', reason: 'Trainer reason' },
      isPending: false, isError: false, isFetching: false, refetch: jest.fn() });
    const decide = jest.spyOn(workoutScheduleChangeProposalApi, 'decide').mockResolvedValue({
      proposal: { ...proposal, status: 'ACCEPTED' }, appliedChange: null, replayed: false,
    } as never);
    const { tree } = render(<StudentChangeProposalDetailScreen proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-accept' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-confirm-decision' }).props.onPress());
    expect(decide).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('Hãy nhập lý do của bạn');
    renderer.act(() => tree.root.findByProps({ testID: 'student-change-decision-reason' }).props.onChangeText('Bận việc gia đình'));
    await renderer.act(async () => { tree.root.findByProps({ testID: 'student-change-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    expect(decide).toHaveBeenCalledWith('proposal-1', { expectedProposalVersion: 4, decision: 'ACCEPT', decisionReason: 'Bận việc gia đình', commandKey: 'stable-key' });
    renderer.act(() => tree.unmount()); decide.mockRestore();
  });

  it('terminal or concealed proposals show no decision and never reveal cached content', () => {
    (useStudentWorkoutScheduleChangeProposalDetail as jest.Mock).mockReturnValue({ data: { ...proposal, status: 'ACCEPTED' }, isPending: false, isError: false, isFetching: false });
    const terminal = render(<StudentChangeProposalDetailScreen proposalId="proposal-1" />);
    expect(terminal.tree.root.findAllByProps({ testID: 'student-change-accept' })).toHaveLength(0);
    renderer.act(() => terminal.tree.unmount());
    (useStudentWorkoutScheduleChangeProposalDetail as jest.Mock).mockReturnValue({ data: proposal, isPending: false,
      isError: true, isFetching: false, error: new ApiError(404, 'concealed') });
    const concealed = render(<StudentChangeProposalDetailScreen proposalId="proposal-1" />);
    expect(concealed.tree.root.findByProps({ testID: 'student-change-detail-denied' })).toBeTruthy();
    expect(JSON.stringify(concealed.tree.toJSON())).not.toContain('Upper body');
    renderer.act(() => concealed.tree.unmount());
  });

  it('conceals cached Student list on 403 and disables queries outside Student capability', () => {
    (useStudentWorkoutScheduleChangeProposals as jest.Mock).mockReturnValue({
      data: { pages: [{ items: [proposal], totalItems: 1 }] }, isPending: false,
      isError: true, error: new ApiError(403, 'denied'), hasNextPage: false,
    });
    const denied = render(<StudentChangeProposalsScreen />);
    expect(denied.tree.root.findByProps({ testID: 'student-change-proposals-denied' })).toBeTruthy();
    expect(JSON.stringify(denied.tree.toJSON())).not.toContain('Upper body');
    renderer.act(() => denied.tree.unmount());
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'TRAINER' });
    const wrongRole = render(<StudentChangeProposalsScreen />);
    expect((useStudentWorkoutScheduleChangeProposals as jest.Mock).mock.calls.at(-1)?.[2]).toBe(false);
    expect(JSON.stringify(wrongRole.tree.toJSON())).not.toContain('Upper body');
    renderer.act(() => wrongRole.tree.unmount());
  });
});
