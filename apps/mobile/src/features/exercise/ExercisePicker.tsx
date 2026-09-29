import React, { useMemo } from 'react';
import { AccessibilityInfo, Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import { ExerciseSummary } from '@/types/exercise';
import { ExerciseCatalogContent } from './components/ExerciseCatalogContent';
import { ExerciseListItem } from './components/ExerciseListItem';

export interface ExercisePickerProps {
  selectedExercise: ExerciseSummary | null;
  onSelectionChange: (exercise: ExerciseSummary) => void;
  onConfirm: (exercise: ExerciseSummary) => void;
  onCancel: () => void;
  excludedExerciseIds?: readonly string[];
}

export function ExercisePicker({
  selectedExercise,
  onSelectionChange,
  onConfirm,
  onCancel,
  excludedExerciseIds = [],
}: ExercisePickerProps) {
  const isDark = useColorScheme() === 'dark';
  const colors = getSemanticColors(isDark);
  const excludedIds = useMemo(() => new Set(excludedExerciseIds), [excludedExerciseIds]);
  const selectedIsExcluded = selectedExercise ? excludedIds.has(selectedExercise.id) : false;

  const selectExercise = (exercise: ExerciseSummary) => {
    if (excludedIds.has(exercise.id) || exercise.id === selectedExercise?.id) return;
    onSelectionChange(exercise);
    AccessibilityInfo.announceForAccessibility(`Đã chọn ${exercise.name}`);
  };

  const renderActions = ({ interactionDisabled }: { interactionDisabled: boolean }) => {
    const canConfirm = selectedExercise !== null && !selectedIsExcluded && !interactionDisabled;
    return (
      <View testID="exercise-picker-actions" style={[styles.actions, { backgroundColor: colors.surface, borderColor: colors.border }]}>
        <View style={styles.selectionSummary} accessibilityLiveRegion="polite">
          <Text style={[styles.selectionLabel, { color: colors.textSecondary }]}>Bài tập đã chọn</Text>
          <Text
            testID="exercise-picker-selection-summary"
            style={[styles.selectionValue, { color: selectedIsExcluded ? colors.warningText : colors.textPrimary }]}
            numberOfLines={2}>
            {selectedIsExcluded
              ? 'Bài tập này đã được thêm'
              : selectedExercise?.name ?? 'Chưa chọn bài tập'}
          </Text>
        </View>
        <View style={styles.buttons}>
          <Pressable
            testID="cancel-exercise-picker"
            accessibilityRole="button"
            accessibilityLabel="Hủy chọn bài tập"
            accessibilityHint="Đóng trình chọn mà không trả về bài tập"
            onPress={onCancel}
            style={[styles.secondaryButton, { borderColor: colors.primary }]}>
            <Text style={[styles.buttonText, { color: colors.primary }]}>Hủy</Text>
          </Pressable>
          <Pressable
            testID="confirm-exercise-picker"
            accessibilityRole="button"
            accessibilityLabel="Xác nhận bài tập đã chọn"
            accessibilityHint="Trả bài tập đã chọn về màn hình tạo kế hoạch"
            accessibilityState={{ disabled: !canConfirm }}
            disabled={!canConfirm}
            onPress={() => {
              if (canConfirm && selectedExercise) onConfirm(selectedExercise);
            }}
            style={[
              styles.primaryButton,
              { backgroundColor: canConfirm ? colors.primary : colors.surfaceSubtle },
            ]}>
            <Text style={[styles.buttonText, { color: canConfirm ? colors.textOnPrimary : colors.textSecondary }]}>
              Xác nhận
            </Text>
          </Pressable>
        </View>
      </View>
    );
  };

  return (
    <ExerciseCatalogContent
      title="Chọn bài tập"
      subtitle="Tìm và chọn một bài tập để thêm vào kế hoạch."
      backLabel="Hủy"
      onBack={onCancel}
      listExtraData={{ selectedExerciseId: selectedExercise?.id, excludedExerciseIds }}
      bottomContent={renderActions}
      renderExercise={(exercise, rowIsDark) => {
        const excluded = excludedIds.has(exercise.id);
        return (
          <ExerciseListItem
            exercise={exercise}
            isDark={rowIsDark}
            mode="single-select"
            selected={exercise.id === selectedExercise?.id}
            disabled={excluded}
            onPress={() => selectExercise(exercise)}
          />
        );
      }}
    />
  );
}

const styles = StyleSheet.create({
  actions: {
    borderTopWidth: 1,
    paddingHorizontal: layout.mobileScreenPadding,
    paddingVertical: spacing.md,
    gap: spacing.md,
  },
  selectionSummary: { gap: spacing.xxs },
  selectionLabel: { ...typography.caption },
  selectionValue: { ...typography.label },
  buttons: { flexDirection: 'row', gap: spacing.md },
  secondaryButton: {
    flex: 1,
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  primaryButton: {
    flex: 1,
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  buttonText: { ...typography.label },
});
