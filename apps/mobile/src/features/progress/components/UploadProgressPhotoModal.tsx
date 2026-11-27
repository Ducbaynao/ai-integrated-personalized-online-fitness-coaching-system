import React, { useEffect, useRef, useState } from 'react';
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
import { fontFamily, typography } from '@/design-system/tokens/typography';
import { PHOTO_VIEW_OPTIONS } from '@/localization/progressLabels';
import { progressPhotoUploadErrorMessage } from '@/localization/progressPhotoErrors';
import { getAuthSessionBinding } from '@/services/apiClient';
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
  const mounted = useRef(false);
  const busy = useRef(false);
  const [isSending, setIsSending] = useState(false);
  const [uploadFailed, setUploadFailed] = useState(false);
  const binding = getAuthSessionBinding();
  const isCurrent = () => {
    const current = getAuthSessionBinding();
    return mounted.current && current.authEpoch === binding.authEpoch && current.capabilityEpoch === binding.capabilityEpoch;
  };
  useEffect(() => {
    mounted.current = true;
    return () => { mounted.current = false; };
  }, []);

  const themeColors = getSemanticColors(isDark);

  const viewTypeOptions: { type: PhotoViewType; label: string }[] = PHOTO_VIEW_OPTIONS;

  const handlePickPhoto = async () => {
    if (isPicking || isSubmitting || busy.current) return;
    setIsPicking(true);
    setValidationError(null);
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ['image/jpeg', 'image/png'],
        copyToCacheDirectory: true,
        multiple: false,
      });
      if (!isCurrent()) return;
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
      setUploadFailed(false);
    } catch {
      if (isCurrent()) setValidationError('Không thể đọc ảnh. Vui lòng chọn lại ảnh từ thiết bị.');
    } finally {
      if (isCurrent()) setIsPicking(false);
    }
  };

  const handleSubmit = async () => {
    if (isSubmitting || isPicking || busy.current) return;
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

    busy.current = true;
    setIsSending(true);
    setUploadFailed(false);
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
      if (!isCurrent()) return;
      setPhoto(null);
      setWeightAtTime('');
      setNotes('');
      onClose();
    } catch (err: unknown) {
      if (isCurrent()) {
        setUploadFailed(true);
        setValidationError(progressPhotoUploadErrorMessage(err));
      }
    } finally {
      busy.current = false;
      if (isCurrent()) setIsSending(false);
    }
  };

  return (
    <Modal visible={visible} transparent animationType="slide" onRequestClose={() => { if (!isSubmitting && !busy.current && !isPicking) onClose(); }}>
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
                disabled={isSubmitting || isPicking || isSending}
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
              <Text testID="upload-error-text" accessibilityRole="alert" style={[styles.errorText, { color: themeColors.dangerText }]}>
                {validationError}
              </Text>
            )}
          </ScrollView>

          <View style={styles.buttonRow}>
            <Pressable
              testID="btn-cancel-upload"
              onPress={onClose}
              disabled={isSubmitting || isPicking || isSending}
              style={[styles.btn, styles.btnCancel, { borderColor: themeColors.border }]}>
              <Text style={[styles.btnText, { color: themeColors.textPrimary }]}>Hủy</Text>
            </Pressable>

            <Pressable
              testID="btn-submit-upload"
              onPress={handleSubmit}
              disabled={isSubmitting || isPicking || isSending}
              style={[styles.btn, styles.btnSubmit, { backgroundColor: themeColors.primary }]}>
              {isSubmitting || isSending ? (
                <ActivityIndicator size="small" color="#FFFFFF" />
              ) : (
                <Text style={[styles.btnText, { color: '#FFFFFF' }]}>{uploadFailed ? 'Thử tải lại' : 'Tải ảnh lên'}</Text>
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
    fontFamily: fontFamily.bold,
    marginBottom: spacing.md,
  },
  scroll: {
    marginBottom: spacing.md,
  },
  label: {
    ...typography.label,
    fontFamily: fontFamily.semiBold,
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
    fontFamily: fontFamily.semiBold,
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
    fontFamily: fontFamily.semiBold,
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
    fontFamily: fontFamily.semiBold,
  },
});
