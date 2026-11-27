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
import { apiErrorMessage } from '@/localization/displayLabels';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { fontFamily, typography } from '@/design-system/tokens/typography';
import { FitnessGoal, GoalLifecycleStatus } from '@/types/goal';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/services/apiClient';
import { GoalOverviewTab } from '@/features/goal/components/GoalOverviewTab';
import { GoalTargetsTab } from '@/features/goal/components/GoalTargetsTab';
import { GoalHistoryTab } from '@/features/goal/components/GoalHistoryTab';
import { GoalProposalsTab } from '@/features/goal/components/GoalProposalsTab';
import { PauseResumeGoalModal } from '@/features/goal/components/PauseResumeGoalModal';
import { CreateGoalModal } from '@/features/goal/components/CreateGoalModal';
import { CreateGoalVersionModal } from '@/features/goal/components/CreateGoalVersionModal';
import { CreateGoalTransitionModal } from '@/features/goal/components/CreateGoalTransitionModal';
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

type GoalEditorContext = {
  target: 'version' | 'transition';
  goalId: string;
  goalTitle: string;
  goalTypeCode: string | null;
} | null;

const GOAL_TARGET_MISMATCH_MESSAGE =
  'Mục tiêu đang hiển thị đã thay đổi. Vui lòng đóng và mở lại biểu mẫu cho đúng mục tiêu hiện tại.';

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

  // Goal creation, version adjustment, and transition modal states
  const [createGoalModalVisible, setCreateGoalModalVisible] = useState(false);
  const [createVersionModalVisible, setCreateVersionModalVisible] = useState(false);
  const [createTransitionModalVisible, setCreateTransitionModalVisible] = useState(false);
  const [goalEditorContext, setGoalEditorContext] = useState<GoalEditorContext>(null);
  const [isCreateGoalSubmitting, setIsCreateGoalSubmitting] = useState(false);
  const [conflictBanner, setConflictBanner] = useState<string | null>(null);

  // Reconciliation state for out-of-band / stale mutation sync
  const [reconciliationState, setReconciliationState] =
    useState<ReconciliationState>(null);

  // Synchronously invalidate and reset route data whenever identity/goalId changes
  const [renderedIdentity, setRenderedIdentity] = useState(currentIdentity);

  // Independent sequence references for load and mutation operations
  const loadRequestSeqRef = useRef(0);
  const mutationRequestSeqRef = useRef(0);
  const latestSubmittedMutationSeqRef = useRef(0);
  const goalModalSessionSeqRef = useRef(0);
  const goalMutationSeqRef = useRef(0);
  const currentIdentityRef = useRef(currentIdentity);

  const isMountedRef = useRef(true);
  const isFocusedRef = useRef(true);

  // Modal session key to ensure clean mount and discard previous input state
  const [modalSessionId, setModalSessionId] = useState(0);
  const [goalModalSessionId, setGoalModalSessionId] = useState(0);

  const [prevGoalStatus, setPrevGoalStatus] = useState<
    GoalLifecycleStatus | undefined
  >(goal?.status);
  const currentGoalRef = useRef(goal);
  const goalEditorContextRef = useRef<GoalEditorContext>(null);

  if (renderedIdentity !== currentIdentity) {
    setRenderedIdentity(currentIdentity);
    setGoal(null);
    setLoadedIdentity(null);
    setError(null);
    setIsLoading(true);
    setIsRefreshing(false);
    setModalVisible(false);
    setIsModalSubmitting(false);
    setCreateGoalModalVisible(false);
    setCreateVersionModalVisible(false);
    setCreateTransitionModalVisible(false);
    setGoalEditorContext(null);
    setIsCreateGoalSubmitting(false);
    setModalSessionId((prev) => prev + 1);
    setGoalModalSessionId((prev) => prev + 1);
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
    goalEditorContextRef.current = null;
    loadRequestSeqRef.current++;
    mutationRequestSeqRef.current++;
    goalModalSessionSeqRef.current++;
    goalMutationSeqRef.current++;
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
          const isAccessOrMissingError =
            err instanceof ApiError &&
            (err.status === 401 || err.status === 403 || err.status === 404);
          if (options?.isReconciliation && !isAccessOrMissingError) {
            setReconciliationState({
              identity: targetIdentity,
              status: 'failed',
              error: apiErrorMessage(err, 'Không thể đồng bộ trạng thái mục tiêu.'),
            });
            return;
          }
          if (options?.silent && !isAccessOrMissingError) {
            return;
          }
          setReconciliationState(null);
          if (isAccessOrMissingError) {
            setModalVisible(false);
            setCreateGoalModalVisible(false);
            setCreateVersionModalVisible(false);
            setCreateTransitionModalVisible(false);
            setGoalEditorContext(null);
            goalEditorContextRef.current = null;
            setIsModalSubmitting(false);
            setIsCreateGoalSubmitting(false);
            setModalSessionId((prev) => prev + 1);
            setGoalModalSessionId((prev) => prev + 1);
            goalModalSessionSeqRef.current += 1;
            goalMutationSeqRef.current += 1;
          }
          if (err instanceof ApiError && err.status === 404) {
            if (goalId) {
              setError('Không tìm thấy mục tiêu hoặc bạn không có quyền xem.');
              setGoal(null);
              setLoadedIdentity(null);
            } else {
              setGoal(null);
              setLoadedIdentity(targetIdentity);
              setError(null);
            }
          } else {
            setError(apiErrorMessage(err, 'Không thể tải mục tiêu thể chất.'));
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
        goalModalSessionSeqRef.current++;
        goalMutationSeqRef.current++;
        setModalVisible(false);
        setIsModalSubmitting(false);
        setCreateGoalModalVisible(false);
        setCreateVersionModalVisible(false);
        setCreateTransitionModalVisible(false);
        setGoalEditorContext(null);
        goalEditorContextRef.current = null;
        setIsCreateGoalSubmitting(false);
        setModalSessionId((prev) => prev + 1);
        setGoalModalSessionId((prev) => prev + 1);
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

      let message = 'Có lỗi xảy ra khi cập nhật trạng thái mục tiêu.';
      if (err instanceof ApiError) {
        if (err.errorResponse?.errorCode === 'ACTIVE_FITNESS_GOAL_ALREADY_EXISTS') {
          message =
            'Bạn đã có một mục tiêu thể chất đang hoạt động. Mỗi lần chỉ được có một mục tiêu đang hoạt động.';
        } else if (err.errorResponse?.errorCode === 'GOAL_LIFECYCLE_CONFLICT') {
          message =
            'Trạng thái mục tiêu đã được thay đổi. Vui lòng tải lại để xem trạng thái mới nhất.';
        } else if (err.status === 409) {
          message = 'Trạng thái mục tiêu đã thay đổi. Vui lòng tải lại và thử lại.';
        } else {
          message = apiErrorMessage(err, message);
        }
      }
      Alert.alert('Không thể cập nhật trạng thái', message);
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

  const setGoalFormModalState = (
    target: 'create' | 'version' | 'transition',
    nextVisible: boolean
  ) => {
    if (nextVisible && (target === 'version' || target === 'transition')) {
      const activeGoal = currentGoalRef.current ?? goal;
      if (!activeGoal || !isGoalMatchingRoute) return;
      const captured: GoalEditorContext = {
        target,
        goalId: activeGoal.id,
        goalTitle: activeGoal.title,
        goalTypeCode:
          activeGoal.currentVersion?.objectives?.find((o) => o.priority === 'PRIMARY')
            ?.goalTypeCode ?? null,
      };
      goalEditorContextRef.current = captured;
      setGoalEditorContext(captured);
    } else {
      goalEditorContextRef.current = null;
      setGoalEditorContext(null);
    }
    goalModalSessionSeqRef.current += 1;
    goalMutationSeqRef.current += 1;
    setGoalModalSessionId((prev) => prev + 1);
    setIsCreateGoalSubmitting(false);
    if (target === 'create') setCreateGoalModalVisible(nextVisible);
    if (target === 'version') setCreateVersionModalVisible(nextVisible);
    if (target === 'transition') setCreateTransitionModalVisible(nextVisible);
  };

  const runGoalFormMutation = async (
    target: 'create' | 'version' | 'transition',
    operation: () => Promise<void>,
    messages: { conflict: string; alertTitle: string; fallback: string }
  ) => {
    if (isCreateGoalSubmitting) return;
    const mutationIdentity = currentIdentity;
    const modalSessionSeq = goalModalSessionSeqRef.current;
    const mutationSeq = ++goalMutationSeqRef.current;
    setIsCreateGoalSubmitting(true);
    setConflictBanner(null);
    try {
      await operation();
      const isSameModalSession =
        isMountedRef.current &&
        isFocusedRef.current &&
        currentIdentityRef.current === mutationIdentity &&
        goalModalSessionSeqRef.current === modalSessionSeq &&
        goalMutationSeqRef.current === mutationSeq;
      if (isSameModalSession) {
        goalEditorContextRef.current = null;
        setGoalEditorContext(null);
        if (target === 'create') setCreateGoalModalVisible(false);
        if (target === 'version') setCreateVersionModalVisible(false);
        if (target === 'transition') setCreateTransitionModalVisible(false);
      }
      if (
        isMountedRef.current &&
        isFocusedRef.current &&
        currentIdentityRef.current === mutationIdentity
      ) {
        if (isSameModalSession) {
          await loadGoal({ showLoading: true });
        } else {
          await loadGoal({
            showLoading: false,
            silent: true,
            isReconciliation: currentGoalRef.current !== null,
          });
        }
      }
    } catch (err: any) {
      const isSameRequest =
        isMountedRef.current &&
        isFocusedRef.current &&
        currentIdentityRef.current === mutationIdentity &&
        goalMutationSeqRef.current === mutationSeq;
      if (isSameRequest) {
        if (err instanceof ApiError && err.status === 409) {
          setConflictBanner(messages.conflict);
        } else {
          Alert.alert(messages.alertTitle, apiErrorMessage(err, messages.fallback));
        }
      }
      throw err;
    } finally {
      if (
        isMountedRef.current &&
        currentIdentityRef.current === mutationIdentity &&
        goalMutationSeqRef.current === mutationSeq
      ) {
        setIsCreateGoalSubmitting(false);
      }
    }
  };

  const handleCreateGoal = async (payload: {
    title: string;
    goalTypeCode: string;
    durationDays?: number;
    notes?: string;
  }) => {
    await runGoalFormMutation(
      'create',
      async () => {
        const today = new Date().toISOString().split('T')[0];
        await fitnessGoalApi.createGoal({
          title: payload.title,
          startDate: today,
          durationDays: payload.durationDays,
          activateImmediately: true,
          objectives: [
            {
              goalTypeCode: payload.goalTypeCode,
              priority: 'PRIMARY',
              notes: payload.notes,
            },
          ],
        });
      },
      {
        conflict:
          'Bạn đã có một mục tiêu thể chất đang hoạt động. Mỗi lần chỉ được có một mục tiêu đang hoạt động.',
        alertTitle: 'Không thể tạo mục tiêu',
        fallback: 'Không thể tạo mục tiêu thể chất.',
      }
    );
  };

  const handleCreateVersion = async (payload: {
    title?: string;
    changeReason: string;
    changeSummary?: string;
    durationDays?: number;
    goalTypeCode: string;
  }) => {
    const captured = goalEditorContextRef.current;
    const latestGoal = currentGoalRef.current ?? goal;
    if (
      !captured ||
      captured.target !== 'version' ||
      !latestGoal ||
      latestGoal.id !== captured.goalId
    ) {
      throw new Error(GOAL_TARGET_MISMATCH_MESSAGE);
    }
    const targetGoalId = captured.goalId;
    await runGoalFormMutation(
      'version',
      async () => {
        await fitnessGoalApi.createGoalVersion(targetGoalId, {
          title: payload.title,
          changeReason: payload.changeReason,
          changeSummary: payload.changeSummary,
          durationDays: payload.durationDays,
          objectives: [
            {
              goalTypeCode: payload.goalTypeCode,
              priority: 'PRIMARY',
            },
          ],
        });
      },
      {
        conflict: 'Phiên bản mục tiêu đã thay đổi. Vui lòng tải lại.',
        alertTitle: 'Không thể điều chỉnh phiên bản',
        fallback: 'Không thể điều chỉnh mục tiêu.',
      }
    );
  };

  const handleCreateTransition = async (payload: {
    transitionReason: string;
    notes?: string;
    title: string;
    goalTypeCode: string;
    durationDays?: number;
  }) => {
    const captured = goalEditorContextRef.current;
    const latestGoal = currentGoalRef.current ?? goal;
    if (
      !captured ||
      captured.target !== 'transition' ||
      !latestGoal ||
      latestGoal.id !== captured.goalId
    ) {
      throw new Error(GOAL_TARGET_MISMATCH_MESSAGE);
    }
    const targetGoalId = captured.goalId;
    await runGoalFormMutation(
      'transition',
      async () => {
        const today = new Date().toISOString().split('T')[0];
        await fitnessGoalApi.createGoalTransition(targetGoalId, {
          transitionReason: payload.transitionReason,
          notes: payload.notes,
          title: payload.title,
          startDate: today,
          durationDays: payload.durationDays,
          objectives: [
            {
              goalTypeCode: payload.goalTypeCode,
              priority: 'PRIMARY',
            },
          ],
        });
      },
      {
        conflict: 'Trạng thái mục tiêu đã thay đổi. Vui lòng tải lại.',
        alertTitle: 'Không thể chuyển mục tiêu',
        fallback: 'Không thể chuyển sang hành trình mục tiêu mới.',
      }
    );
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
            Chưa có mục tiêu thể chất đang hoạt động
          </Text>
          <Text style={[styles.emptySubtitle, { color: themeColors.textSecondary }]}>
            Bạn hiện chưa có mục tiêu thể chất đang hoạt động hoặc tạm dừng. Khi bạn hoặc huấn luyện viên tạo mục tiêu, mục tiêu sẽ xuất hiện tại đây.
          </Text>

          <Pressable
            testID="create-first-goal-button"
            accessibilityRole="button"
            accessibilityLabel="Tạo mục tiêu thể chất mới"
            style={[styles.primaryActionButton, { backgroundColor: themeColors.primary, marginBottom: spacing.sm }]}
            onPress={() => setGoalFormModalState('create', true)}>
            <Text style={styles.primaryActionText}>Tạo mục tiêu thể chất</Text>
          </Pressable>

          <Pressable
            testID="view-proposals-empty-button"
            accessibilityRole="button"
            accessibilityLabel="Xem đề xuất đang chờ"
            style={[styles.secondaryActionButton, { borderColor: themeColors.border }]}
            onPress={() => setActiveTab('proposals')}>
            <Text style={[styles.secondaryActionText, { color: themeColors.textPrimary }]}>
              Xem đề xuất mục tiêu
            </Text>
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
          accessibilityLabel="Quay lại"
          style={styles.backButton}
          onPress={() => {
            if (router.canGoBack()) {
              router.back();
            } else {
              router.replace('/' as any);
            }
          }}>
          <Text style={[styles.backButtonText, { color: themeColors.primary }]}>← Quay lại</Text>
        </Pressable>
        <Text style={[styles.screenTitle, { color: themeColors.textPrimary }]}>
          Mục tiêu thể chất
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
              Không thể tải mục tiêu
            </Text>
            <Text style={[styles.errorSubtitle, { color: themeColors.textSecondary }]}>
              {error}
            </Text>
            <Pressable
              accessibilityRole="button"
              accessibilityLabel="Thử tải lại mục tiêu"
              style={[styles.primaryActionButton, { backgroundColor: themeColors.primary }]}
              onPress={() => loadGoal({ showLoading: true })}>
              <Text style={styles.primaryActionText}>Thử lại</Text>
            </Pressable>
          </View>
        )}

        {/* Loaded Content */}
        {!isLoading && !error && (
          <>
            {/* Conflict Banner */}
            {conflictBanner && (
              <View
                testID="goal-conflict-banner"
                style={[
                  styles.reconciliationCard,
                  {
                    backgroundColor: themeColors.warningSurface,
                    borderColor: themeColors.warningText,
                    marginBottom: spacing.md,
                  },
                ]}>
                <View style={styles.reconciliationCardContent}>
                  <Text
                    style={[
                      styles.reconciliationCardTitle,
                      { color: themeColors.warningText },
                    ]}>
                    Xung đột phiên bản mục tiêu
                  </Text>
                  <Text
                    style={[
                      styles.reconciliationCardSubtitle,
                      { color: themeColors.textSecondary },
                    ]}>
                    {conflictBanner}
                  </Text>
                </View>
                <Pressable
                  testID="reload-goal-conflict-button"
                  accessibilityRole="button"
                  accessibilityLabel="Tải lại mục tiêu"
                  style={[styles.retryActionButton, { backgroundColor: themeColors.primary }]}
                  onPress={() => {
                    setConflictBanner(null);
                    loadGoal({ showLoading: true });
                  }}>
                  <Text style={styles.retryActionText}>Tải lại</Text>
                </Pressable>
              </View>
            )}

            {/* Quick Actions (Pause / Resume / Adjust / Transition) if Goal Exists and not reconciling */}
            {isGoalMatchingRoute && goal && !isReconcilingThisRoute && (
              <View style={styles.actionRow}>
                {isGoalActionAllowed('pause', goal.status) && (
                  <Pressable
                    testID="pause-goal-button"
                    accessibilityRole="button"
                    accessibilityLabel="Tạm dừng mục tiêu"
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
                      ⏸ Tạm dừng mục tiêu
                    </Text>
                  </Pressable>
                )}

                {isGoalActionAllowed('resume', goal.status) && (
                  <Pressable
                    testID="resume-goal-button"
                    accessibilityRole="button"
                    accessibilityLabel="Tiếp tục mục tiêu"
                    style={({ pressed }) => [
                      styles.resumeActionButton,
                      {
                        backgroundColor: pressed
                          ? themeColors.primaryPressed
                          : themeColors.primary,
                      },
                    ]}
                    onPress={handleOpenResume}>
                    <Text style={styles.resumeActionText}>▶ Tiếp tục mục tiêu</Text>
                  </Pressable>
                )}

                {goal.status === 'ACTIVE' && (
                  <>
                    <Pressable
                      testID="adjust-goal-version-button"
                      accessibilityRole="button"
                      accessibilityLabel="Điều chỉnh mục tiêu"
                      style={({ pressed }) => [
                        styles.secondaryActionButton,
                        {
                          borderColor: themeColors.border,
                          backgroundColor: pressed ? themeColors.surfaceSubtle : themeColors.surface,
                        },
                      ]}
                      onPress={() => setGoalFormModalState('version', true)}>
                      <Text style={[styles.secondaryActionText, { color: themeColors.textPrimary }]}>
                        ✎ Điều chỉnh mục tiêu
                      </Text>
                    </Pressable>

                    <Pressable
                      testID="transition-goal-button"
                      accessibilityRole="button"
                      accessibilityLabel="Hành trình chuyển tiếp"
                      style={({ pressed }) => [
                        styles.secondaryActionButton,
                        {
                          borderColor: themeColors.border,
                          backgroundColor: pressed ? themeColors.surfaceSubtle : themeColors.surface,
                        },
                      ]}
                      onPress={() => setGoalFormModalState('transition', true)}>
                      <Text style={[styles.secondaryActionText, { color: themeColors.textPrimary }]}>
                        ↻ Chuyển đổi
                      </Text>
                    </Pressable>
                  </>
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
                      Không thể đồng bộ hóa trạng thái mục tiêu mới nhất
                    </Text>
                    <Text
                      style={[
                        styles.reconciliationCardSubtitle,
                        { color: themeColors.textSecondary },
                      ]}>
                      Vui lòng thử cập nhật lại trạng thái mục tiêu của bạn trước khi thực hiện thay đổi.
                    </Text>
                  </View>
                  <Pressable
                    testID="retry-reconciliation-button"
                    accessibilityRole="button"
                    accessibilityLabel="Thử đồng bộ lại trạng thái mục tiêu"
                    style={[styles.retryActionButton, { backgroundColor: themeColors.primary }]}
                    onPress={handleRetryReconciliation}>
                    <Text style={styles.retryActionText}>Thử lại đồng bộ hóa</Text>
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
                const label = {
                  overview: 'Tổng quan',
                  targets: 'Chỉ tiêu',
                  history: 'Lịch sử',
                  proposals: 'Đề xuất',
                }[tab];
                return (
                  <Pressable
                    key={tab}
                    testID={`tab-${tab}-button`}
                    accessibilityRole="tab"
                    accessibilityLabel={`Thẻ ${label}`}
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
                          fontFamily: isSelected ? fontFamily.bold : fontFamily.medium,
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

      {/* Create First Goal Modal */}
      <CreateGoalModal
        visible={createGoalModalVisible}
        isSubmitting={isCreateGoalSubmitting}
        isDark={isDark}
        sessionId={goalModalSessionId}
        onClose={() => setGoalFormModalState('create', false)}
        onSubmit={handleCreateGoal}
      />

      {/* Adjust Goal Version Modal */}
      {goal && (
        <CreateGoalVersionModal
          visible={createVersionModalVisible}
          currentTitle={
            goalEditorContext?.target === 'version' ? goalEditorContext.goalTitle : goal.title
          }
          currentGoalTypeCode={
            goalEditorContext?.target === 'version'
              ? goalEditorContext.goalTypeCode
              : goal.currentVersion?.objectives?.find((o) => o.priority === 'PRIMARY')?.goalTypeCode
          }
          targetMismatchError={
            createVersionModalVisible &&
            goalEditorContext?.target === 'version' &&
            goal.id !== goalEditorContext.goalId
              ? GOAL_TARGET_MISMATCH_MESSAGE
              : null
          }
          isSubmitting={isCreateGoalSubmitting}
          isDark={isDark}
          sessionId={goalModalSessionId}
          onClose={() => setGoalFormModalState('version', false)}
          onSubmit={handleCreateVersion}
        />
      )}

      {/* Transition Goal Modal */}
      {goal && (
        <CreateGoalTransitionModal
          visible={createTransitionModalVisible}
          targetMismatchError={
            createTransitionModalVisible &&
            goalEditorContext?.target === 'transition' &&
            goal.id !== goalEditorContext.goalId
              ? GOAL_TARGET_MISMATCH_MESSAGE
              : null
          }
          isSubmitting={isCreateGoalSubmitting}
          isDark={isDark}
          sessionId={goalModalSessionId}
          onClose={() => setGoalFormModalState('transition', false)}
          onSubmit={handleCreateTransition}
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
    fontFamily: fontFamily.semiBold,
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
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  secondaryActionButton: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1.5,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  secondaryActionText: {
    ...typography.label,
    fontFamily: fontFamily.bold,
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
    fontFamily: fontFamily.bold,
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
    fontFamily: fontFamily.bold,
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
    fontFamily: fontFamily.bold,
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
    fontFamily: fontFamily.bold,
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
    fontFamily: fontFamily.bold,
    color: '#FFFFFF',
  },
});
