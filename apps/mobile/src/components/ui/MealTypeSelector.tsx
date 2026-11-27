import React from 'react';
import { Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { fontFamily, typography } from '@/design-system/tokens/typography';

export type MealTypeValue = 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK' | 'OTHER';

export interface MealTypeOption {
  value: MealTypeValue;
  label: string;
}

export const MEAL_TYPE_OPTIONS: readonly MealTypeOption[] = [
  { value: 'BREAKFAST', label: 'Bữa sáng' },
  { value: 'LUNCH', label: 'Bữa trưa' },
  { value: 'DINNER', label: 'Bữa tối' },
  { value: 'SNACK', label: 'Bữa phụ' },
  { value: 'OTHER', label: 'Khác' },
] as const;

export interface MealTypeSelectorProps {
  label?: string;
  value: MealTypeValue;
  onChange: (nextValue: MealTypeValue) => void;
  disabled?: boolean;
  options?: readonly MealTypeOption[];
  testID?: string;
  testIDPrefix?: string;
}

/**
 * Accessible radio group for choosing a meal category in food logging and AI estimation flows.
 * Guarantees >= 44x44pt touch targets, non-color-only selection indicators, and wrap-friendly
 * layout for 360px viewports and long labels without mutating enum values or auto-selecting by time.
 */
export function MealTypeSelector({
  label = 'Bữa ăn',
  value,
  onChange,
  disabled = false,
  options = MEAL_TYPE_OPTIONS,
  testID,
  testIDPrefix,
}: MealTypeSelectorProps) {
  const theme = getSemanticColors(useColorScheme() === 'dark');

  return (
    <View style={styles.container} testID={testID}>
      {label ? (
        <Text style={[styles.groupLabel, { color: theme.textPrimary }]}>{label}</Text>
      ) : null}
      <View
        accessibilityRole="radiogroup"
        accessibilityLabel={label}
        style={styles.optionsWrap}
      >
        {options.map((option) => {
          const isSelected = value === option.value;
          const optionTestID = testIDPrefix
            ? `${testIDPrefix}-${option.value.toLowerCase()}`
            : undefined;
          return (
            <Pressable
              key={option.value}
              testID={optionTestID}
              accessibilityRole="radio"
              accessibilityState={{
                checked: isSelected,
                selected: isSelected,
                disabled,
              }}
              accessibilityLabel={`${option.label}${isSelected ? ', đang chọn' : ''}`}
              disabled={disabled}
              onPress={() => {
                if (!disabled) {
                  onChange(option.value);
                }
              }}
              style={({ pressed }) => [
                styles.option,
                {
                  backgroundColor: isSelected ? theme.brandSoft : theme.surface,
                  borderColor: isSelected ? theme.primary : theme.borderStrong,
                  borderWidth: isSelected ? 2 : 1,
                  opacity: disabled ? 0.55 : pressed ? 0.85 : 1,
                },
              ]}
            >
              <View
                style={[
                  styles.indicator,
                  {
                    borderColor: isSelected ? theme.primary : theme.textSecondary,
                    backgroundColor: isSelected ? theme.primary : 'transparent',
                  },
                ]}
              >
                {isSelected ? (
                  <Text
                    style={[styles.checkMark, { color: theme.textOnPrimary }]}
                    accessible={false}
                  >
                    ✓
                  </Text>
                ) : null}
              </View>
              <Text
                style={[
                  styles.optionLabel,
                  {
                    color: isSelected ? theme.primary : theme.textPrimary,
                    fontFamily: isSelected ? fontFamily.bold : fontFamily.semiBold,
                  },
                ]}
              >
                {option.label}
              </Text>
            </Pressable>
          );
        })}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.xs,
    width: '100%',
  },
  groupLabel: {
    ...typography.label,
  },
  optionsWrap: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    width: '100%',
  },
  option: {
    minHeight: layout.minimumTouchTarget,
    minWidth: layout.minimumTouchTarget,
    maxWidth: '100%',
    flexShrink: 1,
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.xs,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
  },
  indicator: {
    width: 18,
    height: 18,
    borderRadius: radius.full,
    borderWidth: 2,
    alignItems: 'center',
    justifyContent: 'center',
    flexShrink: 0,
  },
  checkMark: {
    fontSize: 11,
    fontWeight: '700',
    lineHeight: 13,
  },
  optionLabel: {
    ...typography.bodySmall,
    flexShrink: 1,
  },
});
