import React from 'react';
import renderer from 'react-test-renderer';
import { ProgressSummaryScreen } from '@/features/progress-summary/ProgressSummaryScreen';
import { aiProgressSummaryApi } from '@/services/aiProgressSummaryApi';
import { ApiError } from '@/types/auth';
import { F04ProgressSummaryResponse } from '@/types/aiProgressSummary';

const mockBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ back: mockBack }),
}));

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({
    user: { id: 'student-user-1', timezone: 'Asia/Ho_Chi_Minh' },
    activeCapability: 'STUDENT',
  }),
}));

jest.mock('@/services/aiProgressSummaryApi', () => {
  const actual = jest.requireActual('@/services/aiProgressSummaryApi');
  return {
    ...actual,
    aiProgressSummaryApi: {
      generate: jest.fn(),
      getSummary: jest.fn(),
    },
  };
});

describe('ProgressSummaryScreen', () => {
  const sampleSuccessResponse: F04ProgressSummaryResponse = {
    runId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    correlationId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    studentId: 'student-user-1',
    actorId: 'student-user-1',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    summary: 'Bạn đã hoàn thành 4/4 buổi tập trong 14 ngày qua với khối lượng ổn định.',
    workoutProgressSummary: {
      completionEvaluation: '4/4 buổi tập đã hoàn thành.',
      adherenceEvaluation: 'Tuân thủ lịch tập 100%.',
      volumeEvaluation: 'Tổng khối lượng 12,500 kg.',
    },
    measurementProgressSummary: {
      metricsEvaluations: [
        {
          metricCode: 'WEIGHT',
          trendDirection: 'STABLE',
          message: 'Cân nặng duy trì ổn định ở mức 70.0 kg.',
        },
      ],
    },
    continuityEvaluation: {
      state: 'RECENT_ACTIVITY_OBSERVED',
      message: 'Có buổi tập hoàn thành 2 ngày trước.',
      hasSafetyAlert: false,
    },
    limitations: ['Chưa có chỉ số % mỡ chi tiết'],
    missingData: ['BODY_FAT_PERCENTAGE'],
    factsReferenced: ['workout.completed_count', 'measurement.WEIGHT.latest'],
    asOf: '2026-11-06T10:00:00Z',
    sourceWatermark: 'watermark-sample-12345',
    usage: {
      promptTokens: 150,
      completionTokens: 90,
      totalTokens: 240,
    },
    isStale: false,
    needsRefresh: false,
    currentWatermark: 'watermark-sample-12345',
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  const settle = async () => {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
  };

  it('renders initial view with presets, date inputs, and submit button', () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const preset14 = tree.root.findByProps({ testID: 'preset-14-days' });
    expect(preset14).toBeDefined();

    const fromDateInput = tree.root.findByProps({ testID: 'from-date-input' });
    const toDateInput = tree.root.findByProps({ testID: 'to-date-input' });
    expect(fromDateInput.props.value).toBeDefined();
    expect(toDateInput.props.value).toBeDefined();

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    expect(generateBtn.props.accessibilityState.disabled).toBe(false);
  });

  it('validates date range and disables submit when fromDate is after toDate', async () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const fromDateInput = tree.root.findByProps({ testID: 'from-date-input' });
    const toDateInput = tree.root.findByProps({ testID: 'to-date-input' });

    await renderer.act(async () => {
      fromDateInput.props.onChangeText('2026-11-10');
      toDateInput.props.onChangeText('2026-11-01');
    });

    const errorText = tree.root.findByProps({ testID: 'validation-error-text' });
    expect(errorText.props.children).toBe('Ngày bắt đầu không được sau ngày kết thúc.');

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    expect(generateBtn.props.accessibilityState.disabled).toBe(true);
  });

  it('validates non-existent calendar dates strictly (e.g. Feb 30)', async () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const fromDateInput = tree.root.findByProps({ testID: 'from-date-input' });

    await renderer.act(async () => {
      fromDateInput.props.onChangeText('2026-02-30');
    });

    const errorText = tree.root.findByProps({ testID: 'validation-error-text' });
    expect(errorText.props.children).toContain('Ngày bắt đầu không hợp lệ');

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    expect(generateBtn.props.accessibilityState.disabled).toBe(true);
  });

  it('submits request and renders successful progress summary with all sections', async () => {
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });

    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(1);

    const summaryText = tree.root.findByProps({ testID: 'progress-overall-summary' });
    expect(summaryText.props.children).toContain('Bạn đã hoàn thành 4/4 buổi tập');

    const workoutSection = tree.root.findByProps({ testID: 'workout-progress-section' });
    expect(workoutSection).toBeDefined();

    const measurementSection = tree.root.findByProps({ testID: 'measurement-progress-section' });
    expect(measurementSection).toBeDefined();

    const continuityLabel = tree.root.findByProps({ testID: 'continuity-state-label' });
    expect(continuityLabel.props.children).toBe('Có hoạt động tập luyện gần đây');
  });

  it('renders prominent safety alert card when continuityEvaluation has safety alert', async () => {
    const prolongedInactivityResponse: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      continuityEvaluation: {
        state: 'PROLONGED_INACTIVITY',
        message: 'Bạn đã gián đoạn tập luyện hơn 100 ngày (RETURN_AFTER_100_DAYS_V1).',
        hasSafetyAlert: true,
      },
    };
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce(prolongedInactivityResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    const safetyAlert = tree.root.findByProps({ testID: 'continuity-safety-alert' });
    expect(safetyAlert).toBeDefined();
  });

  it('renders restricted notices when workout or measurement sources are restricted', async () => {
    const restrictedResponse: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      workoutProgressSummary: null,
      measurementProgressSummary: null,
    };
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce(restrictedResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    const workoutRestricted = tree.root.findByProps({ testID: 'workout-restricted-notice' });
    expect(workoutRestricted).toBeDefined();

    const measurementRestricted = tree.root.findByProps({ testID: 'measurement-restricted-notice' });
    expect(measurementRestricted).toBeDefined();
  });

  it('displays stale banner and allows refreshing when data is marked stale', async () => {
    const staleResponse: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      isStale: true,
      needsRefresh: true,
    };
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValue(staleResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    const staleBanner = tree.root.findByProps({ testID: 'progress-summary-stale-banner' });
    expect(staleBanner).toBeDefined();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(2);
  });

  it('resets state and clears summary when target student changes (isolation test)', async () => {
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen studentId="student-A" />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    // Verify student-A summary is displayed
    expect(tree.root.findByProps({ testID: 'progress-overall-summary' })).toBeDefined();

    // Switch to student-B
    await renderer.act(async () => {
      tree.update(<ProgressSummaryScreen studentId="student-B" />);
    });

    // Verify student-A data was completely cleared and not leaked to student-B
    expect(tree.root.findAllByProps({ testID: 'progress-overall-summary' }).length).toBe(0);
  });

  it('fetches summary by initialRunId when provided', async () => {
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa" />
      );
    });
    await settle();

    expect(aiProgressSummaryApi.getSummary).toHaveBeenCalledWith(
      'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
      expect.any(Object)
    );
    const summaryText = tree.root.findByProps({ testID: 'progress-overall-summary' });
    expect(summaryText.props.children).toContain('Bạn đã hoàn thành 4/4 buổi tập');
  });

  it('displays error card when api call fails with ApiError', async () => {
    (aiProgressSummaryApi.generate as jest.Mock).mockRejectedValueOnce(
      new ApiError(403, 'Forbidden', {
        errorCode: 'AI_RUN_ACCESS_DENIED',
        message: 'Forbidden',
        timestamp: '2026-11-06T10:00:00Z',
        requestId: 'req-err',
        fieldErrors: [],
      })
    );

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
  });

  it('does not display stale banner when isStale is false or null', async () => {
    const freshResponse: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      isStale: false,
      needsRefresh: false,
    };
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce(freshResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    expect(tree.root.findAllByProps({ testID: 'progress-summary-stale-banner' }).length).toBe(0);
  });

  it('distinguishes measurement-no-data-notice vs measurement-restricted-notice', async () => {
    // 1. Measurement available but no data logged in window
    const noDataResponse: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      measurementProgressSummary: {
        metricsEvaluations: [],
      },
    };
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce(noDataResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    expect(tree.root.findByProps({ testID: 'measurement-no-data-notice' })).toBeDefined();
    expect(tree.root.findAllByProps({ testID: 'measurement-restricted-notice' }).length).toBe(0);

    // 2. Measurement restricted by sharing policy
    const restrictedResponse: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      measurementProgressSummary: null,
    };
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce(restrictedResponse);

    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    expect(tree.root.findByProps({ testID: 'measurement-restricted-notice' })).toBeDefined();
    expect(tree.root.findAllByProps({ testID: 'measurement-no-data-notice' }).length).toBe(0);
  });

  it('prevents double submit when user rapidly clicks generate button', async () => {
    let resolvePromise!: (val: any) => void;
    const delayedPromise = new Promise((resolve) => {
      resolvePromise = resolve;
    });
    (aiProgressSummaryApi.generate as jest.Mock).mockReturnValue(delayedPromise);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });

    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });

    // First click initiates request
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });

    // Rapid second click while in-flight
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });

    // Resolve the delayed call
    await renderer.act(async () => {
      resolvePromise(sampleSuccessResponse);
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(1);
  });
});
