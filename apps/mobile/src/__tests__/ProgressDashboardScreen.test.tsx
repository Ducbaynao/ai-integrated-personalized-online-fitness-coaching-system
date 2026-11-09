import React from 'react';
import renderer from 'react-test-renderer';
import { ProgressDashboardScreen } from '@/features/progress/ProgressDashboardScreen';
import { measurementApi } from '@/services/measurementApi';
import { progressDashboardApi } from '@/services/progressDashboardApi';
import { ProgressDashboardSummary } from '@/types/measurement';

jest.mock('expo-router', () => ({
  router: {
    push: jest.fn(),
  },
}));

jest.mock('@/services/measurementApi', () => ({
  measurementApi: {
    listProgressPhotos: jest.fn(),
    uploadProgressPhoto: jest.fn(),
    deleteProgressPhoto: jest.fn(),
    getProgressPhotoFileUrl: jest.fn((id: string) => `http://mock-api/photos/${id}/file`),
  },
}));

jest.mock('@/services/progressDashboardApi', () => ({
  progressDashboardApi: {
    getSummary: jest.fn(),
  },
}));

describe('ProgressDashboardScreen', () => {
  const sampleSummaryCurrentGoal: ProgressDashboardSummary = {
    studentId: 'student-1',
    scope: 'CURRENT_GOAL',
    windowStart: '2026-10-01',
    windowEnd: '2026-11-08',
    goal: {
      id: 'g-1',
      title: 'Giảm mỡ tăng cơ quý 4',
      status: 'ACTIVE',
      startDate: '2026-10-01',
      targetDate: '2026-12-31',
      durationDays: 91,
      targets: [
        { metricCode: 'WEIGHT', targetNumericValue: 68.0, unitCode: 'KG' },
        { metricCode: 'BODY_FAT_PERCENTAGE', targetNumericValue: 15.0, unitCode: 'PERCENT' },
      ],
    },
    measurement: {
      availability: 'AVAILABLE',
      metricDeltas: [
        {
          metricCode: 'WEIGHT',
          baselineValue: 74.0,
          latestValue: 71.5,
          deltaValue: -2.5,
          unitCode: 'KG',
          trendDirection: 'DECREASED',
        },
      ],
    },
    workout: {
      availability: 'AVAILABLE',
      adherence: {
        scheduledDueCount: 16,
        completedDueCount: 15,
        completedOnTimeCount: 14,
        completedOffScheduleCount: 1,
        missedCount: 1,
        rawCompletionRate: 0.9375,
        onTimeAdherenceRate: 0.95,
      },
      volumeAndIntensity: {
        knownVolumeKg: 24500,
        workingSetsCompleted: 120,
        totalSetsCompleted: 140,
        totalRepsCompleted: 1200,
      },
    },
    continuity: {
      state: 'RECENT_ACTIVITY_OBSERVED',
      daysSinceLastWorkout: 1,
      rationale: 'Buổi tập gần nhất diễn ra hôm qua.',
    },
    referenceTime: '2026-11-08T08:00:00Z',
    asOf: '2026-11-08T08:00:00Z',
    compositeWatermark: 'wm-1234',
    historicalLimits: {
      hasHistoricalLimits: false,
    },
  };

  const samplePhotos = [
    {
      id: 'p-1',
      studentId: 'student-1',
      mediaFileId: 'mf-1',
      takenAt: '2026-11-01',
      viewType: 'FRONT' as const,
      weightAtTime: 72.0,
      weightUnitCode: 'kg',
      createdAt: '2026-11-01T08:00:00Z',
    },
  ];

  beforeEach(() => {
    jest.clearAllMocks();
  });

  const settle = async () => {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 30));
    });
  };

  it('renders loading state initially and then shows current goal dashboard', async () => {
    (progressDashboardApi.getSummary as jest.Mock).mockResolvedValueOnce(sampleSummaryCurrentGoal);
    (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValueOnce({
      content: samplePhotos,
      totalElements: 1,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressDashboardScreen studentId="student-1" />);
    });

    expect(tree.root.findByProps({ testID: 'progress-loading' })).toBeDefined();

    await settle();

    // Goal card
    const goalCard = tree.root.findByProps({ testID: 'current-goal-card' });
    expect(goalCard).toBeDefined();

    // Measurements card with delta row
    const deltaRow = tree.root.findByProps({ testID: 'delta-row-WEIGHT' });
    expect(deltaRow).toBeDefined();

    // Workout progress card
    const workoutCard = tree.root.findByProps({ testID: 'workout-progress-card' });
    expect(workoutCard).toBeDefined();

    // Continuity card
    const continuityCard = tree.root.findByProps({ testID: 'continuity-card' });
    expect(continuityCard).toBeDefined();

    // Photos gallery card
    const photosCard = tree.root.findByProps({ testID: 'photos-gallery-card' });
    expect(photosCard).toBeDefined();
  });

  it('switches between CURRENT_GOAL and LIFETIME scopes', async () => {
    (progressDashboardApi.getSummary as jest.Mock).mockResolvedValue(sampleSummaryCurrentGoal);
    (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValue({
      content: samplePhotos,
      totalElements: 1,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressDashboardScreen studentId="student-1" />);
    });

    await settle();

    const lifetimeTab = tree.root.findByProps({ testID: 'scope-tab-lifetime' });
    await renderer.act(async () => {
      lifetimeTab.props.onPress();
    });

    await settle();

    expect(progressDashboardApi.getSummary).toHaveBeenCalledWith(
      expect.objectContaining({
        studentId: 'student-1',
        scope: 'LIFETIME',
      })
    );
  });

  it('allows deleting a progress photo in student editable mode', async () => {
    (progressDashboardApi.getSummary as jest.Mock).mockResolvedValue(sampleSummaryCurrentGoal);
    (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValue({
      content: samplePhotos,
      totalElements: 1,
    });
    (measurementApi.deleteProgressPhoto as jest.Mock).mockResolvedValueOnce(undefined);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressDashboardScreen studentId="student-1" readOnly={false} />);
    });

    await settle();

    const deleteBtn = tree.root.findByProps({ testID: 'btn-delete-photo-p-1' });
    await renderer.act(async () => {
      deleteBtn.props.onPress();
    });

    expect(measurementApi.deleteProgressPhoto).toHaveBeenCalledWith('p-1');
  });

  it('hides delete and upload actions when readOnly is true', async () => {
    (progressDashboardApi.getSummary as jest.Mock).mockResolvedValue(sampleSummaryCurrentGoal);
    (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValue({
      content: samplePhotos,
      totalElements: 1,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressDashboardScreen studentId="student-1" readOnly={true} />);
    });

    await settle();

    const uploadBtns = tree.root.findAllByProps({ testID: 'btn-open-upload-photo' });
    expect(uploadBtns).toHaveLength(0);

    const deleteBtns = tree.root.findAllByProps({ testID: 'btn-delete-photo-p-1' });
    expect(deleteBtns).toHaveLength(0);
  });

  it('renders error state and retries successfully', async () => {
    (progressDashboardApi.getSummary as jest.Mock).mockRejectedValueOnce(new Error('500 Internal Error'));
    (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValue({ content: [], totalElements: 0 });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressDashboardScreen studentId="student-1" />);
    });

    await settle();

    expect(tree.root.findByProps({ testID: 'progress-error' })).toBeDefined();

    // Mock success for retry
    (progressDashboardApi.getSummary as jest.Mock).mockResolvedValueOnce(sampleSummaryCurrentGoal);
    const retryBtn = tree.root.findByProps({ testID: 'progress-retry-btn' });
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });

    await settle();

    expect(tree.root.findByProps({ testID: 'current-goal-card' })).toBeDefined();
  });
});
