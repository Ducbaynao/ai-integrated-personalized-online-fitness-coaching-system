import React from 'react';
import { StyleSheet, Text, View } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { FitnessGoal } from '@/types/goal';
import { GoalStatusBadge } from '@/features/goal/components/GoalStatusBadge';

interface GoalOverviewTabProps {
  goal: FitnessGoal;
  isDark: boolean;
}

export function GoalOverviewTab({ goal, isDark }: GoalOverviewTabProps) {
  const themeColors = getSemanticColors(isDark);
  const version = goal.currentVersion;
  const objectives = version?.objectives ?? [];
  const primaryObjective = objectives.find((o) => o.priority === 'PRIMARY');
  const secondaryObjectives = objectives.filter((o) => o.priority === 'SECONDARY');

  return (
    <View style={styles.container}>
      {/* Status & Prominence Banner if Paused */}
      {goal.status === 'PAUSED' && (
        <View
          testID="paused-status-banner"
          accessibilityRole="alert"
          accessibilityLabel="Goal is currently paused"
          style={[
            styles.banner,
            {
              backgroundColor: themeColors.warningSurface,
              borderColor: themeColors.warningText,
            },
          ]}>
          <Text style={[styles.bannerTitle, { color: themeColors.warningText }]}>
            ⏸ Goal is Currently Paused
          </Text>
          <Text style={[styles.bannerBody, { color: themeColors.textPrimary }]}>
            Active training time is frozen. Your target dates and history remain preserved.
          </Text>
          {goal.pausedAt && (
            <Text style={[styles.bannerDate, { color: themeColors.textSecondary }]}>
              Paused at: {new Date(goal.pausedAt).toLocaleString()}
            </Text>
          )}
          {goal.statusReason && (
            <Text style={[styles.bannerReason, { color: themeColors.textPrimary }]}>
              {`Reason: "${goal.statusReason}"`}
            </Text>
          )}
        </View>
      )}

      {/* Main Info Card */}
      <View
        style={[
          styles.card,
          {
            backgroundColor: themeColors.surface,
            borderColor: themeColors.border,
          },
        ]}>
        <View style={styles.headerRow}>
          <Text testID="goal-title" style={[styles.title, { color: themeColors.textPrimary }]}>{goal.title}</Text>
          <GoalStatusBadge status={goal.status} />
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Goal Version</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version ? `Version ${version.versionNumber}` : 'Initial'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Start Date</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version?.startDate ?? 'Unknown'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Target Date</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version?.targetDate ?? 'Not set'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Timeline Duration</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version?.durationDays ? `${version.durationDays} days` : 'Calculated'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Last Updated</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {goal.updatedAt ? new Date(goal.updatedAt).toLocaleDateString() : 'Unknown'}
          </Text>
        </View>

        {goal.statusReason && goal.status !== 'PAUSED' && (
          <View style={styles.reasonRow}>
            <Text style={[styles.label, { color: themeColors.textSecondary }]}>Status Note</Text>
            <Text style={[styles.noteText, { color: themeColors.textPrimary }]}>
              {goal.statusReason}
            </Text>
          </View>
        )}
      </View>

      {/* Objectives Card */}
      <View
        style={[
          styles.card,
          {
            backgroundColor: themeColors.surface,
            borderColor: themeColors.border,
          },
        ]}>
        <Text style={[styles.sectionTitle, { color: themeColors.textPrimary }]}>
          Objectives
        </Text>

        {primaryObjective ? (
          <View style={styles.objectiveItem}>
            <View style={styles.objectiveHeader}>
              <Text style={[styles.objectiveType, { color: themeColors.textPrimary }]}>
                {primaryObjective.goalTypeName}
              </Text>
              <View
                style={[
                  styles.priorityTag,
                  { backgroundColor: themeColors.brandSoft },
                ]}>
                <Text style={[styles.priorityText, { color: themeColors.primary }]}>
                  PRIMARY
                </Text>
              </View>
            </View>
            {primaryObjective.notes && (
              <Text style={[styles.objectiveNotes, { color: themeColors.textSecondary }]}>
                {primaryObjective.notes}
              </Text>
            )}
          </View>
        ) : (
          <Text style={[styles.emptyText, { color: themeColors.textSecondary }]}>
            No primary objective specified.
          </Text>
        )}

        {secondaryObjectives.map((sec) => (
          <View key={sec.id} style={styles.objectiveItem}>
            <View style={styles.objectiveHeader}>
              <Text style={[styles.objectiveType, { color: themeColors.textPrimary }]}>
                {sec.goalTypeName}
              </Text>
              <View
                style={[
                  styles.priorityTag,
                  { backgroundColor: themeColors.surfaceSubtle },
                ]}>
                <Text style={[styles.priorityText, { color: themeColors.textSecondary }]}>
                  SECONDARY
                </Text>
              </View>
            </View>
            {sec.notes && (
              <Text style={[styles.objectiveNotes, { color: themeColors.textSecondary }]}>
                {sec.notes}
              </Text>
            )}
          </View>
        ))}
      </View>
    </View>
  );
}

const styles = StyleSheet.create({
  container: {
    gap: spacing.md,
  },
  banner: {
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.md,
    gap: spacing.xs,
  },
  bannerTitle: {
    ...typography.label,
    fontWeight: '700',
  },
  bannerBody: {
    ...typography.bodySmall,
    lineHeight: 18,
  },
  bannerDate: {
    ...typography.caption,
  },
  bannerReason: {
    ...typography.bodySmall,
    fontStyle: 'italic',
    marginTop: spacing.xxs,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.sm,
  },
  headerRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    gap: spacing.sm,
    marginBottom: spacing.xs,
  },
  title: {
    ...typography.h3,
    flex: 1,
  },
  sectionTitle: {
    ...typography.h3,
    marginBottom: spacing.xs,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  label: {
    ...typography.bodySmall,
  },
  value: {
    ...typography.label,
  },
  reasonRow: {
    marginTop: spacing.xs,
    gap: spacing.xxs,
  },
  noteText: {
    ...typography.bodySmall,
    fontStyle: 'italic',
  },
  objectiveItem: {
    paddingVertical: spacing.xs,
    borderBottomWidth: StyleSheet.hairlineWidth,
    borderBottomColor: '#E1E5EC',
    gap: spacing.xxs,
  },
  objectiveHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  objectiveType: {
    ...typography.label,
  },
  priorityTag: {
    paddingHorizontal: spacing.xs,
    paddingVertical: 2,
    borderRadius: radius.sm,
  },
  priorityText: {
    ...typography.caption,
    fontWeight: '600',
  },
  objectiveNotes: {
    ...typography.bodySmall,
  },
  emptyText: {
    ...typography.bodySmall,
    fontStyle: 'italic',
  },
});
