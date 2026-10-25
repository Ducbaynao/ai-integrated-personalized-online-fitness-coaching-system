import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AttentionReviewScreen } from '@/features/attention/AttentionReviewScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { attentionReviewApi } from '@/services/attentionReviewApi';
import { ApiError } from '@/types/auth';

const mockBack = jest.fn();
const mockReplace = jest.fn();
const mockCanGoBack = jest.fn(() => false);
jest.mock('expo-router', () => ({ useRouter: () => ({ back: mockBack, replace: mockReplace, canGoBack: mockCanGoBack }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useRelationshipDetail: jest.fn() }));
jest.mock('@/services/attentionReviewApi', () => ({ attentionReviewApi: {
  timezone: jest.fn(), confirmTimezone: jest.fn(), reviews: jest.fn(), review: jest.fn(),
  complete: jest.fn(), acknowledgeReview: jest.fn(), signals: jest.fn(),
  signal: jest.fn(), signalHistory: jest.fn(), act: jest.fn(), returnStatus: jest.fn(),
} }));

const clients: QueryClient[] = [];
const trees: renderer.ReactTestRenderer[] = [];
function render(element: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: {
    queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 },
  } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>); });
  trees.push(tree);
  return tree;
}
async function settle() {
  await renderer.act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
}
const task = {
  id: 'review-1', studentId: 'student-1', trainerId: 'trainer-1', coachingPeriodId: 'period-1',
  cycleStartDate: '2026-10-17', dueDate: '2026-10-24', timezone: 'Asia/Bangkok',
  status: 'COMPLETED', version: 1, summary: 'Dữ liệu hiện có', evidenceRefs: [], missingData: [],
  completedAt: '2026-10-18T00:00:00Z', studentAcknowledgedAt: null,
};
const page = (items: unknown[]) => ({ items, page: 0, size: 20, hasMore: false });
const signal = (id: string, rule: 'REVIEW_OVERDUE_V1' | 'RETURN_AFTER_100_DAYS_V1') => ({
  id, studentId: 'student-1', trainerId: 'trainer-1', coachingPeriodId: 'period-1',
  rule, severity: 'LOW', status: 'OPEN', version: 0,
  occurrenceId: null, occurrenceVersion: null, plannedStartAt: null, plannedEndAt: null,
  reviewTaskId: rule === 'REVIEW_OVERDUE_V1' ? 'review-1' : null,
  returnEvidence: rule === 'RETURN_AFTER_100_DAYS_V1' ? {
    previousExecutionId: 'prior', previousExecutionVersion: 1,
    currentExecutionId: 'current', currentExecutionVersion: 1,
    previousCompletedAt: '2026-01-01T10:00:00Z', currentCompletedAt: '2026-04-11T10:00:00Z',
    previousLocalDate: '2026-01-01', currentLocalDate: '2026-04-11',
    timezone: 'Asia/Bangkok', calendarDays: 100,
  } : null,
  detectedAt: '2026-04-11T10:00:00Z',
});

describe('M7 attention and review screen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCanGoBack.mockReturnValue(false);
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: false, isPending: false });
    (attentionReviewApi.timezone as jest.Mock).mockResolvedValue(null);
    (attentionReviewApi.reviews as jest.Mock).mockResolvedValue(page([task]));
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue(page([]));
    (attentionReviewApi.returnStatus as jest.Mock).mockResolvedValue({
      status: 'UNKNOWN', reason: 'PREVIOUS_COMPLETION_MISSING', timezone: 'Asia/Bangkok',
    });
    (attentionReviewApi.acknowledgeReview as jest.Mock).mockResolvedValue({ ...task, version: 2,
      studentAcknowledgedAt: '2026-10-18T01:00:00Z' });
  });
  afterEach(() => {
    renderer.act(() => { trees.splice(0).forEach(tree => tree.unmount()); });
    clients.splice(0).forEach(client => client.clear());
  });

  it('lists own review and requires explicit Student acknowledgment', async () => {
    const tree = render(<AttentionReviewScreen />);
    await settle();
    expect(attentionReviewApi.acknowledgeReview).not.toHaveBeenCalled();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Xác nhận đã xem' }).props.onPress());
    await settle();
    expect(attentionReviewApi.acknowledgeReview).toHaveBeenCalledWith('review-1', 1, expect.any(String));
  });

  it('labels absent completion evidence as unknown rather than zero', async () => {
    const empty = render(<AttentionReviewScreen />);
    await settle();
    expect(JSON.stringify(empty.toJSON())).toContain('chưa xác định');
    expect(attentionReviewApi.returnStatus).toHaveBeenCalledWith(undefined);
  });

  it('explains completed-workout return evidence without inventing a plan action', async () => {
    const evidence = {
      previousExecutionId: 'prior', previousExecutionVersion: 2,
      currentExecutionId: 'current', currentExecutionVersion: 1,
      previousCompletedAt: '2026-01-01T10:00:00Z', currentCompletedAt: '2026-04-11T10:00:00Z',
      previousLocalDate: '2026-01-01', currentLocalDate: '2026-04-11',
      timezone: 'Asia/Bangkok', calendarDays: 100,
    };
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue(page([{
      id: 'signal-return', studentId: 'student-1', trainerId: null, coachingPeriodId: null,
      rule: 'RETURN_AFTER_100_DAYS_V1', severity: 'LOW', status: 'OPEN', version: 0,
      occurrenceId: null, occurrenceVersion: null, plannedStartAt: null, plannedEndAt: null,
      reviewTaskId: null, returnEvidence: evidence, detectedAt: '2026-04-11T10:00:00Z',
    }]));
    const result = render(<AttentionReviewScreen />);
    await settle();
    expect(result.root.findAllByProps({ testID: 'attention-signal-return' }).length).toBeGreaterThan(0);
    expect(JSON.stringify(result.toJSON())).toContain(' ngày lịch');
    expect(JSON.stringify(result.toJSON())).toContain('"100"');
    expect(JSON.stringify(result.toJSON())).toContain('không tự đổi kế hoạch tập');
  });

  it('returns to Coaching hub when opened directly without navigation history', async () => {
    const tree = render(<AttentionReviewScreen />);
    await settle();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Quay lại' }).props.onPress());
    expect(mockBack).not.toHaveBeenCalled();
    expect(mockReplace).toHaveBeenCalledWith('/coaching');
  });

  it('conceals Trainer data when the current period is not authorized', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: true, isPending: false,
      data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'PAUSED' },
        currentPeriod: null } });
    const tree = render(<AttentionReviewScreen relationshipId="relationship-1" />);
    await settle();
    expect(attentionReviewApi.reviews).not.toHaveBeenCalled();
    expect(tree.root.findAllByProps({ testID: 'review-review-1' })).toHaveLength(0);
  });

  it('requires a fresh relationship read when Trainer opens Attention', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: false, isFetching: true,
      isPending: false, data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE' },
        currentPeriod: { mode: 'HUMAN_COACH' } } });
    const tree = render(<AttentionReviewScreen relationshipId="relationship-1" />);
    await settle();
    expect(useRelationshipDetail).toHaveBeenCalledWith('relationship-1', true);
    expect(attentionReviewApi.reviews).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('Đang kiểm tra quyền');
    expect(JSON.stringify(tree.toJSON())).not.toContain('Không thể xem dữ liệu');
  });

  it('does not show return evidence when current Trainer lacks WORKOUT_HISTORY VIEW', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: true, isPending: false,
      data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE' },
        currentPeriod: { mode: 'HUMAN_COACH' } } });
    (attentionReviewApi.returnStatus as jest.Mock).mockRejectedValue(new ApiError(404, 'concealed'));
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue(page([
      signal('review-signal', 'REVIEW_OVERDUE_V1'), signal('return-signal', 'RETURN_AFTER_100_DAYS_V1'),
    ]));
    const tree = render(<AttentionReviewScreen relationshipId="relationship-1" />);
    await settle();
    expect(attentionReviewApi.returnStatus).toHaveBeenCalledWith('student-1');
    expect(tree.root.findAllByProps({ testID: 'return-100-day-status' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'review-review-1' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'attention-review-signal' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'attention-return-signal' })).toHaveLength(0);
    expect(JSON.stringify(clients[0].getQueryData(['attention-review', 'trainer-1', 'TRAINER', 'student-1', 'signals'])))
      .not.toContain('return-signal');
    (attentionReviewApi.returnStatus as jest.Mock).mockResolvedValue({
      status: 'DETECTED', reason: null, timezone: 'Asia/Bangkok',
    });
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử lại' }).props.onPress());
    await settle();
    expect(tree.root.findAllByProps({ testID: 'return-100-day-status' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'attention-return-signal' }).length).toBeGreaterThan(0);
  });

  it('removes stale return evidence after a denied refetch but retains allowed review data', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: true, isPending: false,
      data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE' },
        currentPeriod: { mode: 'HUMAN_COACH' } } });
    (attentionReviewApi.returnStatus as jest.Mock).mockResolvedValue({
      status: 'DETECTED', reason: null, timezone: 'Asia/Bangkok',
    });
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue(page([
      signal('review-signal', 'REVIEW_OVERDUE_V1'), signal('return-signal', 'RETURN_AFTER_100_DAYS_V1'),
    ]));
    const tree = render(<AttentionReviewScreen relationshipId="relationship-1" />);
    await settle();
    expect(tree.root.findAllByProps({ testID: 'return-100-day-status' }).length).toBeGreaterThan(0);
    (attentionReviewApi.returnStatus as jest.Mock).mockRejectedValue(new ApiError(403, 'revoked'));
    await renderer.act(async () => {
      await clients[0].refetchQueries({ queryKey: ['attention-review', 'trainer-1', 'TRAINER', 'student-1', 'return-status'] });
    });
    await settle();
    expect(tree.root.findAllByProps({ testID: 'return-100-day-status' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'review-review-1' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'attention-review-signal' }).length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ testID: 'attention-return-signal' })).toHaveLength(0);
    expect(JSON.stringify(clients[0].getQueryData(['attention-review', 'trainer-1', 'TRAINER', 'student-1', 'signals'])))
      .not.toContain('return-signal');
  });

  it('drops visible Trainer data after a concealed 404', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: true, isPending: false,
      data: { relationship: { studentId: 'student-1', trainerId: 'trainer-1', status: 'ACTIVE' },
        currentPeriod: { mode: 'HUMAN_COACH' } } });
    (attentionReviewApi.reviews as jest.Mock).mockRejectedValue(new ApiError(404, 'concealed'));
    const tree = render(<AttentionReviewScreen relationshipId="relationship-1" />);
    await settle();
    expect(tree.root.findAllByProps({ testID: 'review-review-1' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Hoàn tất đánh giá' })).toHaveLength(0);
  });
});
