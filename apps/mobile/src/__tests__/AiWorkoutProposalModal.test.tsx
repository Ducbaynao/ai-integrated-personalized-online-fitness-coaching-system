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
      updateDraft: jest.fn(),
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

    // Enter notes
    const notesInput = tree!.root.findByProps({ testID: 'ai-proposal-notes-input' });
    renderer.act(() => {
      notesInput.props.onChangeText('No overhead presses');
    });

    // Click Generate
    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutProposalApi.generate).toHaveBeenCalledWith({
      studentId: 'student-1',
      preferences: {
        daysPerWeek: 4,
        note: 'No overhead presses',
      },
    });

    const detailView = tree!.root.findByProps({ testID: 'ai-proposal-detail-view' });
    expect(detailView).toBeDefined();
    expect(tree!.root.findByProps({ testID: 'ai-proposal-revision-badge' })).toBeDefined();
    expect(tree!.root.findByProps({ testID: 'start-edit-draft-button' })).toBeDefined();
  });

  it('accepts proposal successfully and calls onPlanApplied with expected revision', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);
    (aiWorkoutProposalApi.accept as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      status: 'APPLIED',
      appliedBy: 'student-1',
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

    expect(aiWorkoutProposalApi.accept).toHaveBeenCalledWith('rec-uuid-1', 'Approved and excited', undefined, 1);
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

  it('edits draft sessions and prescriptions, saves draft, increments revision and shows customized badge', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);

    const updatedPayload = JSON.parse(JSON.stringify(mockProposal.structuredPayload));
    updatedPayload.plan.name = 'Customized 1-Week Plan';
    updatedPayload.plan.sessions[0].name = 'Modified Session 1';
    updatedPayload.plan.sessions[0].prescriptions[0].targetSets = 4;

    (aiWorkoutProposalApi.updateDraft as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      revision: 2,
      isCustomized: true,
      status: 'PENDING',
      title: 'Customized 1-Week Plan',
      explanation: 'Personalized 3-day plan targeting hypertrophy and general fitness.',
      updatedAt: '2026-10-29T10:00:00Z',
      structuredPayload: updatedPayload,
      originalPayload: mockProposal.structuredPayload,
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

    // Generate proposal
    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Start editing
    const editBtn = tree!.root.findByProps({ testID: 'start-edit-draft-button' });
    renderer.act(() => {
      editBtn.props.onPress();
    });

    const editForm = tree!.root.findByProps({ testID: 'ai-proposal-edit-form' });
    expect(editForm).toBeDefined();

    // Modify plan name
    const planNameInput = tree!.root.findByProps({ testID: 'edit-plan-name-input' });
    renderer.act(() => {
      planNameInput.props.onChangeText('Customized 1-Week Plan');
    });

    // Modify session name
    const sessionNameInput = tree!.root.findByProps({ testID: 'edit-session-name-0' });
    renderer.act(() => {
      sessionNameInput.props.onChangeText('Modified Session 1');
    });

    // Modify sets
    const setsInput = tree!.root.findByProps({ testID: 'edit-prescription-sets-0-0' });
    renderer.act(() => {
      setsInput.props.onChangeText('4');
    });

    // Save draft
    const saveBtn = tree!.root.findByProps({ testID: 'save-draft-button' });
    renderer.act(() => {
      saveBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutProposalApi.updateDraft).toHaveBeenCalledWith(
      'rec-uuid-1',
      expect.objectContaining({
        expectedRevision: 1,
        title: 'Customized 1-Week Plan',
      })
    );

    // After saving, edit mode closed, revision v2 and customized badge displayed
    const revisionBadge = tree!.root.findByProps({ testID: 'ai-proposal-revision-badge' });
    expect(revisionBadge.props.children.props.children.join('')).toBe('v2');

    const provenanceBadge = tree!.root.findByProps({ testID: 'ai-proposal-provenance-badge' });
    expect(provenanceBadge.props.children.props.children).toBe('Đã tùy chỉnh');

    // Toggle comparison with original AI proposal
    const compareBtn = tree!.root.findByProps({ testID: 'toggle-original-comparison-button' });
    renderer.act(() => {
      compareBtn.props.onPress();
    });

    const originalCard = tree!.root.findByProps({ testID: 'original-ai-proposal-card' });
    expect(originalCard).toBeDefined();
  });

  it('handles revision conflict error on save draft', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);

    const errResp: ErrorResponse = {
      errorCode: 'AI_RUN_LIFECYCLE_CONFLICT',
      message: 'Revision conflict: expected revision 1 but current is 2',
      timestamp: '',
      requestId: '',
      fieldErrors: [],
    };
    (aiWorkoutProposalApi.updateDraft as jest.Mock).mockRejectedValueOnce(
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

    const editBtn = tree!.root.findByProps({ testID: 'start-edit-draft-button' });
    renderer.act(() => {
      editBtn.props.onPress();
    });

    const saveBtn = tree!.root.findByProps({ testID: 'save-draft-button' });
    renderer.act(() => {
      saveBtn.props.onPress();
    });
    await flushPromises();

    const errorBanner = tree!.root.findByProps({ testID: 'ai-proposal-error-banner' });
    expect(errorBanner).toBeDefined();
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

  it('hides edit draft button and disables accept button when student views trainer-managed proposal (HUMAN_COACH)', async () => {
    const trainerProposal: WorkoutProposalResponse = {
      ...mockProposal,
      decisionAuthority: 'TRAINER',
    };
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(trainerProposal);

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

    // In HUMAN_COACH, Student has no authority to edit draft
    expect(() => tree!.root.findByProps({ testID: 'start-edit-draft-button' })).toThrow();

    // Accept button should be disabled for unauthorized actor
    const acceptBtn = tree!.root.findByProps({ testID: 'accept-ai-proposal-button' });
    expect(acceptBtn.props.disabled).toBe(true);

    const rejectBtn = tree!.root.findByProps({ testID: 'reject-ai-proposal-button' });
    expect(rejectBtn.props.disabled).toBe(true);
  });
});
