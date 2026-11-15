import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  FlatList,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { AddMeasurementModal } from '@/features/measurements/components/AddMeasurementModal';
import { CorrectMeasurementModal } from '@/features/measurements/components/CorrectMeasurementModal';
import { measurementApi } from '@/services/measurementApi';
import {
  CorrectMeasurementRequest,
  Measurement,
  MetricDefinition,
  RecordMeasurementRequest,
} from '@/types/measurement';

interface MeasurementsScreenProps {
  studentId?: string;
  readOnly?: boolean;
}

export function MeasurementsScreen({ studentId, readOnly = false }: MeasurementsScreenProps) {
  const colorScheme = useColorScheme();
  const isDark = colorScheme === 'dark';
  const themeColors = getSemanticColors(isDark);

  const [metrics, setMetrics] = useState<MetricDefinition[]>([]);
  const [selectedMetricCode, setSelectedMetricCode] = useState<string | null>(null);
  const [measurements, setMeasurements] = useState<Measurement[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);

  const [isAddModalOpen, setIsAddModalOpen] = useState(false);
  const [isSubmittingAdd, setIsSubmittingAdd] = useState(false);

  const [editingMeasurement, setEditingMeasurement] = useState<Measurement | null>(null);
  const [isSubmittingCorrect, setIsSubmittingCorrect] = useState(false);

  const loadData = useCallback(() => {
    let active = true;
    Promise.all([
      measurementApi.getMetricDefinitions(),
      measurementApi.getMeasurements({
        studentId,
        metricCode: selectedMetricCode || undefined,
        size: 50,
        sort: 'desc',
      }),
    ])
      .then(([metricDefs, history]) => {
        if (!active) return;
        setErrorMessage(null);
        setMetrics(metricDefs);
        setMeasurements(history.content);
      })
      .catch((err: any) => {
        if (!active) return;
        setErrorMessage(err?.message || 'Không thể tải dữ liệu số đo');
      })
      .finally(() => {
        if (!active) return;
        setIsLoading(false);
        setIsRefreshing(false);
      });

    return () => {
      active = false;
    };
  }, [studentId, selectedMetricCode]);

  useEffect(() => {
    return loadData();
  }, [loadData]);

  const handleRefresh = () => {
    setIsRefreshing(true);
    loadData();
  };

  const handleAddMeasurement = async (request: RecordMeasurementRequest) => {
    setIsSubmittingAdd(true);
    try {
      await measurementApi.recordMeasurement({
        ...request,
        studentId: studentId || request.studentId,
      });
      await loadData();
    } finally {
      setIsSubmittingAdd(false);
    }
  };

  const handleCorrectMeasurement = async (id: string, request: CorrectMeasurementRequest) => {
    setIsSubmittingCorrect(true);
    try {
      await measurementApi.correctMeasurement(id, request);
      await loadData();
    } finally {
      setIsSubmittingCorrect(false);
    }
  };

  const renderStatusBadge = (status: string) => {
    let bg = themeColors.surfaceSubtle;
    let text = '#6B7280';
    let label = status;

    if (status === 'VALID') {
      bg = '#DEF7EC';
      text = '#03543F';
      label = 'Hợp lệ';
    } else if (status === 'SUSPECT') {
      bg = '#FEF08A';
      text = '#854D0E';
      label = 'Cần kiểm tra (Nghi ngờ)';
    } else if (status === 'SUPERSEDED') {
      bg = '#F3F4F6';
      text = '#9CA3AF';
      label = 'Đã sửa';
    }

    return (
      <View style={[styles.statusBadge, { backgroundColor: bg }]}>
        <Text style={[styles.statusBadgeText, { color: text }]}>{label}</Text>
      </View>
    );
  };

  const renderMeasurementItem = ({ item }: { item: Measurement }) => {
    const isSuperseded = item.validationStatus === 'SUPERSEDED';

    return (
      <View
        testID={`measurement-item-${item.id}`}
        style={[
          styles.itemCard,
          {
            backgroundColor: themeColors.surface,
            borderColor: themeColors.border,
            opacity: isSuperseded ? 0.6 : 1,
          },
        ]}>
        <View style={styles.itemHeader}>
          <Text style={[styles.metricTitle, { color: themeColors.textPrimary }]}>
            {item.metricDisplayName}
          </Text>
          {renderStatusBadge(item.validationStatus)}
        </View>

        <View style={styles.valueRow}>
          <Text
            style={[
              styles.itemValue,
              {
                color: themeColors.textPrimary,
                textDecorationLine: isSuperseded ? 'line-through' : 'none',
              },
            ]}>
            {item.numericValue !== null && item.numericValue !== undefined
              ? `${item.numericValue} ${item.unitSymbol || item.unitCode || ''}`
              : item.textValue || '—'}
          </Text>

          {!readOnly && !isSuperseded && (
            <Pressable
              testID={`btn-correct-${item.id}`}
              onPress={() => setEditingMeasurement(item)}
              style={[styles.btnCorrect, { borderColor: themeColors.border }]}>
              <Text style={[styles.btnCorrectText, { color: themeColors.primary }]}>Sửa</Text>
            </Pressable>
          )}
        </View>

        <View style={styles.metaRow}>
          <Text style={[styles.metaText, { color: themeColors.textSecondary }]}>
            {new Date(item.measuredAt).toLocaleDateString('vi-VN')} {new Date(item.measuredAt).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' })}
          </Text>
          {item.notes ? (
            <Text style={[styles.notesText, { color: themeColors.textSecondary }]} numberOfLines={1}>
              {item.notes}
            </Text>
          ) : null}
        </View>
      </View>
    );
  };

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      <View style={styles.header}>
        <Text style={[styles.screenTitle, { color: themeColors.textPrimary }]}>Nhật ký số đo</Text>
        {!readOnly && (
          <Pressable
            testID="add-measurement-btn"
            accessibilityRole="button"
            accessibilityLabel="Nhập số đo cơ thể"
            onPress={() => setIsAddModalOpen(true)}
            style={[styles.btnAdd, { backgroundColor: themeColors.primary }]}>
            <Text style={styles.btnAddText}>+ Nhập số đo</Text>
          </Pressable>
        )}
      </View>
      {/* Header & Metric filter chips */}
      <View style={[styles.topBar, { borderBottomColor: themeColors.border }]}>
        <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.filterScroll}>
          <Pressable
            testID="filter-all"
            onPress={() => setSelectedMetricCode(null)}
            style={[
              styles.filterChip,
              {
                backgroundColor: selectedMetricCode === null ? themeColors.primary : themeColors.surface,
                borderColor: selectedMetricCode === null ? themeColors.primary : themeColors.border,
              },
            ]}>
            <Text
              style={[
                styles.filterChipText,
                { color: selectedMetricCode === null ? '#FFFFFF' : themeColors.textPrimary },
              ]}>
              Tất cả
            </Text>
          </Pressable>

          {metrics.map(m => {
            const isSelected = selectedMetricCode === m.code;
            return (
              <Pressable
                key={m.code}
                testID={`filter-${m.code}`}
                onPress={() => setSelectedMetricCode(m.code)}
                style={[
                  styles.filterChip,
                  {
                    backgroundColor: isSelected ? themeColors.primary : themeColors.surface,
                    borderColor: isSelected ? themeColors.primary : themeColors.border,
                  },
                ]}>
                <Text
                  style={[
                    styles.filterChipText,
                    { color: isSelected ? '#FFFFFF' : themeColors.textPrimary },
                  ]}>
                  {m.displayName}
                </Text>
              </Pressable>
            );
          })}
        </ScrollView>

      </View>

      {/* Main Content Area */}
      {isLoading ? (
        <View testID="measurements-loading" style={styles.centerContainer}>
          <ActivityIndicator size="large" color={themeColors.primary} />
          <Text style={[styles.centerText, { color: themeColors.textSecondary }]}>Đang tải số đo...</Text>
        </View>
      ) : errorMessage ? (
        <View testID="measurements-error" style={styles.centerContainer}>
          <Text style={[styles.errorTitle, { color: themeColors.dangerText }]}>{errorMessage}</Text>
          <Pressable
            testID="measurements-retry-btn"
            onPress={() => {
              setIsLoading(true);
              loadData();
            }}
            style={[styles.btnRetry, { backgroundColor: themeColors.primary }]}>
            <Text style={styles.btnRetryText}>Thử lại</Text>
          </Pressable>
        </View>
      ) : measurements.length === 0 ? (
        <View testID="measurements-empty" style={styles.centerContainer}>
          <Text style={[styles.emptyTitle, { color: themeColors.textPrimary }]}>Chưa có dữ liệu số đo</Text>
          <Text style={[styles.centerText, { color: themeColors.textSecondary }]}>
            Hãy ghi nhận số đo đầu tiên để theo dõi tiến độ cơ thể của bạn.
          </Text>
        </View>
      ) : (
        <FlatList
          data={measurements}
          keyExtractor={item => item.id}
          renderItem={renderMeasurementItem}
          contentContainerStyle={styles.listContent}
          refreshControl={
            <RefreshControl
              refreshing={isRefreshing}
              onRefresh={handleRefresh}
              colors={[themeColors.primary]}
              tintColor={themeColors.primary}
            />
          }
        />
      )}

      {/* Modals */}
      <AddMeasurementModal
        visible={isAddModalOpen}
        metrics={metrics}
        isSubmitting={isSubmittingAdd}
        isDark={isDark}
        onClose={() => setIsAddModalOpen(false)}
        onSubmit={handleAddMeasurement}
      />

      <CorrectMeasurementModal
        visible={editingMeasurement !== null}
        measurement={editingMeasurement}
        isSubmitting={isSubmittingCorrect}
        isDark={isDark}
        onClose={() => setEditingMeasurement(null)}
        onSubmit={handleCorrectMeasurement}
      />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  screenTitle: {
    ...typography.h3,
    fontWeight: '700',
  },
  topBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderBottomWidth: 1,
  },
  filterScroll: {
    flex: 1,
    marginRight: spacing.sm,
  },
  filterChip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.full,
    borderWidth: 1,
    marginRight: spacing.xs,
  },
  filterChipText: {
    ...typography.caption,
    fontWeight: '600',
  },
  btnAdd: {
    minHeight: 44,
    flexShrink: 0,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  btnAddText: {
    ...typography.label,
    color: '#FFFFFF',
    fontWeight: '700',
  },
  listContent: {
    padding: spacing.md,
  },
  itemCard: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.md,
    marginBottom: spacing.sm,
  },
  itemHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.xs,
  },
  metricTitle: {
    ...typography.h3,
    fontWeight: '600',
  },
  statusBadge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
    borderRadius: radius.full,
  },
  statusBadgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  valueRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginVertical: spacing.xs,
  },
  itemValue: {
    ...typography.h1,
    fontWeight: '700',
  },
  btnCorrect: {
    borderWidth: 1,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
    borderRadius: radius.sm,
  },
  btnCorrectText: {
    ...typography.label,
    fontWeight: '600',
  },
  metaRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginTop: spacing.xs,
  },
  metaText: {
    ...typography.bodySmall,
  },
  notesText: {
    ...typography.bodySmall,
    maxWidth: '50%',
    fontStyle: 'italic',
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
  emptyTitle: {
    ...typography.h2,
    fontWeight: '700',
    marginBottom: spacing.xs,
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
