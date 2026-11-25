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

interface CreateGoalTransitionModalProps {
  visible: boolean;
  isSubmitting: boolean;
  isDark: boolean;
  sessionId?: number;
  onClose: () => void;
  onSubmit: (payload: {
    transitionReason: string;
    notes?: string;
    title: string;
    goalTypeCode: string;
    durationDays?: number;
  }) => Promise<void>;
}

export function CreateGoalTransitionModal({
  visible,
  isSubmitting,
  isDark,
  sessionId = 0,
  onClose,
  onSubmit,
}: CreateGoalTransitionModalProps) {
  const [transitionReason, setTransitionReason] = useState('');
  const [title, setTitle] = useState('');
  const [selectedGoalType, setSelectedGoalType] = useState('MUSCLE_GAIN');
  const [durationDays, setDurationDays] = useState('90');
  const [notes, setNotes] = useState('');

  const themeColors = getSemanticColors(isDark);

  const resetFields = () => {
    setTransitionReason('');
    setTitle('');
    setSelectedGoalType('MUSCLE_GAIN');
    setDurationDays('90');
    setNotes('');
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
    const trimmedReason = transitionReason.trim();
    if (!trimmedReason) {
      setValidationError('Vui lòng nêu lý do bắt đầu hành trình mục tiêu mới.');
      return;
    }
    if (trimmedReason.length > 100) {
      setValidationError('Lý do chuyển mục tiêu không được quá 100 ký tự.');
      return;
    }

    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      setValidationError('Vui lòng nhập tên mục tiêu mới.');
      return;
    }
    if (trimmedTitle.length > 200) {
      setValidationError('Tên mục tiêu không được quá 200 ký tự.');
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
        transitionReason: trimmedReason,
        notes: notes.trim() || undefined,
        title: trimmedTitle,
        goalTypeCode: selectedGoalType,
        durationDays: parsedDuration,
      });
      if (guard.isCurrent()) {
        performClose();
      }
    } catch (err) {
      if (guard.isCurrent()) {
        setValidationError(
          resolveGoalModalErrorMessage(err, 'Không thể chuyển sang hành trình mục tiêu mới.')
        );
      }
    } finally {
      finishRequest(guard);
    }
  };

  return (
    <Modal
      testID="create-goal-transition-modal"
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
              Chuyển sang hành trình mới
            </Text>
            <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
              Kết thúc mục tiêu hiện tại và bắt đầu mục tiêu mới từ ngày đầu tiên. Toàn bộ lịch sử tiến độ vẫn được giữ nguyên.
            </Text>

            {validationError && (
              <View
                testID="goal-transition-error-banner"
                style={[
                  styles.errorBanner,
                  { backgroundColor: themeColors.dangerSurface, borderColor: themeColors.dangerText },
                ]}>
                <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                  {validationError}
                </Text>
              </View>
            )}

            {/* Transition Reason (Mandatory) */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Lý do chuyển đổi * (Tối đa 100 ký tự)
              </Text>
              <TextInput
                testID="goal-transition-reason-input"
                accessibilityLabel="Lý do chuyển đổi"
                style={[
                  styles.input,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={transitionReason}
                onChangeText={(val) => {
                  setTransitionReason(val);
                  if (validationError) setValidationError(null);
                }}
                placeholder="Ví dụ: Hoàn thành giai đoạn giảm mỡ, bắt đầu tăng cơ nạc"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={100}
                editable={!isBusy}
              />
            </View>

            {/* New Goal Title (Mandatory) */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Tiêu đề mục tiêu mới *
              </Text>
              <TextInput
                testID="goal-transition-title-input"
                accessibilityLabel="Tiêu đề mục tiêu mới"
                style={[
                  styles.input,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={title}
                onChangeText={(val) => {
                  setTitle(val);
                  if (validationError) setValidationError(null);
                }}
                placeholder="Ví dụ: Giai đoạn tăng cơ 16 tuần"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={200}
                editable={!isBusy}
              />
            </View>

            {/* New Goal Type */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Loại mục tiêu mới *
              </Text>
              <View style={styles.typeOptionsContainer} testID="goal-transition-type-select">
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
                Thời hạn (ngày)
              </Text>
              <TextInput
                testID="goal-transition-duration-input"
                accessibilityLabel="Thời lượng mục tiêu mới tính bằng ngày"
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
                placeholder="90"
                placeholderTextColor={themeColors.textSecondary}
                keyboardType="numeric"
                editable={!isBusy}
              />
            </View>

            {/* Notes Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Ghi chú chuyển tiếp (Tùy chọn)
              </Text>
              <TextInput
                testID="goal-transition-notes-input"
                accessibilityLabel="Ghi chú chuyển tiếp"
                style={[
                  styles.textArea,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={notes}
                onChangeText={setNotes}
                placeholder="Ghi chú về quá trình chuyển đổi, thành tích trước đó..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={3}
                maxLength={2000}
                editable={!isBusy}
              />
            </View>

            {/* Actions */}
            <View style={styles.actions}>
              <Pressable
                testID="goal-transition-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Hủy chuyển đổi"
                disabled={isBusy}
                onPress={handleClose}
                style={[styles.button, styles.secondaryButton]}>
                <Text style={[styles.secondaryButtonText, { color: themeColors.textSecondary }]}>
                  Hủy
                </Text>
              </Pressable>

              <Pressable
                testID="goal-transition-submit-button"
                accessibilityRole="button"
                accessibilityLabel="Gửi chuyển đổi"
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
                  <Text style={styles.primaryButtonText}>Bắt đầu hành trình mới</Text>
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
