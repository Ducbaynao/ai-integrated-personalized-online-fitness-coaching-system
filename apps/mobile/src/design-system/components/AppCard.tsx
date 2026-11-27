import React from 'react';
import { StyleSheet, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '../tokens/colors';
import { radius } from '../tokens/radius';
import { spacing } from '../tokens/spacing';

export function AppCard({ children, testID }: { children: React.ReactNode; testID?: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return <View testID={testID} style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>{children}</View>;
}

const styles = StyleSheet.create({
  card: { padding: spacing.lg, borderRadius: radius.lg, borderWidth: 1, gap: spacing.md },
});
