import React, { useEffect, useRef, useState } from 'react';
import {
  Pressable,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { DatePickerModal } from '@/components/ui/DatePickerModal';
import { formatVietnameseDateDisplay, parseDateOnly } from '@/features/shared/dateInputUtils';

export interface DateInputFieldProps {
  label?: string;
  helperText?: string;
  value: string;
  onChange: (nextDate: string) => void;
  placeholder?: string;
  error?: string;
  disabled?: boolean;
  allowClear?: boolean;
  minDate?: string;
  maxDate?: string;
  initialBrowseDate?: string;
  yearRange?: { min: number; max: number };
  mondayOnly?: boolean;
  sessionKey?: string | number;
  testID?: string;
  triggerTestID?: string;
  clearTestID?: string;
  accessibilityLabel?: string;
  accessibilityHint?: string;
}

export function DateInputField({
  label,
  helperText,
  value,
  onChange,
  placeholder = 'Chọn ngày',
  error,
  disabled = false,
  allowClear = false,
  minDate,
  maxDate,
  initialBrowseDate,
  yearRange,
  mondayOnly = false,
  sessionKey = 0,
  testID,
  triggerTestID,
  clearTestID,
  accessibilityLabel,
  accessibilityHint,
}: DateInputFieldProps) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [activeOperation, setActiveOperation] = useState<{
    opId: number;
    sessionKey: string | number;
  } | null>(null);
  const [prevSessionKey, setPrevSessionKey] = useState(sessionKey);
  const [prevDisabled, setPrevDisabled] = useState(disabled);
  const nextOpIdRef = useRef(0);
  const activeOperationRef = useRef<{ opId: number; sessionKey: string | number } | null>(null);
  const isMountedRef = useRef(true);

  if (prevSessionKey !== sessionKey || prevDisabled !== disabled) {
    setPrevSessionKey(sessionKey);
    setPrevDisabled(disabled);
    if (activeOperation !== null) {
      setActiveOperation(null);
    }
  }

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      activeOperationRef.current = null;
    };
  }, []);

  useEffect(() => {
    activeOperationRef.current =
      activeOperation && !disabled && activeOperation.sessionKey === sessionKey
        ? activeOperation
        : null;
  }, [activeOperation, disabled, sessionKey]);

  const pickerVisible = Boolean(
    activeOperation && !disabled && activeOperation.sessionKey === sessionKey
  );

  const formattedDisplay = formatVietnameseDateDisplay(value);
  const hasValue = Boolean(value && value.trim());
  const effectiveLabel = accessibilityLabel || label || placeholder;

  const isOperationValid = (opId: number, opSessionKey: string | number): boolean => {
    const active = activeOperationRef.current;
    return Boolean(
      isMountedRef.current &&
        !disabled &&
        active &&
        active.opId === opId &&
        active.sessionKey === opSessionKey &&
        opSessionKey === sessionKey
    );
  };

  const handleOpenPicker = () => {
    if (disabled || !isMountedRef.current) return;
    const opId = ++nextOpIdRef.current;
    const nextOp = { opId, sessionKey };
    activeOperationRef.current = nextOp;
    setActiveOperation(nextOp);
  };

  const handleConfirmForOperation = (
    opId: number,
    opSessionKey: string | number,
    nextDate: string
  ) => {
    if (!isOperationValid(opId, opSessionKey)) return;
    activeOperationRef.current = null;
    setActiveOperation(null);
    onChange(nextDate);
  };

  const handleClearForOperation = (opId: number, opSessionKey: string | number) => {
    if (!isOperationValid(opId, opSessionKey)) return;
    activeOperationRef.current = null;
    setActiveOperation(null);
    onChange('');
  };

  const handleCancelForOperation = (opId: number, opSessionKey: string | number) => {
    if (!isOperationValid(opId, opSessionKey)) return;
    activeOperationRef.current = null;
    setActiveOperation(null);
  };

  const handleDirectClear = () => {
    if (disabled || !isMountedRef.current) return;
    activeOperationRef.current = null;
    setActiveOperation(null);
    onChange('');
  };

  const resolvedTriggerTestID = triggerTestID ?? (testID ? `${testID}-trigger` : undefined);

  return (
    <View testID={testID} style={styles.container}>
      {label ? (
        <Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text>
      ) : null}
      {helperText ? (
        <Text style={[styles.helperText, { color: theme.textSecondary }]}>{helperText}</Text>
      ) : null}

      <View style={styles.triggerRow}>
        <Pressable
          testID={resolvedTriggerTestID}
          accessibilityRole="button"
          accessibilityLabel={effectiveLabel}
          accessibilityHint={accessibilityHint ?? 'Chạm để mở lịch chọn ngày'}
          accessibilityState={{ disabled }}
          accessibilityValue={{ text: formattedDisplay || 'Chưa chọn' }}
          aria-invalid={!!error}
          disabled={disabled}
          onPress={handleOpenPicker}
          style={({ pressed }) => [
            styles.triggerButton,
            {
              backgroundColor: theme.surface,
              borderColor: error ? theme.dangerText : theme.border,
              opacity: disabled ? 0.55 : pressed ? 0.85 : 1,
            },
          ]}>
          <Text
            style={[
              styles.triggerText,
              {
                color: hasValue && parseDateOnly(value)
                  ? theme.textPrimary
                  : hasValue
                    ? theme.dangerText
                    : theme.textSecondary,
              },
            ]}>
            {hasValue ? formattedDisplay : placeholder}
          </Text>
          <Text style={[styles.badgeText, { color: theme.primary }]}>Chọn lịch</Text>
        </Pressable>

        {allowClear && hasValue && !disabled ? (
          <Pressable
            testID={clearTestID ?? (testID ? `${testID}-clear` : undefined)}
            accessibilityRole="button"
            accessibilityLabel={`Xóa ${effectiveLabel}`}
            onPress={handleDirectClear}
            style={({ pressed }) => [
              styles.clearButton,
              {
                borderColor: theme.border,
                backgroundColor: theme.surfaceSubtle,
                opacity: pressed ? 0.8 : 1,
              },
            ]}>
            <Text style={[styles.clearText, { color: theme.dangerText }]}>Xóa</Text>
          </Pressable>
        ) : null}
      </View>

      {error ? (
        <Text accessibilityRole="alert" style={[styles.errorText, { color: theme.dangerText }]}>
          {error}
        </Text>
      ) : null}

      {pickerVisible && activeOperation ? (
        <DatePickerModal
          key={activeOperation.opId}
          visible={pickerVisible}
          title={label || effectiveLabel}
          value={value}
          initialBrowseDate={initialBrowseDate}
          minDate={minDate}
          maxDate={maxDate}
          yearRange={yearRange}
          allowClear={allowClear}
          mondayOnly={mondayOnly}
          sessionKey={activeOperation.sessionKey}
          onConfirm={(nextDate) =>
            handleConfirmForOperation(activeOperation.opId, activeOperation.sessionKey, nextDate)
          }
          onClear={
            allowClear
              ? () => handleClearForOperation(activeOperation.opId, activeOperation.sessionKey)
              : undefined
          }
          onCancel={() =>
            handleCancelForOperation(activeOperation.opId, activeOperation.sessionKey)
          }
        />
      ) : null}
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
  helperText: {
    ...typography.caption,
    marginBottom: spacing.xs,
  },
  triggerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  triggerButton: {
    flex: 1,
    minHeight: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  triggerText: {
    ...typography.body,
  },
  badgeText: {
    ...typography.label,
  },
  clearButton: {
    minHeight: layout.minimumTouchTarget,
    minWidth: layout.minimumTouchTarget,
    paddingHorizontal: spacing.md,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  clearText: {
    ...typography.label,
  },
  errorText: {
    ...typography.caption,
    marginTop: spacing.xs,
  },
});
