import React from 'react';
import { Alert } from 'react-native';
import renderer from 'react-test-renderer';
import { CurrentGoalScreen } from '@/features/goal/CurrentGoalScreen';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/types/auth';
import { FitnessGoal } from '@/types/goal';

jest.mock('expo-router', () => {
  const actualReact = jest.requireActual<typeof import('react')>('react');
  return {
    useRouter: () => ({
      push: jest.fn(),
      replace: jest.fn(),
      back: jest.fn(),
      canGoBack: () => true,
    }),
    useFocusEffect: (cb: any) => actualReact.useEffect(cb, [cb]),
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
  });
});
