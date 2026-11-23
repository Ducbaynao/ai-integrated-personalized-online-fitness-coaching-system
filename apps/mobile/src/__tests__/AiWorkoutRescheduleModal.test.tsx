import React from 'react';
import renderer from 'react-test-renderer';
import { AiWorkoutRescheduleModal } from '@/features/workout-schedule/components/AiWorkoutRescheduleModal';
import { aiWorkoutRescheduleApi } from '@/services/aiWorkoutRescheduleApi';
import { WorkoutRescheduleApiResponse, AcceptRescheduleApiResponse } from '@/types/aiWorkoutReschedule';
import { ApiError } from '@/types/auth';

jest.mock('@/services/aiWorkoutRescheduleApi', () => {
  const actual = jest.requireActual('@/services/aiWorkoutRescheduleApi');
  return {
    ...actual,
    aiWorkoutRescheduleApi: {
      generate: jest.fn(),
      getDetail: jest.fn(),
      accept: jest.fn(),
      reject: jest.fn(),
    },
  };
});

describe('AiWorkoutRescheduleModal', () => {
  const mockAcceptResponse: AcceptRescheduleApiResponse = {
    recommendationId: 'rec-uuid-1',
    status: 'APPLIED',
    appliedBy: 'student-1',
    resultingEntityType: 'PLANNED_WORKOUT',
    resultingEntityId: 'occ-new-uuid',
    appliedAt: new Date().toISOString(),
    replayed: false,
  };

  const mockProposalResponse: WorkoutRescheduleApiResponse = {
    recommendationId: 'rec-uuid-1',
    runId: 'run-uuid-1',
    status: 'PENDING',
    decisionAuthority: 'STUDENT',
    title: 'Dời lịch buổi tập thân trên',
    explanation: 'Dời sang sáng ngày mai do lỡ buổi tập hôm nay.',
    expiresAt: new Date(Date.now() + 25 * 60 * 1000).toISOString(),
    createdAt: new Date().toISOString(),
    structuredPayload: {
      recommendationType: 'WORKOUT_RESCHEDULE_PROPOSAL',
      summary: 'Dời lịch buổi tập sang sáng hôm sau.',
      reasoning: ['Buổi tập trước chưa thực hiện', 'Đảm bảo đủ thời gian hồi phục nhóm cơ'],
      usedData: ['last_workout: 2026-11-20', 'mode: SELF_DIRECTED'],
      missingData: [],
      confidence: 0.95,
      reschedule: {
        occurrenceId: 'occ-1',
        sessionName: 'Buổi 1: Thân trên',
        previousPlannedStartAt: '2026-11-23T09:00:00Z',
        supervisionRequirement: 'SELF_PERFORMABLE',
        destination: {
          localStart: '2026-11-24T09:00',
          localEnd: '2026-11-24T10:00',
          timezone: 'Asia/Ho_Chi_Minh',
          startUtcOffset: '+07:00',
          endUtcOffset: '+07:00',
          proposedStartAt: '2026-11-24T02:00:00Z',
          proposedEndAt: '2026-11-24T03:00:00Z',
        },
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

  it('renders request form and allows generating proposal in SELF_DIRECTED', async () => {
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce(mockProposalResponse);

    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          occurrenceVersion={1}
          sessionName="Buổi 1: Thân trên"
          currentPlannedStartAt="2026-11-23T09:00:00Z"
          timezone="Asia/Ho_Chi_Minh"
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Verify source card renders
    const sourceCard = component!.root.findByProps({ testID: 'reschedule-request-source-card' });
    expect(sourceCard).toBeDefined();

    // Select afternoon time pref
    const afternoonBtn = component!.root.findByProps({ testID: 'time-pref-afternoon' });
    await renderer.act(async () => {
      afternoonBtn.props.onPress();
    });

    // Enter reason
    const reasonInput = component!.root.findByProps({ testID: 'reschedule-reason-input' });
    await renderer.act(async () => {
      reasonInput.props.onChangeText('Bận đột xuất vào buổi sáng');
    });

    // Click generate button
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    expect(aiWorkoutRescheduleApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        occurrenceId: 'occ-1',
        expectedOccurrenceVersion: 1,
        preferredTimeOfDay: 'AFTERNOON',
        reason: 'Bận đột xuất vào buổi sáng',
      }),
      expect.any(String)
    );

    // Verify review card is now visible
    const reviewHeader = component!.root.findByProps({ testID: 'reschedule-review-header' });
    expect(reviewHeader).toBeDefined();

    // In SELF_DIRECTED, student can accept
    const acceptBtn = component!.root.findByProps({ testID: 'accept-reschedule-button' });
    expect(acceptBtn).toBeDefined();
  });

  it('accepts proposal and displays receipt for authorized Student', async () => {
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce(mockProposalResponse);
    (aiWorkoutRescheduleApi.accept as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      status: 'APPLIED',
      appliedBy: 'student-1',
      resultingEntityType: 'WORKOUT_OCCURRENCE',
      resultingEntityId: 'occ-2-successor',
      appliedAt: '2026-11-23T11:45:00Z',
      replayed: false,
    });

    const onAppliedMock = jest.fn();
    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          occurrenceVersion={1}
          sessionName="Buổi 1: Thân trên"
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
          onRescheduleApplied={onAppliedMock}
        />
      );
    });

    await flushPromises();

    // Generate proposal
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Click Accept
    const acceptBtn = component!.root.findByProps({ testID: 'accept-reschedule-button' });
    await renderer.act(async () => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutRescheduleApi.accept).toHaveBeenCalledWith(
      'rec-uuid-1',
      expect.objectContaining({
        expectedOccurrenceVersion: 1,
      }),
      expect.any(String)
    );

    expect(onAppliedMock).toHaveBeenCalled();

    // Receipt is displayed
    const receipt = component!.root.findByProps({ testID: 'reschedule-applied-receipt' });
    expect(receipt).toBeDefined();
  });

  it('blocks Accept button for Student in HUMAN_COACH mode with clear explanation', async () => {
    const trainerAuthorityProposal = {
      ...mockProposalResponse,
      decisionAuthority: 'TRAINER' as const,
    };
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce(trainerAuthorityProposal);

    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          occurrenceVersion={1}
          sessionName="Buổi 1: Thân trên"
          coachingMode="HUMAN_COACH"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Generate proposal
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // In HUMAN_COACH mode, Student cannot accept
    expect(() => component!.root.findByProps({ testID: 'accept-reschedule-button' })).toThrow();

    // But reject is available
    const rejectToggle = component!.root.findByProps({ testID: 'toggle-reject-form-button' });
    expect(rejectToggle).toBeDefined();
  });

  it('allows Trainer to accept in HUMAN_COACH mode', async () => {
    const trainerAuthorityProposal = {
      ...mockProposalResponse,
      decisionAuthority: 'TRAINER' as const,
    };
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce(trainerAuthorityProposal);
    (aiWorkoutRescheduleApi.accept as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      status: 'APPLIED',
      appliedBy: 'trainer-1',
      resultingEntityType: 'WORKOUT_OCCURRENCE',
      resultingEntityId: 'occ-2-successor',
      appliedAt: '2026-11-23T11:45:00Z',
      replayed: false,
    });

    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          occurrenceVersion={1}
          sessionName="Buổi 1: Thân trên"
          coachingMode="HUMAN_COACH"
          isTrainer={true}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Generate proposal
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Trainer can accept
    const acceptBtn = component!.root.findByProps({ testID: 'accept-reschedule-button' });
    expect(acceptBtn).toBeDefined();

    await renderer.act(async () => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutRescheduleApi.accept).toHaveBeenCalled();
  });

  it('handles rejection flow with decision note', async () => {
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce(mockProposalResponse);
    (aiWorkoutRescheduleApi.reject as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-uuid-1',
      status: 'REJECTED',
      rejectedAt: '2026-11-23T11:45:00Z',
    });

    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Generate
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Toggle reject form
    const toggleReject = component!.root.findByProps({ testID: 'toggle-reject-form-button' });
    await renderer.act(async () => {
      toggleReject.props.onPress();
    });

    // Enter note
    const rejectInput = component!.root.findByProps({ testID: 'reject-note-input' });
    await renderer.act(async () => {
      rejectInput.props.onChangeText('Giờ này tôi phải đi làm');
    });

    // Confirm reject
    const confirmReject = component!.root.findByProps({ testID: 'confirm-reject-button' });
    await renderer.act(async () => {
      confirmReject.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutRescheduleApi.reject).toHaveBeenCalledWith('rec-uuid-1', {
      decisionNote: 'Giờ này tôi phải đi làm',
    });

    // Rejected receipt shown
    const rejectedReceipt = component!.root.findByProps({ testID: 'reschedule-rejected-receipt' });
    expect(rejectedReceipt).toBeDefined();
  });

  it('displays error and regenerates on 409 conflict', async () => {
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce(mockProposalResponse);
    const conflictErr = new ApiError(409, 'Version conflict', {
      errorCode: 'PLANNED_WORKOUT_VERSION_CONFLICT',
      message: 'Occurrence version mismatch',
      timestamp: '',
      requestId: '',
      fieldErrors: [],
    });
    (aiWorkoutRescheduleApi.accept as jest.Mock).mockRejectedValueOnce(conflictErr);

    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          occurrenceVersion={1}
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Generate
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // Accept -> fails with 409
    const acceptBtn = component!.root.findByProps({ testID: 'accept-reschedule-button' });
    await renderer.act(async () => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    // Error state is displayed
    const errorState = component!.root.findByProps({ testID: 'reschedule-error-state' });
    expect(errorState).toBeDefined();

    // Regenerate button is offered
    const regenBtn = component!.root.findByProps({ testID: 'regenerate-reschedule-button' });
    expect(regenBtn).toBeDefined();
  });

  it('displays appointment coordination guidance when linkedAppointmentId is present', async () => {
    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          occurrenceVersion={1}
          sessionName="Buổi 1: Thân trên"
          linkedAppointmentId="apt-uuid-1"
          linkedAppointmentVersion={2}
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Notice text in source card
    const alertTexts = component!.root.findAllByProps({ accessibilityRole: 'alert' });
    const hasAlert = alertTexts.some((t) =>
      typeof t.props.children === 'string' && t.props.children.includes('Buổi tập này có lịch hẹn kèm theo')
    );
    expect(hasAlert).toBe(true);
  });

  it('discards late responses when studentId changes while request is in flight', async () => {
    let resolveGenerate: (val: any) => void;
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveGenerate = resolve;
      })
    );

    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Click generate
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });

    // Now switch studentId prop before promise resolves
    await renderer.act(async () => {
      component!.update(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-2"
          occurrenceId="occ-1"
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    // Now resolve the late response
    await renderer.act(async () => {
      resolveGenerate!(mockProposalResponse);
    });
    await flushPromises();

    // Proposal review header should NOT be rendered for student-2
    expect(() => component!.root.findByProps({ testID: 'reschedule-review-header' })).toThrow();
  });

  it('re-uses the exact same commandKey when retrying accept after network timeout', async () => {
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce(mockProposalResponse);
    // First accept attempt fails with network error (status 0)
    (aiWorkoutRescheduleApi.accept as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'Failed to fetch'));
    // Second accept attempt succeeds
    (aiWorkoutRescheduleApi.accept as jest.Mock).mockResolvedValueOnce({
      ...mockAcceptResponse,
      replayed: true,
    });

    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Generate proposal
    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    // First accept attempt
    const acceptBtn = component!.root.findByProps({ testID: 'accept-reschedule-button' });
    await renderer.act(async () => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutRescheduleApi.accept).toHaveBeenCalledTimes(1);
    const firstCallCommandKey = (aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[0][2];
    expect(firstCallCommandKey).toBeTruthy();

    // Verify retry button label
    const retryBtn = component!.root.findByProps({ testID: 'accept-reschedule-button' });
    expect(retryBtn.props.label).toContain('Thử lại cùng lệnh');

    // Second accept attempt (retry)
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutRescheduleApi.accept).toHaveBeenCalledTimes(2);
    const secondCallCommandKey = (aiWorkoutRescheduleApi.accept as jest.Mock).mock.calls[1][2];
    // Must be the exact same commandKey for idempotency replay
    expect(secondCallCommandKey).toBe(firstCallCommandKey);

    // Verify receipt rendered with replayed note
    expect(component!.root.findByProps({ testID: 'reschedule-applied-receipt' })).toBeTruthy();
    expect(JSON.stringify(component!.toJSON())).toContain('phát lại an toàn');
  });

  it('renders heuristic slot disclaimer and dynamic remaining minutes', async () => {
    let component: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      component = renderer.create(
        <AiWorkoutRescheduleModal
          visible={true}
          studentId="student-1"
          occurrenceId="occ-1"
          coachingMode="SELF_DIRECTED"
          isTrainer={false}
          onClose={jest.fn()}
        />
      );
    });

    await flushPromises();

    // Check heuristic disclaimer in request form
    const requestOutput = JSON.stringify(component!.toJSON());
    expect(requestOutput).toContain('phỏng đoán (heuristic)');

    // Generate proposal with 25 minutes remaining
    const futureExpiry = new Date(Date.now() + 25 * 60 * 1000).toISOString();
    (aiWorkoutRescheduleApi.generate as jest.Mock).mockResolvedValueOnce({
      ...mockProposalResponse,
      expiresAt: futureExpiry,
    });

    const generateBtn = component!.root.findByProps({ testID: 'generate-reschedule-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    const reviewOutput = JSON.stringify(component!.toJSON());
    // Check remaining minutes
    expect(reviewOutput).toContain('còn khoảng');
    expect(reviewOutput).toMatch(/còn khoảng (25|26) phút/);
    // Check destination box heuristic disclaimer
    expect(reviewOutput).toContain('chưa bao gồm xác nhận lịch rảnh thực tế');
  });
});
