import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import { ExerciseCatalogItem } from '@/types/exercise';

const DIFFICULTY_LABELS = {
  BEGINNER: 'Cơ bản',
  INTERMEDIATE: 'Trung cấp',
  ADVANCED: 'Nâng cao',
} as const;

interface ExerciseListItemProps {
  exercise: ExerciseCatalogItem;
  isDark?: boolean;
  onPress?: () => void;
}

export function ExerciseListItem({ exercise, isDark = false }: ExerciseListItemProps) {
  const colors = getSemanticColors(isDark);
  const muscles = exercise.primaryMuscles.map((item) => item.name).join(', ');
  const equipment = exercise.equipment.map((item) => item.name).join(', ');

  return (
    <View
      testID={`exercise-row-${exercise.id}`}
      accessible
      accessibilityRole="summary"
      accessibilityLabel={`${exercise.name}. Nhóm cơ chính: ${muscles || 'chưa xác định'}. Thiết bị: ${equipment || 'không yêu cầu'}.`}
      style={[styles.card, { backgroundColor: colors.surface, borderColor: colors.border }]}>
      <View
        testID={`exercise-media-${exercise.id}`}
        accessibilityLabel={exercise.mediaAvailable ? 'Bài tập có media hướng dẫn' : 'Bài tập chưa có media'}
        style={[styles.media, { backgroundColor: colors.surfaceSubtle, borderColor: colors.border }]}>
        <Text style={[styles.mediaGlyph, { color: colors.textSecondary }]}>▶</Text>
        <Text style={[styles.mediaText, { color: colors.textSecondary }]}>
          {exercise.mediaAvailable ? 'Media có sẵn' : 'Chưa có media'}
        </Text>
      </View>
      <View style={styles.content}>
        <Text style={[styles.title, { color: colors.textPrimary }]}>{exercise.name}</Text>
        <Text style={[styles.meta, { color: colors.textSecondary }]} numberOfLines={2}>
          {muscles || 'Chưa xác định nhóm cơ'}
        </Text>
        <Text style={[styles.meta, { color: colors.textSecondary }]} numberOfLines={2}>
          {equipment || 'Không yêu cầu thiết bị'}
        </Text>
        <View style={styles.badges}>
          {exercise.difficulty && (
            <Text style={[styles.badge, { backgroundColor: colors.brandSoft, color: colors.primary }]}>
              {DIFFICULTY_LABELS[exercise.difficulty]}
            </Text>
          )}
          {exercise.category && (
            <Text style={[styles.badge, { backgroundColor: colors.surfaceSubtle, color: colors.textPrimary }]}>
              {exercise.category.name}
            </Text>
          )}
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  card: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.md,
    flexDirection: 'row',
    gap: spacing.md,
  },
  media: {
    width: 88,
    minHeight: 88,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xs,
  },
  mediaGlyph: { ...typography.h3 },
  mediaText: { ...typography.caption, textAlign: 'center', marginTop: spacing.xs },
  content: { flex: 1, justifyContent: 'center' },
  title: { ...typography.h3, marginBottom: spacing.xs },
  meta: { ...typography.bodySmall, marginBottom: spacing.xxs },
  badges: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.xs, marginTop: spacing.xs },
  badge: { ...typography.caption, borderRadius: radius.full, paddingHorizontal: spacing.sm, paddingVertical: spacing.xxs },
});
