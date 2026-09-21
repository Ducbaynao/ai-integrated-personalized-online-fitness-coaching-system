import React, { useCallback, useEffect, useRef, useState } from 'react';
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
import { studentProfileApi } from '@/services/studentProfileApi';
import { ApiError } from '@/types/auth';
import {
  Gender,
  StudentProfileResponse,
  TrainingExperienceLevel,
  UpdateStudentProfileRequest,
} from '@/types/student';
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

export function StudentProfileScreen() {
  const router = useRouter();
  const isDark = useColorScheme() === 'dark';

  const [profile, setProfile] = useState<StudentProfileResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Edit mode state
  const [isEditing, setIsEditing] = useState(false);
  const [editDob, setEditDob] = useState('');
  const [editGender, setEditGender] = useState<Gender | null>(null);
  const [editLevel, setEditLevel] = useState<TrainingExperienceLevel | null>(null);
  const [editMonths, setEditMonths] = useState('');
  const [editDays, setEditDays] = useState<number | null>(null);
  const [editMinutes, setEditMinutes] = useState('');

  const isSubmittingRef = useRef(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  const populateForm = useCallback((data: StudentProfileResponse) => {
    setEditDob(data.dateOfBirth ?? '');
    setEditGender(data.gender ?? null);
    setEditLevel(data.trainingExperienceLevel ?? null);
    setEditMonths(data.trainingExperienceMonths != null ? data.trainingExperienceMonths.toString() : '');
    setEditDays(data.availableDaysPerWeek ?? null);
    setEditMinutes(data.preferredSessionMinutes != null ? data.preferredSessionMinutes.toString() : '');
  }, []);

  const fetchProfile = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const data = await studentProfileApi.getMyStudentProfile();
      setProfile(data);
      populateForm(data);
    } catch (err) {
      if (err instanceof ApiError) {
        setLoadError(err.message || 'Failed to load Student Profile.');
      } else {
        setLoadError('Network error. Unable to load profile.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [populateForm]);

  useEffect(() => {
    let isMounted = true;
    async function load() {
      try {
        const data = await studentProfileApi.getMyStudentProfile();
        if (isMounted) {
          setProfile(data);
          populateForm(data);
          setIsLoading(false);
        }
      } catch (err) {
        if (isMounted) {
          if (err instanceof ApiError) {
            setLoadError(err.message || 'Failed to load Student Profile.');
          } else {
            setLoadError('Network error. Unable to load profile.');
          }
          setIsLoading(false);
        }
      }
    }
    load();
    return () => {
      isMounted = false;
    };
  }, [populateForm]);

  const handleStartEdit = () => {
    if (profile) {
      populateForm(profile);
    }
    setFieldErrors({});
    setSubmitError(null);
    setSuccessMessage(null);
    setIsEditing(true);
  };

  const handleCancelEdit = () => {
    if (profile) {
      populateForm(profile);
    }
    setFieldErrors({});
    setSubmitError(null);
    setIsEditing(false);
  };

  const handleSaveChanges = async () => {
    if (isSubmittingRef.current || !profile) return;

    setSubmitError(null);
    setFieldErrors({});
    setSuccessMessage(null);

    // 1. Client-side validation
    const clientErrors = validateStudentProfileForm({
      dateOfBirth: editDob,
      trainingExperienceMonths: editMonths,
      availableDaysPerWeek: editDays ? editDays.toString() : undefined,
      preferredSessionMinutes: editMinutes,
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

    // 2. Compute exact PATCH diff (partial-update semantics)
    const patchPayload: UpdateStudentProfileRequest = {};
    let hasChanges = false;

    // Date of birth
    const trimmedDob = editDob.trim();
    const originalDob = profile.dateOfBirth ?? '';
    if (trimmedDob !== originalDob) {
      patchPayload.dateOfBirth = trimmedDob === '' ? null : trimmedDob;
      hasChanges = true;
    }

    // Gender
    if (editGender !== profile.gender) {
      patchPayload.gender = editGender;
      hasChanges = true;
    }

    // Training experience level
    if (editLevel !== profile.trainingExperienceLevel) {
      patchPayload.trainingExperienceLevel = editLevel;
      hasChanges = true;
    }

    // Training experience months
    const trimmedMonths = editMonths.trim();
    const originalMonths = profile.trainingExperienceMonths != null ? profile.trainingExperienceMonths.toString() : '';
    if (trimmedMonths !== originalMonths) {
      patchPayload.trainingExperienceMonths = trimmedMonths === '' ? null : Number(trimmedMonths);
      hasChanges = true;
    }

    // Available days per week
    if (editDays !== profile.availableDaysPerWeek) {
      patchPayload.availableDaysPerWeek = editDays;
      hasChanges = true;
    }

    // Preferred session minutes
    const trimmedMinutes = editMinutes.trim();
    const originalMinutes = profile.preferredSessionMinutes != null ? profile.preferredSessionMinutes.toString() : '';
    if (trimmedMinutes !== originalMinutes) {
      patchPayload.preferredSessionMinutes = trimmedMinutes === '' ? null : Number(trimmedMinutes);
      hasChanges = true;
    }

    if (!hasChanges) {
      setIsEditing(false);
      setSuccessMessage('No changes were made.');
      return;
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const updated = await studentProfileApi.updateMyStudentProfile(patchPayload);
      setProfile(updated);
      populateForm(updated);
      setIsEditing(false);
      setSuccessMessage('Profile updated successfully.');
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.errorResponse?.fieldErrors && err.errorResponse.fieldErrors.length > 0) {
          const map: Record<string, string> = {};
          err.errorResponse.fieldErrors.forEach((fe) => {
            map[fe.field] = fe.message;
          });
          setFieldErrors(map);
        }
        setSubmitError(err.message || 'Failed to update profile.');
      } else {
        setSubmitError('Network error. Unable to save changes.');
      }
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
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
      testID="student-profile-screen"
      behavior={Platform.OS === 'ios' ? 'padding' : 'height'}
      style={[styles.container, { backgroundColor: bg }]}>
      <ScrollView contentContainerStyle={styles.scrollContent} keyboardShouldPersistTaps="handled">
        {/* Navigation Bar */}
        <View style={styles.navBar}>
          <Pressable
            testID="back-button"
            accessibilityRole="button"
            accessibilityLabel="Go Back"
            style={({ pressed }) => [styles.backButton, { opacity: pressed ? 0.7 : 1 }]}
            onPress={() => router.replace('/(app)')}>
            <Text style={[styles.backButtonText, { color: themeColors.primary }]}>
              ← Home
            </Text>
          </Pressable>
        </View>

        {/* Loading State */}
        {isLoading && (
          <View testID="profile-loading" style={styles.stateContainer}>
            <ActivityIndicator size="large" color={themeColors.primary} />
            <Text style={[styles.stateText, { color: subtextColor }]}>Loading student profile...</Text>
          </View>
        )}

        {/* Error State */}
        {!isLoading && loadError && (
          <View testID="profile-error" style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
            <Text style={[styles.errorTitle, { color: themeColors.dangerText }]}>
              Unable to Load Profile
            </Text>
            <Text style={[styles.errorBannerText, { color: subtextColor }]}>{loadError}</Text>
            <Pressable
              testID="retry-button"
              accessibilityRole="button"
              accessibilityLabel="Retry loading profile"
              style={[styles.primaryButton, { backgroundColor: themeColors.primary }]}
              onPress={fetchProfile}>
              <Text style={styles.primaryButtonText}>Retry</Text>
            </Pressable>
          </View>
        )}

        {/* Empty State */}
        {!isLoading && !loadError && !profile && (
          <View testID="profile-empty" style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
            <Text style={[styles.sectionTitle, { color: textColor }]}>No Student Profile</Text>
            <Text style={[styles.subtitle, { color: subtextColor }]}>
              You have not set up your Student Profile yet.
            </Text>
            <Pressable
              testID="create-profile-button"
              accessibilityRole="button"
              accessibilityLabel="Create Student Profile"
              style={[styles.primaryButton, { backgroundColor: themeColors.primary }]}
              onPress={() => router.replace('/(onboarding)/student')}>
              <Text style={styles.primaryButtonText}>Complete Setup</Text>
            </Pressable>
          </View>
        )}

        {/* Content State: View Mode */}
        {!isLoading && !loadError && profile && !isEditing && (
          <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
            <View style={styles.cardHeader}>
              <View>
                <Text style={[styles.title, { color: textColor }]}>Student Profile</Text>
                <Text style={[styles.subtitle, { color: subtextColor }]}>
                  Your personal fitness baseline and preferences
                </Text>
              </View>
              <Pressable
                testID="edit-button"
                accessibilityRole="button"
                accessibilityLabel="Edit Profile"
                style={[styles.editButton, { borderColor }]}
                onPress={handleStartEdit}>
                <Text style={[styles.editButtonText, { color: themeColors.primary }]}>
                  Edit
                </Text>
              </Pressable>
            </View>

            {successMessage && (
              <View style={styles.successBanner} accessibilityRole="alert">
                <Text style={styles.successBannerText}>{successMessage}</Text>
              </View>
            )}

            <View style={styles.row}>
              <Text style={[styles.label, { color: subtextColor }]}>Date of Birth</Text>
              <Text testID="view-dob" style={[styles.value, { color: textColor }]}>
                {profile.dateOfBirth ?? 'Not provided'}
              </Text>
            </View>

            <View style={styles.row}>
              <Text style={[styles.label, { color: subtextColor }]}>Gender</Text>
              <Text testID="view-gender" style={[styles.value, { color: textColor }]}>
                {profile.gender ?? 'Not provided'}
              </Text>
            </View>

            <View style={styles.row}>
              <Text style={[styles.label, { color: subtextColor }]}>Experience Level</Text>
              <Text testID="view-level" style={[styles.value, { color: textColor }]}>
                {profile.trainingExperienceLevel ?? 'Not provided'}
              </Text>
            </View>

            <View style={styles.row}>
              <Text style={[styles.label, { color: subtextColor }]}>Experience Months</Text>
              <Text testID="view-months" style={[styles.value, { color: textColor }]}>
                {profile.trainingExperienceMonths != null
                  ? `${profile.trainingExperienceMonths} months`
                  : 'Not provided'}
              </Text>
            </View>

            <View style={styles.row}>
              <Text style={[styles.label, { color: subtextColor }]}>Available Days / Week</Text>
              <Text testID="view-days" style={[styles.value, { color: textColor }]}>
                {profile.availableDaysPerWeek != null
                  ? `${profile.availableDaysPerWeek} days`
                  : 'Not provided'}
              </Text>
            </View>

            <View style={styles.row}>
              <Text style={[styles.label, { color: subtextColor }]}>Session Duration</Text>
              <Text testID="view-minutes" style={[styles.value, { color: textColor }]}>
                {profile.preferredSessionMinutes != null
                  ? `${profile.preferredSessionMinutes} minutes`
                  : 'Not provided'}
              </Text>
            </View>

            <View style={styles.row}>
              <Text style={[styles.label, { color: subtextColor }]}>Onboarding Status</Text>
              <View style={styles.badge}>
                <Text style={styles.badgeText}>
                  {profile.onboardingCompleted ? 'COMPLETED' : 'PENDING'}
                </Text>
              </View>
            </View>
          </View>
        )}

        {/* Content State: Edit Mode */}
        {!isLoading && !loadError && profile && isEditing && (
          <View style={[styles.card, { backgroundColor: cardBg, borderColor }]}>
            <View style={styles.cardHeader}>
              <View>
                <Text style={[styles.title, { color: textColor }]}>Edit Student Profile</Text>
                <Text style={[styles.subtitle, { color: subtextColor }]}>
                  Update only the fields you wish to change
                </Text>
              </View>
            </View>

            {submitError && (
              <View style={styles.errorBanner} accessibilityRole="alert">
                <Text style={styles.errorBannerText}>{submitError}</Text>
              </View>
            )}

            {/* Date of Birth Input */}
            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: textColor }]}>Date of Birth</Text>
              <Text style={[styles.helperText, { color: subtextColor }]}>Format: YYYY-MM-DD</Text>
              <TextInput
                testID="edit-dob-input"
                accessibilityLabel="Date of Birth"
                style={[
                  styles.input,
                  {
                    color: textColor,
                    borderColor: fieldErrors.dateOfBirth ? themeColors.dangerText : borderColor,
                  },
                ]}
                placeholder="YYYY-MM-DD"
                placeholderTextColor={subtextColor}
                value={editDob}
                onChangeText={setEditDob}
                maxLength={10}
                editable={!isSubmitting}
              />
              {fieldErrors.dateOfBirth && (
                <Text style={styles.fieldError}>{fieldErrors.dateOfBirth}</Text>
              )}
            </View>

            {/* Gender Input */}
            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: textColor }]}>Gender</Text>
              <View style={styles.chipsContainer}>
                {GENDER_OPTIONS.map((opt) => {
                  const isSelected = editGender === opt.value;
                  return (
                    <Pressable
                      key={opt.value}
                      testID={`edit-gender-chip-${opt.value}`}
                      accessibilityRole="button"
                      accessibilityLabel={opt.label}
                      accessibilityState={{ selected: isSelected }}
                      disabled={isSubmitting}
                      onPress={() => setEditGender((prev) => (prev === opt.value ? null : opt.value))}
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

            {/* Training Experience Level */}
            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: textColor }]}>Experience Level</Text>
              <View style={styles.chipsContainer}>
                {EXPERIENCE_OPTIONS.map((opt) => {
                  const isSelected = editLevel === opt.value;
                  return (
                    <Pressable
                      key={opt.value}
                      testID={`edit-experience-chip-${opt.value}`}
                      accessibilityRole="button"
                      accessibilityLabel={opt.label}
                      accessibilityState={{ selected: isSelected }}
                      disabled={isSubmitting}
                      onPress={() => setEditLevel((prev) => (prev === opt.value ? null : opt.value))}
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

            {/* Training Experience Months */}
            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: textColor }]}>Experience Months</Text>
              <TextInput
                testID="edit-months-input"
                accessibilityLabel="Training Experience in Months"
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
                value={editMonths}
                onChangeText={setEditMonths}
                keyboardType="numeric"
                editable={!isSubmitting}
              />
              {fieldErrors.trainingExperienceMonths && (
                <Text style={styles.fieldError}>{fieldErrors.trainingExperienceMonths}</Text>
              )}
            </View>

            {/* Available Days Per Week */}
            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: textColor }]}>Available Days Per Week</Text>
              <View style={styles.chipsContainer}>
                {DAY_OPTIONS.map((day) => {
                  const isSelected = editDays === day;
                  return (
                    <Pressable
                      key={day}
                      testID={`edit-days-chip-${day}`}
                      accessibilityRole="button"
                      accessibilityLabel={`${day} days`}
                      accessibilityState={{ selected: isSelected }}
                      disabled={isSubmitting}
                      onPress={() => setEditDays((prev) => (prev === day ? null : day))}
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

            {/* Preferred Session Minutes */}
            <View style={styles.inputGroup}>
              <Text style={[styles.label, { color: textColor }]}>Session Duration (Minutes)</Text>
              <View style={styles.chipsContainer}>
                {DURATION_PRESETS.map((preset) => {
                  const isSelected = editMinutes === preset.toString();
                  return (
                    <Pressable
                      key={preset}
                      testID={`edit-duration-chip-${preset}`}
                      accessibilityRole="button"
                      accessibilityLabel={`${preset} minutes`}
                      accessibilityState={{ selected: isSelected }}
                      disabled={isSubmitting}
                      onPress={() => setEditMinutes(preset.toString())}
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
                testID="edit-duration-input"
                accessibilityLabel="Preferred Session Duration in Minutes"
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
                placeholder="Minutes (5 to 480)"
                placeholderTextColor={subtextColor}
                value={editMinutes}
                onChangeText={setEditMinutes}
                keyboardType="numeric"
                editable={!isSubmitting}
              />
              {fieldErrors.preferredSessionMinutes && (
                <Text style={styles.fieldError}>{fieldErrors.preferredSessionMinutes}</Text>
              )}
            </View>

            {/* Save & Cancel Actions */}
            <Pressable
              testID="save-button"
              accessibilityRole="button"
              accessibilityLabel="Save Changes"
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
              onPress={handleSaveChanges}
              disabled={isSubmitting}>
              {isSubmitting ? (
                <ActivityIndicator color={themeColors.textOnPrimary} />
              ) : (
                <Text style={styles.primaryButtonText}>Save Changes</Text>
              )}
            </Pressable>

            <Pressable
              testID="cancel-button"
              accessibilityRole="button"
              accessibilityLabel="Cancel Editing"
              disabled={isSubmitting}
              style={[styles.secondaryButton, { borderColor }]}
              onPress={handleCancelEdit}>
              <Text style={[styles.secondaryButtonText, { color: textColor }]}>Cancel</Text>
            </Pressable>
          </View>
        )}
      </ScrollView>
    </KeyboardAvoidingView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  scrollContent: {
    padding: layout.mobileScreenPadding,
    gap: spacing.lg,
  },
  navBar: {
    flexDirection: 'row',
    alignItems: 'center',
    paddingVertical: spacing.xs,
  },
  backButton: {
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
  },
  backButtonText: {
    ...typography.label,
  },
  stateContainer: {
    padding: spacing.xxl,
    alignItems: 'center',
    gap: spacing.md,
  },
  stateText: {
    ...typography.body,
  },
  card: {
    borderRadius: radius.lg,
    padding: spacing.lg,
    borderWidth: 1,
    gap: spacing.md,
  },
  cardHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'flex-start',
    marginBottom: spacing.xs,
  },
  title: {
    ...typography.h2,
  },
  subtitle: {
    ...typography.bodySmall,
    marginTop: spacing.xxs,
  },
  sectionTitle: {
    ...typography.h3,
  },
  row: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    paddingVertical: spacing.xxs,
  },
  label: {
    ...typography.bodySmall,
  },
  value: {
    ...typography.label,
  },
  badge: {
    backgroundColor: colors.brand[50],
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.full,
  },
  badgeText: {
    ...typography.caption,
    color: colors.brand[700],
    fontWeight: '600',
  },
  editButton: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
  },
  editButtonText: {
    ...typography.label,
  },
  inputGroup: {
    gap: spacing.xs,
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
  errorTitle: {
    ...typography.h3,
    marginBottom: spacing.xs,
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
  successBanner: {
    backgroundColor: colors.brand[50],
    borderWidth: 1,
    borderColor: colors.brand[100],
    borderRadius: radius.md,
    padding: spacing.md,
  },
  successBannerText: {
    ...typography.bodySmall,
    color: colors.brand[700],
    fontWeight: '600',
  },
});
