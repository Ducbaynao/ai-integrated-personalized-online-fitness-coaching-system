import React from 'react';
import renderer from 'react-test-renderer';
import { GoalProposalsTab } from '@/features/goal/components/GoalProposalsTab';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { GoalProposal } from '@/types/goal';

const mockPush = jest.fn();
jest.mock('expo-router', () => {
  const actualReact = jest.requireActual<typeof import('react')>('react');
  return {
    useRouter: () => ({
      push: mockPush,
      replace: jest.fn(),
      back: jest.fn(),
    }),
    useFocusEffect: (cb: any) => actualReact.useEffect(cb, [cb]),
  };
});

jest.mock('@/services/fitnessGoalApi', () => ({
  fitnessGoalApi: {
    getMyGoalProposals: jest.fn(),
  },
}));

describe('GoalProposalsTab', () => {
  const goalId = 'goal-123';

  const mockPendingProposal: GoalProposal = {
    id: 'prop-pending-1',
    studentId: 'student-1',
    fitnessGoalId: goalId,
    baseGoalVersionId: 'ver-1',
    source: 'TRAINER',
    createdBy: 'trainer-1',
    proposedTitle: 'New Hypertrophy Proposal',
    proposedStartDate: '2026-10-01',
    proposedTargetDate: '2027-01-01',
    proposedDurationDays: 92,
    reason: 'Advance to next phase',
    status: 'PENDING',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    createdAt: '2026-09-25T10:00:00Z',
    updatedAt: '2026-09-25T10:00:00Z',
    objectives: [],
    targets: [],
  };

  const mockAcceptedProposal: GoalProposal = {
    id: 'prop-accepted-2',
    studentId: 'student-1',
    fitnessGoalId: goalId,
    baseGoalVersionId: 'ver-1',
    source: 'STUDENT',
    createdBy: 'student-1',
    proposedTitle: 'Accepted Goal Proposal',
    proposedStartDate: '2026-09-01',
    proposedTargetDate: '2026-12-01',
    proposedDurationDays: 91,
    reason: 'Initial goal target setup',
    status: 'ACCEPTED',
    decidedBy: 'student-1',
    decidedAt: '2026-09-01T12:00:00Z',
    decisionNote: 'Looks great',
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-01T12:00:00Z',
    objectives: [],
    targets: [],
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders proposals list and navigates on item press', async () => {
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce({
      items: [mockPendingProposal, mockAcceptedProposal],
      page: 0,
      size: 20,
      totalItems: 2,
      totalPages: 1,
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalsTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'proposal-item-prop-pending-1' })).toBeDefined();
    expect(root.findByProps({ testID: 'proposal-item-prop-accepted-2' })).toBeDefined();

    // Click item
    renderer.act(() => {
      root.findByProps({ testID: 'proposal-item-prop-pending-1' }).props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/goal-proposals/prop-pending-1');
  });

  it('handles error state and allows retry', async () => {
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockRejectedValueOnce(
      new Error('Failed to load proposals')
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalsTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'proposals-error-card' })).toBeDefined();

    // Retry
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce({
      items: [mockPendingProposal],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'retry-proposals-button' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'proposal-item-prop-pending-1' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'proposals-error-card' }).length).toBe(0);
  });

  it('displays empty card when no proposals exist', async () => {
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce({
      items: [],
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 1,
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalsTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'empty-proposals-card' })).toBeDefined();
  });

  it('filters proposals and resets pagination when filter changes', async () => {
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce({
      items: [mockPendingProposal, mockAcceptedProposal],
      page: 0,
      size: 20,
      totalItems: 2,
      totalPages: 1,
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalsTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    // Switch to PENDING filter
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce({
      items: [mockPendingProposal],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    });

    await renderer.act(async () => {
      root.findByProps({ testID: 'proposals-filter-pending' }).props.onPress();
    });

    expect(fitnessGoalApi.getMyGoalProposals).toHaveBeenLastCalledWith(
      expect.objectContaining({
        status: 'PENDING',
        fitnessGoalId: goalId,
        page: 0,
      })
    );
    expect(root.findByProps({ testID: 'proposal-item-prop-pending-1' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'proposal-item-prop-accepted-2' }).length).toBe(0);
  });

  it('supports load more pagination, retains existing proposals on error, and allows retry', async () => {
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce({
      items: [mockPendingProposal],
      page: 0,
      size: 1,
      totalItems: 2,
      totalPages: 2,
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalsTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'proposal-item-prop-pending-1' })).toBeDefined();
    const loadMoreBtn = root.findByProps({ testID: 'load-more-proposals-button' });
    expect(loadMoreBtn).toBeDefined();

    // Load more fails
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockRejectedValueOnce(
      new Error('Failed to load page 2')
    );

    await renderer.act(async () => {
      loadMoreBtn.props.onPress();
    });

    // Existing proposal is retained
    expect(root.findByProps({ testID: 'proposal-item-prop-pending-1' })).toBeDefined();
    const retryLoadMoreBtn = root.findByProps({ testID: 'retry-load-more-proposals-button' });
    expect(retryLoadMoreBtn).toBeDefined();

    // Retry succeeds
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce({
      items: [mockAcceptedProposal],
      page: 1,
      size: 1,
      totalItems: 2,
      totalPages: 2,
    });

    await renderer.act(async () => {
      retryLoadMoreBtn.props.onPress();
    });

    expect(root.findByProps({ testID: 'proposal-item-prop-pending-1' })).toBeDefined();
    expect(root.findByProps({ testID: 'proposal-item-prop-accepted-2' })).toBeDefined();
  });

  it('guards against stale response when changing filter while a slow request is in flight', async () => {
    let resolveSlowRequest!: (val: any) => void;
    const slowPromise = new Promise((resolve) => {
      resolveSlowRequest = resolve;
    });

    const slowAllProposals = {
      items: [
        {
          ...mockAcceptedProposal,
          id: 'prop-slow-all',
          proposedTitle: 'Old Stale All Proposal',
        },
      ],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    };

    const fastPendingProposals = {
      items: [
        {
          ...mockPendingProposal,
          id: 'prop-fast-pending',
          proposedTitle: 'New Fast Pending Proposal',
        },
      ],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    };

    // First request is slow (for ALL)
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockReturnValueOnce(slowPromise);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalsTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    // Immediately switch filter to PENDING, which resolves fast
    (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValueOnce(fastPendingProposals);

    await renderer.act(async () => {
      root.findByProps({ testID: 'proposals-filter-pending' }).props.onPress();
    });

    // Now fast pending item is displayed
    expect(root.findByProps({ testID: 'proposal-item-prop-fast-pending' })).toBeDefined();

    // Now resolve the older slow request
    await renderer.act(async () => {
      resolveSlowRequest(slowAllProposals);
    });

    // The fast pending item MUST still be displayed; slow stale response was dropped!
    expect(root.findByProps({ testID: 'proposal-item-prop-fast-pending' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'proposal-item-prop-slow-all' }).length).toBe(0);
  });
});
