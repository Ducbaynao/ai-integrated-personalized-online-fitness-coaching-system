import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function CoachingButton({ label, onPress, disabled = false, busy = false, danger = false, secondary = false, testID }: { label: string; onPress: () => void; disabled?: boolean; busy?: boolean; danger?: boolean; secondary?: boolean; testID?: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const backgroundColor = secondary ? 'transparent' : danger ? theme.dangerText : theme.primary;
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={label}
    accessibilityState={{ disabled, busy }} disabled={disabled} onPress={onPress}
    style={({ pressed }) => [styles.button, { backgroundColor, borderColor: danger ? theme.dangerText : theme.primary, opacity: disabled ? 0.5 : pressed ? 0.8 : 1 }]}>
    <Text style={[styles.buttonText, { color: secondary ? (danger ? theme.dangerText : theme.primary) : theme.textOnPrimary }]}>{label}</Text>
  </Pressable>;
}

export function CoachingState({ title, message, onRetry, busy = false, testID }: { title: string; message: string; onRetry?: () => void; busy?: boolean; testID?: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return <View testID={testID} accessibilityRole={busy ? 'progressbar' : onRetry ? 'alert' : 'text'} style={[styles.state, { backgroundColor: theme.surface, borderColor: theme.border }]}>
    {busy ? <ActivityIndicator color={theme.primary} /> : null}
    <Text style={[styles.title, { color: theme.textPrimary }]}>{title}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>{message}</Text>
    {onRetry ? <CoachingButton label="Thử lại" onPress={onRetry} secondary /> : null}
  </View>;
}

export function CoachingCard({ children, testID }: { children: React.ReactNode; testID?: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return <View testID={testID} style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>{children}</View>;
}

export const coachingUiStyles = StyleSheet.create({
  screen: { flex: 1 },
  content: { padding: layout.mobileScreenPadding, paddingBottom: spacing.xxl, gap: spacing.lg },
  row: { flexDirection: 'row', gap: spacing.sm, flexWrap: 'wrap' },
  title: typography.h1,
  h2: typography.h3,
  body: typography.bodySmall,
  label: typography.label,
  input: { minHeight: 48, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.md, ...typography.body },
});

const styles = StyleSheet.create({
  button: { minHeight: layout.minimumTouchTarget, minWidth: layout.minimumTouchTarget, paddingHorizontal: spacing.lg, borderRadius: radius.md, borderWidth: 1, alignItems: 'center', justifyContent: 'center' },
  buttonText: typography.label,
  state: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.lg, gap: spacing.sm, alignItems: 'center' },
  title: { ...typography.h3, textAlign: 'center' },
  body: { ...typography.bodySmall, textAlign: 'center' },
  card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.lg, gap: spacing.sm },
});

