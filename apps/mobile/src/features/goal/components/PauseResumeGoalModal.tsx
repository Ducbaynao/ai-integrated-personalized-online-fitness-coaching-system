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
import { typography } from '@/design-system/tokens/typography';

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
  const titleText = isPause ? 'Pause Fitness Goal' : 'Resume Fitness Goal';
  const actionButtonText = isPause ? 'Pause Goal' : 'Resume Goal';

  const trimmedReason = reason.trim();
  const charCount = trimmedReason.length;
  const isTooLong = charCount > 1000;
  const isBlank = charCount === 0;
  const canProceed = !isBlank && !isTooLong && !isSubmitting;

  const handleInitialSubmit = () => {
    if (isBlank) {
      setValidationError('Reason is required to update status.');
      return;
    }
    if (isTooLong) {
      setValidationError('Reason must not exceed 1000 characters.');
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
            {showConfirm ? 'Confirm Action' : titleText}
          </Text>

          <Text style={[styles.goalName, { color: themeColors.textSecondary }]}>
            Goal: {goalTitle}
          </Text>

          {!showConfirm ? (
            <>
              <Text style={[styles.instruction, { color: themeColors.textSecondary }]}>
                {isPause
                  ? 'Pausing will freeze active goal training time without resetting your history or moving your target date. Please state why you are pausing:'
                  : 'Resuming will reactivate your training journey. Historical pause duration remains recorded in your progress timeline. Please state why you are resuming:'}
              </Text>

              <TextInput
                testID="pause-resume-reason-input"
                accessibilityLabel="Reason for status change"
                style={[
                  styles.input,
                  {
                    color: themeColors.textPrimary,
                    borderColor: validationError || isTooLong ? themeColors.dangerText : themeColors.border,
                    backgroundColor: themeColors.surfaceSubtle,
                  },
                ]}
                placeholder="Enter mandatory reason (max 1000 chars)..."
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
                  accessibilityLabel="Cancel"
                  style={[styles.cancelButton, { borderColor: themeColors.border }]}
                  onPress={handleClose}
                  disabled={isSubmitting}>
                  <Text style={[styles.cancelButtonText, { color: themeColors.textSecondary }]}>
                    Cancel
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
                  <Text style={styles.submitButtonText}>Continue</Text>
                </Pressable>
              </View>
            </>
          ) : (
            <>
              <Text style={[styles.confirmText, { color: themeColors.textPrimary }]}>
                {isPause
                  ? 'Are you sure you want to pause this goal? You can resume it anytime when you are ready to train again.'
                  : 'Are you sure you want to resume this goal? Make sure you do not have another active fitness goal running.'}
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
                  Recorded reason:
                </Text>
                <Text style={[styles.reasonValue, { color: themeColors.textPrimary }]}>
                  {`"${trimmedReason}"`}
                </Text>
              </View>

              <View style={styles.buttonRow}>
                <Pressable
                  testID="pause-resume-back-button"
                  accessibilityRole="button"
                  accessibilityLabel="Back to edit reason"
                  style={[styles.cancelButton, { borderColor: themeColors.border }]}
                  onPress={() => setShowConfirm(false)}
                  disabled={isSubmitting}>
                  <Text style={[styles.cancelButtonText, { color: themeColors.textSecondary }]}>
                    Back
                  </Text>
                </Pressable>

                <Pressable
                  testID="pause-resume-confirm-button"
                  accessibilityRole="button"
                  accessibilityLabel={`Confirm ${actionButtonText}`}
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
                    <Text style={styles.submitButtonText}>Confirm & {actionButtonText}</Text>
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
    fontWeight: '600',
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
    fontStyle: 'italic',
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
