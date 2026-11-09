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
import { MetricDefinition, RecordMeasurementRequest } from '@/types/measurement';

interface AddMeasurementModalProps {
  visible: boolean;
  metrics: MetricDefinition[];
  isSubmitting: boolean;
  isDark: boolean;
  onClose: () => void;
  onSubmit: (request: RecordMeasurementRequest) => Promise<void>;
}

export function AddMeasurementModal({
  visible,
  metrics,
  isSubmitting,
  isDark,
  onClose,
  onSubmit,
}: AddMeasurementModalProps) {
  const [selectedMetricCode, setSelectedMetricCode] = useState(metrics[0]?.code || 'WEIGHT');
  const [numericValue, setNumericValue] = useState('');
  const [textValue, setTextValue] = useState('');
  const [unitCode, setUnitCode] = useState('');
  const [notes, setNotes] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const themeColors = getSemanticColors(isDark);
  const activeMetric = metrics.find(m => m.code === selectedMetricCode) || metrics[0];

  const handleMetricSelect = (code: string) => {
    setSelectedMetricCode(code);
    const m = metrics.find(item => item.code === code);
    if (m?.defaultUnitCode) {
      setUnitCode(m.defaultUnitCode);
    }
  };

  const handleSubmit = async () => {
    setValidationError(null);

    if (!activeMetric) {
      setValidationError('Vui lòng chọn chỉ số đo');
      return;
    }

    let parsedNumeric: number | undefined = undefined;
    if (activeMetric.valueType === 'NUMERIC') {
      if (!numericValue.trim()) {
        setValidationError('Vui lòng nhập giá trị số');
        return;
      }
      const val = parseFloat(numericValue.trim());
      if (isNaN(val)) {
        setValidationError('Giá trị số không hợp lệ');
        return;
      }
      parsedNumeric = val;
    } else if (activeMetric.valueType === 'TEXT') {
      if (!textValue.trim()) {
        setValidationError('Vui lòng nhập giá trị chữ');
        return;
      }
    }

    try {
      await onSubmit({
        metricCode: activeMetric.code,
        numericValue: parsedNumeric,
        textValue: activeMetric.valueType === 'TEXT' ? textValue.trim() : undefined,
        unitCode: unitCode.trim() || activeMetric.defaultUnitCode || undefined,
        measuredAt: new Date().toISOString(),
        notes: notes.trim() || undefined,
      });

      setNumericValue('');
      setTextValue('');
      setNotes('');
      onClose();
    } catch (err: any) {
      setValidationError(err?.message || 'Không thể lưu số đo');
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
          <Text style={[styles.title, { color: themeColors.textPrimary }]}>Ghi nhận số đo mới</Text>

          <ScrollView style={styles.scroll}>
            <Text style={[styles.label, { color: themeColors.textSecondary }]}>Chỉ số:</Text>
            <ScrollView horizontal showsHorizontalScrollIndicator={false} style={styles.chipRow}>
              {metrics.map(m => {
                const isSelected = m.code === (activeMetric?.code || selectedMetricCode);
                return (
                  <Pressable
                    key={m.code}
                    testID={`metric-chip-${m.code}`}
                    onPress={() => handleMetricSelect(m.code)}
                    style={[
                      styles.chip,
                      {
                        backgroundColor: isSelected ? themeColors.primary : themeColors.surfaceSubtle,
                        borderColor: isSelected ? themeColors.primary : themeColors.border,
                      },
                    ]}>
                    <Text
                      style={[
                        styles.chipText,
                        { color: isSelected ? '#FFFFFF' : themeColors.textPrimary },
                      ]}>
                      {m.displayName}
                    </Text>
                  </Pressable>
                );
              })}
            </ScrollView>

            {activeMetric?.valueType === 'NUMERIC' ? (
              <View style={styles.inputGroup}>
                <Text style={[styles.label, { color: themeColors.textSecondary }]}>
                  Giá trị ({unitCode || activeMetric.defaultUnitCode || 'đơn vị'}):
                </Text>
                <TextInput
                  testID="input-numeric-value"
                  style={[styles.input, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                  placeholder="Ví dụ: 75.5"
                  placeholderTextColor={themeColors.textSecondary}
                  keyboardType="numeric"
                  value={numericValue}
                  onChangeText={setNumericValue}
                />
              </View>
            ) : (
              <View style={styles.inputGroup}>
                <Text style={[styles.label, { color: themeColors.textSecondary }]}>Nội dung:</Text>
                <TextInput
                  testID="input-text-value"
                  style={[styles.input, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                  placeholder="Nhập giá trị"
                  placeholderTextColor={themeColors.textSecondary}
                  value={textValue}
                  onChangeText={setTextValue}
                />
              </View>
            )}

            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: themeColors.textSecondary }]}>Ghi chú (tuỳ chọn):</Text>
              <TextInput
                testID="input-notes"
                style={[styles.input, styles.textArea, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                placeholder="Ghi chú buổi cân/đo..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={3}
                value={notes}
                onChangeText={setNotes}
              />
            </View>

            {validationError && (
              <Text testID="modal-error-text" style={[styles.errorText, { color: themeColors.dangerText }]}>
                {validationError}
              </Text>
            )}
          </ScrollView>

          <View style={styles.buttonRow}>
            <Pressable
              testID="btn-cancel-add"
              onPress={onClose}
              disabled={isSubmitting}
              style={[styles.btn, styles.btnCancel, { borderColor: themeColors.border }]}>
              <Text style={[styles.btnText, { color: themeColors.textPrimary }]}>Hủy</Text>
            </Pressable>

            <Pressable
              testID="btn-submit-add"
              onPress={handleSubmit}
              disabled={isSubmitting}
              style={[styles.btn, styles.btnSubmit, { backgroundColor: themeColors.primary }]}>
              {isSubmitting ? (
                <ActivityIndicator size="small" color="#FFFFFF" />
              ) : (
                <Text style={[styles.btnText, { color: '#FFFFFF' }]}>Lưu số đo</Text>
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
  label: {
    ...typography.label,
    fontWeight: '600',
    marginBottom: spacing.xs,
  },
  chipRow: {
    flexDirection: 'row',
    marginBottom: spacing.md,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.full,
    borderWidth: 1,
    marginRight: spacing.sm,
  },
  chipText: {
    ...typography.label,
    fontWeight: '600',
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
    minHeight: 70,
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
