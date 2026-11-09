import React, { useState } from 'react';
import {
  ActivityIndicator,
  Image,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
} from 'react-native';
import * as DocumentPicker from 'expo-document-picker';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { PhotoViewType, UploadProgressPhotoParams } from '@/types/measurement';

interface UploadProgressPhotoModalProps {
  visible: boolean;
  isSubmitting: boolean;
  isDark: boolean;
  onClose: () => void;
  onSubmit: (params: UploadProgressPhotoParams) => Promise<void>;
}

export function UploadProgressPhotoModal({
  visible,
  isSubmitting,
  isDark,
  onClose,
  onSubmit,
}: UploadProgressPhotoModalProps) {
  const [viewType, setViewType] = useState<PhotoViewType>('FRONT');
  const [photo, setPhoto] = useState<{ uri: string; name: string; mimeType: string } | null>(null);
  const [isPicking, setIsPicking] = useState(false);
  const [weightAtTime, setWeightAtTime] = useState('');
  const [notes, setNotes] = useState('');
  const [validationError, setValidationError] = useState<string | null>(null);

  const themeColors = getSemanticColors(isDark);

  const viewTypeOptions: { type: PhotoViewType; label: string }[] = [
    { type: 'FRONT', label: 'Chính diện (Front)' },
    { type: 'BACK', label: 'Phía sau (Back)' },
    { type: 'SIDE_LEFT', label: 'Nghiêng trái' },
    { type: 'SIDE_RIGHT', label: 'Nghiêng phải' },
    { type: 'OTHER', label: 'Khác' },
  ];

  const handlePickPhoto = async () => {
    if (isPicking || isSubmitting) return;
    setIsPicking(true);
    setValidationError(null);
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ['image/jpeg', 'image/png'],
        copyToCacheDirectory: true,
        multiple: false,
      });
      if (result.canceled || !result.assets[0]) return;
      const asset = result.assets[0];
      if (asset.size !== undefined && asset.size > 10 * 1024 * 1024) {
        setValidationError('Ảnh không được vượt quá 10 MB.');
        return;
      }
      const mimeType = asset.mimeType || (/\.png$/i.test(asset.name) ? 'image/png' : /\.jpe?g$/i.test(asset.name) ? 'image/jpeg' : '');
      if (!['image/jpeg', 'image/png'].includes(mimeType)) {
        setValidationError('Vui lòng chọn ảnh JPEG hoặc PNG.');
        return;
      }
      setPhoto({ uri: asset.uri, name: asset.name, mimeType });
    } catch {
      setValidationError('Không thể đọc ảnh. Vui lòng chọn lại ảnh từ thiết bị.');
    } finally {
      setIsPicking(false);
    }
  };

  const handleSubmit = async () => {
    if (isSubmitting || isPicking) return;
    setValidationError(null);

    if (!photo) {
      setValidationError('Vui lòng chọn ảnh từ thiết bị trước khi tải lên.');
      return;
    }

    let weightNum: number | undefined = undefined;
    if (weightAtTime.trim()) {
      const parsed = Number(weightAtTime.trim());
      if (!Number.isFinite(parsed) || parsed <= 0) {
        setValidationError('Cân nặng không hợp lệ');
        return;
      }
      weightNum = parsed;
    }

    try {
      await onSubmit({
        fileUri: photo.uri,
        fileName: photo.name,
        mimeType: photo.mimeType,
        viewType,
        weightAtTime: weightNum,
        weightUnitCode: weightNum !== undefined ? 'KG' : undefined,
        takenAt: new Date().toISOString(),
        notes: notes.trim() || undefined,
      });

      setPhoto(null);
      setWeightAtTime('');
      setNotes('');
      onClose();
    } catch (err: any) {
      setValidationError(err?.message || 'Không thể tải ảnh lên');
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={onClose}>
      <View style={styles.overlay}>
        <View style={[styles.card, { backgroundColor: themeColors.surface, borderColor: themeColors.border }]}>
          <Text style={[styles.title, { color: themeColors.textPrimary }]}>Tải ảnh tiến độ</Text>

          <ScrollView style={styles.scroll}>
            <Text style={[styles.label, { color: themeColors.textSecondary }]}>Góc chụp:</Text>
            <View style={styles.viewTypeGrid}>
              {viewTypeOptions.map(opt => {
                const isSelected = viewType === opt.type;
                return (
                  <Pressable
                    key={opt.type}
                    testID={`view-type-${opt.type}`}
                    onPress={() => setViewType(opt.type)}
                    style={[
                      styles.viewTypeChip,
                      {
                        backgroundColor: isSelected ? themeColors.primary : themeColors.surfaceSubtle,
                        borderColor: isSelected ? themeColors.primary : themeColors.border,
                      },
                    ]}>
                    <Text
                      style={[
                        styles.viewTypeText,
                        { color: isSelected ? '#FFFFFF' : themeColors.textPrimary },
                      ]}>
                      {opt.label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>

            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: themeColors.textSecondary }]}>Ảnh tiến độ (JPEG/PNG, tối đa 10 MB):</Text>
              <Pressable
                testID="btn-select-photo"
                accessibilityRole="button"
                accessibilityLabel="Chọn ảnh từ thiết bị"
                disabled={isSubmitting || isPicking}
                onPress={handlePickPhoto}
                style={[styles.input, { borderColor: themeColors.border }]}>
                <Text style={{ color: themeColors.primary }}>
                  {isPicking ? 'Đang chọn ảnh...' : photo ? 'Chọn ảnh khác' : 'Chọn ảnh từ thiết bị'}
                </Text>
              </Pressable>
              {photo && (
                <View>
                  <Text style={{ color: themeColors.textSecondary }}>{photo.name}</Text>
                  <Image source={{ uri: photo.uri }} style={styles.preview} accessibilityLabel="Ảnh tiến độ đã chọn" />
                </View>
              )}
            </View>

            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: themeColors.textSecondary }]}>Cân nặng tại thời điểm chụp (kg):</Text>
              <TextInput
                testID="input-photo-weight"
                style={[styles.input, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                placeholder="Ví dụ: 74.5"
                placeholderTextColor={themeColors.textSecondary}
                keyboardType="numeric"
                value={weightAtTime}
                onChangeText={setWeightAtTime}
              />
            </View>

            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: themeColors.textSecondary }]}>Ghi chú:</Text>
              <TextInput
                testID="input-photo-notes"
                style={[styles.input, styles.textArea, { color: themeColors.textPrimary, borderColor: themeColors.border }]}
                placeholder="Buổi sáng trước ăn sáng..."
                placeholderTextColor={themeColors.textSecondary}
                multiline
                numberOfLines={2}
                value={notes}
                onChangeText={setNotes}
              />
            </View>

            <View style={[styles.securityNotice, { backgroundColor: themeColors.surfaceSubtle, borderColor: themeColors.border }]}>
              <Text style={[styles.noticeText, { color: themeColors.textSecondary }]}>
                Ảnh được kiểm tra an toàn và lưu trong kho riêng tư. Chỉ bạn và HLV được cấp quyền mới có thể xem.
              </Text>
            </View>

            {validationError && (
              <Text testID="upload-error-text" style={[styles.errorText, { color: themeColors.dangerText }]}>
                {validationError}
              </Text>
            )}
          </ScrollView>

          <View style={styles.buttonRow}>
            <Pressable
              testID="btn-cancel-upload"
              onPress={onClose}
              disabled={isSubmitting || isPicking}
              style={[styles.btn, styles.btnCancel, { borderColor: themeColors.border }]}>
              <Text style={[styles.btnText, { color: themeColors.textPrimary }]}>Hủy</Text>
            </Pressable>

            <Pressable
              testID="btn-submit-upload"
              onPress={handleSubmit}
              disabled={isSubmitting || isPicking}
              style={[styles.btn, styles.btnSubmit, { backgroundColor: themeColors.primary }]}>
              {isSubmitting ? (
                <ActivityIndicator size="small" color="#FFFFFF" />
              ) : (
                <Text style={[styles.btnText, { color: '#FFFFFF' }]}>Tải ảnh lên</Text>
              )}
            </Pressable>
          </View>
        </View>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  preview: {
    width: '100%',
    height: 160,
    resizeMode: 'contain',
    marginTop: spacing.sm,
  },
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
  viewTypeGrid: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginBottom: spacing.md,
  },
  viewTypeChip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
    borderWidth: 1,
  },
  viewTypeText: {
    ...typography.caption,
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
    minHeight: 60,
    textAlignVertical: 'top',
  },
  securityNotice: {
    padding: spacing.md,
    borderRadius: radius.md,
    borderWidth: 1,
    marginBottom: spacing.md,
  },
  noticeText: {
    ...typography.bodySmall,
    lineHeight: 18,
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
