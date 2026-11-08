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

interface CreateGoalVersionModalProps {
  visible: boolean;
  currentTitle: string;
  isSubmitting: boolean;
  isDark: boolean;
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
  onClose,
  onSubmit,
}: CreateGoalVersionModalProps) {
  const [title, setTitle] = useState(currentTitle);
  const [changeReason, setChangeReason] = useState('');
  const [changeSummary, setChangeSummary] = useState('');
  const [selectedGoalType, setSelectedGoalType] = useState('FAT_LOSS');
  const [durationDays, setDurationDays] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const themeColors = getSemanticColors(isDark);

  const handleClose = () => {
    if (isSubmitting) return;
    setTitle(currentTitle);
    setChangeReason('');
    setChangeSummary('');
    setDurationDays('');
    setValidationError(null);
    onClose();
  };

  const handleSubmit = async () => {
    const trimmedReason = changeReason.trim();
    if (!trimmedReason) {
      setValidationError('Change reason is required to create a new goal version.');
      return;
    }
    if (trimmedReason.length > 100) {
      setValidationError('Change reason must not exceed 100 characters.');
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
        title: title.trim() || undefined,
        changeReason: trimmedReason,
        changeSummary: changeSummary.trim() || undefined,
        durationDays: parsedDuration,
        goalTypeCode: selectedGoalType,
      });
      handleClose();
    } catch {
      // Error handled by parent
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
              Adjust Goal (New Version)
            </Text>
            <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
              Create a new version to adjust your targets or timeline while preserving complete
              earlier history.
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
                Change Reason * (Max 100 chars)
              </Text>
              <TextInput
                testID="goal-version-reason-input"
                accessibilityLabel="Change reason"
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
                placeholder="e.g. Schedule adjustment, progressed faster than expected"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={100}
                editable={!isSubmitting}
              />
            </View>

            {/* Change Summary Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Change Summary / Context (Optional)
              </Text>
              <TextInput
                testID="goal-version-summary-input"
                accessibilityLabel="Change summary"
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
                placeholder="Describe what adjustments were made in this version..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={3}
                maxLength={2000}
                editable={!isSubmitting}
              />
            </View>

            {/* Updated Title Input */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Goal Title
              </Text>
              <TextInput
                testID="goal-version-title-input"
                accessibilityLabel="Updated goal title"
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
                placeholder="Goal title"
                placeholderTextColor={themeColors.textSecondary}
                maxLength={200}
                editable={!isSubmitting}
              />
            </View>

            {/* Objective Type Selection */}
            <View style={styles.fieldGroup}>
              <Text style={[styles.label, { color: themeColors.textPrimary }]}>
                Objective Type
              </Text>
              <View style={styles.typeOptionsContainer}>
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
                Adjusted Duration (Days)
              </Text>
              <TextInput
                testID="goal-version-duration-input"
                accessibilityLabel="Adjusted duration in days"
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
                editable={!isSubmitting}
              />
            </View>

            {/* Actions */}
            <View style={styles.actions}>
              <Pressable
                testID="goal-version-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Cancel goal adjustment"
                disabled={isSubmitting}
                onPress={handleClose}
                style={[styles.button, styles.secondaryButton]}>
                <Text style={[styles.secondaryButtonText, { color: themeColors.textSecondary }]}>
                  Cancel
                </Text>
              </Pressable>

              <Pressable
                testID="goal-version-submit-button"
                accessibilityRole="button"
                accessibilityLabel="Save adjusted version"
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
                  <Text style={styles.primaryButtonText}>Save New Version</Text>
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
