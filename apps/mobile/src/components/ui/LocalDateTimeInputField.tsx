import React from 'react';
import { StyleSheet, Text, TextInput, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { DateInputField } from '@/components/ui/DateInputField';
import { joinLocalDateTime, splitLocalDateTime } from '@/features/shared/dateInputUtils';

export interface LocalDateTimeInputFieldProps {
  label?: string;
  value: string;
  onChange: (nextLocalDateTime: string) => void;
  minDate?: string;
  maxDate?: string;
  initialBrowseDate?: string;
  disabled?: boolean;
  allowClear?: boolean;
  sessionKey?: string | number;
  dateTestID?: string;
  timeTestID?: string;
  dateAccessibilityLabel?: string;
  timeAccessibilityLabel?: string;
}

export function LocalDateTimeInputField({
  label,
  value,
  onChange,
  minDate,
  maxDate,
  initialBrowseDate,
  disabled = false,
  allowClear = false,
  sessionKey = 0,
  dateTestID,
  timeTestID,
  dateAccessibilityLabel,
  timeAccessibilityLabel,
}: LocalDateTimeInputFieldProps) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const { date, time } = splitLocalDateTime(value);

  const handleDateChange = (nextDate: string) => {
    onChange(joinLocalDateTime(nextDate, time));
  };

  const handleTimeChange = (nextTime: string) => {
    onChange(joinLocalDateTime(date, nextTime));
  };

  return (
    <View style={styles.container}>
      {label ? (
        <Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text>
      ) : null}
      <View style={styles.row}>
        <View style={styles.dateCol}>
          <DateInputField
            value={date}
            onChange={handleDateChange}
            placeholder="Chọn ngày"
            minDate={minDate}
            maxDate={maxDate}
            initialBrowseDate={initialBrowseDate}
            disabled={disabled}
            allowClear={allowClear}
            sessionKey={sessionKey}
            testID={dateTestID}
            accessibilityLabel={dateAccessibilityLabel ?? (label ? `Ngày ${label}` : 'Chọn ngày')}
          />
        </View>
        <View style={styles.timeCol}>
          <TextInput
            testID={timeTestID}
            accessibilityLabel={timeAccessibilityLabel ?? (label ? `Giờ ${label} (HH:mm)` : 'Giờ địa phương (HH:mm)')}
            placeholder="HH:mm"
            placeholderTextColor={theme.textSecondary}
            value={time}
            onChangeText={handleTimeChange}
            maxLength={5}
            editable={!disabled}
            keyboardType="numbers-and-punctuation"
            style={[
              styles.timeInput,
              {
                color: theme.textPrimary,
                borderColor: theme.border,
                backgroundColor: theme.surface,
              },
            ]}
          />
        </View>
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    marginBottom: spacing.sm,
  },
  label: {
    ...typography.label,
    marginBottom: spacing.xs,
  },
  row: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
  },
  dateCol: {
    flex: 3,
  },
  timeCol: {
    flex: 2,
  },
  timeInput: {
    minHeight: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    ...typography.body,
  },
});
