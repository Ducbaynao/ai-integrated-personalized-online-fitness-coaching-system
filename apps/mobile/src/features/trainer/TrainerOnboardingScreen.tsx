import React, { useRef, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { useRouter } from 'expo-router';
import { apiErrorMessage } from '@/localization/displayLabels';
import { useAuth } from '@/features/auth/AuthContext';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { ApiError } from '@/types/auth';
import { validateTrainerProfileForm } from './trainerValidation';
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function TrainerOnboardingScreen() {
  const router = useRouter();
  const { user, refreshUser, logout } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const [publicSlug, setPublicSlug] = useState('');
  const [bio, setBio] = useState('');
  const [yearsExperience, setYearsExperience] = useState('');
  const [acceptingStudents, setAcceptingStudents] = useState(false);

  const isSubmittingRef = useRef(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const submitProfile = async (skipDetails = false) => {
    if (isSubmittingRef.current) return;

    setErrorMessage(null);
    setFieldErrors({});

    if (!skipDetails) {
      const clientErrors = validateTrainerProfileForm({
        publicSlug,
        bio,
        yearsExperience,
        acceptingStudents,
      });

      const errorMap: Record<string, string> = {};
      Object.entries(clientErrors).forEach(([key, val]) => {
        if (val) {
          errorMap[key] = val;
        }
      });

      if (Object.keys(errorMap).length > 0) {
        setFieldErrors(errorMap);
        return;
      }
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const payload = skipDetails
        ? {}
        : {
            publicSlug: publicSlug.trim() ? publicSlug.trim() : null,
            bio: bio.trim() ? bio.trim() : null,
            yearsExperience: yearsExperience.trim()
              ? Number(yearsExperience.trim())
              : null,
            acceptingStudents,
          };

      await trainerProfileApi.createTrainerProfile(payload);
      await refreshUser('TRAINER');
      router.replace('/(app)');
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409) {
          const code = err.errorResponse?.errorCode;
          if (code === 'TRAINER_SLUG_ALREADY_EXISTS') {
            setFieldErrors({
              publicSlug: 'Đường dẫn công khai này đã được dùng. Vui lòng chọn đường dẫn khác.',
            });
            return;
          }
          if (code === 'TRAINER_CAPABILITY_REVOKED') {
            setErrorMessage(
              'Quyền huấn luyện của tài khoản đã bị thu hồi. Vui lòng liên hệ quản trị nền tảng.'
            );
            return;
          }
          // Profile might already exist
          try {
            const refreshed = await refreshUser('TRAINER');
            if (refreshed?.capabilities?.hasTrainerProfile) {
              router.replace('/(app)');
              return;
            }
          } catch {
            // Ignore refresh error
          }
        }

        if (err.errorResponse?.fieldErrors && err.errorResponse.fieldErrors.length > 0) {
          const apiMap: Record<string, string> = {};
          err.errorResponse.fieldErrors.forEach((fe) => {
            apiMap[fe.field] = 'Giá trị chưa hợp lệ. Vui lòng kiểm tra lại.';
          });
          setFieldErrors(apiMap);
        } else {
          setErrorMessage(apiErrorMessage(err, 'Không thể tạo hồ sơ huấn luyện viên.'));
        }
      } else {
        setErrorMessage('Lỗi mạng. Vui lòng thử lại.');
      }
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      <ScrollView
        testID="trainer-onboarding-screen"
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled">
        <View style={styles.header}>
          <Text style={[styles.title, { color: themeColors.textPrimary }]}>
            Thiết lập hồ sơ huấn luyện viên
          </Text>
          <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
            Kích hoạt vai trò Huấn luyện viên để thiết lập hồ sơ huấn luyện.
          </Text>
        </View>

        {/* Invariant Policy Notice */}
        <View
          testID="coaching-authority-notice"
          accessibilityRole="alert"
          style={[
            styles.noticeCard,
            {
              backgroundColor: themeColors.surfaceSubtle,
              borderColor: themeColors.border,
            },
          ]}>
          <Text
            style={[
              styles.noticeTitle,
              { color: themeColors.textPrimary },
            ]}>
            Quy tắc về quyền huấn luyện
          </Text>
          <Text
            style={[
              styles.noticeText,
              { color: themeColors.textSecondary },
            ]}>
            Tạo hồ sơ Huấn luyện viên sẽ kích hoạt vai trò tương ứng. Quyền huấn luyện chỉ được cấp sau khi quản trị nền tảng xem xét và phê duyệt bằng cấp, chứng chỉ.
          </Text>
        </View>

        {errorMessage && (
          <View
            testID="trainer-error-banner"
            accessibilityRole="alert"
            style={[
              styles.errorBanner,
              {
                backgroundColor: themeColors.dangerSurface,
                borderColor: themeColors.dangerText,
              },
            ]}>
            <Text
              style={[
                styles.errorBannerText,
                { color: themeColors.dangerText },
              ]}>
              {errorMessage}
            </Text>
          </View>
        )}

        <View style={styles.formSection}>
          {/* Public Slug */}
          <View style={styles.fieldGroup}>
            <Text style={[styles.label, { color: themeColors.textPrimary }]}>
              Định danh hồ sơ công khai
            </Text>
            <Text style={[styles.fieldHint, { color: themeColors.textSecondary }]}>
              Định danh URL tùy chọn (ví dụ: coach-alex). Chỉ dùng chữ thường không dấu, chữ số và dấu gạch nối.
            </Text>
            <TextInput
              testID="trainer-slug-input"
              accessibilityLabel="Định danh hồ sơ công khai"
              accessibilityHint="Nhập định danh URL duy nhất bằng chữ thường không dấu, chữ số và dấu gạch nối"
              autoCapitalize="none"
              autoCorrect={false}
              placeholder="Ví dụ: coach-alex"
              placeholderTextColor={themeColors.textSecondary}
              value={publicSlug}
              onChangeText={(val) => {
                setPublicSlug(val);
                if (fieldErrors.publicSlug) {
                  setFieldErrors((prev) => ({ ...prev, publicSlug: '' }));
                }
              }}
              style={[
                styles.input,
                {
                  backgroundColor: themeColors.surface,
                  borderColor: fieldErrors.publicSlug ? themeColors.dangerText : themeColors.border,
                  color: themeColors.textPrimary,
                },
              ]}
            />
            {fieldErrors.publicSlug ? (
              <Text
                testID="trainer-field-error-publicSlug"
                style={[styles.fieldErrorText, { color: themeColors.dangerText }]}>
                {fieldErrors.publicSlug}
              </Text>
            ) : null}
          </View>

          {/* Bio */}
          <View style={styles.fieldGroup}>
            <Text style={[styles.label, { color: themeColors.textPrimary }]}>
              Giới thiệu chuyên môn
            </Text>
            <Text style={[styles.fieldHint, { color: themeColors.textSecondary }]}>
              Mô tả nền tảng, triết lý và chuyên môn huấn luyện của bạn (tối đa 4000 ký tự).
            </Text>
            <TextInput
              testID="trainer-bio-input"
              accessibilityLabel="Giới thiệu chuyên môn"
              accessibilityHint="Nhập tiểu sử huấn luyện chuyên nghiệp của bạn"
              multiline
              numberOfLines={4}
              maxLength={4000}
              placeholder="Giới thiệu kinh nghiệm và phong cách huấn luyện của bạn..."
              placeholderTextColor={themeColors.textSecondary}
              value={bio}
              onChangeText={(val) => {
                setBio(val);
                if (fieldErrors.bio) {
                  setFieldErrors((prev) => ({ ...prev, bio: '' }));
                }
              }}
              style={[
                styles.textArea,
                {
                  backgroundColor: themeColors.surface,
                  borderColor: fieldErrors.bio ? themeColors.dangerText : themeColors.border,
                  color: themeColors.textPrimary,
                },
              ]}
            />
            {fieldErrors.bio ? (
              <Text
                testID="trainer-field-error-bio"
                style={[styles.fieldErrorText, { color: themeColors.dangerText }]}>
                {fieldErrors.bio}
              </Text>
            ) : null}
          </View>

          {/* Years of Experience */}
          <View style={styles.fieldGroup}>
            <Text style={[styles.label, { color: themeColors.textPrimary }]}>
              Số năm kinh nghiệm huấn luyện
            </Text>
            <TextInput
              testID="trainer-experience-input"
              accessibilityLabel="Số năm kinh nghiệm huấn luyện"
              accessibilityHint="Nhập số năm kinh nghiệm từ 0 đến 99,99"
              keyboardType="decimal-pad"
              placeholder="e.g. 5"
              placeholderTextColor={themeColors.textSecondary}
              value={yearsExperience}
              onChangeText={(val) => {
                setYearsExperience(val);
                if (fieldErrors.yearsExperience) {
                  setFieldErrors((prev) => ({ ...prev, yearsExperience: '' }));
                }
              }}
              style={[
                styles.input,
                {
                  backgroundColor: themeColors.surface,
                  borderColor: fieldErrors.yearsExperience
                    ? themeColors.dangerText
                    : themeColors.border,
                  color: themeColors.textPrimary,
                },
              ]}
            />
            {fieldErrors.yearsExperience ? (
              <Text
                testID="trainer-field-error-yearsExperience"
                style={[styles.fieldErrorText, { color: themeColors.dangerText }]}>
                {fieldErrors.yearsExperience}
              </Text>
            ) : null}
          </View>

          {/* Accepting Students Switch */}
          <View
            style={[
              styles.switchRow,
              {
                backgroundColor: themeColors.surface,
                borderColor: themeColors.border,
              },
            ]}>
            <View style={styles.switchInfo}>
              <Text style={[styles.switchLabel, { color: themeColors.textPrimary }]}>
                Nhận học viên
              </Text>
              <Text style={[styles.switchDescription, { color: themeColors.textSecondary }]}>
                Cho phép nhận học viên mới sau khi được xác minh.
              </Text>
            </View>
            <Switch
              testID="trainer-accepting-switch"
              accessibilityLabel="Nhận học viên"
              value={acceptingStudents}
              onValueChange={setAcceptingStudents}
              trackColor={{ false: colors.neutral[300], true: themeColors.primary }}
              thumbColor={Platform.OS === 'android' ? '#ffffff' : undefined}
            />
          </View>
        </View>

        {/* Action Buttons */}
        <View style={styles.actionContainer}>
          <Pressable
            testID="trainer-submit-button"
            accessibilityRole="button"
            accessibilityLabel="Tạo hồ sơ huấn luyện viên"
            disabled={isSubmitting}
            onPress={() => submitProfile(false)}
            style={[
              styles.primaryButton,
              {
                backgroundColor: themeColors.primary,
                opacity: isSubmitting ? 0.7 : 1,
              },
            ]}>
            {isSubmitting ? (
              <ActivityIndicator color={themeColors.textOnPrimary} />
            ) : (
              <Text style={styles.primaryButtonText}>Hoàn tất thiết lập huấn luyện viên</Text>
            )}
          </Pressable>

          <Pressable
            testID="trainer-skip-button"
            accessibilityRole="button"
            accessibilityLabel="Bỏ qua chi tiết và kích hoạt hồ sơ huấn luyện viên ngay bây giờ"
            disabled={isSubmitting}
            onPress={() => submitProfile(true)}
            style={styles.secondaryButton}>
            <Text style={[styles.secondaryButtonText, { color: themeColors.textSecondary }]}>
              Để sau
            </Text>
          </Pressable>
        </View>

        <View style={styles.footer}>
          <Text style={[styles.footerText, { color: themeColors.textSecondary }]}>
            Đang đăng nhập bằng {user?.email}
          </Text>
          <Pressable
            testID="trainer-onboarding-logout-button"
            accessibilityRole="button"
            accessibilityLabel="Đăng xuất khỏi tài khoản của bạn"
            onPress={logout}
            style={styles.logoutButton}>
            <Text style={[styles.logoutText, { color: themeColors.textSecondary }]}>
              Đăng xuất
            </Text>
          </Pressable>
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollContent: {
    paddingHorizontal: layout.mobileScreenPadding,
    paddingTop: spacing.xl,
    paddingBottom: spacing.xxl,
  },
  header: {
    marginBottom: spacing.lg,
  },
  title: {
    ...typography.h2,
    marginBottom: spacing.xs,
  },
  subtitle: {
    ...typography.bodySmall,
  },
  noticeCard: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.md,
    marginBottom: spacing.lg,
  },
  noticeTitle: {
    ...typography.label,
    marginBottom: spacing.xs,
  },
  noticeText: {
    ...typography.caption,
  },
  errorBanner: {
    borderRadius: radius.md,
    borderWidth: 1,
    padding: spacing.md,
    marginBottom: spacing.lg,
  },
  errorBannerText: {
    ...typography.bodySmall,
  },
  formSection: {
    gap: spacing.lg,
    marginBottom: spacing.xl,
  },
  fieldGroup: {
    gap: spacing.xs,
  },
  label: {
    ...typography.label,
  },
  fieldHint: {
    ...typography.caption,
    marginBottom: spacing.xs,
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
    minHeight: 100,
    textAlignVertical: 'top',
  },
  fieldErrorText: {
    ...typography.caption,
    marginTop: spacing.xs,
  },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    padding: spacing.md,
    borderRadius: radius.lg,
    borderWidth: 1,
    minHeight: layout.minimumTouchTarget,
  },
  switchInfo: {
    flex: 1,
    paddingRight: spacing.md,
  },
  switchLabel: {
    ...typography.label,
    marginBottom: 2,
  },
  switchDescription: {
    ...typography.caption,
  },
  actionContainer: {
    gap: spacing.md,
    marginBottom: spacing.xl,
  },
  primaryButton: {
    borderRadius: radius.md,
    minHeight: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  primaryButtonText: {
    ...typography.label,
    color: semanticColors.textOnPrimary,
  },
  secondaryButton: {
    minHeight: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
  },
  secondaryButtonText: {
    ...typography.label,
  },
  footer: {
    alignItems: 'center',
    gap: spacing.xs,
  },
  footerText: {
    ...typography.caption,
  },
  logoutButton: {
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    minHeight: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
  },
  logoutText: {
    ...typography.bodySmall,
    fontWeight: '500',
    textDecorationLine: 'underline',
  },
});
