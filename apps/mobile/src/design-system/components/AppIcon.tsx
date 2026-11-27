import React from 'react';
import { View } from 'react-native';
import { SymbolView } from 'expo-symbols';
import { iconSize } from '../tokens/spacing';

const symbols = {
  workout: { ios: 'dumbbell', android: 'fitness_center', web: 'fitness_center' },
  goal: { ios: 'target', android: 'flag', web: 'flag' },
  exercise: { ios: 'figure.strengthtraining.traditional', android: 'exercise', web: 'exercise' },
  nutrition: { ios: 'fork.knife', android: 'restaurant', web: 'restaurant' },
  progress: { ios: 'chart.xyaxis.line', android: 'trending_up', web: 'trending_up' },
  measurement: { ios: 'ruler', android: 'straighten', web: 'straighten' },
  coaching: { ios: 'person.2', android: 'group', web: 'group' },
  knowledge: { ios: 'book', android: 'menu_book', web: 'menu_book' },
  summary: { ios: 'doc.text', android: 'summarize', web: 'summarize' },
  profile: { ios: 'person.crop.circle', android: 'account_circle', web: 'account_circle' },
  arrow: { ios: 'arrow.right', android: 'arrow_forward', web: 'arrow_forward' },
  chevron: { ios: 'chevron.right', android: 'chevron_right', web: 'chevron_right' },
  logout: { ios: 'rectangle.portrait.and.arrow.right', android: 'logout', web: 'logout' },
} as const;

export type AppIconName = keyof typeof symbols;

/** Decorative icon. The containing action supplies its accessible text. */
export function AppIcon({ name, color, size = iconSize.medium }: { name: AppIconName; color: string; size?: number }) {
  return (
    <View accessible={false} aria-hidden accessibilityElementsHidden importantForAccessibility="no-hide-descendants" style={{ pointerEvents: 'none' }}>
      <SymbolView name={symbols[name]} size={size} tintColor={color} weight="regular" />
    </View>
  );
}
