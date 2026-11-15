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
  { code: 'FAT_LOSS', label: 'Giảm mỡ' },
  { code: 'MUSCLE_GAIN', label: 'Tăng cơ' },
  { code: 'STRENGTH', label: 'Tăng sức mạnh' },
  { code: 'FITNESS_IMPROVEMENT', label: 'Cải thiện thể lực' },
  { code: 'MAINTENANCE', label: 'Duy trì thể lực' },
];

interface CreateGoalModalProps {
  visible: boolean;
  isSubmitting: boolean;
  isDark: boolean;
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
  onClose,
  onSubmit,
}: CreateGoalModalProps) {
  const [title, setTitle] = useState('');
  const [selectedGoalType, setSelectedGoalType] = useState('FAT_LOSS');
  const [durationDays, setDurationDays] = useState('90');
  const [notes, setNotes] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const themeColors = getSemanticColors(isDark);

  const handleClose = () => {
    if (isSubmitting) return;
    setTitle('');
    setSelectedGoalType('FAT_LOSS');
    setDurationDays('90');
    setNotes('');
    setValidationError(null);
    onClose();
  };

  const handleSubmit = async () => {
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

    setValidationError(null);
    try {
      await onSubmit({
        title: trimmedTitle,
        goalTypeCode: selectedGoalType,
        durationDays: parsedDuration,
        notes: notes.trim() || undefined,
      });
      handleClose();
    } catch {
      // Error is caught and surfaced by parent
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
                editable={!isSubmitting}
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
                editable={!isSubmitting}
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
                editable={!isSubmitting}
              />
            </View>

            {/* Actions */}
            <View style={styles.actions}>
              <Pressable
                testID="create-goal-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Hủy việc tạo mục tiêu"
                disabled={isSubmitting}
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
