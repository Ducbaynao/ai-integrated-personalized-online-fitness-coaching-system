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
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { CorrectMeasurementRequest, Measurement } from '@/types/measurement';

interface CorrectMeasurementModalProps {
  visible: boolean;
  measurement: Measurement | null;
  isSubmitting: boolean;
  isDark: boolean;
  onClose: () => void;
  onSubmit: (id: string, request: CorrectMeasurementRequest) => Promise<void>;
}

export function CorrectMeasurementModal({
  visible,
  measurement,
  isSubmitting,
  isDark,
  onClose,
  onSubmit,
}: CorrectMeasurementModalProps) {
  const [numericValue, setNumericValue] = useState(
    measurement?.numericValue !== undefined && measurement?.numericValue !== null
      ? measurement.numericValue.toString()
      : ''
  );
  const [textValue, setTextValue] = useState(measurement?.textValue || '');
  const [reason, setReason] = useState('');
  const [notes, setNotes] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const themeColors = getSemanticColors(isDark);

  if (!measurement) return null;

  const handleSubmit = async () => {
    setValidationError(null);

    if (!reason.trim()) {
      setValidationError('Vui lòng nhập lý do điều chỉnh số đo (bắt buộc)');
      return;
    }

    let parsedNumeric: number | undefined = undefined;
    if (measurement.numericValue !== undefined && measurement.numericValue !== null) {
      if (!numericValue.trim()) {
        setValidationError('Vui lòng nhập giá trị số điều chỉnh');
        return;
      }
      const val = parseFloat(numericValue.trim());
      if (isNaN(val)) {
        setValidationError('Giá trị số không hợp lệ');
        return;
      }
      parsedNumeric = val;
    }

    try {
      await onSubmit(measurement.id, {
        numericValue: parsedNumeric,
        textValue: textValue.trim() || undefined,
        reason: reason.trim(),
        notes: notes.trim() || undefined,
      });

      onClose();
    } catch (err: any) {
      setValidationError(err?.message || 'Không thể điều chỉnh số đo');
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
          <Text style={[styles.title, { color: themeColors.textPrimary }]}>Điều chỉnh số đo</Text>

          <ScrollView style={styles.scroll}>
            <View style={[styles.infoBox, { backgroundColor: themeColors.surfaceSubtle, borderColor: themeColors.border }]}>
              <Text style={[styles.infoLabel, { color: themeColors.textSecondary }]}>
                Chỉ số: <Text style={{ color: themeColors.textPrimary, fontWeight: '700' }}>{measurement.metricDisplayName}</Text>
              </Text>
              <Text style={[styles.infoLabel, { color: themeColors.textSecondary }]}>
                Giá trị ban đầu:{' '}
                <Text style={{ color: themeColors.textPrimary, fontWeight: '700' }}>
                  {measurement.numericValue !== null && measurement.numericValue !== undefined
                    ? `${measurement.numericValue} ${measurement.unitSymbol || measurement.unitCode || ''}`
                    : measurement.textValue || '—'}
                </Text>
              </Text>
              <Text style={[styles.infoLabel, { color: themeColors.textSecondary }]}>
                Thời điểm đo: {new Date(measurement.measuredAt).toLocaleString('vi-VN')}
              </Text>
            </View>

            {measurement.numericValue !== null && measurement.numericValue !== undefined && (
              <View style={styles.inputGroup}>
                <Text style={[styles.label, { color: themeColors.textSecondary }]}>
                  Giá trị mới ({measurement.unitSymbol || measurement.unitCode || ''}):
                </Text>
                <TextInput
                  testID="input-correct-numeric-value"
                  style={[styles.input, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                  keyboardType="numeric"
                  value={numericValue}
                  onChangeText={setNumericValue}
                />
              </View>
            )}

            {measurement.textValue !== null && measurement.textValue !== undefined && (
              <View style={styles.inputGroup}>
                <Text style={[styles.label, { color: themeColors.textSecondary }]}>Nội dung mới:</Text>
                <TextInput
                  testID="input-correct-text-value"
                  style={[styles.input, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                  value={textValue}
                  onChangeText={setTextValue}
                />
              </View>
            )}

            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: themeColors.textSecondary }]}>Lý do điều chỉnh (bắt buộc):</Text>
              <TextInput
                testID="input-correct-reason"
                style={[styles.input, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                placeholder="Ví dụ: Nhập nhầm số cân sáng nay..."
                placeholderTextColor={themeColors.textSecondary}
                value={reason}
                onChangeText={setReason}
              />
            </View>

            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: themeColors.textSecondary }]}>Ghi chú thêm:</Text>
              <TextInput
                testID="input-correct-notes"
                style={[styles.input, styles.textArea, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                placeholder="Ghi chú chi tiết..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={2}
                value={notes}
                onChangeText={setNotes}
              />
            </View>

            {validationError && (
              <Text testID="correct-error-text" style={[styles.errorText, { color: themeColors.dangerText }]}>
                {validationError}
              </Text>
            )}
          </ScrollView>

          <View style={styles.buttonRow}>
            <Pressable
              testID="btn-cancel-correct"
              onPress={onClose}
              disabled={isSubmitting}
              style={[styles.btn, styles.btnCancel, { borderColor: themeColors.border }]}>
              <Text style={[styles.btnText, { color: themeColors.textPrimary }]}>Hủy</Text>
            </Pressable>

            <Pressable
              testID="btn-submit-correct"
              onPress={handleSubmit}
              disabled={isSubmitting}
              style={[styles.btn, styles.btnSubmit, { backgroundColor: themeColors.primary }]}>
              {isSubmitting ? (
                <ActivityIndicator size="small" color="#FFFFFF" />
              ) : (
                <Text style={[styles.btnText, { color: '#FFFFFF' }]}>Xác nhận sửa</Text>
              )}
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  overlay: {
    flex: 1,
    backgroundColor: 'rgba(0,0,0,0.5)',
    justifyContent: 'center',
    alignItems: 'center',
    padding: spacing.md,
  },
  card: {
    width: '100%',
    maxHeight: '85%',
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.lg,
  },
  title: {
    ...typography.h2,
    fontWeight: '700',
    marginBottom: spacing.md,
  },
  scroll: {
    marginBottom: spacing.md,
  },
  infoBox: {
    padding: spacing.md,
    borderRadius: radius.md,
    borderWidth: 1,
    marginBottom: spacing.md,
  },
  infoLabel: {
    ...typography.body,
    marginBottom: spacing.xs,
  },
  label: {
    ...typography.label,
    fontWeight: '600',
    marginBottom: spacing.xs,
  },
  inputGroup: {
    marginBottom: spacing.md,
  },
  input: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    ...typography.body,
  },
  textArea: {
    minHeight: 60,
    textAlignVertical: 'top',
  },
  errorText: {
    ...typography.bodySmall,
    fontWeight: '600',
    marginTop: spacing.xs,
  },
  buttonRow: {
    flexDirection: 'row',
    justifyContent: 'flex-end',
    gap: spacing.sm,
  },
  btn: {
    paddingHorizontal: spacing.lg,
    paddingVertical: spacing.md,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    minWidth: 90,
  },
  btnCancel: {
    borderWidth: 1,
  },
  btnSubmit: {},
  btnText: {
    ...typography.label,
    fontWeight: '600',
  },
});
