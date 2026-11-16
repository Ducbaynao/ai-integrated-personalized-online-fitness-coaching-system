import React from 'react';
import { Text } from 'react-native';
import renderer from 'react-test-renderer';
import { AiWorkoutAdjustmentModal } from '@/features/workout-plan/components/AiWorkoutAdjustmentModal';
import { aiWorkoutAdjustmentApi } from '@/services/aiWorkoutAdjustmentApi';
import { exerciseApi } from '@/services/exerciseApi';
import { WorkoutAdjustmentApiResponse } from '@/types/aiWorkoutAdjustment';
import { ApiError } from '@/types/auth';

jest.mock('@/services/measurementApi', () => ({
  measurementApi: {
    getMetricDefinitions: jest.fn().mockResolvedValue([
      { defaultUnitCode: 'KG', defaultUnitId: 1, defaultDimension: 'MASS' },
      { defaultUnitCode: 'LB', defaultUnitId: 2, defaultDimension: 'MASS' },
    ]),
  },
}));

jest.mock('@/services/exerciseApi', () => ({
  exerciseApi: {
    getCatalog: jest.fn(),
    getDetail: jest.fn(),
    getFilterMetadata: jest.fn(),
  },
}));

jest.mock('@/services/aiWorkoutAdjustmentApi', () => {
  const actual = jest.requireActual('@/services/aiWorkoutAdjustmentApi');
  return {
    ...actual,
    aiWorkoutAdjustmentApi: {
      generate: jest.fn(),
      accept: jest.fn(),
      reject: jest.fn(),
      getRecommendation: jest.fn(),
      updateDraft: jest.fn(),
      shareDraft: jest.fn(),
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
            exerciseVariationId: '11111111-1111-4111-8111-111111111111',
            exerciseName: 'Barbell Back Squat',
            movementPattern: 'SQUAT',
            equipmentType: 'BARBELL',
          },
          after: {
            replacementVariationId: '22222222-2222-4222-8222-222222222222',
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

  const mockSetsRepsVolumeProposal: WorkoutAdjustmentApiResponse = {
    recommendationId: 'rec-vol-sets-1',
    runId: 'run-vol-2',
    status: 'PENDING',
    decisionAuthority: 'TRAINER',
    title: 'Tăng số hiệp và reps cho Barbell Squat',
    explanation: 'Tăng thể tích tập để kích thích phì đại cơ',
    expiresAt: '2026-11-20T12:00:00Z',
    createdAt: '2026-11-15T12:00:00Z',
    structuredPayload: {
      recommendationType: 'WORKOUT_ADJUSTMENT',
      summary: 'Tăng volume tập luyện',
      reasoning: ['RPE thuận lợi'],
      usedData: ['Workout logs'],
      missingData: [],
      limitations: [],
      confidence: 0.95,
      changes: [
        {
          changeId: 'chg-vol-sets',
          changeType: 'VOLUME_ADJUSTMENT',
          scope: 'ONE_MATERIALIZED_OCCURRENCE',
          targetRef: {
            scope: 'ONE_MATERIALIZED_OCCURRENCE',
            plannedWorkoutId: 'occ-123',
            plannedSessionExerciseId: 'pres-1',
          },
          before: {
            targetSets: 3,
            targetRepsMin: 8,
            targetRepsMax: 10,
          },
          after: {
            targetSets: 4,
            targetRepsMin: 10,
            targetRepsMax: 12,
            deltaSets: 1,
            deltaRepsMin: 2,
            deltaRepsMax: 2,
          },
          reason: 'Tăng volume cho buổi tập',
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

  describe('F03 Proposal Editing, Revision Diff & Draft Sharing', () => {
    it('trainer in HUMAN_COACH can edit draft, increment revision, view diff, and accept with updated revision', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-trainer-1',
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(trainerProposal);

      const updateResponse = {
        recommendationId: 'rec-trainer-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING' as const,
        title: 'Thay Squat bằng Lunges (Đã sửa)',
        explanation: 'Huấn luyện viên điều chỉnh sang Lunges',
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: {
          ...trainerProposal.structuredPayload,
          changes: [
            {
              ...trainerProposal.structuredPayload.changes[0],
              after: {
                ...(trainerProposal.structuredPayload.changes[0].after as any),
                replacementExerciseName: 'Dumbbell Walking Lunge',
              },
            },
          ],
        },
        originalPayload: trainerProposal.structuredPayload,
        diff: {
          hasChanges: true,
          changeType: 'EXERCISE_SUBSTITUTION',
          summary: 'Updated 1 field',
          fieldDiffs: [
            {
              field: 'replacementExerciseName',
              changeType: 'EXERCISE_SUBSTITUTION',
              originalValue: 'Goblet Squat',
              customizedValue: 'Dumbbell Walking Lunge',
              description: 'Replacement exercise name updated',
            },
          ],
        },
      };
      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce(updateResponse);

      const acceptResponse = {
        recommendationId: 'rec-trainer-1',
        status: 'APPLIED' as const,
        appliedBy: 'trainer-1',
        resultingEntityType: 'WORKOUT_OCCURRENCE' as const,
        resultingEntityId: 'occ-123',
        appliedAt: '2026-11-15T12:05:00Z',
        replayed: false,
      };
      (aiWorkoutAdjustmentApi.accept as jest.Mock).mockResolvedValueOnce(acceptResponse);

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

      // Generate proposal
      const generateBtn = tree.root.findByProps({ testID: 'adjustment-generate-button' });
      renderer.act(() => {
        generateBtn.props.onPress();
      });
      await flushPromises();

      // Proposal loaded in revision 1
      const revBadge = tree.root.findByProps({ testID: 'adjustment-revision-badge' });
      expect(revBadge.props.children.join('')).toContain('v1');
      expect(tree.root.findByProps({ testID: 'adjustment-private-badge' })).toBeDefined();

      // Click Edit Draft
      const editBtn = tree.root.findByProps({ testID: 'adjustment-start-edit-button' });
      renderer.act(() => {
        editBtn.props.onPress();
      });
      await flushPromises();

      // Edit form is open
      const titleInput = tree.root.findByProps({ testID: 'adjustment-edit-title-input' });
      renderer.act(() => {
        titleInput.props.onChangeText('Thay Squat bằng Lunges (Đã sửa)');
      });

      const replacementInput = tree.root.findByProps({ testID: 'adjustment-edit-replacement-name-input' });
      renderer.act(() => {
        replacementInput.props.onChangeText('Dumbbell Walking Lunge');
      });

      // Save Draft
      const saveBtn = tree.root.findByProps({ testID: 'adjustment-save-draft-button' });
      renderer.act(() => {
        saveBtn.props.onPress();
      });
      await flushPromises();

      // updateDraft was called with expectedRevision: 1
      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith('rec-trainer-1', {
        expectedRevision: 1,
        title: 'Thay Squat bằng Lunges (Đã sửa)',
        explanation: trainerProposal.explanation,
        structuredPayload: expect.objectContaining({
          changes: expect.arrayContaining([
            expect.objectContaining({
              after: expect.objectContaining({
                replacementExerciseName: 'Dumbbell Walking Lunge',
              }),
            }),
          ]),
        }),
      });

      // Now revision badge shows v2 (Đã tùy chỉnh)
      const updatedRevBadge = tree.root.findByProps({ testID: 'adjustment-revision-badge' });
      expect(updatedRevBadge.props.children.join('')).toContain('v2');
      expect(updatedRevBadge.props.children.join('')).toContain('Đã tùy chỉnh');

      // Structured diff card is displayed
      const diffCard = tree.root.findByProps({ testID: 'adjustment-diff-summary-card' });
      expect(diffCard).toBeDefined();
      const fieldDiff = tree.root.findByProps({ testID: 'field-diff-replacementExerciseName' });
      const diffValues = fieldDiff.findAllByType(Text).map((node) => node.props.children);
      expect(diffValues).toContain('Goblet Squat');
      expect(diffValues).toContain('Dumbbell Walking Lunge');

      // Accept the proposal -> MUST send expectedRevision: 2 (the updated revision!)
      const acceptBtn = tree.root.findByProps({ testID: 'adjustment-accept-button' });
      renderer.act(() => {
        acceptBtn.props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.accept).toHaveBeenCalledWith('rec-trainer-1', expect.objectContaining({
        expectedRevision: 2,
      }));
    });

    it('trainer can share draft with student and unshared changes warning triggers when editing further', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-share-1',
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(trainerProposal);

      const shareResponse = {
        recommendationId: 'rec-share-1',
        sharedWithStudent: true,
        sharedRevision: 1,
        sharedAt: '2026-11-15T12:00:00Z',
        sharedBy: 'trainer-1',
      };
      (aiWorkoutAdjustmentApi.shareDraft as jest.Mock).mockResolvedValueOnce(shareResponse);

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

      // Generate
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      // Initially private
      expect(tree.root.findByProps({ testID: 'adjustment-private-badge' })).toBeDefined();

      // Click Share with Student
      const shareBtn = tree.root.findByProps({ testID: 'adjustment-share-button' });
      renderer.act(() => {
        shareBtn.props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.shareDraft).toHaveBeenCalledWith('rec-share-1', {
        expectedRevision: 1,
      });

      // Shared badge now visible
      const sharedBadge = tree.root.findByProps({ testID: 'adjustment-shared-badge' });
      expect(sharedBadge.props.children).toBe('Đã chia sẻ (v1)');
      expect(tree.root.findByProps({ testID: 'adjustment-share-success' })).toBeDefined();

      // Trainer edits draft to revision 2
      const updateResponse = {
        recommendationId: 'rec-share-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING' as const,
        title: 'Chỉnh sửa v2',
        explanation: 'Trainer chỉnh sửa thêm',
        updatedAt: '2026-11-15T12:10:00Z',
        structuredPayload: trainerProposal.structuredPayload,
        originalPayload: trainerProposal.structuredPayload,
        diff: null,
      };
      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce(updateResponse);

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // Now revision is 2, but sharedRevision is still 1 -> Unshared changes warning must appear!
      const unsharedWarning = tree.root.findByProps({ testID: 'adjustment-unshared-warning' });
      expect(unsharedWarning).toBeDefined();
      const reshareBtn = tree.root.findByProps({ testID: 'adjustment-reshare-button' });
      expect(reshareBtn).toBeDefined();

      // Trainer re-shares v2
      const reShareResponse = {
        recommendationId: 'rec-share-1',
        sharedWithStudent: true,
        sharedRevision: 2,
        sharedAt: '2026-11-15T12:15:00Z',
        sharedBy: 'trainer-1',
      };
      (aiWorkoutAdjustmentApi.shareDraft as jest.Mock).mockResolvedValueOnce(reShareResponse);

      renderer.act(() => {
        reshareBtn.props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.shareDraft).toHaveBeenCalledWith('rec-share-1', {
        expectedRevision: 2,
      });

      // Warning disappears after re-sharing
      expect(() => tree.root.findByProps({ testID: 'adjustment-unshared-warning' })).toThrow();
    });

    it('student in HUMAN_COACH viewing shared proposal sees snapshot banner and has NO edit/share/accept/reject buttons', async () => {
      const sharedSnapshotProposal = {
        id: 'rec-shared-99',
        recommendationId: 'rec-shared-99',
        aiRunId: 'run-99',
        studentId: 'stu-1',
        coachingPeriodId: 'cp-1',
        recommendationType: 'WORKOUT_ADJUSTMENT_PROPOSAL',
        title: 'Thay đổi bài Squat (Bản chia sẻ v2)',
        explanation: 'Huấn luyện viên đã chia sẻ đề xuất này',
        status: 'PENDING' as const,
        decisionAuthority: 'TRAINER' as const,
        targetEntityType: 'WORKOUT_OCCURRENCE',
        targetEntityId: 'occ-123',
        decidedBy: null,
        decidedAt: null,
        decisionNote: null,
        expiresAt: '2026-11-20T00:00:00Z',
        revision: 2,
        isCustomized: true,
        lastModifiedBy: 'trainer-1',
        createdAt: '2026-11-15T00:00:00Z',
        updatedAt: '2026-11-15T01:00:00Z',
        structuredPayload: mockSubstitutionProposal.structuredPayload,
        originalPayload: mockSubstitutionProposal.structuredPayload,
        sharedWithStudent: true,
        sharedRevision: 2,
        sharedAt: '2026-11-15T01:00:00Z',
        sharedBy: 'trainer-1',
      };
      (aiWorkoutAdjustmentApi.getRecommendation as jest.Mock).mockResolvedValueOnce(sharedSnapshotProposal);

      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(
          <AiWorkoutAdjustmentModal
            visible={true}
            studentId="stu-1"
            isTrainer={false} // Student
            initialRecommendationId="rec-shared-99"
            occurrenceContext={mockOccurrenceContext}
            onClose={jest.fn()}
          />
        );
      });
      await flushPromises();

      // Automatically fetched recommendation detail
      expect(aiWorkoutAdjustmentApi.getRecommendation).toHaveBeenCalledWith('rec-shared-99');

      // Student sees shared banner and shared badge
      expect(tree.root.findByProps({ testID: 'adjustment-student-shared-banner' })).toBeDefined();
      const studentBadge = tree.root.findByProps({ testID: 'adjustment-student-shared-badge' });
      expect(studentBadge.props.children.join('')).toContain('v2');

      // CRITICAL INVARIANT: Student in HUMAN_COACH CANNOT mutate proposal!
      expect(() => tree.root.findByProps({ testID: 'adjustment-start-edit-button' })).toThrow();
      expect(() => tree.root.findByProps({ testID: 'adjustment-share-button' })).toThrow();
      expect(() => tree.root.findByProps({ testID: 'adjustment-reshare-button' })).toThrow();
      expect(() => tree.root.findByProps({ testID: 'adjustment-accept-button' })).toThrow();
      expect(() => tree.root.findByProps({ testID: 'adjustment-start-reject-button' })).toThrow();
    });

    it('student in SELF_DIRECTED can edit draft and accept, but cannot share', async () => {
      const selfDirectedProposal: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        decisionAuthority: 'STUDENT',
        recommendationId: 'rec-self-1',
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(selfDirectedProposal);

      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(
          <AiWorkoutAdjustmentModal
            visible={true}
            studentId="stu-1"
            isTrainer={false} // Student
            occurrenceContext={mockOccurrenceContext}
            onClose={jest.fn()}
          />
        );
      });

      // Generate
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      // Student in SELF_DIRECTED CAN edit and CAN accept/reject
      expect(tree.root.findByProps({ testID: 'adjustment-start-edit-button' })).toBeDefined();
      expect(tree.root.findByProps({ testID: 'adjustment-accept-button' })).toBeDefined();
      expect(tree.root.findByProps({ testID: 'adjustment-start-reject-button' })).toBeDefined();

      // Student in SELF_DIRECTED CANNOT share (no share button)
      expect(() => tree.root.findByProps({ testID: 'adjustment-share-button' })).toThrow();
      expect(() => tree.root.findByProps({ testID: 'adjustment-reshare-button' })).toThrow();
    });

    it('lookup by ID displays unshared error message when backend returns 403 AI_FORBIDDEN', async () => {
      const forbiddenError = new ApiError(
        403,
        'Access denied: Private trainer draft has not been shared with the student.',
        {
          errorCode: 'AI_FORBIDDEN',
          message: 'Access denied: Private trainer draft has not been shared with the student.',
          timestamp: '2026-11-15T00:00:00Z',
          requestId: 'req-unshared',
          fieldErrors: [],
        }
      );
      (aiWorkoutAdjustmentApi.getRecommendation as jest.Mock).mockRejectedValueOnce(forbiddenError);

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

      // Enter ID in lookup input
      const lookupInput = tree.root.findByProps({ testID: 'adjustment-lookup-input' });
      renderer.act(() => {
        lookupInput.props.onChangeText('rec-private-trainer-draft');
      });

      const lookupBtn = tree.root.findByProps({ testID: 'adjustment-lookup-button' });
      renderer.act(() => {
        lookupBtn.props.onPress();
      });
      await flushPromises();

      // Displays unshared explanation
      const errMsg = tree.root.findByProps({ testID: 'adjustment-error-message' });
      expect(errMsg).toBeDefined();
      const allTexts = errMsg.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('chưa được chia sẻ cho học viên');
    });

    it('revision conflict on edit displays conflict guidance with reload button', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-conflict-1',
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(trainerProposal);

      const conflictError = new ApiError(
        409,
        'Revision conflict: expected revision 1 but was 2',
        {
          errorCode: 'AI_RUN_LIFECYCLE_CONFLICT',
          message: 'Revision conflict: expected revision 1 but was 2',
          timestamp: '2026-11-15T00:00:00Z',
          requestId: 'req-conflict',
          fieldErrors: [],
        }
      );
      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockRejectedValueOnce(conflictError);

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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // Shows conflict guidance
      const conflictGuidance = tree.root.findByProps({ testID: 'adjustment-conflict-guidance' });
      expect(conflictGuidance).toBeDefined();
      const reloadBtn = tree.root.findByProps({ testID: 'adjustment-reload-conflict-button' });
      expect(reloadBtn).toBeDefined();

      // Clicking reload calls getRecommendation
      (aiWorkoutAdjustmentApi.getRecommendation as jest.Mock).mockResolvedValueOnce({
        ...trainerProposal,
        revision: 2,
      });
      renderer.act(() => {
        reloadBtn.props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.getRecommendation).toHaveBeenCalledWith('rec-conflict-1');
    });

    it('exercise substitution allows selecting variation from exercise catalog modal', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-cat-picker-1',
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(trainerProposal);

      (exerciseApi.getCatalog as jest.Mock).mockResolvedValueOnce({
        items: [
          {
            id: 'ex-split-squat-id',
            name: 'Bulgarian Split Squat',
            movementPattern: 'LUNGE',
            variationCount: 1,
            equipment: [{ code: 'DUMBBELL' }],
          },
        ],
        total: 1,
        page: 0,
        size: 15,
      });

      (exerciseApi.getDetail as jest.Mock).mockResolvedValueOnce({
        id: 'ex-split-squat-id',
        name: 'Bulgarian Split Squat',
        movementPattern: 'LUNGE',
        variations: [
          {
            id: '88888888-8888-4888-8888-888888888888',
            name: 'Bulgarian Split Squat (Dumbbell)',
            equipment: [{ code: 'DUMBBELL', name: 'Dumbbell' }],
          },
        ],
      });

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

      // Generate
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      // Open Edit Form
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Click Open Catalog Picker
      const openPickerBtn = tree.root.findByProps({ testID: 'adjustment-open-catalog-picker-button' });
      renderer.act(() => {
        openPickerBtn.props.onPress();
      });
      await flushPromises();

      // Catalog picker modal is open
      expect(tree.root.findByProps({ testID: 'adjustment-catalog-picker-modal' })).toBeDefined();

      // Search catalog
      const searchInput = tree.root.findByProps({ testID: 'adjustment-catalog-search-input' });
      renderer.act(() => {
        searchInput.props.onChangeText('Bulgarian');
      });
      (exerciseApi.getCatalog as jest.Mock).mockResolvedValueOnce({
        items: [
          {
            id: 'ex-split-squat-id',
            name: 'Bulgarian Split Squat',
            movementPattern: 'LUNGE',
            variationCount: 1,
            equipment: [{ code: 'DUMBBELL' }],
          },
        ],
        total: 1,
        page: 0,
        size: 15,
      });
      const searchBtn = tree.root.findByProps({ testID: 'adjustment-catalog-search-button' });
      renderer.act(() => {
        searchBtn.props.onPress();
      });
      await flushPromises();

      expect(exerciseApi.getCatalog).toHaveBeenCalledWith({ query: 'Bulgarian', size: 15 });

      // Click Exercise
      const exerciseItem = tree.root.findByProps({ testID: 'catalog-exercise-ex-split-squat-id' });
      renderer.act(() => {
        exerciseItem.props.onPress();
      });
      await flushPromises();

      expect(exerciseApi.getDetail).toHaveBeenCalledWith('ex-split-squat-id');

      // Click Variation
      const variationItem = tree.root.findByProps({ testID: 'catalog-variation-88888888-8888-4888-8888-888888888888' });
      renderer.act(() => {
        variationItem.props.onPress();
      });
      await flushPromises();

      // Form updated with selected variation info
      const idInput = tree.root.findByProps({ testID: 'adjustment-edit-replacement-id-input' });
      expect(idInput.props.value).toBe('88888888-8888-4888-8888-888888888888');
      const nameInput = tree.root.findByProps({ testID: 'adjustment-edit-replacement-name-input' });
      expect(nameInput.props.value).toBe('Bulgarian Split Squat (Dumbbell)');

      // Save Draft
      const saveBtn = tree.root.findByProps({ testID: 'adjustment-save-draft-button' });
      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-cat-picker-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: trainerProposal.title,
        explanation: trainerProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: trainerProposal.structuredPayload,
        originalPayload: trainerProposal.structuredPayload,
        diff: null,
      });
      renderer.act(() => {
        saveBtn.props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-cat-picker-1',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  replacementVariationId: '88888888-8888-4888-8888-888888888888',
                  replacementExerciseName: 'Bulgarian Split Squat (Dumbbell)',
                  movementPattern: 'LUNGE',
                  equipmentType: 'DUMBBELL',
                }),
              }),
            ]),
          }),
        })
      );
    });

    it('exercise substitution rejects setting replacement variation equal to before exerciseVariationId', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-same-var-1',
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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Enter same variation ID as before ('11111111-1111-4111-8111-111111111111')
      const idInput = tree.root.findByProps({ testID: 'adjustment-edit-replacement-id-input' });
      renderer.act(() => {
        idInput.props.onChangeText('11111111-1111-4111-8111-111111111111');
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // updateDraft was NOT called
      expect(aiWorkoutAdjustmentApi.updateDraft).not.toHaveBeenCalled();

      // Error message rendered
      const allTexts = tree.root.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('Biến thể thay thế không được trùng với biến thể bài tập gốc.');
    });

    it('volume adjustment validates integer constraints and rejects decimal inputs without silent truncation', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSetsRepsVolumeProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-vol-dec-1',
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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Enter decimals in integer fields
      const setsInput = tree.root.findByProps({ testID: 'adjustment-edit-sets-input' });
      renderer.act(() => {
        setsInput.props.onChangeText('3.5');
      });

      const repsMinInput = tree.root.findByProps({ testID: 'adjustment-edit-reps-min-input' });
      renderer.act(() => {
        repsMinInput.props.onChangeText('8.2');
      });

      const repsMaxInput = tree.root.findByProps({ testID: 'adjustment-edit-reps-max-input' });
      renderer.act(() => {
        repsMaxInput.props.onChangeText('10.8');
      });

      const durationInput = tree.root.findByProps({ testID: 'adjustment-edit-duration-input' });
      renderer.act(() => {
        durationInput.props.onChangeText('45.5');
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).not.toHaveBeenCalled();

      const allTexts = tree.root.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('Số hiệp phải là số nguyên!');
      expect(allTexts).toContain('Số reps tối thiểu phải là số nguyên!');
      expect(allTexts).toContain('Số reps tối đa phải là số nguyên!');
      expect(allTexts).toContain('Thời lượng giữ phải là số nguyên!');
    });

    it('volume adjustment auto-calculates deltas correctly and validates repsMax >= repsMin', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSetsRepsVolumeProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-vol-delta-1',
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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // First test invalid range: repsMax < repsMin
      const repsMinInput = tree.root.findByProps({ testID: 'adjustment-edit-reps-min-input' });
      renderer.act(() => {
        repsMinInput.props.onChangeText('12');
      });
      const repsMaxInput = tree.root.findByProps({ testID: 'adjustment-edit-reps-max-input' });
      renderer.act(() => {
        repsMaxInput.props.onChangeText('10');
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).not.toHaveBeenCalled();
      let allTexts = tree.root.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('Số reps tối đa không được nhỏ hơn số reps tối thiểu!');

      // Fix range and set valid sets/reps
      // Before was sets: 3, repsMin: 8, repsMax: 10
      // Update to sets: 5, repsMin: 10, repsMax: 14
      // Deltas should be deltaSets: 2, deltaRepsMin: 2, deltaRepsMax: 4
      const setsInput = tree.root.findByProps({ testID: 'adjustment-edit-sets-input' });
      renderer.act(() => {
        setsInput.props.onChangeText('5');
      });
      renderer.act(() => {
        repsMinInput.props.onChangeText('10');
      });
      renderer.act(() => {
        repsMaxInput.props.onChangeText('14');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-vol-delta-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: trainerProposal.title,
        explanation: trainerProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: trainerProposal.structuredPayload,
        originalPayload: trainerProposal.structuredPayload,
        diff: null,
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-vol-delta-1',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  targetSets: 5,
                  deltaSets: 2,
                  targetRepsMin: 10,
                  deltaRepsMin: 2,
                  targetRepsMax: 14,
                  deltaRepsMax: 4,
                }),
              }),
            ]),
          }),
        })
      );
    });

    it('load adjustment synchronizes direction, deltaLoad, deltaPercent, unit and unitId', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-load-sync-1',
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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Before targetLoad: 80 KG
      // Change to 85 KG with KG unit
      const loadInput = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput.props.onChangeText('85');
      });
      const unitInput = tree.root.findByProps({ testID: 'adjustment-edit-load-unit-input' });
      renderer.act(() => {
        unitInput.props.onChangeText('KG');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-load-sync-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: trainerProposal.title,
        explanation: trainerProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: trainerProposal.structuredPayload,
        originalPayload: trainerProposal.structuredPayload,
        diff: null,
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-load-sync-1',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  targetLoad: 85,
                  direction: 'INCREASE',
                  deltaLoad: 5,
                  deltaPercent: 6.3,
                  loadUnit: 'KG',
                  loadUnitId: 1,
                }),
              }),
            ]),
          }),
        })
      );
    });

    it('load adjustment rejects same load value with clear error', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-load-same-1',
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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Before targetLoad: 80 KG
      // Input 80 KG (same as before)
      const loadInput = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput.props.onChangeText('80');
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).not.toHaveBeenCalled();

      const allTexts = tree.root.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('Mức tạ mới phải khác mức tạ hiện tại (tăng hoặc giảm)!');
    });

    it('switching initialRecommendationId from A to B cleanly resets state and loads proposal B', async () => {
      const proposalA: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        recommendationId: 'rec-A',
        title: 'Đề xuất A',
      };
      const proposalB: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        recommendationId: 'rec-B',
        title: 'Đề xuất B',
      };

      (aiWorkoutAdjustmentApi.getRecommendation as jest.Mock).mockImplementation((id: string) => {
        if (id === 'rec-A') return Promise.resolve(proposalA);
        if (id === 'rec-B') return Promise.resolve(proposalB);
        return Promise.reject(new Error('Not found'));
      });

      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(
          <AiWorkoutAdjustmentModal
            visible={true}
            studentId="stu-1"
            initialRecommendationId="rec-A"
            occurrenceContext={mockOccurrenceContext}
            onClose={jest.fn()}
          />
        );
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.getRecommendation).toHaveBeenCalledWith('rec-A');
      let allTexts = tree.root.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('Đề xuất A');

      // Now switch initialRecommendationId to rec-B
      renderer.act(() => {
        tree.update(
          <AiWorkoutAdjustmentModal
            visible={true}
            studentId="stu-1"
            initialRecommendationId="rec-B"
            occurrenceContext={mockOccurrenceContext}
            onClose={jest.fn()}
          />
        );
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.getRecommendation).toHaveBeenCalledWith('rec-B');
      allTexts = tree.root.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('Đề xuất B');
    });

    it('blocks all competing actions while any operation is in flight', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockSubstitutionProposal,
        decisionAuthority: 'TRAINER',
        recommendationId: 'rec-inflight-1',
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

      // Generate
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      // Open Edit
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Create an unresolved promise for updateDraft to simulate in-flight request
      let resolveUpdate!: (val: any) => void;
      const hangingUpdatePromise = new Promise((resolve) => {
        resolveUpdate = resolve;
      });
      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockReturnValueOnce(hangingUpdatePromise);

      // Press save draft -> request begins
      const saveBtn = tree.root.findByProps({ testID: 'adjustment-save-draft-button' });
      renderer.act(() => {
        saveBtn.props.onPress();
      });

      // In-flight: save button should be disabled
      expect(saveBtn.props.disabled).toBe(true);

      // Duplicate press does not call updateDraft again
      renderer.act(() => {
        saveBtn.props.onPress();
      });
      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledTimes(1);

      // Resolve the hanging promise
      renderer.act(() => {
        resolveUpdate({
          recommendationId: 'rec-inflight-1',
          revision: 2,
          isCustomized: true,
          status: 'PENDING',
          title: 'Đã lưu',
          explanation: 'Lưu thành công',
          updatedAt: '2026-11-15T12:00:00Z',
          structuredPayload: trainerProposal.structuredPayload,
          originalPayload: trainerProposal.structuredPayload,
          diff: null,
        });
      });
      await flushPromises();

      // Once resolved, buttons become active again
      expect(tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.disabled).toBe(false);
      expect(tree.root.findByProps({ testID: 'adjustment-accept-button' }).props.disabled).toBe(false);
    });

    it('load adjustment preserves custom unit IDs other than 1 and 2 (e.g. loadUnitId: 41 for KG, 77 for LB)', async () => {
      const customUnitProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        recommendationId: 'rec-custom-unit-1',
        decisionAuthority: 'TRAINER',
        structuredPayload: {
          ...mockLoadProposal.structuredPayload,
          changes: [
            {
              ...mockLoadProposal.structuredPayload.changes[0],
              before: {
                targetLoad: 100,
                loadUnit: 'LB',
                loadUnitId: 77, // Custom unit ID from measurement_units catalog
              },
              after: {
                targetLoad: 105,
                loadUnit: 'LB',
                loadUnitId: 77,
                direction: 'INCREASE',
                deltaLoad: 5,
                deltaPercent: 5.0,
              },
            },
          ],
        },
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(customUnitProposal);

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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Enter 110 LB
      const loadInput = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput.props.onChangeText('110');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-custom-unit-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: customUnitProposal.title,
        explanation: customUnitProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: customUnitProposal.structuredPayload,
        originalPayload: customUnitProposal.structuredPayload,
        diff: null,
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // Verified loadUnitId is preserved as 77 (NOT hardcoded to 1 or 2!)
      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-custom-unit-1',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  targetLoad: 110,
                  direction: 'INCREASE',
                  deltaLoad: 10,
                  loadUnit: 'LB',
                  loadUnitId: 77,
                }),
              }),
            ]),
          }),
        })
      );
    });

    it('load adjustment normalizes alias LBS to canonical LB', async () => {
      const trainerProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        recommendationId: 'rec-alias-lbs-1',
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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Enter 85 and unit LBS (alias)
      const loadInput = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput.props.onChangeText('85');
      });
      const unitInput = tree.root.findByProps({ testID: 'adjustment-edit-load-unit-input' });
      renderer.act(() => {
        unitInput.props.onChangeText('LBS');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-alias-lbs-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: trainerProposal.title,
        explanation: trainerProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: trainerProposal.structuredPayload,
        originalPayload: trainerProposal.structuredPayload,
        diff: null,
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // Verified loadUnit is normalized to 'LB'
      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-alias-lbs-1',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  loadUnit: 'LB',
                }),
              }),
            ]),
          }),
        })
      );
    });

    it('load adjustment rejects calculating delta when baseline is unknown or bodyweight, but permits explicit zero', async () => {
      // 1. Unknown baseline (targetLoad: null)
      const bwProposal: WorkoutAdjustmentApiResponse = {
        ...mockBodyweightLoadProposal,
        decisionAuthority: 'TRAINER',
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(bwProposal);

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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // User tries to enter 50 KG when baseline load is null (unknown/bodyweight)
      const loadInput = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput.props.onChangeText('50');
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).not.toHaveBeenCalled();
      let allTexts = tree.root.findAllByType(Text).map((t: any) => t.props.children).flat(Infinity).join('');
      expect(allTexts).toContain('Không thể tính chênh lệch tải khi mức tạ ban đầu chưa xác định (unknown) hoặc là bài tự trọng.');

      // 2. Explicit zero baseline (targetLoad: 0, e.g. empty bar or explicit 0 KG)
      const zeroBaselineProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        recommendationId: 'rec-zero-base-1',
        decisionAuthority: 'TRAINER',
        structuredPayload: {
          ...mockLoadProposal.structuredPayload,
          changes: [
            {
              ...mockLoadProposal.structuredPayload.changes[0],
              before: {
                targetLoad: 0,
                loadUnit: 'KG',
                loadUnitId: 41,
              },
              after: {
                targetLoad: 10,
                loadUnit: 'KG',
                loadUnitId: 41,
                direction: 'INCREASE',
                deltaLoad: 10,
                deltaPercent: null,
              },
            },
          ],
        },
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(zeroBaselineProposal);

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
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      const loadInputZero = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInputZero.props.onChangeText('15');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-zero-base-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: zeroBaselineProposal.title,
        explanation: zeroBaselineProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: zeroBaselineProposal.structuredPayload,
        originalPayload: zeroBaselineProposal.structuredPayload,
        diff: null,
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-zero-base-1',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  targetLoad: 15,
                  deltaLoad: 15,
                  direction: 'INCREASE',
                  loadUnitId: 41,
                }),
              }),
            ]),
          }),
        })
      );
    });

    it('load adjustment handles unit conversion and unit-driven direction inversion correctly', async () => {
      // Case A: Before is 100 LB (which is 45.36 KG).
      // User inputs 50 KG.
      // Raw numbers: 50 < 100 (appears to decrease).
      // Physical conversion: 50 KG = 110.23 LB > 100 LB -> direction is INCREASE (+4.64 KG)!
      const lbBaselineProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        recommendationId: 'rec-invert-dir-1',
        decisionAuthority: 'TRAINER',
        structuredPayload: {
          ...mockLoadProposal.structuredPayload,
          changes: [
            {
              ...mockLoadProposal.structuredPayload.changes[0],
              before: {
                targetLoad: 100,
                loadUnit: 'LB',
                loadUnitId: 77,
              },
              after: {
                targetLoad: 105,
                loadUnit: 'LB',
                loadUnitId: 77,
                direction: 'INCREASE',
                deltaLoad: 5,
                deltaPercent: 5.0,
              },
            },
          ],
        },
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(lbBaselineProposal);

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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Enter 50 with unit KG
      const loadInput = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput.props.onChangeText('50');
      });
      const unitInput = tree.root.findByProps({ testID: 'adjustment-edit-load-unit-input' });
      renderer.act(() => {
        unitInput.props.onChangeText('KG');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-invert-dir-1',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: lbBaselineProposal.title,
        explanation: lbBaselineProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: lbBaselineProposal.structuredPayload,
        originalPayload: lbBaselineProposal.structuredPayload,
        diff: null,
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // Delta: 50 - 45.36 = +4.64 KG. Direction: INCREASE!
      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-invert-dir-1',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  targetLoad: 50,
                  direction: 'INCREASE',
                  deltaLoad: 4.64,
                  loadUnit: 'KG',
                }),
              }),
            ]),
          }),
        })
      );

      // Case B: Before is 50 KG (110.23 LB).
      // User inputs 100 LB.
      // Raw numbers: 100 > 50 (appears to increase).
      // Physical conversion: 100 LB = 45.36 KG < 50 KG -> direction is DECREASE (-10.23 LB)!
      const kgBaselineProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        recommendationId: 'rec-invert-dir-2',
        decisionAuthority: 'TRAINER',
        structuredPayload: {
          ...mockLoadProposal.structuredPayload,
          changes: [
            {
              ...mockLoadProposal.structuredPayload.changes[0],
              before: {
                targetLoad: 50,
                loadUnit: 'KG',
                loadUnitId: 41,
              },
              after: {
                targetLoad: 55,
                loadUnit: 'KG',
                loadUnitId: 41,
                direction: 'INCREASE',
                deltaLoad: 5,
                deltaPercent: 10.0,
              },
            },
          ],
        },
      };
      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(kgBaselineProposal);

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

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Enter 100 with unit LB
      const loadInput2 = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput2.props.onChangeText('100');
      });
      const unitInput2 = tree.root.findByProps({ testID: 'adjustment-edit-load-unit-input' });
      renderer.act(() => {
        unitInput2.props.onChangeText('LB');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-invert-dir-2',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: kgBaselineProposal.title,
        explanation: kgBaselineProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: kgBaselineProposal.structuredPayload,
        originalPayload: kgBaselineProposal.structuredPayload,
        diff: null,
      });

      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // Delta: 100 - 110.23 = -10.23 LB. Direction: DECREASE!
      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-invert-dir-2',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  targetLoad: 100,
                  direction: 'DECREASE',
                  deltaLoad: -10.23,
                  loadUnit: 'LB',
                }),
              }),
            ]),
          }),
        })
      );
    });

    it('changing load unit discards old unit ID and blocks saving when new unit cannot be resolved', async () => {
      const kgLoadProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        recommendationId: 'rec-unit-unresolved-1',
        decisionAuthority: 'TRAINER',
        title: 'Tăng mức tạ Squat',
        explanation: 'Thử thách cơ bắp',
        structuredPayload: {
          ...mockLoadProposal.structuredPayload,
          changes: [
            {
              changeId: 'chg-1',
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
                loadUnitId: 77,
              },
              after: {
                targetLoad: 85,
                loadUnit: 'KG',
                loadUnitId: 77,
                direction: 'INCREASE',
                deltaLoad: 5,
                deltaPercent: 6.25,
              },
              reason: 'Tăng mức tạ',
            },
          ],
        },
      };

      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(kgLoadProposal);

      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(
          <AiWorkoutAdjustmentModal
            visible={true}
            studentId="stu-1"
            isTrainer={true}
            occurrenceContext={mockOccurrenceContext}
            unitReferences={[{ code: 'KG', id: 77 }]}
            onClose={jest.fn()}
          />
        );
      });

      // Generate & open proposal
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      // Start edit
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Change load unit to an unknown / unresolvable unit 'POOD'
      const unitInput = tree.root.findByProps({ testID: 'adjustment-edit-load-unit-input' });
      renderer.act(() => {
        unitInput.props.onChangeText('POOD');
      });

      // Save draft should be blocked
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // updateDraft should NOT be called
      expect(aiWorkoutAdjustmentApi.updateDraft).not.toHaveBeenCalled();

      // Error message for load unit should be visible
      const unitError = tree.root.findByProps({ testID: 'adjustment-edit-load-unit-error' });
      expect(unitError).toBeDefined();
      expect(unitError.props.children).toContain('Không thể xác định mã định danh đơn vị');
    });

    it('changing load unit resolves matching unit ID from prescriptions/catalog and does not leak old unit ID', async () => {
      const kgLoadProposal: WorkoutAdjustmentApiResponse = {
        ...mockLoadProposal,
        recommendationId: 'rec-unit-resolve-2',
        decisionAuthority: 'TRAINER',
        title: 'Tăng mức tạ Squat',
        explanation: 'Thử thách cơ bắp',
        structuredPayload: {
          ...mockLoadProposal.structuredPayload,
          changes: [
            {
              changeId: 'chg-1',
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
                loadUnitId: 77,
              },
              after: {
                targetLoad: 85,
                loadUnit: 'KG',
                loadUnitId: 77,
                direction: 'INCREASE',
                deltaLoad: 5,
                deltaPercent: 6.25,
              },
              reason: 'Tăng mức tạ',
            },
          ],
        },
      };

      (aiWorkoutAdjustmentApi.generate as jest.Mock).mockResolvedValueOnce(kgLoadProposal);

      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(
          <AiWorkoutAdjustmentModal
            visible={true}
            studentId="stu-1"
            isTrainer={true}
            occurrenceContext={{
              ...mockOccurrenceContext,
              prescriptions: [
                {
                  id: 'pres-lb',
                  exerciseName: 'Dumbbell Bench Press',
                  targetLoad: 50,
                  loadUnit: 'LB',
                  loadUnitId: 41,
                  targetSets: 3,
                  targetRepsMin: 8,
                  targetRepsMax: 10,
                },
              ],
            }}
            unitReferences={[
              { code: 'KG', id: 77 },
              { code: 'LB', id: 41 },
            ]}
            onClose={jest.fn()}
          />
        );
      });

      // Generate & open proposal
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-generate-button' }).props.onPress();
      });
      await flushPromises();

      // Start edit
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-start-edit-button' }).props.onPress();
      });
      await flushPromises();

      // Change target load to 180 and unit to LB (alias LBS should normalize to LB and resolve ID 41, NOT 77)
      const loadInput = tree.root.findByProps({ testID: 'adjustment-edit-target-load-input' });
      renderer.act(() => {
        loadInput.props.onChangeText('180');
      });
      const unitInput = tree.root.findByProps({ testID: 'adjustment-edit-load-unit-input' });
      renderer.act(() => {
        unitInput.props.onChangeText('LBS');
      });

      (aiWorkoutAdjustmentApi.updateDraft as jest.Mock).mockResolvedValueOnce({
        recommendationId: 'rec-unit-resolve-2',
        revision: 2,
        isCustomized: true,
        status: 'PENDING',
        title: kgLoadProposal.title,
        explanation: kgLoadProposal.explanation,
        updatedAt: '2026-11-15T12:00:00Z',
        structuredPayload: kgLoadProposal.structuredPayload,
        originalPayload: kgLoadProposal.structuredPayload,
        diff: null,
      });

      // Save draft
      renderer.act(() => {
        tree.root.findByProps({ testID: 'adjustment-save-draft-button' }).props.onPress();
      });
      await flushPromises();

      // Expect updateDraft called with loadUnit: 'LB' and loadUnitId: 41 (NOT 77)
      expect(aiWorkoutAdjustmentApi.updateDraft).toHaveBeenCalledWith(
        'rec-unit-resolve-2',
        expect.objectContaining({
          structuredPayload: expect.objectContaining({
            changes: expect.arrayContaining([
              expect.objectContaining({
                after: expect.objectContaining({
                  targetLoad: 180,
                  loadUnit: 'LB',
                  loadUnitId: 41,
                }),
              }),
            ]),
          }),
        })
      );
    });
  });
});
