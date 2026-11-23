import React, { useState, useRef, useEffect, useMemo } from 'react';
import {
  AccessibilityInfo,
  Modal,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { colors, getSemanticColors } from '@/design-system/tokens/colors';
import { spacing, layout } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import { aiRecommendationStatusLabels } from '@/localization/displayLabels';
import {
  aiWorkoutRescheduleApi,
  getWorkoutRescheduleErrorMessage,
  getWorkoutRescheduleFieldErrors,
} from '@/services/aiWorkoutRescheduleApi';
import { createCommandKey } from '@/services/coachingApi';
import {
  WorkoutRescheduleApiResponse,
  AiWorkoutRescheduleDetail,
  AcceptRescheduleApiResponse,
  F05WorkoutReschedulePayload,
  RescheduleRecommendationStatus,
} from '@/types/aiWorkoutReschedule';
import { WorkoutButton, WorkoutCard, WorkoutState } from '@/features/workout-plan/components/WorkoutUi';
import { formatScheduleTime } from '@/features/workout-schedule/scheduleDisplay';

export interface AiWorkoutRescheduleModalProps {
  visible: boolean;
  studentId: string;
  occurrenceId: string;
  occurrenceVersion?: number;
  sessionName?: string;
  currentPlannedStartAt?: string;
  timezone?: string;
  linkedAppointmentId?: string | null;
  linkedAppointmentVersion?: number | null;
  initialRecommendationId?: string;
  isTrainer?: boolean;
  coachingMode?: 'HUMAN_COACH' | 'SELF_DIRECTED';
  onClose: () => void;
  onRescheduleApplied?: (result: AcceptRescheduleApiResponse) => void;
}

function RescheduleModalContent({
  studentId,
  occurrenceId,
  occurrenceVersion,
  sessionName,
  currentPlannedStartAt,
  timezone,
  linkedAppointmentId,
  linkedAppointmentVersion,
  initialRecommendationId,
  isTrainer = false,
  coachingMode = 'SELF_DIRECTED',
  onClose,
  onRescheduleApplied,
}: Omit<AiWorkoutRescheduleModalProps, 'visible'>) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);
  const [mountedTime] = useState(() => Date.now());

  // Guard against late async responses
  const activeStudentIdRef = useRef(studentId);
  const activeOccurrenceIdRef = useRef(occurrenceId);
  const isMountedRef = useRef(true);

  useEffect(() => {
    activeStudentIdRef.current = studentId;
    activeOccurrenceIdRef.current = occurrenceId;
  }, [studentId, occurrenceId]);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

  // Form states
  const [preferredTimeOfDay, setPreferredTimeOfDay] = useState<'MORNING' | 'AFTERNOON' | 'EVENING'>('MORNING');
  const [reason, setReason] = useState<string>('');

  // Proposal state
  const [proposal, setProposal] = useState<WorkoutRescheduleApiResponse | AiWorkoutRescheduleDetail | null>(null);
  const [activeCommandKey, setActiveCommandKey] = useState<string>(() => createCommandKey());
  const [pendingAcceptCommandKey, setPendingAcceptCommandKey] = useState<string | null>(null);
  const [isLoadingProposal, setIsLoadingProposal] = useState<boolean>(() => Boolean(initialRecommendationId));
  const [isApplying, setIsApplying] = useState<boolean>(false);
  const [isRejecting, setIsRejecting] = useState<boolean>(false);
  const [showRejectForm, setShowRejectForm] = useState<boolean>(false);
  const [decisionNote, setDecisionNote] = useState<string>('');

  // Error & Status
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [appliedResult, setAppliedResult] = useState<AcceptRescheduleApiResponse | null>(null);
  const [isRejected, setIsRejected] = useState<boolean>(false);
  const [isStaleConflict, setIsStaleConflict] = useState<boolean>(false);

  // Reset form when user clicks recreate
  const resetForm = () => {
    setProposal(null);
    setActiveCommandKey(createCommandKey());
    setPendingAcceptCommandKey(null);
    setIsLoadingProposal(false);
    setIsApplying(false);
    setIsRejecting(false);
    setShowRejectForm(false);
    setDecisionNote('');
    setErrorMessage(null);
    setFieldErrors({});
    setAppliedResult(null);
    setIsRejected(false);
    setIsStaleConflict(false);
    setReason('');
    setPreferredTimeOfDay('MORNING');
  };

  // Fetch initial recommendation if passed
  useEffect(() => {
    if (initialRecommendationId) {
      let isCurrent = true;
      aiWorkoutRescheduleApi
        .getDetail(initialRecommendationId)
        .then((detail) => {
          if (!isMountedRef.current || !isCurrent) return;
          if (activeOccurrenceIdRef.current !== occurrenceId) return;
          setProposal(detail);
        })
        .catch((err) => {
          if (!isMountedRef.current || !isCurrent) return;
          setErrorMessage(getWorkoutRescheduleErrorMessage(err));
        })
        .finally(() => {
          if (isMountedRef.current && isCurrent) {
            setIsLoadingProposal(false);
          }
        });

      return () => {
        isCurrent = false;
      };
    }
  }, [initialRecommendationId, occurrenceId]);

  // Generate proposal
  const handleGenerate = async () => {
    if (isLoadingProposal || isApplying) return;
    setIsLoadingProposal(true);
    setErrorMessage(null);
    setFieldErrors({});
    setIsStaleConflict(false);

    const commandKey = activeCommandKey || createCommandKey();
    const reqStudentId = isTrainer ? studentId : undefined;

    try {
      const response = await aiWorkoutRescheduleApi.generate(
        {
          occurrenceId,
          studentId: reqStudentId,
          expectedOccurrenceVersion: occurrenceVersion,
          reason: reason.trim() || undefined,
          preferredTimeOfDay,
        },
        commandKey
      );

      if (!isMountedRef.current) return;
      if (activeOccurrenceIdRef.current !== occurrenceId || activeStudentIdRef.current !== studentId) return;

      setProposal(response);
      AccessibilityInfo.announceForAccessibility('Đã tạo đề xuất dời lịch tập thành công. Hãy xem lại trước khi quyết định.');
    } catch (err) {
      if (!isMountedRef.current) return;
      if (activeOccurrenceIdRef.current !== occurrenceId || activeStudentIdRef.current !== studentId) return;

      const fErrors = getWorkoutRescheduleFieldErrors(err);
      setFieldErrors(fErrors);
      const msg = getWorkoutRescheduleErrorMessage(err);
      setErrorMessage(msg);
      if (msg.includes('Phiên bản') || msg.includes('Xung đột') || msg.includes('hết hạn') || msg.includes('trùng lặp')) {
        setIsStaleConflict(true);
      }
    } finally {
      if (isMountedRef.current) {
        setIsLoadingProposal(false);
      }
    }
  };

  // Structured payload extraction
  const payload: F05WorkoutReschedulePayload | null = useMemo(() => {
    if (!proposal) return null;
    return proposal.structuredPayload as F05WorkoutReschedulePayload;
  }, [proposal]);

  // Expiry check
  const isExpired = useMemo(() => {
    if (!proposal) return false;
    if (proposal.status === 'EXPIRED') return true;
    if (!proposal.expiresAt) return false;
    return new Date(proposal.expiresAt).getTime() <= mountedTime;
  }, [proposal, mountedTime]);

  // Remaining minutes until expiry
  const remainingMinutes = useMemo(() => {
    if (!proposal?.expiresAt) return null;
    const diffMs = new Date(proposal.expiresAt).getTime() - mountedTime;
    return Math.max(0, Math.ceil(diffMs / (60 * 1000)));
  }, [proposal, mountedTime]);

  // Decision authority calculation
  const canAccept = useMemo(() => {
    if (!proposal) return false;
    // If a retry is pending with previous commandKey, allow replaying even if client-side clock passed expiresAt
    if (isExpired && !pendingAcceptCommandKey) return false;
    if (proposal.status !== 'PENDING') return false;

    // Decision authority from backend proposal
    if (proposal.decisionAuthority === 'TRAINER') {
      return isTrainer;
    }
    if (proposal.decisionAuthority === 'STUDENT') {
      return !isTrainer && coachingMode !== 'HUMAN_COACH';
    }
    return false;
  }, [proposal, isExpired, pendingAcceptCommandKey, coachingMode, isTrainer]);

  // Accept and apply
  const handleAccept = async () => {
    if (!proposal || !canAccept || isApplying || isLoadingProposal) return;
    const targetRecommendationId = proposal.recommendationId;
    setIsApplying(true);
    setErrorMessage(null);
    setIsStaleConflict(false);

    // Reuse existing command key if retrying after network timeout, else create new
    const acceptCmdKey = pendingAcceptCommandKey || createCommandKey();
    if (!pendingAcceptCommandKey) {
      setPendingAcceptCommandKey(acceptCmdKey);
    }

    try {
      const response = await aiWorkoutRescheduleApi.accept(
        targetRecommendationId,
        {
          expectedRevision: (proposal as any).revision ?? 1,
          expectedOccurrenceVersion: occurrenceVersion,
          expectedAppointmentVersion: linkedAppointmentVersion ?? undefined,
          expectedAppointmentId: linkedAppointmentId ?? undefined,
          decisionNote: decisionNote.trim() || undefined,
        },
        acceptCmdKey
      );

      if (!isMountedRef.current) return;
      if (activeOccurrenceIdRef.current !== occurrenceId || activeStudentIdRef.current !== studentId) return;
      if (proposal?.recommendationId !== targetRecommendationId) return;

      setPendingAcceptCommandKey(null);
      setAppliedResult(response);
      AccessibilityInfo.announceForAccessibility('Đã áp dụng dời lịch tập thành công.');
      if (onRescheduleApplied) {
        onRescheduleApplied(response);
      }
    } catch (err) {
      if (!isMountedRef.current) return;
      if (activeOccurrenceIdRef.current !== occurrenceId || activeStudentIdRef.current !== studentId) return;
      if (proposal?.recommendationId !== targetRecommendationId) return;

      const msg = getWorkoutRescheduleErrorMessage(err);
      setErrorMessage(msg);
      if (
        msg.includes('Phiên bản') ||
        msg.includes('Xung đột') ||
        msg.includes('hết hạn') ||
        msg.includes('trùng lặp') ||
        msg.includes('lịch hẹn')
      ) {
        setIsStaleConflict(true);
        setPendingAcceptCommandKey(null);
      }
    } finally {
      if (isMountedRef.current) {
        setIsApplying(false);
      }
    }
  };

  // Reject
  const handleReject = async () => {
    if (!proposal || isRejecting || isLoadingProposal) return;
    setIsRejecting(true);
    setErrorMessage(null);

    try {
      await aiWorkoutRescheduleApi.reject(proposal.recommendationId, {
        decisionNote: decisionNote.trim() || undefined,
      });

      if (!isMountedRef.current) return;
      if (activeOccurrenceIdRef.current !== occurrenceId || activeStudentIdRef.current !== studentId) return;

      setIsRejected(true);
      AccessibilityInfo.announceForAccessibility('Đã từ chối đề xuất dời lịch. Buổi tập ban đầu không thay đổi.');
    } catch (err) {
      if (!isMountedRef.current) return;
      if (activeOccurrenceIdRef.current !== occurrenceId || activeStudentIdRef.current !== studentId) return;

      setErrorMessage(getWorkoutRescheduleErrorMessage(err));
    } finally {
      if (isMountedRef.current) {
        setIsRejecting(false);
      }
    }
  };

  const supervisionText = (req?: string) => {
    if (req === 'COACH_REQUIRED') return 'Bắt buộc có HLV kèm';
    if (req === 'COACH_OPTIONAL') return 'HLV kèm tùy chọn';
    return 'Tự thực hiện (Không cần HLV)';
  };

  return (
    <ScrollView
      style={[styles.container, { backgroundColor: theme.canvas }]}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled"
    >
      <Text accessibilityRole="header" style={[styles.headerTitle, { color: theme.textPrimary }]}>
        Đề xuất dời lịch tập bằng AI
      </Text>

      <WorkoutButton testID="reschedule-modal-close-top" label="Đóng" secondary onPress={onClose} />

      {/* Global Loading / Status */}
      {isLoadingProposal ? (
        <WorkoutState busy title="Đang xử lý đề xuất từ AI" message="Vui lòng chờ trong giây lát..." />
      ) : null}

      {/* Applied Receipt Screen */}
      {appliedResult ? (
        <WorkoutCard testID="reschedule-applied-receipt">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
            Đã dời lịch tập thành công!
          </Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Buổi tập đã được chuyển sang thời gian mới. Buổi tập cũ vẫn được lưu trong lịch sử để theo dõi.
          </Text>
          {appliedResult.resultingEntityId ? (
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              Mã buổi tập mới: {appliedResult.resultingEntityId}
            </Text>
          ) : null}
          {appliedResult.replayed ? (
            <Text style={[styles.body, { color: theme.warningText }]}>
              (Thao tác đã được ghi nhận trước đó và phát lại an toàn)
            </Text>
          ) : null}
          <WorkoutButton testID="reschedule-applied-close" label="Hoàn tất và quay lại" onPress={onClose} />
        </WorkoutCard>
      ) : null}

      {/* Rejected Receipt Screen */}
      {isRejected ? (
        <WorkoutCard testID="reschedule-rejected-receipt">
          <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
            Đã từ chối đề xuất dời lịch
          </Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Đề xuất dời lịch đã bị từ chối. Buổi tập ban đầu vẫn được giữ nguyên ở trạng thái đã lên lịch.
          </Text>
          <WorkoutButton testID="reschedule-rejected-close" label="Đóng" onPress={onClose} />
        </WorkoutCard>
      ) : null}

      {/* Request Form (when no proposal yet) */}
      {!proposal && !appliedResult && !isRejected && !isLoadingProposal ? (
        <>
          <WorkoutCard testID="reschedule-request-source-card">
            <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
              Thông tin buổi tập cần dời
            </Text>
            <Text style={[styles.label, { color: theme.textPrimary }]}>
              {sessionName?.trim() || 'Buổi tập theo lịch'}
            </Text>
            {currentPlannedStartAt ? (
              <Text style={[styles.body, { color: theme.textSecondary }]}>
                Thời gian ban đầu: {formatScheduleTime(currentPlannedStartAt, timezone ?? null)}
              </Text>
            ) : null}
            {timezone ? (
              <Text style={[styles.body, { color: theme.textSecondary }]}>Múi giờ: {timezone}</Text>
            ) : null}

            {linkedAppointmentId ? (
              <View style={[styles.alertBox, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>
                  Lưu ý lịch hẹn: Buổi tập này có lịch hẹn kèm theo. AI sẽ tìm giờ tập nằm trong lịch hẹn đã xác
                  nhận. Nếu bạn muốn đổi sang ngày giờ khác ngoài lịch hẹn, vui lòng thực hiện đổi lịch hẹn trước.
                </Text>
              </View>
            ) : null}
          </WorkoutCard>

          <WorkoutCard testID="reschedule-request-form">
            <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
              Tùy chọn dời lịch
            </Text>

            <Text style={[styles.label, { color: theme.textPrimary }]}>Thời điểm ưu tiên trong ngày:</Text>
            <View style={styles.buttonRow}>
              {(['MORNING', 'AFTERNOON', 'EVENING'] as const).map((time) => {
                const labelMap = {
                  MORNING: 'Buổi sáng (09:00)',
                  AFTERNOON: 'Buổi chiều (14:00)',
                  EVENING: 'Buổi tối (18:00)',
                };
                return (
                  <WorkoutButton
                    key={time}
                    testID={`time-pref-${time.toLowerCase()}`}
                    label={labelMap[time]}
                    secondary={preferredTimeOfDay !== time}
                    onPress={() => setPreferredTimeOfDay(time)}
                  />
                );
              })}
            </View>
            <Text style={[styles.body, { color: theme.textSecondary, marginTop: spacing.xs }]}>
              Lưu ý: Các mốc 09:00 (Sáng), 14:00 (Chiều) và 18:00 (Tối) là giờ gợi ý phỏng đoán (heuristic), không phải lịch rảnh (availability) đã xác nhận của Huấn luyện viên hay cơ sở.
            </Text>

            <Text style={[styles.label, { color: theme.textPrimary }]}>Lý do dời lịch (tùy chọn):</Text>
            <TextInput
              testID="reschedule-reason-input"
              accessibilityLabel="Lý do dời lịch"
              placeholder="Ví dụ: Bận việc đột xuất, đau cơ, cần hồi phục thêm..."
              value={reason}
              onChangeText={setReason}
              multiline
              maxLength={500}
              style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
            />
            {fieldErrors.reason ? (
              <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>
                {fieldErrors.reason}
              </Text>
            ) : null}

            <WorkoutButton
              testID="generate-reschedule-button"
              label="Tạo đề xuất dời lịch (AI)"
              busy={isLoadingProposal}
              disabled={isLoadingProposal}
              onPress={handleGenerate}
            />
          </WorkoutCard>
        </>
      ) : null}

      {/* Proposal Review Screen */}
      {proposal && !appliedResult && !isRejected && !isLoadingProposal ? (
        <>
          <WorkoutCard testID="reschedule-review-header">
            <View style={styles.badgeRow}>
              <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary, flex: 1 }]}>
                {proposal.title}
              </Text>
              <View
                style={[
                  styles.statusBadge,
                  {
                    backgroundColor:
                      proposal.status === 'PENDING'
                        ? theme.primary
                        : proposal.status === 'APPLIED'
                        ? colors.success[600]
                        : theme.surface,
                  },
                ]}
              >
                <Text style={[styles.statusBadgeText, { color: theme.textOnPrimary }]}>
                  {aiRecommendationStatusLabels[proposal.status as RescheduleRecommendationStatus] ?? proposal.status}
                </Text>
              </View>
            </View>

            <Text style={[styles.body, { color: theme.textSecondary }]}>{proposal.explanation}</Text>

            {proposal.expiresAt ? (
              <Text style={[styles.body, { color: isExpired ? theme.dangerText : theme.textSecondary }]}>
                {isExpired
                  ? 'Đề xuất này đã hết hạn. Hãy tạo đề xuất mới.'
                  : `Hết hạn lúc: ${formatScheduleTime(proposal.expiresAt, timezone ?? null)}${remainingMinutes !== null ? ` (còn khoảng ${remainingMinutes} phút)` : ''}`}
              </Text>
            ) : null}
          </WorkoutCard>

          {payload ? (
            <WorkoutCard testID="reschedule-details-card">
              <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
                Kế hoạch dời lịch
              </Text>

              <View style={styles.detailRow}>
                <Text style={[styles.label, { color: theme.textPrimary }]}>Buổi tập:</Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  {payload.reschedule.sessionName?.trim() || sessionName?.trim() || 'Buổi tập'}
                </Text>
              </View>

              <View style={styles.detailRow}>
                <Text style={[styles.label, { color: theme.textPrimary }]}>Thời gian ban đầu:</Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  {formatScheduleTime(payload.reschedule.previousPlannedStartAt, payload.reschedule.destination.timezone ?? null)}
                </Text>
              </View>

              <View style={styles.destinationBox}>
                <Text style={[styles.label, { color: theme.primary }]}>Thời gian đề xuất mới:</Text>
                <Text style={[styles.destTime, { color: theme.textPrimary }]}>
                  {payload.reschedule.destination.localStart} UTC{payload.reschedule.destination.startUtcOffset} →{' '}
                  {payload.reschedule.destination.localEnd} UTC{payload.reschedule.destination.endUtcOffset}
                </Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  Múi giờ: {payload.reschedule.destination.timezone}
                </Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  Giờ hệ thống: {formatScheduleTime(payload.reschedule.destination.proposedStartAt, payload.reschedule.destination.timezone ?? null)}
                </Text>
                <Text style={[styles.body, { color: theme.textSecondary, marginTop: spacing.xs }]}>
                  Khung giờ đề xuất trên dựa trên phân tích phỏng đoán của AI và khung giờ ưu tiên; chưa bao gồm xác nhận lịch rảnh thực tế của huấn luyện viên hoặc cơ sở.
                </Text>
              </View>

              <View style={styles.detailRow}>
                <Text style={[styles.label, { color: theme.textPrimary }]}>Hình thức giám sát:</Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  {supervisionText(payload.reschedule.supervisionRequirement)}
                </Text>
              </View>

              {payload.reasoning && payload.reasoning.length > 0 ? (
                <View style={styles.sectionGap}>
                  <Text style={[styles.label, { color: theme.textPrimary }]}>Lý giải của AI:</Text>
                  {payload.reasoning.map((r, i) => (
                    <Text key={i} style={[styles.body, { color: theme.textSecondary }]}>
                      • {r}
                    </Text>
                  ))}
                </View>
              ) : null}

              {payload.missingData && payload.missingData.length > 0 ? (
                <View style={styles.sectionGap}>
                  <Text style={[styles.label, { color: theme.textSecondary }]}>Dữ liệu chưa có:</Text>
                  {payload.missingData.map((m, i) => (
                    <Text key={i} style={[styles.body, { color: theme.textSecondary }]}>
                      - {m}
                    </Text>
                  ))}
                </View>
              ) : null}
            </WorkoutCard>
          ) : null}

          {/* Authority Guidance */}
          {(proposal.decisionAuthority === 'TRAINER' || coachingMode === 'HUMAN_COACH') && !isTrainer ? (
            <View style={[styles.alertBox, { backgroundColor: theme.surface, borderColor: theme.border }]}>
              <Text accessibilityRole="alert" style={[styles.body, { color: theme.textSecondary }]}>
                Trong chế độ có Huấn luyện viên (HUMAN_COACH), chỉ Huấn luyện viên mới có quyền phê duyệt áp dụng
                thay đổi giáo án. Bạn có thể xem đề xuất hoặc từ chối đề xuất này.
              </Text>
            </View>
          ) : null}

          {/* Action Buttons */}
          {proposal.status === 'PENDING' && (!isExpired || Boolean(pendingAcceptCommandKey)) ? (
            <View style={styles.actionColumn}>
              {canAccept ? (
                <WorkoutButton
                  testID="accept-reschedule-button"
                  label={pendingAcceptCommandKey && errorMessage ? 'Thử lại cùng lệnh (Xác nhận & Áp dụng)' : 'Chấp nhận & Áp dụng'}
                  busy={isApplying}
                  disabled={isApplying || isRejecting}
                  onPress={handleAccept}
                />
              ) : null}

              {!isExpired && !showRejectForm ? (
                <WorkoutButton
                  testID="toggle-reject-form-button"
                  label="Từ chối đề xuất này"
                  secondary
                  danger
                  disabled={isApplying || isRejecting}
                  onPress={() => setShowRejectForm(true)}
                />
              ) : null}

              {!isExpired && showRejectForm ? (
                <WorkoutCard testID="reject-form-card">
                  <Text style={[styles.label, { color: theme.textPrimary }]}>Lý do từ chối (tùy chọn):</Text>
                  <TextInput
                    testID="reject-note-input"
                    accessibilityLabel="Lý do từ chối"
                    placeholder="Nhập lý do không đồng ý với khung giờ này..."
                    value={decisionNote}
                    onChangeText={setDecisionNote}
                    multiline
                    maxLength={500}
                    style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                  />
                  <View style={styles.buttonRow}>
                    <WorkoutButton
                      testID="confirm-reject-button"
                      label="Xác nhận từ chối"
                      danger
                      busy={isRejecting}
                      disabled={isApplying || isRejecting}
                      onPress={handleReject}
                    />
                    <WorkoutButton
                      testID="cancel-reject-button"
                      label="Hủy"
                      secondary
                      disabled={isRejecting}
                      onPress={() => setShowRejectForm(false)}
                    />
                  </View>
                </WorkoutCard>
              ) : null}
            </View>
          ) : null}

          {isExpired || isStaleConflict ? (
            <WorkoutButton
              testID="regenerate-reschedule-button"
              label="Tạo đề xuất mới"
              secondary
              onPress={resetForm}
            />
          ) : null}
        </>
      ) : null}

      {/* Error Display */}
      {errorMessage ? (
        <WorkoutState
          testID="reschedule-error-state"
          title="Chưa thể hoàn tất thao tác"
          message={errorMessage}
          onRetry={isStaleConflict ? resetForm : undefined}
        />
      ) : null}
    </ScrollView>
  );
}

export function AiWorkoutRescheduleModal(props: AiWorkoutRescheduleModalProps) {
  return (
    <Modal
      testID="ai-workout-reschedule-modal"
      visible={props.visible}
      animationType="slide"
      transparent={false}
      onRequestClose={props.onClose}
    >
      {props.visible ? <RescheduleModalContent {...props} /> : null}
    </Modal>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  content: {
    padding: layout.mobileScreenPadding,
    paddingBottom: spacing.xxl,
    gap: spacing.md,
  },
  headerTitle: {
    ...typography.h2,
    marginBottom: spacing.xs,
  },
  h2: typography.h3,
  label: typography.label,
  body: typography.bodySmall,
  destTime: {
    ...typography.label,
    fontSize: 16,
    marginVertical: spacing.xs,
  },
  destinationBox: {
    padding: spacing.md,
    borderRadius: radius.md,
    borderWidth: 1,
    borderColor: '#7C3AED33',
    backgroundColor: '#7C3AED0A',
    gap: spacing.xs,
  },
  buttonRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
  },
  badgeRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  statusBadge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: radius.sm,
  },
  statusBadgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  detailRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  sectionGap: {
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  actionColumn: {
    gap: spacing.sm,
    marginTop: spacing.sm,
  },
  alertBox: {
    padding: spacing.md,
    borderRadius: radius.md,
    borderWidth: 1,
    gap: spacing.xs,
  },
  input: {
    minHeight: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    ...typography.body,
  },
});
