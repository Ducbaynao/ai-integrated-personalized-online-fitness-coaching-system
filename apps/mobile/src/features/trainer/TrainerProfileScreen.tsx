import React, { useCallback, useEffect, useRef, useState } from 'react';
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
import * as DocumentPicker from 'expo-document-picker';
import { useAuth } from '@/features/auth/AuthContext';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { trainerApplicationApi } from '@/services/trainerApplicationApi';
import { ApiError } from '@/types/auth';
import {
  TrainerApplicationResponse,
  TrainerDocumentUploadResponse,
  TrainerProfileResponse,
  UpdateTrainerProfileRequest,
} from '@/types/trainer';
import { validateTrainerProfileForm } from './trainerValidation';
import { colors, getSemanticColors, semanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';

export function TrainerProfileScreen() {
  const router = useRouter();
  const { user } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const canCoach = Boolean(user?.capabilities?.canCoach);

  const [profile, setProfile] = useState<TrainerProfileResponse | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Edit mode state
  const [isEditing, setIsEditing] = useState(false);
  const [editSlug, setEditSlug] = useState('');
  const [editBio, setEditBio] = useState('');
  const [editYears, setEditYears] = useState('');
  const [editAccepting, setEditAccepting] = useState(false);

  const isSubmittingRef = useRef(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Application state
  const [application, setApplication] = useState<TrainerApplicationResponse | null>(null);
  const [applicantNote, setApplicantNote] = useState('');
  const [isSubmittingApp, setIsSubmittingApp] = useState(false);
  const [appError, setAppError] = useState<string | null>(null);
  const [appSuccess, setAppSuccess] = useState<string | null>(null);
  const [uploadedDocs, setUploadedDocs] = useState<TrainerDocumentUploadResponse[]>([]);
  const [isUploadingDoc, setIsUploadingDoc] = useState(false);
  const [docUploadError, setDocUploadError] = useState<string | null>(null);

  const handlePickAndUploadDocument = async () => {
    if (isUploadingDoc) return;
    setDocUploadError(null);
    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ['application/pdf', 'image/png', 'image/jpeg'],
        copyToCacheDirectory: true,
      });

      if (result.canceled || !result.assets || result.assets.length === 0) {
        return;
      }

      const asset = result.assets[0];
      if (asset.size && asset.size > 10 * 1024 * 1024) {
        setDocUploadError('File size exceeds the 10 MB limit.');
        return;
      }

      setIsUploadingDoc(true);
      const mime = asset.mimeType || (asset.name.endsWith('.pdf') ? 'application/pdf' : 'image/jpeg');
      const uploaded = await trainerApplicationApi.uploadDocument(asset.uri, asset.name, mime);
      setUploadedDocs((prev) => [...prev, uploaded]);
    } catch (err) {
      if (err instanceof ApiError) {
        setDocUploadError(err.message || 'Failed to upload document.');
      } else {
        setDocUploadError('Failed to select or upload document.');
      }
    } finally {
      setIsUploadingDoc(false);
    }
  };

  const handleRemoveDocument = (docId: string) => {
    setUploadedDocs((prev) => prev.filter((d) => d.id !== docId));
  };

  const populateForm = useCallback((data: TrainerProfileResponse) => {
    setEditSlug(data.publicSlug ?? '');
    setEditBio(data.bio ?? '');
    setEditYears(data.yearsExperience != null ? data.yearsExperience.toString() : '');
    setEditAccepting(Boolean(data.acceptingStudents));
  }, []);

  const fetchApplication = useCallback(async () => {
    try {
      const app = await trainerApplicationApi.getCurrentApplication();
      setApplication(app);
    } catch {
      setApplication(null);
    }
  }, []);

  const fetchProfile = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    try {
      const data = await trainerProfileApi.getMyTrainerProfile();
      setProfile(data);
      populateForm(data);
      if (data.verificationStatus !== 'NOT_SUBMITTED') {
        await fetchApplication();
      }
    } catch (err) {
      if (err instanceof ApiError) {
        setLoadError(err.message || 'Failed to load Trainer Profile.');
      } else {
        setLoadError('Network error. Unable to load profile.');
      }
    } finally {
      setIsLoading(false);
    }
  }, [populateForm, fetchApplication]);

  useEffect(() => {
    let isMounted = true;
    async function load() {
      try {
        const data = await trainerProfileApi.getMyTrainerProfile();
        if (isMounted) {
          setProfile(data);
          populateForm(data);
          if (data.verificationStatus !== 'NOT_SUBMITTED') {
            try {
              const app = await trainerApplicationApi.getCurrentApplication();
              if (isMounted) {
                setApplication(app);
              }
            } catch {
              if (isMounted) {
                setApplication(null);
              }
            }
          }
          setIsLoading(false);
        }
      } catch (err) {
        if (isMounted) {
          if (err instanceof ApiError) {
            setLoadError(err.message || 'Failed to load Trainer Profile.');
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

  const handleSubmitApplication = async () => {
    if (isSubmittingApp || isUploadingDoc) return;
    setIsSubmittingApp(true);
    setAppError(null);
    setAppSuccess(null);
    try {
      const res = await trainerApplicationApi.submitApplication({
        applicantNote: applicantNote.trim() || undefined,
        documentMediaIds: uploadedDocs.length > 0 ? uploadedDocs.map((d) => d.id) : undefined,
      });
      setApplication(res);
      setApplicantNote('');
      setUploadedDocs([]);
      const isResubmit = profile?.verificationStatus === 'REJECTED';
      setAppSuccess(
        isResubmit
          ? 'Application resubmitted successfully. It is now pending review.'
          : 'Application submitted successfully. It is now pending review.'
      );
      const updatedProfile = await trainerProfileApi.getMyTrainerProfile();
      setProfile(updatedProfile);
    } catch (err) {
      if (err instanceof ApiError) {
        setAppError(err.message || 'Failed to submit application.');
      } else {
        setAppError('Network error. Unable to submit application.');
      }
    } finally {
      setIsSubmittingApp(false);
    }
  };

  const startEditing = () => {
    if (profile) {
      populateForm(profile);
    }
    setFieldErrors({});
    setSubmitError(null);
    setSuccessMessage(null);
    setIsEditing(true);
  };

  const cancelEditing = () => {
    if (profile) {
      populateForm(profile);
    }
    setFieldErrors({});
    setSubmitError(null);
    setIsEditing(false);
  };

  const handleSave = async () => {
    if (isSubmittingRef.current || !profile) return;

    setSubmitError(null);
    setFieldErrors({});
    setSuccessMessage(null);

    const clientErrors = validateTrainerProfileForm({
      publicSlug: editSlug,
      bio: editBio,
      yearsExperience: editYears,
      acceptingStudents: editAccepting,
    });

    const errorMap: Record<string, string> = {};
    Object.entries(clientErrors).forEach(([k, v]) => {
      if (v) errorMap[k] = v;
    });

    if (Object.keys(errorMap).length > 0) {
      setFieldErrors(errorMap);
      return;
    }

    // Build diff payload
    const payload: UpdateTrainerProfileRequest = {};
    let hasChanges = false;

    const trimmedSlug = editSlug.trim();
    const origSlug = profile.publicSlug ?? '';
    if (trimmedSlug !== origSlug) {
      payload.publicSlug = trimmedSlug === '' ? null : trimmedSlug;
      hasChanges = true;
    }

    const trimmedBio = editBio.trim();
    const origBio = profile.bio ?? '';
    if (trimmedBio !== origBio) {
      payload.bio = trimmedBio === '' ? null : trimmedBio;
      hasChanges = true;
    }

    const trimmedYears = editYears.trim();
    const origYears = profile.yearsExperience != null ? profile.yearsExperience.toString() : '';
    if (trimmedYears !== origYears) {
      payload.yearsExperience = trimmedYears === '' ? null : Number(trimmedYears);
      hasChanges = true;
    }

    if (editAccepting !== profile.acceptingStudents) {
      payload.acceptingStudents = editAccepting;
      hasChanges = true;
    }

    if (!hasChanges) {
      setIsEditing(false);
      return;
    }

    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const updated = await trainerProfileApi.updateMyTrainerProfile(payload);
      setProfile(updated);
      populateForm(updated);
      setIsEditing(false);
      setSuccessMessage('Trainer Profile updated successfully.');
    } catch (err) {
      if (err instanceof ApiError) {
        if (err.status === 409 && err.errorResponse?.errorCode === 'TRAINER_SLUG_ALREADY_EXISTS') {
          setFieldErrors({
            publicSlug: 'This public handle is already in use. Please choose another.',
          });
          return;
        }

        if (err.errorResponse?.fieldErrors && err.errorResponse.fieldErrors.length > 0) {
          const apiMap: Record<string, string> = {};
          err.errorResponse.fieldErrors.forEach((fe) => {
            apiMap[fe.field] = fe.message;
          });
          setFieldErrors(apiMap);
        } else {
          setSubmitError(err.message || 'Failed to update Trainer Profile.');
        }
      } else {
        setSubmitError('Network error. Please try again.');
      }
    } finally {
      isSubmittingRef.current = false;
      setIsSubmitting(false);
    }
  };

  if (isLoading) {
    return (
      <View
        testID="trainer-profile-loading"
        style={[styles.centerContainer, { backgroundColor: themeColors.canvas }]}>
        <ActivityIndicator size="large" color={themeColors.primary} />
        <Text style={[styles.loadingText, { color: themeColors.textSecondary }]}>
          Loading Trainer Profile...
        </Text>
      </View>
    );
  }

  if (loadError) {
    return (
      <View
        testID="trainer-profile-load-error"
        style={[styles.centerContainer, { backgroundColor: themeColors.canvas }]}>
        <Text style={[styles.errorTitle, { color: themeColors.dangerText }]}>
          Unable to Load Profile
        </Text>
        <Text style={[styles.errorSubtitle, { color: themeColors.textSecondary }]}>
          {loadError}
        </Text>
        <Pressable
          testID="trainer-profile-retry-button"
          accessibilityRole="button"
          accessibilityLabel="Retry loading trainer profile"
          onPress={fetchProfile}
          style={[styles.primaryButton, { backgroundColor: themeColors.primary, marginTop: spacing.md }]}>
          <Text style={styles.primaryButtonText}>Retry</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      <ScrollView
        testID="trainer-profile-screen"
        contentContainerStyle={styles.scrollContent}
        keyboardShouldPersistTaps="handled">
        {/* Navigation Bar */}
        <View style={styles.navBar}>
          <Pressable
            testID="trainer-profile-back-button"
            accessibilityRole="button"
            accessibilityLabel="Go back to home"
            onPress={() => router.replace('/(app)')}
            style={styles.backButton}>
            <Text style={[styles.backButtonText, { color: themeColors.primary }]}>
              ← Back to Overview
            </Text>
          </Pressable>

          {!isEditing ? (
            <Pressable
              testID="trainer-profile-edit-button"
              accessibilityRole="button"
              accessibilityLabel="Edit Trainer Profile"
              onPress={startEditing}
              style={[styles.editButton, { borderColor: themeColors.border }]}>
              <Text style={[styles.editButtonText, { color: themeColors.primary }]}>Edit</Text>
            </Pressable>
          ) : (
            <View style={styles.editActions}>
              <Pressable
                testID="trainer-profile-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Cancel editing"
                disabled={isSubmitting}
                onPress={cancelEditing}
                style={styles.cancelButton}>
                <Text style={[styles.cancelButtonText, { color: themeColors.textSecondary }]}>
                  Cancel
                </Text>
              </Pressable>
              <Pressable
                testID="trainer-profile-save-button"
                accessibilityRole="button"
                accessibilityLabel="Save Trainer Profile changes"
                disabled={isSubmitting}
                onPress={handleSave}
                style={[
                  styles.saveButton,
                  { backgroundColor: themeColors.primary, opacity: isSubmitting ? 0.7 : 1 },
                ]}>
                {isSubmitting ? (
                  <ActivityIndicator size="small" color="#ffffff" />
                ) : (
                  <Text style={styles.saveButtonText}>Save</Text>
                )}
              </Pressable>
            </View>
          )}
        </View>

        <View style={styles.header}>
          <Text style={[styles.title, { color: themeColors.textPrimary }]}>Trainer Profile</Text>
          <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
            Professional details and coaching eligibility
          </Text>
        </View>

        {successMessage && (
          <View
            testID="trainer-profile-success-banner"
            accessibilityRole="alert"
            style={[
              styles.banner,
              {
                backgroundColor: themeColors.successSurface,
                borderColor: themeColors.successText,
              },
            ]}>
            <Text
              style={[
                styles.bannerText,
                { color: themeColors.successText },
              ]}>
              {successMessage}
            </Text>
          </View>
        )}

        {submitError && (
          <View
            testID="trainer-profile-error-banner"
            accessibilityRole="alert"
            style={[
              styles.banner,
              {
                backgroundColor: themeColors.dangerSurface,
                borderColor: themeColors.dangerText,
              },
            ]}>
            <Text
              style={[
                styles.bannerText,
                { color: themeColors.dangerText },
              ]}>
              {submitError}
            </Text>
          </View>
        )}

        {/* Status Section */}
        <View
          style={[
            styles.sectionCard,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
            },
          ]}>
          <Text style={[styles.sectionTitle, { color: themeColors.textPrimary }]}>
            Status & Governance
          </Text>

          {/* Verification Status */}
          <View style={styles.statusRow}>
            <Text style={[styles.statusLabel, { color: themeColors.textSecondary }]}>
              Verification Status
            </Text>
            <View
              testID="trainer-profile-verification-status"
              style={[
                styles.badge,
                {
                  backgroundColor:
                    profile?.verificationStatus === 'VERIFIED'
                      ? themeColors.successSurface
                      : profile?.verificationStatus === 'PENDING'
                      ? themeColors.warningSurface
                      : colors.neutral[100],
                },
              ]}>
              <Text
                style={[
                  styles.badgeText,
                  {
                    color:
                      profile?.verificationStatus === 'VERIFIED'
                        ? themeColors.successText
                        : profile?.verificationStatus === 'PENDING'
                        ? themeColors.warningText
                        : colors.neutral[700],
                  },
                ]}>
                {profile?.verificationStatus ?? 'NOT_SUBMITTED'}
              </Text>
            </View>
          </View>

          {/* Activity Status */}
          <View style={styles.statusRow}>
            <Text style={[styles.statusLabel, { color: themeColors.textSecondary }]}>
              Activity Status
            </Text>
            <View
              style={[
                styles.badge,
                {
                  backgroundColor:
                    profile?.activityStatus === 'ACTIVE'
                      ? themeColors.successSurface
                      : colors.neutral[100],
                },
              ]}>
              <Text
                style={[
                  styles.badgeText,
                  {
                    color:
                      profile?.activityStatus === 'ACTIVE'
                        ? themeColors.successText
                        : colors.neutral[700],
                  },
                ]}>
                {profile?.activityStatus ?? 'INACTIVE'}
              </Text>
            </View>
          </View>

          {/* Coaching Eligibility */}
          <View style={styles.statusRow}>
            <Text style={[styles.statusLabel, { color: themeColors.textSecondary }]}>
              Coaching Eligibility
            </Text>
            <View
              testID="trainer-profile-coaching-eligibility"
              style={[
                styles.badge,
                {
                  backgroundColor:
                    profile?.coachingEligibility?.eligible
                      ? themeColors.successSurface
                      : themeColors.warningSurface,
                },
              ]}>
              <Text
                style={[
                  styles.badgeText,
                  {
                    color:
                      profile?.coachingEligibility?.eligible
                        ? themeColors.successText
                        : themeColors.warningText,
                  },
                ]}>
                {profile?.coachingEligibility?.eligible ? 'ELIGIBLE' : 'INELIGIBLE'}
              </Text>
            </View>
          </View>

          {/* Coaching Authority Invariant */}
          <View
            testID="trainer-profile-coaching-authority"
            style={[
              styles.authorityCard,
              {
                backgroundColor: themeColors.surfaceSubtle,
                borderColor: themeColors.border,
              },
            ]}>
            <Text style={[styles.authorityTitle, { color: themeColors.textPrimary }]}>
              Coaching Authority: {canCoach ? 'Authorized' : 'Unavailable (Verification required)'}
            </Text>
            <Text style={[styles.authorityDetail, { color: themeColors.textSecondary }]}>
              {canCoach
                ? 'Coaching authority is granted. You are authorized to accept and coach clients.'
                : 'Coaching authority is derived from platform verification and administration review. It remains disabled until all policy and credential requirements are approved.'}
              {!profile?.coachingEligibility?.eligible && profile?.coachingEligibility?.blockingReasons?.length
                ? ` Current blocking reasons: ${profile.coachingEligibility.blockingReasons.join(', ')}.`
                : ''}
            </Text>
          </View>
        </View>

        {/* Verification Application Card */}
        <View
          style={[
            styles.sectionCard,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
            },
          ]}>
          <Text style={[styles.sectionTitle, { color: themeColors.textPrimary }]}>
            Verification Application
          </Text>

          {appSuccess && (
            <View
              testID="trainer-application-success-banner"
              accessibilityRole="alert"
              style={[
                styles.banner,
                {
                  backgroundColor: themeColors.successSurface,
                  borderColor: themeColors.successText,
                  marginBottom: spacing.sm,
                },
              ]}>
              <Text style={[styles.bannerText, { color: themeColors.successText }]}>
                {appSuccess}
              </Text>
            </View>
          )}

          {appError && (
            <View
              testID="trainer-application-error-banner"
              accessibilityRole="alert"
              style={[
                styles.banner,
                {
                  backgroundColor: themeColors.dangerSurface,
                  borderColor: themeColors.dangerText,
                  marginBottom: spacing.sm,
                },
              ]}>
              <Text style={[styles.bannerText, { color: themeColors.dangerText }]}>
                {appError}
              </Text>
            </View>
          )}

          {profile?.verificationStatus === 'NOT_SUBMITTED' && (
            <View testID="trainer-application-not-submitted-card" style={styles.appStateContainer}>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                You have not submitted a verification application yet. Submit your application and
                qualifications to become eligible to coach students.
              </Text>
              <View style={styles.fieldGroup}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Application Notes / Qualifications
                </Text>
                <TextInput
                  testID="trainer-application-note-input"
                  accessibilityLabel="Application notes or qualifications"
                  style={[
                    styles.textArea,
                    {
                      borderColor: themeColors.border,
                      backgroundColor: themeColors.canvas,
                      color: themeColors.textPrimary,
                    },
                  ]}
                  value={applicantNote}
                  onChangeText={setApplicantNote}
                  placeholder="Enter details about your coaching credentials, experience, or certifications..."
                  placeholderTextColor={themeColors.textSecondary}
                  multiline
                  numberOfLines={4}
                  maxLength={2000}
                />
              </View>

              <View style={[styles.fieldGroup, { marginTop: spacing.sm }]}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Verification Documents (PDF, PNG, JPEG)
                </Text>
                <Pressable
                  testID="trainer-document-upload-button"
                  accessibilityRole="button"
                  accessibilityLabel="Attach verification document"
                  disabled={isUploadingDoc || isSubmittingApp}
                  onPress={handlePickAndUploadDocument}
                  style={[
                    styles.uploadDocButton,
                    {
                      borderColor: themeColors.border,
                      backgroundColor: themeColors.canvas,
                      opacity: isUploadingDoc ? 0.6 : 1,
                    },
                  ]}>
                  {isUploadingDoc ? (
                    <ActivityIndicator size="small" color={themeColors.primary} />
                  ) : (
                    <Text style={[styles.uploadDocButtonText, { color: themeColors.primary }]}>
                      + Attach Document (Max 10MB)
                    </Text>
                  )}
                </Pressable>

                {docUploadError && (
                  <Text testID="trainer-document-upload-error" style={[styles.errorText, { color: themeColors.dangerText }]}>
                    {docUploadError}
                  </Text>
                )}

                {uploadedDocs.length > 0 && (
                  <View testID="trainer-uploaded-docs-list" style={styles.uploadedDocsList}>
                    {uploadedDocs.map((doc) => (
                      <View
                        key={doc.id}
                        testID={`uploaded-doc-item-${doc.id}`}
                        style={[
                          styles.uploadedDocItem,
                          { borderColor: themeColors.border, backgroundColor: themeColors.surface },
                        ]}>
                        <View style={{ flex: 1, marginRight: spacing.sm }}>
                          <Text
                            style={[styles.uploadedDocName, { color: themeColors.textPrimary }]}
                            numberOfLines={1}>
                            {doc.originalFilename}
                          </Text>
                          <Text style={[styles.uploadedDocStatus, { color: themeColors.successText }]}>
                            {doc.scanStatus === 'CLEAN' ? 'Scanned & Clean' : doc.scanStatus}
                          </Text>
                        </View>
                        <Pressable
                          testID={`remove-doc-${doc.id}`}
                          accessibilityRole="button"
                          accessibilityLabel={`Remove document ${doc.originalFilename}`}
                          disabled={isSubmittingApp}
                          onPress={() => handleRemoveDocument(doc.id)}
                          style={styles.removeDocButton}>
                          <Text style={[styles.removeDocText, { color: themeColors.dangerText }]}>✕</Text>
                        </Pressable>
                      </View>
                    ))}
                  </View>
                )}
              </View>

              <Pressable
                testID="trainer-application-submit-button"
                accessibilityRole="button"
                accessibilityLabel="Submit verification application"
                disabled={isSubmittingApp || isUploadingDoc}
                onPress={handleSubmitApplication}
                style={[
                  styles.primaryButton,
                  {
                    backgroundColor: themeColors.primary,
                    opacity: isSubmittingApp || isUploadingDoc ? 0.7 : 1,
                    marginTop: spacing.sm,
                  },
                ]}>
                {isSubmittingApp ? (
                  <ActivityIndicator size="small" color="#ffffff" />
                ) : (
                  <Text style={styles.primaryButtonText}>Submit Application</Text>
                )}
              </Pressable>
            </View>
          )}

          {profile?.verificationStatus === 'PENDING' && (
            <View testID="trainer-application-pending-banner" style={styles.appStateContainer}>
              <View
                style={[
                  styles.badge,
                  { backgroundColor: themeColors.warningSurface, alignSelf: 'flex-start', marginBottom: spacing.xs },
                ]}>
                <Text style={[styles.badgeText, { color: themeColors.warningText }]}>
                  APPLICATION UNDER REVIEW
                </Text>
              </View>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                Your verification application is currently under review by platform administrators.
                You will be notified once a decision has been made.
              </Text>
              {application?.submittedAt && (
                <Text style={[styles.timestampText, { color: themeColors.textSecondary }]}>
                  Submitted: {new Date(application.submittedAt).toLocaleDateString()}
                </Text>
              )}
            </View>
          )}

          {profile?.verificationStatus === 'REJECTED' && (
            <View testID="trainer-application-rejected-card" style={styles.appStateContainer}>
              <View
                testID="trainer-application-rejected-banner"
                style={[
                  styles.badge,
                  { backgroundColor: themeColors.dangerSurface, alignSelf: 'flex-start', marginBottom: spacing.xs },
                ]}>
                <Text style={[styles.badgeText, { color: themeColors.dangerText }]}>
                  APPLICATION REJECTED
                </Text>
              </View>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                Your previous application was rejected. Please review the reason below and resubmit
                with updated information.
              </Text>

              <View
                style={[
                  styles.rejectionReasonBox,
                  {
                    backgroundColor: themeColors.dangerSurface,
                    borderColor: themeColors.dangerText,
                  },
                ]}>
                <Text style={[styles.rejectionReasonLabel, { color: themeColors.dangerText }]}>
                  Rejection Reason:
                </Text>
                <Text
                  testID="trainer-application-rejection-reason"
                  style={[styles.rejectionReasonText, { color: themeColors.textPrimary }]}>
                  {application?.rejectionReason ?? 'Credentials did not satisfy platform requirements.'}
                </Text>
              </View>

              <View style={[styles.fieldGroup, { marginTop: spacing.md }]}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Updated Application Notes / Corrections
                </Text>
                <TextInput
                  testID="trainer-application-resubmit-note-input"
                  accessibilityLabel="Updated application notes or corrections"
                  style={[
                    styles.textArea,
                    {
                      borderColor: themeColors.border,
                      backgroundColor: themeColors.canvas,
                      color: themeColors.textPrimary,
                    },
                  ]}
                  value={applicantNote}
                  onChangeText={setApplicantNote}
                  placeholder="Explain how you addressed the rejection reasons..."
                  placeholderTextColor={themeColors.textSecondary}
                  multiline
                  numberOfLines={4}
                  maxLength={2000}
                />
              </View>

              <View style={[styles.fieldGroup, { marginTop: spacing.sm }]}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Updated Verification Documents (PDF, PNG, JPEG)
                </Text>
                <Pressable
                  testID="trainer-document-resubmit-upload-button"
                  accessibilityRole="button"
                  accessibilityLabel="Attach updated verification document"
                  disabled={isUploadingDoc || isSubmittingApp}
                  onPress={handlePickAndUploadDocument}
                  style={[
                    styles.uploadDocButton,
                    {
                      borderColor: themeColors.border,
                      backgroundColor: themeColors.canvas,
                      opacity: isUploadingDoc ? 0.6 : 1,
                    },
                  ]}>
                  {isUploadingDoc ? (
                    <ActivityIndicator size="small" color={themeColors.primary} />
                  ) : (
                    <Text style={[styles.uploadDocButtonText, { color: themeColors.primary }]}>
                      + Attach Document (Max 10MB)
                    </Text>
                  )}
                </Pressable>

                {docUploadError && (
                  <Text testID="trainer-document-upload-error" style={[styles.errorText, { color: themeColors.dangerText }]}>
                    {docUploadError}
                  </Text>
                )}

                {uploadedDocs.length > 0 && (
                  <View testID="trainer-resubmit-uploaded-docs-list" style={styles.uploadedDocsList}>
                    {uploadedDocs.map((doc) => (
                      <View
                        key={doc.id}
                        testID={`uploaded-doc-item-${doc.id}`}
                        style={[
                          styles.uploadedDocItem,
                          { borderColor: themeColors.border, backgroundColor: themeColors.surface },
                        ]}>
                        <View style={{ flex: 1, marginRight: spacing.sm }}>
                          <Text
                            style={[styles.uploadedDocName, { color: themeColors.textPrimary }]}
                            numberOfLines={1}>
                            {doc.originalFilename}
                          </Text>
                          <Text style={[styles.uploadedDocStatus, { color: themeColors.successText }]}>
                            {doc.scanStatus === 'CLEAN' ? 'Scanned & Clean' : doc.scanStatus}
                          </Text>
                        </View>
                        <Pressable
                          testID={`remove-doc-${doc.id}`}
                          accessibilityRole="button"
                          accessibilityLabel={`Remove document ${doc.originalFilename}`}
                          disabled={isSubmittingApp}
                          onPress={() => handleRemoveDocument(doc.id)}
                          style={styles.removeDocButton}>
                          <Text style={[styles.removeDocText, { color: themeColors.dangerText }]}>✕</Text>
                        </Pressable>
                      </View>
                    ))}
                  </View>
                )}
              </View>

              <Pressable
                testID="trainer-application-resubmit-button"
                accessibilityRole="button"
                accessibilityLabel="Resubmit verification application"
                disabled={isSubmittingApp || isUploadingDoc}
                onPress={handleSubmitApplication}
                style={[
                  styles.primaryButton,
                  {
                    backgroundColor: themeColors.primary,
                    opacity: isSubmittingApp || isUploadingDoc ? 0.7 : 1,
                    marginTop: spacing.sm,
                  },
                ]}>
                {isSubmittingApp ? (
                  <ActivityIndicator size="small" color="#ffffff" />
                ) : (
                  <Text style={styles.primaryButtonText}>Resubmit Application</Text>
                )}
              </Pressable>
            </View>
          )}

          {profile?.verificationStatus === 'VERIFIED' && (
            <View testID="trainer-application-verified-banner" style={styles.appStateContainer}>
              <View
                style={[
                  styles.badge,
                  { backgroundColor: themeColors.successSurface, alignSelf: 'flex-start', marginBottom: spacing.xs },
                ]}>
                <Text style={[styles.badgeText, { color: themeColors.successText }]}>
                  VERIFIED TRAINER
                </Text>
              </View>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                Your verification application has been approved. You are authorized to coach
                students when accepting students is enabled.
              </Text>
              {profile?.verifiedAt && (
                <Text style={[styles.timestampText, { color: themeColors.textSecondary }]}>
                  Verified on: {new Date(profile.verifiedAt).toLocaleDateString()}
                </Text>
              )}
            </View>
          )}
        </View>

        {/* Profile Information / Edit Form */}
        <View
          style={[
            styles.sectionCard,
            {
              backgroundColor: themeColors.surface,
              borderColor: themeColors.border,
            },
          ]}>
          <Text style={[styles.sectionTitle, { color: themeColors.textPrimary }]}>
            {isEditing ? 'Edit Profile Information' : 'Profile Details'}
          </Text>

          {isEditing ? (
            <View style={styles.formFields}>
              {/* Public Slug */}
              <View style={styles.fieldGroup}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Public Profile Handle
                </Text>
                <TextInput
                  testID="trainer-profile-slug-input"
                  accessibilityLabel="Public Profile Handle"
                  autoCapitalize="none"
                  autoCorrect={false}
                  placeholder="e.g. coach-alex"
                  placeholderTextColor={themeColors.textSecondary}
                  value={editSlug}
                  onChangeText={(val) => {
                    setEditSlug(val);
                    if (fieldErrors.publicSlug) {
                      setFieldErrors((prev) => ({ ...prev, publicSlug: '' }));
                    }
                  }}
                  style={[
                    styles.input,
                    {
                      backgroundColor: themeColors.surface,
                      borderColor: fieldErrors.publicSlug
                        ? themeColors.dangerText
                        : themeColors.border,
                      color: themeColors.textPrimary,
                    },
                  ]}
                />
                {fieldErrors.publicSlug ? (
                  <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                    {fieldErrors.publicSlug}
                  </Text>
                ) : null}
              </View>

              {/* Bio */}
              <View style={styles.fieldGroup}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Professional Bio
                </Text>
                <TextInput
                  testID="trainer-profile-bio-input"
                  accessibilityLabel="Professional Bio"
                  multiline
                  numberOfLines={4}
                  maxLength={4000}
                  placeholder="Tell clients about your coaching background..."
                  placeholderTextColor={themeColors.textSecondary}
                  value={editBio}
                  onChangeText={(val) => {
                    setEditBio(val);
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
                  <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                    {fieldErrors.bio}
                  </Text>
                ) : null}
              </View>

              {/* Years of Experience */}
              <View style={styles.fieldGroup}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Years of Coaching Experience
                </Text>
                <TextInput
                  testID="trainer-profile-experience-input"
                  accessibilityLabel="Years of Coaching Experience"
                  keyboardType="decimal-pad"
                  placeholder="e.g. 5"
                  placeholderTextColor={themeColors.textSecondary}
                  value={editYears}
                  onChangeText={(val) => {
                    setEditYears(val);
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
                  <Text style={[styles.errorText, { color: themeColors.dangerText }]}>
                    {fieldErrors.yearsExperience}
                  </Text>
                ) : null}
              </View>

              {/* Accepting Students Switch */}
              <View style={styles.switchRow}>
                <View style={styles.switchInfo}>
                  <Text style={[styles.switchLabel, { color: themeColors.textPrimary }]}>
                    Accepting New Students
                  </Text>
                  <Text style={[styles.switchDescription, { color: themeColors.textSecondary }]}>
                    Open to coaching assignments once verified.
                  </Text>
                </View>
                <Switch
                  testID="trainer-profile-accepting-switch"
                  accessibilityLabel="Accepting New Students"
                  value={editAccepting}
                  onValueChange={setEditAccepting}
                  trackColor={{ false: colors.neutral[300], true: themeColors.primary }}
                  thumbColor={Platform.OS === 'android' ? '#ffffff' : undefined}
                />
              </View>
            </View>
          ) : (
            <View style={styles.detailRows}>
              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Public Handle
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.publicSlug ? `@${profile.publicSlug}` : 'None specified'}
                </Text>
              </View>

              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Experience
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.yearsExperience != null
                    ? `${profile.yearsExperience} years`
                    : 'Not specified'}
                </Text>
              </View>

              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Accepting Students
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.acceptingStudents ? 'Yes' : 'No'}
                </Text>
              </View>

              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Bio
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.bio || 'No bio provided.'}
                </Text>
              </View>
            </View>
          )}
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
    paddingTop: spacing.lg,
    paddingBottom: spacing.xxl,
  },
  centerContainer: {
    flex: 1,
    alignItems: 'center',
    justifyContent: 'center',
    padding: spacing.xl,
  },
  loadingText: {
    marginTop: spacing.md,
    ...typography.body,
  },
  errorTitle: {
    ...typography.h3,
    marginBottom: spacing.xs,
  },
  errorSubtitle: {
    ...typography.bodySmall,
    textAlign: 'center',
    marginBottom: spacing.md,
  },
  navBar: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.md,
  },
  backButton: {
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
  },
  backButtonText: {
    ...typography.label,
  },
  editButton: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
    alignItems: 'center',
  },
  editButtonText: {
    ...typography.label,
  },
  editActions: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  cancelButton: {
    paddingHorizontal: spacing.sm,
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
  },
  cancelButtonText: {
    ...typography.bodySmall,
    fontWeight: '500',
  },
  saveButton: {
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    minHeight: layout.minimumTouchTarget,
    justifyContent: 'center',
    alignItems: 'center',
  },
  saveButtonText: {
    ...typography.label,
    color: semanticColors.textOnPrimary,
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
  banner: {
    borderRadius: radius.md,
    borderWidth: 1,
    padding: spacing.md,
    marginBottom: spacing.lg,
  },
  bannerText: {
    ...typography.bodySmall,
  },
  sectionCard: {
    borderRadius: radius.xl,
    borderWidth: 1,
    padding: spacing.lg,
    marginBottom: spacing.lg,
  },
  sectionTitle: {
    ...typography.label,
    fontSize: 16,
    marginBottom: spacing.md,
  },
  statusRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: spacing.md,
  },
  statusLabel: {
    ...typography.bodySmall,
  },
  badge: {
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.full,
  },
  badgeText: {
    ...typography.caption,
    fontWeight: '700',
  },
  authorityCard: {
    borderRadius: radius.lg,
    borderWidth: 1,
    padding: spacing.md,
    marginTop: spacing.xs,
  },
  authorityTitle: {
    ...typography.label,
    marginBottom: 4,
  },
  authorityDetail: {
    ...typography.caption,
    lineHeight: 16,
  },
  formFields: {
    gap: spacing.md,
  },
  fieldGroup: {
    gap: spacing.xs,
  },
  fieldLabel: {
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
    minHeight: 100,
    textAlignVertical: 'top',
  },
  errorText: {
    ...typography.caption,
    marginTop: 2,
  },
  switchRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingVertical: spacing.sm,
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
  detailRows: {
    gap: spacing.md,
  },
  detailItem: {
    gap: 2,
  },
  detailLabel: {
    ...typography.caption,
    textTransform: 'uppercase',
    letterSpacing: 0.5,
  },
  detailValue: {
    ...typography.body,
    lineHeight: 22,
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
  appStateContainer: {
    gap: spacing.xs,
  },
  appStateDescription: {
    ...typography.bodySmall,
    lineHeight: 20,
    marginBottom: spacing.xs,
  },
  timestampText: {
    ...typography.caption,
    marginTop: 2,
  },
  rejectionReasonBox: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    marginVertical: spacing.xs,
  },
  rejectionReasonLabel: {
    ...typography.label,
    marginBottom: 4,
  },
  rejectionReasonText: {
    ...typography.bodySmall,
    lineHeight: 20,
  },
  uploadDocButton: {
    borderWidth: 1,
    borderStyle: 'dashed',
    borderRadius: radius.md,
    paddingVertical: spacing.md,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
    minHeight: layout.minimumTouchTarget,
  },
  uploadDocButtonText: {
    ...typography.label,
  },
  uploadedDocsList: {
    marginTop: spacing.xs,
    gap: spacing.xs,
  },
  uploadedDocItem: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
  },
  uploadedDocName: {
    ...typography.bodySmall,
    fontWeight: '500',
  },
  uploadedDocStatus: {
    ...typography.caption,
  },
  removeDocButton: {
    padding: spacing.xs,
    minHeight: layout.minimumTouchTarget,
    minWidth: layout.minimumTouchTarget,
    alignItems: 'center',
    justifyContent: 'center',
  },
  removeDocText: {
    fontSize: 16,
    fontWeight: '700',
  },
});
