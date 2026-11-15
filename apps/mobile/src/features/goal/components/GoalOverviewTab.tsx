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
          accessibilityLabel="Mục tiêu hiện đang bị tạm dừng"
          style={[
            styles.banner,
            {
              backgroundColor: themeColors.warningSurface,
              borderColor: themeColors.warningText,
            },
          ]}>
          <Text style={[styles.bannerTitle, { color: themeColors.warningText }]}>
            ⏸ Mục tiêu hiện đang bị tạm dừng
          </Text>
          <Text style={[styles.bannerBody, { color: themeColors.textPrimary }]}>
            Thời gian tập luyện tích lũy tạm dừng. Ngày mục tiêu và lịch sử vẫn được giữ nguyên.
          </Text>
          {goal.pausedAt && (
            <Text style={[styles.bannerDate, { color: themeColors.textSecondary }]}>
              Tạm dừng lúc: {new Date(goal.pausedAt).toLocaleString('vi-VN')}
            </Text>
          )}
          {goal.statusReason && (
            <Text style={[styles.bannerReason, { color: themeColors.textPrimary }]}>
              {`Lý do: "${goal.statusReason}"`}
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
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Phiên bản mục tiêu</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version ? `Phiên bản ${version.versionNumber}` : 'Ban đầu'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Ngày bắt đầu</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version?.startDate ?? 'Chưa rõ'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Ngày mục tiêu</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version?.targetDate ?? 'Chưa thiết lập'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Thời hạn mục tiêu</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {version?.durationDays ? `${version.durationDays} ngày` : 'Được tính tự động'}
          </Text>
        </View>

        <View style={styles.row}>
          <Text style={[styles.label, { color: themeColors.textSecondary }]}>Cập nhật lần cuối</Text>
          <Text style={[styles.value, { color: themeColors.textPrimary }]}>
            {goal.updatedAt ? new Date(goal.updatedAt).toLocaleDateString('vi-VN') : 'Chưa rõ'}
          </Text>
        </View>

        {goal.statusReason && goal.status !== 'PAUSED' && (
          <View style={styles.reasonRow}>
            <Text style={[styles.label, { color: themeColors.textSecondary }]}>Ghi chú trạng thái</Text>
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
          Mục tiêu
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
                  CHÍNH
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
            Chưa xác định loại mục tiêu chính.
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
                  PHỤ
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
