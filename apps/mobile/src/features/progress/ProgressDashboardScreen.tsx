import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
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
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { UploadProgressPhotoModal } from '@/features/progress/components/UploadProgressPhotoModal';
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
        setErrorMessage(err?.message || 'Không thể tải bảng tiến độ');
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
      alert(err?.message || 'Không thể xóa ảnh');
    }
  };

  const renderTrendChip = (direction?: string) => {
    let text = 'Ổn định';
    let color = '#4B5563';
    let bg = '#F3F4F6';

    if (direction === 'INCREASED') {
      text = '▲ Tăng';
      color = '#1D4ED8';
      bg = '#EFF6FF';
    } else if (direction === 'DECREASED') {
      text = '▼ Giảm';
      color = '#047857';
      bg = '#ECFDF5';
    } else if (direction === 'INSUFFICIENT_DATA') {
      text = 'Chưa đủ dữ liệu';
      color = '#9CA3AF';
      bg = '#F9FAFB';
    }

    return (
      <View style={[styles.trendChip, { backgroundColor: bg }]}>
        <Text style={[styles.trendText, { color }]}>{text}</Text>
      </View>
    );
  };

  return (
    <View style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      {/* Scope Selector Bar */}
      <View style={[styles.scopeBar, { borderBottomColor: themeColors.border }]}>
        <Pressable
          testID="scope-tab-current-goal"
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
          testID="scope-tab-lifetime"
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
            Toàn thời gian (Lifetime)
          </Text>
        </Pressable>
      </View>

      {/* Main Content Area */}
      {isLoading ? (
        <View testID="progress-loading" style={styles.centerContainer}>
          <ActivityIndicator size="large" color={themeColors.primary} />
          <Text style={[styles.centerText, { color: themeColors.textSecondary }]}>Đang phân tích tiến độ...</Text>
        </View>
      ) : errorMessage ? (
        <View testID="progress-error" style={styles.centerContainer}>
          <Text style={[styles.errorTitle, { color: themeColors.dangerText }]}>{errorMessage}</Text>
          <Pressable
            testID="progress-retry-btn"
            onPress={() => {
              setIsLoading(true);
              loadData();
            }}
            style={[styles.btnRetry, { backgroundColor: themeColors.primary }]}>
            <Text style={styles.btnRetryText}>Thử lại</Text>
          </Pressable>
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
            <View
              testID="current-goal-card"
              style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
              <View style={styles.cardHeader}>
                <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>{summary.goal.title}</Text>
                <View style={[styles.badge, { backgroundColor: '#DEF7EC' }]}>
                  <Text style={[styles.badgeText, { color: '#03543F' }]}>{summary.goal.status}</Text>
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
                      • {tgt.metricCode}: {tgt.targetNumericValue} {tgt.unitCode || ''}
                    </Text>
                  ))}
                </View>
              )}
            </View>
          )}

          {/* Measurements Progress & Deltas Card */}
          <View
            testID="measurement-progress-card"
            style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
            <View style={styles.cardHeader}>
              <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Số đo cơ thể & Thay đổi</Text>
              <Pressable
                testID="nav-to-measurements-btn"
                onPress={() => router.push('/(app)/measurements' as any)}>
                <Text style={[styles.linkText, { color: themeColors.primary }]}>Chi tiết số đo →</Text>
              </Pressable>
            </View>

            {summary.measurement.availability === 'NO_DATA' ||
            !summary.measurement.metricDeltas ||
            summary.measurement.metricDeltas.length === 0 ? (
              <View style={styles.emptyMetricBox}>
                <Text style={[styles.emptySubText, { color: themeColors.textSecondary }]}>
                  Chưa có dữ liệu số đo trong giai đoạn này.
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
                          {delta.metricCode}
                        </Text>
                        <Text style={[styles.baselineLatestText, { color: themeColors.textSecondary }]}>
                          Ban đầu: {delta.baselineValue !== null && delta.baselineValue !== undefined ? delta.baselineValue : '—'} → Hiện tại:{' '}
                          {delta.latestValue !== null && delta.latestValue !== undefined ? delta.latestValue : '—'} {delta.unitCode || ''}
                        </Text>
                      </View>

                      <View style={styles.deltaRight}>
                        {delta.deltaValue !== null && delta.deltaValue !== undefined ? (
                          <Text style={[styles.deltaValueText, { color: themeColors.textPrimary }]}>
                            {delta.deltaValue > 0 ? `+${delta.deltaValue}` : `${delta.deltaValue}`} {delta.unitCode || ''}
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
          </View>

          {/* Workout Adherence & Volume Card */}
          <View
            testID="workout-progress-card"
            style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
            <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Tuân thủ & Khối lượng tập luyện</Text>

            {summary.workout.availability === 'NO_DATA' ? (
              <View style={styles.emptyMetricBox}>
                <Text style={[styles.emptySubText, { color: themeColors.textSecondary }]}>
                  Chưa có lịch tập hoặc buổi tập nào hoàn thành trong giai đoạn này.
                </Text>
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
                  <Text style={[styles.statLabel, { color: themeColors.textSecondary }]}>Số hiệp chính (Sets)</Text>
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
          </View>

          {/* Training Continuity Card */}
          <View
            testID="continuity-card"
            style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
            <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Tính liên tục tập luyện</Text>
            <View style={styles.continuityRow}>
              <View style={[styles.badge, { backgroundColor: '#EFF6FF' }]}>
                <Text style={[styles.badgeText, { color: '#1E40AF' }]}>{summary.continuity.state}</Text>
              </View>
              {summary.continuity.daysSinceLastWorkout !== null && summary.continuity.daysSinceLastWorkout !== undefined && (
                <Text style={[styles.daysSinceText, { color: themeColors.textSecondary }]}>
                  {summary.continuity.daysSinceLastWorkout} ngày từ buổi tập gần nhất
                </Text>
              )}
            </View>
            {summary.continuity.rationale ? (
              <Text style={[styles.continuityRationale, { color: themeColors.textSecondary }]}>
                {summary.continuity.rationale}
              </Text>
            ) : null}
          </View>

          {/* Progress Photos Gallery */}
          <View
            testID="photos-gallery-card"
            style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
            <View style={styles.cardHeader}>
              <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>Hình ảnh tiến độ</Text>
              {!readOnly && (
                <Pressable
                  testID="upload-photo-btn"
                  onPress={() => setIsUploadModalOpen(true)}>
                  <Text style={[styles.linkText, { color: themeColors.primary }]}>+ Thêm ảnh</Text>
                </Pressable>
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
                        {p.viewType}
                      </Text>
                      {p.weightAtTime ? (
                        <Text style={[styles.photoWeightText, { color: themeColors.textPrimary }]}>
                          {p.weightAtTime} {p.weightUnitCode || 'kg'}
                        </Text>
                      ) : null}
                    </View>
                    <Text style={[styles.photoDateText, { color: themeColors.textSecondary }]}>{p.takenAt}</Text>
                    {!readOnly && (
                      <Pressable
                        testID={`btn-delete-photo-${p.id}`}
                        onPress={() => handleDeletePhoto(p.id)}
                        style={styles.btnDeletePhoto}>
                        <Text style={styles.deletePhotoText}>Xóa</Text>
                      </Pressable>
                    )}
                  </View>
                ))}
              </ScrollView>
            )}
          </View>

          {/* AI Progress Summary banner (Optional F04 integration) */}
          <View style={[styles.aiNoticeCard, { backgroundColor: themeColors.surfaceSubtle, borderColor: themeColors.border }]}>
            <Text style={[styles.aiNoticeText, { color: themeColors.textSecondary }]}>
              ℹ️ Dữ liệu trên được trích xuất hoàn toàn tự động từ Progress Engine. AI không can thiệp thay đổi số liệu gốc.
            </Text>
            <Pressable
              testID="btn-open-ai-summary"
              onPress={() => router.push('/(app)/progress-summary' as any)}
              style={[styles.btnAiSummary, { backgroundColor: themeColors.primary }]}>
              <Text style={styles.btnAiSummaryText}>Xem tóm tắt phân tích bằng AI (F04) →</Text>
            </Pressable>
          </View>
        </ScrollView>
      ) : null}

      <UploadProgressPhotoModal
        visible={isUploadModalOpen}
        isSubmitting={isUploadingPhoto}
        isDark={isDark}
        onClose={() => setIsUploadModalOpen(false)}
        onSubmit={handleUploadPhoto}
      />
    </View>
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
    fontWeight: '700',
  },
  scrollContent: {
    padding: spacing.md,
  },
  card: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.md,
    marginBottom: spacing.md,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.sm,
  },
  cardTitle: {
    ...typography.h3,
    fontWeight: '700',
  },
  linkText: {
    ...typography.label,
    fontWeight: '600',
  },
  badge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: radius.full,
  },
  badgeText: {
    ...typography.caption,
    fontWeight: '700',
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
    fontWeight: '600',
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
    fontStyle: 'italic',
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
    fontWeight: '600',
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
    fontWeight: '700',
  },
  trendChip: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: radius.sm,
    marginTop: 2,
  },
  trendText: {
    ...typography.caption,
    fontWeight: '700',
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
    fontWeight: '700',
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
    fontStyle: 'italic',
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
    fontWeight: '700',
  },
  photoWeightText: {
    ...typography.bodySmall,
    fontWeight: '600',
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
    fontWeight: '700',
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
    fontWeight: '600',
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
    fontWeight: '700',
  },
});
