import React, { useMemo, useState } from 'react';
import { Pressable, ScrollView, StyleSheet, Text, TextInput, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import {
  formatTimezoneDisplay,
  getSupportedTimezoneOptions,
  normalizeSearchText,
  type FriendlyTimezoneOption,
} from '@/features/shared/timezoneUtils';

export interface TimezoneContextPickerProps {
  value: string;
  onChange: (nextTimezone: string) => void;
  expanded: boolean;
  onToggleExpanded: () => void;
  label?: string;
  helperText?: string;
  disabled?: boolean;
  extraTimezones?: readonly (string | null | undefined)[];
  testIDPrefix?: string;
}

/**
 * Nontechnical timezone selector presenting friendly localized labels and friendly city/region
 * search over all backend-supported TZDB zones (including aliases) while emitting exact internal identifiers.
 */
export function TimezoneContextPicker({
  value,
  onChange,
  expanded,
  onToggleExpanded,
  label = 'Khung giờ áp dụng',
  helperText,
  disabled = false,
  extraTimezones = [],
  testIDPrefix = 'timezone-picker',
}: TimezoneContextPickerProps) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [searchQuery, setSearchQuery] = useState('');

  const allOptions = useMemo<FriendlyTimezoneOption[]>(
    () => getSupportedTimezoneOptions([value, ...extraTimezones]),
    [extraTimezones, value],
  );

  const filteredOptions = useMemo<FriendlyTimezoneOption[]>(() => {
    const normalized = normalizeSearchText(searchQuery);
    if (!normalized) {
      return allOptions;
    }
    const tokens = normalized.split(' ').filter(Boolean);
    return allOptions.filter((option) => tokens.every((token) => option.searchText.includes(token)));
  }, [allOptions, searchQuery]);

  return (
    <View style={styles.container}>
      <Text testID={`${testIDPrefix}-display`} style={[styles.summary, { color: theme.textPrimary }]}>
        {label}: {formatTimezoneDisplay(value)}
      </Text>
      {helperText ? (
        <Text style={[styles.helper, { color: theme.textSecondary }]}>{helperText}</Text>
      ) : null}
      <Pressable
        testID={`${testIDPrefix}-toggle`}
        accessibilityRole="button"
        accessibilityLabel={expanded ? 'Ẩn danh sách chọn khung giờ' : 'Chọn khung giờ khác'}
        disabled={disabled}
        onPress={() => {
          if (expanded) {
            setSearchQuery('');
          }
          onToggleExpanded();
        }}
        style={({ pressed }) => [
          styles.toggleButton,
          {
            borderColor: theme.borderStrong,
            backgroundColor: theme.surface,
            opacity: disabled ? 0.55 : pressed ? 0.85 : 1,
          },
        ]}
      >
        <Text style={[styles.toggleLabel, { color: theme.primary }]}>
          {expanded ? 'Đóng danh sách khung giờ' : 'Chọn khung giờ khác'}
        </Text>
      </Pressable>

      {expanded ? (
        <View style={styles.expandedPanel}>
          <TextInput
            testID={`${testIDPrefix}-search-input`}
            accessibilityLabel="Tìm thành phố hoặc khu vực"
            placeholder="Tìm thành phố, quốc gia hoặc khu vực"
            placeholderTextColor={theme.textSecondary}
            value={searchQuery}
            editable={!disabled}
            onChangeText={setSearchQuery}
            style={[
              styles.searchInput,
              {
                color: theme.textPrimary,
                borderColor: theme.borderStrong,
                backgroundColor: theme.surface,
              },
            ]}
          />
          <ScrollView
            nestedScrollEnabled
            style={[styles.optionsScroll, { borderColor: theme.border }]}
            contentContainerStyle={styles.optionsWrap}
          >
            <View accessibilityRole="radiogroup" accessibilityLabel={label} style={styles.optionsGroup}>
              {filteredOptions.length === 0 ? (
                <Text style={[styles.helper, { color: theme.textSecondary }]}>
                  Không tìm thấy thành phố hoặc khu vực phù hợp.
                </Text>
              ) : null}
              {filteredOptions.map((option) => {
                const selected = option.timezone === value;
                return (
                  <Pressable
                    key={option.timezone}
                    testID={`${testIDPrefix}-option-${option.timezone}`}
                    accessibilityRole="radio"
                    accessibilityState={{ checked: selected, selected, disabled }}
                    accessibilityLabel={`Chọn khung giờ ${option.label}`}
                    disabled={disabled}
                    onPress={() => {
                      if (!disabled) {
                        setSearchQuery('');
                        onChange(option.timezone);
                      }
                    }}
                    style={({ pressed }) => [
                      styles.optionButton,
                      {
                        backgroundColor: selected ? theme.brandSoft : theme.surface,
                        borderColor: selected ? theme.primary : theme.borderStrong,
                        borderWidth: selected ? 2 : 1,
                        opacity: disabled ? 0.55 : pressed ? 0.85 : 1,
                      },
                    ]}
                  >
                    <Text
                      style={[
                        styles.optionLabel,
                        { color: selected ? theme.primary : theme.textPrimary },
                      ]}
                    >
                      {selected ? '✓ ' : ''}
                      {option.label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          </ScrollView>
        </View>
      ) : null}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.xs,
    marginVertical: spacing.xs,
  },
  summary: {
    ...typography.label,
  },
  helper: {
    ...typography.bodySmall,
  },
  toggleButton: {
    minHeight: layout.minimumTouchTarget,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    alignSelf: 'flex-start',
    justifyContent: 'center',
  },
  toggleLabel: {
    ...typography.label,
  },
  expandedPanel: {
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  searchInput: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.bodySmall,
  },
  optionsScroll: {
    maxHeight: 260,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.xs,
  },
  optionsWrap: {
    paddingBottom: spacing.xs,
  },
  optionsGroup: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  optionButton: {
    minHeight: layout.minimumTouchTarget,
    minWidth: layout.minimumTouchTarget,
    maxWidth: '100%',
    flexShrink: 1,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
    justifyContent: 'center',
  },
  optionLabel: {
    ...typography.bodySmall,
    fontWeight: '600',
    flexShrink: 1,
  },
});
