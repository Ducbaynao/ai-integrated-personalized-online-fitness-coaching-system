import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Alert,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { FitnessGoal, FitnessGoalVersion, GoalProposal, ProposalDecision } from '@/types/goal';
import { apiErrorMessage, goalProposalStatusLabels } from '@/localization/displayLabels';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/services/apiClient';
import { GoalScreenSkeleton } from '@/features/goal/components/GoalScreenSkeleton';

interface GoalProposalDetailScreenProps {
  proposalId: string;
}

interface ProposalDetailData {
  loadedProposalId: string;
  proposal: GoalProposal;
  baseVersion: FitnessGoalVersion | null;
  currentGoal: FitnessGoal | null;
  isExpiredByDate: boolean;
}

interface ProposalLoadError {
  proposalId: string;
  message: string;
}

export function GoalProposalDetailScreen({ proposalId }: GoalProposalDetailScreenProps) {
  const router = useRouter();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const [data, setData] = useState<ProposalDetailData | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<ProposalLoadError | null>(null);

  // Decision Modal State
  const [decisionModalVisible, setDecisionModalVisible] = useState(false);
  const [pendingDecision, setPendingDecision] = useState<ProposalDecision>('ACCEPT');
  const [decisionNote, setDecisionNote] = useState('');
  const [noteError, setNoteError] = useState<string | null>(null);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [blockedState, setBlockedState] = useState<{ proposalId: string; reason: string } | null>(null);

  const isMatchingProposal = data?.loadedProposalId === proposalId;
  const currentLoadError = loadError?.proposalId === proposalId ? loadError.message : null;
  const isDisplayLoading = isLoading || !isMatchingProposal || Boolean(currentLoadError);
  const proposal = isMatchingProposal ? data.proposal : null;
  const baseVersion = isMatchingProposal ? data.baseVersion : null;
  const currentGoal = isMatchingProposal ? data.currentGoal : null;
  const isExpiredByDate = isMatchingProposal ? data.isExpiredByDate : false;

  const decisionBlockedReason =
    blockedState?.proposalId === proposalId && isMatchingProposal
      ? blockedState.reason
      : null;

  const requestSeqRef = useRef(0);

  const loadData = useCallback(
    async (options?: { showLoading?: boolean; suppressErrorScreen?: boolean }) => {
      const requestId = ++requestSeqRef.current;
      setDecisionModalVisible(false);
      setDecisionNote('');
      setNoteError(null);
      await Promise.resolve();
      if (options?.showLoading) {
        setIsLoading(true);
        setLoadError(null);
      }
      try {
        const prop = await fitnessGoalApi.getGoalProposal(proposalId);
        if (requestId !== requestSeqRef.current) return;

        let initialBaseVersion = prop.baseVersion || null;
        let initialCurrentGoal: FitnessGoal | null = null;

        if (!initialBaseVersion && prop.fitnessGoalId) {
          try {
            const goal = await fitnessGoalApi.getGoal(prop.fitnessGoalId);
            if (requestId === requestSeqRef.current) {
              initialCurrentGoal = goal;
              if (goal.currentVersion) {
                initialBaseVersion = goal.currentVersion;
              }
            }
          } catch {
            // Base goal load is non-fatal for comparison
          }
        }

        if (requestId !== requestSeqRef.current) return;

        const expired = Boolean(
          prop.expiresAt && new Date(prop.expiresAt).getTime() <= Date.now()
        );

        setData({
          loadedProposalId: proposalId,
          proposal: prop,
          baseVersion: initialBaseVersion,
          currentGoal: initialCurrentGoal,
          isExpiredByDate: expired,
        });
        setLoadError(null);
      } catch (err: any) {
        if (requestId === requestSeqRef.current) {
          if (!options?.suppressErrorScreen) {
            const message = apiErrorMessage(err, 'Không thể tải chi tiết đề xuất mục tiêu.');
            setLoadError({ proposalId, message });
          }
        }
      } finally {
        if (requestId === requestSeqRef.current) {
          setIsLoading(false);
        }
      }
    },
    [proposalId]
  );

  useEffect(() => {
    const seqRef = requestSeqRef;
    const execute = async () => {
      await loadData();
    };
    execute();
    return () => {
      seqRef.current++;
    };
  }, [loadData]);

  const handleOpenDecision = (decision: ProposalDecision) => {
    if (!isMatchingProposal || !proposal || proposal.id !== proposalId || isDisplayLoading) {
      return;
    }
    setPendingDecision(decision);
    setDecisionNote('');
    setNoteError(null);
    setDecisionModalVisible(true);
  };

  const handleConfirmDecision = async () => {
    if (
      !isMatchingProposal ||
      !proposal ||
      proposal.id !== proposalId ||
      isDisplayLoading ||
      isSubmitting
    ) {
      return;
    }

    if (pendingDecision === 'REJECT' && !decisionNote.trim()) {
      setNoteError('Vui lòng nêu lý do từ chối để huấn luyện viên hiểu quyết định của bạn.');
      return;
    }

    setNoteError(null);
    setIsSubmitting(true);
    try {
      const updatedProposal = await fitnessGoalApi.decideGoalProposal(
        proposal.id,
        pendingDecision,
        decisionNote
      );
      setData((prev) =>
        prev && prev.loadedProposalId === proposal.id
          ? { ...prev, proposal: updatedProposal }
          : prev
      );
      setDecisionModalVisible(false);
      Alert.alert(
        'Đã cập nhật đề xuất',
        pendingDecision === 'ACCEPT' ? 'Đã chấp nhận đề xuất.' : 'Đã từ chối đề xuất.'
      );
    } catch (err: any) {
      let isTerminalConflict = false;
      let terminalReason: string | null = null;
      let isConflict = false;
      let msg = 'Không thể gửi quyết định.';
      if (err instanceof ApiError) {
        const errorCode = err.errorResponse?.errorCode;
        if (errorCode === 'GOAL_PROPOSAL_ALREADY_DECIDED') {
          isTerminalConflict = true;
          terminalReason = 'Đề xuất đã được xử lý ở một yêu cầu khác và không thể xử lý lại.';
          msg = terminalReason;
        } else if (errorCode === 'GOAL_PROPOSAL_EXPIRED') {
          isTerminalConflict = true;
          terminalReason = 'Đề xuất đã hết hạn và không thể xử lý.';
          msg = terminalReason;
        } else if (errorCode === 'STALE_GOAL_PROPOSAL') {
          isTerminalConflict = true;
          terminalReason = 'Phiên bản mục tiêu gốc đã thay đổi. Đề xuất này không còn hiệu lực. Vui lòng tải lại mục tiêu.';
          msg = terminalReason;
        } else if (errorCode === 'GOAL_VERSION_NO_CHANGES') {
          isTerminalConflict = true;
          terminalReason = 'Đề xuất không có thay đổi so với phiên bản đang hoạt động.';
          msg = terminalReason;
        } else if (err.status === 409) {
          isConflict = true;
          msg = 'Có xung đột khi xử lý quyết định. Vui lòng tải lại.';
        } else {
          msg = 'Không thể xử lý quyết định. Vui lòng thử lại.';
        }
      }

      setDecisionModalVisible(false);
      Alert.alert('Không thể xử lý quyết định', msg);

      if (isTerminalConflict && terminalReason) {
        setBlockedState({ proposalId: proposal.id, reason: terminalReason });
      }

      if (isTerminalConflict || isConflict) {
        await loadData({ suppressErrorScreen: true });
      }
    } finally {
      setIsSubmitting(false);
    }
  };

  // 1. Error state for current proposal
  if (currentLoadError && !isLoading) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: themeColors.canvas }]}>
        <View style={styles.centerContainer}>
          <Text
            testID="proposal-error-message"
            style={[styles.errorTitle, { color: themeColors.dangerText }]}>
            {currentLoadError}
          </Text>
          <View style={styles.errorActionRow}>
            <Pressable
              testID="proposal-error-retry-button"
              accessibilityRole="button"
              accessibilityLabel="Thử tải lại đề xuất"
              disabled={isLoading}
              style={[styles.primaryButton, { backgroundColor: themeColors.primary }]}
              onPress={() => loadData({ showLoading: true })}>
              <Text style={styles.primaryButtonText}>Thử lại</Text>
            </Pressable>
            <Pressable
              testID="proposal-error-back-button"
              accessibilityRole="button"
              accessibilityLabel="Quay lại"
              style={[
                styles.secondaryButton,
                { borderColor: themeColors.border, backgroundColor: themeColors.surface },
              ]}
              onPress={() => router.back()}>
              <Text style={[styles.secondaryButtonText, { color: themeColors.textPrimary }]}>
                Quay lại
              </Text>
            </Pressable>
          </View>
        </View>
      </SafeAreaView>
    );
  }

  // 2. Loading state: skeleton when loading or waiting for matching proposal data
  if (isLoading || !isMatchingProposal) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: themeColors.canvas }]}>
        <View testID="proposal-loading-skeleton" style={styles.skeletonContainer}>
          <GoalScreenSkeleton isDark={isDark} />
        </View>
      </SafeAreaView>
    );
  }

  // 3. Fallback if matching proposal is missing
  if (!proposal) {
    return (
      <SafeAreaView style={[styles.container, { backgroundColor: themeColors.canvas }]}>
        <View style={styles.centerContainer}>
          <Text
            testID="proposal-error-message"
            style={[styles.errorTitle, { color: themeColors.dangerText }]}>
            Không tìm thấy đề xuất.
          </Text>
          <Pressable
            testID="proposal-error-back-button"
            accessibilityRole="button"
            accessibilityLabel="Quay lại"
            style={[styles.primaryButton, { backgroundColor: themeColors.primary }]}
            onPress={() => router.back()}>
            <Text style={styles.primaryButtonText}>Quay lại</Text>
          </Pressable>
        </View>
      </SafeAreaView>
    );
  }

  const effectiveBlockedReason =
    decisionBlockedReason ||
    (isExpiredByDate
      ? 'Đề xuất đã hết hạn và không thể xử lý.'
      : null);

  const isPending =
    proposal.status === 'PENDING' &&
    !effectiveBlockedReason &&
    !isExpiredByDate;

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      {/* Top Header */}
      <View style={[styles.header, { borderBottomColor: themeColors.border }]}>
        <Pressable
          testID="proposal-screen-back-button"
          accessibilityRole="button"
          accessibilityLabel="Quay lại"
          style={styles.backButton}
          onPress={() => router.back()}>
          <Text style={[styles.backButtonText, { color: themeColors.primary }]}>← Quay lại</Text>
        </Pressable>
        <Text style={[styles.screenTitle, { color: themeColors.textPrimary }]}>
          Đề xuất mục tiêu
        </Text>
        <View style={styles.headerSpacer} />
      </View>

      <ScrollView contentContainerStyle={styles.scrollContent}>
        {/* Terminal Conflict / Blocked Banner */}
        {effectiveBlockedReason && (
          <View
            testID="proposal-blocked-banner"
            style={[
              styles.banner,
              {
                backgroundColor: themeColors.surfaceSubtle,
                borderColor: themeColors.dangerText,
              },
            ]}>
            <Text
              testID="proposal-blocked-message"
              style={[
                styles.bannerTitle,
                { color: themeColors.dangerText },
              ]}>
              {`Không thể xử lý quyết định: ${effectiveBlockedReason}`}
            </Text>
          </View>
        )}

        {/* Status Banner */}
        <View
          testID={`proposal-status-banner-${proposal.status.toLowerCase()}`}
          style={[
            styles.banner,
            {
              backgroundColor:
                proposal.status === 'PENDING'
                  ? themeColors.warningSurface
                  : proposal.status === 'ACCEPTED'
                  ? themeColors.successSurface
                  : themeColors.surfaceSubtle,
              borderColor:
                proposal.status === 'PENDING'
                  ? themeColors.warningText
                  : proposal.status === 'ACCEPTED'
                  ? themeColors.successText
                  : themeColors.border,
            },
          ]}>
          <View style={styles.bannerHeader}>
            <Text
              style={[
                styles.bannerTitle,
                {
                  color:
                    proposal.status === 'PENDING'
                      ? themeColors.warningText
                      : proposal.status === 'ACCEPTED'
                      ? themeColors.successText
                      : themeColors.textPrimary,
                },
              ]}>
              Trạng thái: {goalProposalStatusLabels[proposal.status]}
            </Text>
            <Text style={[styles.sourceBadge, { color: themeColors.textSecondary }]}>
              Nguồn: {proposal.source}
            </Text>
          </View>
          <Text style={[styles.reasonText, { color: themeColors.textPrimary }]}>
            Lý do đề xuất: {proposal.reason}
          </Text>
          {proposal.decisionNote && (
            <Text style={[styles.decisionNoteText, { color: themeColors.textSecondary }]}>
              Ghi chú quyết định: {proposal.decisionNote}
            </Text>
          )}
        </View>

        {/* Comparison Section */}
        <View
          style={[
            styles.card,
            { backgroundColor: themeColors.surface, borderColor: themeColors.border },
          ]}>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            So sánh hiện tại và đề xuất
          </Text>

          {/* Title Comparison */}
          <View style={styles.comparisonRow}>
            <View style={styles.comparisonCol}>
              <Text style={[styles.columnLabel, { color: themeColors.textSecondary }]}>
                Phiên bản hiện tại
              </Text>
              <Text style={[styles.columnValue, { color: themeColors.textPrimary }]}>
                {baseVersion?.title || currentGoal?.title || 'Chưa có tiêu đề đang hoạt động'}
              </Text>
            </View>
            <View style={styles.dividerCol} />
            <View style={styles.comparisonCol}>
              <Text style={[styles.columnLabel, { color: themeColors.textSecondary }]}>
                Đề xuất cập nhật
              </Text>
              <Text
                testID="proposal-proposed-title"
                style={[
                  styles.columnValue,
                  {
                    color:
                      proposal.proposedTitle &&
                      proposal.proposedTitle !== (baseVersion?.title || currentGoal?.title)
                        ? themeColors.primary
                        : themeColors.textPrimary,
                    fontWeight: proposal.proposedTitle ? '700' : '400',
                  },
                ]}>
                {proposal.proposedTitle || baseVersion?.title || currentGoal?.title || 'Không đổi'}
              </Text>
            </View>
          </View>

          {/* Timeline Comparison */}
          <View style={[styles.comparisonRow, styles.borderedRow, { borderColor: themeColors.border }]}>
            <View style={styles.comparisonCol}>
              <Text style={[styles.columnLabel, { color: themeColors.textSecondary }]}>
                Thời hạn / Chỉ tiêu hiện tại
              </Text>
              <Text style={[styles.columnValue, { color: themeColors.textPrimary }]}>
                {baseVersion?.durationDays ? `${baseVersion.durationDays} ngày` : 'Chưa đặt thời hạn'}
                {baseVersion?.targetDate ? ` (Kết thúc: ${baseVersion.targetDate})` : ''}
              </Text>
            </View>
            <View style={styles.dividerCol} />
            <View style={styles.comparisonCol}>
              <Text style={[styles.columnLabel, { color: themeColors.textSecondary }]}>
                Thời hạn / Chỉ tiêu đề xuất
              </Text>
              <Text
                style={[
                  styles.columnValue,
                  {
                    color:
                      proposal.proposedDurationDays || proposal.proposedTargetDate
                        ? themeColors.primary
                        : themeColors.textPrimary,
                    fontWeight:
                      proposal.proposedDurationDays || proposal.proposedTargetDate
                        ? '700'
                        : '400',
                  },
                ]}>
                {proposal.proposedDurationDays
                  ? `${proposal.proposedDurationDays} ngày`
                  : baseVersion?.durationDays
                  ? `${baseVersion.durationDays} ngày`
                  : 'Không đổi'}
                {proposal.proposedTargetDate ? ` (Kết thúc: ${proposal.proposedTargetDate})` : ''}
              </Text>
            </View>
          </View>
        </View>

        {/* Proposed Objectives */}
        <View
          style={[
            styles.card,
            { backgroundColor: themeColors.surface, borderColor: themeColors.border },
          ]}>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            Loại mục tiêu đề xuất ({proposal.objectives.length})
          </Text>
          {proposal.objectives.length === 0 ? (
            <Text style={[styles.emptySectionText, { color: themeColors.textSecondary }]}>
              Không có thay đổi loại mục tiêu được đề xuất.
            </Text>
          ) : (
            proposal.objectives.map((obj, idx) => (
              <View
                key={obj.id || idx}
                testID={`proposed-objective-${idx}`}
                style={[
                  styles.itemCard,
                  {
                    backgroundColor: themeColors.surfaceSubtle,
                    borderColor: themeColors.border,
                  },
                ]}>
                <View style={styles.itemHeader}>
                  <Text style={[styles.itemTitle, { color: themeColors.textPrimary }]}>
                    {obj.goalTypeName || obj.goalTypeCode}
                  </Text>
                  <View
                    style={[
                      styles.priorityBadge,
                      {
                        backgroundColor:
                          obj.priority === 'PRIMARY'
                            ? themeColors.brandSoft
                            : themeColors.surface,
                      },
                    ]}>
                    <Text
                      style={[
                        styles.priorityText,
                        {
                          color:
                            obj.priority === 'PRIMARY'
                              ? themeColors.primary
                              : themeColors.textSecondary,
                        },
                      ]}>
                      {obj.priority}
                    </Text>
                  </View>
                </View>
                {obj.notes && (
                  <Text style={[styles.itemNotes, { color: themeColors.textSecondary }]}>
                    {obj.notes}
                  </Text>
                )}
              </View>
            ))
          )}
        </View>

        {/* Proposed Targets */}
        <View
          style={[
            styles.card,
            { backgroundColor: themeColors.surface, borderColor: themeColors.border },
          ]}>
          <Text style={[styles.cardTitle, { color: themeColors.textPrimary }]}>
            Chỉ tiêu đề xuất ({proposal.targets.length})
          </Text>
          {proposal.targets.length === 0 ? (
            <Text style={[styles.emptySectionText, { color: themeColors.textSecondary }]}>
              Không có thay đổi chỉ tiêu được đề xuất.
            </Text>
          ) : (
            proposal.targets.map((tgt, idx) => (
              <View
                key={tgt.id || idx}
                testID={`proposed-target-${idx}`}
                style={[
                  styles.itemCard,
                  {
                    backgroundColor: themeColors.surfaceSubtle,
                    borderColor: themeColors.border,
                  },
                ]}>
                <Text style={[styles.itemTitle, { color: themeColors.textPrimary }]}>
                  {tgt.metricDisplayName || tgt.metricCode}
                </Text>
                <View style={styles.targetGrid}>
                  <View style={styles.targetGridItem}>
                    <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                      Giá trị bắt đầu
                    </Text>
                    <Text style={[styles.metaVal, { color: themeColors.textPrimary }]}>
                      {tgt.startValue !== null && tgt.startValue !== undefined
                        ? `${tgt.startValue} ${tgt.unitSymbol}`
                        : '—'}
                    </Text>
                  </View>
                  <View style={styles.targetGridItem}>
                    <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                      Giá trị mục tiêu
                    </Text>
                    <Text
                      style={[
                        styles.metaVal,
                        { color: themeColors.primary, fontWeight: '700' },
                      ]}>
                      {tgt.targetValue !== null && tgt.targetValue !== undefined
                        ? `${tgt.targetValue} ${tgt.unitSymbol}`
                        : tgt.targetMinValue !== null && tgt.targetMaxValue !== null
                        ? `${tgt.targetMinValue} - ${tgt.targetMaxValue} ${tgt.unitSymbol}`
                        : '—'}
                    </Text>
                  </View>
                  {tgt.targetDate && (
                    <View style={styles.targetGridItem}>
                      <Text style={[styles.metaLabel, { color: themeColors.textSecondary }]}>
                        Ngày mục tiêu
                      </Text>
                      <Text style={[styles.metaVal, { color: themeColors.textPrimary }]}>
                        {tgt.targetDate}
                      </Text>
                    </View>
                  )}
                </View>
                {tgt.notes && (
                  <Text style={[styles.itemNotes, { color: themeColors.textSecondary }]}>
                    {tgt.notes}
                  </Text>
                )}
              </View>
            ))
          )}
        </View>

        {/* Action Buttons for Pending Proposals */}
        {isPending && (
          <View style={styles.actionSection}>
            <Pressable
              testID="accept-proposal-button"
              accessibilityRole="button"
              accessibilityLabel="Chấp nhận đề xuất"
              style={[styles.acceptButton, { backgroundColor: themeColors.primary }]}
              onPress={() => handleOpenDecision('ACCEPT')}>
              <Text style={styles.acceptButtonText}>Chấp nhận đề xuất</Text>
            </Pressable>

            <Pressable
              testID="reject-proposal-button"
              accessibilityRole="button"
              accessibilityLabel="Từ chối đề xuất"
              style={[
                styles.rejectButton,
                {
                  borderColor: themeColors.dangerText,
                  backgroundColor: themeColors.surface,
                },
              ]}
              onPress={() => handleOpenDecision('REJECT')}>
              <Text style={[styles.rejectButtonText, { color: themeColors.dangerText }]}>
                Từ chối đề xuất
              </Text>
            </Pressable>
          </View>
        )}
      </ScrollView>

      {/* Decision Confirmation Modal */}
      <Modal
        visible={decisionModalVisible && isMatchingProposal && !isDisplayLoading}
        transparent
        animationType="fade"
        onRequestClose={() => !isSubmitting && setDecisionModalVisible(false)}>
        <View style={styles.modalOverlay}>
          <View
            style={[
              styles.modalCard,
              {
                backgroundColor: themeColors.surface,
                borderColor: themeColors.border,
              },
            ]}>
            <Text style={[styles.modalTitle, { color: themeColors.textPrimary }]}>
              {pendingDecision === 'ACCEPT' ? 'Chấp nhận đề xuất' : 'Từ chối đề xuất'}
            </Text>

            <Text
              testID="proposal-modal-desc"
              style={[styles.modalDesc, { color: themeColors.textSecondary }]}>
              {pendingDecision === 'ACCEPT'
                ? 'Chấp nhận sẽ áp dụng các thay đổi được đề xuất. Tùy mục tiêu chính, hệ thống sẽ tạo phiên bản mới hoặc bắt đầu hành trình mục tiêu mới. Lịch sử tập luyện được giữ lại.'
                : 'Từ chối sẽ giữ nguyên phiên bản mục tiêu hiện tại. Vui lòng nêu lý do để huấn luyện viên điều chỉnh.'}
            </Text>

            <View style={styles.noteHeaderRow}>
              <Text style={[styles.fieldLabel, { color: themeColors.textSecondary }]}>
                {pendingDecision === 'ACCEPT'
                  ? 'Note / Comments (Optional)'
                  : 'Lý do từ chối (bắt buộc)'}
              </Text>
              <Text
                testID="proposal-note-char-count"
                style={[
                  styles.charCount,
                  {
                    color:
                      decisionNote.length > 2000
                        ? themeColors.dangerText
                        : themeColors.textSecondary,
                  },
                ]}>
                {decisionNote.length}/2000
              </Text>
            </View>

            <TextInput
              testID="proposal-decision-note-input"
              accessibilityLabel={
                pendingDecision === 'ACCEPT'
                  ? 'Ghi chú cho quyết định (không bắt buộc)'
                  : 'Lý do từ chối đề xuất (bắt buộc)'
              }
              style={[
                styles.noteInput,
                {
                  color: themeColors.textPrimary,
                  borderColor: noteError ? themeColors.dangerText : themeColors.border,
                  backgroundColor: themeColors.surfaceSubtle,
                },
              ]}
              placeholder={
                pendingDecision === 'ACCEPT'
                  ? 'Thêm lời nhắn cho huấn luyện viên (không bắt buộc)...'
                  : 'Nêu lý do bạn từ chối đề xuất...'
              }
              placeholderTextColor={themeColors.textSecondary}
              multiline
              numberOfLines={3}
              value={decisionNote}
              onChangeText={(text) => {
                setDecisionNote(text);
                if (noteError && text.trim()) {
                  setNoteError(null);
                }
              }}
              editable={!isSubmitting}
              maxLength={2000}
            />

            {noteError && (
              <Text testID="proposal-note-error" style={[styles.noteErrorText, { color: themeColors.dangerText }]}>
                {noteError}
              </Text>
            )}

            <View style={styles.modalButtons}>
              <Pressable
                testID="modal-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Hủy"
                style={[styles.modalCancelBtn, { borderColor: themeColors.border }]}
                onPress={() => setDecisionModalVisible(false)}
                disabled={isSubmitting}>
                <Text style={[styles.modalCancelText, { color: themeColors.textSecondary }]}>
                  Hủy
                </Text>
              </Pressable>

              <Pressable
                testID="modal-confirm-button"
                accessibilityRole="button"
                accessibilityLabel={`Xác nhận ${pendingDecision === 'ACCEPT' ? 'chấp nhận' : 'từ chối'}`}
                accessibilityState={{ disabled: isSubmitting, busy: isSubmitting }}
                style={[
                  styles.modalConfirmBtn,
                  {
                    backgroundColor:
                      pendingDecision === 'ACCEPT'
                        ? themeColors.primary
                        : themeColors.dangerText,
                  },
                ]}
                onPress={handleConfirmDecision}
                disabled={isSubmitting}>
                {isSubmitting ? (
                  <ActivityIndicator color={themeColors.textOnPrimary} />
                ) : (
                  <Text style={styles.modalConfirmText}>
                    Xác nhận {pendingDecision === 'ACCEPT' ? 'chấp nhận' : 'từ chối'}
                  </Text>
                )}
              </Pressable>
            </View>
          </View>
        </View>
      </Modal>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  skeletonContainer: {
    padding: layout.mobileScreenPadding,
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
  centerContainer: {
    flex: 1,
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.xl,
    gap: spacing.md,
  },
  errorTitle: {
    ...typography.h3,
    textAlign: 'center',
  },
  banner: {
    borderRadius: radius.md,
    padding: spacing.md,
    borderWidth: 1,
    gap: spacing.xs,
  },
  bannerHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  bannerTitle: {
    ...typography.label,
    fontWeight: '700',
  },
  sourceBadge: {
    ...typography.caption,
  },
  reasonText: {
    ...typography.bodySmall,
    fontWeight: '500',
  },
  decisionNoteText: {
    ...typography.caption,
    fontStyle: 'italic',
    marginTop: spacing.xxs,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.md,
    borderWidth: 1,
    gap: spacing.md,
  },
  cardTitle: {
    ...typography.h3,
    fontSize: 16,
  },
  comparisonRow: {
    flexDirection: 'row',
    alignItems: 'flex-start',
    gap: spacing.sm,
  },
  borderedRow: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: spacing.sm,
  },
  comparisonCol: {
    flex: 1,
    gap: spacing.xxs,
  },
  dividerCol: {
    width: 1,
    backgroundColor: '#CBD1DC',
    alignSelf: 'stretch',
  },
  columnLabel: {
    ...typography.caption,
    fontWeight: '600',
  },
  columnValue: {
    ...typography.bodySmall,
  },
  emptySectionText: {
    ...typography.bodySmall,
    fontStyle: 'italic',
  },
  itemCard: {
    borderRadius: radius.md,
    padding: spacing.sm,
    borderWidth: 1,
    gap: spacing.xs,
  },
  itemHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  itemTitle: {
    ...typography.bodySmall,
    fontWeight: '700',
  },
  priorityBadge: {
    paddingHorizontal: spacing.xs,
    paddingVertical: 2,
    borderRadius: radius.sm,
  },
  priorityText: {
    ...typography.caption,
    fontWeight: '700',
  },
  itemNotes: {
    ...typography.caption,
  },
  targetGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    marginTop: spacing.xxs,
  },
  targetGridItem: {
    flex: 1,
    minWidth: 100,
  },
  metaLabel: {
    ...typography.caption,
  },
  metaVal: {
    ...typography.bodySmall,
  },
  actionSection: {
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
  acceptButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  acceptButtonText: {
    ...typography.label,
    color: '#FFFFFF',
    fontWeight: '700',
  },
  rejectButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    borderWidth: 1.5,
    justifyContent: 'center',
    alignItems: 'center',
  },
  rejectButtonText: {
    ...typography.label,
    fontWeight: '700',
  },
  primaryButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.xl,
    justifyContent: 'center',
    alignItems: 'center',
  },
  primaryButtonText: {
    ...typography.label,
    color: '#FFFFFF',
    fontWeight: '700',
  },
  modalOverlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.lg,
  },
  modalCard: {
    width: '100%',
    maxWidth: 480,
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.lg,
    gap: spacing.md,
  },
  modalTitle: {
    ...typography.h3,
  },
  modalDesc: {
    ...typography.bodySmall,
    lineHeight: 20,
  },
  noteHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  fieldLabel: {
    ...typography.caption,
    fontWeight: '600',
  },
  charCount: {
    ...typography.caption,
    fontWeight: '500',
  },
  noteInput: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
    minHeight: 80,
    textAlignVertical: 'top',
    ...typography.body,
  },
  noteErrorText: {
    ...typography.caption,
    marginTop: -spacing.xs,
  },
  modalButtons: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  modalCancelBtn: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalCancelText: {
    ...typography.label,
  },
  modalConfirmBtn: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  modalConfirmText: {
    ...typography.label,
    color: '#FFFFFF',
    fontWeight: '700',
  },
  errorActionRow: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
  secondaryButton: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  secondaryButtonText: {
    ...typography.label,
    fontWeight: '600',
  },
});
