import React from 'react';
import { Alert } from 'react-native';
import renderer from 'react-test-renderer';
import { TrainerStudentGoalScreen } from '@/features/coaching/TrainerStudentGoalScreen';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/types/auth';
import { FitnessGoal, GoalProposal } from '@/types/goal';

const mockBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({
    push: jest.fn(),
    back: mockBack,
    replace: jest.fn(),
  }),
}));

jest.mock('@/features/coaching/coachingQueries', () => ({
  useRelationshipDetail: () => ({
    isPending: false,
    data: {
      relationship: {
        id: 'rel-123',
        studentId: 'student-456',
        trainerId: 'trainer-789',
        status: 'ACTIVE',
        counterparty: { displayName: 'John Student' },
      },
    },
  }),
}));

jest.mock('@/services/fitnessGoalApi', () => ({
  fitnessGoalApi: {
    getStudentCurrentGoalForTrainer: jest.fn(),
    getStudentGoalProposalsForTrainer: jest.fn(),
    createGoalProposal: jest.fn(),
  },
}));

describe('TrainerStudentGoalScreen', () => {
  const activeStudentGoal: FitnessGoal = {
    id: 'goal-101',
    studentId: 'student-456',
    title: 'Hypertrophy Focus',
    status: 'ACTIVE',
    createdBy: 'student-456',
    activatedAt: '2026-09-01T10:00:00Z',
    pausedAt: null,
    completedAt: null,
    endedAt: null,
    statusReason: null,
    createdAt: '2026-09-01T10:00:00Z',
    updatedAt: '2026-09-01T10:00:00Z',
    currentVersion: {
      id: 'ver-1',
      versionNumber: 1,
      title: 'Hypertrophy Baseline',
      startDate: '2026-09-01',
      targetDate: '2026-11-30',
      durationDays: 90,
      effectiveFrom: '2026-09-01T10:00:00Z',
      effectiveUntil: null,
      resumeDate: null,
      changeReason: 'Initial setup',
      changeSummary: null,
      createdBy: 'student-456',
      sourceProposalId: null,
      createdAt: '2026-09-01T10:00:00Z',
      lockedAt: null,
      lockedBy: null,
      lockReason: null,
      isCurrent: true,
      objectives: [
        {
          id: 'obj-1',
          goalTypeId: 2,
          goalTypeCode: 'MUSCLE_GAIN',
          goalTypeName: 'Muscle Gain',
          priority: 'PRIMARY',
          sortOrder: 0,
          notes: null,
        },
      ],
      targets: [],
    },
  };

  const pendingProposal: GoalProposal = {
    id: 'prop-1',
    studentId: 'student-456',
    fitnessGoalId: 'goal-101',
    baseGoalVersionId: 'ver-1',
    source: 'TRAINER',
    createdBy: 'trainer-789',
    proposedTitle: 'Accelerated Hypertrophy',
    proposedStartDate: '2026-09-15',
    proposedTargetDate: '2026-11-15',
    proposedDurationDays: 60,
    reason: 'Rapid initial strength adaptation justifies higher volume phase',
    status: 'PENDING',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    createdAt: '2026-09-10T10:00:00Z',
    updatedAt: '2026-09-10T10:00:00Z',
    objectives: [],
    targets: [],
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  });

  it('renders student active goal details, objectives, and proposal history', async () => {
    (fitnessGoalApi.getStudentCurrentGoalForTrainer as jest.Mock).mockResolvedValueOnce(activeStudentGoal);
    (fitnessGoalApi.getStudentGoalProposalsForTrainer as jest.Mock).mockResolvedValueOnce({
      items: [pendingProposal],
      totalItems: 1,
    });

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerStudentGoalScreen relationshipId="rel-123" />);
    });
    const root = tree.root;

    expect(fitnessGoalApi.getStudentCurrentGoalForTrainer).toHaveBeenCalledWith('student-456');
    expect(root.findByProps({ testID: 'student-active-goal-card' })).toBeDefined();
    expect(root.findByProps({ testID: 'goal-status-badge' })).toBeDefined();
    expect(root.findByProps({ testID: 'propose-goal-change-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'proposal-card-prop-1' })).toBeDefined();
  });

  it('renders notice card when student has no active goal (404)', async () => {
    (fitnessGoalApi.getStudentCurrentGoalForTrainer as jest.Mock).mockRejectedValueOnce(
      new ApiError(404, 'No goal found', {
        errorCode: 'FITNESS_GOAL_NOT_FOUND',
        message: 'No active or paused fitness goal found for student',
        timestamp: '2026-11-08T00:00:00Z',
        requestId: 'req-1',
        fieldErrors: [],
      })
    );

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerStudentGoalScreen relationshipId="rel-123" />);
    });
    const root = tree.root;

    expect(root.findByProps({ testID: 'trainer-goal-error-card' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'propose-goal-change-button' }).length).toBe(0);
  });

  it('shows loading while retrying a failed request, then renders the recovered goal', async () => {
    (fitnessGoalApi.getStudentCurrentGoalForTrainer as jest.Mock).mockRejectedValueOnce(
      new Error('Network unavailable')
    );
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerStudentGoalScreen relationshipId="rel-123" />);
    });
    expect(tree.root.findByProps({ testID: 'trainer-goal-error-card' })).toBeDefined();

    let resolveGoal!: (goal: FitnessGoal) => void;
    (fitnessGoalApi.getStudentCurrentGoalForTrainer as jest.Mock).mockReturnValueOnce(
      new Promise<FitnessGoal>((resolve) => { resolveGoal = resolve; })
    );
    (fitnessGoalApi.getStudentGoalProposalsForTrainer as jest.Mock).mockResolvedValueOnce({ items: [] });
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'retry-trainer-goal-button' }).props.onPress();
    });
    expect(tree.root.findByProps({ testID: 'trainer-goal-loading' })).toBeDefined();
    expect(tree.root.findAllByProps({ testID: 'trainer-goal-error-card' })).toHaveLength(0);

    await renderer.act(async () => { resolveGoal(activeStudentGoal); });
    expect(tree.root.findByProps({ testID: 'student-active-goal-card' })).toBeDefined();
    expect(tree.root.findAllByProps({ testID: 'trainer-goal-loading' })).toHaveLength(0);
  });

  it('opens proposal modal, validates mandatory coaching reason, and submits proposal', async () => {
    (fitnessGoalApi.getStudentCurrentGoalForTrainer as jest.Mock).mockResolvedValue(activeStudentGoal);
    (fitnessGoalApi.getStudentGoalProposalsForTrainer as jest.Mock).mockResolvedValue({
      items: [],
      totalItems: 0,
    });

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerStudentGoalScreen relationshipId="rel-123" />);
    });
    const root = tree.root;

    // Press propose button
    const proposeBtn = root.findByProps({ testID: 'propose-goal-change-button' });
    await renderer.act(async () => {
      proposeBtn.props.onPress();
    });

    expect(root.findByProps({ testID: 'create-goal-proposal-modal' })).toBeDefined();

    // Submit with empty reason -> validation error banner appears
    await renderer.act(async () => {
      root.findByProps({ testID: 'goal-proposal-submit-button' }).props.onPress();
    });
    expect(root.findByProps({ testID: 'goal-proposal-error-banner' })).toBeDefined();
    expect(fitnessGoalApi.createGoalProposal).not.toHaveBeenCalled();

    // Input coaching reason
    renderer.act(() => {
      root.findByProps({ testID: 'goal-proposal-reason-input' }).props.onChangeText(
        'Increasing training block duration based on bench press progression'
      );
    });

    // Mock proposal API success
    (fitnessGoalApi.createGoalProposal as jest.Mock).mockResolvedValueOnce({
      id: 'prop-new',
      status: 'PENDING',
    });

    // Submit
    await renderer.act(async () => {
      root.findByProps({ testID: 'goal-proposal-submit-button' }).props.onPress();
    });

    expect(fitnessGoalApi.createGoalProposal).toHaveBeenCalledWith(
      'goal-101',
      expect.objectContaining({
        reason: 'Increasing training block duration based on bench press progression',
        objectives: expect.arrayContaining([
          expect.objectContaining({
            goalTypeCode: 'MUSCLE_GAIN',
            priority: 'PRIMARY',
          }),
        ]),
      })
    );
    expect(Alert.alert).toHaveBeenCalledWith('Thành công', expect.any(String));
  });

  it('handles 409 conflict when proposing goal change concurrently', async () => {
    (fitnessGoalApi.getStudentCurrentGoalForTrainer as jest.Mock).mockResolvedValue(activeStudentGoal);
    (fitnessGoalApi.getStudentGoalProposalsForTrainer as jest.Mock).mockResolvedValue({
      items: [],
      totalItems: 0,
    });

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerStudentGoalScreen relationshipId="rel-123" />);
    });
    const root = tree.root;

    await renderer.act(async () => {
      root.findByProps({ testID: 'propose-goal-change-button' }).props.onPress();
    });

    renderer.act(() => {
      root.findByProps({ testID: 'goal-proposal-reason-input' }).props.onChangeText('Coach reason');
    });

    // Mock 409 conflict
    (fitnessGoalApi.createGoalProposal as jest.Mock).mockRejectedValueOnce(
      new ApiError(409, 'Conflict', {
        errorCode: 'INVALID_LIFECYCLE_TRANSITION',
        message: 'Goal proposals can only be created for an ACTIVE fitness goal.',
        timestamp: '2026-11-08T00:00:00Z',
        requestId: 'req-409',
        fieldErrors: [],
      })
    );

    await renderer.act(async () => {
      root.findByProps({ testID: 'goal-proposal-submit-button' }).props.onPress();
    });

    expect(Alert.alert).toHaveBeenCalledWith(
      'Xung đột',
      'Goal proposals can only be created for an ACTIVE fitness goal.'
    );
  });

  it('navigates back when pressing back button', async () => {
    (fitnessGoalApi.getStudentCurrentGoalForTrainer as jest.Mock).mockResolvedValue(activeStudentGoal);
    (fitnessGoalApi.getStudentGoalProposalsForTrainer as jest.Mock).mockResolvedValue({
      items: [],
      totalItems: 0,
    });

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerStudentGoalScreen relationshipId="rel-123" />);
    });
    const root = tree.root;

    renderer.act(() => {
      root.findByProps({ testID: 'back-to-relationship-button' }).props.onPress();
    });

    expect(mockBack).toHaveBeenCalled();
  });
});
