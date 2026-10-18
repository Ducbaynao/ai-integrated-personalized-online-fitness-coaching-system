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
  signal: jest.fn(), signalHistory: jest.fn(), act: jest.fn(),
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

describe('M7 attention and review screen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCanGoBack.mockReturnValue(false);
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
    (useRelationshipDetail as jest.Mock).mockReturnValue({ isFetchedAfterMount: false, isPending: false });
    (attentionReviewApi.timezone as jest.Mock).mockResolvedValue(null);
    (attentionReviewApi.reviews as jest.Mock).mockResolvedValue(page([task]));
    (attentionReviewApi.signals as jest.Mock).mockResolvedValue(page([]));
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
