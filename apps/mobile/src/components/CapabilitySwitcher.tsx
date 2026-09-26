import React from 'react';
import { Pressable, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { useAuth } from '@/features/auth/AuthContext';
import { ActiveCapability } from '@/features/auth/routeGuard';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function CapabilitySwitcher() {
  const { user, activeCapability, setActiveCapability } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const isDualRole =
    Boolean(user?.capabilities?.hasStudentProfile) &&
    Boolean(user?.capabilities?.hasTrainerProfile);

  if (!isDualRole) {
    return null;
  }

  const handleSelect = (capability: ActiveCapability) => {
    if (activeCapability !== capability) {
      setActiveCapability(capability);
    }
  };

  const isStudentActive = activeCapability === 'STUDENT';
  const isTrainerActive = activeCapability === 'TRAINER';

  return (
    <View
      testID="capability-switcher"
      accessibilityRole="radiogroup"
      accessibilityLabel="Chuyển hồ sơ đang sử dụng"
      style={[
        styles.container,
        {
          backgroundColor: themeColors.surfaceSubtle,
          borderColor: themeColors.border,
        },
      ]}>
      <Pressable
        testID="switch-to-student"
        accessibilityRole="radio"
        accessibilityState={{ selected: isStudentActive }}
        accessibilityLabel="Hồ sơ học viên"
        onPress={() => handleSelect('STUDENT')}
        style={[
          styles.tab,
          isStudentActive && {
            backgroundColor: themeColors.primary,
          },
        ]}>
        <Text
          style={[
            styles.tabText,
            {
              color: isStudentActive
                ? themeColors.textOnPrimary
                : themeColors.textSecondary,
              fontWeight: isStudentActive ? '700' : '500',
            },
          ]}>
          Học viên
        </Text>
      </Pressable>

      <Pressable
        testID="switch-to-trainer"
        accessibilityRole="radio"
        accessibilityState={{ selected: isTrainerActive }}
        accessibilityLabel="Hồ sơ huấn luyện viên"
        onPress={() => handleSelect('TRAINER')}
        style={[
          styles.tab,
          isTrainerActive && {
            backgroundColor: themeColors.primary,
          },
        ]}>
        <Text
          style={[
            styles.tabText,
            {
              color: isTrainerActive
                ? themeColors.textOnPrimary
                : themeColors.textSecondary,
              fontWeight: isTrainerActive ? '700' : '500',
            },
          ]}>
          Huấn luyện viên
        </Text>
      </Pressable>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    flexDirection: 'row',
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.xs,
    marginBottom: spacing.md,
  },
  tab: {
    flex: 1,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: layout.minimumTouchTarget,
  },
  tabText: {
    ...typography.bodySmall,
  },
});
