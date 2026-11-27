import React from 'react';
import { ActivityIndicator, StyleSheet, Text, View, useColorScheme, type StyleProp, type ViewProps, type ViewStyle } from 'react-native';
import { getSemanticColors } from '../tokens/colors';
import { radius } from '../tokens/radius';
import { layout, spacing } from '../tokens/spacing';
import { typography } from '../tokens/typography';
import { AppButton } from './AppButton';
import { AppIcon, type AppIconName } from './AppIcon';

type AppCardProps = {
  children?: React.ReactNode;
  testID?: string;
  title?: string;
  icon?: AppIconName;
  message?: string;
  state?: 'loading' | 'empty' | 'error' | 'info';
  onRetry?: () => void;
  accessibilityRole?: ViewProps['accessibilityRole'];
  accessibilityLabel?: string;
  style?: StyleProp<ViewStyle>;
  isDark?: boolean;
};

export function AppCard({ children, testID, title, icon, message, state, onRetry, accessibilityRole, accessibilityLabel, style, isDark: darkOverride }: AppCardProps) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(darkOverride ?? isDark);
  const stateIcon = state === 'error' ? 'error' : 'info';
  return (
    <View testID={testID} accessibilityRole={accessibilityRole ?? (state === 'loading' ? 'progressbar' : state === 'error' ? 'alert' : state ? 'text' : undefined)}
      accessibilityState={state ? { busy: state === 'loading' } : undefined} accessibilityLabel={accessibilityLabel} aria-busy={state === 'loading'}
      style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }, state && styles.feedback, style]}>
      {title ? <View style={styles.heading}>
        {state === 'loading' ? <ActivityIndicator color={theme.primary} /> : (icon || state) ? <AppIcon name={icon ?? stateIcon} color={state === 'error' ? theme.dangerText : theme.primary} /> : null}
        <Text accessibilityRole="header" aria-level={2} style={[styles.title, { color: theme.textPrimary }]}>{title}</Text>
      </View> : null}
      {message ? <Text style={[styles.message, { color: theme.textPrimary }]}>{message}</Text> : null}
      {children}
      {onRetry ? <AppButton label="Thử lại" icon="refresh" variant="secondary" onPress={onRetry} /> : null}
    </View>
  );
}

const styles = StyleSheet.create({
  card: { padding: spacing.lg, borderRadius: radius.lg, borderWidth: 1, gap: spacing.md },
  feedback: { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' },
  heading: { flexDirection: 'row', gap: spacing.sm, alignItems: 'center' },
  title: { ...typography.h3, flex: 1 },
  message: typography.bodySmall,
});
