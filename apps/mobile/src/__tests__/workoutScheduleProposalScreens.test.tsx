import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutScheduleProposalApi } from '@/services/workoutScheduleProposalApi';
import {
  useWorkoutScheduleProposalDetail, useWorkoutScheduleProposals,
} from '@/features/workout-schedule-proposals/workoutScheduleProposalQueries';
import { StudentScheduleProposalsScreen } from '@/features/workout-schedule-proposals/StudentScheduleProposalsScreen';
import { StudentScheduleProposalDetailScreen } from '@/features/workout-schedule-proposals/StudentScheduleProposalDetailScreen';
import type { WorkoutScheduleProposal } from '@/types/workoutScheduleProposal';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/services/workoutScheduleProposalApi', () => ({ workoutScheduleProposalApi: {
  listMine: jest.fn(), detail: jest.fn(), decide: jest.fn(),
} }));
jest.mock('@/features/workout-schedule-proposals/workoutScheduleProposalQueries', () => {
  const actual = jest.requireActual('@/features/workout-schedule-proposals/workoutScheduleProposalQueries');
  return { ...actual, useWorkoutScheduleProposals: jest.fn(), useWorkoutScheduleProposalDetail: jest.fn() };
});

const proposal: WorkoutScheduleProposal = {
  proposalId: 'proposal-1', proposalVersion: 4, status: 'PENDING', studentId: 'student-1',
  trainerId: 'trainer-1', trainerDisplayName: 'Huấn luyện viên An', coachingPeriodId: 'period-1', planId: 'plan-1', sourcePlanName: 'Sức mạnh',
  sourcePlanVersionId: 'version-1', sourcePlanVersionNumber: 3, observedPlanAggregateVersion: 8,
  weekAnchorDate: '2026-10-12', timezone: 'Asia/Ho_Chi_Minh', proposedAt: '2026-10-11T01:00:00Z',
  items: [{ clientItemId: 'item-1', planSessionId: 'session-1', weekNumber: 1, dayNumber: 1,
    sequenceNumber: 1, localStart: '2026-10-12T08:00:00', localEnd: '2026-10-12T09:00:00',
    startUtcOffset: '+07:00', endUtcOffset: '+07:00', sourceSessionName: 'Ngày thân trên', sourceSessionFocus: 'Vai' }],
  decidedAt: null, decidedBy: null, decisionNote: null, withdrawnAt: null, withdrawnBy: null,
};

const clients: QueryClient[] = [];
function render(node: React.ReactNode) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  clients.push(client);
  const invalidate = jest.spyOn(client, 'invalidateQueries');
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{node}</QueryClientProvider>); });
  return { tree, invalidate };
}
function detail(value: WorkoutScheduleProposal = proposal, extras: Record<string, unknown> = {}) {
  (useWorkoutScheduleProposalDetail as jest.Mock).mockReturnValue({
    data: value, isPending: false, isError: false, isFetching: false, error: null, refetch: jest.fn(), ...extras,
  });
}
function list(items: WorkoutScheduleProposal[] = [proposal], extras: Record<string, unknown> = {}) {
  (useWorkoutScheduleProposals as jest.Mock).mockReturnValue({
    data: { pages: [{ items, page: 0, size: 20, totalItems: items.length, totalPages: 1 }] },
    isPending: false, isError: false, error: null, refetch: jest.fn(), hasNextPage: false,
    isFetchingNextPage: false, fetchNextPage: jest.fn(), ...extras,
  });
}

describe('Student schedule proposal screens', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
    (createCommandKey as jest.Mock).mockReturnValue('stable-command-key');
    detail(); list();
  });
  afterEach(() => clients.splice(0).forEach((client) => client.clear()));

  it('lists server proposal names without raw identifiers and opens detail; handles empty and paging', () => {
    let view = render(<StudentScheduleProposalsScreen />);
    expect(JSON.stringify(view.tree.toJSON())).toContain('Huấn luyện viên An');
    expect(JSON.stringify(view.tree.toJSON())).toContain('Sức mạnh');
    expect(JSON.stringify(view.tree.toJSON())).not.toContain('trainer-1');
    renderer.act(() => view.tree.root.findByProps({ testID: 'open-proposal-proposal-1' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/workout-schedule-proposals/proposal-1');
    renderer.act(() => view.tree.unmount());
    const fetchNextPage = jest.fn();
    list([proposal], { hasNextPage: true, fetchNextPage });
    view = render(<StudentScheduleProposalsScreen />);
    renderer.act(() => view.tree.root.findByProps({ testID: 'proposals-load-more' }).props.onPress());
    expect(fetchNextPage).toHaveBeenCalledTimes(1);
    renderer.act(() => view.tree.unmount());
    list([]);
    view = render(<StudentScheduleProposalsScreen />);
    expect(view.tree.root.findByProps({ testID: 'proposals-empty' })).toBeTruthy();
    renderer.act(() => view.tree.unmount());
  });

  it('renders exact local intent and nullable history without inventing names; terminal has no decisions', () => {
    detail({ ...proposal, status: 'WITHDRAWN', withdrawnAt: '2026-10-11T02:00:00Z' });
    const { tree } = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    const output = JSON.stringify(tree.toJSON());
    expect(output).toContain('2026-10-12 08:00:00');
    expect(output).toContain('UTC+07:00');
    expect(output).toContain('Ngày thân trên');
    expect(output).not.toContain('session-1');
    expect(tree.root.findAllByProps({ testID: 'proposal-accept' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('keeps legacy nullable names explicit without exposing raw IDs after coaching ends', () => {
    const legacy = { ...proposal, trainerDisplayName: null, sourcePlanName: null,
      items: [{ ...proposal.items[0], sourceSessionName: null, sourceSessionFocus: null }] };
    detail(legacy);
    const { tree } = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    const output = JSON.stringify(tree.toJSON());
    expect(output).toContain('Chưa có tên huấn luyện viên');
    expect(output).toContain('Chưa có tên kế hoạch');
    expect(output).not.toContain('trainer-1');
    expect(output).not.toContain('session-1');
    renderer.act(() => tree.unmount());
  });

  it('confirms ACCEPT once using the actual version and invalidates proposal/calendar state', async () => {
    let finish!: (value: unknown) => void;
    (workoutScheduleProposalApi.decide as jest.Mock).mockReturnValue(new Promise((resolve) => { finish = resolve; }));
    const { tree, invalidate } = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'proposal-accept' }).props.onPress());
    expect(workoutScheduleProposalApi.decide).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('không buổi nào được tạo');
    await renderer.act(async () => {
      const confirm = tree.root.findByProps({ testID: 'proposal-confirm-decision' });
      confirm.props.onPress(); confirm.props.onPress(); await Promise.resolve();
    });
    expect(workoutScheduleProposalApi.decide).toHaveBeenCalledTimes(1);
    expect(workoutScheduleProposalApi.decide).toHaveBeenCalledWith('proposal-1', {
      expectedProposalVersion: 4, decision: 'ACCEPT', commandKey: 'stable-command-key',
    });
    await renderer.act(async () => {
      finish({ proposal: { ...proposal, status: 'ACCEPTED', proposalVersion: 5 }, confirmedBatch: { batchId: 'batch-1' }, replayed: false });
      await Promise.resolve();
    });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workout-schedule-proposals', 'student', 'student-1'] });
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['planned-workouts', 'student', 'student-1'] });
    renderer.act(() => tree.unmount());
  });

  it('REJECT sends a separate explicit decision without creating a local occurrence', async () => {
    (workoutScheduleProposalApi.decide as jest.Mock).mockResolvedValue({
      proposal: { ...proposal, status: 'REJECTED' }, confirmedBatch: null, replayed: false,
    });
    const { tree } = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'proposal-reject' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'proposal-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    expect(workoutScheduleProposalApi.decide).toHaveBeenCalledWith('proposal-1', {
      expectedProposalVersion: 4, decision: 'REJECT', commandKey: 'stable-command-key',
    });
    renderer.act(() => tree.unmount());
  });

  it('retries network uncertainty with identical key and version', async () => {
    (workoutScheduleProposalApi.decide as jest.Mock)
      .mockRejectedValueOnce(new ApiError(0, 'offline'))
      .mockResolvedValueOnce({ proposal: { ...proposal, status: 'ACCEPTED' }, confirmedBatch: {}, replayed: true });
    const { tree } = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'proposal-accept' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'proposal-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'proposal-decision-error' })).toBeTruthy();
    await renderer.act(async () => { tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress(); await Promise.resolve(); });
    expect(workoutScheduleProposalApi.decide).toHaveBeenNthCalledWith(1, 'proposal-1', {
      expectedProposalVersion: 4, decision: 'ACCEPT', commandKey: 'stable-command-key',
    });
    expect(workoutScheduleProposalApi.decide).toHaveBeenNthCalledWith(2, 'proposal-1', {
      expectedProposalVersion: 4, decision: 'ACCEPT', commandKey: 'stable-command-key',
    });
    expect(createCommandKey).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
  });

  it('stale 409 refreshes and requires a new explicit decision without automatic replay', async () => {
    (workoutScheduleProposalApi.decide as jest.Mock).mockRejectedValue(new ApiError(409, 'stale', {
      errorCode: 'WORKOUT_SCHEDULE_PROPOSAL_VERSION_CONFLICT', message: 'stale', timestamp: '', requestId: '', fieldErrors: [],
    }));
    const { tree, invalidate } = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'proposal-accept' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'proposal-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    expect(workoutScheduleProposalApi.decide).toHaveBeenCalledTimes(1);
    expect(tree.root.findAllByProps({ testID: 'proposal-confirmation' })).toHaveLength(0);
    expect(invalidate).toHaveBeenCalledWith({ queryKey: ['workout-schedule-proposals', 'student', 'student-1'] });
    expect(tree.root.findByProps({ testID: 'proposal-decision-error' })).toBeTruthy();
    renderer.act(() => tree.unmount());
  });

  it('conceals cached detail immediately when decision loses authority', async () => {
    (workoutScheduleProposalApi.decide as jest.Mock).mockRejectedValue(new ApiError(403, 'denied'));
    const { tree } = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'proposal-accept' }).props.onPress());
    await renderer.act(async () => { tree.root.findByProps({ testID: 'proposal-confirm-decision' }).props.onPress(); await Promise.resolve(); });
    expect(tree.root.findByProps({ testID: 'proposal-permission-lost' })).toBeTruthy();
    expect(JSON.stringify(tree.toJSON())).not.toContain('Huấn luyện viên An');
    expect(tree.root.findAllByProps({ testID: 'proposal-accept' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('handles loading, concealment and capability guard without leaking proposal content', () => {
    detail(proposal, { data: undefined, isPending: true });
    let view = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    expect(JSON.stringify(view.tree.toJSON())).toContain('Đang tải đề xuất');
    renderer.act(() => view.tree.unmount());
    detail(proposal, { isError: true, error: new ApiError(404, 'hidden') });
    view = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    expect(view.tree.root.findByProps({ testID: 'proposal-detail-error' })).toBeTruthy();
    expect(JSON.stringify(view.tree.toJSON())).not.toContain('Huấn luyện viên An');
    renderer.act(() => view.tree.unmount());
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1', capabilities: { hasStudentProfile: false } }, activeCapability: 'TRAINER' });
    view = render(<StudentScheduleProposalDetailScreen proposalId="proposal-1" />);
    expect(JSON.stringify(view.tree.toJSON())).not.toContain('Sức mạnh');
    renderer.act(() => view.tree.unmount());
  });
});
