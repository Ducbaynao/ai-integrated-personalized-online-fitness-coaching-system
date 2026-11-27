import React, { useEffect, useRef, useState } from 'react';
import { ScrollView, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import { verificationLabels } from '@/localization/displayLabels';
import { radius } from '@/design-system/tokens/radius';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { CoachingOutcome, CoachingQrRole } from '@/types/coaching';
import { coachingApi, createCommandKey } from '@/services/coachingApi';
import { coachingErrorMessage, coachingStatusLabels, isStaleCoachingError } from './coachingMessages';
import { coachingQueryKeys, useResolveCoachingQr } from './coachingQueries';
import { coachingUiStyles as styles } from './components/CoachingUi';

function getInitials(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return `${parts[0][0]}${parts[parts.length - 1][0]}`.toUpperCase();
}

export function QrProfilePreviewScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ publicCode?: string; targetRole?: string; payloadVersion?: string }>();
  const { user, activeCapability } = useAuth();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const queryClient = useQueryClient();

  const publicCode = typeof params.publicCode === 'string' ? params.publicCode.trim() : '';
  const targetRole: CoachingQrRole | null =
    params.targetRole === 'TRAINER' || params.targetRole === 'STUDENT' ? params.targetRole : null;
  const payloadVersion = Number(params.payloadVersion ?? '1');
  const validParams = /^[A-Za-z0-9_-]{32}$/.test(publicCode) && targetRole !== null && payloadVersion === 1;

  const query = useResolveCoachingQr(user?.id, validParams ? publicCode : '', targetRole ?? 'TRAINER', 1);

  const currentContextKey = `${user?.id ?? ''}:${activeCapability ?? ''}:${publicCode}:${targetRole ?? ''}`;
  const mountedRef = useRef(true);
  const userIdRef = useRef(user?.id);
  const capabilityRef = useRef(activeCapability);
  const commandKeyRef = useRef<{ intentKey: string; commandKey: string } | null>(null);
  const [submittedState, setSubmittedState] = useState<{ contextKey: string; outcome: CoachingOutcome } | null>(null);
  const submittedOutcome = submittedState?.contextKey === currentContextKey ? submittedState.outcome : null;

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    userIdRef.current = user?.id;
    capabilityRef.current = activeCapability;
    commandKeyRef.current = null;
  }, [user?.id, activeCapability, publicCode, targetRole]);

  const initiateMutation = useMutation({
    mutationFn: async (vars: {
      counterpartyId: string;
      role: CoachingQrRole;
      commandKey: string;
      startedUserId: string | undefined;
      startedCapability: typeof activeCapability;
      startedContextKey: string;
    }) => {
      const outcome =
        vars.role === 'TRAINER'
          ? await coachingApi.requestTrainer(vars.counterpartyId, vars.commandKey)
          : await coachingApi.inviteStudent(vars.counterpartyId, vars.commandKey);
      return {
        outcome,
        startedUserId: vars.startedUserId,
        startedCapability: vars.startedCapability,
        startedContextKey: vars.startedContextKey,
      };
    },
    onSuccess: ({ outcome, startedUserId, startedCapability, startedContextKey }) => {
      if (
        !mountedRef.current ||
        userIdRef.current !== startedUserId ||
        capabilityRef.current !== startedCapability
      ) {
        return;
      }
      commandKeyRef.current = null;
      setSubmittedState({ contextKey: startedContextKey, outcome });
      queryClient.invalidateQueries({ queryKey: coachingQueryKeys.relationships() });
      queryClient.invalidateQueries({ queryKey: coachingQueryKeys.qr() });
      if (outcome.relationship?.id) {
        queryClient.invalidateQueries({ queryKey: coachingQueryKeys.detail(outcome.relationship.id) });
      }
    },
    onError: (error, vars) => {
      if (
        !mountedRef.current ||
        userIdRef.current !== vars.startedUserId ||
        capabilityRef.current !== vars.startedCapability
      ) {
        return;
      }
      const code = (error as { errorResponse?: { errorCode?: string } })?.errorResponse?.errorCode;
      if (
        isStaleCoachingError(error) ||
        code === 'COACHING_REQUEST_ALREADY_PENDING' ||
        code === 'COACHING_STUDENT_ALREADY_ASSIGNED' ||
        code === 'COACHING_IDEMPOTENCY_CONFLICT'
      ) {
        commandKeyRef.current = null;
        queryClient.invalidateQueries({ queryKey: coachingQueryKeys.relationships() });
        query.refetch();
      }
      // Network/5xx errors intentionally keep commandKeyRef.current so retry reuses the same key.
    },
  });

  const handleInitiate = () => {
    if (!query.data || !query.data.relationshipState.canInitiate || initiateMutation.isPending || submittedOutcome) {
      return;
    }
    const intentKey = `${query.data.counterpartyId}:${query.data.targetRole}`;
    if (!commandKeyRef.current || commandKeyRef.current.intentKey !== intentKey) {
      commandKeyRef.current = { intentKey, commandKey: createCommandKey() };
    }
    initiateMutation.mutate({
      counterpartyId: query.data.counterpartyId,
      role: query.data.targetRole,
      commandKey: commandKeyRef.current.commandKey,
      startedUserId: user?.id,
      startedCapability: activeCapability,
      startedContextKey: currentContextKey,
    });
  };

  const committedRel = submittedOutcome?.relationship;
  const effectiveStatus = committedRel?.status ?? query.data?.relationshipState.status ?? 'NONE';
  const effectiveRelId = committedRel?.id ?? query.data?.relationshipState.relationshipId ?? null;
  const effectiveDirection = committedRel?.direction ?? query.data?.relationshipState.direction ?? null;

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
      <ScrollView testID="qr-profile-preview-screen" style={styles.screen} contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]}>
        <View>
          <Text accessibilityRole="header" aria-level={1} style={[styles.title, { color: theme.textPrimary }]}>Hồ sơ từ mã QR</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Tư cách hiện hành: {activeCapability === 'TRAINER' ? 'Huấn luyện viên' : 'Học viên'} · Xem hồ sơ chưa cấp quyền dữ liệu sức khỏe.
          </Text>
        </View>

        {!validParams ? (
          <AppCard state="info"
            testID="qr-preview-invalid-params"
            title="Mã QR không hợp lệ"
            message="Liên kết mã QR thiếu thông tin hoặc phiên bản không được hỗ trợ."
          />
        ) : query.isPending ? (
          <AppCard testID="qr-preview-loading" state="loading" title="Đang xác thực mã QR" message="Đang tải hồ sơ tối thiểu từ máy chủ." />
        ) : query.isError ? (
          <AppCard state="error"
            testID="qr-preview-error"
            title="Không thể mở hồ sơ"
            message={coachingErrorMessage(query.error)}
            onRetry={() => query.refetch()}
          />
        ) : (
          <>
            <AppCard testID="qr-preview-card">
              <View style={localStyles.headerRow}>
                <View
                  testID="qr-preview-avatar-placeholder"
                  accessibilityLabel={`Chữ viết tắt tên ${query.data.displayName}`}
                  style={[localStyles.avatarPlaceholder, { backgroundColor: theme.surfaceSubtle, borderColor: theme.border }]}
                >
                  <Text style={[styles.h2, { color: theme.primary }]}>
                    {getInitials(query.data.displayName)}
                  </Text>
                </View>
                <View style={localStyles.headerMeta}>
                  <Text accessibilityRole="header" aria-level={2} testID="qr-preview-display-name" style={[styles.h2, { color: theme.textPrimary }]}>
                    {query.data.displayName}
                  </Text>
                  <Text testID="qr-preview-role" style={[styles.label, { color: theme.primary }]}>
                    {query.data.targetRole === 'TRAINER' ? 'Huấn luyện viên' : 'Học viên'}
                  </Text>
                </View>
              </View>

              {query.data.targetRole === 'TRAINER' && query.data.trainerSummary ? (
                <View testID="qr-preview-trainer-summary" style={localStyles.summaryBlock}>
                  <View style={localStyles.statusRow}>
                    <AppIcon name={query.data.trainerSummary.verificationStatus === 'VERIFIED' ? 'shield' : 'info'} color={theme.primary} />
                    <Text testID="qr-preview-verification" style={[styles.label, localStyles.statusText, { color: theme.textPrimary }]}>
                      Trạng thái xác minh: {verificationLabels[query.data.trainerSummary.verificationStatus]}
                    </Text>
                  </View>
                  <Text testID="qr-preview-availability" style={[styles.body, { color: theme.textPrimary }]}>
                    {query.data.trainerSummary.isAcceptingStudents ? 'Đang nhận học viên' : 'Hiện không nhận học viên'}
                  </Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    Chuyên môn: {query.data.trainerSummary.specialties.length > 0 ? query.data.trainerSummary.specialties.join(', ') : 'Chưa cập nhật'}
                  </Text>
                  {query.data.trainerSummary.yearsExperience !== null ? (
                    <Text style={[styles.body, { color: theme.textSecondary }]}>
                      Kinh nghiệm: {query.data.trainerSummary.yearsExperience} năm
                    </Text>
                  ) : null}
                </View>
              ) : null}
            </AppCard>
            <AppCard state="info" icon="info" title="Thông tin công khai" message="Hồ sơ chỉ hiển thị thông tin máy chủ cung cấp. Lời mời đang chờ chưa bắt đầu huấn luyện hoặc cấp quyền dữ liệu sức khỏe." />

            {effectiveStatus !== 'NONE' && effectiveRelId ? (
              <AppCard testID="qr-preview-existing-relationship">
                <Text style={[styles.label, { color: theme.textPrimary }]}>
                  {submittedOutcome
                    ? query.data.targetRole === 'TRAINER'
                      ? 'Đã gửi yêu cầu huấn luyện'
                      : 'Đã gửi lời mời huấn luyện'
                    : `Trạng thái hiện tại: ${coachingStatusLabels[effectiveStatus]}`}
                </Text>
                {effectiveStatus === 'PENDING' && effectiveDirection ? (
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    {effectiveDirection === 'INCOMING'
                      ? 'Đối tác đã gửi cho bạn trước đó. Bạn có thể xem và chấp nhận hoặc từ chối.'
                      : 'Đang chờ đối tác phản hồi.'}
                  </Text>
                ) : null}
                <AppButton
                  icon="arrow"
                  testID="view-existing-relationship-button"
                  label={effectiveStatus === 'PENDING' ? 'Xem yêu cầu' : 'Xem quan hệ hiện có'}
                  onPress={() => router.replace(`/coaching/${effectiveRelId}` as never)}
                />
              </AppCard>
            ) : query.data.relationshipState.canInitiate ? (
              <View style={localStyles.actionBlock}>
                <AppButton
                  icon="coaching"
                  testID="qr-initiate-coaching-button"
                  label={
                    initiateMutation.isPending
                      ? 'Đang gửi...'
                      : query.data.targetRole === 'TRAINER'
                        ? 'Gửi yêu cầu huấn luyện'
                        : 'Gửi lời mời huấn luyện'
                  }
                  loading={initiateMutation.isPending}
                  disabled={initiateMutation.isPending}
                  onPress={handleInitiate}
                />
                {initiateMutation.isError ? (
                  <AppCard state="error"
                    testID="qr-initiate-error"
                    title="Chưa thể gửi"
                    message={coachingErrorMessage(initiateMutation.error)}
                    onRetry={handleInitiate}
                  />
                ) : null}
              </View>
            ) : null}
          </>
        )}

        <AppButton
          icon="back"
          testID="qr-preview-rescan-button"
          label="Quét mã khác"
          variant="secondary"
          onPress={() => router.back()}
        />
      </ScrollView>
    </SafeAreaView>
  );
}

const localStyles = StyleSheet.create({
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.md,
  },
  avatarPlaceholder: {
    width: layout.featureIconContainer,
    height: layout.featureIconContainer,
    borderRadius: radius.full,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  headerMeta: {
    flex: 1,
    minWidth: 0,
    gap: spacing.xs,
  },
  summaryBlock: {
    paddingTop: spacing.sm,
    gap: spacing.md,
  },
  statusRow: { flexDirection: 'row', alignItems: 'center', gap: spacing.sm },
  statusText: { flex: 1, minWidth: 0 },
  actionBlock: {
    gap: spacing.md,
  },
});
