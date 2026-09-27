import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { GoalLifecycleStatus, ProposalStatus } from '@/types/goal';

interface GoalStatusBadgeProps {
  status: GoalLifecycleStatus | ProposalStatus;
  testID?: string;
}

export function GoalStatusBadge({ status, testID }: GoalStatusBadgeProps) {
  const getBadgeStyle = () => {
    switch (status) {
      case 'ACTIVE':
      case 'ACCEPTED':
        return {
          bg: colors.success[100],
          text: colors.success[600],
          label: status === 'ACTIVE' ? 'Active' : 'Accepted',
        };
      case 'PAUSED':
        return {
          bg: colors.warning[100],
          text: colors.warning[600],
          label: 'Paused',
        };
      case 'PENDING':
        return {
          bg: colors.brand[50],
          text: colors.brand[700],
          label: 'Pending Approval',
        };
      case 'REJECTED':
      case 'ABANDONED':
        return {
          bg: colors.danger[100],
          text: colors.danger[600],
          label: status === 'REJECTED' ? 'Rejected' : 'Abandoned',
        };
      case 'COMPLETED':
        return {
          bg: colors.brand[100],
          text: colors.brand[700],
          label: 'Completed',
        };
      case 'DRAFT':
        return {
          bg: colors.neutral[100],
          text: colors.neutral[700],
          label: 'Draft',
        };
      case 'ENDED':
      case 'REPLACED':
      case 'CANCELLED':
      case 'EXPIRED':
      case 'ARCHIVED':
        return {
          bg: colors.neutral[200],
          text: colors.neutral[700],
          label: status.charAt(0) + status.slice(1).toLowerCase(),
        };
      default:
        return {
          bg: colors.neutral[100],
          text: colors.neutral[700],
          label: status,
        };
    }
  };

  const config = getBadgeStyle();

  return (
    <View
      testID={testID ?? `status-badge-${status.toLowerCase()}`}
      accessibilityRole="text"
      accessibilityLabel={`Status: ${config.label}`}
      style={[styles.badge, { backgroundColor: config.bg }]}>
      <Text style={[styles.text, { color: config.text }]}>{config.label}</Text>
    </View>
  );
}

const styles = StyleSheet.create({
  badge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.full,
    alignSelf: 'flex-start',
  },
  text: {
    ...typography.caption,
    fontWeight: '700',
  },
});
