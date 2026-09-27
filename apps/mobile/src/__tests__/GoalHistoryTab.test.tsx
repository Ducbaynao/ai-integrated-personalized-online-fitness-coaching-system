import React from 'react';
import renderer from 'react-test-renderer';
import { GoalHistoryTab } from '@/features/goal/components/GoalHistoryTab';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { FitnessGoalVersion, GoalTransition } from '@/types/goal';

jest.mock('@/services/fitnessGoalApi', () => ({
  fitnessGoalApi: {
    getGoalVersions: jest.fn(),
    getGoalTransitions: jest.fn(),
  },
}));

describe('GoalHistoryTab', () => {
  const goalId = 'goal-123';

  const mockVersion1: FitnessGoalVersion = {
    id: 'ver-1',
    versionNumber: 1,
    title: 'Initial Hypertrophy Phase',
    startDate: '2026-01-01',
    targetDate: '2026-04-01',
    durationDays: 90,
    effectiveFrom: '2026-01-01T00:00:00Z',
    effectiveUntil: '2026-04-01T00:00:00Z',
    resumeDate: null,
    changeReason: 'Initial setup',
    changeSummary: 'Created first version',
    createdBy: 'student-1',
    sourceProposalId: null,
    createdAt: '2026-01-01T00:00:00Z',
    lockedAt: null,
    lockedBy: null,
    lockReason: null,
    isCurrent: false,
    objectives: [],
    targets: [],
  };

  const mockVersion2: FitnessGoalVersion = {
    id: 'ver-2',
    versionNumber: 2,
    title: 'Strength and Power Phase',
    startDate: '2026-04-01',
    targetDate: '2026-07-01',
    durationDays: 91,
    effectiveFrom: '2026-04-01T00:00:00Z',
    effectiveUntil: null,
    resumeDate: null,
    changeReason: 'Progressed to strength phase',
    changeSummary: 'Increased squat target',
    createdBy: 'trainer-1',
    sourceProposalId: 'prop-1',
    createdAt: '2026-04-01T00:00:00Z',
    lockedAt: null,
    lockedBy: null,
    lockReason: null,
    isCurrent: true,
    objectives: [],
    targets: [],
  };

  const mockTransition: GoalTransition = {
    id: 'trans-1',
    previousGoalId: 'goal-old',
    newGoalId: goalId,
    transitionReason: 'Completed fat loss phase successfully',
    initiatedBy: 'student-1',
    transitionedAt: '2026-01-01T00:00:00Z',
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders goal versions list successfully', async () => {
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValueOnce({
      items: [mockVersion2, mockVersion1],
      page: 0,
      size: 20,
      totalItems: 2,
      totalPages: 1,
    });
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValueOnce([mockTransition]);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalHistoryTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'version-card-2' })).toBeDefined();
    expect(root.findByProps({ testID: 'version-card-1' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'empty-versions-card' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'versions-error-card' }).length).toBe(0);
  });

  it('displays independent error card and retry button when versions request fails', async () => {
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockRejectedValueOnce(
      new Error('Failed to fetch versions')
    );
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValueOnce([mockTransition]);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalHistoryTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'versions-error-card' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'empty-versions-card' }).length).toBe(0);

    // Press Retry
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValueOnce({
      items: [mockVersion2],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'retry-versions-button' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'version-card-2' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'versions-error-card' }).length).toBe(0);
  });

  it('displays empty card when versions array is empty without error', async () => {
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValueOnce({
      items: [],
      page: 0,
      size: 20,
      totalItems: 0,
      totalPages: 1,
    });
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValueOnce([]);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalHistoryTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'empty-versions-card' })).toBeDefined();
  });

  it('switches to transitions sub-tab and renders transitions or error card independently', async () => {
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValueOnce({
      items: [mockVersion1],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    });
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockRejectedValueOnce(
      new Error('Failed to fetch transitions')
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalHistoryTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    // Switch to transitions sub-tab
    renderer.act(() => {
      root.findByProps({ testID: 'subtab-transitions-button' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'transitions-error-card' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'empty-transitions-card' }).length).toBe(0);

    // Retry transitions
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValueOnce([mockTransition]);
    await renderer.act(async () => {
      await root.findByProps({ testID: 'retry-transitions-button' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'transition-card-trans-1' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'transitions-error-card' }).length).toBe(0);
  });

  it('supports pagination with load more, keeps existing items on error, and allows retry', async () => {
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValueOnce({
      items: [mockVersion2],
      page: 0,
      size: 1,
      totalItems: 2,
      totalPages: 2,
    });
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValueOnce([]);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalHistoryTab goalId={goalId} isDark={false} />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'version-card-2' })).toBeDefined();
    const loadMoreBtn = root.findByProps({ testID: 'load-more-versions-button' });
    expect(loadMoreBtn).toBeDefined();

    // Trigger load more which fails
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockRejectedValueOnce(
      new Error('Network error loading page 2')
    );

    await renderer.act(async () => {
      loadMoreBtn.props.onPress();
    });

    // Existing version-2 is still retained!
    expect(root.findByProps({ testID: 'version-card-2' })).toBeDefined();
    // Load more error row is displayed
    const retryLoadMoreBtn = root.findByProps({ testID: 'retry-load-more-versions-button' });
    expect(retryLoadMoreBtn).toBeDefined();

    // Retry load more succeeds with deduplicated item
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValueOnce({
      items: [mockVersion1],
      page: 1,
      size: 1,
      totalItems: 2,
      totalPages: 2,
    });

    await renderer.act(async () => {
      retryLoadMoreBtn.props.onPress();
    });

    expect(root.findByProps({ testID: 'version-card-2' })).toBeDefined();
    expect(root.findByProps({ testID: 'version-card-1' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'retry-load-more-versions-button' }).length).toBe(0);
  });

  it('guards against stale response using sequence tokens when goalId changes', async () => {
    let resolveSlowRequest!: (val: any) => void;
    const slowPromise = new Promise((resolve) => {
      resolveSlowRequest = resolve;
    });

    const slowVersions = {
      items: [
        {
          ...mockVersion1,
          id: 'ver-slow-old',
          title: 'Old Stale Slow Version',
          versionNumber: 99,
        },
      ],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    };

    const fastVersions = {
      items: [
        {
          ...mockVersion2,
          id: 'ver-fast-new',
          title: 'New Fast Goal Version',
          versionNumber: 1,
        },
      ],
      page: 0,
      size: 20,
      totalItems: 1,
      totalPages: 1,
    };

    (fitnessGoalApi.getGoalVersions as jest.Mock).mockReturnValueOnce(slowPromise);
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValue([]);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalHistoryTab goalId="goal-slow" isDark={false} />);
    });

    // Switch goalId to goal-fast
    (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValueOnce(fastVersions);
    (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValue([]);

    await renderer.act(async () => {
      tree.update(<GoalHistoryTab goalId="goal-fast" isDark={false} />);
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'version-card-1' })).toBeDefined();

    // Now resolve slow request
    await renderer.act(async () => {
      resolveSlowRequest(slowVersions);
    });

    // Stale slow request should NOT overwrite
    expect(root.findByProps({ testID: 'version-card-1' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'version-card-99' }).length).toBe(0);
  });
});
