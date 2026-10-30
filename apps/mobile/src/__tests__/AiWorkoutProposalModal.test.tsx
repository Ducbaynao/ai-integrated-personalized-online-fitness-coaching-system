import React from 'react';
import renderer from 'react-test-renderer';
import { AiWorkoutProposalModal } from '@/features/workout-plan/components/AiWorkoutProposalModal';
import { aiWorkoutProposalApi } from '@/services/aiWorkoutProposalApi';
import { WorkoutProposalResponse } from '@/types/aiWorkoutProposal';
import { ApiError, ErrorResponse } from '@/types/auth';

import { workoutPlanApi } from '@/services/workoutPlanApi';
import { studentProfileApi } from '@/services/studentProfileApi';

jest.mock('@/services/workoutPlanApi', () => ({
  workoutPlanApi: {
    current: jest.fn().mockResolvedValue(null),
  },
}));

jest.mock('@/services/studentProfileApi', () => ({
  studentProfileApi: {
    getMyStudentProfile: jest.fn().mockResolvedValue({
      userId: 'student-1',
      availableDaysPerWeek: 3,
      preferredSessionMinutes: 60,
      trainingExperienceLevel: 'BEGINNER',
    }),
  },
}));

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

    // Select 45 minutes
    const minBtn = tree!.root.findByProps({ testID: 'session-minutes-45' });
    renderer.act(() => {
      minBtn.props.onPress();
    });

    // Confirm no physical limitations
    const noLimBtn = tree!.root.findByProps({ testID: 'limitations-none-button' });
    renderer.act(() => {
      noLimBtn.props.onPress();
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

    expect(aiWorkoutProposalApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        studentId: 'student-1',
        preferences: expect.objectContaining({
          daysPerWeek: 4,
          preferredDaysPerWeek: 4,
          preferredSessionMinutes: 45,
          allowBodyweight: true,
          hasPhysicalLimitations: false,
          limitationsConfirmed: true,
          note: 'No overhead presses',
        }),
      })
    );

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

    expect(aiWorkoutProposalApi.accept).toHaveBeenCalledWith('rec-uuid-1', 'Approved and excited', undefined, 1, undefined, undefined, true);
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

  it('accepts proposal when active plan exists: renders Before/After card and passes active plan details', async () => {
    const mockActivePlan = {
      plan: {
        id: 'plan-active-1',
        name: 'Existing Active Plan',
        aggregateVersion: 2,
        status: 'ACTIVE',
      },
      currentVersion: {
        id: 'ver-active-1',
        versionNumber: 2,
      },
    };
    (workoutPlanApi.current as jest.Mock).mockResolvedValueOnce(mockActivePlan);
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);
    (aiWorkoutProposalApi.accept as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      status: 'APPLIED',
      appliedBy: 'student-1',
      resultingEntityType: 'WORKOUT_PLAN',
      resultingEntityId: 'plan-active-1',
      appliedAt: '2026-10-27T10:05:00Z',
      replayed: false,
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

    // Verify Before / After Card is rendered
    const impactCard = tree!.root.findByProps({ testID: 'ai-proposal-plan-impact-card' });
    expect(impactCard).toBeDefined();

    const acceptBtn = tree!.root.findByProps({ testID: 'accept-ai-proposal-button' });
    renderer.act(() => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutProposalApi.accept).toHaveBeenCalledWith(
      'rec-uuid-1',
      undefined,
      undefined,
      1,
      'plan-active-1',
      2,
      false
    );
  });
  it('displays backend field errors and readiness guidance banner on validation failure', async () => {
    const errorResponse: ErrorResponse = {
      errorCode: 'VALIDATION_FAILED',
      message: 'Validation failed',
      timestamp: '2026-10-30T10:00:00Z',
      requestId: 'req-val-1',
      fieldErrors: [
        { field: 'fitnessGoal', code: 'REQUIRED', message: 'Active fitness goal is required.' },
        { field: 'startDate', code: 'PAST_DATE', message: 'Workout plan start date cannot be in the past.' },
        { field: 'limitations', code: 'REQUIRED', message: 'Physical limitations or health status confirmation is required.' },
      ],
    };
    (aiWorkoutProposalApi.generate as jest.Mock).mockRejectedValueOnce(
      new ApiError(400, 'Validation failed', errorResponse)
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
    await flushPromises();

    // Click Generate without selecting limitations
    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Verify readiness guidance card is displayed
    const guidanceCard = tree!.root.findByProps({ testID: 'readiness-guidance-card' });
    expect(guidanceCard).toBeDefined();

    // Verify error banner
    const errorBanner = tree!.root.findByProps({ testID: 'ai-proposal-error-banner' });
    expect(errorBanner).toBeDefined();
  });

  it('handles physical limitations toggle and requires description when limitations exist', async () => {
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
    await flushPromises();

    // Select "Có hạn chế"
    const yesLimBtn = tree!.root.findByProps({ testID: 'limitations-yes-button' });
    renderer.act(() => {
      yesLimBtn.props.onPress();
    });

    // Enter limitations description
    const limInput = tree!.root.findByProps({ testID: 'ai-proposal-limitations-input' });
    renderer.act(() => {
      limInput.props.onChangeText('Recovering from mild knee strain');
    });

    // Generate
    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutProposalApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        studentId: 'student-1',
        preferences: expect.objectContaining({
          hasPhysicalLimitations: true,
          limitationsConfirmed: true,
          limitationsDescription: 'Recovering from mild knee strain',
        }),
      })
    );
  });

  it('does not prefill preferences from getMyStudentProfile when isTrainer is true', async () => {
    (studentProfileApi.getMyStudentProfile as jest.Mock).mockClear();

    renderer.act(() => {
      renderer.create(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-99"
          isTrainer={true}
          onClose={jest.fn()}
        />
      );
    });
    await flushPromises();

    // Trainer must not load their own student profile as the target student's profile
    expect(studentProfileApi.getMyStudentProfile).not.toHaveBeenCalled();
  });

  it('resets proposal and user inputs when studentId changes to prevent data leakage', async () => {
    (aiWorkoutProposalApi.generate as jest.Mock).mockResolvedValueOnce(mockProposal);

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-A"
          isTrainer={true}
          onClose={jest.fn()}
        />
      );
    });
    await flushPromises();

    // Fill notes for Student A
    const notesInput = tree!.root.findByProps({ testID: 'ai-proposal-notes-input' });
    renderer.act(() => {
      notesInput.props.onChangeText('Notes for Student A');
    });

    // Confirm no limitations for Student A
    const noLimBtn = tree!.root.findByProps({ testID: 'limitations-none-button' });
    renderer.act(() => {
      noLimBtn.props.onPress();
    });

    // Generate proposal for Student A
    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Verify proposal detail is rendered for Student A
    expect(tree!.root.findByProps({ testID: 'ai-proposal-detail-view' })).toBeDefined();

    // Now switch modal to Student B (studentId prop changes)
    renderer.act(() => {
      tree!.update(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-B"
          isTrainer={true}
          onClose={jest.fn()}
        />
      );
    });
    await flushPromises();

    // Verify proposal detail from Student A is WIPED and request form is reset for Student B
    expect(() => tree!.root.findByProps({ testID: 'ai-proposal-detail-view' })).toThrow();
    const newForm = tree!.root.findByProps({ testID: 'ai-proposal-request-form' });
    expect(newForm).toBeDefined();

    // Verify notes input for Student B is empty (not leaked from Student A)
    const newNotesInput = tree!.root.findByProps({ testID: 'ai-proposal-notes-input' });
    expect(newNotesInput.props.value).toBe('');
  });

  it('ignores stale in-flight response when studentId changes during request', async () => {
    let resolveGenerate!: (val: any) => void;
    (aiWorkoutProposalApi.generate as jest.Mock).mockImplementation(
      () =>
        new Promise((resolve) => {
          resolveGenerate = resolve;
        })
    );

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-A"
          isTrainer={true}
          onClose={jest.fn()}
        />
      );
    });
    await flushPromises();

    // Trigger generate for student-A (request is now in-flight)
    const generateBtn = tree!.root.findByProps({ testID: 'generate-ai-proposal-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    // While request for student-A is in-flight, switch to student-B
    renderer.act(() => {
      tree.update(
        <AiWorkoutProposalModal
          visible={true}
          studentId="student-B"
          isTrainer={true}
          onClose={jest.fn()}
        />
      );
    });

    // Now resolve the old request for student-A
    renderer.act(() => {
      resolveGenerate(mockProposal);
    });
    await flushPromises();

    // The proposal detail should NOT be rendered for student-B
    expect(() => tree!.root.findByProps({ testID: 'ai-proposal-detail-view' })).toThrow();
    expect(tree!.root.findByProps({ testID: 'ai-proposal-request-form' })).toBeDefined();
  });
});
