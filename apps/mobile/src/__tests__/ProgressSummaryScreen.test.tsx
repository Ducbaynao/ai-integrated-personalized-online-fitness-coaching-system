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
    fromDate: '2026-10-17',
    toDate: '2026-10-31',
    timezone: 'Asia/Ho_Chi_Minh',
    requestedMetricCodes: ['WEIGHT'],
    metricScope: 'SELECTED',
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

  it('handles sequential resolution of prior pending requests across student switching (A -> B -> A) without data leaks', async () => {
    function createDeferred<T>() {
      let resolve!: (value: T) => void;
      let reject!: (reason?: any) => void;
      const promise = new Promise<T>((res, rej) => {
        resolve = res;
        reject = rej;
      });
      return { promise, resolve, reject };
    }

    const deferredA1 = createDeferred<any>();
    const deferredB = createDeferred<any>();
    const deferredA2 = createDeferred<any>();

    const mockGenerate = aiProgressSummaryApi.generate as jest.Mock;
    mockGenerate
      .mockImplementationOnce(() => deferredA1.promise)
      .mockImplementationOnce(() => deferredB.promise)
      .mockImplementationOnce(() => deferredA2.promise);

    const responseA1 = { ...sampleSuccessResponse, summary: 'Summary for Student A - Request 1' };
    const responseB = { ...sampleSuccessResponse, summary: 'CONFIDENTIAL SUMMARY FOR STUDENT B' };
    const responseA2 = { ...sampleSuccessResponse, summary: 'Summary for Student A - Request 2 (LATEST)' };

    let tree!: renderer.ReactTestRenderer;
    // 1. Mount for Student A and trigger Request A1
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen studentId="student-A" />);
    });
    const generateBtnA1 = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtnA1.props.onPress();
    });

    // 2. Switch to Student B and trigger Request B
    await renderer.act(async () => {
      tree.update(<ProgressSummaryScreen studentId="student-B" />);
    });
    const generateBtnB = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtnB.props.onPress();
    });

    // 3. Switch back to Student A and trigger Request A2
    await renderer.act(async () => {
      tree.update(<ProgressSummaryScreen studentId="student-A" />);
    });
    const generateBtnA2 = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtnA2.props.onPress();
    });

    // 4. Resolve prior requests before A2:
    // A1 (stale reqId for student A) -> discarded
    await renderer.act(async () => {
      deferredA1.resolve(responseA1);
    });
    await settle();
    expect(tree.root.findAllByProps({ testID: 'progress-overall-summary' }).length).toBe(0);

    // B (student mismatch while viewing A) -> discarded
    await renderer.act(async () => {
      deferredB.resolve(responseB);
    });
    await settle();
    expect(tree.root.findAllByProps({ testID: 'progress-overall-summary' }).length).toBe(0);

    // Finally A2 -> accepted and rendered
    await renderer.act(async () => {
      deferredA2.resolve(responseA2);
    });
    await settle();

    const summaryCard = tree.root.findByProps({ testID: 'progress-overall-summary' });
    expect(summaryCard).toBeDefined();
    expect(summaryCard.props.children).toBe('Summary for Student A - Request 2 (LATEST)');
    expect(summaryCard.props.children).not.toContain('CONFIDENTIAL SUMMARY FOR STUDENT B');
    expect(summaryCard.props.children).not.toContain('Summary for Student A - Request 1');
  });

  it('prevents late responses (A1, B) from overwriting newer displayed result (A2) and corrupting states when resolved out of order (A2 first, then A1, then B)', async () => {
    function createDeferred<T>() {
      let resolve!: (value: T) => void;
      let reject!: (reason?: any) => void;
      const promise = new Promise<T>((res, rej) => {
        resolve = res;
        reject = rej;
      });
      return { promise, resolve, reject };
    }

    const deferredA1 = createDeferred<any>();
    const deferredB = createDeferred<any>();
    const deferredA2 = createDeferred<any>();

    const mockGenerate = aiProgressSummaryApi.generate as jest.Mock;
    mockGenerate
      .mockImplementationOnce(() => deferredA1.promise)
      .mockImplementationOnce(() => deferredB.promise)
      .mockImplementationOnce(() => deferredA2.promise);

    const responseA1 = { ...sampleSuccessResponse, summary: 'Summary for Student A - Request 1 (STALE)' };
    const responseB = { ...sampleSuccessResponse, summary: 'CONFIDENTIAL SUMMARY FOR STUDENT B' };
    const responseA2 = { ...sampleSuccessResponse, summary: 'Summary for Student A - Request 2 (LATEST)' };

    let tree!: renderer.ReactTestRenderer;
    // 1. Mount for Student A and trigger Request A1
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen studentId="student-A" />);
    });
    const generateBtnA1 = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtnA1.props.onPress();
    });

    // 2. Switch to Student B and trigger Request B
    await renderer.act(async () => {
      tree.update(<ProgressSummaryScreen studentId="student-B" />);
    });
    const generateBtnB = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtnB.props.onPress();
    });

    // 3. Switch back to Student A and trigger Request A2
    await renderer.act(async () => {
      tree.update(<ProgressSummaryScreen studentId="student-A" />);
    });
    const generateBtnA2 = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtnA2.props.onPress();
    });

    // 4. RESOLVE A2 FIRST: Confirm latest result is rendered immediately
    await renderer.act(async () => {
      deferredA2.resolve(responseA2);
    });
    await settle();

    const summaryCardAfterA2 = tree.root.findByProps({ testID: 'progress-overall-summary' });
    expect(summaryCardAfterA2).toBeDefined();
    expect(summaryCardAfterA2.props.children).toBe('Summary for Student A - Request 2 (LATEST)');
    // No error message present
    expect(tree.root.findAllByProps({ testID: 'progress-summary-error-banner' }).length).toBe(0);

    // 5. RESOLVE A1 SECOND (late response from older request A1):
    // Must NOT overwrite newer result A2 and must NOT corrupt loading/error states
    await renderer.act(async () => {
      deferredA1.resolve(responseA1);
    });
    await settle();

    const summaryCardAfterA1 = tree.root.findByProps({ testID: 'progress-overall-summary' });
    expect(summaryCardAfterA1.props.children).toBe('Summary for Student A - Request 2 (LATEST)');
    expect(summaryCardAfterA1.props.children).not.toContain('Summary for Student A - Request 1 (STALE)');
    expect(tree.root.findAllByProps({ testID: 'progress-summary-error-banner' }).length).toBe(0);

    // 6. RESOLVE B LAST (late response for different student B):
    // Must NOT leak student B data and must keep student A result intact
    await renderer.act(async () => {
      deferredB.resolve(responseB);
    });
    await settle();

    const summaryCardAfterB = tree.root.findByProps({ testID: 'progress-overall-summary' });
    expect(summaryCardAfterB.props.children).toBe('Summary for Student A - Request 2 (LATEST)');
    expect(summaryCardAfterB.props.children).not.toContain('CONFIDENTIAL SUMMARY FOR STUDENT B');
    expect(tree.root.findAllByProps({ testID: 'progress-summary-error-banner' }).length).toBe(0);
  });

  it('prevents late rejections from prior requests (A1, B) from corrupting currently displayed result (A2)', async () => {
    function createDeferred<T>() {
      let resolve!: (value: T) => void;
      let reject!: (reason?: any) => void;
      const promise = new Promise<T>((res, rej) => {
        resolve = res;
        reject = rej;
      });
      return { promise, resolve, reject };
    }

    const deferredA1 = createDeferred<any>();
    const deferredB = createDeferred<any>();
    const deferredA2 = createDeferred<any>();

    const mockGenerate = aiProgressSummaryApi.generate as jest.Mock;
    mockGenerate
      .mockImplementationOnce(() => deferredA1.promise)
      .mockImplementationOnce(() => deferredB.promise)
      .mockImplementationOnce(() => deferredA2.promise);

    const responseA2 = { ...sampleSuccessResponse, summary: 'Summary for Student A - Request 2 (LATEST)' };

    let tree!: renderer.ReactTestRenderer;
    // 1. Mount A and request A1
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen studentId="student-A" />);
    });
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'generate-summary-button' }).props.onPress();
    });

    // 2. Switch to B and request B
    await renderer.act(async () => {
      tree.update(<ProgressSummaryScreen studentId="student-B" />);
    });
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'generate-summary-button' }).props.onPress();
    });

    // 3. Switch back to A and request A2
    await renderer.act(async () => {
      tree.update(<ProgressSummaryScreen studentId="student-A" />);
    });
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'generate-summary-button' }).props.onPress();
    });

    // 4. Resolve A2 successfully first
    await renderer.act(async () => {
      deferredA2.resolve(responseA2);
    });
    await settle();

    expect(tree.root.findByProps({ testID: 'progress-overall-summary' }).props.children).toBe(
      'Summary for Student A - Request 2 (LATEST)'
    );
    expect(tree.root.findAllByProps({ testID: 'progress-summary-error-banner' }).length).toBe(0);

    // 5. Late reject from A1: should be ignored, should NOT show error banner on A2
    await renderer.act(async () => {
      deferredA1.reject(new Error('Network error on old request A1'));
    });
    await settle();

    expect(tree.root.findByProps({ testID: 'progress-overall-summary' }).props.children).toBe(
      'Summary for Student A - Request 2 (LATEST)'
    );
    expect(tree.root.findAllByProps({ testID: 'progress-summary-error-banner' }).length).toBe(0);

    // 6. Late reject from B: should be ignored
    await renderer.act(async () => {
      deferredB.reject(new Error('Student B network failure'));
    });
    await settle();

    expect(tree.root.findByProps({ testID: 'progress-overall-summary' }).props.children).toBe(
      'Summary for Student A - Request 2 (LATEST)'
    );
    expect(tree.root.findAllByProps({ testID: 'progress-summary-error-banner' }).length).toBe(0);
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

  it('preserves original scope (dates, timezone, requested metrics) when refreshing saved stale summary', async () => {
    const customStaleSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-custom-october',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-31',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: 'SELECTED',
      requestedMetricCodes: ['WEIGHT', 'BODY_FAT_PERCENTAGE'],
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(customStaleSummary);
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce({
      ...customStaleSummary,
      runId: 'fresh-run-custom-october',
      isStale: false,
      needsRefresh: false,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-custom-october" />
      );
    });
    await settle();

    // Verify stale banner exists
    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    expect(refreshBtn).toBeDefined();

    // Click "Làm mới ngay"
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    // Expect generate was called with exact saved scope, NOT the default 14 days
    expect(aiProgressSummaryApi.generate).toHaveBeenCalledWith(
      {
        studentId: 'student-user-1',
        fromDate: '2026-10-01',
        toDate: '2026-10-31',
        timezone: 'Asia/Ho_Chi_Minh',
        metricCodes: ['WEIGHT', 'BODY_FAT_PERCENTAGE'],
      },
      expect.any(Object)
    );
  });

  it('preserves saved scope on "Làm mới ngay" even if form inputs are edited, while "Cập nhật lại tóm tắt" uses modified form inputs', async () => {
    const savedStaleSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-dates-sync',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-31',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: 'SELECTED',
      requestedMetricCodes: ['WEIGHT'],
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(savedStaleSummary);
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValue(sampleSuccessResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-dates-sync" />
      );
    });
    await settle();

    // Form was populated from readback: modify fromDate input
    const fromInput = tree.root.findByProps({ testID: 'from-date-input' });
    renderer.act(() => {
      fromInput.props.onChangeText('2026-10-10');
    });

    // 1. Tapping "Làm mới ngay" strictly refreshes the saved summary scope (2026-10-01 to 2026-10-31)
    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenLastCalledWith(
      {
        studentId: 'student-user-1',
        fromDate: '2026-10-01',
        toDate: '2026-10-31',
        timezone: 'Asia/Ho_Chi_Minh',
        metricCodes: ['WEIGHT'],
      },
      expect.any(Object)
    );

    // Now change form again
    renderer.act(() => {
      fromInput.props.onChangeText('2026-10-15');
    });

    // 2. Tapping form submit "Cập nhật lại tóm tắt" uses the user-modified form scope (2026-10-15)
    const generateBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      generateBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenLastCalledWith(
      expect.objectContaining({
        studentId: 'student-user-1',
        fromDate: '2026-10-15',
        toDate: '2026-10-31',
      }),
      expect.any(Object)
    );
  });

  it('legacy run lacking fromDate/toDate rejects automatic refresh with validation error and does not call generate', async () => {
    const legacyStaleSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'legacy-run-no-dates',
      isStale: true,
      needsRefresh: true,
      fromDate: undefined,
      toDate: undefined,
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(legacyStaleSummary);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="legacy-run-no-dates" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    // Generate must NOT have been called
    expect(aiProgressSummaryApi.generate).not.toHaveBeenCalled();

    // Error card guides the user
    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
    const errorTexts = errorCard.findAllByType('Text' as any);
    const hasExpectedMessage = errorTexts.some((t: any) =>
      typeof t.props.children === 'string' &&
      t.props.children.includes('Bản tóm tắt cũ không có đủ thông tin phạm vi lưu trữ')
    );
    expect(hasExpectedMessage).toBe(true);
  });

  it('refreshing saved summary with metricScope ALL sends metricCodes undefined even if form chips were modified', async () => {
    const allMetricsStaleSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-all-metrics',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-25',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: 'ALL',
      requestedMetricCodes: undefined,
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(allMetricsStaleSummary);
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce({
      ...allMetricsStaleSummary,
      runId: 'fresh-run-all-metrics',
      isStale: false,
      needsRefresh: false,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-all-metrics" />
      );
    });
    await settle();

    // Toggle a metric chip in the form (e.g. WEIGHT)
    const weightChip = tree.root.findByProps({ testID: 'metric-chip-WEIGHT' });
    renderer.act(() => {
      weightChip.props.onPress();
    });

    // Tap "Làm mới ngay"
    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    // Must preserve ALL (metricCodes: undefined), NOT sending the user-clicked chip!
    expect(aiProgressSummaryApi.generate).toHaveBeenLastCalledWith(
      {
        studentId: 'student-user-1',
        fromDate: '2026-10-01',
        toDate: '2026-10-25',
        timezone: 'Asia/Ho_Chi_Minh',
        metricCodes: undefined,
      },
      expect.any(Object)
    );
  });

  it('refreshing saved summary with metricScope SELECTED preserves requestedMetricCodes even if form chips were modified', async () => {
    const selectedStaleSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-selected-metrics',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-25',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: 'SELECTED',
      requestedMetricCodes: ['WEIGHT'],
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(selectedStaleSummary);
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce({
      ...selectedStaleSummary,
      runId: 'fresh-run-selected-metrics',
      isStale: false,
      needsRefresh: false,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-selected-metrics" />
      );
    });
    await settle();

    // Toggle another metric chip in form (e.g. BODY_FAT_PERCENTAGE)
    const bfChip = tree.root.findByProps({ testID: 'metric-chip-BODY_FAT_PERCENTAGE' });
    renderer.act(() => {
      bfChip.props.onPress();
    });

    // Tap "Làm mới ngay"
    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    // Must preserve strictly the original requestedMetricCodes ['WEIGHT']
    expect(aiProgressSummaryApi.generate).toHaveBeenLastCalledWith(
      {
        studentId: 'student-user-1',
        fromDate: '2026-10-01',
        toDate: '2026-10-25',
        timezone: 'Asia/Ho_Chi_Minh',
        metricCodes: ['WEIGHT'],
      },
      expect.any(Object)
    );
  });

  it('refreshing legacy run with UNKNOWN_LEGACY displays guidance banner and does not generate', async () => {
    const unknownLegacySummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'legacy-run-unknown-scope',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-25',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: 'UNKNOWN_LEGACY',
      requestedMetricCodes: undefined,
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(unknownLegacySummary);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="legacy-run-unknown-scope" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    // Must NOT call generate
    expect(aiProgressSummaryApi.generate).not.toHaveBeenCalled();

    // Guides user with clear message
    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
    const errorTexts = errorCard.findAllByType('Text' as any);
    const hasExpectedMessage = errorTexts.some((t: any) =>
      typeof t.props.children === 'string' &&
      t.props.children.includes('Bản tóm tắt cũ không có đủ thông tin phạm vi lưu trữ')
    );
    expect(hasExpectedMessage).toBe(true);
  });

  it('refreshing saved summary with metricScope undefined or null blocks refresh and shows legacy scope message', async () => {
    const undefinedScopeSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'legacy-run-undefined-scope',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-25',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: undefined,
      requestedMetricCodes: undefined,
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(undefinedScopeSummary);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="legacy-run-undefined-scope" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    // Must NOT call generate
    expect(aiProgressSummaryApi.generate).not.toHaveBeenCalled();

    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
    const errorTexts = errorCard.findAllByType('Text' as any);
    const hasExpectedMessage = errorTexts.some((t: any) =>
      typeof t.props.children === 'string' &&
      t.props.children.includes('Bản tóm tắt cũ không có đủ thông tin phạm vi lưu trữ')
    );
    expect(hasExpectedMessage).toBe(true);
  });

  it('refreshing saved summary with invalid metricScope string blocks refresh and shows legacy scope message', async () => {
    const invalidScopeSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'legacy-run-invalid-scope',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-25',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: 'SOME_UNSUPPORTED_SCOPE' as any,
      requestedMetricCodes: ['WEIGHT'],
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(invalidScopeSummary);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="legacy-run-invalid-scope" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).not.toHaveBeenCalled();

    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
    const errorTexts = errorCard.findAllByType('Text' as any);
    const hasExpectedMessage = errorTexts.some((t: any) =>
      typeof t.props.children === 'string' &&
      t.props.children.includes('Bản tóm tắt cũ không có đủ thông tin phạm vi lưu trữ')
    );
    expect(hasExpectedMessage).toBe(true);
  });

  it('refreshing saved summary with metricScope SELECTED but missing or empty requestedMetricCodes blocks refresh and shows missing codes message', async () => {
    const selectedWithoutCodesSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'run-selected-no-codes',
      isStale: true,
      needsRefresh: true,
      fromDate: '2026-10-01',
      toDate: '2026-10-25',
      timezone: 'Asia/Ho_Chi_Minh',
      metricScope: 'SELECTED',
      requestedMetricCodes: [],
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(selectedWithoutCodesSummary);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="run-selected-no-codes" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).not.toHaveBeenCalled();

    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
    const errorTexts = errorCard.findAllByType('Text' as any);
    const hasExpectedMessage = errorTexts.some((t: any) =>
      typeof t.props.children === 'string' &&
      t.props.children.includes('Bản tóm tắt thiếu danh sách chỉ số đã chọn hợp lệ')
    );
    expect(hasExpectedMessage).toBe(true);
  });

  it('network failure during initialRunId loading sets error card; clicking retry retries getSummary and recovers', async () => {
    const netErr = new ApiError(0, 'Mất kết nối mạng');
    (aiProgressSummaryApi.getSummary as jest.Mock)
      .mockRejectedValueOnce(netErr)
      .mockResolvedValueOnce(sampleSuccessResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="run-network-fail" />
      );
    });
    await settle();

    // Verify error card is displayed
    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
    const errorTexts = errorCard.findAllByType('Text' as any);
    const hasNetErrMsg = errorTexts.some((t: any) =>
      typeof t.props.children === 'string' &&
      t.props.children.includes('Mất kết nối mạng')
    );
    expect(hasNetErrMsg).toBe(true);
    expect(aiProgressSummaryApi.getSummary).toHaveBeenCalledTimes(1);

    // Click "Thử lại" button
    const retryBtn = tree.root.findByProps({ testID: 'retry-summary-button' });
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });
    await settle();

    // Should call getSummary a second time with the initialRunId
    expect(aiProgressSummaryApi.getSummary).toHaveBeenCalledTimes(2);
    expect(aiProgressSummaryApi.getSummary).toHaveBeenLastCalledWith(
      'run-network-fail',
      expect.any(Object)
    );

    // Summary should now be successfully displayed
    const summaryContainer = tree.root.findByProps({ testID: 'progress-summary-result-container' });
    expect(summaryContainer).toBeDefined();
  });

  it('network failure during generateSummary sets error card; clicking retry retries generate with original parameters and recovers', async () => {
    const netErr = new ApiError(0, 'Mất kết nối mạng');
    (aiProgressSummaryApi.generate as jest.Mock)
      .mockRejectedValueOnce(netErr)
      .mockResolvedValueOnce(sampleSuccessResponse);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ProgressSummaryScreen />);
    });
    await settle();

    // Select a preset or input
    const submitBtn = tree.root.findByProps({ testID: 'generate-summary-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(1);

    // Error card displayed
    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();

    // Click "Thử lại"
    const retryBtn = tree.root.findByProps({ testID: 'retry-summary-button' });
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(2);
    const summaryContainer = tree.root.findByProps({ testID: 'progress-summary-result-container' });
    expect(summaryContainer).toBeDefined();
  });

  it('network failure during refreshSavedSummary sets error card; clicking retry retries refresh with original scope and recovers', async () => {
    const staleRun: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-for-refresh-retry',
      isStale: true,
      needsRefresh: true,
      metricScope: 'SELECTED',
      requestedMetricCodes: ['WEIGHT'],
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(staleRun);

    const netErr = new ApiError(0, 'Mất kết nối mạng');
    (aiProgressSummaryApi.generate as jest.Mock)
      .mockRejectedValueOnce(netErr)
      .mockResolvedValueOnce({
        ...sampleSuccessResponse,
        runId: 'fresh-run-after-refresh-retry',
        isStale: false,
        needsRefresh: false,
      });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-for-refresh-retry" />
      );
    });
    await settle();

    // Tap refresh
    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(1);

    // Error card displayed
    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();

    // Click retry
    const retryBtn = tree.root.findByProps({ testID: 'retry-summary-button' });
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(2);
    expect(aiProgressSummaryApi.generate).toHaveBeenLastCalledWith(
      expect.objectContaining({
        studentId: 'student-user-1',
        metricCodes: ['WEIGHT'],
      }),
      expect.any(Object)
    );

    const summaryContainer = tree.root.findByProps({ testID: 'progress-summary-result-container' });
    expect(summaryContainer).toBeDefined();
  });

  it('refreshing saved summary with missing timezone blocks refresh and displays guidance banner', async () => {
    const missingTzSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-missing-tz',
      isStale: true,
      needsRefresh: true,
      metricScope: 'SELECTED',
      requestedMetricCodes: ['WEIGHT'],
      timezone: undefined,
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(missingTzSummary);

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-missing-tz" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    // Must NOT call generate
    expect(aiProgressSummaryApi.generate).not.toHaveBeenCalled();

    // Guidance banner must be visible
    const errorCard = tree.root.findByProps({ testID: 'progress-summary-error-card' });
    expect(errorCard).toBeDefined();
    const errorTexts = errorCard.findAllByType('Text' as any);
    const hasExpectedMessage = errorTexts.some((t: any) =>
      typeof t.props.children === 'string' &&
      t.props.children.includes('Bản tóm tắt cũ không có đủ thông tin múi giờ lưu trữ')
    );
    expect(hasExpectedMessage).toBe(true);
  });

  it('refreshing saved summary preserves original stored timezone even if user profile has different timezone', async () => {
    // Saved run has Asia/Tokyo, while auth mock has Asia/Ho_Chi_Minh
    const storedTokyoSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-tokyo-tz',
      isStale: true,
      needsRefresh: true,
      metricScope: 'SELECTED',
      requestedMetricCodes: ['WEIGHT'],
      timezone: 'Asia/Tokyo',
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(storedTokyoSummary);
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce({
      ...sampleSuccessResponse,
      runId: 'fresh-run-tokyo-tz',
      timezone: 'Asia/Tokyo',
      isStale: false,
      needsRefresh: false,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-tokyo-tz" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(1);
    expect(aiProgressSummaryApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        timezone: 'Asia/Tokyo',
      }),
      expect.any(Object)
    );
  });

  it('refreshing saved summary with restored timezone from metadata refreshes with that timezone', async () => {
    const storedParisSummary: F04ProgressSummaryResponse = {
      ...sampleSuccessResponse,
      runId: 'stale-run-paris-tz',
      isStale: true,
      needsRefresh: true,
      metricScope: 'SELECTED',
      requestedMetricCodes: ['WEIGHT'],
      timezone: 'Europe/Paris',
    };
    (aiProgressSummaryApi.getSummary as jest.Mock).mockResolvedValueOnce(storedParisSummary);
    (aiProgressSummaryApi.generate as jest.Mock).mockResolvedValueOnce({
      ...sampleSuccessResponse,
      runId: 'fresh-run-paris-tz',
      timezone: 'Europe/Paris',
      isStale: false,
      needsRefresh: false,
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <ProgressSummaryScreen initialRunId="stale-run-paris-tz" />
      );
    });
    await settle();

    const refreshBtn = tree.root.findByProps({ testID: 'refresh-summary-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(aiProgressSummaryApi.generate).toHaveBeenCalledTimes(1);
    expect(aiProgressSummaryApi.generate).toHaveBeenCalledWith(
      expect.objectContaining({
        timezone: 'Europe/Paris',
      }),
      expect.any(Object)
    );
  });
});
