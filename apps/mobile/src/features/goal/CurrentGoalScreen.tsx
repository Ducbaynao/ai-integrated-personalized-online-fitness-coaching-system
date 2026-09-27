import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
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
import { useFocusEffect, useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { FitnessGoal, GoalLifecycleStatus } from '@/types/goal';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/services/apiClient';
import { GoalOverviewTab } from '@/features/goal/components/GoalOverviewTab';
import { GoalTargetsTab } from '@/features/goal/components/GoalTargetsTab';
import { GoalHistoryTab } from '@/features/goal/components/GoalHistoryTab';
import { GoalProposalsTab } from '@/features/goal/components/GoalProposalsTab';
import { PauseResumeGoalModal } from '@/features/goal/components/PauseResumeGoalModal';
import { GoalScreenSkeleton } from '@/features/goal/components/GoalScreenSkeleton';

export function isGoalActionAllowed(
  action: 'pause' | 'resume',
  status?: GoalLifecycleStatus | null
): boolean {
  if (!status) return false;
  if (action === 'pause') return status === 'ACTIVE';
  if (action === 'resume') return status === 'PAUSED';
  return false;
}

type MainTab = 'overview' | 'targets' | 'history' | 'proposals';

type ReconciliationState =
  | { identity: string; status: 'pending' }
  | { identity: string; status: 'failed'; error?: string }
  | null;

const CURRENT_GOAL_IDENTITY = 'current:me';

function getGoalRouteIdentity(goalId?: string): string {
  const trimmed = goalId?.trim();
  return trimmed ? `goal-detail:${trimmed}` : CURRENT_GOAL_IDENTITY;
}

interface CurrentGoalScreenProps {
  goalId?: string;
}

export function CurrentGoalScreen({ goalId }: CurrentGoalScreenProps) {
  const router = useRouter();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const currentIdentity = getGoalRouteIdentity(goalId);

  const [activeTab, setActiveTab] = useState<MainTab>('overview');
  const [goal, setGoal] = useState<FitnessGoal | null>(null);
  const [loadedIdentity, setLoadedIdentity] = useState<string | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // Pause / Resume Modal State
  const [modalVisible, setModalVisible] = useState(false);
  const [modalAction, setModalAction] = useState<'pause' | 'resume'>('pause');
  const [isModalSubmitting, setIsModalSubmitting] = useState(false);

  // Reconciliation state for out-of-band / stale mutation sync
  const [reconciliationState, setReconciliationState] =
    useState<ReconciliationState>(null);

  // Synchronously invalidate and reset route data whenever identity/goalId changes
  const [renderedIdentity, setRenderedIdentity] = useState(currentIdentity);

  // Independent sequence references for load and mutation operations
  const loadRequestSeqRef = useRef(0);
  const mutationRequestSeqRef = useRef(0);
  const latestSubmittedMutationSeqRef = useRef(0);
  const currentIdentityRef = useRef(currentIdentity);

  const isMountedRef = useRef(true);
  const isFocusedRef = useRef(true);

  // Modal session key to ensure clean mount and discard previous input state
  const [modalSessionId, setModalSessionId] = useState(0);

  const [prevGoalStatus, setPrevGoalStatus] = useState<
    GoalLifecycleStatus | undefined
  >(goal?.status);
  const currentGoalRef = useRef(goal);

  if (renderedIdentity !== currentIdentity) {
    setRenderedIdentity(currentIdentity);
    setGoal(null);
    setLoadedIdentity(null);
    setError(null);
    setIsLoading(true);
    setIsRefreshing(false);
    setModalVisible(false);
    setIsModalSubmitting(false);
    setModalSessionId((prev) => prev + 1);
    setActiveTab('overview');
    setReconciliationState(null);
    setPrevGoalStatus(undefined);
  }

  // Adjust modal state during render if goal status changed and action is no longer allowed
  if (goal?.status !== prevGoalStatus) {
    setPrevGoalStatus(goal?.status);
    if (
      modalVisible &&
      !isModalSubmitting &&
      !isGoalActionAllowed(modalAction, goal?.status)
    ) {
      setModalVisible(false);
      setModalSessionId((prev) => prev + 1);
    }
  }

  useEffect(() => {
    currentGoalRef.current = goal;
  }, [goal]);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    currentIdentityRef.current = currentIdentity;
    loadRequestSeqRef.current++;
    mutationRequestSeqRef.current++;
    latestSubmittedMutationSeqRef.current = 0;
  }, [currentIdentity]);

  const isGoalMatchingRoute =
    loadedIdentity === currentIdentity && goal !== null;

  const isReconcilingThisRoute =
    reconciliationState !== null &&
    reconciliationState.identity === currentIdentity;

  const loadGoal = useCallback(
    async (options?: {
      showLoading?: boolean;
      silent?: boolean;
      isReconciliation?: boolean;
    }) => {
      const targetIdentity = currentIdentity;
      const requestId = ++loadRequestSeqRef.current;
      await Promise.resolve();

      if (options?.showLoading) {
        setIsLoading(true);
        setError(null);
      }

      try {
        let fetchedGoal: FitnessGoal;
        if (goalId) {
          fetchedGoal = await fitnessGoalApi.getGoal(goalId.trim());
        } else {
          fetchedGoal = await fitnessGoalApi.getCurrentGoal(true);
        }

        if (
          isMountedRef.current &&
          isFocusedRef.current &&
          loadRequestSeqRef.current === requestId &&
          currentIdentityRef.current === targetIdentity
        ) {
          setGoal(fetchedGoal);
          setLoadedIdentity(targetIdentity);
          setError(null);
          setReconciliationState(null);
        }
      } catch (err: any) {
        if (
          isMountedRef.current &&
          isFocusedRef.current &&
          loadRequestSeqRef.current === requestId &&
          currentIdentityRef.current === targetIdentity
        ) {
          if (options?.isReconciliation) {
            setReconciliationState({
              identity: targetIdentity,
              status: 'failed',
              error: err?.message || 'Failed to sync goal status.',
            });
            return;
          }
          if (options?.silent) {
            return;
          }
          if (err instanceof ApiError && err.status === 404) {
            if (goalId) {
              setError('Goal not found or inaccessible.');
              setGoal(null);
              setLoadedIdentity(null);
            } else {
              setGoal(null);
              setLoadedIdentity(targetIdentity);
              setError(null);
            }
          } else {
            setError(err?.message || 'Failed to load fitness goal.');
            setGoal(null);
            setLoadedIdentity(null);
          }
        }
      } finally {
        if (
          isMountedRef.current &&
          isFocusedRef.current &&
          loadRequestSeqRef.current === requestId &&
          currentIdentityRef.current === targetIdentity
        ) {
          setIsLoading(false);
          setIsRefreshing(false);
        }
      }
    },
    [currentIdentity, goalId]
  );

  useFocusEffect(
    useCallback(() => {
      isFocusedRef.current = true;
      setModalVisible(false);
      setIsModalSubmitting(false);
      setModalSessionId((prev) => prev + 1);

      const execute = async () => {
        await loadGoal();
      };
      execute();

      return () => {
        isFocusedRef.current = false;
        loadRequestSeqRef.current++;
        mutationRequestSeqRef.current++;
        setModalVisible(false);
        setIsModalSubmitting(false);
        setModalSessionId((prev) => prev + 1);
      };
    }, [loadGoal])
  );

  const handleRefresh = () => {
    setIsRefreshing(true);
    loadGoal({ showLoading: false });
  };

  const handleRetryReconciliation = () => {
    setReconciliationState({ identity: currentIdentity, status: 'pending' });
    loadGoal({ showLoading: false, silent: true, isReconciliation: true });
  };

  const handleOpenPause = () => {
    if (
      isReconcilingThisRoute ||
      !isGoalMatchingRoute ||
      !goal ||
      !isGoalActionAllowed('pause', goal.status)
    ) {
      return;
    }
    setModalSessionId((prev) => prev + 1);
    setModalAction('pause');
    setModalVisible(true);
  };

  const handleOpenResume = () => {
    if (
      isReconcilingThisRoute ||
      !isGoalMatchingRoute ||
      !goal ||
      !isGoalActionAllowed('resume', goal.status)
    ) {
      return;
    }
    setModalSessionId((prev) => prev + 1);
    setModalAction('resume');
    setModalVisible(true);
  };

  const handleModalSubmit = async (reason: string) => {
    const mutationIdentity = currentIdentity;
    const currentGoal = currentGoalRef.current || goal;

    if (
      !currentGoal ||
      !isGoalMatchingRoute ||
      loadedIdentity !== mutationIdentity ||
      isModalSubmitting ||
      isReconcilingThisRoute ||
      !isGoalActionAllowed(modalAction, currentGoal.status)
    ) {
      if (!isGoalActionAllowed(modalAction, currentGoal?.status)) {
        setModalVisible(false);
        setModalSessionId((prev) => prev + 1);
      }
      return;
    }

    const mutationGoalId = currentGoal.id;
    const mutationSeq = ++mutationRequestSeqRef.current;
    latestSubmittedMutationSeqRef.current = mutationSeq;

    setIsModalSubmitting(true);
    try {
      let updatedGoal: FitnessGoal;
      if (modalAction === 'pause') {
        updatedGoal = await fitnessGoalApi.pauseGoal(mutationGoalId, reason);
      } else {
        updatedGoal = await fitnessGoalApi.resumeGoal(mutationGoalId, reason);
      }

      if (
        isMountedRef.current &&
        isFocusedRef.current &&
        currentIdentityRef.current === mutationIdentity &&
        mutationRequestSeqRef.current === mutationSeq
      ) {
        // Invalidate any load/refresh requests started before this mutation completed
        loadRequestSeqRef.current++;
        setIsLoading(false);
        setIsRefreshing(false);

        setGoal(updatedGoal);
        setLoadedIdentity(mutationIdentity);
        setModalVisible(false);
        setReconciliationState(null);
      } else if (
        isMountedRef.current &&
        isFocusedRef.current &&
        currentIdentityRef.current === mutationIdentity &&
        mutationSeq === latestSubmittedMutationSeqRef.current
      ) {
        // Stale mutation from a previous focus session completed successfully after refocus on same route identity.
        // Mark current route data as unconfirmed.
        setReconciliationState({ identity: mutationIdentity, status: 'pending' });

        // Immediately close any unsubmitted modal and reset its input state
        setModalVisible(false);
        setModalSessionId((prev) => prev + 1);

        loadGoal({ showLoading: false, silent: true, isReconciliation: true });
      }
    } catch (err: any) {
      if (
        !isMountedRef.current ||
        !isFocusedRef.current ||
        currentIdentityRef.current !== mutationIdentity ||
        mutationRequestSeqRef.current !== mutationSeq
      ) {
        return;
      }

      let message = 'An unexpected error occurred while updating goal status.';
      if (err instanceof ApiError) {
        if (err.errorResponse?.errorCode === 'ACTIVE_FITNESS_GOAL_ALREADY_EXISTS') {
          message =
            'You already have another active fitness goal. Only one active goal is permitted at a time.';
        } else if (err.errorResponse?.errorCode === 'GOAL_LIFECYCLE_CONFLICT') {
          message =
            'The goal status was updated by another request. Please refresh to see the latest status.';
        } else if (err.status === 409) {
          message =
            err.errorResponse?.message ||
            'Goal status conflict occurred. Please refresh and try again.';
        } else if (err.errorResponse?.message) {
          message = err.errorResponse.message;
        }
      }
      Alert.alert('Status Update Failed', message);
      throw err;
    } finally {
      if (
        isMountedRef.current &&
        isFocusedRef.current &&
        currentIdentityRef.current === mutationIdentity &&
        mutationRequestSeqRef.current === mutationSeq
      ) {
        setIsModalSubmitting(false);
      }
    }
  };

  const renderTabContent = () => {
    if (!isGoalMatchingRoute || !goal) {
      if (activeTab === 'proposals') {
        return <GoalProposalsTab isDark={isDark} />;
      }
      return (
        <View
          testID="no-current-goal-card"
          style={[
            styles.emptyCard,
            { backgroundColor: themeColors.surface, borderColor: themeColors.border },
          ]}>
          <Text style={[styles.emptyTitle, { color: themeColors.textPrimary }]}>
            No Active Fitness Goal
          </Text>
          <Text style={[styles.emptySubtitle, { color: themeColors.textSecondary }]}>
            You currently do not have an active or paused fitness goal. When you or your coach
            establish a fitness goal, it will appear here.
          </Text>
          <Pressable
            testID="view-proposals-empty-button"
            accessibilityRole="button"
            accessibilityLabel="Check Pending Proposals"
            style={[styles.primaryActionButton, { backgroundColor: themeColors.primary }]}
            onPress={() => setActiveTab('proposals')}>
            <Text style={styles.primaryActionText}>Check Goal Proposals</Text>
          </Pressable>
        </View>
      );
    }

    switch (activeTab) {
      case 'overview':
        return <GoalOverviewTab goal={goal} isDark={isDark} />;
      case 'targets':
        return (
          <GoalTargetsTab
            targets={goal.currentVersion?.targets ?? []}
            isDark={isDark}
          />
        );
      case 'history':
        return <GoalHistoryTab goalId={goal.id} isDark={isDark} />;
      case 'proposals':
        return <GoalProposalsTab goalId={goal.id} isDark={isDark} />;
    }
  };

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      {/* Top Header */}
      <View style={[styles.header, { borderBottomColor: themeColors.border }]}>
        <Pressable
          testID="goal-screen-back-button"
          accessibilityRole="button"
          accessibilityLabel="Go back"
          style={styles.backButton}
          onPress={() => {
            if (router.canGoBack()) {
              router.back();
            } else {
              router.replace('/' as any);
            }
          }}>
          <Text style={[styles.backButtonText, { color: themeColors.primary }]}>← Back</Text>
        </Pressable>
        <Text style={[styles.screenTitle, { color: themeColors.textPrimary }]}>
          Fitness Goal
        </Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView
        contentContainerStyle={styles.scrollContent}
        refreshControl={
          <RefreshControl
            refreshing={isRefreshing}
            onRefresh={handleRefresh}
            tintColor={themeColors.primary}
          />
        }>
        {/* Loading State: Skeleton */}
        {isLoading && <GoalScreenSkeleton isDark={isDark} />}

        {/* Error State */}
        {!isLoading && error && (
          <View
            testID="goal-error-card"
            style={[
              styles.errorCard,
              { backgroundColor: themeColors.surface, borderColor: themeColors.border },
            ]}>
            <Text style={[styles.errorTitle, { color: themeColors.dangerText }]}>
              Unable to Load Goal
            </Text>
            <Text style={[styles.errorSubtitle, { color: themeColors.textSecondary }]}>
              {error}
            </Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Retry loading goal"
              style={[styles.primaryActionButton, { backgroundColor: themeColors.primary }]}
              onPress={() => loadGoal({ showLoading: true })}>
              <Text style={styles.primaryActionText}>Retry</Text>
            </Pressable>
          </View>
        )}

        {/* Loaded Content */}
        {!isLoading && !error && (
          <>
            {/* Quick Actions (Pause / Resume) if Goal Exists and not reconciling */}
            {isGoalMatchingRoute && goal && !isReconcilingThisRoute && (
              <View style={styles.actionRow}>
                {isGoalActionAllowed('pause', goal.status) && (
                  <Pressable
                    testID="pause-goal-button"
                    accessibilityRole="button"
                    accessibilityLabel="Pause Goal"
                    style={({ pressed }) => [
                      styles.pauseActionButton,
                      {
                        borderColor: themeColors.warningText,
                        backgroundColor: pressed
                          ? themeColors.warningSurface
                          : themeColors.surface,
                      },
                    ]}
                    onPress={handleOpenPause}>
                    <Text style={[styles.pauseActionText, { color: themeColors.warningText }]}>
                      ⏸ Pause Goal
                    </Text>
                  </Pressable>
                )}

                {isGoalActionAllowed('resume', goal.status) && (
                  <Pressable
                    testID="resume-goal-button"
                    accessibilityRole="button"
                    accessibilityLabel="Resume Goal"
                    style={({ pressed }) => [
                      styles.resumeActionButton,
                      {
                        backgroundColor: pressed
                          ? themeColors.primaryPressed
                          : themeColors.primary,
                      },
                    ]}
                    onPress={handleOpenResume}>
                    <Text style={styles.resumeActionText}>▶ Resume Goal</Text>
                  </Pressable>
                )}
              </View>
            )}

            {/* Reconciliation Retry Banner */}
            {isGoalMatchingRoute &&
              goal &&
              isReconcilingThisRoute &&
              reconciliationState?.status === 'failed' && (
                <View
                  testID="reconciliation-retry-banner"
                  style={[
                    styles.reconciliationCard,
                    {
                      backgroundColor: themeColors.warningSurface,
                      borderColor: themeColors.warningText,
                    },
                  ]}>
                  <View style={styles.reconciliationCardContent}>
                    <Text
                      style={[
                        styles.reconciliationCardTitle,
                        { color: themeColors.warningText },
                      ]}>
                      Unable to sync latest goal status
                    </Text>
                    <Text
                      style={[
                        styles.reconciliationCardSubtitle,
                        { color: themeColors.textSecondary },
                      ]}>
                      Please retry to update your goal status before making changes.
                    </Text>
                  </View>
                  <Pressable
                    testID="retry-reconciliation-button"
                    accessibilityRole="button"
                    accessibilityLabel="Retry syncing goal status"
                    style={[styles.retryActionButton, { backgroundColor: themeColors.primary }]}
                    onPress={handleRetryReconciliation}>
                    <Text style={styles.retryActionText}>Retry Sync</Text>
                  </Pressable>
                </View>
            )}

            {/* Navigation Tabs */}
            <View
              style={[
                styles.tabBar,
                {
                  backgroundColor: themeColors.surfaceSubtle,
                  borderColor: themeColors.border,
                },
              ]}>
              {(['overview', 'targets', 'history', 'proposals'] as MainTab[]).map((tab) => {
                const isSelected = activeTab === tab;
                const label = tab.charAt(0).toUpperCase() + tab.slice(1);
                return (
                  <Pressable
                    key={tab}
                    testID={`tab-${tab}-button`}
                    accessibilityRole="tab"
                    accessibilityLabel={`${label} Tab`}
                    accessibilityState={{ selected: isSelected }}
                    style={[
                      styles.tabItem,
                      isSelected && [
                        styles.activeTabItem,
                        { backgroundColor: themeColors.surface },
                      ],
                    ]}
                    onPress={() => setActiveTab(tab)}>
                    <Text
                      style={[
                        styles.tabText,
                        {
                          color: isSelected
                            ? themeColors.primary
                            : themeColors.textSecondary,
                          fontWeight: isSelected ? '700' : '500',
                        },
                      ]}>
                      {label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>

            {/* Active Tab View */}
            <View style={styles.tabContentContainer}>{renderTabContent()}</View>
          </>
        )}
      </ScrollView>

      {/* Pause / Resume Modal */}
      {isGoalMatchingRoute &&
        goal &&
        modalVisible &&
        !isReconcilingThisRoute &&
        isGoalActionAllowed(modalAction, goal.status) && (
          <PauseResumeGoalModal
            key={modalSessionId}
            visible={modalVisible}
            action={modalAction}
            goalTitle={goal.title}
            isSubmitting={isModalSubmitting}
            isDark={isDark}
            onClose={() => setModalVisible(false)}
            onSubmit={handleModalSubmit}
          />
      )}
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: layout.mobileScreenPadding,
    paddingVertical: spacing.sm,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  backButton: {
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
    paddingRight: spacing.sm,
  },
  backButtonText: {
    ...typography.label,
    fontWeight: '600',
  },
  screenTitle: {
    ...typography.h3,
  },
  headerSpacer: {
    width: 48,
  },
  scrollContent: {
    padding: layout.mobileScreenPadding,
    gap: spacing.md,
  },
  errorCard: {
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
    alignItems: 'center',
    gap: spacing.sm,
  },
  errorTitle: {
    ...typography.h3,
  },
  errorSubtitle: {
    ...typography.bodySmall,
    textAlign: 'center',
  },
  emptyCard: {
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
    alignItems: 'center',
    gap: spacing.md,
  },
  emptyTitle: {
    ...typography.h3,
  },
  emptySubtitle: {
    ...typography.bodySmall,
    textAlign: 'center',
    lineHeight: 20,
  },
  actionRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
  },
  pauseActionButton: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1.5,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  pauseActionText: {
    ...typography.label,
    fontWeight: '700',
  },
  resumeActionButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  resumeActionText: {
    ...typography.label,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  primaryActionButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.xl,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: spacing.xs,
  },
  primaryActionText: {
    ...typography.label,
    fontWeight: '700',
    color: '#FFFFFF',
  },
  tabBar: {
    flexDirection: 'row',
    borderRadius: radius.md,
    borderWidth: 1,
    padding: 3,
  },
  tabItem: {
    flex: 1,
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
    alignItems: 'center',
    borderRadius: radius.sm,
  },
  activeTabItem: {
    shadowColor: '#000',
    shadowOpacity: 0.05,
    shadowRadius: 2,
    elevation: 1,
  },
  tabText: {
    ...typography.label,
    fontSize: 13,
  },
  tabContentContainer: {
    marginTop: spacing.xs,
  },
  reconciliationCard: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    gap: spacing.sm,
    marginBottom: spacing.xs,
  },
  reconciliationCardContent: {
    flex: 1,
  },
  reconciliationCardTitle: {
    ...typography.label,
    fontWeight: '700',
    marginBottom: 2,
  },
  reconciliationCardSubtitle: {
    ...typography.caption,
    lineHeight: 16,
  },
  retryActionButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  retryActionText: {
    ...typography.label,
    fontWeight: '700',
    color: '#FFFFFF',
  },
});
