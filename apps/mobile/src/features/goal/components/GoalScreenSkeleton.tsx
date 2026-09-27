import React, { useEffect, useState } from 'react';
import { Animated, StyleSheet, View } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';

interface SkeletonBoxProps {
  width: number | `${number}%`;
  height: number;
  borderRadius?: number;
  backgroundColor: string;
  opacity: Animated.Value;
}

function SkeletonBox({
  width,
  height,
  borderRadius = radius.sm,
  backgroundColor,
  opacity,
}: SkeletonBoxProps) {
  return (
    <Animated.View
      style={[
        {
          width,
          height,
          borderRadius,
          backgroundColor,
          opacity,
        },
      ]}
    />
  );
}

interface GoalScreenSkeletonProps {
  isDark?: boolean;
  testID?: string;
}

export function GoalScreenSkeleton({
  isDark = false,
  testID = 'goal-screen-skeleton',
}: GoalScreenSkeletonProps) {
  const themeColors = getSemanticColors(isDark);
  const [opacityAnim] = useState(() => new Animated.Value(0.3));

  useEffect(() => {
    const pulse = Animated.loop(
      Animated.sequence([
        Animated.timing(opacityAnim, {
          toValue: 0.7,
          duration: 800,
          useNativeDriver: true,
        }),
        Animated.timing(opacityAnim, {
          toValue: 0.3,
          duration: 800,
          useNativeDriver: true,
        }),
      ])
    );
    pulse.start();
    return () => pulse.stop();
  }, [opacityAnim]);

  return (
    <View testID={testID} style={styles.container}>
      {/* Header Skeleton Card */}
      <View
        style={[
          styles.card,
          { backgroundColor: themeColors.surface, borderColor: themeColors.border },
        ]}>
        <View style={styles.rowBetween}>
          <SkeletonBox
            width={90}
            height={24}
            borderRadius={radius.full}
            backgroundColor={themeColors.surfaceSubtle}
            opacity={opacityAnim}
          />
          <SkeletonBox
            width={60}
            height={18}
            borderRadius={radius.sm}
            backgroundColor={themeColors.surfaceSubtle}
            opacity={opacityAnim}
          />
        </View>
        <SkeletonBox
          width="70%"
          height={28}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="90%"
          height={16}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="50%"
          height={14}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
      </View>

      {/* Tab bar placeholder */}
      <View style={styles.tabBar}>
        <SkeletonBox
          width="23%"
          height={36}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="23%"
          height={36}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="23%"
          height={36}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="23%"
          height={36}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
      </View>

      {/* Content Cards */}
      <View
        style={[
          styles.card,
          { backgroundColor: themeColors.surface, borderColor: themeColors.border },
        ]}>
        <SkeletonBox
          width="40%"
          height={20}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="100%"
          height={48}
          borderRadius={radius.md}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="100%"
          height={48}
          borderRadius={radius.md}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
      </View>

      <View
        style={[
          styles.card,
          { backgroundColor: themeColors.surface, borderColor: themeColors.border },
        ]}>
        <SkeletonBox
          width="40%"
          height={20}
          borderRadius={radius.sm}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
        <SkeletonBox
          width="100%"
          height={60}
          borderRadius={radius.md}
          backgroundColor={themeColors.surfaceSubtle}
          opacity={opacityAnim}
        />
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.md,
    width: '100%',
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.md,
  },
  rowBetween: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  tabBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: spacing.xs,
  },
});
