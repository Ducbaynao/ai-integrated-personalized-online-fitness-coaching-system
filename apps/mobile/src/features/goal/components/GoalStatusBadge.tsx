import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { colors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { fontFamily, typography } from '@/design-system/tokens/typography';
import { GoalLifecycleStatus, ProposalStatus } from '@/types/goal';
import { goalProposalStatusLabels, goalStatusLabels } from '@/localization/displayLabels';

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
          label: status === 'ACTIVE' ? goalStatusLabels.ACTIVE : goalProposalStatusLabels.ACCEPTED,
        };
      case 'PAUSED':
        return {
          bg: colors.warning[100],
          text: colors.warning[600],
          label: goalStatusLabels.PAUSED,
        };
      case 'PENDING':
        return {
          bg: colors.brand[50],
          text: colors.brand[700],
          label: goalProposalStatusLabels.PENDING,
        };
      case 'REJECTED':
      case 'ABANDONED':
        return {
          bg: colors.danger[100],
          text: colors.danger[600],
          label: status === 'REJECTED' ? goalProposalStatusLabels.REJECTED : goalStatusLabels.ABANDONED,
        };
      case 'COMPLETED':
        return {
          bg: colors.brand[100],
          text: colors.brand[700],
          label: goalStatusLabels.COMPLETED,
        };
      case 'DRAFT':
        return {
          bg: colors.neutral[100],
          text: colors.neutral[700],
          label: goalStatusLabels.DRAFT,
        };
      case 'ENDED':
      case 'REPLACED':
      case 'CANCELLED':
      case 'EXPIRED':
      case 'ARCHIVED':
        return {
          bg: colors.neutral[200],
          text: colors.neutral[700],
          label: status in goalStatusLabels
            ? goalStatusLabels[status as GoalLifecycleStatus]
            : goalProposalStatusLabels[status as ProposalStatus],
        };
      default:
        return {
          bg: colors.neutral[100],
          text: colors.neutral[700],
          label: 'Chưa rõ trạng thái',
        };
    }
  };

  const config = getBadgeStyle();

  return (
    <View
      testID={testID ?? `status-badge-${status.toLowerCase()}`}
      accessibilityRole="text"
      accessibilityLabel={`Trạng thái: ${config.label}`}
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
    fontFamily: fontFamily.bold,
  },
});
