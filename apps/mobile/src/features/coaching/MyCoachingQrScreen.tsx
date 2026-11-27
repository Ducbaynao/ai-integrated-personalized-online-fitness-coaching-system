import React, { useEffect, useRef, useState } from 'react';
import { Linking, Platform, ScrollView, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { coachingApi, createCommandKey } from '@/services/coachingApi';
import { CoachingQrRole } from '@/types/coaching';
import { coachingErrorMessage, isStaleCoachingError } from './coachingMessages';
import { encodeQrMatrixToPngBytes } from './coachingQrCodec';
import { coachingQueryKeys, useMyCoachingQr } from './coachingQueries';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from './components/CoachingUi';
import { CoachingQrMatrixView } from './components/CoachingQrMatrixView';
import { useCoachingQrImageExport } from './useCoachingQrImageExport';

function roleLabel(role: CoachingQrRole): string {
  return role === 'TRAINER' ? 'Huấn luyện viên' : 'Học viên';
}

interface RotateIntent {
  userId: string | undefined;
  role: CoachingQrRole;
  expectedVersion: number;
  commandKey: string;
}

export function MyCoachingQrScreen() {
  const { user, activeCapability } = useAuth();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const queryClient = useQueryClient();
  const { saveImage: saveQrImage, canOpenSettings } = useCoachingQrImageExport();

  const hasStudent = Boolean(user?.capabilities?.hasStudentProfile);
  const hasTrainer = Boolean(user?.capabilities?.hasTrainerProfile);
  const defaultRole: CoachingQrRole =
    activeCapability === 'TRAINER' && hasTrainer
      ? 'TRAINER'
      : hasStudent
        ? 'STUDENT'
        : 'TRAINER';

  const [roleOverride, setRoleOverride] = useState<{ userId: string | undefined; role: CoachingQrRole } | null>(null);
  const selectedRole: CoachingQrRole =
    roleOverride && roleOverride.userId === user?.id ? roleOverride.role : defaultRole;
  const setSelectedRole = (role: CoachingQrRole) => setRoleOverride({ userId: user?.id, role });

  const contextKey = `${user?.id ?? ''}:${activeCapability ?? ''}:${selectedRole}`;
  const [confirmRotateKey, setConfirmRotateKey] = useState<string | null>(null);
  const [saveFeedback, setSaveFeedback] = useState<{ contextKey: string; message: string } | null>(null);
  const [rotateFeedback, setRotateFeedback] = useState<{ contextKey: string; message: string } | null>(null);
  const [savingImage, setSavingImage] = useState(false);

  const mountedRef = useRef(true);
  const userIdRef = useRef(user?.id);
  const capabilityRef = useRef(activeCapability);
  const rotateIntentRef = useRef<RotateIntent | null>(null);
  const rotateInFlightRef = useRef(false);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    userIdRef.current = user?.id;
    capabilityRef.current = activeCapability;
    rotateIntentRef.current = null;
  }, [user?.id, activeCapability, selectedRole]);

  const roleEligible =
    selectedRole === 'STUDENT' ? hasStudent : hasTrainer && Boolean(user?.capabilities?.canCoach);

  const qrQuery = useMyCoachingQr(user?.id, selectedRole, roleEligible);

  const rotateMutation = useMutation({
    mutationFn: async (vars: {
      intent: RotateIntent;
      startedCapability: typeof activeCapability;
      startedContextKey: string;
    }) => {
      const updated = await coachingApi.rotateMyQr(
        vars.intent.role,
        vars.intent.expectedVersion,
        vars.intent.commandKey
      );
      return {
        updated,
        startedUserId: vars.intent.userId,
        startedCapability: vars.startedCapability,
        startedContextKey: vars.startedContextKey,
      };
    },
    onSuccess: ({ updated, startedUserId, startedCapability, startedContextKey }) => {
      if (
        !mountedRef.current ||
        userIdRef.current !== startedUserId ||
        capabilityRef.current !== startedCapability
      ) {
        return;
      }
      rotateIntentRef.current = null;
      setConfirmRotateKey(null);
      setRotateFeedback({
        contextKey: startedContextKey,
        message: 'Đã tạo mã QR mới. Mã QR cũ đã bị vô hiệu.',
      });
      queryClient.setQueryData(coachingQueryKeys.myQr(startedUserId, updated.role), updated);
    },
    onError: (error, vars) => {
      if (
        !mountedRef.current ||
        userIdRef.current !== vars.intent.userId ||
        capabilityRef.current !== vars.startedCapability
      ) {
        return;
      }
      const code = (error as { errorResponse?: { errorCode?: string } })?.errorResponse?.errorCode;
      if (isStaleCoachingError(error) || code === 'COACHING_IDEMPOTENCY_CONFLICT') {
        rotateIntentRef.current = null;
        setConfirmRotateKey(null);
        queryClient.invalidateQueries({
          queryKey: coachingQueryKeys.myQr(vars.intent.userId, vars.intent.role),
        });
      }
      // Network/5xx errors intentionally keep rotateIntentRef.current (role, expectedVersion, commandKey)
      // immutable so retry sends the exact same payload regardless of background query refetches.
    },
    onSettled: () => {
      rotateInFlightRef.current = false;
    },
  });

  const handleSaveImage = async () => {
    if (!qrQuery.data || savingImage) return;
    const startedUserId = user?.id;
    const startedContextKey = contextKey;
    const qrUriToSave = qrQuery.data.qrUri;
    setSaveFeedback(null);
    setSavingImage(true);
    try {
      const pngBytes = encodeQrMatrixToPngBytes(qrUriToSave);
      const result = await saveQrImage(
        pngBytes,
        `coaching-qr-${selectedRole.toLowerCase()}-${Date.now()}.png`,
        () => mountedRef.current && userIdRef.current === startedUserId
      );
      if (!mountedRef.current || userIdRef.current !== startedUserId) return;
      if (result === 'cancelled') return;
      if (result === 'permission-denied') {
        setSaveFeedback({
          contextKey: startedContextKey,
          message: 'Chưa có quyền lưu ảnh vào thư viện. Vui lòng cấp quyền trong Cài đặt.',
        });
        return;
      }
      setSaveFeedback({
        contextKey: startedContextKey,
        message: Platform.OS === 'web'
          ? 'Đã yêu cầu trình duyệt tải ảnh mã QR.'
          : 'Đã lưu ảnh mã QR vào thư viện ảnh của bạn.',
      });
    } catch {
      if (mountedRef.current && userIdRef.current === startedUserId) {
        setSaveFeedback({
          contextKey: startedContextKey,
          message: 'Không thể lưu ảnh mã QR. Vui lòng thử lại.',
        });
      }
    } finally {
      if (mountedRef.current) {
        setSavingImage(false);
      }
    }
  };

  const handleConfirmRotate = () => {
    if (rotateInFlightRef.current || rotateMutation.isPending) return;
    if (!rotateIntentRef.current) {
      if (!qrQuery.data) return;
      rotateIntentRef.current = {
        userId: user?.id,
        role: selectedRole,
        expectedVersion: qrQuery.data.version,
        commandKey: createCommandKey(),
      };
    }
    rotateInFlightRef.current = true;
    setRotateFeedback(null);
    rotateMutation.mutate({
      intent: rotateIntentRef.current,
      startedCapability: activeCapability,
      startedContextKey: contextKey,
    });
  };

  const confirmRotate = confirmRotateKey === contextKey;
  const activeSaveFeedback = saveFeedback?.contextKey === contextKey ? saveFeedback.message : null;
  const activeRotateFeedback = rotateFeedback?.contextKey === contextKey ? rotateFeedback.message : null;

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
      <ScrollView testID="my-coaching-qr-screen" style={styles.screen} contentContainerStyle={styles.content}>
        <View>
          <Text style={[styles.title, { color: theme.textPrimary }]}>Mã QR của tôi</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Chia sẻ mã này để đối tác quét và gửi yêu cầu hoặc lời mời huấn luyện.
          </Text>
        </View>

        {hasStudent && hasTrainer ? (
          <View style={styles.row} testID="qr-role-switcher">
            <CoachingButton
              testID="qr-role-student"
              label="Tư cách Học viên"
              secondary={selectedRole !== 'STUDENT'}
              onPress={() => {
                setSelectedRole('STUDENT');
                setConfirmRotateKey(null);
                setRotateFeedback(null);
                setSaveFeedback(null);
                rotateIntentRef.current = null;
              }}
            />
            <CoachingButton
              testID="qr-role-trainer"
              label="Tư cách Huấn luyện viên"
              secondary={selectedRole !== 'TRAINER'}
              onPress={() => {
                setSelectedRole('TRAINER');
                setConfirmRotateKey(null);
                setRotateFeedback(null);
                setSaveFeedback(null);
                rotateIntentRef.current = null;
              }}
            />
          </View>
        ) : null}

        {!roleEligible ? (
          <CoachingState
            testID="my-qr-ineligible"
            title="Chưa thể hiển thị mã QR"
            message={
              selectedRole === 'TRAINER'
                ? 'Hồ sơ Huấn luyện viên cần được xác minh và đang hoạt động để cấp mã QR.'
                : 'Tài khoản chưa kích hoạt hồ sơ Học viên.'
            }
          />
        ) : qrQuery.isPending ? (
          <CoachingState testID="my-qr-loading" busy title="Đang tải mã QR" message="Vui lòng chờ trong giây lát." />
        ) : qrQuery.isError ? (
          <CoachingState
            testID="my-qr-error"
            title="Không thể tải mã QR"
            message={coachingErrorMessage(qrQuery.error)}
            onRetry={() => qrQuery.refetch()}
          />
        ) : (
          <>
            <CoachingCard testID="my-qr-card">
              <Text testID="my-qr-display-name" style={[styles.h2, { color: theme.textPrimary, textAlign: 'center' }]}>
                {qrQuery.data.displayName}
              </Text>
              <Text testID="my-qr-role-label" style={[styles.label, { color: theme.primary, textAlign: 'center' }]}>
                Tư cách: {roleLabel(qrQuery.data.role)}
              </Text>
              <View style={localStyles.qrContainer}>
                <CoachingQrMatrixView
                  qrUri={qrQuery.data.qrUri}
                  accessibilityLabel={`Mã QR ${roleLabel(qrQuery.data.role)} của ${qrQuery.data.displayName}`}
                />
              </View>
              <Text style={[styles.body, { color: theme.textSecondary, textAlign: 'center' }]}>
                Phiên bản mã: v{qrQuery.data.version} · Mở lại màn hình không tự đổi mã.
              </Text>
            </CoachingCard>

            <CoachingButton
              testID="save-qr-image-button"
              label={savingImage ? 'Đang lưu ảnh...' : 'Lưu ảnh QR'}
              busy={savingImage}
              disabled={savingImage}
              onPress={handleSaveImage}
            />
            {activeSaveFeedback ? (
              <CoachingCard testID="save-qr-feedback">
                <Text style={[styles.body, { color: theme.textPrimary }]}>{activeSaveFeedback}</Text>
                {canOpenSettings ? (
                  <CoachingButton label="Mở cài đặt" secondary onPress={() => Linking.openSettings()} />
                ) : null}
              </CoachingCard>
            ) : null}

            {!confirmRotate ? (
              <CoachingButton
                testID="rotate-qr-prompt-button"
                label="Tạo lại mã QR"
                secondary
                onPress={() => {
                  if (!qrQuery.data) return;
                  rotateIntentRef.current = {
                    userId: user?.id,
                    role: selectedRole,
                    expectedVersion: qrQuery.data.version,
                    commandKey: createCommandKey(),
                  };
                  setConfirmRotateKey(contextKey);
                  setRotateFeedback(null);
                }}
              />
            ) : (
              <CoachingCard testID="rotate-qr-confirm-card">
                <Text style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận tạo lại mã QR?</Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  Mã QR cũ (kể cả ảnh đã lưu trước đó) sẽ bị vô hiệu ngay lập tức.
                </Text>
                <View style={styles.row}>
                  <CoachingButton
                    testID="rotate-qr-confirm-button"
                    label={rotateMutation.isPending ? 'Đang tạo lại...' : 'Xác nhận đổi mã'}
                    danger
                    busy={rotateMutation.isPending}
                    disabled={rotateMutation.isPending}
                    onPress={handleConfirmRotate}
                  />
                  <CoachingButton
                    testID="rotate-qr-cancel-button"
                    label="Hủy"
                    secondary
                    disabled={rotateMutation.isPending}
                    onPress={() => {
                      rotateIntentRef.current = null;
                      setConfirmRotateKey(null);
                    }}
                  />
                </View>
                {rotateMutation.isError ? (
                  <Text testID="rotate-qr-error" style={[styles.body, { color: theme.dangerText }]}>
                    {coachingErrorMessage(rotateMutation.error)}
                  </Text>
                ) : null}
              </CoachingCard>
            )}

            {activeRotateFeedback ? (
              <Text testID="rotate-qr-success" style={[styles.body, { color: theme.primary, textAlign: 'center' }]}>
                {activeRotateFeedback}
              </Text>
            ) : null}
          </>
        )}
      </ScrollView>
    </SafeAreaView>
  );
}

const localStyles = StyleSheet.create({
  qrContainer: {
    paddingVertical: spacing.md,
    alignItems: 'center',
  },
});
