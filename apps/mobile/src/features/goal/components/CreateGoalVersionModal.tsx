import React, { useState } from 'react';
import {
  ActivityIndicator,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { formatGoalTypeDisplay } from '@/features/goal/goalTypeOptions';
import {
  resolveGoalModalErrorMessage,
  useGoalModalSessionGuard,
} from './CreateGoalModal';

function normalizeGoalTypeCode(code?: string | null): string | null {
  const trimmed = code?.trim();
  return trimmed ? trimmed : null;
}

interface CreateGoalVersionModalProps {
  visible: boolean;
  currentTitle: string;
  currentGoalTypeCode?: string | null;
  targetMismatchError?: string | null;
  isSubmitting: boolean;
  isDark: boolean;
  sessionId?: number;
  onClose: () => void;
  onSubmit: (payload: {
    title?: string;
    changeReason: string;
    changeSummary?: string;
    durationDays?: number;
    goalTypeCode: string;
  }) => Promise<void>;
}

export function CreateGoalVersionModal({
  visible,
  currentTitle,
  currentGoalTypeCode,
  targetMismatchError,
  isSubmitting,
  isDark,
  sessionId = 0,
  onClose,
  onSubmit,
}: CreateGoalVersionModalProps) {
  const [title, setTitle] = useState(currentTitle);
  const [changeReason, setChangeReason] = useState('');
  const [changeSummary, setChangeSummary] = useState('');
  const [lockedGoalType, setLockedGoalType] = useState<string | null>(() =>
    normalizeGoalTypeCode(currentGoalTypeCode)
  );
  const [durationDays, setDurationDays] = useState('');

  const themeColors = getSemanticColors(isDark);

  const resetFields = () => {
    setTitle(currentTitle);
    setChangeReason('');
    setChangeSummary('');
    setLockedGoalType(normalizeGoalTypeCode(currentGoalTypeCode));
    setDurationDays('');
  };

  const {
    validationError,
    setValidationError,
    isBusy,
    beginRequest,
    finishRequest,
    clearLocalSubmitting,
  } = useGoalModalSessionGuard({
    visible,
    isSubmitting,
    sessionId,
    onResetForm: resetFields,
  });

  const performClose = () => {
    resetFields();
    setValidationError(null);
    clearLocalSubmitting();
    onClose();
  };

  const handleClose = () => {
    if (isBusy) return;
    performClose();
  };

  const handleSubmit = async () => {
    if (isBusy) return;
    if (targetMismatchError) {
      setValidationError(targetMismatchError);
      return;
    }
    if (!lockedGoalType) {
      setValidationError(
        'Chưa xác định được loại mục tiêu chính (PRIMARY) hiện tại. Không thể lưu phiên bản mới.'
      );
      return;
    }
    const trimmedReason = changeReason.trim();
    if (!trimmedReason) {
      setValidationError('Vui lòng nêu lý do tạo phiên bản mục tiêu mới.');
      return;
    }
    if (trimmedReason.length > 100) {
      setValidationError('Lý do thay đổi không được quá 100 ký tự.');
      return;
    }

    let parsedDuration: number | undefined;
    if (durationDays.trim()) {
      const num = parseInt(durationDays.trim(), 10);
      if (isNaN(num) || num <= 0) {
        setValidationError('Thời hạn phải là số ngày dương.');
        return;
      }
      parsedDuration = num;
    }

    const guard = beginRequest();
    try {
      await onSubmit({
        title: title.trim() || undefined,
        changeReason: trimmedReason,
        changeSummary: changeSummary.trim() || undefined,
        durationDays: parsedDuration,
        goalTypeCode: lockedGoalType,
      });
      if (guard.isCurrent()) {
        performClose();
      }
    } catch (err) {
      if (guard.isCurrent()) {
        setValidationError(resolveGoalModalErrorMessage(err, 'Không thể điều chỉnh mục tiêu.'));
      }
    } finally {
      finishRequest(guard);
    }
  };

  return (
    <Modal
      testID="create-goal-version-modal"
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
          <ScrollView keyboardShouldPersistTaps="handled">
            <Text style={[styles.title, { color: themeColors.textPrimary }]}>
              Điều chỉnh mục tiêu (Phiên bản mới)
            </Text>
            <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
              Tạo phiên bản mới để điều chỉnh chỉ tiêu hoặc thời hạn, đồng thời giữ nguyên toàn bộ lịch sử trước đó.
            </Text>

            {(targetMismatchError || validationError) && (
              <View
                testID="goal-version-error-banner"
                style={[
                  styles.errorBanner,
                  { backgroundColor: themeColors.dangerSurface, borderColor: themeColors.dangerText },
                ]}>
                <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                  {targetMismatchError || validationError}
                </Text>
              </View>
            )}

            {/* Change Reason Input (Mandatory) */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Lý do thay đổi * (Tối đa 100 ký tự)
              </Text>
              <TextInput
                testID="goal-version-reason-input"
                accessibilityLabel="Lý do thay đổi"
                style={[
                  styles.input,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={changeReason}
                onChangeText={(val) => {
                  setChangeReason(val);
                  if (validationError) setValidationError(null);
                }}
                placeholder="Ví dụ: Điều chỉnh lịch, tiến triển nhanh hơn dự kiến"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={100}
                editable={!isBusy}
              />
            </View>

            {/* Change Summary Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Tóm tắt thay đổi / Bối cảnh (không bắt buộc)
              </Text>
              <TextInput
                testID="goal-version-summary-input"
                accessibilityLabel="Tóm tắt thay đổi"
                style={[
                  styles.textArea,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={changeSummary}
                onChangeText={setChangeSummary}
                placeholder="Mô tả những điều chỉnh đã được thực hiện trong phiên bản này..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={3}
                maxLength={2000}
                editable={!isBusy}
              />
            </View>

            {/* Updated Title Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Tiêu đề mục tiêu
              </Text>
              <TextInput
                testID="goal-version-title-input"
                accessibilityLabel="Tên mục tiêu đã cập nhật"
                style={[
                  styles.input,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={title}
                onChangeText={setTitle}
                placeholder="Tiêu đề mục tiêu"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={200}
                editable={!isBusy}
              />
            </View>

            {/* Read-only Primary Objective Type */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Loại mục tiêu chính (PRIMARY)
              </Text>
              {lockedGoalType ? (
                <View
                  testID="goal-version-primary-readonly"
                  style={[
                    styles.readonlyCard,
                    {
                      backgroundColor: themeColors.surfaceSubtle,
                      borderColor: themeColors.border,
                    },
                  ]}>
                  <Text style={[styles.readonlyValue, { color: themeColors.textPrimary }]}>
                    {formatGoalTypeDisplay(lockedGoalType)}
                  </Text>
                  <Text style={[styles.readonlyHint, { color: themeColors.textSecondary }]}>
                    Phiên bản mới giữ nguyên loại mục tiêu chính hiện tại. Để đổi loại mục tiêu, hãy dùng luồng chuyển sang mục tiêu mới.
                  </Text>
                </View>
              ) : (
                <View
                  testID="goal-version-primary-missing"
                  style={[
                    styles.errorBanner,
                    {
                      backgroundColor: themeColors.dangerSurface,
                      borderColor: themeColors.dangerText,
                      marginBottom: 0,
                    },
                  ]}>
                  <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                    Chưa xác định loại mục tiêu chính (PRIMARY) hiện tại. Không thể lưu phiên bản mới.
                  </Text>
                </View>
              )}
            </View>

            {/* Duration Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Thời hạn điều chỉnh (ngày)
              </Text>
              <TextInput
                testID="goal-version-duration-input"
                accessibilityLabel="Thời hạn điều chỉnh tính bằng ngày"
                style={[
                  styles.input,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={durationDays}
                onChangeText={setDurationDays}
                placeholder="e.g. 60"
                placeholderTextColor={themeColors.textSecondary}
                keyboardType="numeric"
                editable={!isBusy}
              />
            </View>

            {/* Actions */}
            <View style={styles.actions}>
              <Pressable
                testID="goal-version-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Hủy điều chỉnh mục tiêu"
                disabled={isBusy}
                onPress={handleClose}
                style={[styles.button, styles.secondaryButton]}>
                <Text style={[styles.secondaryButtonText, { color: themeColors.textSecondary }]}>
                  Hủy
                </Text>
              </Pressable>

              <Pressable
                testID="goal-version-submit-button"
                accessibilityRole="button"
                accessibilityLabel="Lưu phiên bản đã điều chỉnh"
                disabled={isBusy || !lockedGoalType || Boolean(targetMismatchError)}
                onPress={handleSubmit}
                style={[
                  styles.button,
                  styles.primaryButton,
                  {
                    backgroundColor: themeColors.primary,
                    opacity: isBusy || !lockedGoalType || Boolean(targetMismatchError) ? 0.5 : 1,
                  },
                ]}>
                {isBusy ? (
                  <ActivityIndicator size="small" color="#ffffff" />
                ) : (
                  <Text style={styles.primaryButtonText}>Lưu phiên bản mới</Text>
                )}
              </Pressable>
            </View>
          </ScrollView>
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
    padding: spacing.md,
  },
  dialog: {
    width: '100%',
    maxHeight: '90%',
    borderRadius: radius.xl,
    borderWidth: 1,
    padding: spacing.lg,
  },
  title: {
    ...typography.h2,
    marginBottom: spacing.xs,
  },
  subtitle: {
    ...typography.bodySmall,
    lineHeight: 20,
    marginBottom: spacing.md,
  },
  errorBanner: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.sm,
    marginBottom: spacing.md,
  },
  errorText: {
    ...typography.caption,
  },
  fieldGroup: {
    gap: spacing.xs,
    marginBottom: spacing.md,
  },
  label: {
    ...typography.label,
  },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
    minHeight: layout.minimumTouchTarget,
  },
  textArea: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
    minHeight: 80,
    textAlignVertical: 'top',
  },
  readonlyCard: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    gap: spacing.xs,
  },
  readonlyValue: {
    ...typography.label,
  },
  readonlyHint: {
    ...typography.caption,
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  button: {
    borderRadius: radius.md,
    minHeight: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  secondaryButton: {
    backgroundColor: 'transparent',
  },
  secondaryButtonText: {
    ...typography.label,
  },
  primaryButton: {},
  primaryButtonText: {
    ...typography.label,
    color: '#ffffff',
  },
});
