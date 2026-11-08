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
import { GOAL_TYPE_OPTIONS } from './CreateGoalModal';

interface CreateGoalTransitionModalProps {
  visible: boolean;
  isSubmitting: boolean;
  isDark: boolean;
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
  onClose,
  onSubmit,
}: CreateGoalTransitionModalProps) {
  const [transitionReason, setTransitionReason] = useState('');
  const [title, setTitle] = useState('');
  const [selectedGoalType, setSelectedGoalType] = useState('MUSCLE_GAIN');
  const [durationDays, setDurationDays] = useState('90');
  const [notes, setNotes] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const themeColors = getSemanticColors(isDark);

  const handleClose = () => {
    if (isSubmitting) return;
    setTransitionReason('');
    setTitle('');
    setSelectedGoalType('MUSCLE_GAIN');
    setDurationDays('90');
    setNotes('');
    setValidationError(null);
    onClose();
  };

  const handleSubmit = async () => {
    const trimmedReason = transitionReason.trim();
    if (!trimmedReason) {
      setValidationError('Transition reason is required to start a new journey.');
      return;
    }
    if (trimmedReason.length > 100) {
      setValidationError('Transition reason must not exceed 100 characters.');
      return;
    }

    const trimmedTitle = title.trim();
    if (!trimmedTitle) {
      setValidationError('New goal title is required.');
      return;
    }
    if (trimmedTitle.length > 200) {
      setValidationError('Goal title must not exceed 200 characters.');
      return;
    }

    let parsedDuration: number | undefined;
    if (durationDays.trim()) {
      const num = parseInt(durationDays.trim(), 10);
      if (isNaN(num) || num <= 0) {
        setValidationError('Duration must be a positive number of days.');
        return;
      }
      parsedDuration = num;
    }

    setValidationError(null);
    try {
      await onSubmit({
        transitionReason: trimmedReason,
        notes: notes.trim() || undefined,
        title: trimmedTitle,
        goalTypeCode: selectedGoalType,
        durationDays: parsedDuration,
      });
      handleClose();
    } catch {
      // Error surfaced by parent
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
              Transition to New Journey
            </Text>
            <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
              Conclude your current goal journey and begin a fresh journey from Day 1. Complete
              historical progress is preserved.
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
                Transition Reason * (Max 100 chars)
              </Text>
              <TextInput
                testID="goal-transition-reason-input"
                accessibilityLabel="Transition reason"
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
                placeholder="e.g. Completed cut, starting lean bulking journey"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={100}
                editable={!isSubmitting}
              />
            </View>

            {/* New Goal Title (Mandatory) */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                New Goal Title *
              </Text>
              <TextInput
                testID="goal-transition-title-input"
                accessibilityLabel="New goal title"
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
                placeholder="e.g. 16-Week Muscle Hypertrophy Phase"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={200}
                editable={!isSubmitting}
              />
            </View>

            {/* New Goal Type */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                New Objective Type *
              </Text>
              <View style={styles.typeOptionsContainer} testID="goal-transition-type-select">
                {GOAL_TYPE_OPTIONS.map((opt) => {
                  const isSelected = selectedGoalType === opt.code;
                  return (
                    <Pressable
                      key={opt.code}
                      accessibilityRole="button"
                      onPress={() => setSelectedGoalType(opt.code)}
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
                Duration (Days)
              </Text>
              <TextInput
                testID="goal-transition-duration-input"
                accessibilityLabel="New goal duration in days"
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
                editable={!isSubmitting}
              />
            </View>

            {/* Notes Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Transition Notes (Optional)
              </Text>
              <TextInput
                testID="goal-transition-notes-input"
                accessibilityLabel="Transition notes"
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
                placeholder="Notes on the transition, previous accomplishments..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={3}
                maxLength={2000}
                editable={!isSubmitting}
              />
            </View>

            {/* Actions */}
            <View style={styles.actions}>
              <Pressable
                testID="goal-transition-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Cancel transition"
                disabled={isSubmitting}
                onPress={handleClose}
                style={[styles.button, styles.secondaryButton]}>
                <Text style={[styles.secondaryButtonText, { color: themeColors.textSecondary }]}>
                  Cancel
                </Text>
              </Pressable>

              <Pressable
                testID="goal-transition-submit-button"
                accessibilityRole="button"
                accessibilityLabel="Submit transition"
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
                  <Text style={styles.primaryButtonText}>Start New Journey</Text>
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
