import React from 'react';
import { Text } from 'react-native';
import renderer from 'react-test-renderer';
import { AiWorkoutAdjustmentModal } from '@/features/workout-plan/components/AiWorkoutAdjustmentModal';
import { aiWorkoutAdjustmentApi } from '@/services/aiWorkoutAdjustmentApi';
import { WorkoutAdjustmentApiResponse } from '@/types/aiWorkoutAdjustment';
import { ApiError } from '@/types/auth';

jest.mock('@/services/aiWorkoutAdjustmentApi', () => {
  const actual = jest.requireActual('@/services/aiWorkoutAdjustmentApi');
  return {
    ...actual,
    aiWorkoutAdjustmentApi: {
      generate: jest.fn(),
      accept: jest.fn(),
      reject: jest.fn(),
      getRecommendation: jest.fn(),
    },
  };
});

describe('AiWorkoutAdjustmentModal', () => {
  const flushPromises = async () => {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50));
    });
  };

  const mockPrescriptions = [
    {
      id: 'pres-1',
      exerciseName: 'Barbell Back Squat',
      targetLoad: 80,
      loadUnit: 'KG',
      targetSets: 3,
      targetRepsMin: 8,
      targetRepsMax: 10,
    },
    {
      id: 'pres-2',
      exerciseName: 'Plank Hold',
      targetLoad: null,
      loadUnit: null,
      targetSets: 3,
      durationSeconds: 45,
    },
  ];

  const mockOccurrenceContext = {
    plannedWorkoutId: 'occ-123',
    occurrenceVersion: 2,
    sessionName: 'Buổi Chân & Bụng',
    prescriptions: mockPrescriptions,
  };

  const mockPlanContext = {
    planId: 'plan-456',
    planVersionNumber: 1,
    planAggregateVersion: 3,
    planName: 'Giáo án Tăng cơ 4 tuần',
    prescriptions: mockPrescriptions,
  };

  const mockSubstitutionProposal: WorkoutAdjustmentApiResponse = {
    recommendationId: 'rec-sub-1',
    runId: 'run-1',
    status: 'PENDING',
    decisionAuthority: 'STUDENT',
    title: 'Thay thế bài Barbell Squat bằng Goblet Squat',
    explanation: 'Thiếu thanh đòn barbell, chuyển sang tạ đơn để kích hoạt cơ đùi tương đương.',
    expiresAt: '2026-11-04T12:00:00Z',
    createdAt: '2026-11-03T12:00:00Z',
    structuredPayload: {
      recommendationType: 'WORKOUT_ADJUSTMENT',
      summary: 'Thay thế bài Squat do thiếu thiết bị',
      reasoning: ['Nhóm cơ tương thích', 'Thiết bị dumbbell khả dụng'],
      usedData: ['Lịch sử tập luyện gần nhất', 'Khai báo thiết bị'],
      missingData: [],
      limitations: [],
      confidence: 0.95,
      changes: [
        {
          changeId: 'chg-1',
          changeType: 'EXERCISE_SUBSTITUTION',
          scope: 'ONE_MATERIALIZED_OCCURRENCE',
          targetRef: {
            scope: 'ONE_MATERIALIZED_OCCURRENCE',
            plannedWorkoutId: 'occ-123',
            plannedSessionExerciseId: 'pres-1',
          },
          before: {
            exerciseVariationId: 'var-1',
            exerciseName: 'Barbell Back Squat',
            movementPattern: 'SQUAT',
            equipmentType: 'BARBELL',
          },
          after: {
            replacementVariationId: 'var-2',
            replacementExerciseName: 'Goblet Squat',
            movementPattern: 'SQUAT',
            equipmentType: 'DUMBBELL',
            substitutionCategory: 'EQUIPMENT_UNAVAILABLE',
          },
          reason: 'Máy bận hoặc thiếu thiết bị barbell',
          versionGuards: {
            expectedOccurrenceVersion: 2,
          },
        },
      ],
    },
  };

  const mockLoadProposal: WorkoutAdjustmentApiResponse = {
    recommendationId: 'rec-load-1',
    runId: 'run-2',
    status: 'PENDING',
    decisionAuthority: 'STUDENT',
    title: 'Tăng mức tạ Barbell Squat thêm 2.5 KG',
    explanation: 'Dữ liệu RPE tuần trước thấp (RPE 6), học viên đủ điều kiện tăng tải an toàn.',
    expiresAt: '2026-11-04T12:00:00Z',
    createdAt: '2026-11-03T12:00:00Z',
    structuredPayload: {
      recommendationType: 'WORKOUT_ADJUSTMENT',
      summary: 'Tăng mức tạ an toàn',
      reasoning: ['RPE đạt ngưỡng tiến triển'],
      usedData: ['Actual Workout RPE'],
      missingData: [],
      limitations: [],
      confidence: 0.92,
      changes: [
        {
          changeId: 'chg-2',
          changeType: 'LOAD_ADJUSTMENT',
          scope: 'ONE_MATERIALIZED_OCCURRENCE',
          targetRef: {
            scope: 'ONE_MATERIALIZED_OCCURRENCE',
            plannedWorkoutId: 'occ-123',
            plannedSessionExerciseId: 'pres-1',
          },
          before: {
            targetLoad: 80,
            loadUnit: 'KG',
            loadUnitId: 1,
          },
          after: {
            targetLoad: 82.5,
            loadUnit: 'KG',
            loadUnitId: 1,
            direction: 'INCREASE',
            deltaLoad: 2.5,
            deltaPercent: 3.125,
          },
          reason: 'Học viên hoàn thành nhẹ nhàng các hiệp trước',
          versionGuards: {
            expectedOccurrenceVersion: 2,
          },
        },
      ],
    },
  };

  const mockBodyweightLoadProposal: WorkoutAdjustmentApiResponse = {
    ...mockLoadProposal,
    recommendationId: 'rec-bw-1',
    structuredPayload: {
      ...mockLoadProposal.structuredPayload,
      changes: [
        {
          changeId: 'chg-bw',
          changeType: 'LOAD_ADJUSTMENT',
          scope: 'ONE_MATERIALIZED_OCCURRENCE',
          targetRef: {
            scope: 'ONE_MATERIALIZED_OCCURRENCE',
            plannedWorkoutId: 'occ-123',
            plannedSessionExerciseId: 'pres-2',
          },
          before: {
            targetLoad: null,
            loadUnit: null,
          },
          after: {
            targetLoad: null,
            loadUnit: null,
            direction: 'MAINTAIN',
            deltaLoad: null,
            deltaPercent: null,
          },
          reason: 'Bài tự trọng, giữ nguyên tải',
        },
      ],
    },
  };

  const mockVolumeProposal: WorkoutAdjustmentApiResponse = {
    recommendationId: 'rec-vol-1',
    runId: 'run-3',
    status: 'PENDING',
    decisionAuthority: 'STUDENT',
    title: 'Tăng thời lượng Plank thêm 15 giây',
    explanation: 'Khả năng giữ tư thế tốt, tăng thời gian isometric.',
    expiresAt: '2026-11-04T12:00:00Z',
    createdAt: '2026-11-03T12:00:00Z',
    structuredPayload: {
      recommendationType: 'WORKOUT_ADJUSTMENT',
      summary: 'Tăng thời lượng bài tập isometric',
      reasoning: ['Tiến triển thể lực'],
      usedData: ['Workout logs'],
      missingData: [],
      limitations: [],
      confidence: 0.9,
      changes: [
        {
          changeId: 'chg-3',
          changeType: 'VOLUME_ADJUSTMENT',
          scope: 'ONE_MATERIALIZED_OCCURRENCE',
          targetRef: {
            scope: 'ONE_MATERIALIZED_OCCURRENCE',
            plannedWorkoutId: 'occ-123',
            plannedSessionExerciseId: 'pres-2',
          },
          before: {
            targetSets: 3,
            durationSeconds: 45,
          },
          after: {
            targetSets: 3,
            durationSeconds: 60,
            deltaSets: 0,
            deltaDurationSeconds: 15,
          },
          reason: 'Tăng sức bền lõi cơ thể',
          versionGuards: {
            expectedOccurrenceVersion: 2,
          },
        },
      ],
    },
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders form initially and generates occurrence adjustment proposal', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockSubstitutionProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const modal = tree.root.findByProps({ testID: 'ai-workout-adjustment-modal' });
    expect(modal).toBeDefined();

    const requestForm = tree.root.findByProps({ testID: 'adjustment-request-form' });
    expect(requestForm).toBeDefined();

    const reasonInput = tree.root.findByProps({ testID: 'adjustment-reason-input' });
    renderer.act(() => {
      reasonInput.props.onChangeText('Máy squat bận');
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    expect(aiWorkoutAdjustmentApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        studentId: 'stu-1',
        scope: 'ONE_MATERIALIZED_OCCURRENCE',
        target: expect.objectContaining({
          scope: 'ONE_MATERIALIZED_OCCURRENCE',
          plannedWorkoutId: 'occ-123',
          plannedSessionExerciseId: 'pres-1',
        }),
        requestedChangeType: 'EXERCISE_SUBSTITUTION',
        userReason: 'Máy squat bận',
      })
    );

    const proposalView = tree.root.findByProps({ testID: 'adjustment-proposal-view' });
    expect(proposalView).toBeDefined();
  });

  it('renders Before/After diff card for EXERCISE_SUBSTITUTION', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockSubstitutionProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const diffCard = tree.root.findByProps({ testID: 'adjustment-diff-substitution' });
    expect(diffCard).toBeDefined();
  });

  it('renders Before/After diff card for LOAD_ADJUSTMENT with load units', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockLoadProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const loadTypeBtn = tree.root.findByProps({ testID: 'adjustment-change-type-load' });
    renderer.act(() => {
      loadTypeBtn.props.onPress();
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const diffCard = tree.root.findByProps({ testID: 'adjustment-diff-load' });
    expect(diffCard).toBeDefined();
  });

  it('renders Before/After diff card for bodyweight LOAD_ADJUSTMENT without converting to 0', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockBodyweightLoadProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const loadTypeBtn = tree.root.findByProps({ testID: 'adjustment-change-type-load' });
    renderer.act(() => {
      loadTypeBtn.props.onPress();
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const diffCard = tree.root.findByProps({ testID: 'adjustment-diff-load' });
    expect(diffCard).toBeDefined();
  });

  it('renders Before/After diff card for VOLUME_ADJUSTMENT (duration)', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockVolumeProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const volumeTypeBtn = tree.root.findByProps({ testID: 'adjustment-change-type-volume' });
    renderer.act(() => {
      volumeTypeBtn.props.onPress();
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const diffCard = tree.root.findByProps({ testID: 'adjustment-diff-volume' });
    expect(diffCard).toBeDefined();
  });

  it('accepts proposal successfully and calls onAdjustmentApplied with resulting entity', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockSubstitutionProposal);
    (aiWorkoutAdjustmentApi.accept as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-sub-1',
      status: 'APPLIED',
      appliedBy: 'stu-1',
      resultingEntityType: 'WORKOUT_OCCURRENCE',
      resultingEntityId: 'occ-123',
      appliedAt: '2026-11-03T12:05:00Z',
      replayed: false,
    });

    const onAppliedMock = jest.fn();

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
          onAdjustmentApplied={onAppliedMock}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const noteInput = tree.root.findByProps({ testID: 'adjustment-decision-note-input' });
    renderer.act(() => {
      noteInput.props.onChangeText('Duyệt thay bài tập');
    });

    const acceptBtn = tree.root.findByProps({ testID: 'adjustment-accept-button' });
    renderer.act(() => {
      acceptBtn.props.onPress();
    });

    await flushPromises();

    expect(aiWorkoutAdjustmentApi.accept).toHaveBeenCalledWith(
      'rec-sub-1',
      expect.objectContaining({
        decisionNote: 'Duyệt thay bài tập',
        expectedRevision: 1,
        expectedTargetVersion: 2,
      })
    );

    const appliedCard = tree.root.findByProps({ testID: 'adjustment-applied-card' });
    expect(appliedCard).toBeDefined();
    expect(onAppliedMock).toHaveBeenCalledWith({
      resultingEntityType: 'WORKOUT_OCCURRENCE',
      resultingEntityId: 'occ-123',
    });
  });

  it('rejects proposal and updates status to REJECTED without claiming workout mutation', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockSubstitutionProposal);
    (aiWorkoutAdjustmentApi.reject as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-sub-1',
      status: 'REJECTED',
      rejectedBy: 'stu-1',
      reason: 'Tôi muốn tập bài khác',
      rejectedAt: '2026-11-03T12:05:00Z',
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const startRejectBtn = tree.root.findByProps({ testID: 'adjustment-start-reject-button' });
    renderer.act(() => {
      startRejectBtn.props.onPress();
    });

    const reasonInput = tree.root.findByProps({ testID: 'adjustment-reject-reason-input' });
    renderer.act(() => {
      reasonInput.props.onChangeText('Tôi muốn tập bài khác');
    });

    const confirmRejectBtn = tree.root.findByProps({ testID: 'adjustment-reject-button' });
    renderer.act(() => {
      confirmRejectBtn.props.onPress();
    });

    await flushPromises();

    expect(aiWorkoutAdjustmentApi.reject).toHaveBeenCalledWith('rec-sub-1', {
      reason: 'Tôi muốn tập bài khác',
    });

    expect(() => tree.root.findByProps({ testID: 'adjustment-applied-card' })).toThrow();
  });

  it('enforces authority: student viewing trainer proposal has action buttons hidden with warning banner', async () => {
    const trainerProposal: WorkoutAdjustmentApiResponse = {
      ...mockSubstitutionProposal,
      decisionAuthority: 'TRAINER',
    };
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(trainerProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          isTrainer={false}
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const banner = tree.root.findByProps({ testID: 'adjustment-authority-banner' });
    expect(banner).toBeDefined();
    expect(() => tree.root.findByProps({ testID: 'adjustment-accept-button' })).toThrow();
  });

  it('enforces authority: trainer viewing trainer proposal can accept', async () => {
    const trainerProposal: WorkoutAdjustmentApiResponse = {
      ...mockSubstitutionProposal,
      decisionAuthority: 'TRAINER',
    };
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(trainerProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          isTrainer={true}
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const acceptBtn = tree.root.findByProps({ testID: 'adjustment-accept-button' });
    expect(acceptBtn).toBeDefined();
    expect(() => tree.root.findByProps({ testID: 'adjustment-authority-banner' })).toThrow();
  });

  it('handles stale version conflict (HTTP 409) gracefully and displays reload guidance without auto-apply', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockSubstitutionProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'PLANNED_WORKOUT_VERSION_CONFLICT',
      message: 'Occurrence version conflict',
      timestamp: '2026-11-03T12:00:00Z',
      requestId: 'req-c',
      fieldErrors: [],
    });
    (aiWorkoutAdjustmentApi.accept as jest.Mock).mockRejectedValueOnce(conflictError);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    const acceptBtn = tree.root.findByProps({ testID: 'adjustment-accept-button' });
    renderer.act(() => {
      acceptBtn.props.onPress();
    });

    await flushPromises();

    const guidance = tree.root.findByProps({ testID: 'adjustment-conflict-guidance' });
    expect(guidance).toBeDefined();
    expect(() => tree.root.findByProps({ testID: 'adjustment-applied-card' })).toThrow();
    expect(aiWorkoutAdjustmentApi.accept).toHaveBeenCalledTimes(1);
  });

  it('generates proposal with TEMPLATE_OR_FUTURE_OCCURRENCES scope', async () => {
    const templateProposal: WorkoutAdjustmentApiResponse = {
      ...mockSubstitutionProposal,
      structuredPayload: {
        ...mockSubstitutionProposal.structuredPayload,
        changes: [
          {
            ...mockSubstitutionProposal.structuredPayload.changes[0],
            scope: 'TEMPLATE_OR_FUTURE_OCCURRENCES',
            targetRef: {
              scope: 'TEMPLATE_OR_FUTURE_OCCURRENCES',
              planId: 'plan-456',
              planVersionNumber: 1,
              exercisePrescriptionId: 'pres-1',
            },
          },
        ],
      },
    };
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(templateProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          initialScope="TEMPLATE_OR_FUTURE_OCCURRENCES"
          planContext={mockPlanContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    await flushPromises();

    expect(aiWorkoutAdjustmentApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        scope: 'TEMPLATE_OR_FUTURE_OCCURRENCES',
        target: expect.objectContaining({
          scope: 'TEMPLATE_OR_FUTURE_OCCURRENCES',
          planId: 'plan-456',
          planVersionNumber: 1,
          exercisePrescriptionId: 'pres-1',
        }),
      })
    );
  });

  it('discards stale generate response when studentId or target changes during in-flight request', async () => {
    let resolveGenerate!: (val: any) => void;
    const generatePromise = new Promise((resolve) => {
      resolveGenerate = resolve;
    });
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockReturnValueOnce(generatePromise);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });

    // Student ID changes while request is in flight
    renderer.act(() => {
      tree.update(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-2"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    // Old response resolves
    await renderer.act(async () => {
      resolveGenerate(mockSubstitutionProposal);
      await flushPromises();
    });

    // The stale proposal should NOT be displayed in the session of stu-2
    expect(() => tree.root.findByProps({ testID: 'adjustment-proposal-view' })).toThrow();
    // The request form should still be visible for stu-2
    expect(tree.root.findByProps({ testID: 'adjustment-request-form' })).toBeDefined();
  });

  it('discards stale accept callback when modal is closed while accept is in flight', async () => {
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(mockSubstitutionProposal);
    let resolveAccept!: (val: any) => void;
    const acceptPromise = new Promise((resolve) => {
      resolveAccept = resolve;
    });
    (aiWorkoutAdjustmentApi.accept as jest.Mock).mockReturnValueOnce(acceptPromise);
    const onAppliedMock = jest.fn();

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
          onAdjustmentApplied={onAppliedMock}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    const acceptBtn = tree.root.findByProps({ testID: 'adjustment-accept-button' });
    renderer.act(() => {
      acceptBtn.props.onPress();
    });

    // Close the modal while accept is in flight
    renderer.act(() => {
      tree.update(
        <AiWorkoutAdjustmentModal
          visible={false}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
          onAdjustmentApplied={onAppliedMock}
        />
      );
    });

    // Old accept response resolves
    await renderer.act(async () => {
      resolveAccept({
        recommendationId: 'rec-sub-1',
        status: 'APPLIED',
        appliedBy: 'stu-1',
        resultingEntityType: 'WORKOUT_OCCURRENCE',
        resultingEntityId: 'occ-123',
        appliedAt: '2026-11-03T12:05:00Z',
        replayed: false,
      });
      await flushPromises();
    });

    // Stale callback must NOT be invoked
    expect(onAppliedMock).not.toHaveBeenCalled();
  });

  it('selects expectedTargetVersion according to scope: template uses plan aggregate version even when opened from schedule screen with different occurrence version', async () => {
    const templateProposalWithDifferentVersions: WorkoutAdjustmentApiResponse = {
      ...mockSubstitutionProposal,
      structuredPayload: {
        ...mockSubstitutionProposal.structuredPayload,
        changes: [
          {
            ...mockSubstitutionProposal.structuredPayload.changes[0],
            scope: 'TEMPLATE_OR_FUTURE_OCCURRENCES',
            versionGuards: {
              expectedPlanAggregateVersion: 3,
            },
          },
        ],
      },
    };
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(templateProposalWithDifferentVersions);
    (aiWorkoutAdjustmentApi.accept as jest.Mock).mockResolvedValueOnce({
      recommendationId: 'rec-sub-1',
      status: 'APPLIED',
      appliedBy: 'stu-1',
      resultingEntityType: 'WORKOUT_PLAN',
      resultingEntityId: 'plan-456',
      appliedAt: '2026-11-03T12:05:00Z',
      replayed: false,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          initialScope="TEMPLATE_OR_FUTURE_OCCURRENCES"
          occurrenceContext={{
            plannedWorkoutId: 'occ-777',
            occurrenceVersion: 7,
            prescriptions: mockPrescriptions,
          }}
          planContext={{
            planId: 'plan-456',
            planVersionNumber: 1,
            planAggregateVersion: 3,
            prescriptions: mockPrescriptions,
          }}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    const acceptBtn = tree.root.findByProps({ testID: 'adjustment-accept-button' });
    renderer.act(() => {
      acceptBtn.props.onPress();
    });
    await flushPromises();

    // Must pass expectedTargetVersion: 3 (plan aggregate version), NEVER 7 (occurrence version)
    expect(aiWorkoutAdjustmentApi.accept).toHaveBeenCalledWith(
      'rec-sub-1',
      expect.objectContaining({
        expectedTargetVersion: 3,
      })
    );
  });

  it('distinguishes clearly between unknown load, zero load, unknown unit, and bodyweight', async () => {
    // 1. Unknown load with KG unit: targetLoad is null, loadUnit is 'KG' -> displays "Chưa có dữ liệu (KG)"
    const unknownLoadWithUnitProposal: WorkoutAdjustmentApiResponse = {
      ...mockLoadProposal,
      title: 'Tập bài Bench Press',
      explanation: 'Điều chỉnh bài tập',
      structuredPayload: {
        ...mockLoadProposal.structuredPayload,
        changes: [
          {
            ...mockLoadProposal.structuredPayload.changes[0],
            before: {
              targetLoad: null,
              loadUnit: 'KG',
              loadUnitId: 1,
            },
            after: {
              targetLoad: 20,
              loadUnit: null,
              loadUnitId: null,
              direction: 'INCREASE',
              deltaLoad: null,
              deltaPercent: null,
            },
            reason: 'Tăng mức tải',
          },
        ],
      },
    };
    (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(unknownLoadWithUnitProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const loadTypeBtn = tree.root.findByProps({ testID: 'adjustment-change-type-load' });
    renderer.act(() => {
      loadTypeBtn.props.onPress();
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    const diffCard = tree.root.findByProps({ testID: 'adjustment-diff-load' });
    const textNodes = diffCard.findAllByType(Text).map((t: any) => t.props.children);
    const flattenedText = textNodes.flat(Infinity).join(' ');

    // Before has targetLoad: null, loadUnit: 'KG' -> must show "Chưa có dữ liệu (KG)", NOT Bodyweight!
    expect(flattenedText).toContain('Chưa có dữ liệu (KG)');
    expect(flattenedText).not.toContain('Trọng lượng cơ thể');
    // After has targetLoad: 20, loadUnit: null -> must show "20", NOT default to "20 KG"!
    expect(flattenedText).toContain('20');
    expect(flattenedText).not.toContain('20 KG');
  });

  it('anti-double-submit and retry identity: ignores duplicate taps while in-flight and preserves commandKey on retry', async () => {
    let rejectGen!: (err: any) => void;
    (aiWorkoutAdjustmentApi.generate as jest.Mock)
      .mockImplementationOnce(() => new Promise((_, rej) => { rejectGen = rej; }))
      .mockResolvedValueOnce(mockSubstitutionProposal);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <AiWorkoutAdjustmentModal
          visible={true}
          studentId="stu-1"
          occurrenceContext={mockOccurrenceContext}
          onClose={jest.fn()}
        />
      );
    });

    const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });

    // Double tap
    renderer.act(() => {
      generateBtn.props.onPress();
      generateBtn.props.onPress();
    });

    // Only 1 API call dispatched
    expect(aiWorkoutAdjustmentApi.generate).toHaveBeenCalledTimes(1);
    const firstCallKey = (aiWorkoutAdjustmentApi.generate as jest.Mock).mock.calls[0][0].commandKey;
    expect(firstCallKey).toBeDefined();

    // Fail the first call
    await renderer.act(async () => {
      rejectGen(new Error('Network error'));
      await flushPromises();
    });

    // Retry generate button
    renderer.act(() => {
      generateBtn.props.onPress();
    });
    await flushPromises();

    expect(aiWorkoutAdjustmentApi.generate).toHaveBeenCalledTimes(2);
    const retryCallKey = (aiWorkoutAdjustmentApi.generate as jest.Mock).mock.calls[1][0].commandKey;
    // Must preserve identical commandKey on retry
    expect(retryCallKey).toBe(firstCallKey);
  });
});
