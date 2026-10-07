import React from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function WorkoutButton({ label, onPress, disabled = false, busy = false, danger = false, secondary = false, testID, hint }: { label: string; onPress: () => void; disabled?: boolean; busy?: boolean; danger?: boolean; secondary?: boolean; testID?: string; hint?: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={label} accessibilityHint={hint} accessibilityState={{ disabled, busy }} disabled={disabled} onPress={onPress} style={({ pressed }) => [local.button, { backgroundColor: secondary ? 'transparent' : danger ? theme.dangerText : theme.primary, borderColor: danger ? theme.dangerText : theme.primary, opacity: disabled ? 0.5 : pressed ? 0.8 : 1 }]}><Text style={[local.buttonText, { color: secondary ? (danger ? theme.dangerText : theme.primary) : theme.textOnPrimary }]}>{label}</Text></Pressable>;
}
export function WorkoutCard({ children, testID }: { children: React.ReactNode; testID?: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return <View testID={testID} style={[local.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>{children}</View>;
}
export function WorkoutState({ title, message, busy = false, onRetry, testID }: { title: string; message: string; busy?: boolean; onRetry?: () => void; testID?: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return <View testID={testID} accessibilityRole={busy ? 'progressbar' : onRetry ? 'alert' : 'text'} style={[local.state, { backgroundColor: theme.surface, borderColor: theme.border }]}>{busy ? <ActivityIndicator color={theme.primary} /> : null}<Text style={[local.stateTitle, { color: theme.textPrimary }]}>{title}</Text><Text style={[local.stateBody, { color: theme.textSecondary }]}>{message}</Text>{onRetry ? <WorkoutButton label="Thử lại" onPress={onRetry} secondary /> : null}</View>;
}
export const workoutStyles = StyleSheet.create({ screen: { flex: 1 }, content: { padding: layout.mobileScreenPadding, paddingBottom: spacing.xxl, gap: spacing.lg }, row: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm }, title: typography.h1, h2: typography.h3, label: typography.label, body: typography.bodySmall, input: { minHeight: 48, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.md, ...typography.body } });
const local = StyleSheet.create({ button: { minHeight: layout.minimumTouchTarget, minWidth: layout.minimumTouchTarget, paddingHorizontal: spacing.lg, borderRadius: radius.md, borderWidth: 1, alignItems: 'center', justifyContent: 'center' }, buttonText: typography.label, card: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.lg, gap: spacing.sm }, state: { borderWidth: 1, borderRadius: radius.lg, padding: spacing.lg, gap: spacing.sm, alignItems: 'center' }, stateTitle: { ...typography.h3, textAlign: 'center' }, stateBody: { ...typography.bodySmall, textAlign: 'center' } });
