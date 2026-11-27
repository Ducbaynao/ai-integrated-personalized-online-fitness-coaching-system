import React from 'react';
import renderer from 'react-test-renderer';
import { Text } from 'react-native';
import { ProgressDashboardScreen } from '@/features/progress/ProgressDashboardScreen';
import { UploadProgressPhotoModal } from '@/features/progress/components/UploadProgressPhotoModal';
import { invalidateAuthSession } from '@/services/apiClient';
import { measurementApi } from '@/services/measurementApi';
import { progressDashboardApi } from '@/services/progressDashboardApi';
import { ProgressDashboardSummary } from '@/types/measurement';

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({ user: { id: 'viewer' }, activeCapability: 'STUDENT' }),
}));

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

  it.each(['student change', 'account change', 'unmount'])('ignores an upload completion after %s', async change => {
    (progressDashboardApi.getSummary as jest.Mock).mockResolvedValue(sampleSummaryCurrentGoal);
    (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValue({ content: [], totalElements: 0 });
    let finish!: () => void;
    (measurementApi.uploadProgressPhoto as jest.Mock).mockReturnValue(new Promise<void>(resolve => { finish = resolve; }));
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => { tree = renderer.create(<ProgressDashboardScreen studentId="student-1" />); });
    renderer.act(() => { tree.root.findByProps({ testID: 'upload-photo-btn' }).props.onPress(); });
    let pending!: Promise<void>;
    renderer.act(() => {
      pending = tree.root.findByType(UploadProgressPhotoModal).props.onSubmit({ fileUri: 'file:///fixture.jpg', fileName: 'fixture.jpg', mimeType: 'image/jpeg' });
    });
    const guard = (measurementApi.uploadProgressPhoto as jest.Mock).mock.calls[0][1];
    expect(guard()).toBe(true);
    await renderer.act(async () => {
      if (change === 'unmount') tree.unmount();
      else {
        if (change === 'account change') invalidateAuthSession();
        tree.update(<ProgressDashboardScreen studentId={change === 'student change' ? 'student-2' : 'student-1'} />);
      }
    });
    expect(guard()).toBe(false);
    const loads = (measurementApi.listProgressPhotos as jest.Mock).mock.calls.length;
    await renderer.act(async () => { finish(); await pending; });
    expect(measurementApi.listProgressPhotos).toHaveBeenCalledTimes(loads);
    if (change !== 'unmount') {
      expect(tree.root.findAllByType(UploadProgressPhotoModal)).toHaveLength(0);
      renderer.act(() => tree.unmount());
    }
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

  describe('P7-016 Vietnamese copy and semantics', () => {
    const renderWith = async (summary: ProgressDashboardSummary, photos = samplePhotos) => {
      (progressDashboardApi.getSummary as jest.Mock).mockResolvedValue(summary);
      (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValue({ content: photos, totalElements: photos.length });
      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(<ProgressDashboardScreen studentId="student-1" />);
      });
      await settle();
      return tree;
    };
    const flat = (c: any): string =>
      Array.isArray(c) ? c.map(flat).join('') : typeof c === 'string' || typeof c === 'number' ? String(c) : '';
    const textIn = (node: renderer.ReactTestInstance) => node.findAllByType(Text).map((n) => flat(n.props.children)).join(' | ');
    const textOf = (tree: renderer.ReactTestRenderer) => textIn(tree.root);

    it('shows Vietnamese labels instead of raw goal status, metric, unit, continuity and photo codes', async () => {
      const tree = await renderWith({
        ...sampleSummaryCurrentGoal,
        continuity: {
          state: 'RECENT_ACTIVITY_OBSERVED',
          daysSinceLastWorkout: 1,
          rationale: 'Recent completed workout recorded in system on 2026-11-07; consistency unclassified',
        },
      });
      const text = textOf(tree);
      expect(text).toContain('Đang hoạt động');
      expect(text).toContain('Cân nặng');
      expect(text).toContain('Tình trạng lịch sử tập luyện');
      expect(text).toContain('Chính diện');
      for (const raw of ['ACTIVE', 'WEIGHT', 'BODY_FAT_PERCENTAGE', 'PERCENT', 'RECENT_ACTIVITY_OBSERVED', 'FRONT',
        'consistency unclassified', 'Lifetime', 'Progress Engine', '(F04)', '(Sets)', 'Tần suất']) {
        expect(text).not.toContain(raw);
      }
      // testIDs still carry the stable code for automation
      expect(tree.root.findByProps({ testID: 'delta-row-WEIGHT' })).toBeDefined();
    });

    it('does not show an unknown or missing trend as stable', async () => {
      const tree = await renderWith({
        ...sampleSummaryCurrentGoal,
        measurement: {
          availability: 'AVAILABLE',
          metricDeltas: [
            { metricCode: 'WEIGHT', baselineValue: 70, latestValue: 70, deltaValue: 0, unitCode: 'KG', trendDirection: 'STABLE' },
            { metricCode: 'WAIST_CIRCUMFERENCE', baselineValue: 80, latestValue: null, deltaValue: null, unitCode: 'CM' },
          ],
        },
      });
      const stableRow = textIn(tree.root.findByProps({ testID: 'delta-row-WEIGHT' }));
      const unknownRow = textIn(tree.root.findByProps({ testID: 'delta-row-WAIST_CIRCUMFERENCE' }));
      expect(stableRow).toContain('Không đổi');
      expect(stableRow).toMatch(/\| 0 kg/); // zero delta is a real value, not a dash
      expect(unknownRow).toContain('Chưa xác định xu hướng');
      expect(unknownRow).not.toMatch(/Không đổi|Ổn định/);
    });

    it('labels units by metric: bare BMI, "điểm" for other SCORE, neutral COUNT', async () => {
      const tree = await renderWith({
        ...sampleSummaryCurrentGoal,
        measurement: {
          availability: 'AVAILABLE',
          metricDeltas: [
            { metricCode: 'BMI', baselineValue: 25, latestValue: 24.5, deltaValue: -0.5, unitCode: 'SCORE', trendDirection: 'DECREASED' },
            { metricCode: 'VISCERAL_FAT', baselineValue: 10, latestValue: 9, deltaValue: -1, unitCode: 'SCORE', trendDirection: 'DECREASED' },
            { metricCode: 'RESTING_HEART_RATE', baselineValue: 70, latestValue: 62, deltaValue: -8, unitCode: 'COUNT', trendDirection: 'DECREASED' },
            { metricCode: 'CUSTOM_STEPS', baselineValue: 5, latestValue: 7, deltaValue: 2, unitCode: 'COUNT', trendDirection: 'INCREASED' },
          ],
        },
      });
      const bmi = textIn(tree.root.findByProps({ testID: 'delta-row-BMI' }));
      const visceral = textIn(tree.root.findByProps({ testID: 'delta-row-VISCERAL_FAT' }));
      const heart = textIn(tree.root.findByProps({ testID: 'delta-row-RESTING_HEART_RATE' }));
      const custom = textIn(tree.root.findByProps({ testID: 'delta-row-CUSTOM_STEPS' }));
      expect(bmi).toContain('Hiện tại: 24.5');
      expect(bmi).not.toMatch(/điểm|score|SCORE/);
      expect(visceral).toContain('Hiện tại: 9 điểm');
      expect(visceral).toContain('-1 điểm');
      for (const row of [heart, custom]) {
        expect(row).not.toMatch(/nhịp|phút|lần|count|COUNT/);
      }
      expect(heart).toContain('| -8 |');
      expect(custom).toContain('| +2 |');
    });

    it('distinguishes RESTRICTED from NO_DATA in measurement and workout sections', async () => {
      const restricted = await renderWith({
        ...sampleSummaryCurrentGoal,
        measurement: { availability: 'RESTRICTED' },
        workout: { availability: 'RESTRICTED' },
      });
      const restrictedText = textOf(restricted);
      expect(restrictedText).toContain('phạm vi quyền xem');
      expect(restrictedText).not.toContain('Chưa có dữ liệu số đo');
      expect(restricted.root.findAllByProps({ testID: 'workout-section-notice' }).length).toBeGreaterThan(0);

      const noData = await renderWith({
        ...sampleSummaryCurrentGoal,
        measurement: { availability: 'NO_DATA' },
        workout: { availability: 'NO_DATA' },
      });
      const noDataText = textOf(noData);
      expect(noDataText).toContain('Chưa có dữ liệu số đo');
      expect(noDataText).not.toContain('phạm vi quyền xem');
    });

    it('keeps zero, missing and partial workout values distinct', async () => {
      const tree = await renderWith({
        ...sampleSummaryCurrentGoal,
        workout: {
          availability: 'AVAILABLE',
          adherence: { ...sampleSummaryCurrentGoal.workout.adherence!, rawCompletionRate: 0 },
          volumeAndIntensity: {
            knownVolumeKg: null,
            workingSetsCompleted: 0,
            totalSetsCompleted: 0,
            totalRepsCompleted: 0,
          },
        },
      });
      const card = textIn(tree.root.findByProps({ testID: 'workout-progress-card' }));
      expect(card).toContain('0% | Tỷ lệ hoàn thành');
      expect(card).toContain('0 | Số hiệp chính');
      expect(card).toContain('— | Tổng khối lượng');
    });

    it('explains restricted continuity neutrally and handles an unknown state without the raw code', async () => {
      const restricted = await renderWith({
        ...sampleSummaryCurrentGoal,
        continuity: { state: 'RESTRICTED_HISTORY', rationale: 'Actor lacks WORKOUT_HISTORY permission' },
      });
      const explanation = restricted.root.findByProps({ testID: 'continuity-explanation' }).props.children;
      expect(explanation).toContain('chưa đủ để đánh giá');
      expect(textOf(restricted)).not.toContain('WORKOUT_HISTORY');

      const unknown = await renderWith({
        ...sampleSummaryCurrentGoal,
        continuity: { state: 'FUTURE_STATE' as any },
      });
      expect(unknown.root.findByProps({ testID: 'continuity-state-label' }).props.children).toBe('Chưa xác định trạng thái');
      expect(textOf(unknown)).not.toContain('FUTURE_STATE');
    });

    it('P7-016: shows photo takenAt as a labelled UTC instant, never raw ISO or a guessed local time', async () => {
      const photos = [
        { ...samplePhotos[0], id: 'p-utc', takenAt: '2026-11-01T23:30:00Z' },
        { ...samplePhotos[0], id: 'p-date-only', takenAt: '2026-11-01' },
        { ...samplePhotos[0], id: 'p-missing', takenAt: '' },
      ];
      const tree = await renderWith(sampleSummaryCurrentGoal, photos);
      const takenAt = (id: string) => {
        const node = tree.root.findByProps({ testID: `photo-taken-at-${id}` });
        const c = node.props.children;
        return Array.isArray(c) ? c.join('') : String(c);
      };
      expect(takenAt('p-utc')).toMatch(/23:30.*· UTC$/);
      expect(takenAt('p-utc')).not.toContain('06:30'); // not shifted to Asia/Ho_Chi_Minh
      expect(takenAt('p-utc')).not.toContain('T23:30:00Z');
      expect(takenAt('p-date-only')).toBe('Chưa rõ thời điểm chụp');
      expect(takenAt('p-missing')).toBe('Chưa rõ thời điểm chụp');
    });

    it('does not render a raw error message on load failure', async () => {
      (progressDashboardApi.getSummary as jest.Mock).mockRejectedValueOnce(new Error('500 Internal Error at ProgressEngineService'));
      (measurementApi.listProgressPhotos as jest.Mock).mockResolvedValue({ content: [], totalElements: 0 });
      let tree!: renderer.ReactTestRenderer;
      renderer.act(() => {
        tree = renderer.create(<ProgressDashboardScreen studentId="student-1" />);
      });
      await settle();
      const text = textOf(tree);
      expect(text).toContain('Không thể tải bảng tiến độ');
      expect(text).not.toContain('ProgressEngineService');
    });
  });
});
