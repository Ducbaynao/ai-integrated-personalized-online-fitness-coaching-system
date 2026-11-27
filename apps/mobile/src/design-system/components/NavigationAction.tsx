import React, { useState } from 'react';
import { Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { colors, getSemanticColors } from '../tokens/colors';
import { radius } from '../tokens/radius';
import { layout, spacing, iconSize } from '../tokens/spacing';
import { typography } from '../tokens/typography';
import { AppIcon, AppIconName } from './AppIcon';

export function NavigationAction({ title, icon, onPress, testID, accessibilityLabel, description, tile = false }: {
  title: string; icon: AppIconName; onPress: () => void; testID?: string; accessibilityLabel: string; description?: string; tile?: boolean;
}) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [focused, setFocused] = useState(false);
  return (
    <Pressable testID={testID} accessibilityRole="button" accessibilityLabel={accessibilityLabel} onPress={() => onPress()}
      onFocus={() => setFocused(true)} onBlur={() => setFocused(false)}
      style={({ pressed }) => [styles.action, tile && styles.tile, {
        backgroundColor: pressed ? theme.surfaceSubtle : theme.surface,
        borderColor: focused ? theme.primary : theme.border,
      }]}>
      <View style={[styles.icon, { backgroundColor: theme.brandSoft }]}>
        <AppIcon name={icon} color={colors.brand[700]} />
      </View>
      <View style={styles.copy}>
        <Text style={[styles.title, { color: theme.textPrimary }]}>{title}</Text>
        {description ? <Text style={[styles.description, { color: theme.textPrimary }]}>{description}</Text> : null}
      </View>
      {!tile && <AppIcon name="chevron" color={theme.textSecondary} size={iconSize.small} />}
    </Pressable>
  );
}

const styles = StyleSheet.create({
  action: {
    minHeight: layout.minimumTouchTarget, borderRadius: radius.lg, borderWidth: 1,
    padding: spacing.md, gap: spacing.md, flexDirection: 'row', alignItems: 'center',
  },
  tile: { flexDirection: 'column', alignItems: 'flex-start', flexGrow: 1, flexBasis: '45%', minWidth: 0 },
  icon: { width: layout.minimumTouchTarget, height: layout.minimumTouchTarget, borderRadius: radius.md, alignItems: 'center', justifyContent: 'center' },
  copy: { maxWidth: '100%', flexShrink: 1, flexGrow: 1, minWidth: 0, gap: spacing.xs },
  title: { ...typography.label },
  description: typography.bodySmall,
});
