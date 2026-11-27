import React from 'react';
import { View } from 'react-native';
import { SymbolView } from 'expo-symbols';
import { iconSize } from '../tokens/spacing';

const symbols = {
  trash: { ios: 'trash', android: 'delete', web: 'delete' },
  calendar: { ios: 'calendar', android: 'calendar_month', web: 'calendar_month' },
  back: { ios: 'arrow.left', android: 'arrow_back', web: 'arrow_back' },
  refresh: { ios: 'arrow.clockwise', android: 'refresh', web: 'refresh' },
  chat: { ios: 'bubble.left.and.bubble.right', android: 'chat_bubble_outline', web: 'chat_bubble_outline' },
  bell: { ios: 'bell', android: 'notifications', web: 'notifications' },
  attention: { ios: 'exclamationmark.triangle', android: 'warning', web: 'warning' },
  check: { ios: 'checkmark.circle', android: 'check_circle', web: 'check_circle' },
  plus: { ios: 'plus', android: 'add', web: 'add' },
  info: { ios: 'info.circle', android: 'info', web: 'info' },
  error: { ios: 'exclamationmark.circle', android: 'error_outline', web: 'error_outline' },
  history: { ios: 'clock.arrow.circlepath', android: 'history', web: 'history' },
  shield: { ios: 'checkmark.shield', android: 'verified_user', web: 'verified_user' },
  pause: { ios: 'pause', android: 'pause', web: 'pause' },
  play: { ios: 'play', android: 'play_arrow', web: 'play_arrow' },
  edit: { ios: 'pencil', android: 'edit', web: 'edit' },
  photo: { ios: 'photo', android: 'image', web: 'image' },
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
