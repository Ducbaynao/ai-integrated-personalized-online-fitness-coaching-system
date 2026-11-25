import React, { useEffect, useRef, useState } from 'react';
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
import { apiErrorMessage } from '@/localization/displayLabels';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';

export const GOAL_TYPE_OPTIONS = [
  { code: 'FAT_LOSS', label: 'Giảm mỡ' },
  { code: 'MUSCLE_GAIN', label: 'Tăng cơ' },
  { code: 'STRENGTH', label: 'Tăng sức mạnh' },
  { code: 'FITNESS_IMPROVEMENT', label: 'Cải thiện thể lực' },
  { code: 'MAINTENANCE', label: 'Duy trì thể lực' },
];

/** Map goal mutation rejections into safe, actionable Vietnamese inline feedback for modal forms. */
export function resolveGoalModalErrorMessage(error: unknown, fallback: string): string {
  if (error instanceof Error && error.name === 'AbortError') {
    return 'Thao tác đã được hủy.';
  }
  if (error instanceof Error && error.name === 'TimeoutError') {
    return 'Yêu cầu quá thời gian chờ. Vui lòng thử lại.';
  }

  if (error && typeof error === 'object' && 'status' in error) {
    const status = (error as { status?: unknown }).status;
    const errorResponse =
      'errorResponse' in error
        ? (error as {
            errorResponse?: {
              errorCode?: unknown;
              message?: unknown;
              fieldErrors?: { field?: string; code?: string; message?: string }[];
            };
          }).errorResponse
        : undefined;
    const errorCode =
      typeof errorResponse?.errorCode === 'string' ? errorResponse.errorCode : undefined;

    if (
      status === 401 ||
      status === 403 ||
      errorCode === 'UNAUTHORIZED' ||
      errorCode === 'FORBIDDEN'
    ) {
      return apiErrorMessage(error, fallback);
    }

    if (status === 408) {
      return 'Yêu cầu quá thời gian chờ. Vui lòng thử lại.';
    }
    if (status === 0) {
      return apiErrorMessage(error, fallback);
    }

    if (status === 409) {
      if (errorCode === 'ACTIVE_FITNESS_GOAL_ALREADY_EXISTS') {
        return 'Bạn đã có một mục tiêu thể chất đang hoạt động. Mỗi lần chỉ được có một mục tiêu đang hoạt động.';
      }
      return apiErrorMessage(error, fallback);
    }

    if (typeof status === 'number' && status >= 500) {
      return 'Hệ thống đang gặp sự cố tạm thời. Vui lòng thử lại sau.';
    }

    if (status === 400 || status === 422) {
      const fieldErrors = Array.isArray(errorResponse?.fieldErrors)
        ? errorResponse.fieldErrors
        : [];
      const isGoalTypeError =
        errorCode === 'INVALID_GOAL_TYPE' ||
        fieldErrors.some(
          (fe) =>
            fe?.field?.toLowerCase().includes('goaltype') ||
            fe?.message?.toLowerCase().includes('goal type')
        ) ||
        (typeof errorResponse?.message === 'string' &&
          errorResponse.message.toLowerCase().includes('goal type'));
      if (isGoalTypeError) {
        return 'Loại mục tiêu hiện chưa sẵn sàng hoặc không hợp lệ trên hệ thống. Vui lòng chọn loại khác hoặc thử lại sau.';
      }
      return apiErrorMessage(error, fallback);
    }

    return apiErrorMessage(error, fallback);
  }

  return fallback;
}

export interface GoalModalRequestGuard {
  isCurrent: () => boolean;
}

/** Isolate async modal submit state across close/reopen, session changes, and unmount. */
export function useGoalModalSessionGuard({
  visible,
  isSubmitting,
  sessionId = 0,
  onResetForm,
}: {
  visible: boolean;
  isSubmitting: boolean;
  sessionId?: number;
  onResetForm: () => void;
}) {
  const [validationError, setValidationError] = useState<string | null>(null);
  const [isLocalSubmitting, setIsLocalSubmitting] = useState(false);

  const mountedRef = useRef(true);
  const sessionGenRef = useRef(0);
  const requestGenRef = useRef(0);

  const [prevKey, setPrevKey] = useState({ visible, sessionId });
  if (prevKey.visible !== visible || prevKey.sessionId !== sessionId) {
    setPrevKey({ visible, sessionId });
    setValidationError(null);
    setIsLocalSubmitting(false);
    onResetForm();
  }

  useEffect(() => {
    mountedRef.current = true;
    sessionGenRef.current += 1;
    requestGenRef.current += 1;
    return () => {
      sessionGenRef.current += 1;
      requestGenRef.current += 1;
    };
  }, [visible, sessionId]);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  const isBusy = isSubmitting || isLocalSubmitting;

  const beginRequest = (): GoalModalRequestGuard => {
    const capturedSession = sessionGenRef.current;
    const capturedRequest = ++requestGenRef.current;
    setValidationError(null);
    setIsLocalSubmitting(true);
    return {
      isCurrent: () =>
        mountedRef.current &&
        sessionGenRef.current === capturedSession &&
        requestGenRef.current === capturedRequest,
    };
  };

  const finishRequest = (guard: GoalModalRequestGuard) => {
    if (guard.isCurrent()) {
      setIsLocalSubmitting(false);
    }
  };

  return {
    validationError,
    setValidationError,
    isBusy,
    beginRequest,
    finishRequest,
    clearLocalSubmitting: () => setIsLocalSubmitting(false),
  };
}

interface CreateGoalModalProps {
  visible: boolean;
  isSubmitting: boolean;
  isDark: boolean;
  sessionId?: number;
  onClose: () => void;
  onSubmit: (payload: {
    title: string;
    goalTypeCode: string;
    durationDays?: number;
    notes?: string;
  }) => Promise<void>;
}

export function CreateGoalModal({
  visible,
  isSubmitting,
  isDark,
  sessionId = 0,
  onClose,
  onSubmit,
}: CreateGoalModalProps) {
  const [title, setTitle] = useState('');
  const [selectedGoalType, setSelectedGoalType] = useState('FAT_LOSS');
  const [durationDays, setDurationDays] = useState('90');
  const [notes, setNotes] = useState('');

  const themeColors = getSemanticColors(isDark);

  const resetFields = () => {
    setTitle('');
    setSelectedGoalType('FAT_LOSS');
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
    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      setValidationError('Vui lòng nhập tên mục tiêu.');
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
        title: trimmedTitle,
        goalTypeCode: selectedGoalType,
        durationDays: parsedDuration,
        notes: notes.trim() || undefined,
      });
      if (guard.isCurrent()) {
        performClose();
      }
    } catch (err) {
      if (guard.isCurrent()) {
        setValidationError(resolveGoalModalErrorMessage(err, 'Không thể tạo mục tiêu thể chất.'));
      }
    } finally {
      finishRequest(guard);
    }
  };

  return (
    <Modal
      testID="create-goal-modal"
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
              Tạo mục tiêu thể chất
            </Text>
            <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
              Thiết lập hành trình tập luyện chính của bạn. Bạn có thể điều chỉnh mục tiêu hoặc chuyển đổi sau này.
            </Text>

            {validationError && (
              <View
                testID="create-goal-error-banner"
                style={[
                  styles.errorBanner,
                  { backgroundColor: themeColors.dangerSurface, borderColor: themeColors.dangerText },
                ]}>
                <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                  {validationError}
                </Text>
              </View>
            )}

            {/* Title Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Tiêu đề mục tiêu *
              </Text>
              <TextInput
                testID="create-goal-title-input"
                accessibilityLabel="Tiêu đề mục tiêu"
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
                placeholder="Ví dụ: Giảm mỡ và tăng sức mạnh trong 12 tuần"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={200}
                editable={!isBusy}
              />
            </View>

            {/* Goal Type Picker */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Loại mục tiêu chính *
              </Text>
              <View style={styles.typeOptionsContainer} testID="create-goal-type-select">
                {GOAL_TYPE_OPTIONS.map((opt) => {
                  const isSelected = selectedGoalType === opt.code;
                  return (
                    <Pressable
                      key={opt.code}
                      accessibilityRole="button"
                      accessibilityLabel={`Chọn ${opt.label}`}
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
                testID="create-goal-duration-input"
                accessibilityLabel="Thời hạn tính bằng ngày"
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
                Ghi chú cá nhân (không bắt buộc)
              </Text>
              <TextInput
                testID="create-goal-notes-input"
                accessibilityLabel="Ghi chú mục tiêu"
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
                placeholder="Động lực cụ thể, điểm chuẩn mục tiêu hoặc lời nhắc nhở..."
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
                testID="create-goal-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Hủy việc tạo mục tiêu"
                disabled={isBusy}
                onPress={handleClose}
                style={[styles.button, styles.secondaryButton]}>
                <Text style={[styles.secondaryButtonText, { color: themeColors.textSecondary }]}>
                  Hủy
                </Text>
              </Pressable>

              <Pressable
                testID="create-goal-submit-button"
                accessibilityRole="button"
                accessibilityLabel="Gửi mục tiêu mới"
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
                  <Text style={styles.primaryButtonText}>Tạo mục tiêu</Text>
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
