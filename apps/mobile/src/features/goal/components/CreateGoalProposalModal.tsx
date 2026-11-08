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

export const GOAL_TYPE_OPTIONS = [
  { code: 'FAT_LOSS', label: 'Fat Loss (Giảm mỡ)' },
  { code: 'MUSCLE_GAIN', label: 'Muscle Gain (Tăng cơ)' },
  { code: 'STRENGTH', label: 'Strength (Sức mạnh)' },
  { code: 'FITNESS_IMPROVEMENT', label: 'Fitness Improvement (Thể lực)' },
  { code: 'MAINTENANCE', label: 'Maintenance (Duy trì)' },
];

interface CreateGoalProposalModalProps {
  visible: boolean;
  isSubmitting: boolean;
  isDark: boolean;
  currentTitle?: string;
  onClose: () => void;
  onSubmit: (payload: {
    proposedTitle?: string;
    proposedDurationDays?: number;
    reason: string;
    goalTypeCode: string;
  }) => Promise<void>;
}

export function CreateGoalProposalModal({
  visible,
  isSubmitting,
  isDark,
  currentTitle = '',
  onClose,
  onSubmit,
}: CreateGoalProposalModalProps) {
  const themeColors = getSemanticColors(isDark);
  const [proposedTitle, setProposedTitle] = useState(currentTitle);
  const [proposedDurationDays, setProposedDurationDays] = useState('60');
  const [reason, setReason] = useState('');
  const [selectedGoalType, setSelectedGoalType] = useState('MUSCLE_GAIN');
  const [validationError, setValidationError] = useState<string | null>(null);

  const handleClose = () => {
    if (isSubmitting) return;
    setValidationError(null);
    onClose();
  };

  const handleSubmit = async () => {
    const trimmedReason = reason.trim();
    if (!trimmedReason) {
      setValidationError('Proposal reason is required explaining the coaching adjustment.');
      return;
    }
    if (trimmedReason.length > 500) {
      setValidationError('Reason must not exceed 500 characters.');
      return;
    }

    let parsedDuration: number | undefined;
    if (proposedDurationDays.trim()) {
      const num = parseInt(proposedDurationDays.trim(), 10);
      if (isNaN(num) || num <= 0) {
        setValidationError('Duration must be a positive number of days.');
        return;
      }
      parsedDuration = num;
    }

    setValidationError(null);
    try {
      await onSubmit({
        proposedTitle: proposedTitle.trim() || undefined,
        proposedDurationDays: parsedDuration,
        reason: trimmedReason,
        goalTypeCode: selectedGoalType,
      });
      handleClose();
    } catch {
      // Error surfaced by parent screen
    }
  };

  return (
    <Modal
      testID="create-goal-proposal-modal"
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
              Propose Goal Modification
            </Text>
            <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
              Propose target adjustments for your student. Changes become active only upon student acceptance.
            </Text>

            {validationError && (
              <View
                testID="goal-proposal-error-banner"
                style={[
                  styles.errorBanner,
                  { backgroundColor: themeColors.dangerSurface, borderColor: themeColors.dangerText },
                ]}>
                <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                  {validationError}
                </Text>
              </View>
            )}

            {/* Coaching Reason Input (Mandatory) */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Coaching Reason * (Max 500 chars)
              </Text>
              <TextInput
                testID="goal-proposal-reason-input"
                accessibilityLabel="Proposal coaching reason"
                style={[
                  styles.textArea,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={reason}
                onChangeText={(val) => {
                  setReason(val);
                  if (validationError) setValidationError(null);
                }}
                placeholder="e.g. Accelerating hypertrophy progression based on rapid strength adaptation"
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={3}
                maxLength={500}
                editable={!isSubmitting}
              />
            </View>

            {/* Proposed Title Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Proposed Goal Title
              </Text>
              <TextInput
                testID="goal-proposal-title-input"
                accessibilityLabel="Proposed goal title"
                style={[
                  styles.input,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={proposedTitle}
                onChangeText={setProposedTitle}
                placeholder="e.g. Advanced Hypertrophy Phase 2"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={200}
                editable={!isSubmitting}
              />
            </View>

            {/* Goal Type Chips */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Primary Focus
              </Text>
              <View style={styles.chipRow}>
                {GOAL_TYPE_OPTIONS.map((opt) => {
                  const isSelected = selectedGoalType === opt.code;
                  return (
                    <Pressable
                      key={opt.code}
                      testID={`goal-type-chip-${opt.code}`}
                      accessibilityRole="button"
                      accessibilityLabel={`Select ${opt.label}`}
                      style={[
                        styles.chip,
                        {
                          borderColor: isSelected ? themeColors.primary : themeColors.border,
                          backgroundColor: isSelected ? themeColors.primary : themeColors.surfaceSubtle,
                        },
                      ]}
                      onPress={() => !isSubmitting && setSelectedGoalType(opt.code)}>
                      <Text
                        style={[
                          styles.chipText,
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

            {/* Proposed Duration */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Proposed Duration (Days)
              </Text>
              <TextInput
                testID="goal-proposal-duration-input"
                accessibilityLabel="Proposed duration in days"
                style={[
                  styles.input,
                  {
                    borderColor: themeColors.border,
                    backgroundColor: themeColors.canvas,
                    color: themeColors.textPrimary,
                  },
                ]}
                value={proposedDurationDays}
                onChangeText={setProposedDurationDays}
                placeholder="60"
                placeholderTextColor={themeColors.textSecondary}
                keyboardType="numeric"
                editable={!isSubmitting}
              />
            </View>

            {/* Actions */}
            <View style={styles.actions}>
              <Pressable
                testID="goal-proposal-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Cancel proposal"
                disabled={isSubmitting}
                onPress={handleClose}
                style={[styles.button, styles.secondaryButton]}>
                <Text style={[styles.secondaryButtonText, { color: themeColors.textSecondary }]}>
                  Cancel
                </Text>
              </Pressable>

              <Pressable
                testID="goal-proposal-submit-button"
                accessibilityRole="button"
                accessibilityLabel="Submit proposal"
                disabled={isSubmitting}
                onPress={handleSubmit}
                style={[
                  styles.button,
                  styles.primaryButton,
                  {
                    backgroundColor: themeColors.primary,
                    opacity: isSubmitting ? 0.7 : 1,
                  },
                ]}>
                {isSubmitting ? (
                  <ActivityIndicator size="small" color="#ffffff" />
                ) : (
                  <Text style={styles.primaryButtonText}>Send Proposal</Text>
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
    backgroundColor: '#00000080',
    justifyContent: 'center',
    padding: spacing.lg,
  },
  dialog: {
    maxHeight: '90%',
    borderRadius: radius.lg,
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
    fontWeight: '600',
  },
  fieldGroup: {
    marginBottom: spacing.md,
  },
  label: {
    ...typography.label,
    marginBottom: spacing.xs,
  },
  input: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    ...typography.body,
  },
  textArea: {
    minHeight: 80,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
    textAlignVertical: 'top',
  },
  chipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  chip: {
    minHeight: layout.minimumTouchTarget,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: radius.md,
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  chipText: {
    ...typography.caption,
  },
  actions: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: spacing.sm,
    marginTop: spacing.md,
  },
  button: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    paddingHorizontal: spacing.lg,
    justifyContent: 'center',
    alignItems: 'center',
  },
  primaryButton: {},
  primaryButtonText: {
    ...typography.label,
    fontWeight: '700',
    color: '#ffffff',
  },
  secondaryButton: {},
  secondaryButtonText: {
    ...typography.label,
    fontWeight: '600',
  },
});
