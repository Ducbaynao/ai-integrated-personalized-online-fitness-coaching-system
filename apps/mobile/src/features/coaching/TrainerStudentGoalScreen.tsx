import React, { useCallback, useEffect, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Text,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { useRelationshipDetail } from './coachingQueries';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { FitnessGoal, GoalProposal } from '@/types/goal';
import { ApiError } from '@/types/auth';
import { CreateGoalProposalModal } from '../goal/components/CreateGoalProposalModal';

export function TrainerStudentGoalScreen({ relationshipId }: { relationshipId: string }) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);
  const router = useRouter();

  const relationshipQuery = useRelationshipDetail(relationshipId);
  const relationship = relationshipQuery.data?.relationship ?? null;
  const studentId = relationship?.studentId ?? null;

  const [goal, setGoal] = useState<FitnessGoal | null>(null);
  const [proposals, setProposals] = useState<GoalProposal[]>([]);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [isModalOpen, setIsModalOpen] = useState(false);
  const [isSubmittingProposal, setIsSubmittingProposal] = useState(false);

  const loadData = useCallback(() => {
    if (!studentId) return;
    return fitnessGoalApi.getStudentCurrentGoalForTrainer(studentId).then(async (studentGoal) => {
      setError(null);
      setGoal(studentGoal);

      try {
        const proposalPage = await fitnessGoalApi.getStudentGoalProposalsForTrainer(studentId, {
          fitnessGoalId: studentGoal.id,
        });
        setProposals(proposalPage.items || []);
      } catch {
        // Non-blocking if proposals fail
      }
    }).catch((err: any) => {
      if (err instanceof ApiError && err.status === 404) {
        setGoal(null);
        setError('Học viên chưa có mục tiêu thể chất đang hoạt động.');
      } else {
        setError(err?.message || 'Không thể tải mục tiêu thể chất của học viên.');
      }
    }).finally(() => {
      setIsLoading(false);
      setIsRefreshing(false);
    });
  }, [studentId]);

  useEffect(() => {
    if (studentId) {
      void loadData();
    }
  }, [studentId, loadData]);

  const handleRefresh = () => {
    setIsRefreshing(true);
    setError(null);
    void loadData();
  };

  const handleRetry = () => {
    setIsLoading(true);
    setError(null);
    void loadData();
  };

  const handleCreateProposal = async (payload: {
    proposedTitle?: string;
    proposedDurationDays?: number;
    reason: string;
    goalTypeCode: string;
  }) => {
    if (!goal || isSubmittingProposal) return;
    setIsSubmittingProposal(true);
    try {
      await fitnessGoalApi.createGoalProposal(goal.id, {
        proposedTitle: payload.proposedTitle,
        proposedDurationDays: payload.proposedDurationDays,
        reason: payload.reason,
        objectives: [
          {
            goalTypeCode: payload.goalTypeCode,
            priority: 'PRIMARY',
          },
        ],
      });
      setIsModalOpen(false);
      Alert.alert('Thành công', 'Đề xuất thay đổi mục tiêu đã được gửi tới học viên.');
      await loadData();
    } catch (err: any) {
      if (err instanceof ApiError && err.status === 409) {
        Alert.alert('Xung đột', err.errorResponse?.message || 'Không thể gửi đề xuất do mục tiêu đã thay đổi trạng thái.');
      } else {
        Alert.alert('Gửi đề xuất thất bại', err?.message || 'Đã có lỗi xảy ra.');
      }
      throw err;
    } finally {
      setIsSubmittingProposal(false);
    }
  };

  if (relationshipQuery.isPending || (isLoading && !goal && !error)) {
    return (
      <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
        <View style={styles.centerContainer} testID="trainer-goal-loading">
          <ActivityIndicator size="large" color={theme.primary} />
          <Text style={[styles.loadingText, { color: theme.textSecondary }]}>
            Đang tải mục tiêu của học viên...
          </Text>
        </View>
      </SafeAreaView>
    );
  }

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
      <ScrollView
        testID="trainer-student-goal-screen"
        style={styles.screen}
        contentContainerStyle={styles.content}
        refreshControl={<RefreshControl refreshing={isRefreshing} onRefresh={handleRefresh} />}>
        {/* Header */}
        <View style={styles.headerRow}>
          <Pressable
            testID="back-to-relationship-button"
            accessibilityRole="button"
            accessibilityLabel="Quay lại chi tiết quan hệ"
            onPress={() => router.back()}
            style={[styles.backButton, { borderColor: theme.border }]}>
            <Text style={[styles.backButtonText, { color: theme.textPrimary }]}>← Quay lại</Text>
          </Pressable>
          <Text style={[styles.screenTitle, { color: theme.textPrimary }]}>Mục tiêu thể chất</Text>
        </View>

        {/* Error / Empty Card */}
        {error && (
          <View
            testID="trainer-goal-error-card"
            style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
            <Text style={[styles.cardTitle, { color: theme.dangerText }]}>Thông báo</Text>
            <Text style={[styles.cardBody, { color: theme.textSecondary }]}>{error}</Text>
            <Pressable
              testID="retry-trainer-goal-button"
              accessibilityRole="button"
              accessibilityLabel="Thử lại"
              style={[styles.primaryButton, { backgroundColor: theme.primary, marginTop: spacing.md }]}
              onPress={handleRetry}>
              <Text style={styles.primaryButtonText}>Tải lại</Text>
            </Pressable>
          </View>
        )}

        {/* Current Active Goal Details */}
        {goal && (
          <>
            <View
              testID="student-active-goal-card"
              style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
              <View style={styles.badgeRow}>
                <Text style={[styles.cardTitle, { color: theme.textPrimary, flex: 1 }]}>
                  {goal.title}
                </Text>
                <View
                  testID="goal-status-badge"
                  style={[
                    styles.badge,
                    {
                      backgroundColor:
                        goal.status === 'ACTIVE' ? theme.successSurface : theme.warningSurface,
                    },
                  ]}>
                  <Text
                    style={[
                      styles.badgeText,
                      {
                        color:
                          goal.status === 'ACTIVE' ? theme.successText : theme.warningText,
                      },
                    ]}>
                    {goal.status}
                  </Text>
                </View>
              </View>

              {goal.currentVersion && (
                <View style={styles.detailsBlock}>
                  <Text style={[styles.detailText, { color: theme.textSecondary }]}>
                    Phiên bản hiện hành: #{goal.currentVersion.versionNumber} ({goal.currentVersion.title})
                  </Text>
                  {goal.currentVersion.durationDays && (
                    <Text style={[styles.detailText, { color: theme.textSecondary }]}>
                      Thời lượng: {goal.currentVersion.durationDays} ngày
                    </Text>
                  )}
                  {goal.currentVersion.startDate && (
                    <Text style={[styles.detailText, { color: theme.textSecondary }]}>
                      Bắt đầu: {goal.currentVersion.startDate}
                      {goal.currentVersion.targetDate ? ` → Dự kiến kết thúc: ${goal.currentVersion.targetDate}` : ''}
                    </Text>
                  )}

                  {/* Objectives */}
                  {goal.currentVersion.objectives && goal.currentVersion.objectives.length > 0 && (
                    <View style={styles.objectivesBlock}>
                      <Text style={[styles.subHeading, { color: theme.textPrimary }]}>
                        Mục tiêu trọng tâm:
                      </Text>
                      {goal.currentVersion.objectives.map((obj) => (
                        <Text
                          key={obj.id}
                          style={[styles.bulletItem, { color: theme.textSecondary }]}>
                          • {obj.goalTypeName || obj.goalTypeCode} ({obj.priority})
                        </Text>
                      ))}
                    </View>
                  )}
                </View>
              )}

              {/* Action Button to Propose */}
              {goal.status === 'ACTIVE' && (
                <Pressable
                  testID="propose-goal-change-button"
                  accessibilityRole="button"
                  accessibilityLabel="Đề xuất điều chỉnh mục tiêu"
                  style={[
                    styles.primaryButton,
                    { backgroundColor: theme.primary, marginTop: spacing.md },
                  ]}
                  onPress={() => setIsModalOpen(true)}>
                  <Text style={styles.primaryButtonText}>✎ Đề xuất điều chỉnh mục tiêu</Text>
                </Pressable>
              )}
            </View>

            {/* Proposal History Section */}
            <View style={styles.proposalsSection}>
              <Text style={[styles.sectionHeading, { color: theme.textPrimary }]}>
                Lịch sử đề xuất điều chỉnh ({proposals.length})
              </Text>

              {proposals.length === 0 ? (
                <View
                  testID="empty-proposals-message"
                  style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                  <Text style={[styles.cardBody, { color: theme.textSecondary }]}>
                    Chưa có đề xuất điều chỉnh nào được gửi cho mục tiêu này.
                  </Text>
                </View>
              ) : (
                proposals.map((prop) => (
                  <View
                    key={prop.id}
                    testID={`proposal-card-${prop.id}`}
                    style={[
                      styles.card,
                      { backgroundColor: theme.surface, borderColor: theme.border },
                    ]}>
                    <View style={styles.badgeRow}>
                      <Text style={[styles.proposalTitle, { color: theme.textPrimary, flex: 1 }]}>
                        {prop.proposedTitle || 'Điều chỉnh mục tiêu'}
                      </Text>
                      <View
                        style={[
                          styles.badge,
                          {
                            backgroundColor:
                              prop.status === 'ACCEPTED'
                                ? theme.successSurface
                                : prop.status === 'REJECTED'
                                ? theme.dangerSurface
                                : theme.warningSurface,
                          },
                        ]}>
                        <Text
                          style={[
                            styles.badgeText,
                            {
                              color:
                                prop.status === 'ACCEPTED'
                                  ? theme.successText
                                  : prop.status === 'REJECTED'
                                  ? theme.dangerText
                                  : theme.warningText,
                            },
                          ]}>
                          {prop.status}
                        </Text>
                      </View>
                    </View>

                    <Text style={[styles.detailText, { color: theme.textPrimary, marginTop: spacing.xs }]}>
                      Lý do HLV: {prop.reason}
                    </Text>

                    {prop.proposedDurationDays && (
                      <Text style={[styles.detailText, { color: theme.textSecondary }]}>
                        Thời lượng đề xuất: {prop.proposedDurationDays} ngày
                      </Text>
                    )}

                    {prop.decisionNote && (
                      <Text style={[styles.detailText, { color: theme.textSecondary, marginTop: spacing.xs }]}>
                        Phản hồi của học viên: {prop.decisionNote}
                      </Text>
                    )}

                    <Text style={[styles.captionText, { color: theme.textSecondary, marginTop: spacing.xs }]}>
                      Gửi ngày: {new Date(prop.createdAt).toLocaleDateString('vi-VN')}
                    </Text>
                  </View>
                ))
              )}
            </View>
          </>
        )}

        {/* Create Proposal Modal */}
        {goal && (
          <CreateGoalProposalModal
            visible={isModalOpen}
            isSubmitting={isSubmittingProposal}
            isDark={isDark}
            currentTitle={goal.title}
            onClose={() => setIsModalOpen(false)}
            onSubmit={handleCreateProposal}
          />
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  content: {
    padding: spacing.md,
    gap: spacing.md,
  },
  centerContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.xl,
  },
  loadingText: {
    ...typography.body,
    marginTop: spacing.sm,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginBottom: spacing.xs,
  },
  backButton: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.sm,
    justifyContent: 'center',
    alignItems: 'center',
  },
  backButtonText: {
    ...typography.label,
  },
  screenTitle: {
    ...typography.h2,
    flex: 1,
  },
  card: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.md,
  },
  cardTitle: {
    ...typography.h3,
  },
  cardBody: {
    ...typography.body,
    marginTop: spacing.xs,
  },
  badgeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
  },
  badge: {
    borderRadius: radius.sm,
    paddingHorizontal: spacing.sm,
    paddingVertical: 2,
  },
  badgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  detailsBlock: {
    marginTop: spacing.sm,
    gap: spacing.xs,
  },
  detailText: {
    ...typography.bodySmall,
  },
  subHeading: {
    ...typography.label,
    marginTop: spacing.xs,
  },
  objectivesBlock: {
    marginTop: spacing.xs,
    paddingLeft: spacing.xs,
    gap: 2,
  },
  bulletItem: {
    ...typography.caption,
  },
  primaryButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  primaryButtonText: {
    ...typography.label,
    fontWeight: '700',
    color: '#ffffff',
  },
  proposalsSection: {
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
  sectionHeading: {
    ...typography.h3,
  },
  proposalTitle: {
    ...typography.label,
    fontWeight: '700',
  },
  captionText: {
    ...typography.caption,
    fontSize: 11,
  },
});
