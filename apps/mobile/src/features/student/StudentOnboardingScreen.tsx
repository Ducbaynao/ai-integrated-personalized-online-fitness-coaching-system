import React, { useRef, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { studentProfileApi } from '@/services/studentProfileApi';
import { ApiError } from '@/types/auth';
import { Gender, TrainingExperienceLevel } from '@/types/student';
import { validateStudentProfileForm } from './studentValidation';
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

const GENDER_OPTIONS: { label: string; value: Gender }[] = [
  { label: 'Male', value: 'MALE' },
  { label: 'Female', value: 'FEMALE' },
  { label: 'Non-binary', value: 'NON_BINARY' },
  { label: 'Other', value: 'OTHER' },
  { label: 'Prefer not to say', value: 'PREFER_NOT_TO_SAY' },
];

const EXPERIENCE_OPTIONS: { label: string; value: TrainingExperienceLevel }[] = [
  { label: 'Beginner', value: 'BEGINNER' },
  { label: 'Intermediate', value: 'INTERMEDIATE' },
  { label: 'Advanced', value: 'ADVANCED' },
];

const DAY_OPTIONS = [1, 2, 3, 4, 5, 6, 7];
const DURATION_PRESETS = [30, 45, 60, 90];

export function StudentOnboardingScreen() {
  const router = useRouter();
  const { user, refreshUser, logout } = useAuth();
  const isDark = useColorScheme() === 'dark';

  const [dateOfBirth, setDateOfBirth] = useState('');
  const [gender, setGender] = useState<Gender | null>(null);
  const [trainingExperienceLevel, setTrainingExperienceLevel] = useState<TrainingExperienceLevel | null>(null);
  const [trainingExperienceMonths, setTrainingExperienceMonths] = useState('');
  const [availableDaysPerWeek, setAvailableDaysPerWeek] = useState<number | null>(null);
  const [preferredSessionMinutes, setPreferredSessionMinutes] = useState('');

  const isSubmittingRef = useRef(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});

  const handleGenderSelect = (val: Gender) => {
    setGender((prev) => (prev === val ? null : val));
  };

  const handleExperienceSelect = (val: TrainingExperienceLevel) => {
    setTrainingExperienceLevel((prev) => (prev === val ? null : val));
  };

  const handleDaysSelect = (val: number) => {
    setAvailableDaysPerWeek((prev) => (prev === val ? null : val));
  };

  const handleDurationPreset = (val: number) => {
    setPreferredSessionMinutes(val.toString());
  };

  const submitProfile = async (skipDetails = false) => {
    if (isSubmittingRef.current) return;

    setErrorMessage(null);
    setFieldErrors({});

    if (!skipDetails) {
      const clientErrors = validateStudentProfileForm({
        dateOfBirth,
        trainingExperienceMonths,
        availableDaysPerWeek: availableDaysPerWeek ? availableDaysPerWeek.toString() : undefined,
        preferredSessionMinutes,
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
        ? { onboardingCompleted: true }
        : {
            dateOfBirth: dateOfBirth.trim() ? dateOfBirth.trim() : null,
            gender: gender ?? null,
            trainingExperienceLevel: trainingExperienceLevel ?? null,
            trainingExperienceMonths: trainingExperienceMonths.trim()
              ? Number(trainingExperienceMonths.trim())
              : null,
            availableDaysPerWeek: availableDaysPerWeek ?? null,
            preferredSessionMinutes: preferredSessionMinutes.trim()
              ? Number(preferredSessionMinutes.trim())
              : null,
            onboardingCompleted: true,
          };

      await studentProfileApi.createStudentProfile(payload);
      await refreshUser('STUDENT');
      router.replace('/(app)');
    } catch (err) {
      if (err instanceof ApiError) {
        // Recovery logic for 409 Conflict: single refreshUser() check
        if (err.status === 409) {
          try {
            const refreshed = await refreshUser('STUDENT');
            if (refreshed?.capabilities?.hasStudentProfile) {
              router.replace('/(app)');
              return;
            }
          } catch {
            // Ignore refresh error and show original 409 error below
          }
        }

        if (err.errorResponse?.fieldErrors && err.errorResponse.fieldErrors.length > 0) {
          const map: Record<string, string> = {};
          err.errorResponse.fieldErrors.forEach((fe) => {
            map[fe.field] = fe.message;
          });
          setFieldErrors(map);
        }
        setErrorMessage(err.message || 'Failed to complete student onboarding.');
      } else {
        setErrorMessage('An unexpected error occurred. Please check your connection and try again.');
      }
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  const handleLogout = async () => {
    try {
      await logout();
    } catch {
      // Swallowed
    }
  };

  const themeColors = getSemanticColors(isDark);
  const bg = themeColors.canvas;
  const cardBg = themeColors.surface;
  const textColor = themeColors.textPrimary;
  const subtextColor = themeColors.textSecondary;
  const borderColor = themeColors.border;

  return (
    <KeyboardAvoidingView
      testID="onboarding-screen"
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      style={[styles.container, { backgroundColor: bg }]}>
      <ScrollView
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled">
        <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
          {/* Header */}
          <View style={styles.header}>
            <Text style={[styles.greeting, { color: textColor }]}>
              Welcome{user?.displayName ? `, ${user.displayName}` : ''}!
            </Text>
            <Text style={[styles.title, { color: textColor }]}>
              Complete Your Student Profile
            </Text>
            <Text style={[styles.subtitle, { color: subtextColor }]}>
              Set up your fitness baseline so we can personalize your training journey.
            </Text>
          </View>

          {/* Error Banner */}
          {errorMessage && (
            <View
              style={styles.errorBanner}
              accessibilityRole="alert"
              accessibilityLiveRegion="polite">
              <Text style={styles.errorBannerText}>{errorMessage}</Text>
            </View>
          )}

          {/* 1. Date of Birth */}
          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Date of Birth</Text>
            <Text style={[styles.helperText, { color: subtextColor }]}>
              Format: YYYY-MM-DD (e.g., 1995-06-15)
            </Text>
            <TextInput
              testID="dob-input"
              accessibilityLabel="Date of Birth"
              accessibilityHint="Enter your date of birth in YYYY-MM-DD format"
              aria-invalid={!!fieldErrors.dateOfBirth}
              style={[
                styles.input,
                {
                  color: textColor,
                  borderColor: fieldErrors.dateOfBirth ? themeColors.dangerText : borderColor,
                },
              ]}
              placeholder="YYYY-MM-DD"
              placeholderTextColor={subtextColor}
              value={dateOfBirth}
              onChangeText={setDateOfBirth}
              maxLength={10}
              editable={!isSubmitting}
            />
            {fieldErrors.dateOfBirth && (
              <Text style={styles.fieldError}>{fieldErrors.dateOfBirth}</Text>
            )}
          </View>

          {/* 2. Gender */}
          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Gender</Text>
            <View style={styles.chipsContainer}>
              {GENDER_OPTIONS.map((opt) => {
                const isSelected = gender === opt.value;
                return (
                  <Pressable
                    key={opt.value}
                    testID={`gender-chip-${opt.value}`}
                    accessibilityRole="button"
                    accessibilityLabel={opt.label}
                    accessibilityState={{ selected: isSelected }}
                    disabled={isSubmitting}
                    onPress={() => handleGenderSelect(opt.value)}
                    style={[
                      styles.chip,
                      {
                        backgroundColor: isSelected
                          ? themeColors.primary
                          : themeColors.surfaceSubtle,
                        borderColor: isSelected ? themeColors.primary : borderColor,
                      },
                    ]}>
                    <Text
                      style={[
                        styles.chipText,
                        { color: isSelected ? themeColors.textOnPrimary : textColor },
                      ]}>
                      {opt.label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          </View>

          {/* 3. Training Experience Level */}
          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Experience Level</Text>
            <View style={styles.chipsContainer}>
              {EXPERIENCE_OPTIONS.map((opt) => {
                const isSelected = trainingExperienceLevel === opt.value;
                return (
                  <Pressable
                    key={opt.value}
                    testID={`experience-chip-${opt.value}`}
                    accessibilityRole="button"
                    accessibilityLabel={opt.label}
                    accessibilityState={{ selected: isSelected }}
                    disabled={isSubmitting}
                    onPress={() => handleExperienceSelect(opt.value)}
                    style={[
                      styles.chip,
                      {
                        backgroundColor: isSelected
                          ? themeColors.primary
                          : themeColors.surfaceSubtle,
                        borderColor: isSelected ? themeColors.primary : borderColor,
                      },
                    ]}>
                    <Text
                      style={[
                        styles.chipText,
                        { color: isSelected ? themeColors.textOnPrimary : textColor },
                      ]}>
                      {opt.label}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
          </View>

          {/* 4. Training Experience (Months) */}
          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Training Experience (Months)</Text>
            <Text style={[styles.helperText, { color: subtextColor }]}>
              Total months of regular workout experience (e.g., 12)
            </Text>
            <TextInput
              testID="months-input"
              accessibilityLabel="Training Experience in Months"
              accessibilityHint="Enter number of months you have trained"
              aria-invalid={!!fieldErrors.trainingExperienceMonths}
              style={[
                styles.input,
                {
                  color: textColor,
                  borderColor: fieldErrors.trainingExperienceMonths
                    ? themeColors.dangerText
                    : borderColor,
                },
              ]}
              placeholder="e.g., 12"
              placeholderTextColor={subtextColor}
              value={trainingExperienceMonths}
              onChangeText={setTrainingExperienceMonths}
              keyboardType="numeric"
              editable={!isSubmitting}
            />
            {fieldErrors.trainingExperienceMonths && (
              <Text style={styles.fieldError}>{fieldErrors.trainingExperienceMonths}</Text>
            )}
          </View>

          {/* 5. Available Days Per Week */}
          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Available Days Per Week</Text>
            <Text style={[styles.helperText, { color: subtextColor }]}>
              How many days per week can you train? (1 to 7)
            </Text>
            <View style={styles.chipsContainer}>
              {DAY_OPTIONS.map((day) => {
                const isSelected = availableDaysPerWeek === day;
                return (
                  <Pressable
                    key={day}
                    testID={`days-chip-${day}`}
                    accessibilityRole="button"
                    accessibilityLabel={`${day} days per week`}
                    accessibilityState={{ selected: isSelected }}
                    disabled={isSubmitting}
                    onPress={() => handleDaysSelect(day)}
                    style={[
                      styles.dayChip,
                      {
                        backgroundColor: isSelected
                          ? themeColors.primary
                          : themeColors.surfaceSubtle,
                        borderColor: isSelected ? themeColors.primary : borderColor,
                      },
                    ]}>
                    <Text
                      style={[
                        styles.chipText,
                        { color: isSelected ? themeColors.textOnPrimary : textColor },
                      ]}>
                      {day}
                    </Text>
                  </Pressable>
                );
              })}
            </View>
            {fieldErrors.availableDaysPerWeek && (
              <Text style={styles.fieldError}>{fieldErrors.availableDaysPerWeek}</Text>
            )}
          </View>

          {/* 6. Preferred Session Duration */}
          <View style={styles.inputGroup}>
            <Text style={[styles.label, { color: textColor }]}>Preferred Session Duration (Minutes)</Text>
            <Text style={[styles.helperText, { color: subtextColor }]}>
              Typical duration between 5 and 480 minutes
            </Text>
            <View style={styles.chipsContainer}>
              {DURATION_PRESETS.map((preset) => {
                const isSelected = preferredSessionMinutes === preset.toString();
                return (
                  <Pressable
                    key={preset}
                    testID={`duration-chip-${preset}`}
                    accessibilityRole="button"
                    accessibilityLabel={`${preset} minutes`}
                    accessibilityState={{ selected: isSelected }}
                    disabled={isSubmitting}
                    onPress={() => handleDurationPreset(preset)}
                    style={[
                      styles.chip,
                      {
                        backgroundColor: isSelected
                          ? themeColors.primary
                          : themeColors.surfaceSubtle,
                        borderColor: isSelected ? themeColors.primary : borderColor,
                      },
                    ]}>
                    <Text
                      style={[
                        styles.chipText,
                        { color: isSelected ? themeColors.textOnPrimary : textColor },
                      ]}>
                      {preset}m
                    </Text>
                  </Pressable>
                );
              })}
            </View>
            <TextInput
              testID="duration-input"
              accessibilityLabel="Preferred Session Duration in Minutes"
              accessibilityHint="Enter minutes per workout session"
              aria-invalid={!!fieldErrors.preferredSessionMinutes}
              style={[
                styles.input,
                {
                  color: textColor,
                  borderColor: fieldErrors.preferredSessionMinutes
                    ? themeColors.dangerText
                    : borderColor,
                  marginTop: spacing.xs,
                },
              ]}
              placeholder="Or enter custom minutes (e.g., 45)"
              placeholderTextColor={subtextColor}
              value={preferredSessionMinutes}
              onChangeText={setPreferredSessionMinutes}
              keyboardType="numeric"
              editable={!isSubmitting}
            />
            {fieldErrors.preferredSessionMinutes && (
              <Text style={styles.fieldError}>{fieldErrors.preferredSessionMinutes}</Text>
            )}
          </View>

          {/* Submit Button */}
          <Pressable
            testID="submit-button"
            accessibilityRole="button"
            accessibilityLabel="Complete Setup"
            accessibilityState={{ disabled: isSubmitting, busy: isSubmitting }}
            style={({ pressed }) => [
              styles.primaryButton,
              {
                backgroundColor: isSubmitting
                  ? themeColors.primaryPressed
                  : pressed
                  ? themeColors.primaryPressed
                  : themeColors.primary,
              },
            ]}
            onPress={() => submitProfile(false)}
            disabled={isSubmitting}>
            {isSubmitting ? (
              <ActivityIndicator color={themeColors.textOnPrimary} />
            ) : (
              <Text style={styles.primaryButtonText}>Complete Setup</Text>
            )}
          </Pressable>

          {/* Continue without details option */}
          <Pressable
            testID="skip-button"
            accessibilityRole="button"
            accessibilityLabel="Continue without details"
            accessibilityState={{ disabled: isSubmitting }}
            style={({ pressed }) => [
              styles.secondaryButton,
              {
                borderColor,
                backgroundColor: pressed ? themeColors.surfaceSubtle : 'transparent',
              },
            ]}
            onPress={() => submitProfile(true)}
            disabled={isSubmitting}>
            <Text style={[styles.secondaryButtonText, { color: textColor }]}>
              Continue without details
            </Text>
          </Pressable>

          {/* Switch accounts / Logout */}
          <Pressable
            testID="logout-button"
            accessibilityRole="button"
            accessibilityLabel="Sign out or switch account"
            disabled={isSubmitting}
            style={styles.switchAccount}
            onPress={handleLogout}>
            <Text style={[styles.switchAccountText, { color: subtextColor }]}>
              Not your account?{' '}
              <Text style={{ color: themeColors.primary, fontWeight: '600' }}>
                Sign out
              </Text>
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
    flexGrow: 1,
    padding: layout.mobileScreenPadding,
    justifyContent: 'center',
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.xl,
    borderWidth: 1,
    gap: spacing.lg,
  },
  header: {
    gap: spacing.xxs,
  },
  greeting: {
    ...typography.label,
    color: colors.brand[600],
  },
  title: {
    ...typography.h2,
  },
  subtitle: {
    ...typography.bodySmall,
    marginTop: spacing.xxs,
  },
  errorBanner: {
    backgroundColor: semanticColors.dangerSurface,
    borderWidth: 1,
    borderColor: colors.danger[600],
    borderRadius: radius.md,
    padding: spacing.md,
  },
  errorBannerText: {
    ...typography.bodySmall,
    color: semanticColors.dangerText,
    fontWeight: '500',
  },
  inputGroup: {
    gap: spacing.xs,
  },
  label: {
    ...typography.label,
  },
  helperText: {
    ...typography.caption,
  },
  input: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    ...typography.body,
  },
  fieldError: {
    ...typography.caption,
    color: semanticColors.dangerText,
    marginTop: spacing.xxs,
  },
  chipsContainer: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    marginTop: spacing.xxs,
  },
  chip: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.full,
    borderWidth: 1,
    minHeight: 36,
    justifyContent: 'center',
    alignItems: 'center',
  },
  dayChip: {
    width: 40,
    height: 40,
    borderRadius: radius.full,
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  chipText: {
    ...typography.label,
    fontSize: 13,
  },
  primaryButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: spacing.sm,
  },
  primaryButtonText: {
    ...typography.label,
    color: semanticColors.textOnPrimary,
  },
  secondaryButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    borderWidth: 1,
    justifyContent: 'center',
    alignItems: 'center',
  },
  secondaryButtonText: {
    ...typography.label,
  },
  switchAccount: {
    alignItems: 'center',
    paddingVertical: spacing.xs,
  },
  switchAccountText: {
    ...typography.bodySmall,
  },
});
