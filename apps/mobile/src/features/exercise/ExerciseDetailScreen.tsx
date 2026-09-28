import React from 'react';
import { StyleSheet, Text, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { normalizeExerciseId } from '@/services/exerciseApi';

interface ExerciseDetailScreenProps {
  exerciseId: unknown;
}

export function ExerciseDetailScreen({ exerciseId }: ExerciseDetailScreenProps) {
  const colors = getSemanticColors(useColorScheme() === 'dark');
  const validExerciseId = normalizeExerciseId(exerciseId);

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: colors.canvas }]}>
      <View style={styles.content}>
        <Text accessibilityRole="header" style={[styles.title, { color: colors.textPrimary }]}>
          {validExerciseId ? 'Chi tiết bài tập' : 'Đường dẫn bài tập không hợp lệ'}
        </Text>
        <Text style={[styles.body, { color: colors.textSecondary }]}>
          {validExerciseId
            ? 'Đang chuẩn bị nội dung chi tiết bài tập.'
            : 'Vui lòng quay lại thư viện bài tập và thử lại.'}
        </Text>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  content: {
    flex: 1,
    paddingHorizontal: layout.mobileScreenPadding,
    paddingVertical: spacing.xl,
    gap: spacing.md,
  },
  title: { ...typography.h1 },
  body: { ...typography.body },
});
