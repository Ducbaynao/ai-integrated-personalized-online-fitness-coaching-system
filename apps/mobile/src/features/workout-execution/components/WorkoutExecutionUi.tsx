import React from 'react';
import {
  ActivityIndicator,
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

export function ExecutionButton({
  label,
  onPress,
  disabled = false,
  busy = false,
  secondary = false,
  danger = false,
  testID,
  hint,
}: {
  label: string;
  onPress: () => void;
  disabled?: boolean;
  busy?: boolean;
  secondary?: boolean;
  danger?: boolean;
  testID?: string;
  hint?: string;
}) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const action = danger ? theme.dangerText : theme.primary;
  return (
    <Pressable
      testID={testID}
      accessibilityRole="button"
      accessibilityLabel={label}
      accessibilityHint={hint}
      accessibilityState={{ disabled, busy }}
      disabled={disabled}
      onPress={onPress}
      style={({ pressed }) => [
        styles.button,
        {
          backgroundColor: secondary ? 'transparent' : action,
          borderColor: action,
          opacity: disabled ? 0.5 : pressed ? 0.8 : 1,
        },
      ]}>
      {busy ? <ActivityIndicator color={secondary ? action : theme.textOnPrimary} /> : null}
      <Text style={[styles.buttonText, { color: secondary ? action : theme.textOnPrimary }]}>
        {label}
      </Text>
    </Pressable>
  );
}

export function ExecutionCard({
  children,
  testID,
}: {
  children: React.ReactNode;
  testID?: string;
}) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return (
    <View
      testID={testID}
      style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
      {children}
    </View>
  );
}

export function ExecutionState({
  title,
  message,
  busy = false,
  onRetry,
  actionLabel,
  onAction,
  testID,
}: {
  title: string;
  message: string;
  busy?: boolean;
  onRetry?: () => void;
  actionLabel?: string;
  onAction?: () => void;
  testID?: string;
}) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return (
    <View
      testID={testID}
      accessibilityRole={busy ? 'progressbar' : onRetry ? 'alert' : 'text'}
      style={[styles.state, { backgroundColor: theme.surface, borderColor: theme.border }]}>
      {busy ? <ActivityIndicator color={theme.primary} /> : null}
      <Text style={[styles.stateTitle, { color: theme.textPrimary }]}>{title}</Text>
      <Text style={[styles.stateBody, { color: theme.textSecondary }]}>{message}</Text>
      {onRetry ? <ExecutionButton label="Thử lại" secondary onPress={onRetry} /> : null}
      {actionLabel && onAction ? (
        <ExecutionButton label={actionLabel} secondary onPress={onAction} />
      ) : null}
    </View>
  );
}

export const executionStyles = StyleSheet.create({
  screen: { flex: 1 },
  content: {
    padding: layout.mobileScreenPadding,
    paddingBottom: spacing.xxl,
    gap: spacing.lg,
  },
  row: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  title: typography.h1,
  h2: typography.h3,
  label: typography.label,
  body: typography.bodySmall,
  caption: typography.caption,
  input: {
    minHeight: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    ...typography.body,
  },
});

const styles = StyleSheet.create({
  button: {
    minHeight: layout.minimumTouchTarget,
    minWidth: layout.minimumTouchTarget,
    paddingHorizontal: spacing.lg,
    borderRadius: radius.md,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
    gap: spacing.sm,
  },
  buttonText: typography.label,
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.lg, gap: spacing.sm },
  state: {
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.lg,
    gap: spacing.sm,
    alignItems: 'center',
  },
  stateTitle: { ...typography.h3, textAlign: 'center' },
  stateBody: { ...typography.bodySmall, textAlign: 'center' },
});
