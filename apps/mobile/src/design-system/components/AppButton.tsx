import React, { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, useColorScheme } from 'react-native';
import { colors, getSemanticColors } from '../tokens/colors';
import { radius } from '../tokens/radius';
import { spacing, layout, iconSize } from '../tokens/spacing';
import { typography } from '../tokens/typography';
import { AppIcon, AppIconName } from './AppIcon';

type AppButtonProps = {
  label: string;
  onPress: () => void;
  variant?: 'primary' | 'secondary' | 'ghost' | 'danger';
  disabled?: boolean;
  loading?: boolean;
  icon?: AppIconName;
  accessibilityLabel?: string;
  testID?: string;
  size?: 'medium' | 'large';
};

export function AppButton({ label, onPress, variant = 'primary', disabled = false, loading = false, icon, accessibilityLabel, testID, size = 'medium' }: AppButtonProps) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);
  const [focused, setFocused] = useState(false);
  const unavailable = disabled || loading;
  const filled = variant === 'primary' || variant === 'danger';
  const foreground = disabled
    ? (isDark ? theme.textSecondary : colors.neutral[700])
    : filled ? theme.textOnPrimary : theme.primary;

  return (
    <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={accessibilityLabel ?? label}
      aria-busy={loading}
      accessibilityState={{ disabled: unavailable, busy: loading }} disabled={unavailable}
      onPress={onPress} onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
      style={({ pressed }) => [styles.button, {
        minHeight: size === 'large' ? layout.largeButtonHeight : layout.buttonHeight,
        backgroundColor: disabled ? theme.surfaceSubtle
          : variant === 'danger' ? theme.dangerPressed
          : variant === 'primary' ? (pressed ? theme.primaryPressed : theme.primary)
          : pressed ? theme.surfaceSubtle : variant === 'secondary' ? theme.surface : 'transparent',
        borderColor: focused ? theme.textPrimary : disabled ? theme.border
          : variant === 'secondary' ? theme.borderStrong : 'transparent',
        opacity: pressed && variant === 'danger' ? 0.85 : 1,
      }]}>
      {loading ? <ActivityIndicator color={foreground} /> : icon ? <AppIcon name={icon} color={foreground} size={iconSize.small} /> : null}
      <Text style={[styles.label, { color: foreground }]}>{label}</Text>
    </Pressable>
  );
}

const styles = StyleSheet.create({
  button: {
    minHeight: layout.buttonHeight, minWidth: layout.minimumTouchTarget, borderWidth: 2, borderRadius: radius.md,
    paddingHorizontal: spacing.lg, paddingVertical: spacing.md,
    flexDirection: 'row', alignItems: 'center', justifyContent: 'center', gap: spacing.sm,
  },
  label: { ...typography.label, flexShrink: 1, textAlign: 'center' },
});
