import React from 'react';
import { Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import { SemanticColorScheme } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

interface ActiveFilterChip {
  key: string;
  label: string;
}

interface SearchAndFilterBarProps {
  value: string;
  onChangeText: (value: string) => void;
  onOpenFilters: () => void;
  chips: ActiveFilterChip[];
  onRemoveChip: (key: string) => void;
  colors: SemanticColorScheme;
}

export function SearchAndFilterBar({
  value,
  onChangeText,
  onOpenFilters,
  chips,
  onRemoveChip,
  colors,
}: SearchAndFilterBarProps) {
  return (
    <View style={styles.container}>
      <View style={styles.row}>
        <TextInput
          testID="exercise-search-input"
          accessibilityLabel="Tìm kiếm bài tập"
          accessibilityHint="Nhập tên hoặc từ khóa bài tập"
          placeholder="Tìm bài tập"
          placeholderTextColor={colors.textSecondary}
          value={value}
          onChangeText={onChangeText}
          returnKeyType="search"
          autoCapitalize="none"
          autoCorrect={false}
          style={[
            styles.input,
            { backgroundColor: colors.surface, borderColor: colors.border, color: colors.textPrimary },
          ]}
        />
        {value.length > 0 && (
          <Pressable
            testID="clear-exercise-search"
            accessibilityRole="button"
            accessibilityLabel="Xóa từ khóa tìm kiếm"
            onPress={() => onChangeText('')}
            style={[styles.clearButton, { borderColor: colors.border, backgroundColor: colors.surface }]}>
            <Text style={[styles.clearText, { color: colors.textPrimary }]}>×</Text>
          </Pressable>
        )}
        <Pressable
          testID="exercise-filter-button"
          accessibilityRole="button"
          accessibilityLabel="Lọc bài tập"
          accessibilityHint="Mở các lựa chọn lọc danh mục bài tập"
          accessibilityState={{ expanded: false }}
          onPress={onOpenFilters}
          style={({ pressed }) => [
            styles.filterButton,
            { backgroundColor: pressed ? colors.primaryPressed : colors.primary },
          ]}>
          <Text style={[styles.filterButtonText, { color: colors.textOnPrimary }]}>Bộ lọc</Text>
          {chips.length > 0 && (
            <View style={[styles.countBadge, { backgroundColor: colors.surface }]}>
              <Text style={[styles.countText, { color: colors.primary }]}>{chips.length}</Text>
            </View>
          )}
        </Pressable>
      </View>

      {chips.length > 0 && (
        <View testID="active-filter-chips" style={styles.chips}>
          {chips.map((chip) => (
            <Pressable
              key={chip.key}
              testID={`remove-filter-${chip.key}`}
              accessibilityRole="button"
              accessibilityLabel={`Xóa bộ lọc ${chip.label}`}
              onPress={() => onRemoveChip(chip.key)}
              style={[styles.chip, { backgroundColor: colors.brandSoft, borderColor: colors.primary }]}>
              <Text style={[styles.chipText, { color: colors.primary }]}>{chip.label} ×</Text>
            </Pressable>
          ))}
        </View>
      )}
    </View>
  );
}

const styles = StyleSheet.create({
  container: { gap: spacing.sm },
  row: { flexDirection: 'row', gap: spacing.sm },
  input: {
    ...typography.body,
    flex: 1,
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
  },
  filterButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'center',
    gap: spacing.xs,
  },
  clearButton: {
    width: layout.minimumTouchTarget,
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  clearText: { ...typography.h3 },
  filterButtonText: { ...typography.label },
  countBadge: {
    minWidth: 20,
    height: 20,
    borderRadius: radius.full,
    alignItems: 'center',
    justifyContent: 'center',
  },
  countText: { ...typography.caption, fontWeight: '700' },
  chips: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  chip: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.full,
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
  },
  chipText: { ...typography.caption, fontWeight: '600' },
});
