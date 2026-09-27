import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { GoalTarget } from '@/types/goal';

interface GoalTargetsTabProps {
  targets: GoalTarget[];
  isDark: boolean;
}

export function GoalTargetsTab({ targets, isDark }: GoalTargetsTabProps) {
  const themeColors = getSemanticColors(isDark);

  if (targets.length === 0) {
    return (
      <View
        testID="empty-targets-card"
        style={[
          styles.emptyCard,
          {
            backgroundColor: themeColors.surface,
            borderColor: themeColors.border,
          },
        ]}>
        <Text style={[styles.emptyTitle, { color: themeColors.textPrimary }]}>
          No Measurable Targets
        </Text>
        <Text style={[styles.emptySubtitle, { color: themeColors.textSecondary }]}>
          This goal has no target metric measurements configured.
        </Text>
      </View>
    );
  }

  const formatTargetValue = (target: GoalTarget): string => {
    if (target.targetValue !== null && target.targetValue !== undefined) {
      return `${target.targetValue} ${target.unitSymbol}`;
    }
    if (
      target.targetMinValue !== null &&
      target.targetMinValue !== undefined &&
      target.targetMaxValue !== null &&
      target.targetMaxValue !== undefined
    ) {
      return `${target.targetMinValue} – ${target.targetMaxValue} ${target.unitSymbol}`;
    }
    if (target.targetMinValue !== null && target.targetMinValue !== undefined) {
      return `≥ ${target.targetMinValue} ${target.unitSymbol}`;
    }
    if (target.targetMaxValue !== null && target.targetMaxValue !== undefined) {
      return `≤ ${target.targetMaxValue} ${target.unitSymbol}`;
    }
    return 'Unknown';
  };

  const formatStartValue = (target: GoalTarget): string => {
    if (target.startValue !== null && target.startValue !== undefined) {
      return `${target.startValue} ${target.unitSymbol}`;
    }
    return 'Not recorded';
  };

  return (
    <View style={styles.container}>
      {targets.map((target) => (
        <View
          key={target.id}
          testID={`target-card-${target.id}`}
          style={[
            styles.card,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
            },
          ]}>
          <View style={styles.cardHeader}>
            <Text style={[styles.metricName, { color: themeColors.textPrimary }]}>
              {target.metricDisplayName}
            </Text>
            <View
              style={[
                styles.unitBadge,
                { backgroundColor: themeColors.surfaceSubtle },
              ]}>
              <Text style={[styles.unitText, { color: themeColors.textSecondary }]}>
                {target.unitCode} ({target.unitSymbol})
              </Text>
            </View>
          </View>

          <View style={styles.grid}>
            <View style={styles.gridItem}>
              <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                Baseline
              </Text>
              <Text style={[styles.metaValue, { color: themeColors.textPrimary }]}>
                {formatStartValue(target)}
              </Text>
            </View>

            <View style={styles.gridItem}>
              <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                Goal Target
              </Text>
              <Text
                style={[
                  styles.metaValue,
                  { color: themeColors.primary, fontWeight: '700' },
                ]}>
                {formatTargetValue(target)}
              </Text>
            </View>
          </View>

          {target.targetRepetitions !== null && target.targetRepetitions !== undefined && (
            <View style={styles.row}>
              <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                Target Repetitions:
              </Text>
              <Text style={[styles.rowValue, { color: themeColors.textPrimary }]}>
                {target.targetRepetitions} reps
              </Text>
            </View>
          )}

          {target.targetDate && (
            <View style={styles.row}>
              <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                Target Date:
              </Text>
              <Text style={[styles.rowValue, { color: themeColors.textPrimary }]}>
                {target.targetDate}
              </Text>
            </View>
          )}

          {target.notes && (
            <Text style={[styles.notes, { color: themeColors.textSecondary }]}>
              Note: {target.notes}
            </Text>
          )}
        </View>
      ))}
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.md,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.sm,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.xs,
  },
  metricName: {
    ...typography.h3,
    flex: 1,
  },
  unitBadge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
  },
  unitText: {
    ...typography.caption,
    fontWeight: '600',
  },
  grid: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    paddingVertical: spacing.xs,
    borderTopWidth: StyleSheet.hairlineWidth,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderColor: '#E1E5EC',
  },
  gridItem: {
    flex: 1,
    gap: 2,
  },
  metaLabel: {
    ...typography.caption,
  },
  metaValue: {
    ...typography.body,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  rowValue: {
    ...typography.bodySmall,
    fontWeight: '600',
  },
  notes: {
    ...typography.bodySmall,
    fontStyle: 'italic',
    marginTop: spacing.xxs,
  },
  emptyCard: {
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
    alignItems: 'center',
    gap: spacing.xs,
  },
  emptyTitle: {
    ...typography.h3,
  },
  emptySubtitle: {
    ...typography.bodySmall,
    textAlign: 'center',
  },
});
