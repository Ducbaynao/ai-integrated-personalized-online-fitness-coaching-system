import { SafeAreaView } from 'react-native-safe-area-context';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import React, { useCallback, useEffect, useState } from 'react';
import {
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { router } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { fontFamily, typography } from '@/design-system/tokens/typography';
import { UploadProgressPhotoModal } from '@/features/progress/components/UploadProgressPhotoModal';
import { apiErrorMessage, goalStatusLabels } from '@/localization/displayLabels';
import { formatContextFreeInstant } from '@/localization/instantDisplay';
import {
  CONTINUITY_SECTION_TITLE,
  continuityExplanation,
  continuityStateLabel,
  daysSinceLastWorkoutText,
  metricLabel,
  photoViewLabel,
  sectionNotice,
  trendLabel,
  unitLabel,
  withUnit,
} from '@/localization/progressLabels';
import { measurementApi } from '@/services/measurementApi';
import { progressDashboardApi } from '@/services/progressDashboardApi';
import {
  MetricDelta,
  ProgressDashboardSummary,
  ProgressPhoto,
  ProgressScope,
  UploadProgressPhotoParams,
} from '@/types/measurement';

interface ProgressDashboardScreenProps {
  studentId?: string;
  readOnly?: boolean;
}

export function ProgressDashboardScreen({ studentId, readOnly = false }: ProgressDashboardScreenProps) {
  const colorScheme = useColorScheme();
  const isDark = colorScheme === 'dark';
  const themeColors = getSemanticColors(isDark);

  const [scope, setScope] = useState<ProgressScope>('CURRENT_GOAL');
  const [summary, setSummary] = useState<ProgressDashboardSummary | null>(null);
  const [photos, setPhotos] = useState<ProgressPhoto[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [isUploadModalOpen, setIsUploadModalOpen] = useState(false);
  const [isUploadingPhoto, setIsUploadingPhoto] = useState(false);

  const loadData = useCallback(() => {
    let active = true;
    Promise.all([
      progressDashboardApi.getSummary({
        studentId,
        scope,
      }),
      measurementApi.listProgressPhotos({
        studentId,
        size: 20,
      }),
    ])
      .then(([sum, photoPage]) => {
        if (!active) return;
        setErrorMessage(null);
        setSummary(sum);
        setPhotos(photoPage.content);
      })
      .catch((err: any) => {
        if (!active) return;
        setErrorMessage(apiErrorMessage(err, 'Không thể tải bảng tiến độ. Vui lòng thử lại.'));
      })
      .finally(() => {
        if (!active) return;
        setIsLoading(false);
        setIsRefreshing(false);
      });

    return () => {
      active = false;
    };
  }, [studentId, scope]);

  useEffect(() => {
    return loadData();
  }, [loadData]);

  const handleRefresh = () => {
    setIsRefreshing(true);
    loadData();
  };

  const handleUploadPhoto = async (params: UploadProgressPhotoParams) => {
    setIsUploadingPhoto(true);
    try {
      await measurementApi.uploadProgressPhoto({
        ...params,
        studentId: studentId || params.studentId,
      });
      await loadData();
    } finally {
      setIsUploadingPhoto(false);
    }
  };

  const handleDeletePhoto = async (photoId: string) => {
    try {
      await measurementApi.deleteProgressPhoto(photoId);
      setPhotos(prev => prev.filter(p => p.id !== photoId));
    } catch (err: any) {
      alert(apiErrorMessage(err, 'Không thể xóa ảnh. Vui lòng thử lại.'));
    }
  };

  const renderTrendChip = (direction?: string) => {
    const trend = trendLabel(direction);
    let color = '#4B5563';
    let bg = '#F3F4F6';

    if (trend.tone === 'up') {
      color = '#1D4ED8';
      bg = '#EFF6FF';
    } else if (trend.tone === 'down') {
      color = '#047857';
      bg = '#ECFDF5';
    } else if (trend.tone === 'unknown') {
      color = '#6B7280';
      bg = '#F9FAFB';
    }

    return (
      <View style={[styles.trendChip, { backgroundColor: bg }]}>
        <Text style={[styles.trendText, { color }]}>
          {trend.tone === 'unknown' ? trend.label : `${trend.symbol} ${trend.label}`}
        </Text>
      </View>
    );
  };

  const formatValue = (value?: number | null) => (value !== null && value !== undefined ? `${value}` : '—');
  /** Missing stays "—" without a unit; a present value (including 0) gets its unit without a dangling space. */
  const formatWithUnit = (value: number | null | undefined, unitCode?: string | null, metricCode?: string | null) =>
    value !== null && value !== undefined ? withUnit(value, unitCode, { metricCode }) : '—';
  const measurementNotice = summary
    ? sectionNotice('measurement', summary.measurement.availability, (summary.measurement.metricDeltas?.length ?? 0) > 0)
    : null;
  const workoutNotice = summary ? sectionNotice('workout', summary.workout.availability, true) : null;
  const daysSinceText = summary ? daysSinceLastWorkoutText(summary.continuity.daysSinceLastWorkout) : null;

  return (
    <SafeAreaView edges={['top']} style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      <View style={{ flexDirection: 'row', gap: spacing.md, alignItems: 'center', padding: spacing.lg }}>
        <AppIcon name="progress" color={themeColors.primary} /><Text accessibilityRole="header" style={[typography.h1, { color: themeColors.textPrimary, flex: 1 }]}>Tiến độ</Text>
      </View>
      {/* Scope Selector Bar */}
      <View style={[styles.scopeBar, { borderBottomColor: themeColors.border }]}>
        <Pressable
          testID="scope-tab-current-goal" accessibilityRole="tab" accessibilityState={{ selected: scope === 'CURRENT_GOAL' }}
          onPress={() => setScope('CURRENT_GOAL')}
          style={[
            styles.scopeTab,
            scope === 'CURRENT_GOAL' && [styles.activeScopeTab, { borderBottomColor: themeColors.primary }],
          ]}>
          <Text
            style={[
              styles.scopeTabText,
              { color: scope === 'CURRENT_GOAL' ? themeColors.primary : themeColors.textSecondary },
            ]}>
            Mục tiêu hiện tại
          </Text>
        </Pressable>

        <Pressable
          testID="scope-tab-lifetime" accessibilityRole="tab" accessibilityState={{ selected: scope === 'LIFETIME' }}
          onPress={() => setScope('LIFETIME')}
          style={[
            styles.scopeTab,
            scope === 'LIFETIME' && [styles.activeScopeTab, { borderBottomColor: themeColors.primary }],
          ]}>
          <Text
            style={[
              styles.scopeTabText,
              { color: scope === 'LIFETIME' ? themeColors.primary : themeColors.textSecondary },
            ]}>
            Toàn bộ thời gian
          </Text>
        </Pressable>
      </View>

      {/* Main Content Area */}
      {isLoading ? (
        <View testID="progress-loading" style={styles.centerContainer}>
          <AppCard state="loading" title="Đang phân tích tiến độ..." />
        </View>
      ) : errorMessage ? (
        <View testID="progress-error" style={styles.centerContainer}>
          <AppCard state="error" title={errorMessage}>
          <AppButton testID="progress-retry-btn" onPress={() => {
              setIsLoading(true);
              loadData();
            }} label="Thử lại" icon="refresh" variant="secondary" />
          </AppCard>
        </View>
      ) : summary ? (
        <ScrollView
          contentContainerStyle={styles.scrollContent}
          refreshControl={
            <RefreshControl
              refreshing={isRefreshing}
              onRefresh={handleRefresh}
              colors={[themeColors.primary]}
              tintColor={themeColors.primary}
            />
          }>
          {/* Goal Info Card (when in CURRENT_GOAL scope) */}
          {scope === 'CURRENT_GOAL' && summary.goal && (
            <AppCard
              testID="current-goal-card"
              style={{ marginBottom: spacing.md }}>
              <View style={styles.cardHeader}>
                <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>{summary.goal.title}</Text>
                <View style={[styles.badge, { backgroundColor: '#DEF7EC' }]}>
                  <Text style={[styles.badgeText, { color: '#03543F' }]}>
                    {(goalStatusLabels as Record<string, string>)[summary.goal.status] ?? 'Chưa xác định trạng thái'}
                  </Text>
                </View>
              </View>

              <Text style={[styles.dateRangeText, { color: themeColors.textSecondary }]}>
                {summary.goal.startDate} → {summary.goal.targetDate || 'Đang thực hiện'} ({summary.goal.durationDays || '—'} ngày)
              </Text>

              {summary.goal.targets && summary.goal.targets.length > 0 && (
                <View style={styles.targetsContainer}>
                  <Text style={[styles.sectionSubtitle, { color: themeColors.textSecondary }]}>Chỉ tiêu mục tiêu:</Text>
                  {summary.goal.targets.map((tgt, i) => (
                    <Text key={i} style={[styles.targetItem, { color: themeColors.textPrimary }]}>
                      • {metricLabel(tgt.metricCode)}: {formatWithUnit(tgt.targetNumericValue, tgt.unitCode, tgt.metricCode)}
                    </Text>
                  ))}
                </View>
              )}
            </AppCard>
          )}

          {/* Measurements Progress & Deltas Card */}
          <AppCard
            testID="measurement-progress-card"
            style={{ marginBottom: spacing.md }}>
            <View style={styles.cardHeader}>
              <AppIcon name="measurement" color={themeColors.primary} /><Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Số đo cơ thể và thay đổi</Text>
              <AppButton testID="nav-to-measurements-btn" onPress={() => router.push('/(app)/measurements' as any)} label="Chi tiết số đo" icon="measurement" variant="secondary" />
            </View>

            {measurementNotice || !summary.measurement.metricDeltas ? (
              <View testID="measurement-section-notice" style={styles.emptyMetricBox}>
                <Text style={[styles.emptySubText, { color: themeColors.textSecondary }]}>
                  {measurementNotice}
                </Text>
              </View>
            ) : (
              <View style={styles.deltasTable}>
                {summary.measurement.metricDeltas.map((delta: MetricDelta, index: number) => {
                  return (
                    <View
                      key={index}
                      testID={`delta-row-${delta.metricCode}`}
                      style={[styles.deltaRow, { borderBottomColor: themeColors.border }]}>
                      <View style={styles.deltaLeft}>
                        <Text style={[styles.metricCodeText, { color: themeColors.textPrimary }]}>
                          {metricLabel(delta.metricCode)}
                        </Text>
                        <Text style={[styles.baselineLatestText, { color: themeColors.textSecondary }]}>
                          Ban đầu: {formatValue(delta.baselineValue)} → Hiện tại:{' '}
                          {formatWithUnit(delta.latestValue, delta.unitCode, delta.metricCode)}
                        </Text>
                      </View>

                      <View style={styles.deltaRight}>
                        {delta.deltaValue !== null && delta.deltaValue !== undefined ? (
                          <Text style={[styles.deltaValueText, { color: themeColors.textPrimary }]}>
                            {withUnit(delta.deltaValue > 0 ? `+${delta.deltaValue}` : `${delta.deltaValue}`, delta.unitCode, {
                              metricCode: delta.metricCode,
                            })}
                          </Text>
                        ) : (
                          <Text style={[styles.deltaValueText, { color: themeColors.textSecondary }]}>—</Text>
                        )}
                        {renderTrendChip(delta.trendDirection)}
                      </View>
                    </View>
                  );
                })}
              </View>
            )}
          </AppCard>

          {/* Workout Adherence & Volume Card */}
          <AppCard
            testID="workout-progress-card"
            style={{ marginBottom: spacing.md }}>
            <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Tuân thủ và khối lượng tập luyện</Text>

            {workoutNotice ? (
              <View testID="workout-section-notice" style={styles.emptyMetricBox}>
                <Text style={[styles.emptySubText, { color: themeColors.textSecondary }]}>{workoutNotice}</Text>
              </View>
            ) : (
              <View style={styles.statsGrid}>
                <View style={styles.statCol}>
                  <Text style={[styles.statValue, { color: themeColors.textPrimary }]}>
                    {summary.workout.adherence?.rawCompletionRate !== null &&
                    summary.workout.adherence?.rawCompletionRate !== undefined
                      ? `${(summary.workout.adherence.rawCompletionRate * 100).toFixed(0)}%`
                      : '—'}
                  </Text>
                  <Text style={[styles.statLabel, { color: themeColors.textSecondary }]}>Tỷ lệ hoàn thành</Text>
                </View>

                <View style={styles.statCol}>
                  <Text style={[styles.statValue, { color: themeColors.textPrimary }]}>
                    {summary.workout.volumeAndIntensity?.workingSetsCompleted ?? '—'}
                  </Text>
                  <Text style={[styles.statLabel, { color: themeColors.textSecondary }]}>Số hiệp chính</Text>
                </View>

                <View style={styles.statCol}>
                  <Text style={[styles.statValue, { color: themeColors.textPrimary }]}>
                    {summary.workout.volumeAndIntensity?.knownVolumeKg !== null &&
                    summary.workout.volumeAndIntensity?.knownVolumeKg !== undefined
                      ? `${summary.workout.volumeAndIntensity.knownVolumeKg.toLocaleString()} kg`
                      : '—'}
                  </Text>
                  <Text style={[styles.statLabel, { color: themeColors.textSecondary }]}>Tổng khối lượng</Text>
                </View>
              </View>
            )}
          </AppCard>

          {/* Training Continuity Card */}
          <AppCard
            testID="continuity-card"
            style={{ marginBottom: spacing.md }}>
            <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>{CONTINUITY_SECTION_TITLE}</Text>
            <View style={styles.continuityRow}>
              <View style={[styles.badge, { backgroundColor: '#EFF6FF' }]}>
                <Text testID="continuity-state-label" style={[styles.badgeText, { color: '#1E40AF' }]}>
                  {continuityStateLabel(summary.continuity.state)}
                </Text>
              </View>
              {daysSinceText ? (
                <Text style={[styles.daysSinceText, { color: themeColors.textSecondary }]}>{daysSinceText}</Text>
              ) : null}
            </View>
            <Text
              testID="continuity-explanation"
              style={[styles.continuityRationale, { color: themeColors.textSecondary }]}>
              {continuityExplanation(summary.continuity)}
            </Text>
          </AppCard>

          {/* Progress Photos Gallery */}
          <AppCard
            testID="photos-gallery-card"
            style={{ marginBottom: spacing.md }}>
            <View style={styles.cardHeader}>
              <AppIcon name="photo" color={themeColors.primary} /><Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Hình ảnh tiến độ</Text>
              {!readOnly && (
                <AppButton testID="upload-photo-btn" onPress={() => setIsUploadModalOpen(true)} label="Thêm ảnh" icon="plus" variant="secondary" />
              )}
            </View>

            {photos.length === 0 ? (
              <View style={styles.emptyMetricBox}>
                <Text style={[styles.emptySubText, { color: themeColors.textSecondary }]}>
                  Chưa có ảnh tiến độ nào được tải lên.
                </Text>
              </View>
            ) : (
              <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.photosScroll}>
                {photos.map(p => (
                  <View
                    key={p.id}
                    testID={`photo-card-${p.id}`}
                    style={[styles.photoCard, { backgroundColor: themeColors.surfaceSubtle, borderColor: themeColors.border }]}>
                    <View style={styles.photoPlaceholder}>
                      <Text style={[styles.photoViewTypeText, { color: themeColors.textSecondary }]}>
                        {photoViewLabel(p.viewType)}
                      </Text>
                      {p.weightAtTime ? (
                        <Text style={[styles.photoWeightText, { color: themeColors.textPrimary }]}>
                          {p.weightAtTime} {unitLabel(p.weightUnitCode) || 'kg'}
                        </Text>
                      ) : null}
                    </View>
                    <Text testID={`photo-taken-at-${p.id}`} style={[styles.photoDateText, { color: themeColors.textSecondary }]}>
                      {formatContextFreeInstant(p.takenAt) ?? 'Chưa rõ thời điểm chụp'}
                    </Text>
                    {!readOnly && (
                      <AppButton testID={`btn-delete-photo-${p.id}`} onPress={() => handleDeletePhoto(p.id)} label="Xóa" icon="trash" variant="danger" />
                    )}
                  </View>
                ))}
              </ScrollView>
            )}
          </AppCard>

          {/* AI Progress Summary banner (Optional F04 integration) */}
          <AppCard style={[styles.aiNoticeCard, { backgroundColor: themeColors.surfaceSubtle, borderColor: themeColors.border }]}>
            <Text style={[styles.aiNoticeText, { color: themeColors.textSecondary }]}>
              Số liệu trên do hệ thống tính từ dữ liệu đã ghi nhận. AI không thay đổi số liệu gốc.
            </Text>
            <AppButton testID="btn-open-ai-summary" onPress={() => router.push('/(app)/progress-summary' as any)} label="Xem tóm tắt phân tích bằng AI" icon="summary" variant="secondary" />
          </AppCard>
        </ScrollView>
      ) : null}

      <UploadProgressPhotoModal
        visible={isUploadModalOpen}
        isSubmitting={isUploadingPhoto}
        isDark={isDark}
        onClose={() => setIsUploadModalOpen(false)}
        onSubmit={handleUploadPhoto}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scopeBar: {
    flexDirection: 'row',
    borderBottomWidth: 1,
  },
  scopeTab: {
    minHeight: layout.minimumTouchTarget, paddingHorizontal: spacing.sm,
    flex: 1,
    paddingVertical: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
    borderBottomWidth: 2,
    borderBottomColor: 'transparent',
  },
  activeScopeTab: {},
  scopeTabText: {
    ...typography.label,
    fontFamily: fontFamily.bold,
  },
  scrollContent: {
    width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center',
    padding: spacing.md,
  },
  card: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.md,
    marginBottom: spacing.md,
  },
  cardHeader: {
    flexWrap: 'wrap', gap: spacing.sm,
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.sm,
  },
  cardTitle: {
    flexShrink: 1,
    ...typography.h3,
    fontFamily: fontFamily.bold,
  },
  linkText: {
    ...typography.label,
    fontFamily: fontFamily.semiBold,
  },
  badge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: radius.full,
  },
  badgeText: {
    ...typography.caption,
    fontFamily: fontFamily.bold,
  },
  dateRangeText: {
    ...typography.body,
    marginBottom: spacing.xs,
  },
  targetsContainer: {
    marginTop: spacing.xs,
  },
  sectionSubtitle: {
    ...typography.caption,
    fontFamily: fontFamily.semiBold,
    marginBottom: 2,
  },
  targetItem: {
    ...typography.bodySmall,
    marginLeft: spacing.xs,
  },
  emptyMetricBox: {
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
  emptySubText: {
    ...typography.body,
    fontFamily: fontFamily.regularItalic,
  },
  deltasTable: {
    marginTop: spacing.xs,
  },
  deltaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  deltaLeft: {
    flex: 1,
  },
  metricCodeText: {
    ...typography.body,
    fontFamily: fontFamily.semiBold,
  },
  baselineLatestText: {
    ...typography.bodySmall,
    marginTop: 2,
  },
  deltaRight: {
    alignItems: 'flex-end',
  },
  deltaValueText: {
    ...typography.h3,
    fontFamily: fontFamily.bold,
  },
  trendChip: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: radius.sm,
    marginTop: 2,
  },
  trendText: {
    ...typography.caption,
    fontFamily: fontFamily.bold,
  },
  statsGrid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    marginTop: spacing.sm,
  },
  statCol: {
    flex: 1,
    alignItems: 'center',
  },
  statValue: {
    ...typography.h2,
    fontFamily: fontFamily.bold,
  },
  statLabel: {
    ...typography.caption,
    textAlign: 'center',
    marginTop: 2,
  },
  continuityRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginVertical: spacing.xs,
  },
  daysSinceText: {
    ...typography.bodySmall,
  },
  continuityRationale: {
    ...typography.bodySmall,
    marginTop: spacing.xs,
    fontFamily: fontFamily.regularItalic,
  },
  photosScroll: {
    flexDirection: 'row',
    marginTop: spacing.xs,
  },
  photoCard: {
    width: 110,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.xs,
    marginRight: spacing.sm,
    alignItems: 'center',
  },
  photoPlaceholder: {
    width: '100%',
    height: 90,
    backgroundColor: '#E5E7EB',
    borderRadius: radius.sm,
    alignItems: 'center',
    justifyContent: 'center',
    marginBottom: spacing.xs,
  },
  photoViewTypeText: {
    ...typography.caption,
    fontFamily: fontFamily.bold,
  },
  photoWeightText: {
    ...typography.bodySmall,
    fontFamily: fontFamily.semiBold,
    marginTop: 2,
  },
  photoDateText: {
    ...typography.caption,
  },
  btnDeletePhoto: {
    marginTop: spacing.xs,
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
  },
  deletePhotoText: {
    ...typography.caption,
    color: '#EF4444',
  },
  aiNoticeCard: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.md,
    marginBottom: spacing.lg,
  },
  aiNoticeText: {
    ...typography.bodySmall,
    lineHeight: 18,
    marginBottom: spacing.sm,
  },
  btnAiSummary: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
    alignItems: 'center',
  },
  btnAiSummaryText: {
    ...typography.label,
    color: '#FFFFFF',
    fontFamily: fontFamily.bold,
  },
  centerContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
  },
  centerText: {
    ...typography.body,
    textAlign: 'center',
    marginTop: spacing.sm,
  },
  errorTitle: {
    ...typography.body,
    fontFamily: fontFamily.semiBold,
    textAlign: 'center',
    marginBottom: spacing.md,
  },
  btnRetry: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
  },
  btnRetryText: {
    ...typography.label,
    color: '#FFFFFF',
    fontFamily: fontFamily.bold,
  },
});
