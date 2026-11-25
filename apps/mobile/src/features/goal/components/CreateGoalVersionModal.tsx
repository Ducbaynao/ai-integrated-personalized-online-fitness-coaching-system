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
import {
  GOAL_TYPE_OPTIONS,
  resolveGoalModalErrorMessage,
  useGoalModalSessionGuard,
} from './CreateGoalModal';

interface CreateGoalVersionModalProps {
  visible: boolean;
  currentTitle: string;
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
  isSubmitting,
  isDark,
  sessionId = 0,
  onClose,
  onSubmit,
}: CreateGoalVersionModalProps) {
  const [title, setTitle] = useState(currentTitle);
  const [changeReason, setChangeReason] = useState('');
  const [changeSummary, setChangeSummary] = useState('');
  const [selectedGoalType, setSelectedGoalType] = useState('FAT_LOSS');
  const [durationDays, setDurationDays] = useState('');

  const themeColors = getSemanticColors(isDark);

  const resetFields = () => {
    setTitle(currentTitle);
    setChangeReason('');
    setChangeSummary('');
    setSelectedGoalType('FAT_LOSS');
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
        goalTypeCode: selectedGoalType,
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

            {validationError && (
              <View
                testID="goal-version-error-banner"
                style={[
                  styles.errorBanner,
                  { backgroundColor: themeColors.dangerSurface, borderColor: themeColors.dangerText },
                ]}>
                <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                  {validationError}
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

            {/* Objective Type Selection */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Loại mục tiêu
              </Text>
              <View style={styles.typeOptionsContainer}>
                {GOAL_TYPE_OPTIONS.map((opt) => {
                  const isSelected = selectedGoalType === opt.code;
                  return (
                    <Pressable
                      key={opt.code}
                      accessibilityRole="button"
                      onPress={() => {
                        setSelectedGoalType(opt.code);
                        if (validationError) setValidationError(null);
                      }}
                      style={[
                        styles.typeChip,
                        {
                          backgroundColor: isSelected
                            ? themeColors.primary
                            : themeColors.surfaceSubtle,
                          borderColor: isSelected
                            ? themeColors.primary
                            : themeColors.border,
                        },
                      ]}>
                      <Text
                        style={[
                          styles.typeChipText,
                          {
                            color: isSelected ? '#ffffff' : themeColors.textPrimary,
                            fontWeight: isSelected ? '700' : '400',
                          },
                        ]}>
                        {opt.label}
                      </Text>
                    </Pressable>
                  );
                })}
              </View>
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
                disabled={isBusy}
                onPress={handleSubmit}
                style={[
                  styles.button,
                  styles.primaryButton,
                  {
                    backgroundColor: themeColors.primary,
                    opacity: isBusy ? 0.7 : 1,
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
  typeOptionsContainer: {
    gap: spacing.xs,
  },
  typeChip: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
    borderWidth: 1,
  },
  typeChipText: {
    ...typography.bodySmall,
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
