import React from 'react';
import { Alert } from 'react-native';
import renderer from 'react-test-renderer';
import { CurrentGoalScreen } from '@/features/goal/CurrentGoalScreen';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/types/auth';
import { FitnessGoal } from '@/types/goal';

let mockActiveFocusCleanup: (() => void) | void = undefined;
let mockActiveFocusCallback: (() => (() => void) | void) | null = null;

const simulateScreenBlur = () => {
  if (typeof mockActiveFocusCleanup === 'function') {
    mockActiveFocusCleanup();
    mockActiveFocusCleanup = undefined;
  }
};

const simulateScreenFocus = async () => {
  if (mockActiveFocusCallback) {
    await renderer.act(async () => {
      mockActiveFocusCleanup = mockActiveFocusCallback!();
    });
  }
};

jest.mock('expo-router', () => {
  const actualReact = jest.requireActual<typeof import('react')>('react');
  return {
    useRouter: () => ({
      push: jest.fn(),
      replace: jest.fn(),
      back: jest.fn(),
      canGoBack: () => true,
    }),
    useFocusEffect: (cb: any) => {
      actualReact.useEffect(() => {
        mockActiveFocusCallback = cb;
        mockActiveFocusCleanup = cb();
        return () => {
          if (typeof mockActiveFocusCleanup === 'function') {
            mockActiveFocusCleanup();
            mockActiveFocusCleanup = undefined;
          }
          mockActiveFocusCallback = null;
        };
      }, [cb]);
    },
  };
});

jest.mock('@/services/fitnessGoalApi', () => ({
  fitnessGoalApi: {
    getCurrentGoal: jest.fn(),
    getGoal: jest.fn(),
    getGoalVersions: jest.fn().mockResolvedValue({ items: [], totalPages: 1 }),
    getGoalTransitions: jest.fn().mockResolvedValue([]),
    getMyGoalProposals: jest.fn().mockResolvedValue({ items: [], totalPages: 1 }),
    pauseGoal: jest.fn(),
    resumeGoal: jest.fn(),
  },
}));

describe('CurrentGoalScreen', () => {
  const activeGoal: FitnessGoal = {
    id: 'goal-123',
    studentId: 'student-abc',
    title: 'Strength and Hypertrophy Focus',
    status: 'ACTIVE',
    createdBy: 'student-abc',
    activatedAt: '2026-09-01T10:00:00Z',
    pausedAt: null,
    completedAt: null,
    endedAt: null,
    statusReason: 'Initial setup',
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-01T10:00:00Z',
    currentVersion: {
      id: 'version-1',
      versionNumber: 1,
      title: 'Phase 1 - Hypertrophy Baseline',
      startDate: '2026-09-01',
      targetDate: '2026-12-01',
      durationDays: 91,
      effectiveFrom: '2026-09-01T10:00:00Z',
      effectiveUntil: null,
      resumeDate: null,
      changeReason: 'Initial baseline',
      changeSummary: 'Baseline hypertrophy program',
      createdBy: 'student-abc',
      sourceProposalId: null,
      createdAt: '2026-09-01T10:00:00Z',
      lockedAt: null,
      lockedBy: null,
      lockReason: null,
      isCurrent: true,
      objectives: [
        {
          id: 'obj-1',
          goalTypeId: 1,
          goalTypeCode: 'MUSCLE_GAIN',
          goalTypeName: 'Muscle Gain',
          priority: 'PRIMARY',
          sortOrder: 1,
          notes: 'Focus on upper body volume',
        },
      ],
      targets: [
        {
          id: 'tgt-1',
          metricDefinitionId: 10,
          metricCode: 'BODY_WEIGHT',
          metricDisplayName: 'Body Weight',
          startValue: 70,
          targetValue: 75,
          unitId: 1,
          unitCode: 'KG',
          unitSymbol: 'kg',
          targetDate: '2026-12-01',
          notes: 'Gradual lean bulk',
        },
      ],
    },
  };

  const pausedGoal: FitnessGoal = {
    ...activeGoal,
    status: 'PAUSED',
    pausedAt: '2026-09-20T12:00:00Z',
    statusReason: 'Recovering from ankle sprain',
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Alert, 'alert').mockImplementation(() => {});
    mockActiveFocusCleanup = undefined;
    mockActiveFocusCallback = null;
  });

  it('renders active goal overview tab with title and Pause button', async () => {
    (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<CurrentGoalScreen />);
    });
    const root = tree!.root;

    expect(fitnessGoalApi.getCurrentGoal).toHaveBeenCalledWith(true);
    expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'resume-goal-button' }).length).toBe(0);
  });

  it('allows student to pause an active goal with mandatory reason and confirmation', async () => {
    (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);
    (fitnessGoalApi.pauseGoal as jest.Mock).mockResolvedValueOnce(pausedGoal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<CurrentGoalScreen />);
    });
    const root = tree!.root;

    // Click "Pause Goal"
    const pauseBtn = root.findByProps({ testID: 'pause-goal-button' });
    renderer.act(() => {
      pauseBtn.props.onPress();
    });

    // Enter mandatory reason
    const input = root.findByProps({ testID: 'pause-resume-reason-input' });
    renderer.act(() => {
      input.props.onChangeText('Recovering from ankle sprain');
    });

    // Click Continue to step to confirmation
    const continueBtn = root.findByProps({ testID: 'pause-resume-continue-button' });
    renderer.act(() => {
      continueBtn.props.onPress();
    });

    // Confirm pause
    const confirmBtn = root.findByProps({ testID: 'pause-resume-confirm-button' });
    await renderer.act(async () => {
      confirmBtn.props.onPress();
    });

    expect(fitnessGoalApi.pauseGoal).toHaveBeenCalledWith(
      'goal-123',
      'Recovering from ankle sprain'
    );
  });

  it('renders paused goal with paused banner and Resume button', async () => {
    (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(pausedGoal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<CurrentGoalScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'paused-status-banner' })).toBeDefined();
    expect(root.findByProps({ testID: 'resume-goal-button' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'pause-goal-button' }).length).toBe(0);
  });

  it('allows student to resume a paused goal with mandatory reason', async () => {
    (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(pausedGoal);
    (fitnessGoalApi.resumeGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<CurrentGoalScreen />);
    });
    const root = tree!.root;

    // Click "Resume Goal"
    const resumeBtn = root.findByProps({ testID: 'resume-goal-button' });
    renderer.act(() => {
      resumeBtn.props.onPress();
    });

    // Enter reason
    const input = root.findByProps({ testID: 'pause-resume-reason-input' });
    renderer.act(() => {
      input.props.onChangeText('Fully healed and ready to train');
    });

    // Continue
    const continueBtn = root.findByProps({ testID: 'pause-resume-continue-button' });
    renderer.act(() => {
      continueBtn.props.onPress();
    });

    // Confirm
    const confirmBtn = root.findByProps({ testID: 'pause-resume-confirm-button' });
    await renderer.act(async () => {
      confirmBtn.props.onPress();
    });

    expect(fitnessGoalApi.resumeGoal).toHaveBeenCalledWith(
      'goal-123',
      'Fully healed and ready to train'
    );
  });

  describe('Pause/Resume 409 error hierarchy mapping', () => {
    it('maps ACTIVE_FITNESS_GOAL_ALREADY_EXISTS specifically first', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(pausedGoal);
      const conflictError = new ApiError(409, 'Conflict', {
        errorCode: 'ACTIVE_FITNESS_GOAL_ALREADY_EXISTS',
        message: 'Student already has an active fitness goal',
        timestamp: '2026-09-26T00:00:00Z',
        requestId: 'req-1',
        fieldErrors: [],
      });
      (fitnessGoalApi.resumeGoal as jest.Mock).mockRejectedValueOnce(conflictError);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      renderer.act(() => {
        root.findByProps({ testID: 'resume-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Ready');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      await renderer.act(async () => {
        try {
          await root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
        } catch {}
      });

      expect(Alert.alert).toHaveBeenCalledWith(
        'Status Update Failed',
        'You already have another active fitness goal. Only one active goal is permitted at a time.'
      );
    });

    it('maps GOAL_LIFECYCLE_CONFLICT specifically second', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(pausedGoal);
      const conflictError = new ApiError(409, 'Conflict', {
        errorCode: 'GOAL_LIFECYCLE_CONFLICT',
        message: 'Status mismatch during CAS update',
        timestamp: '2026-09-26T00:00:00Z',
        requestId: 'req-2',
        fieldErrors: [],
      });
      (fitnessGoalApi.resumeGoal as jest.Mock).mockRejectedValueOnce(conflictError);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      renderer.act(() => {
        root.findByProps({ testID: 'resume-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Ready');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      await renderer.act(async () => {
        try {
          await root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
        } catch {}
      });

      expect(Alert.alert).toHaveBeenCalledWith(
        'Status Update Failed',
        'The goal status was updated by another request. Please refresh to see the latest status.'
      );
    });

    it('falls back to generic 409 message when no recognized error code is provided', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(pausedGoal);
      const genericConflict = new ApiError(409, 'Generic conflict');
      (fitnessGoalApi.resumeGoal as jest.Mock).mockRejectedValueOnce(genericConflict);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      renderer.act(() => {
        root.findByProps({ testID: 'resume-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Ready');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      await renderer.act(async () => {
        try {
          await root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
        } catch {}
      });

      expect(Alert.alert).toHaveBeenCalledWith(
        'Status Update Failed',
        'Goal status conflict occurred. Please refresh and try again.'
      );
    });
  });

  describe('HTTP 404 semantics', () => {
    it('renders empty state when no active goal exists on /me/current (404 without goalId)', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockRejectedValueOnce(
        new ApiError(404, 'No goal found')
      );

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      expect(root.findByProps({ testID: 'no-current-goal-card' })).toBeDefined();
      expect(root.findByProps({ testID: 'view-proposals-empty-button' })).toBeDefined();
      expect(root.findAllByProps({ testID: 'goal-error-card' }).length).toBe(0);
    });

    it('renders error screen when goalId is provided and returns 404', async () => {
      (fitnessGoalApi.getGoal as jest.Mock).mockRejectedValueOnce(
        new ApiError(404, 'Fitness goal not found')
      );

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen goalId="non-existent-goal-id" />);
      });
      const root = tree!.root;

      expect(root.findByProps({ testID: 'goal-error-card' })).toBeDefined();
      expect(root.findAllByProps({ testID: 'no-current-goal-card' }).length).toBe(0);
    });
  });

  it('navigates through tabs: targets, history, and proposals', async () => {
    (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<CurrentGoalScreen />);
    });
    const root = tree!.root;

    // Switch to Targets tab
    renderer.act(() => {
      root.findByProps({ testID: 'tab-targets-button' }).props.onPress();
    });
    expect(root.findByProps({ testID: 'target-card-tgt-1' })).toBeDefined();

    // Switch to History tab
    await renderer.act(async () => {
      root.findByProps({ testID: 'tab-history-button' }).props.onPress();
    });
    expect(fitnessGoalApi.getGoalVersions).toHaveBeenCalledWith('goal-123', 0, 20);

    // Switch to Proposals tab
    await renderer.act(async () => {
      root.findByProps({ testID: 'tab-proposals-button' }).props.onPress();
    });
    expect(fitnessGoalApi.getMyGoalProposals).toHaveBeenCalledWith({
      status: undefined,
      fitnessGoalId: 'goal-123',
      page: 0,
      size: 20,
    });
  });

  describe('Stale response guard with deferred promises', () => {
    it('discards stale response when an older slow request completes after a newer request', async () => {
      let resolveSlowRequest!: (val: FitnessGoal) => void;
      const slowPromise = new Promise<FitnessGoal>((resolve) => {
        resolveSlowRequest = resolve;
      });

      const fastGoal: FitnessGoal = {
        ...activeGoal,
        title: 'Newest Fast Goal Title',
      };

      const slowGoal: FitnessGoal = {
        ...activeGoal,
        title: 'Old Stale Goal Title',
      };

      (fitnessGoalApi.getGoal as jest.Mock).mockReturnValueOnce(slowPromise);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen goalId="goal-slow" />);
      });

      // Switch goalId to goal-fast which resolves quickly
      (fitnessGoalApi.getGoal as jest.Mock).mockResolvedValueOnce(fastGoal);
      await renderer.act(async () => {
        tree.update(<CurrentGoalScreen goalId="goal-fast" />);
      });

      const root = tree!.root;
      // Verify fast goal is now displayed
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Newest Fast Goal Title');

      // Now resolve the older slow request
      await renderer.act(async () => {
        resolveSlowRequest(slowGoal);
      });

      // The fast goal MUST remain displayed; the stale response must NOT overwrite it
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Newest Fast Goal Title');
    });

    it('invalidates goal A and shows loading skeleton while request B is pending, then displays only goal B upon completion', async () => {
      const goalA: FitnessGoal = {
        ...activeGoal,
        id: 'goal-A',
        title: 'Goal A Active Title',
        status: 'ACTIVE',
      };

      const goalB: FitnessGoal = {
        ...activeGoal,
        id: 'goal-B',
        title: 'Goal B Active Title',
        status: 'ACTIVE',
      };

      (fitnessGoalApi.getGoal as jest.Mock).mockResolvedValueOnce(goalA);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen goalId="goal-A" />);
      });
      const root = tree!.root;

      // 1. Load xong goal A
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Goal A Active Title');
      expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();

      // 2. Update component sang goalId B, trong đó request B đang pending
      let resolveGoalB!: (val: FitnessGoal) => void;
      const pendingPromiseB = new Promise<FitnessGoal>((resolve) => {
        resolveGoalB = resolve;
      });
      (fitnessGoalApi.getGoal as jest.Mock).mockReturnValueOnce(pendingPromiseB);

      await renderer.act(async () => {
        tree.update(<CurrentGoalScreen goalId="goal-B" />);
      });

      // 3. Trong thời gian B pending:
      // - Không hiển thị nội dung goal A
      expect(root.findAllByProps({ testID: 'goal-title' }).length).toBe(0);
      expect(root.findAllByProps({ testID: 'overview-goal-card' }).length).toBe(0);
      // - Không có nút pause/resume của A
      expect(root.findAllByProps({ testID: 'pause-goal-button' }).length).toBe(0);
      expect(root.findAllByProps({ testID: 'resume-goal-button' }).length).toBe(0);
      // - Có loading state phù hợp
      expect(root.findByProps({ testID: 'goal-screen-skeleton' })).toBeDefined();

      // 4. Khi B hoàn thành, chỉ goal B được hiển thị
      await renderer.act(async () => {
        resolveGoalB(goalB);
      });

      expect(root.findAllByProps({ testID: 'goal-screen-skeleton' }).length).toBe(0);
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Goal B Active Title');
      expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();
    });

    it('discards in-flight pause/resume mutation of goal A when route switches to goal B', async () => {
      const goalA: FitnessGoal = {
        ...activeGoal,
        id: 'goal-A',
        title: 'Goal A Active Title',
        status: 'ACTIVE',
      };

      const pausedGoalA: FitnessGoal = {
        ...goalA,
        status: 'PAUSED',
        statusReason: 'Pausing Goal A',
      };

      const goalB: FitnessGoal = {
        ...activeGoal,
        id: 'goal-B',
        title: 'Goal B Active Title',
        status: 'ACTIVE',
      };

      (fitnessGoalApi.getGoal as jest.Mock).mockResolvedValueOnce(goalA);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen goalId="goal-A" />);
      });
      const root = tree!.root;

      // Verify Goal A loaded
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Goal A Active Title');

      // Open pause modal for Goal A
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });

      // Enter reason and proceed to confirm
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Pausing Goal A');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      // Set up deferred pause mutation promise for A
      let resolvePauseA!: (val: FitnessGoal) => void;
      const pendingPausePromiseA = new Promise<FitnessGoal>((resolve) => {
        resolvePauseA = resolve;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingPausePromiseA);

      // Submit pause mutation for A
      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });
      expect(fitnessGoalApi.pauseGoal).toHaveBeenCalledWith('goal-A', 'Pausing Goal A');

      // Route transitions to Goal B while pause mutation of A is still pending
      (fitnessGoalApi.getGoal as jest.Mock).mockResolvedValueOnce(goalB);
      await renderer.act(async () => {
        tree.update(<CurrentGoalScreen goalId="goal-B" />);
      });

      // Goal B is now displayed
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Goal B Active Title');
      expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();

      // Now resolve the older pause mutation for Goal A
      await renderer.act(async () => {
        resolvePauseA(pausedGoalA);
      });

      // Response mutation của A không được ghi vào màn hình B
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Goal B Active Title');
      expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();
      expect(root.findAllByProps({ testID: 'resume-goal-button' }).length).toBe(0);
      expect(root.findAllByProps({ testID: 'paused-status-banner' }).length).toBe(0);

      // Không được làm thay đổi modal/state thuộc route B
      // For instance, opening pause modal on B operates cleanly on Goal B
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      expect(root.findByProps({ testID: 'pause-resume-reason-input' })).toBeDefined();
      expect(Alert.alert).not.toHaveBeenCalled();
    });

    it('does not display alert or fail route B when an in-flight mutation of goal A rejects after route change', async () => {
      const goalA: FitnessGoal = {
        ...activeGoal,
        id: 'goal-A',
        title: 'Goal A Active Title',
        status: 'ACTIVE',
      };

      const goalB: FitnessGoal = {
        ...activeGoal,
        id: 'goal-B',
        title: 'Goal B Active Title',
        status: 'ACTIVE',
      };

      (fitnessGoalApi.getGoal as jest.Mock).mockResolvedValueOnce(goalA);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen goalId="goal-A" />);
      });
      const root = tree!.root;

      // Open pause modal for Goal A
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Reason A');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      let rejectPauseA!: (err: any) => void;
      const pendingPausePromiseA = new Promise<FitnessGoal>((_, reject) => {
        rejectPauseA = reject;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingPausePromiseA);

      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });

      // Switch to Goal B
      (fitnessGoalApi.getGoal as jest.Mock).mockResolvedValueOnce(goalB);
      await renderer.act(async () => {
        tree.update(<CurrentGoalScreen goalId="goal-B" />);
      });

      // Reject Goal A's mutation
      await renderer.act(async () => {
        rejectPauseA(new ApiError(500, 'Server failure on Goal A'));
      });

      // Alert must NOT be shown on Route B, and Route B remains healthy
      expect(Alert.alert).not.toHaveBeenCalled();
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Goal B Active Title');
    });

    it('resets state when transitioning from current goal route to detail goal route', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe(activeGoal.title);

      const detailGoal: FitnessGoal = {
        ...activeGoal,
        id: 'goal-detail-1',
        title: 'Detail Goal Title',
      };
      (fitnessGoalApi.getGoal as jest.Mock).mockResolvedValueOnce(detailGoal);

      await renderer.act(async () => {
        tree.update(<CurrentGoalScreen goalId="goal-detail-1" />);
      });

      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Detail Goal Title');
    });

    it('does not invalidate in-flight mutation when load/refresh occurs on the same route (Test A)', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      // 1. Load goal A
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe(activeGoal.title);
      expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();

      // 2. Start pause mutation on A with deferred promise
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Recovering');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      let resolvePause!: (val: FitnessGoal) => void;
      const pendingPause = new Promise<FitnessGoal>((resolve) => {
        resolvePause = resolve;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingPause);

      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });
      expect(fitnessGoalApi.pauseGoal).toHaveBeenCalledWith('goal-123', 'Recovering');

      // 3. Trigger a load/refresh of goal A while mutation is pending
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);
      await renderer.act(async () => {
        const refreshControl = root.findByProps({ refreshing: false });
        refreshControl.props.onRefresh();
      });

      // 4. Complete the mutation
      await renderer.act(async () => {
        resolvePause(pausedGoal);
      });

      // 5. Verify mutation is processed and applied, not discarded by load sequence
      expect(root.findByProps({ testID: 'paused-status-banner' })).toBeDefined();
      expect(root.findByProps({ testID: 'resume-goal-button' })).toBeDefined();
      expect(root.findAllByProps({ testID: 'pause-goal-button' }).length).toBe(0);
      expect(root.findAllByProps({ testID: 'pause-resume-reason-input' }).length).toBe(0);
    });

    it('resets modal state when blurred and does not leave modal locked upon refocus (Test B)', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      // 1. Goal A is loaded
      expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();

      // 2. Open modal and submit mutation
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Recovering');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      let resolvePause!: (val: FitnessGoal) => void;
      const pendingPause = new Promise<FitnessGoal>((resolve) => {
        resolvePause = resolve;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingPause);

      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });

      // 3. Simulate blur
      renderer.act(() => {
        simulateScreenBlur();
      });

      // Modal is closed on blur
      expect(root.findAllByProps({ testID: 'pause-resume-reason-input' }).length).toBe(0);

      // 4. Mutation completes after blur
      await renderer.act(async () => {
        resolvePause(pausedGoal);
      });

      // 5. Simulate refocus on same route
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);
      await simulateScreenFocus();

      // 6. Verify:
      // - Modal remains closed
      expect(root.findAllByProps({ testID: 'pause-resume-reason-input' }).length).toBe(0);
      // - No Alert from mutation
      expect(Alert.alert).not.toHaveBeenCalled();
      // - Goal is displayed
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe(activeGoal.title);
      // - Modal is not locked in submitting state: can open freshly
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      const reasonInput = root.findByProps({ testID: 'pause-resume-reason-input' });
      expect(reasonInput).toBeDefined();
      expect(reasonInput.props.editable).toBe(true);
      expect(reasonInput.props.value).toBe('');
    });

    it('prevents old focus session mutation from affecting new focus session modal and state (Test C)', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      // 1. Start old mutation on session 1
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Old reason');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      let resolveOldPause!: (val: FitnessGoal) => void;
      const pendingOldPause = new Promise<FitnessGoal>((resolve) => {
        resolveOldPause = resolve;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingOldPause);

      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });

      // 2. Blur then refocus on same identity
      renderer.act(() => {
        simulateScreenBlur();
      });

      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);
      await simulateScreenFocus();

      // 3. In new session, open modal and submit a new mutation
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('New reason');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      let resolveNewPause!: (val: FitnessGoal) => void;
      const pendingNewPause = new Promise<FitnessGoal>((resolve) => {
        resolveNewPause = resolve;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingNewPause);

      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });

      // New mutation is now pending, new modal is open and submitting
      const confirmButton = root.findByProps({ testID: 'pause-resume-confirm-button' });
      expect(confirmButton.props.accessibilityState.busy).toBe(true);
      expect(confirmButton.props.disabled).toBe(true);

      // 4. Old mutation resolves
      await renderer.act(async () => {
        resolveOldPause(pausedGoal);
      });

      // Verify old mutation did NOT:
      // - close new modal (confirm button still present in modal)
      expect(root.findByProps({ testID: 'pause-resume-confirm-button' })).toBeDefined();
      // - reset submitting state of new mutation (still busy/disabled)
      const stillConfirmButton = root.findByProps({ testID: 'pause-resume-confirm-button' });
      expect(stillConfirmButton.props.accessibilityState.busy).toBe(true);
      expect(stillConfirmButton.props.disabled).toBe(true);
      // - overwrite goal
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe(activeGoal.title);
      // - show alert
      expect(Alert.alert).not.toHaveBeenCalled();

      // 5. Complete new mutation
      const finalPausedGoal: FitnessGoal = {
        ...pausedGoal,
        statusReason: 'New reason',
      };
      await renderer.act(async () => {
        resolveNewPause(finalPausedGoal);
      });

      // New mutation successfully completes and updates goal
      expect(root.findAllByProps({ testID: 'pause-resume-confirm-button' }).length).toBe(0);
      expect(root.findByProps({ testID: 'paused-status-banner' })).toBeDefined();
      expect(root.findByProps({ testID: 'resume-goal-button' })).toBeDefined();
    });

    it('prevents older refresh response from overwriting newer mutation result when refresh resolves after mutation', async () => {
      // 1. Load goal A in ACTIVE state
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe(activeGoal.title);
      expect(root.findByProps({ testID: 'pause-goal-button' })).toBeDefined();

      // 2. Start pull-to-refresh with deferred promise (pending)
      let resolveOldRefresh!: (val: FitnessGoal) => void;
      const pendingOldRefresh = new Promise<FitnessGoal>((resolve) => {
        resolveOldRefresh = resolve;
      });
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockReturnValueOnce(pendingOldRefresh);

      renderer.act(() => {
        const refreshControl = root.findByProps({ refreshing: false });
        refreshControl.props.onRefresh();
      });

      // 3. Start pause mutation and leave it pending
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Injury break');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      let resolvePause!: (val: FitnessGoal) => void;
      const pendingPause = new Promise<FitnessGoal>((resolve) => {
        resolvePause = resolve;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingPause);

      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });

      // 4. Resolve pause mutation FIRST with goal A in PAUSED state
      await renderer.act(async () => {
        resolvePause(pausedGoal);
      });

      // 5. Confirm UI displays paused state, Resume button, no Pause button, modal closed, spinner not stuck
      expect(root.findByProps({ testID: 'paused-status-banner' })).toBeDefined();
      expect(root.findByProps({ testID: 'resume-goal-button' })).toBeDefined();
      expect(root.findAllByProps({ testID: 'pause-goal-button' }).length).toBe(0);
      expect(root.findAllByProps({ testID: 'pause-resume-confirm-button' }).length).toBe(0);
      expect(root.findAllByProps({ testID: 'pause-resume-reason-input' }).length).toBe(0);
      expect(root.findByProps({ refreshing: false })).toBeDefined();

      // 6. Resolve older refresh request with snapshot A in ACTIVE state
      await renderer.act(async () => {
        resolveOldRefresh(activeGoal);
      });

      // 7. Confirm older refresh response did NOT overwrite PAUSED state
      expect(root.findByProps({ testID: 'paused-status-banner' })).toBeDefined();
      expect(root.findByProps({ testID: 'resume-goal-button' })).toBeDefined();
      expect(root.findAllByProps({ testID: 'pause-goal-button' }).length).toBe(0);
      expect(root.findByProps({ refreshing: false })).toBeDefined();
      expect(Alert.alert).not.toHaveBeenCalled();
    });

    it('allows a new refresh started after mutation success to update the UI', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      // Pause mutation completes
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Reason');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      (fitnessGoalApi.pauseGoal as jest.Mock).mockResolvedValueOnce(pausedGoal);
      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });
      expect(root.findByProps({ testID: 'paused-status-banner' })).toBeDefined();

      // New refresh started after mutation success
      const refreshedPausedGoal: FitnessGoal = {
        ...pausedGoal,
        title: 'Refreshed Paused Goal',
      };
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(refreshedPausedGoal);

      await renderer.act(async () => {
        root.findByProps({ refreshing: false }).props.onRefresh();
      });

      // New refresh response is applied
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Refreshed Paused Goal');
      expect(root.findByProps({ testID: 'paused-status-banner' })).toBeDefined();
    });

    it('does not invalidate in-flight load when a mutation fails', async () => {
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(activeGoal);

      let tree: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      const root = tree!.root;

      // Start refresh with pending promise
      let resolveRefresh!: (val: FitnessGoal) => void;
      const pendingRefresh = new Promise<FitnessGoal>((resolve) => {
        resolveRefresh = resolve;
      });
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockReturnValueOnce(pendingRefresh);

      renderer.act(() => {
        root.findByProps({ refreshing: false }).props.onRefresh();
      });

      // Start pause mutation
      renderer.act(() => {
        root.findByProps({ testID: 'pause-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-reason-input' }).props.onChangeText('Reason');
      });
      renderer.act(() => {
        root.findByProps({ testID: 'pause-resume-continue-button' }).props.onPress();
      });

      let rejectPause!: (err: any) => void;
      const pendingPause = new Promise<FitnessGoal>((_, reject) => {
        rejectPause = reject;
      });
      (fitnessGoalApi.pauseGoal as jest.Mock).mockReturnValueOnce(pendingPause);

      await renderer.act(async () => {
        root.findByProps({ testID: 'pause-resume-confirm-button' }).props.onPress();
      });

      // Mutation fails
      await renderer.act(async () => {
        rejectPause(
          new ApiError(500, 'Server error', {
            errorCode: 'INTERNAL_ERROR',
            message: 'Server error',
            timestamp: '',
            requestId: '',
            fieldErrors: [],
          })
        );
      });

      // Alert should be displayed for the failed mutation
      expect(Alert.alert).toHaveBeenCalledWith(
        'Status Update Failed',
        'Server error'
      );

      // Now resolve the ongoing refresh
      const refreshedActiveGoal: FitnessGoal = {
        ...activeGoal,
        title: 'Refreshed Active Goal',
      };
      await renderer.act(async () => {
        resolveRefresh(refreshedActiveGoal);
      });

      // The refresh was NOT invalidated by mutation failure, so it successfully updates UI
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Refreshed Active Goal');
      expect(root.findByProps({ refreshing: false })).toBeDefined();
    });
  });
});
