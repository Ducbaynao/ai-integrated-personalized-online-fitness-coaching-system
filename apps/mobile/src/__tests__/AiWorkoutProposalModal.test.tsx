import React from 'react';
import renderer from 'react-test-renderer';
import { AiWorkoutProposalModal } from '@/features/workout-plan/components/AiWorkoutProposalModal';
import { aiWorkoutProposalApi } from '@/services/aiWorkoutProposalApi';
import { WorkoutProposalResponse } from '@/types/aiWorkoutProposal';
import { ApiError, ErrorResponse } from '@/types/auth';

jest.mock('@/services/aiWorkoutProposalApi', () => {
  const actual = jest.requireActual('@/services/aiWorkoutProposalApi');
  return {
    ...actual,
    aiWorkoutProposalApi: {
      generate: jest.fn(),
      accept: jest.fn(),
      reject: jest.fn(),
    },
  };
});

describe('AiWorkoutProposalModal', () => {
  const mockProposal: WorkoutProposalResponse = {
    recommendationId: 'rec-uuid-1',
    aiRunId: 'run-uuid-1',
    status: 'PENDING',
    decisionAuthority: 'STUDENT',
    title: '1-Week Full Body Starter',
    explanation: 'Personalized 3-day plan targeting hypertrophy and general fitness.',
    expiresAt: '2026-11-03T10:00:00Z',
    createdAt: '2026-10-27T10:00:00Z',
    structuredPayload: {
      recommendationType: 'WORKOUT_PLAN_PROPOSAL',
      summary: 'Personalized 3-day plan targeting hypertrophy and general fitness.',
      confidence: 0.92,
      reasoning: [
        'Program tailored for BEGINNER level.',
        'Distributed across 3 days for recovery.',
      ],
      usedData: ['coaching_mode: SELF_DIRECTED'],
      missingData: ['recent_1rm_test_results'],
      limitations: ['Proposal covers exactly 1 week.'],
      plan: {
        name: '1-Week Full Body Starter',
        durationWeeks: 1,
        sessions: [
          {
            weekNumber: 1,
            dayNumber: 1,
            sequenceNumber: 1,
            name: 'Session 1: Upper Focus',
            estimatedDurationMinutes: 45,
            prescriptions: [
              {
                exerciseVariationId: 'a0000000-0000-4000-8000-000000000001',
                sequenceNumber: 1,
                targetSets: 3,
                targetRepsMin: 8,
                targetRepsMax: 10,
                restSeconds: 90,
              },
            ],
          },
        ],
      },
    },
  };

  const flushPromises = async () => {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders request form initially and generates proposal', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-1"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    const requestForm = tree!.root.findByProps({ testID: 'ai-proposal-request-form' });
    expect(requestForm).toBeDefined();

    // Select 4 days
    const dayBtn = tree!.root.findByProps({ testID: 'days-per-week-4' });
    renderer.act(() => {
      dayBtn.props.onPress();
    });

    // Notes input
    const notesInput = tree!.root.findByProps({ testID: 'ai-proposal-notes-input' });
    renderer.act(() => {
      notesInput.props.onChangeText('Focus on shoulders');
    });

    // Click generate
    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    expect(aiWorkoutProposalApi.generate).toHaveBeenCalledWith({
      studentId: 'student-1',
      preferences: {
        daysPerWeek: 4,
        note: 'Focus on shoulders',
      },
    });

    const detailView = tree!.root.findByProps({ testID: 'ai-proposal-detail-view' });
    expect(detailView).toBeDefined();
    expect(tree!.root.findByProps({ testID: 'ai-proposal-summary-card' })).toBeDefined();
    expect(tree!.root.findByProps({ testID: 'ai-proposal-reasoning-card' })).toBeDefined();
    expect(tree!.root.findByProps({ testID: 'ai-proposal-missing-data-card' })).toBeDefined();
    expect(tree!.root.findByProps({ testID: 'ai-proposal-limitations-card' })).toBeDefined();
    expect(tree!.root.findByProps({ testID: 'ai-proposal-sessions-card' })).toBeDefined();
  });

  it('accepts proposal successfully and calls onPlanApplied', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);
    (aiWorkoutProposalApi.accept as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      status: 'APPLIED',
      resultingEntityType: 'WORKOUT_PLAN',
      resultingEntityId: 'new-plan-uuid-1',
      appliedAt: '2026-10-27T10:05:00Z',
      replayed: false,
    });

    const onPlanApplied = jest.fn();

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-1"
          isTrainer={false}
          onClose={jest.fn()}
          onPlanApplied={onPlanApplied}
        />
      );
    });

    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Type decision note
    const noteInput = tree!.root.findByProps({ testID: 'ai-decision-note-input' });
    renderer.act(() => {
      noteInput.props.onChangeText('Approved and excited');
    });

    // Click Accept
    const acceptBtn = tree!.root.findByProps({ testID: 'accept-ai-proposal-button' });
    renderer.act(() => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutProposalApi.accept).toHaveBeenCalledWith('rec-uuid-1', 'Approved and excited');
    expect(onPlanApplied).toHaveBeenCalledWith('new-plan-uuid-1');
    expect(tree!.root.findByProps({ testID: 'ai-proposal-applied-success' })).toBeDefined();
  });

  it('rejects proposal and updates status to REJECTED', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);
    (aiWorkoutProposalApi.reject as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      status: 'REJECTED',
      rejectedBy: 'student-1',
      rejectedAt: '2026-10-27T10:05:00Z',
      reason: 'Volume is too high',
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-1"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    const noteInput = tree!.root.findByProps({ testID: 'ai-decision-note-input' });
    renderer.act(() => {
      noteInput.props.onChangeText('Volume is too high');
    });

    const rejectBtn = tree!.root.findByProps({ testID: 'reject-ai-proposal-button' });
    renderer.act(() => {
      rejectBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutProposalApi.reject).toHaveBeenCalledWith('rec-uuid-1', 'Volume is too high');
    expect(tree!.root.findByProps({ testID: 'ai-proposal-rejected-notice' })).toBeDefined();
  });

  it('displays conflict error when active plan already exists', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);

    const errResp: ErrorResponse = {
      errorCode: 'ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS',
      message: 'Active plan exists',
      timestamp: '',
      requestId: '',
      fieldErrors: [],
    };
    (aiWorkoutProposalApi.accept as jest.Mock).mockRejectedValueOnce(
      new ApiError(409, 'Conflict', errResp)
    );

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-1"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    const acceptBtn = tree!.root.findByProps({ testID: 'accept-ai-proposal-button' });
    renderer.act(() => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    const errorBanner = tree!.root.findByProps({ testID: 'ai-proposal-error-banner' });
    expect(errorBanner).toBeDefined();
  });
});
