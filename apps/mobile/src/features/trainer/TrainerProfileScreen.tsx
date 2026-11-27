import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
  Platform,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleSheet,
  Switch,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { useRouter } from 'expo-router';
import { apiErrorMessage, coachingBlockingLabels, trainerActivityLabels, unspecified, verificationLabels } from '@/localization/displayLabels';
import { SafeAreaView } from 'react-native-safe-area-context';
import * as DocumentPicker from 'expo-document-picker';
import { useAuth } from '@/features/auth/AuthContext';
import { coachingQueryKeys } from '@/features/coaching/coachingQueries';
import { queryClient } from '@/services/queryClient';
import { AuthSessionBinding, getAuthSessionBinding } from '@/services/apiClient';
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
import { fontFamily, typography } from '@/design-system/tokens/typography';

export function TrainerProfileScreen() {
  const router = useRouter();
  const { user, activeCapability, refreshUser } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const themeColors = getSemanticColors(isDark);

  const canCoach = Boolean(user?.capabilities?.canCoach);
  const actorKey = `${user?.id ?? ''}:${activeCapability ?? ''}`;
  const currentBindingSnapshot = getAuthSessionBinding?.();
  const sessionFingerprint = `${actorKey}:${currentBindingSnapshot?.authEpoch ?? 0}:${currentBindingSnapshot?.capabilityEpoch ?? 0}`;

  const [profileState, setProfileState] = useState<{
    actorKey: string;
    data: TrainerProfileResponse;
  } | null>(null);
  const profile = profileState?.actorKey === actorKey ? profileState.data : null;
  const [isLoading, setIsLoading] = useState(true);
  const [isRefreshing, setIsRefreshing] = useState(false);
  const [loadError, setLoadError] = useState<string | null>(null);

  // Edit mode state
  const [isEditing, setIsEditing] = useState(false);
  const [editSlug, setEditSlug] = useState('');
  const [editBio, setEditBio] = useState('');
  const [editYears, setEditYears] = useState('');
  const [editAccepting, setEditAccepting] = useState(false);

  const isMountedRef = useRef(true);
  const refreshUserRef = useRef(refreshUser);
  const canCoachRef = useRef(canCoach);
  const actorKeyRef = useRef(actorKey);
  const actorEpochRef = useRef(0);
  const lastCommittedActorKeyRef = useRef(actorKey);
  const profileRef = useRef<TrainerProfileResponse | null>(profile);
  const isEditingRef = useRef(isEditing);
  const profileRequestGenRef = useRef(0);
  const activeRefreshGenRef = useRef(0);
  const editorGenerationRef = useRef(0);
  const uploadOpGenRef = useRef(0);
  const activeUploadRef = useRef<{
    opId: number;
    actorKey: string;
    actorEpoch: number;
    binding?: AuthSessionBinding;
  } | null>(null);

  useEffect(() => {
    refreshUserRef.current = refreshUser;
    canCoachRef.current = canCoach;
    profileRef.current = profile;
    isEditingRef.current = isEditing;
  }, [refreshUser, canCoach, profile, isEditing]);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      profileRequestGenRef.current += 1;
      uploadOpGenRef.current += 1;
      activeUploadRef.current = null;
    };
  }, []);

  const markDraftEdited = useCallback(() => {
    editorGenerationRef.current += 1;
  }, []);

  const isSubmittingRef = useRef(false);
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState<string | null>(null);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [successMessage, setSuccessMessage] = useState<string | null>(null);

  // Application state
  const [applicationState, setApplicationState] = useState<{
    actorKey: string;
    data: TrainerApplicationResponse | null;
  } | null>(null);
  const application = applicationState?.actorKey === actorKey ? applicationState.data : null;
  const [applicantNote, setApplicantNote] = useState('');
  const [isSubmittingApp, setIsSubmittingApp] = useState(false);
  const [appError, setAppError] = useState<string | null>(null);
  const [appSuccess, setAppSuccess] = useState<string | null>(null);
  const [uploadedDocsState, setUploadedDocsState] = useState<{
    fingerprint: string;
    items: TrainerDocumentUploadResponse[];
  }>(() => ({ fingerprint: sessionFingerprint, items: [] }));
  const [uploadingState, setUploadingState] = useState<{
    opId: number;
    fingerprint: string;
  } | null>(null);
  const [docUploadErrorState, setDocUploadErrorState] = useState<{
    fingerprint: string;
    message: string;
  } | null>(null);

  const uploadedDocs =
    uploadedDocsState.fingerprint === sessionFingerprint ? uploadedDocsState.items : [];
  const isUploadingDoc =
    uploadingState !== null && uploadingState.fingerprint === sessionFingerprint;
  const docUploadError =
    docUploadErrorState !== null && docUploadErrorState.fingerprint === sessionFingerprint
      ? docUploadErrorState.message
      : null;

  const isUploadContextCurrent = useCallback(
    (ctx: {
      opId: number;
      actorKey: string;
      actorEpoch: number;
      binding?: AuthSessionBinding;
    }) => {
      if (!isMountedRef.current) return false;
      if (uploadOpGenRef.current !== ctx.opId) return false;
      if (actorEpochRef.current !== ctx.actorEpoch) return false;
      if (actorKeyRef.current !== ctx.actorKey) return false;
      if (ctx.binding && getAuthSessionBinding) {
        const nowBinding = getAuthSessionBinding();
        if (
          nowBinding.authEpoch !== ctx.binding.authEpoch ||
          nowBinding.capabilityEpoch !== ctx.binding.capabilityEpoch
        ) {
          return false;
        }
      }
      return true;
    },
    [],
  );

  const handlePickAndUploadDocument = async () => {
    if (activeUploadRef.current && isUploadContextCurrent(activeUploadRef.current)) {
      return;
    }

    const opId = ++uploadOpGenRef.current;
    const initialBinding = getAuthSessionBinding?.();
    const startedActorKey = actorKeyRef.current;
    const startedActorEpoch = actorEpochRef.current;
    const startedFingerprint = `${startedActorKey}:${initialBinding?.authEpoch ?? 0}:${initialBinding?.capabilityEpoch ?? 0}`;
    const opCtx = {
      opId,
      actorKey: startedActorKey,
      actorEpoch: startedActorEpoch,
      binding: initialBinding,
    };
    const isCurrentOp = () => isUploadContextCurrent(opCtx);

    activeUploadRef.current = opCtx;
    setDocUploadErrorState(null);
    setUploadingState({ opId, fingerprint: startedFingerprint });

    try {
      const result = await DocumentPicker.getDocumentAsync({
        type: ['application/pdf', 'image/png', 'image/jpeg'],
        copyToCacheDirectory: true,
      });

      if (!isCurrentOp()) {
        return;
      }
      if (result.canceled || !result.assets || result.assets.length === 0) {
        return;
      }

      const asset = result.assets[0];
      if (asset.size && asset.size > 10 * 1024 * 1024) {
        setDocUploadErrorState({
          fingerprint: startedFingerprint,
          message: 'Tệp vượt quá giới hạn 10 MB.',
        });
        return;
      }

      const mime = asset.mimeType || (asset.name.endsWith('.pdf') ? 'application/pdf' : 'image/jpeg');
      const webFile = (asset as { file?: Blob }).file;
      const uploaded = await trainerApplicationApi.uploadDocument(
        asset.uri,
        asset.name,
        mime,
        webFile,
        {
          ...(initialBinding ? { sessionBinding: initialBinding } : {}),
          shouldContinue: isCurrentOp,
        },
      );
      if (!isCurrentOp()) {
        return;
      }
      setUploadedDocsState((prev) => ({
        fingerprint: startedFingerprint,
        items:
          prev.fingerprint === startedFingerprint
            ? [...prev.items, uploaded]
            : [uploaded],
      }));
    } catch (err) {
      if (!isCurrentOp()) {
        return;
      }
      setDocUploadErrorState({
        fingerprint: startedFingerprint,
        message:
          err instanceof ApiError
            ? apiErrorMessage(err, 'Không thể tải tài liệu lên.')
            : 'Không thể chọn hoặc tải tài liệu lên.',
      });
    } finally {
      if (isCurrentOp()) {
        activeUploadRef.current = null;
        setUploadingState((prev) => (prev?.opId === opId ? null : prev));
      }
    }
  };

  const handleRemoveDocument = (docId: string) => {
    setUploadedDocsState((prev) =>
      prev.fingerprint === sessionFingerprint
        ? { ...prev, items: prev.items.filter((d) => d.id !== docId) }
        : prev,
    );
  };

  const populateForm = useCallback((data: TrainerProfileResponse) => {
    setEditSlug(data.publicSlug ?? '');
    setEditBio(data.bio ?? '');
    setEditYears(data.yearsExperience != null ? data.yearsExperience.toString() : '');
    setEditAccepting(Boolean(data.acceptingStudents));
  }, []);

  const syncAuthAndCoachingCache = useCallback(async () => {
    try {
      await refreshUserRef.current?.();
    } catch {
      // Keep fetched trainer profile state even if /users/me transiently fails.
    }
    await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.all });
  }, []);

  const loadProfileState = useCallback(async (options?: {
    mode?: 'mount' | 'reload' | 'refresh' | 'sync';
    syncAuthMode?: 'always' | 'ifNeeded' | 'never';
  }): Promise<TrainerProfileResponse | null> => {
    const mode = options?.mode ?? 'reload';
    const syncAuthMode = options?.syncAuthMode ?? 'always';
    const requestGen = ++profileRequestGenRef.current;
    const startedActorKey = actorKeyRef.current;
    const startedEditorGen = editorGenerationRef.current;
    const isCurrentRequest = () =>
      isMountedRef.current &&
      requestGen === profileRequestGenRef.current &&
      startedActorKey === actorKeyRef.current;

    if (mode === 'refresh') {
      activeRefreshGenRef.current = requestGen;
    }

    try {
      const data = await trainerProfileApi.getMyTrainerProfile();
      if (!isCurrentRequest()) return null;

      if (lastCommittedActorKeyRef.current !== startedActorKey) {
        lastCommittedActorKeyRef.current = startedActorKey;
        const nowBinding = getAuthSessionBinding?.();
        setUploadedDocsState({
          fingerprint: `${startedActorKey}:${nowBinding?.authEpoch ?? 0}:${nowBinding?.capabilityEpoch ?? 0}`,
          items: [],
        });
        setApplicantNote('');
        isEditingRef.current = false;
        setIsEditing(false);
      }

      profileRef.current = data;
      setProfileState({ actorKey: startedActorKey, data });

      // Keep server profile updates separate from active/dirty editor drafts.
      if (!isEditingRef.current && editorGenerationRef.current === startedEditorGen) {
        populateForm(data);
      }

      if (data.verificationStatus !== 'NOT_SUBMITTED') {
        try {
          const app = await trainerApplicationApi.getCurrentApplication();
          if (!isCurrentRequest()) return null;
          setApplicationState({ actorKey: startedActorKey, data: app });
        } catch {
          if (!isCurrentRequest()) return null;
          setApplicationState({ actorKey: startedActorKey, data: null });
        }
      } else {
        setApplicationState({ actorKey: startedActorKey, data: null });
      }

      const needsSync =
        syncAuthMode === 'always' ||
        (syncAuthMode === 'ifNeeded' &&
          (Boolean(data.coachingEligibility?.eligible) !== canCoachRef.current ||
            data.verificationStatus === 'VERIFIED'));
      if (needsSync) {
        await syncAuthAndCoachingCache();
        if (!isCurrentRequest()) return null;
      }

      return data;
    } catch (err) {
      if (!isCurrentRequest()) return null;
      if (mode === 'refresh') {
        setSubmitError(
          err instanceof ApiError
            ? apiErrorMessage(err, 'Không thể làm mới hồ sơ huấn luyện viên.')
            : 'Không thể làm mới hồ sơ huấn luyện viên. Vui lòng thử lại.',
        );
      } else if (mode === 'mount' || mode === 'reload') {
        setLoadError(
          err instanceof ApiError
            ? apiErrorMessage(err, 'Không thể tải hồ sơ huấn luyện viên.')
            : 'Không thể tải hồ sơ huấn luyện viên. Vui lòng thử lại.',
        );
      }
      return null;
    } finally {
      if (isMountedRef.current && startedActorKey === actorKeyRef.current) {
        if ((mode === 'mount' || mode === 'reload') && requestGen === profileRequestGenRef.current) {
          setIsLoading(false);
        }
        if (mode === 'refresh' && requestGen === activeRefreshGenRef.current) {
          setIsRefreshing(false);
        }
      }
    }
  }, [populateForm, syncAuthAndCoachingCache]);

  const fetchProfile = useCallback(async () => {
    setIsLoading(true);
    setLoadError(null);
    await loadProfileState({ mode: 'reload', syncAuthMode: 'always' });
  }, [loadProfileState]);

  const handleRefresh = useCallback(async () => {
    if (isRefreshing) return;
    setIsRefreshing(true);
    setLoadError(null);
    setSubmitError(null);
    await loadProfileState({ mode: 'refresh', syncAuthMode: 'always' });
  }, [isRefreshing, loadProfileState]);

  useEffect(() => {
    actorKeyRef.current = actorKey;
    actorEpochRef.current += 1;
    uploadOpGenRef.current += 1;
    activeUploadRef.current = null;
    profileRef.current = null;
    isEditingRef.current = false;
    editorGenerationRef.current += 1;
    async function loadInitialProfile() {
      await loadProfileState({ mode: 'mount', syncAuthMode: 'ifNeeded' });
    }
    void loadInitialProfile();
  }, [actorKey, loadProfileState]);

  const handleSubmitApplication = async () => {
    if (isSubmittingApp || isUploadingDoc) return;
    const startedActorKey = actorKeyRef.current;
    const submitFingerprint = sessionFingerprint;
    setIsSubmittingApp(true);
    setAppError(null);
    setAppSuccess(null);
    try {
      const res = await trainerApplicationApi.submitApplication({
        applicantNote: applicantNote.trim() || undefined,
        documentMediaIds: uploadedDocs.length > 0 ? uploadedDocs.map((d) => d.id) : undefined,
      });
      if (!isMountedRef.current || actorKeyRef.current !== startedActorKey) return;
      setApplicationState({ actorKey: startedActorKey, data: res });
      setApplicantNote('');
      setUploadedDocsState({ fingerprint: submitFingerprint, items: [] });
      const isResubmit = profileRef.current?.verificationStatus === 'REJECTED';
      setAppSuccess(
        isResubmit
          ? 'Đã nộp lại đơn. Hồ sơ đang chờ xét duyệt.'
          : 'Đã nộp đơn. Hồ sơ đang chờ xét duyệt.'
      );
      const syncGen = ++profileRequestGenRef.current;
      const updatedProfile = await trainerProfileApi.getMyTrainerProfile();
      if (
        !isMountedRef.current ||
        actorKeyRef.current !== startedActorKey ||
        syncGen !== profileRequestGenRef.current
      ) {
        return;
      }
      profileRef.current = updatedProfile;
      setProfileState({ actorKey: startedActorKey, data: updatedProfile });
      await syncAuthAndCoachingCache();
    } catch (err) {
      if (!isMountedRef.current || actorKeyRef.current !== startedActorKey) return;
      if (err instanceof ApiError) {
        setAppError(apiErrorMessage(err, 'Không thể nộp đơn.'));
        if (err.status === 409 && err.errorResponse?.errorCode === 'TRAINER_APPLICATION_ALREADY_ACTIVE') {
          await loadProfileState({ mode: 'sync', syncAuthMode: 'always' });
        }
      } else {
        setAppError('Không thể nộp đơn. Vui lòng thử lại.');
      }
    } finally {
      if (isMountedRef.current) {
        setIsSubmittingApp(false);
      }
    }
  };

  const startEditing = () => {
    editorGenerationRef.current += 1;
    isEditingRef.current = true;
    if (profileRef.current) {
      populateForm(profileRef.current);
    }
    setFieldErrors({});
    setSubmitError(null);
    setSuccessMessage(null);
    setIsEditing(true);
  };

  const cancelEditing = () => {
    editorGenerationRef.current += 1;
    isEditingRef.current = false;
    if (profileRef.current) {
      populateForm(profileRef.current);
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

    const startedActorKey = actorKeyRef.current;
    isSubmittingRef.current = true;
    setIsSubmitting(true);

    try {
      const updated = await trainerProfileApi.updateMyTrainerProfile(payload);
      if (!isMountedRef.current || actorKeyRef.current !== startedActorKey) {
        return;
      }
      profileRequestGenRef.current += 1;
      editorGenerationRef.current += 1;
      isEditingRef.current = false;
      profileRef.current = updated;
      setProfileState({ actorKey: startedActorKey, data: updated });
      populateForm(updated);
      setIsEditing(false);
      setSuccessMessage('Đã cập nhật hồ sơ huấn luyện viên.');
      await syncAuthAndCoachingCache();
    } catch (err) {
      if (!isMountedRef.current || actorKeyRef.current !== startedActorKey) {
        return;
      }
      if (err instanceof ApiError) {
        if (err.status === 409 && err.errorResponse?.errorCode === 'TRAINER_SLUG_ALREADY_EXISTS') {
          setFieldErrors({
            publicSlug: 'Đường dẫn công khai này đã được dùng. Vui lòng chọn đường dẫn khác.',
          });
          return;
        }

        if (err.errorResponse?.fieldErrors && err.errorResponse.fieldErrors.length > 0) {
          const apiMap: Record<string, string> = {};
          err.errorResponse.fieldErrors.forEach((fe) => {
            apiMap[fe.field] = 'Giá trị chưa hợp lệ. Vui lòng kiểm tra lại.';
          });
          setFieldErrors(apiMap);
        } else {
          setSubmitError(apiErrorMessage(err, 'Không thể cập nhật hồ sơ huấn luyện viên.'));
        }
      } else {
        setSubmitError('Lỗi mạng. Vui lòng thử lại.');
      }
    } finally {
      isSubmittingRef.current = false;
      if (isMountedRef.current) {
        setIsSubmitting(false);
      }
    }
  };

  if (isLoading) {
    return (
      <View
        testID="trainer-profile-loading"
        style={[styles.centerContainer, { backgroundColor: themeColors.canvas }]}>
        <ActivityIndicator size="large" color={themeColors.primary} />
        <Text style={[styles.loadingText, { color: themeColors.textSecondary }]}>
          Đang tải hồ sơ huấn luyện viên...
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
          Không thể tải hồ sơ
        </Text>
        <Text style={[styles.errorSubtitle, { color: themeColors.textSecondary }]}>
          {loadError}
        </Text>
        <Pressable
          testID="trainer-profile-retry-button"
          accessibilityRole="button"
          accessibilityLabel="Thử tải lại hồ sơ huấn luyện viên"
          onPress={fetchProfile}
          style={[styles.primaryButton, { backgroundColor: themeColors.primary, marginTop: spacing.md }]}>
          <Text style={styles.primaryButtonText}>Thử lại</Text>
        </Pressable>
      </View>
    );
  }

  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.container, { backgroundColor: themeColors.canvas }]}>
    <KeyboardAvoidingView
      behavior={Platform.OS === 'ios' ? 'padding' : undefined}
      style={[styles.container, { backgroundColor: themeColors.canvas }]}>
      <ScrollView
        testID="trainer-profile-screen"
        contentContainerStyle={styles.scrollContent}
        refreshControl={<RefreshControl refreshing={isRefreshing} onRefresh={handleRefresh} />}
        keyboardShouldPersistTaps="handled">
        {/* Navigation Bar */}
        <View style={styles.navBar}>
          <Pressable
            testID="trainer-profile-back-button"
            accessibilityRole="button"
            accessibilityLabel="Về Trang chủ"
            onPress={() => router.replace('/(app)')}
            style={styles.backButton}>
            <Text style={[styles.backButtonText, { color: themeColors.primary }]}>
              ← Về Tổng quan
            </Text>
          </Pressable>

          {!isEditing ? (
            <Pressable
              testID="trainer-profile-edit-button"
              accessibilityRole="button"
              accessibilityLabel="Chỉnh sửa hồ sơ huấn luyện viên"
              onPress={startEditing}
              style={[styles.editButton, { borderColor: themeColors.border }]}>
              <Text style={[styles.editButtonText, { color: themeColors.primary }]}>Chỉnh sửa</Text>
            </Pressable>
          ) : (
            <View style={styles.editActions}>
              <Pressable
                testID="trainer-profile-cancel-button"
                accessibilityRole="button"
                accessibilityLabel="Hủy chỉnh sửa"
                disabled={isSubmitting}
                onPress={cancelEditing}
                style={styles.cancelButton}>
                <Text style={[styles.cancelButtonText, { color: themeColors.textSecondary }]}>
                  Hủy
                </Text>
              </Pressable>
              <Pressable
                testID="trainer-profile-save-button"
                accessibilityRole="button"
                accessibilityLabel="Lưu thay đổi hồ sơ huấn luyện viên"
                disabled={isSubmitting}
                onPress={handleSave}
                style={[
                  styles.saveButton,
                  { backgroundColor: themeColors.primary, opacity: isSubmitting ? 0.7 : 1 },
                ]}>
                {isSubmitting ? (
                  <ActivityIndicator size="small" color="#ffffff" />
                ) : (
                  <Text style={styles.saveButtonText}>Lưu</Text>
                )}
              </Pressable>
            </View>
          )}
        </View>

        <View style={styles.header}>
          <Text style={[styles.title, { color: themeColors.textPrimary }]}>Hồ sơ huấn luyện viên</Text>
          <Text style={[styles.subtitle, { color: themeColors.textSecondary }]}>
            Thông tin chuyên môn và điều kiện được huấn luyện
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
            Trạng thái và quản lý
          </Text>

          {/* Verification Status */}
          <View style={styles.statusRow}>
            <Text style={[styles.statusLabel, { color: themeColors.textSecondary }]}>
              Trạng thái xác minh
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
                {verificationLabels[profile?.verificationStatus ?? 'NOT_SUBMITTED']}
              </Text>
            </View>
          </View>

          {/* Activity Status */}
          <View style={styles.statusRow}>
            <Text style={[styles.statusLabel, { color: themeColors.textSecondary }]}>
              Trạng thái hoạt động
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
                {trainerActivityLabels[profile?.activityStatus ?? 'INACTIVE']}
              </Text>
            </View>
          </View>

          {/* Coaching Eligibility */}
          <View style={styles.statusRow}>
            <Text style={[styles.statusLabel, { color: themeColors.textSecondary }]}>
              Điều kiện được huấn luyện
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
                {profile?.coachingEligibility?.eligible ? 'Đủ điều kiện' : 'Chưa đủ điều kiện'}
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
              Quyền huấn luyện: {canCoach ? 'Đã được cấp' : 'Chưa được cấp (cần xác minh)'}
            </Text>
            <Text style={[styles.authorityDetail, { color: themeColors.textSecondary }]}>
              {canCoach
                ? 'Bạn đã được phép nhận và huấn luyện học viên.'
                : 'Quyền huấn luyện chỉ được cấp sau khi nền tảng xác minh và xét duyệt hồ sơ theo quy định.'}
              {!profile?.coachingEligibility?.eligible && profile?.coachingEligibility?.blockingReasons?.length
                ? ` Lý do chưa đủ điều kiện: ${profile.coachingEligibility.blockingReasons.map((reason) => coachingBlockingLabels[reason]).join(', ')}.`
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
            Hồ sơ xác minh
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
                Bạn chưa nộp hồ sơ xác minh. Hãy nộp hồ sơ cùng bằng cấp để được xét quyền huấn luyện học viên.
              </Text>
              <View style={styles.fieldGroup}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Ghi chú hồ sơ / Bằng cấp
                </Text>
                <TextInput
                  testID="trainer-application-note-input"
                  accessibilityLabel="Ghi chú hồ sơ hoặc bằng cấp"
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
                  placeholder="Nhập bằng cấp, kinh nghiệm hoặc chứng chỉ huấn luyện..."
                  placeholderTextColor={themeColors.textSecondary}
                  multiline
                  numberOfLines={4}
                  maxLength={2000}
                />
              </View>

              <View style={[styles.fieldGroup, { marginTop: spacing.sm }]}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Tài liệu xác minh (PDF, PNG, JPEG)
                </Text>
                <Pressable
                  testID="trainer-document-upload-button"
                  accessibilityRole="button"
                  accessibilityLabel="Đính kèm tài liệu xác minh"
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
                      + Đính kèm tài liệu (tối đa 10 MB)
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
                            {doc.scanStatus === 'CLEAN' ? 'Đã quét, an toàn' : 'Đang xử lý quét tệp'}
                          </Text>
                        </View>
                        <Pressable
                          testID={`remove-doc-${doc.id}`}
                          accessibilityRole="button"
                          accessibilityLabel={`Xóa tài liệu ${doc.originalFilename}`}
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
                accessibilityLabel="Nộp hồ sơ xác minh"
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
                  <Text style={styles.primaryButtonText}>Nộp hồ sơ xác minh</Text>
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
                  HỒ SƠ ĐANG ĐƯỢC XEM XÉT
                </Text>
              </View>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                Quản trị nền tảng đang xem xét hồ sơ xác minh của bạn. Sau khi được phê duyệt và bật chế độ nhận học viên, học viên mới có thể tìm thấy bạn theo tên.
              </Text>
              {application?.submittedAt && (
                <Text style={[styles.timestampText, { color: themeColors.textSecondary }]}>
                  Đã gửi: {new Date(application.submittedAt).toLocaleDateString('vi-VN')}
                </Text>
              )}
              <Pressable
                testID="trainer-application-refresh-button"
                accessibilityRole="button"
                accessibilityLabel="Làm mới trạng thái xác minh"
                disabled={isRefreshing}
                onPress={handleRefresh}
                style={[styles.editButton, { borderColor: themeColors.border, alignSelf: 'flex-start', marginTop: spacing.xs, opacity: isRefreshing ? 0.6 : 1 }]}>
                <Text style={[styles.editButtonText, { color: themeColors.primary }]}>
                  {isRefreshing ? 'Đang làm mới…' : 'Làm mới trạng thái'}
                </Text>
              </Pressable>
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
                  HỒ SƠ BỊ TỪ CHỐI
                </Text>
              </View>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                Đơn đăng ký trước đó của bạn đã bị từ chối. Vui lòng xem lại lý do bên dưới và gửi lại với thông tin cập nhật.
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
                  Lý do từ chối:
                </Text>
                <Text
                  testID="trainer-application-rejection-reason"
                  style={[styles.rejectionReasonText, { color: themeColors.textPrimary }]}>
                  {application?.rejectionReason ?? 'Hồ sơ chưa đáp ứng yêu cầu của nền tảng.'}
                </Text>
              </View>

              <View style={[styles.fieldGroup, { marginTop: spacing.md }]}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Ghi chú / Chỉnh sửa hồ sơ đã cập nhật
                </Text>
                <TextInput
                  testID="trainer-application-resubmit-note-input"
                  accessibilityLabel="Ghi chú hoặc chỉnh sửa hồ sơ đã cập nhật"
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
                  placeholder="Giải thích cách bạn giải quyết lý do bị từ chối..."
                  placeholderTextColor={themeColors.textSecondary}
                  multiline
                  numberOfLines={4}
                  maxLength={2000}
                />
              </View>

              <View style={[styles.fieldGroup, { marginTop: spacing.sm }]}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Tài liệu xác minh được cập nhật (PDF, PNG, JPEG)
                </Text>
                <Pressable
                  testID="trainer-document-resubmit-upload-button"
                  accessibilityRole="button"
                  accessibilityLabel="Đính kèm tài liệu xác minh đã cập nhật"
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
                      + Đính kèm tài liệu (tối đa 10 MB)
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
                            {doc.scanStatus === 'CLEAN' ? 'Đã quét, an toàn' : 'Đang xử lý quét tệp'}
                          </Text>
                        </View>
                        <Pressable
                          testID={`remove-doc-${doc.id}`}
                          accessibilityRole="button"
                          accessibilityLabel={`Xóa tài liệu ${doc.originalFilename}`}
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
                accessibilityLabel="Gửi lại hồ sơ xác minh"
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
                  <Text style={styles.primaryButtonText}>Gửi lại hồ sơ</Text>
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
                  HUẤN LUYỆN VIÊN ĐƯỢC XÁC MINH
                </Text>
              </View>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                Hồ sơ xác minh của bạn đã được phê duyệt. Bạn có thể huấn luyện học viên khi bật chế độ nhận học viên.
              </Text>
              {!profile?.acceptingStudents && (
                <View
                  testID="trainer-accepting-students-notice"
                  style={[
                    styles.rejectionReasonBox,
                    {
                      backgroundColor: themeColors.warningSurface,
                      borderColor: themeColors.warningText,
                    },
                  ]}>
                  <Text style={[styles.rejectionReasonText, { color: themeColors.warningText }]}>
                    Chế độ nhận học viên đang Tắt, nên học viên chưa tìm thấy bạn theo tên. Hãy bật &ldquo;Đang nhận học viên mới&rdquo; trong hồ sơ.
                  </Text>
                  <Pressable
                    testID="trainer-enable-accepting-button"
                    accessibilityRole="button"
                    accessibilityLabel="Bật nhận học viên ngay"
                    onPress={startEditing}
                    style={[styles.editButton, { borderColor: themeColors.warningText, alignSelf: 'flex-start', marginTop: spacing.xs }]}>
                    <Text style={[styles.editButtonText, { color: themeColors.warningText }]}>
                      Bật nhận học viên ngay
                    </Text>
                  </Pressable>
                </View>
              )}
              {!canCoach && (
                <Pressable
                  testID="trainer-application-refresh-button"
                  accessibilityRole="button"
                  accessibilityLabel="Làm mới quyền huấn luyện"
                  disabled={isRefreshing}
                  onPress={handleRefresh}
                  style={[styles.editButton, { borderColor: themeColors.border, alignSelf: 'flex-start', marginTop: spacing.xs, opacity: isRefreshing ? 0.6 : 1 }]}>
                  <Text style={[styles.editButtonText, { color: themeColors.primary }]}>
                    {isRefreshing ? 'Đang làm mới…' : 'Làm mới quyền huấn luyện'}
                  </Text>
                </Pressable>
              )}
              {profile?.verifiedAt && (
                <Text style={[styles.timestampText, { color: themeColors.textSecondary }]}>
                  Xác minh ngày: {new Date(profile.verifiedAt).toLocaleDateString('vi-VN')}
                </Text>
              )}
            </View>
          )}

          {profile?.verificationStatus === 'SUSPENDED' && (
            <View testID="trainer-application-suspended-banner" style={styles.appStateContainer}>
              <View
                style={[
                  styles.badge,
                  { backgroundColor: themeColors.dangerSurface, alignSelf: 'flex-start', marginBottom: spacing.xs },
                ]}>
                <Text style={[styles.badgeText, { color: themeColors.dangerText }]}>
                  HỒ SƠ XÁC MINH BỊ TẠM NGƯNG
                </Text>
              </View>
              <Text style={[styles.appStateDescription, { color: themeColors.textSecondary }]}>
                Trạng thái xác minh hiện đang bị tạm ngưng. Bạn tạm thời không thể nhận hoặc xuất hiện trong danh bạ tìm kiếm học viên.
              </Text>
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
            {isEditing ? 'Chỉnh sửa hồ sơ' : 'Chi tiết hồ sơ'}
          </Text>

          {isEditing ? (
            <View style={styles.formFields}>
              {/* Public Slug */}
              <View style={styles.fieldGroup}>
                <Text style={[styles.fieldLabel, { color: themeColors.textPrimary }]}>
                  Định danh hồ sơ công khai
                </Text>
                <TextInput
                  testID="trainer-profile-slug-input"
                  accessibilityLabel="Định danh hồ sơ công khai"
                  autoCapitalize="none"
                  autoCorrect={false}
                  placeholder="Ví dụ: coach-alex"
                  placeholderTextColor={themeColors.textSecondary}
                  value={editSlug}
                  onChangeText={(val) => {
                    markDraftEdited();
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
                  Giới thiệu chuyên môn
                </Text>
                <TextInput
                  testID="trainer-profile-bio-input"
                  accessibilityLabel="Giới thiệu chuyên môn"
                  multiline
                  numberOfLines={4}
                  maxLength={4000}
                  placeholder="Giới thiệu kinh nghiệm huấn luyện của bạn..."
                  placeholderTextColor={themeColors.textSecondary}
                  value={editBio}
                  onChangeText={(val) => {
                    markDraftEdited();
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
                  Số năm kinh nghiệm huấn luyện
                </Text>
                <TextInput
                  testID="trainer-profile-experience-input"
                  accessibilityLabel="Số năm kinh nghiệm huấn luyện"
                  keyboardType="decimal-pad"
                  placeholder="e.g. 5"
                  placeholderTextColor={themeColors.textSecondary}
                  value={editYears}
                  onChangeText={(val) => {
                    markDraftEdited();
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
                    Đang nhận học viên mới
                  </Text>
                  <Text style={[styles.switchDescription, { color: themeColors.textSecondary }]}>
                    Sẵn sàng nhận học viên sau khi được xác minh.
                  </Text>
                </View>
                <Switch
                  testID="trainer-profile-accepting-switch"
                  accessibilityLabel="Đang nhận học viên mới"
                  value={editAccepting}
                  onValueChange={(val) => {
                    markDraftEdited();
                    setEditAccepting(val);
                  }}
                  trackColor={{ false: colors.neutral[300], true: themeColors.primary }}
                  thumbColor={Platform.OS === 'android' ? '#ffffff' : undefined}
                />
              </View>
            </View>
          ) : (
            <View style={styles.detailRows}>
              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Định danh công khai (public slug)
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.publicSlug ? `@${profile.publicSlug}` : unspecified}
                </Text>
                <Text style={[styles.timestampText, { color: themeColors.textSecondary }]}>
                  Chỉ là định danh URL tùy chọn; không phải xác minh và học viên tìm theo tên hiển thị sau khi Admin duyệt.
                </Text>
              </View>

              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Kinh nghiệm
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.yearsExperience != null
                    ? `${profile.yearsExperience} năm`
                    : unspecified}
                </Text>
              </View>

              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Nhận học viên
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.acceptingStudents ? 'Có' : 'Không'}
                </Text>
              </View>

              <View style={styles.detailItem}>
                <Text style={[styles.detailLabel, { color: themeColors.textSecondary }]}>
                  Giới thiệu
                </Text>
                <Text style={[styles.detailValue, { color: themeColors.textPrimary }]}>
                  {profile?.bio || 'Chưa có giới thiệu.'}
                </Text>
              </View>
            </View>
          )}
        </View>
      </ScrollView>
    </KeyboardAvoidingView>
    </SafeAreaView>
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
    fontFamily: fontFamily.medium,
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
    fontFamily: fontFamily.bold,
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
    fontFamily: fontFamily.medium,
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
