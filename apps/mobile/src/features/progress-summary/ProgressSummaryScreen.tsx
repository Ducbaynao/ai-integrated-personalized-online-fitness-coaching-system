import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import moment from 'moment-timezone';
import { useAuth } from '@/features/auth/AuthContext';
import { colors, getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import {
  aiProgressSummaryApi,
  getAiProgressSummaryErrorMessage,
  getUserTimezone,
  CONTINUITY_STATE_LABELS,
  METRIC_LABELS,
  METRIC_OPTIONS,
  TREND_DIRECTION_LABELS,
} from '@/services/aiProgressSummaryApi';
import {
  F04ProgressSummaryResponse,
} from '@/types/aiProgressSummary';

export interface ProgressSummaryScreenProps {
  studentId?: string;
  relationshipId?: string;
  initialRunId?: string;
  onBack?: () => void;
}

type PresetDays = 7 | 14 | 30 | 'CUSTOM';

export function ProgressSummaryScreen({
  studentId,
  relationshipId,
  initialRunId,
  onBack,
}: ProgressSummaryScreenProps) {
  const router = useRouter();
  const { user, activeCapability } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);

  // Target student: if provided via prop (Trainer mode), use that. Otherwise use current user's ID (Student mode).
  const targetStudentId = studentId || user?.id;
  const isTrainer = activeCapability === 'TRAINER' && Boolean(studentId && studentId !== user?.id);

  // Date range states
  const [preset, setPreset] = useState<PresetDays>(14);
  const [toDate, setToDate] = useState(() => moment().format('YYYY-MM-DD'));
  const [fromDate, setFromDate] = useState(() => moment().subtract(14, 'days').format('YYYY-MM-DD'));

  // Metrics filter state (default all selected)
  const [selectedMetrics, setSelectedMetrics] = useState<string[]>(() =>
    METRIC_OPTIONS.map((m) => m.code)
  );

  // UI state
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [isLoadingRun, setIsLoadingRun] = useState(() => Boolean(initialRunId));
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<F04ProgressSummaryResponse | null>(null);

  // Request counter and abort controller for robust race-condition and cancellation protection (covers A -> B -> A)
  const activeRequestIdRef = useRef(0);
  const inFlightAbortRef = useRef<AbortController | null>(null);
  const isSubmittingRef = useRef(false);
  const isMountedRef = useRef(true);
  const currentStudentIdRef = useRef(targetStudentId);
  const lastFailedActionRef = useRef<(() => void) | null>(null);
  const loadRunByIdRef = useRef<((runId: string) => AbortController) | null>(null);
  const handleGenerateSummaryRef = useRef<(() => Promise<void>) | null>(null);
  const handleRefreshSavedSummaryRef = useRef<(() => Promise<void>) | null>(null);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      if (inFlightAbortRef.current) {
        inFlightAbortRef.current.abort();
        inFlightAbortRef.current = null;
      }
    };
  }, []);

  // When target student changes or user logs out, immediately cancel pending requests and clear state
  useEffect(() => {
    if (currentStudentIdRef.current !== targetStudentId) {
      currentStudentIdRef.current = targetStudentId;
      if (inFlightAbortRef.current) {
        inFlightAbortRef.current.abort();
        inFlightAbortRef.current = null;
      }
      activeRequestIdRef.current++;
      setResult(null);
      setErrorMessage(null);
      setIsSubmitting(false);
      isSubmittingRef.current = false;
      lastFailedActionRef.current = null;
    }
  }, [targetStudentId]);

  // Load initial run if requested via prop
  const loadRunById = useCallback((runIdToLoad: string) => {
    const controller = new AbortController();
    queueMicrotask(() => {
      if (isMountedRef.current && !controller.signal.aborted) {
        setIsLoadingRun(true);
        setErrorMessage(null);
      }
    });

    aiProgressSummaryApi
      .getSummary(runIdToLoad, controller.signal)
      .then((data) => {
        if (isMountedRef.current && !controller.signal.aborted) {
          setResult(data);
          if (data.fromDate) {
            setFromDate(data.fromDate);
          }
          if (data.toDate) {
            setToDate(data.toDate);
          }
          if (
            data.metricScope === 'SELECTED' &&
            Array.isArray(data.requestedMetricCodes) &&
            data.requestedMetricCodes.length > 0
          ) {
            setSelectedMetrics(data.requestedMetricCodes);
          } else if (data.metricScope === 'ALL') {
            setSelectedMetrics([]);
          }
          if (data.fromDate && data.toDate) {
            const today = moment().format('YYYY-MM-DD');
            const daysDiff = moment(data.toDate).diff(moment(data.fromDate), 'days');
            if (data.toDate === today && (daysDiff === 7 || daysDiff === 14 || daysDiff === 30)) {
              setPreset(daysDiff as PresetDays);
            } else {
              setPreset('CUSTOM');
            }
          }
          lastFailedActionRef.current = null;
        }
      })
      .catch((err) => {
        if (isMountedRef.current && !controller.signal.aborted) {
          setErrorMessage(getAiProgressSummaryErrorMessage(err));
          lastFailedActionRef.current = () => {
            loadRunByIdRef.current?.(runIdToLoad);
          };
        }
      })
      .finally(() => {
        if (isMountedRef.current && !controller.signal.aborted) {
          setIsLoadingRun(false);
        }
      });

    return controller;
  }, []);

  useEffect(() => {
    if (initialRunId) {
      const controller = loadRunById(initialRunId);
      return () => {
        controller.abort();
      };
    }
  }, [initialRunId, loadRunById]);

  // Handle Preset selection
  const handleSelectPreset = (days: PresetDays) => {
    setPreset(days);
    if (days !== 'CUSTOM') {
      const today = moment().format('YYYY-MM-DD');
      const start = moment().subtract(days, 'days').format('YYYY-MM-DD');
      setToDate(today);
      setFromDate(start);
    }
  };

  // Toggle metric selection
  const handleToggleMetric = (code: string) => {
    setSelectedMetrics((prev) =>
      prev.includes(code) ? prev.filter((c) => c !== code) : [...prev, code]
    );
  };

  // Date range validation with strict calendar and order checks
  const validationError = useMemo(() => {
    if (!fromDate || !moment(fromDate, 'YYYY-MM-DD', true).isValid()) {
      return 'Ngày bắt đầu không hợp lệ (định dạng YYYY-MM-DD hoặc ngày không tồn tại).';
    }
    if (!toDate || !moment(toDate, 'YYYY-MM-DD', true).isValid()) {
      return 'Ngày kết thúc không hợp lệ (định dạng YYYY-MM-DD hoặc ngày không tồn tại).';
    }
    if (moment(fromDate).isAfter(moment(toDate))) {
      return 'Ngày bắt đầu không được sau ngày kết thúc.';
    }
    return null;
  }, [fromDate, toDate]);

  // Submit handler with sequence guarding against A -> B -> A race conditions
  const handleGenerateSummary = useCallback(async () => {
    if (isSubmittingRef.current) {
      return; // Anti-double submit
    }

    if (validationError) {
      setErrorMessage(validationError);
      return;
    }

    const callingStudentId = targetStudentId;
    const reqId = ++activeRequestIdRef.current;

    if (inFlightAbortRef.current) {
      inFlightAbortRef.current.abort();
    }
    const controller = new AbortController();
    inFlightAbortRef.current = controller;

    isSubmittingRef.current = true;
    setIsSubmitting(true);
    setErrorMessage(null);

    try {
      const resolvedTimezone = getUserTimezone(user?.timezone);

      const response = await aiProgressSummaryApi.generate(
        {
          studentId: callingStudentId,
          fromDate,
          toDate,
          timezone: resolvedTimezone,
          metricCodes: selectedMetrics.length > 0 ? selectedMetrics : undefined,
        },
        controller.signal
      );

      // Verify mounting, student identity, AND exact sequence ID (prevents A -> B -> A race conditions)
      if (
        isMountedRef.current &&
        currentStudentIdRef.current === callingStudentId &&
        activeRequestIdRef.current === reqId
      ) {
        setResult(response);
        lastFailedActionRef.current = null;
        if (response.fromDate) {
          setFromDate(response.fromDate);
        }
        if (response.toDate) {
          setToDate(response.toDate);
        }
        if (response.requestedMetricCodes && response.requestedMetricCodes.length > 0) {
          setSelectedMetrics(response.requestedMetricCodes);
        }
      }
    } catch (err) {
      if (controller.signal.aborted) {
        return; // Request was cleanly cancelled; ignore without showing error
      }
      if (
        isMountedRef.current &&
        currentStudentIdRef.current === callingStudentId &&
        activeRequestIdRef.current === reqId
      ) {
        setErrorMessage(getAiProgressSummaryErrorMessage(err));
        lastFailedActionRef.current = () => {
          handleGenerateSummaryRef.current?.();
        };
      }
    } finally {
      if (activeRequestIdRef.current === reqId && isMountedRef.current) {
        setIsSubmitting(false);
        isSubmittingRef.current = false;
        inFlightAbortRef.current = null;
      }
    }
  }, [targetStudentId, fromDate, toDate, user, selectedMetrics, validationError]);

  // Refresh handler attached strictly to the stale saved summary
  // Preserves original scope: Student, fromDate, toDate, timezone, and requested metric codes
  const handleRefreshSavedSummary = useCallback(async () => {
    if (isSubmittingRef.current) {
      return; // Anti-double submit
    }
    if (!result) {
      return;
    }

    const legacyScopeMessage =
      'Bản tóm tắt cũ không có đủ thông tin phạm vi lưu trữ. Vui lòng kiểm tra phạm vi ngày và chỉ số trong biểu mẫu rồi bấm "Cập nhật lại tóm tắt".';

    // 1. Validate date window
    if (!result.fromDate || !result.toDate) {
      setErrorMessage(legacyScopeMessage);
      lastFailedActionRef.current = null;
      return;
    }

    // 2. Validate studentId
    const callingStudentId = result.studentId || targetStudentId;
    if (!callingStudentId) {
      setErrorMessage(legacyScopeMessage);
      lastFailedActionRef.current = null;
      return;
    }

    // 3. Validate timezone: must strictly use stored timezone, never silently fallback to user profile/device
    if (!result.timezone || typeof result.timezone !== 'string' || result.timezone.trim().length === 0) {
      setErrorMessage(
        'Bản tóm tắt cũ không có đủ thông tin múi giờ lưu trữ. Vui lòng kiểm tra múi giờ, phạm vi ngày và chỉ số trong biểu mẫu rồi bấm "Cập nhật lại tóm tắt".'
      );
      lastFailedActionRef.current = null;
      return;
    }
    const refreshTimezone = result.timezone.trim();

    // 4. Validate metricScope strictly:
    // - ALL is accepted ONLY when metricScope is explicitly 'ALL'
    // - SELECTED is accepted ONLY when metricScope is explicitly 'SELECTED' AND requestedMetricCodes is a valid non-empty array
    // - Any missing/undefined/null/UNKNOWN_LEGACY or unsupported scope, or SELECTED without codes:
    //   MUST NOT automatically refresh and MUST NOT guess as ALL or use form chips.
    let refreshMetrics: string[] | undefined = undefined;

    if (result.metricScope === 'ALL') {
      refreshMetrics = undefined; // Explicitly ALL metrics
    } else if (result.metricScope === 'SELECTED') {
      if (
        Array.isArray(result.requestedMetricCodes) &&
        result.requestedMetricCodes.length > 0 &&
        result.requestedMetricCodes.every((c) => typeof c === 'string' && c.trim().length > 0)
      ) {
        refreshMetrics = Array.from(result.requestedMetricCodes.map((c) => c.trim()));
      } else {
        // SELECTED but missing or empty codes: inconsistent metadata, block auto-refresh!
        setErrorMessage(
          'Bản tóm tắt thiếu danh sách chỉ số đã chọn hợp lệ. Vui lòng kiểm tra các chỉ số trong biểu mẫu rồi bấm "Cập nhật lại tóm tắt".'
        );
        lastFailedActionRef.current = null;
        return;
      }
    } else {
      // metricScope is undefined, null, 'UNKNOWN_LEGACY', or an unsupported string
      setErrorMessage(legacyScopeMessage);
      lastFailedActionRef.current = null;
      return;
    }

    const refreshFromDate = result.fromDate;
    const refreshToDate = result.toDate;

    // Sync form state back to the validated original scope of this summary
    setFromDate(refreshFromDate);
    setToDate(refreshToDate);
    setSelectedMetrics(refreshMetrics || []);
    const today = moment().format('YYYY-MM-DD');
    const daysDiff = moment(refreshToDate).diff(moment(refreshFromDate), 'days');
    if (refreshToDate === today && (daysDiff === 7 || daysDiff === 14 || daysDiff === 30)) {
      setPreset(daysDiff as PresetDays);
    } else {
      setPreset('CUSTOM');
    }

    const reqId = ++activeRequestIdRef.current;
    if (inFlightAbortRef.current) {
      inFlightAbortRef.current.abort();
    }
    const controller = new AbortController();
    inFlightAbortRef.current = controller;

    isSubmittingRef.current = true;
    setIsSubmitting(true);
    setErrorMessage(null);

    try {
      const response = await aiProgressSummaryApi.generate(
        {
          studentId: callingStudentId,
          fromDate: refreshFromDate,
          toDate: refreshToDate,
          timezone: refreshTimezone,
          metricCodes: refreshMetrics,
        },
        controller.signal
      );

      if (
        isMountedRef.current &&
        currentStudentIdRef.current === callingStudentId &&
        activeRequestIdRef.current === reqId
      ) {
        setResult(response);
        lastFailedActionRef.current = null;
      }
    } catch (err) {
      if (controller.signal.aborted) {
        return;
      }
      if (
        isMountedRef.current &&
        currentStudentIdRef.current === callingStudentId &&
        activeRequestIdRef.current === reqId
      ) {
        setErrorMessage(getAiProgressSummaryErrorMessage(err));
        lastFailedActionRef.current = () => {
          handleRefreshSavedSummaryRef.current?.();
        };
      }
    } finally {
      if (activeRequestIdRef.current === reqId && isMountedRef.current) {
        setIsSubmitting(false);
        isSubmittingRef.current = false;
        inFlightAbortRef.current = null;
      }
    }
  }, [result, targetStudentId]);

  // Keep references to handlers updated for safe retry invocation outside render
  useEffect(() => {
    loadRunByIdRef.current = loadRunById;
    handleGenerateSummaryRef.current = handleGenerateSummary;
    handleRefreshSavedSummaryRef.current = handleRefreshSavedSummary;
  }, [loadRunById, handleGenerateSummary, handleRefreshSavedSummary]);

  // Unified retry handler retrying the exact failed operation (initial load, generate, or refresh)
  const handleRetry = useCallback(() => {
    if (lastFailedActionRef.current) {
      lastFailedActionRef.current();
    } else {
      handleGenerateSummary();
    }
  }, [handleGenerateSummary]);

  const handleBack = () => {
    if (onBack) {
      onBack();
    } else {
      router.back();
    }
  };

  const isStale = result?.isStale === true || result?.needsRefresh === true;

  return (
    <SafeAreaView edges={['top']} style={[styles.container, { backgroundColor: theme.canvas }]}>
      <KeyboardAvoidingView
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}
        style={styles.keyboardContainer}>
        {/* Navigation Header */}
        <View style={[styles.header, { borderBottomColor: theme.border }]}>
          <Pressable
            testID="progress-summary-back-button"
            accessibilityRole="button"
            accessibilityLabel="Quay lại"
            onPress={handleBack}
            style={styles.backButton}>
            <Text style={[styles.backButtonText, { color: theme.primary }]}>← Quay lại</Text>
          </Pressable>
          <Text style={[styles.headerTitle, { color: theme.textPrimary }]}>
            {isTrainer ? 'Tiến độ học viên (AI)' : 'Tóm tắt tiến độ (AI)'}
          </Text>
          <View style={styles.headerSpacer} />
        </View>

        <ScrollView contentContainerStyle={styles.scrollContent}>
          {/* Scope & Date Window Card */}
          <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
            <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>
              {isTrainer ? 'Phạm vi phân tích học viên' : 'Chọn khoảng thời gian phân tích'}
            </Text>
            <Text style={[styles.cardSubtitle, { color: theme.textSecondary }]}>
              Hệ thống sẽ tổng hợp buổi tập hoàn thành, độ tuân thủ và số đo thể chất trong khoảng ngày đã chọn.
            </Text>

            {/* Presets */}
            <View style={styles.presetsRow}>
              {([7, 14, 30] as const).map((days) => (
                <Pressable
                  key={days}
                  testID={`preset-${days}-days`}
                  accessibilityRole="button"
                  accessibilityLabel={`Khoảng ${days} ngày qua`}
                  onPress={() => handleSelectPreset(days)}
                  style={[
                    styles.presetButton,
                    {
                      backgroundColor:
                        preset === days ? theme.brandSoft : theme.surfaceSubtle,
                      borderColor: preset === days ? theme.primary : theme.border,
                    },
                  ]}>
                  <Text
                    style={[
                      styles.presetButtonText,
                      { color: preset === days ? theme.primary : theme.textPrimary },
                    ]}>
                    {days} ngày qua
                  </Text>
                </Pressable>
              ))}
              <Pressable
                testID="preset-custom"
                accessibilityRole="button"
                accessibilityLabel="Tùy chọn khoảng ngày"
                onPress={() => handleSelectPreset('CUSTOM')}
                style={[
                  styles.presetButton,
                  {
                    backgroundColor:
                      preset === 'CUSTOM' ? theme.brandSoft : theme.surfaceSubtle,
                    borderColor: preset === 'CUSTOM' ? theme.primary : theme.border,
                  },
                ]}>
                <Text
                  style={[
                    styles.presetButtonText,
                    { color: preset === 'CUSTOM' ? theme.primary : theme.textPrimary },
                  ]}>
                  Tùy chọn
                </Text>
              </Pressable>
            </View>

            {/* Date Inputs */}
            <View style={styles.dateInputsRow}>
              <View style={styles.dateInputCol}>
                <Text style={[styles.inputLabel, { color: theme.textSecondary }]}>Từ ngày</Text>
                <TextInput
                  testID="from-date-input"
                  accessibilityLabel="Từ ngày định dạng YYYY-MM-DD"
                  value={fromDate}
                  onChangeText={(text) => {
                    setPreset('CUSTOM');
                    setFromDate(text);
                  }}
                  placeholder="YYYY-MM-DD"
                  placeholderTextColor={theme.textSecondary}
                  maxLength={10}
                  editable={!isSubmitting}
                  style={[
                    styles.dateInput,
                    {
                      color: theme.textPrimary,
                      backgroundColor: theme.surfaceSubtle,
                      borderColor: theme.border,
                    },
                  ]}
                />
              </View>

              <View style={styles.dateInputCol}>
                <Text style={[styles.inputLabel, { color: theme.textSecondary }]}>Đến ngày</Text>
                <TextInput
                  testID="to-date-input"
                  accessibilityLabel="Đến ngày định dạng YYYY-MM-DD"
                  value={toDate}
                  onChangeText={(text) => {
                    setPreset('CUSTOM');
                    setToDate(text);
                  }}
                  placeholder="YYYY-MM-DD"
                  placeholderTextColor={theme.textSecondary}
                  maxLength={10}
                  editable={!isSubmitting}
                  style={[
                    styles.dateInput,
                    {
                      color: theme.textPrimary,
                      backgroundColor: theme.surfaceSubtle,
                      borderColor: theme.border,
                    },
                  ]}
                />
              </View>
            </View>

            {/* Metrics Chips */}
            <View style={styles.metricsSection}>
              <Text style={[styles.inputLabel, { color: theme.textSecondary }]}>
                Chỉ số thể chất theo dõi (tùy chọn)
              </Text>
              <View style={styles.metricsChipsRow}>
                {METRIC_OPTIONS.map((metric) => {
                  const isSelected = selectedMetrics.includes(metric.code);
                  return (
                    <Pressable
                      key={metric.code}
                      testID={`metric-chip-${metric.code}`}
                      accessibilityRole="checkbox"
                      accessibilityState={{ checked: isSelected }}
                      accessibilityLabel={`Chọn chỉ số ${metric.label}`}
                      onPress={() => handleToggleMetric(metric.code)}
                      style={[
                        styles.metricChip,
                        {
                          backgroundColor: isSelected ? theme.brandSoft : theme.surfaceSubtle,
                          borderColor: isSelected ? theme.primary : theme.border,
                        },
                      ]}>
                      <Text
                        style={[
                          styles.metricChipText,
                          { color: isSelected ? theme.primary : theme.textPrimary },
                        ]}>
                        {isSelected ? '✓ ' : '+ '}
                        {metric.label}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
            </View>

            {/* Validation Message */}
            {validationError && (
              <Text testID="validation-error-text" style={[styles.validationText, { color: theme.dangerText }]}>
                {validationError}
              </Text>
            )}

            {/* Action Button */}
            <Pressable
              testID="generate-summary-button"
              accessibilityRole="button"
              accessibilityLabel="Tạo bản tóm tắt tiến độ"
              accessibilityState={{ disabled: isSubmitting || Boolean(validationError), busy: isSubmitting }}
              disabled={isSubmitting || Boolean(validationError)}
              onPress={handleGenerateSummary}
              style={({ pressed }) => [
                styles.primaryButton,
                {
                  backgroundColor:
                    isSubmitting || Boolean(validationError)
                      ? theme.border
                      : pressed
                      ? colors.brand[700]
                      : theme.primary,
                },
              ]}>
              {isSubmitting ? (
                <View style={styles.buttonBusyRow}>
                  <ActivityIndicator size="small" color={theme.textOnPrimary} />
                  <Text style={[styles.primaryButtonText, { color: theme.textOnPrimary }]}>
                    Đang phân tích tiến độ...
                  </Text>
                </View>
              ) : (
                <Text style={[styles.primaryButtonText, { color: theme.textOnPrimary }]}>
                  {result ? 'Cập nhật lại tóm tắt' : 'Tạo tóm tắt tiến độ AI'}
                </Text>
              )}
            </Pressable>
          </View>

          {/* Loading Initial Run Indicator */}
          {isLoadingRun && (
            <View testID="loading-run-indicator" style={styles.loadingContainer}>
              <ActivityIndicator size="large" color={theme.primary} />
              <Text style={[styles.loadingText, { color: theme.textSecondary }]}>
                Đang tải bản tóm tắt tiến độ...
              </Text>
            </View>
          )}

          {/* Error Card */}
          {errorMessage && (
            <View
              testID="progress-summary-error-card"
              accessibilityRole="alert"
              style={[styles.errorCard, { backgroundColor: theme.dangerSurface, borderColor: theme.borderStrong }]}>
              <Text style={[styles.errorTitle, { color: theme.dangerText }]}>Chưa thể tạo bản tóm tắt</Text>
              <Text style={[styles.errorMessage, { color: theme.dangerText }]}>{errorMessage}</Text>
              <Pressable
                testID="retry-summary-button"
                accessibilityRole="button"
                accessibilityLabel="Thử lại"
                onPress={handleRetry}
                style={[styles.retryButton, { borderColor: theme.dangerText }]}>
                <Text style={[styles.retryButtonText, { color: theme.dangerText }]}>Thử lại</Text>
              </Pressable>
            </View>
          )}

          {/* Stale / Refresh Needed Alert Banner */}
          {isStale && (
            <View
              testID="progress-summary-stale-banner"
              accessibilityRole="alert"
              style={[styles.warningBanner, { backgroundColor: theme.warningSurface, borderColor: colors.warning[600] }]}>
              <View style={styles.warningContent}>
                <Text style={[styles.warningTitle, { color: theme.warningText }]}>
                  Dữ liệu nguồn đã thay đổi
                </Text>
                <Text style={[styles.warningMessage, { color: theme.warningText }]}>
                  Đã ghi nhận buổi tập hoặc số đo mới kể từ thời điểm lập bản tóm tắt này. Bạn có thể yêu cầu tạo bản tóm tắt mới.
                </Text>
              </View>
              <Pressable
                testID="refresh-summary-button"
                accessibilityRole="button"
                accessibilityLabel="Làm mới tóm tắt"
                disabled={isSubmitting}
                onPress={handleRefreshSavedSummary}
                style={[styles.refreshButton, { backgroundColor: colors.warning[600] }]}>
                <Text style={styles.refreshButtonText}>Làm mới ngay</Text>
              </Pressable>
            </View>
          )}

          {/* Results Presentation */}
          {result && (
            <View testID="progress-summary-result-container" style={styles.resultContainer}>
              {/* Header Badge */}
              <View style={styles.resultTypeHeader}>
                <View
                  style={[
                    styles.resultTypeBadge,
                    {
                      backgroundColor:
                        result.resultType === 'INFORMATION'
                          ? colors.brand[50]
                          : result.resultType === 'INSUFFICIENT_DATA'
                          ? theme.warningSurface
                          : theme.dangerSurface,
                      borderColor:
                        result.resultType === 'INFORMATION'
                          ? colors.brand[300]
                          : result.resultType === 'INSUFFICIENT_DATA'
                          ? colors.warning[600]
                          : theme.dangerText,
                    },
                  ]}>
                  <Text
                    style={[
                      styles.resultTypeBadgeText,
                      {
                        color:
                          result.resultType === 'INFORMATION'
                            ? colors.brand[700]
                            : result.resultType === 'INSUFFICIENT_DATA'
                            ? theme.warningText
                            : theme.dangerText,
                      },
                    ]}>
                    {result.resultType === 'INFORMATION'
                      ? 'THÔNG TIN TIẾN ĐỘ'
                      : result.resultType === 'INSUFFICIENT_DATA'
                      ? 'CHƯA ĐỦ DỮ LIỆU ĐỂ KẾT LUẬN'
                      : 'BỊ CHẶN BỞI CHÍNH SÁCH'}
                  </Text>
                </View>
              </View>

              {/* Prolonged Inactivity / Safety Alert Card (strictly from continuityEvaluation without ungrounded load adjustment advice) */}
              {result.continuityEvaluation?.hasSafetyAlert && (
                <View
                  testID="continuity-safety-alert"
                  accessibilityRole="alert"
                  style={[
                    styles.alertCard,
                    { backgroundColor: theme.warningSurface, borderColor: colors.warning[600] },
                  ]}>
                  <Text style={[styles.alertCardTitle, { color: theme.warningText }]}>
                    ⚠️ CẢNH BÁO AN TOÀN KHI TRỞ LẠI TẬP LUYỆN
                  </Text>
                  <Text style={[styles.alertCardMessage, { color: theme.warningText }]}>
                    {result.continuityEvaluation.message}
                  </Text>
                </View>
              )}

              {/* Overall Summary Card */}
              <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>Tóm tắt tổng quan</Text>
                <Text testID="progress-overall-summary" style={[styles.summaryBody, { color: theme.textPrimary }]}>
                  {result.summary}
                </Text>
              </View>

              {/* Continuity State Card */}
              {result.continuityEvaluation && (
                <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                  <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>
                    Tính liền mạch tập luyện
                  </Text>
                  <View style={styles.continuityRow}>
                    <Text style={[styles.label, { color: theme.textSecondary }]}>Trạng thái:</Text>
                    <Text testID="continuity-state-label" style={[styles.value, { color: theme.primary }]}>
                      {CONTINUITY_STATE_LABELS[result.continuityEvaluation.state] ||
                        result.continuityEvaluation.state}
                    </Text>
                  </View>
                  <Text style={[styles.bodySmall, { color: theme.textSecondary }]}>
                    {result.continuityEvaluation.message}
                  </Text>
                </View>
              )}

              {/* Workout Progress Section */}
              <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>Tiến độ tập luyện</Text>
                {result.workoutProgressSummary ? (
                  <View testID="workout-progress-section" style={styles.evaluationsList}>
                    {result.workoutProgressSummary.completionEvaluation && (
                      <View style={styles.evalItem}>
                        <Text style={[styles.evalLabel, { color: theme.textPrimary }]}>
                          Hoàn thành buổi tập:
                        </Text>
                        <Text style={[styles.evalText, { color: theme.textSecondary }]}>
                          {result.workoutProgressSummary.completionEvaluation}
                        </Text>
                      </View>
                    )}
                    {result.workoutProgressSummary.adherenceEvaluation && (
                      <View style={styles.evalItem}>
                        <Text style={[styles.evalLabel, { color: theme.textPrimary }]}>
                          Tuân thủ lịch tập:
                        </Text>
                        <Text style={[styles.evalText, { color: theme.textSecondary }]}>
                          {result.workoutProgressSummary.adherenceEvaluation}
                        </Text>
                      </View>
                    )}
                    {result.workoutProgressSummary.volumeEvaluation && (
                      <View style={styles.evalItem}>
                        <Text style={[styles.evalLabel, { color: theme.textPrimary }]}>
                          Khối lượng & cường độ:
                        </Text>
                        <Text style={[styles.evalText, { color: theme.textSecondary }]}>
                          {result.workoutProgressSummary.volumeEvaluation}
                        </Text>
                      </View>
                    )}
                  </View>
                ) : (
                  <View
                    testID="workout-restricted-notice"
                    style={[styles.noticeBox, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.noticeText, { color: theme.textSecondary }]}>
                      Dữ liệu buổi tập không khả dụng hoặc bị hạn chế quyền truy cập theo chính sách phân quyền.
                    </Text>
                  </View>
                )}
              </View>

              {/* Measurement Progress Section */}
              <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>Số đo thể chất</Text>
                {result.measurementProgressSummary ? (
                  result.measurementProgressSummary.metricsEvaluations &&
                  result.measurementProgressSummary.metricsEvaluations.length > 0 ? (
                    <View testID="measurement-progress-section" style={styles.evaluationsList}>
                      {result.measurementProgressSummary.metricsEvaluations.map((metric) => {
                        const trend =
                          TREND_DIRECTION_LABELS[metric.trendDirection] || {
                            label: metric.trendDirection,
                            symbol: '•',
                          };
                        const metricLabel =
                          METRIC_LABELS[metric.metricCode] ||
                          METRIC_OPTIONS.find((m) => m.code === metric.metricCode)?.label ||
                          metric.metricCode;
                        return (
                          <View key={metric.metricCode} style={styles.metricItemRow}>
                            <View style={styles.metricHeaderRow}>
                              <Text style={[styles.metricCodeText, { color: theme.textPrimary }]}>
                                {metricLabel}
                              </Text>
                              <View
                                style={[
                                  styles.trendBadge,
                                  {
                                    backgroundColor:
                                      metric.trendDirection === 'STABLE'
                                        ? colors.neutral[100]
                                        : colors.brand[50],
                                  },
                                ]}>
                                <Text style={[styles.trendBadgeText, { color: theme.primary }]}>
                                  {trend.symbol} {trend.label}
                                </Text>
                              </View>
                            </View>
                            <Text style={[styles.metricMessageText, { color: theme.textSecondary }]}>
                              {metric.message}
                            </Text>
                          </View>
                        );
                      })}
                    </View>
                  ) : (
                    <View
                      testID="measurement-no-data-notice"
                      style={[styles.noticeBox, { backgroundColor: theme.surfaceSubtle }]}>
                      <Text style={[styles.noticeText, { color: theme.textSecondary }]}>
                        Chưa có dữ liệu số đo thể chất trong khoảng thời gian đã chọn.
                      </Text>
                    </View>
                  )
                ) : (
                  <View
                    testID="measurement-restricted-notice"
                    style={[styles.noticeBox, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.noticeText, { color: theme.textSecondary }]}>
                      Dữ liệu số đo thể chất bị hạn chế quyền truy cập theo chính sách chia sẻ.
                    </Text>
                  </View>
                )}
              </View>

              {/* Limitations & Missing Data */}
              {Boolean(
                (result.limitations && result.limitations.length > 0) ||
                  (result.missingData && result.missingData.length > 0)
              ) && (
                <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                  <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>
                    Giới hạn và dữ liệu khuyết thiếu
                  </Text>
                  {result.limitations && result.limitations.length > 0 && (
                    <View style={styles.limitationsList}>
                      <Text style={[styles.evalLabel, { color: theme.textPrimary }]}>Giới hạn diễn giải:</Text>
                      {result.limitations.map((lim, idx) => (
                        <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                          • {lim}
                        </Text>
                      ))}
                    </View>
                  )}
                  {result.missingData && result.missingData.length > 0 && (
                    <View style={[styles.limitationsList, { marginTop: spacing.xs }]}>
                      <Text style={[styles.evalLabel, { color: theme.textPrimary }]}>Dữ liệu chưa có:</Text>
                      {result.missingData.map((miss, idx) => (
                        <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                          • {miss}
                        </Text>
                      ))}
                    </View>
                  )}
                </View>
              )}

              {/* Metadata Footer */}
              <View style={styles.footerMetadata}>
                <Text style={[styles.footerText, { color: theme.textSecondary }]}>
                  Thời điểm tổng hợp: {moment(result.asOf).format('DD/MM/YYYY HH:mm')}
                </Text>
                <Text style={[styles.footerText, { color: theme.textSecondary }]}>
                  Mã phiên hỗ trợ: {result.runId}
                </Text>
              </View>
            </View>
          )}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  keyboardContainer: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: layout.mobileScreenPadding,
    paddingVertical: spacing.sm,
    borderBottomWidth: 1,
  },
  backButton: {
    paddingVertical: spacing.xs,
    paddingRight: spacing.sm,
  },
  backButtonText: {
    ...typography.body,
    fontWeight: '600',
  },
  headerTitle: {
    ...typography.h3,
    flex: 1,
    textAlign: 'center',
  },
  headerSpacer: {
    width: 60,
  },
  scrollContent: {
    padding: layout.mobileScreenPadding,
    gap: spacing.lg,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.sm,
  },
  cardTitle: {
    ...typography.h3,
  },
  cardSubtitle: {
    ...typography.bodySmall,
  },
  presetsRow: {
    flexDirection: 'row',
    gap: spacing.xs,
    flexWrap: 'wrap',
    marginTop: spacing.xs,
  },
  presetButton: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.full,
    borderWidth: 1,
  },
  presetButtonText: {
    ...typography.bodySmall,
    fontWeight: '600',
  },
  dateInputsRow: {
    flexDirection: 'row',
    gap: spacing.md,
    marginTop: spacing.xs,
  },
  dateInputCol: {
    flex: 1,
  },
  inputLabel: {
    ...typography.caption,
    marginBottom: spacing.xxs,
  },
  dateInput: {
    ...typography.body,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  metricsSection: {
    marginTop: spacing.xs,
  },
  metricsChipsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginTop: spacing.xxs,
  },
  metricChip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.full,
    borderWidth: 1,
  },
  metricChipText: {
    ...typography.caption,
    fontWeight: '600',
  },
  validationText: {
    ...typography.caption,
    fontWeight: '600',
    marginTop: spacing.xxs,
  },
  primaryButton: {
    borderRadius: radius.md,
    paddingVertical: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
    marginTop: spacing.sm,
  },
  primaryButtonText: {
    ...typography.body,
    fontWeight: '700',
  },
  buttonBusyRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  loadingContainer: {
    alignItems: 'center',
    justifyContent: 'center',
    paddingVertical: spacing.xl,
    gap: spacing.sm,
  },
  loadingText: {
    ...typography.bodySmall,
  },
  errorCard: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.xs,
  },
  errorTitle: {
    ...typography.h3,
    fontSize: 16,
  },
  errorMessage: {
    ...typography.bodySmall,
  },
  retryButton: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
    alignSelf: 'flex-start',
    marginTop: spacing.xs,
  },
  retryButtonText: {
    ...typography.caption,
    fontWeight: '700',
  },
  warningBanner: {
    borderRadius: radius.lg,
    padding: spacing.md,
    borderWidth: 1,
    gap: spacing.sm,
  },
  warningContent: {
    gap: spacing.xxs,
  },
  warningTitle: {
    ...typography.body,
    fontWeight: '700',
  },
  warningMessage: {
    ...typography.bodySmall,
  },
  refreshButton: {
    alignSelf: 'flex-start',
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.md,
  },
  refreshButtonText: {
    ...typography.caption,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  resultContainer: {
    gap: spacing.lg,
  },
  resultTypeHeader: {
    flexDirection: 'row',
  },
  resultTypeBadge: {
    borderWidth: 1,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.full,
  },
  resultTypeBadgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  alertCard: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.xs,
  },
  alertCardTitle: {
    ...typography.body,
    fontWeight: '700',
  },
  alertCardMessage: {
    ...typography.bodySmall,
    lineHeight: 20,
  },
  summaryBody: {
    ...typography.body,
    lineHeight: 22,
  },
  continuityRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
  },
  label: {
    ...typography.caption,
    fontWeight: '600',
  },
  value: {
    ...typography.body,
    fontWeight: '700',
  },
  bodySmall: {
    ...typography.bodySmall,
    lineHeight: 18,
  },
  evaluationsList: {
    gap: spacing.sm,
  },
  evalItem: {
    gap: spacing.xxs,
  },
  evalLabel: {
    ...typography.caption,
    fontWeight: '700',
  },
  evalText: {
    ...typography.bodySmall,
    lineHeight: 18,
  },
  noticeBox: {
    padding: spacing.md,
    borderRadius: radius.md,
  },
  noticeText: {
    ...typography.bodySmall,
    fontStyle: 'italic',
  },
  metricItemRow: {
    borderBottomWidth: 1,
    borderBottomColor: colors.neutral[200],
    paddingBottom: spacing.sm,
    gap: spacing.xxs,
  },
  metricHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  metricCodeText: {
    ...typography.body,
    fontWeight: '600',
  },
  trendBadge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.full,
  },
  trendBadgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  metricMessageText: {
    ...typography.bodySmall,
  },
  limitationsList: {
    gap: spacing.xxs,
  },
  bulletItem: {
    ...typography.bodySmall,
    lineHeight: 18,
  },
  footerMetadata: {
    paddingVertical: spacing.sm,
    gap: spacing.xxs,
  },
  footerText: {
    ...typography.caption,
    fontSize: 11,
  },
});
