import React, { useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Text, View } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import { ExerciseCatalogFilters, ExerciseFilterMetadata } from '@/types/exercise';
import { EXERCISE_DIFFICULTY_LABELS } from '../exerciseMessages';

type FilterArrayKey = Exclude<keyof ExerciseCatalogFilters, 'query'>;

interface ExerciseFilterModalProps {
  visible: boolean;
  filters: ExerciseCatalogFilters;
  metadata?: ExerciseFilterMetadata;
  isLoading: boolean;
  isError: boolean;
  onRetryMetadata: () => void;
  onApply: (filters: ExerciseCatalogFilters) => void;
  onClose: () => void;
  isDark?: boolean;
}

export function ExerciseFilterModal({
  visible,
  filters,
  metadata,
  isLoading,
  isError,
  onRetryMetadata,
  onApply,
  onClose,
  isDark = false,
}: ExerciseFilterModalProps) {
  const colors = getSemanticColors(isDark);
  const [draft, setDraft] = useState<ExerciseCatalogFilters>(filters);

  const toggle = (key: FilterArrayKey, value: string) => {
    const current = (draft[key] ?? []) as readonly string[];
    const next = current.includes(value)
      ? current.filter((item) => item !== value)
      : [...current, value];
    setDraft((previous) => ({ ...previous, [key]: next }));
  };

  const renderSection = (
    title: string,
    key: FilterArrayKey,
    options: { code: string; name: string }[]
  ) => (
    <View style={styles.section}>
      <Text style={[styles.sectionTitle, { color: colors.textPrimary }]}>{title}</Text>
      <View style={styles.options}>
        {options.map((option) => {
          const selected = ((draft[key] ?? []) as readonly string[]).includes(option.code);
          return (
            <Pressable
              key={option.code}
              testID={`filter-option-${key}-${option.code}`}
              accessibilityRole="checkbox"
              accessibilityLabel={`${title}: ${option.name}`}
              accessibilityState={{ checked: selected }}
              onPress={() => toggle(key, option.code)}
              style={[
                styles.option,
                {
                  backgroundColor: selected ? colors.brandSoft : colors.surface,
                  borderColor: selected ? colors.primary : colors.border,
                },
              ]}>
              <Text style={[styles.optionText, { color: selected ? colors.primary : colors.textPrimary }]}>
                {option.name}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );

  return (
    <Modal visible={visible} animationType="slide" transparent onRequestClose={onClose}>
      <View style={styles.backdrop}>
        <View style={[styles.sheet, { backgroundColor: colors.canvas }]}>
          <View style={styles.header}>
            <Text accessibilityRole="header" style={[styles.title, { color: colors.textPrimary }]}>Bộ lọc bài tập</Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Đóng bộ lọc"
              accessibilityHint="Đóng mà không áp dụng các thay đổi"
              onPress={onClose}
              style={styles.closeButton}>
              <Text style={[styles.closeText, { color: colors.textPrimary }]}>Đóng</Text>
            </Pressable>
          </View>

          {isLoading && <Text style={[styles.message, { color: colors.textSecondary }]}>Đang tải bộ lọc…</Text>}
          {isError && !metadata && (
            <View style={styles.messageBlock}>
              <Text style={[styles.message, { color: colors.dangerText }]}>Không thể tải lựa chọn bộ lọc.</Text>
              <Pressable accessibilityRole="button" accessibilityLabel="Thử tải lại bộ lọc" accessibilityHint="Tải lại các lựa chọn bộ lọc" onPress={onRetryMetadata}>
                <Text style={[styles.link, { color: colors.primary }]}>Thử lại</Text>
              </Pressable>
            </View>
          )}

          {metadata && (
            <ScrollView contentContainerStyle={styles.scrollContent}>
              {renderSection('Danh mục', 'categoryCodes', metadata.categories)}
              {renderSection('Nhóm cơ', 'muscleGroupCodes', metadata.muscleGroups)}
              {renderSection('Thiết bị', 'equipmentCodes', metadata.equipment)}
              {renderSection('Nhãn', 'tagCodes', metadata.tags)}
              {renderSection(
                'Độ khó',
                'difficulties',
                metadata.difficulties.map((code) => ({ code, name: EXERCISE_DIFFICULTY_LABELS[code] }))
              )}
              {renderSection(
                'Kiểu chuyển động',
                'movementPatterns',
                metadata.movementPatterns.map((value) => ({ code: value, name: value }))
              )}
            </ScrollView>
          )}

          <View style={[styles.actions, { borderColor: colors.border }]}>
            <Pressable
              testID="clear-exercise-filters"
              accessibilityRole="button"
              accessibilityLabel="Xóa toàn bộ bộ lọc"
              accessibilityHint="Bỏ toàn bộ lựa chọn đang đánh dấu"
              onPress={() => setDraft({})}
              style={[styles.secondaryButton, { borderColor: colors.primary }]}>
              <Text style={[styles.buttonText, { color: colors.primary }]}>Xóa lọc</Text>
            </Pressable>
            <Pressable
              testID="apply-exercise-filters"
              accessibilityRole="button"
              accessibilityLabel="Áp dụng bộ lọc"
              accessibilityHint="Áp dụng các lựa chọn và đóng bộ lọc"
              onPress={() => onApply(draft)}
              style={[styles.primaryButton, { backgroundColor: colors.primary }]}>
              <Text style={[styles.buttonText, { color: colors.textOnPrimary }]}>Áp dụng</Text>
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.45)', justifyContent: 'flex-end' },
  sheet: { maxHeight: '90%', borderTopLeftRadius: radius.xl, borderTopRightRadius: radius.xl },
  header: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', padding: spacing.lg },
  title: { ...typography.h2 },
  closeButton: { minHeight: layout.minimumTouchTarget, justifyContent: 'center', paddingHorizontal: spacing.sm },
  closeText: { ...typography.label },
  scrollContent: { paddingHorizontal: spacing.lg, paddingBottom: spacing.lg, gap: spacing.lg },
  section: { gap: spacing.sm },
  sectionTitle: { ...typography.h3 },
  options: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  option: { minHeight: layout.minimumTouchTarget, borderWidth: 1, borderRadius: radius.full, justifyContent: 'center', paddingHorizontal: spacing.md },
  optionText: { ...typography.bodySmall, fontWeight: '600' },
  messageBlock: { padding: spacing.lg, alignItems: 'center', gap: spacing.sm },
  message: { ...typography.bodySmall, textAlign: 'center', padding: spacing.lg },
  link: { ...typography.label, textDecorationLine: 'underline' },
  actions: { flexDirection: 'row', borderTopWidth: 1, padding: spacing.lg, gap: spacing.md },
  secondaryButton: { flex: 1, minHeight: layout.minimumTouchTarget, borderWidth: 1, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  primaryButton: { flex: 1, minHeight: layout.minimumTouchTarget, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  buttonText: { ...typography.label },
});
