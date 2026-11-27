import React, { useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { fontFamily, typography } from '@/design-system/tokens/typography';

interface PauseResumeGoalModalProps {
  visible: boolean;
  action: 'pause' | 'resume';
  goalTitle: string;
  isSubmitting: boolean;
  isDark: boolean;
  onClose: () => void;
  onSubmit: (reason: string) => Promise<void>;
}

export function PauseResumeGoalModal({
  visible,
  action,
  goalTitle,
  isSubmitting,
  isDark,
  onClose,
  onSubmit,
}: PauseResumeGoalModalProps) {
  const [reason, setReason] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);
  const [showConfirm, setShowConfirm] = useState(false);

  const themeColors = getSemanticColors(isDark);
  const isPause = action === 'pause';
  const titleText = isPause ? 'Tạm dừng mục tiêu thể chất' : 'Tiếp tục mục tiêu thể chất';
  const actionButtonText = isPause ? 'Tạm dừng mục tiêu' : 'Tiếp tục mục tiêu';

  const trimmedReason = reason.trim();
  const charCount = trimmedReason.length;
  const isTooLong = charCount > 1000;
  const isBlank = charCount === 0;
  const canProceed = !isBlank && !isTooLong && !isSubmitting;

  const handleInitialSubmit = () => {
    if (isBlank) {
      setValidationError('Vui lòng nêu lý do thay đổi trạng thái.');
      return;
    }
    if (isTooLong) {
      setValidationError('Lý do không được quá 1.000 ký tự.');
      return;
    }
    setValidationError(null);
    setShowConfirm(true);
  };

  const handleFinalConfirm = async () => {
    if (!canProceed) return;
    try {
      await onSubmit(trimmedReason);
      setReason('');
      setShowConfirm(false);
    } catch {
      // Error handled by parent component
    }
  };

  const handleClose = () => {
    if (isSubmitting) return;
    setReason('');
    setValidationError(null);
    setShowConfirm(false);
    onClose();
  };

  return (
    <Modal
      visible={visible}
      transparent
      animationType="fade"
      onRequestClose={handleClose}>
      <View style={styles.overlay}>
        <View
          style={[
            styles.dialog,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
            },
          ]}>
          <Text style={[styles.title, { color: themeColors.textPrimary }]}>
            {showConfirm ? 'Xác nhận thao tác' : titleText}
          </Text>

          <Text style={[styles.goalName, { color: themeColors.textSecondary }]}>
            Mục tiêu: {goalTitle}
          </Text>

          {!showConfirm ? (
            <>
              <Text style={[styles.instruction, { color: themeColors.textSecondary }]}>
                {isPause
                  ? 'Tạm dừng sẽ ngưng tính thời gian tập của mục tiêu, giữ nguyên lịch sử và ngày đích. Vui lòng nêu lý do tạm dừng:'
                  : 'Tiếp tục sẽ kích hoạt lại hành trình tập luyện. Thời gian tạm dừng vẫn được ghi trong lịch sử tiến độ. Vui lòng nêu lý do tiếp tục:'}
              </Text>

              <TextInput
                testID="pause-resume-reason-input"
                accessibilityLabel="Lý do thay đổi trạng thái"
                style={[
                  styles.input,
                  {
                    color: themeColors.textPrimary,
                    borderColor: validationError || isTooLong ? themeColors.dangerText : themeColors.border,
                    backgroundColor: themeColors.surfaceSubtle,
                  },
                ]}
                placeholder="Nhập lý do bắt buộc (tối đa 1000 ký tự)..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={4}
                value={reason}
                onChangeText={(text) => {
                  setReason(text);
                  if (validationError) setValidationError(null);
                }}
                editable={!isSubmitting}
                maxLength={1050}
              />

              <View style={styles.counterRow}>
                {validationError ? (
                  <Text testID="pause-resume-error-text" style={[styles.errorText, { color: themeColors.dangerText }]}>
                    {validationError}
                  </Text>
                ) : <View />}
                <Text
                  style={[
                    styles.counterText,
                    {
                      color: isTooLong ? themeColors.dangerText : themeColors.textSecondary,
                    },
                  ]}>
                  {charCount}/1000
                </Text>
              </View>

              <View style={styles.buttonRow}>
                <Pressable
                  testID="pause-resume-cancel-button"
                  accessibilityRole="button"
                  accessibilityLabel="Hủy"
                  style={[styles.cancelButton, { borderColor: themeColors.border }]}
                  onPress={handleClose}
                  disabled={isSubmitting}>
                  <Text style={[styles.cancelButtonText, { color: themeColors.textSecondary }]}>
                    Hủy
                  </Text>
                </Pressable>

                <Pressable
                  testID="pause-resume-continue-button"
                  accessibilityRole="button"
                  accessibilityLabel={actionButtonText}
                  accessibilityState={{ disabled: isSubmitting, busy: isSubmitting }}
                  style={[
                    styles.submitButton,
                    {
                      backgroundColor: canProceed
                        ? isPause
                          ? themeColors.warningText
                          : themeColors.primary
                        : themeColors.border,
                    },
                  ]}
                  onPress={handleInitialSubmit}
                  disabled={isSubmitting}>
                  <Text style={styles.submitButtonText}>Tiếp tục</Text>
                </Pressable>
              </View>
            </>
          ) : (
            <>
              <Text style={[styles.confirmText, { color: themeColors.textPrimary }]}>
                {isPause
                  ? 'Bạn có chắc muốn tạm dừng mục tiêu? Bạn có thể tiếp tục khi sẵn sàng tập lại.'
                  : 'Bạn có chắc muốn tiếp tục mục tiêu? Hãy đảm bảo bạn không có mục tiêu thể chất khác đang hoạt động.'}
              </Text>

              <View
                style={[
                  styles.reasonPreviewCard,
                  {
                    backgroundColor: themeColors.surfaceSubtle,
                    borderColor: themeColors.border,
                  },
                ]}>
                <Text style={[styles.reasonLabel, { color: themeColors.textSecondary }]}>
                  Lý do được ghi lại:
                </Text>
                <Text style={[styles.reasonValue, { color: themeColors.textPrimary }]}>
                  {`"${trimmedReason}"`}
                </Text>
              </View>

              <View style={styles.buttonRow}>
                <Pressable
                  testID="pause-resume-back-button"
                  accessibilityRole="button"
                  accessibilityLabel="Quay lại chỉnh sửa lý do"
                  style={[styles.cancelButton, { borderColor: themeColors.border }]}
                  onPress={() => setShowConfirm(false)}
                  disabled={isSubmitting}>
                  <Text style={[styles.cancelButtonText, { color: themeColors.textSecondary }]}>
                    Quay lại
                  </Text>
                </Pressable>

                <Pressable
                  testID="pause-resume-confirm-button"
                  accessibilityRole="button"
                  accessibilityLabel={`Xác nhận ${actionButtonText}`}
                  accessibilityState={{ disabled: isSubmitting, busy: isSubmitting }}
                  style={[
                    styles.submitButton,
                    {
                      backgroundColor: isPause
                        ? themeColors.warningText
                        : themeColors.primary,
                    },
                  ]}
                  onPress={handleFinalConfirm}
                  disabled={isSubmitting}>
                  {isSubmitting ? (
                    <ActivityIndicator color={themeColors.textOnPrimary} />
                  ) : (
                    <Text style={styles.submitButtonText}>Xác nhận và {actionButtonText}</Text>
                  )}
                </Pressable>
              </View>
            </>
          )}
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0, 0, 0, 0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.lg,
  },
  dialog: {
    width: '100%',
    maxWidth: 480,
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.lg,
    gap: spacing.md,
  },
  title: {
    ...typography.h3,
  },
  goalName: {
    ...typography.label,
    fontFamily: fontFamily.semiBold,
  },
  instruction: {
    ...typography.bodySmall,
    lineHeight: 20,
  },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
    ...typography.body,
    minHeight: 100,
    textAlignVertical: 'top',
  },
  counterRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  errorText: {
    ...typography.caption,
  },
  counterText: {
    ...typography.caption,
  },
  confirmText: {
    ...typography.body,
    lineHeight: 22,
  },
  reasonPreviewCard: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
  reasonLabel: {
    ...typography.caption,
  },
  reasonValue: {
    ...typography.bodySmall,
    fontFamily: fontFamily.regularItalic,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  cancelButton: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    justifyContent: 'center',
    alignItems: 'center',
  },
  cancelButtonText: {
    ...typography.label,
  },
  submitButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  submitButtonText: {
    ...typography.label,
    color: '#FFFFFF',
  },
});
