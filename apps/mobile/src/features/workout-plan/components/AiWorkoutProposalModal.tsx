import React, { useState } from 'react';
import {
  Modal,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  useColorScheme,
} from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { aiWorkoutProposalApi, getWorkoutProposalErrorMessage } from '@/services/aiWorkoutProposalApi';
import { WorkoutProposalPayload, WorkoutProposalResponse } from '@/types/aiWorkoutProposal';
import { WorkoutButton, WorkoutCard, WorkoutState } from './WorkoutUi';

interface Props {
  visible: boolean;
  studentId?: string;
  isTrainer?: boolean;
  onClose: () => void;
  onPlanApplied?: (planId: string) => void;
}

export function AiWorkoutProposalModal({
  visible,
  studentId,
  isTrainer = false,
  onClose,
  onPlanApplied,
}: Props) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);

  const [daysPerWeek, setDaysPerWeek] = useState<number>(3);
  const [preferencesNote, setPreferencesNote] = useState<string>('');
  const [decisionNote, setDecisionNote] = useState<string>('');

  const [isGenerating, setIsGenerating] = useState<boolean>(false);
  const [isDeciding, setIsDeciding] = useState<boolean>(false);
  const [proposal, setProposal] = useState<WorkoutProposalResponse | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [appliedPlanId, setAppliedPlanId] = useState<string | null>(null);

  const handleGenerate = async () => {
    setIsGenerating(true);
    setErrorMessage(null);
    try {
      const res = await aiWorkoutProposalApi.generate({
        studentId,
        preferences: {
          daysPerWeek,
          note: preferencesNote.trim(),
        },
      });
      setProposal(res);
    } catch (err) {
      setErrorMessage(getWorkoutProposalErrorMessage(err));
    } finally {
      setIsGenerating(false);
    }
  };

  const handleAccept = async () => {
    if (!proposal) return;
    setIsDeciding(true);
    setErrorMessage(null);
    try {
      const res = await aiWorkoutProposalApi.accept(
        proposal.recommendationId,
        decisionNote.trim() || undefined
      );
      setProposal({
        ...proposal,
        status: 'APPLIED',
      });
      setAppliedPlanId(res.resultingEntityId);
      if (onPlanApplied) {
        onPlanApplied(res.resultingEntityId);
      }
    } catch (err) {
      setErrorMessage(getWorkoutProposalErrorMessage(err));
    } finally {
      setIsDeciding(false);
    }
  };

  const handleReject = async () => {
    if (!proposal) return;
    setIsDeciding(true);
    setErrorMessage(null);
    try {
      await aiWorkoutProposalApi.reject(
        proposal.recommendationId,
        decisionNote.trim() || 'Người dùng từ chối đề xuất'
      );
      setProposal({
        ...proposal,
        status: 'REJECTED',
      });
    } catch (err) {
      setErrorMessage(getWorkoutProposalErrorMessage(err));
    } finally {
      setIsDeciding(false);
    }
  };

  const payload: WorkoutProposalPayload | null = proposal?.structuredPayload ?? null;

  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent={false}
      onRequestClose={onClose}
    >
      <View style={[styles.container, { backgroundColor: theme.canvas }]}>
        <View style={[styles.header, { borderBottomColor: theme.border }]}>
          <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>
            Đề xuất giáo án 1 tuần (AI)
          </Text>
          <TouchableOpacity
            testID="close-ai-proposal-modal-button"
            accessibilityLabel="Đóng"
            onPress={onClose}
            style={styles.closeBtn}
          >
            <Text style={[styles.closeText, { color: theme.textSecondary }]}>✕</Text>
          </TouchableOpacity>
        </View>

        <ScrollView contentContainerStyle={styles.scrollContent}>
          {errorMessage ? (
            <WorkoutCard testID="ai-proposal-error-banner">
              <Text style={[styles.errorTitle, { color: theme.dangerText }]}>Lỗi xử lý</Text>
              <Text style={[styles.errorBody, { color: theme.textPrimary }]}>{errorMessage}</Text>
            </WorkoutCard>
          ) : null}

          {!proposal ? (
            <WorkoutCard testID="ai-proposal-request-form">
              <Text style={[styles.h2, { color: theme.textPrimary }]}>Tùy chọn tạo giáo án</Text>
              <Text style={[styles.body, { color: theme.textSecondary }]}>
                Hệ thống AI sẽ đối chiếu mục tiêu và dữ liệu tập luyện hiện tại để thiết kế giáo án mẫu chuẩn xác trong phạm vi đúng 1 tuần.
              </Text>

              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Số ngày tập mỗi tuần:
              </Text>
              <View style={styles.daysSelector}>
                {[3, 4].map((days) => (
                  <TouchableOpacity
                    key={days}
                    testID={`days-per-week-${days}`}
                    style={[
                      styles.dayOption,
                      {
                        backgroundColor: daysPerWeek === days ? theme.primary : theme.surfaceSubtle,
                      },
                    ]}
                    onPress={() => setDaysPerWeek(days)}
                  >
                    <Text
                      style={[
                        styles.dayOptionText,
                        { color: daysPerWeek === days ? theme.textOnPrimary : theme.textPrimary },
                      ]}
                    >
                      {days} buổi / tuần
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>

              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Ghi chú hoặc ưu tiên đặc biệt:
              </Text>
              <Text style={[styles.subLabel, { color: theme.textSecondary }]}>
                Văn bản tự do chỉ mang tính tham khảo sở thích cá nhân, không thay thế dữ liệu y tế hoặc chẩn đoán chấn thương đã xác thực.
              </Text>
              <TextInput
                testID="ai-proposal-notes-input"
                style={[
                  styles.textInput,
                  {
                    color: theme.textPrimary,
                    borderColor: theme.border,
                    backgroundColor: theme.surfaceSubtle,
                  },
                ]}
                placeholder="Ví dụ: Ưu tiên thân trên, hạn chế tạ nặng bài squat..."
                placeholderTextColor={theme.textSecondary}
                multiline
                numberOfLines={3}
                value={preferencesNote}
                onChangeText={setPreferencesNote}
              />

              <WorkoutButton
                testID="generate-ai-proposal-button"
                label={isGenerating ? 'Đang phân tích và tạo giáo án...' : 'Tạo đề xuất giáo án (AI)'}
                busy={isGenerating}
                disabled={isGenerating}
                onPress={handleGenerate}
              />
            </WorkoutCard>
          ) : (
            <View testID="ai-proposal-detail-view">
              <WorkoutCard testID="ai-proposal-summary-card">
                <View style={styles.badgeRow}>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.badgeText, { color: theme.textPrimary }]}>
                      Trạng thái: {proposal.status}
                    </Text>
                  </View>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.badgeText, { color: theme.textPrimary }]}>
                      Thẩm quyền: {proposal.decisionAuthority}
                    </Text>
                  </View>
                </View>

                <Text style={[styles.h2, { color: theme.textPrimary }]}>
                  {proposal.title}
                </Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  {proposal.explanation}
                </Text>

                {payload?.confidence ? (
                  <Text style={[styles.metaText, { color: theme.textSecondary, marginTop: 4 }]}>
                    Độ tự tin từ mô hình (tự đánh giá, chưa qua kiểm chứng lâm sàng): {(payload.confidence * 100).toFixed(0)}%
                  </Text>
                ) : null}
              </WorkoutCard>

              {/* Reasoning */}
              {payload?.reasoning && payload.reasoning.length > 0 ? (
                <WorkoutCard testID="ai-proposal-reasoning-card">
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>Căn cứ đề xuất</Text>
                  {payload.reasoning.map((item, idx) => (
                    <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {item}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Missing data & limitations */}
              {payload?.missingData && payload.missingData.length > 0 ? (
                <WorkoutCard testID="ai-proposal-missing-data-card">
                  <Text style={[styles.h3, { color: theme.warningText }]}>
                    Dữ liệu còn thiếu
                  </Text>
                  {payload.missingData.map((item, idx) => (
                    <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {item}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {payload?.limitations && payload.limitations.length > 0 ? (
                <WorkoutCard testID="ai-proposal-limitations-card">
                  <Text style={[styles.h3, { color: theme.textSecondary }]}>Giới hạn lâm sàng & thực hành</Text>
                  {payload.limitations.map((item, idx) => (
                    <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {item}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Sessions & Prescriptions */}
              {payload?.plan?.sessions ? (
                <WorkoutCard testID="ai-proposal-sessions-card">
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>
                    Lịch tập 1 tuần ({payload.plan.sessions.length} buổi)
                  </Text>
                  {payload.plan.sessions.map((session, sIdx) => (
                    <View key={sIdx} style={[styles.sessionBlock, { borderTopColor: theme.border }]}>
                      <Text style={[styles.sessionTitle, { color: theme.textPrimary }]}>
                        {session.name} {session.focus ? `· ${session.focus}` : ''}
                      </Text>
                      {session.estimatedDurationMinutes ? (
                        <Text style={[styles.sessionMeta, { color: theme.textSecondary }]}>
                          Thời lượng ước tính: {session.estimatedDurationMinutes} phút
                        </Text>
                      ) : null}
                      {session.prescriptions.map((p, pIdx) => (
                        <View key={pIdx} style={styles.prescriptionRow}>
                          <Text style={[styles.prescriptionText, { color: theme.textPrimary }]}>
                            {pIdx + 1}. Bài tập (ID: {p.exerciseVariationId.slice(0, 8)}...): {p.targetSets} hiệp
                            {p.targetRepsMin ? ` x ${p.targetRepsMin}-${p.targetRepsMax ?? p.targetRepsMin} lần` : ''}
                            {p.restSeconds ? ` (Nghỉ ${p.restSeconds}s)` : ''}
                          </Text>
                        </View>
                      ))}
                    </View>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Decision Section */}
              {proposal.status === 'PENDING' ? (
                <WorkoutCard testID="ai-proposal-decision-card">
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>Quyết định thẩm quyền</Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    {proposal.decisionAuthority === 'TRAINER' && !isTrainer
                      ? 'Lưu ý: Bạn đang ở chế độ Huấn luyện viên. Chỉ Huấn luyện viên phụ trách mới có thẩm quyền chấp thuận đề xuất này.'
                      : 'Khi chấp thuận, kế hoạch 1 tuần sẽ được tự động tạo và kích hoạt trong hệ thống.'}
                  </Text>

                  <Text style={[styles.label, { color: theme.textPrimary, marginTop: 8 }]}>
                    Ghi chú quyết định:
                  </Text>
                  <TextInput
                    testID="ai-decision-note-input"
                    style={[
                      styles.textInput,
                      {
                        color: theme.textPrimary,
                        borderColor: theme.border,
                        backgroundColor: theme.surfaceSubtle,
                      },
                    ]}
                    placeholder="Ghi chú lý do chấp thuận hoặc từ chối..."
                    placeholderTextColor={theme.textSecondary}
                    value={decisionNote}
                    onChangeText={setDecisionNote}
                  />

                  <View style={styles.decisionActions}>
                    <WorkoutButton
                      testID="accept-ai-proposal-button"
                      label={isDeciding ? 'Đang áp dụng...' : 'Chấp thuận & Áp dụng'}
                      busy={isDeciding}
                      disabled={isDeciding}
                      onPress={handleAccept}
                    />
                    <WorkoutButton
                      testID="reject-ai-proposal-button"
                      label="Từ chối đề xuất"
                      secondary
                      disabled={isDeciding}
                      onPress={handleReject}
                    />
                  </View>
                </WorkoutCard>
              ) : null}

              {proposal.status === 'APPLIED' ? (
                <WorkoutCard testID="ai-proposal-applied-success">
                  <Text style={[styles.h3, { color: theme.successText }]}>Đã áp dụng thành công</Text>
                  <Text style={[styles.body, { color: theme.textPrimary }]}>
                    Giáo án đã được tạo và kích hoạt. ID kế hoạch: {appliedPlanId ?? 'N/A'}.
                  </Text>
                </WorkoutCard>
              ) : null}

              {proposal.status === 'REJECTED' ? (
                <WorkoutCard testID="ai-proposal-rejected-notice">
                  <Text style={[styles.h3, { color: theme.textSecondary }]}>Đã từ chối đề xuất</Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    Đề xuất giáo án này đã bị từ chối và không làm thay đổi kế hoạch tập luyện hiện hành.
                  </Text>
                </WorkoutCard>
              ) : null}
            </View>
          )}
        </ScrollView>
      </View>
    </Modal>
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
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
  },
  closeBtn: {
    padding: 8,
  },
  closeText: {
    fontSize: 20,
    fontWeight: '600',
  },
  scrollContent: {
    padding: 16,
    gap: 12,
  },
  h2: {
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 4,
  },
  h3: {
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 6,
  },
  body: {
    fontSize: 14,
    lineHeight: 20,
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 4,
  },
  subLabel: {
    fontSize: 12,
    lineHeight: 16,
    marginBottom: 4,
  },
  daysSelector: {
    flexDirection: 'row',
    gap: 8,
    marginVertical: 6,
  },
  dayOption: {
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: 8,
  },
  dayOptionText: {
    fontSize: 13,
    fontWeight: '600',
  },
  textInput: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    fontSize: 14,
    marginVertical: 6,
  },
  badgeRow: {
    flexDirection: 'row',
    gap: 8,
    marginBottom: 8,
  },
  badge: {
    paddingVertical: 4,
    paddingHorizontal: 8,
    borderRadius: 4,
  },
  badgeText: {
    fontSize: 11,
    fontWeight: '600',
  },
  metaText: {
    fontSize: 12,
  },
  bulletItem: {
    fontSize: 13,
    lineHeight: 18,
    marginVertical: 2,
  },
  sessionBlock: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: 8,
    marginTop: 8,
  },
  sessionTitle: {
    fontSize: 14,
    fontWeight: '600',
  },
  sessionMeta: {
    fontSize: 12,
    marginBottom: 4,
  },
  prescriptionRow: {
    paddingVertical: 2,
  },
  prescriptionText: {
    fontSize: 12,
  },
  decisionActions: {
    marginTop: 12,
    gap: 8,
  },
  errorTitle: {
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 2,
  },
  errorBody: {
    fontSize: 13,
  },
});
