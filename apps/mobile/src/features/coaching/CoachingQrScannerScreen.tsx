import React, { useCallback, useEffect, useRef, useState } from 'react';
import { AppState, Linking, Pressable, ScrollView, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useFocusEffect, useLocalSearchParams, useRouter } from 'expo-router';
import { BarcodeScanningResult, Camera, CameraView, useCameraPermissions } from 'expo-camera';
import * as ImagePicker from 'expo-image-picker';
import { useAuth } from '@/features/auth/AuthContext';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { CoachingQrRole } from '@/types/coaching';
import { parseCoachingQrUri } from './coachingQrCodec';
import { clearPendingCoachingQrIntent } from './pendingQrIntent';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from './components/CoachingUi';

interface DecodedCandidate {
  raw: string;
  publicCode: string;
  payloadVersion: number;
}

function parseErrorMessage(reason: 'EMPTY' | 'INVALID_URI' | 'UNSUPPORTED_VERSION' | 'INVALID_CODE'): string {
  switch (reason) {
    case 'EMPTY':
      return 'Không tìm thấy nội dung mã QR.';
    case 'UNSUPPORTED_VERSION':
      return 'Phiên bản mã QR không được hỗ trợ.';
    case 'INVALID_CODE':
    case 'INVALID_URI':
    default:
      return 'Mã QR này không thuộc hệ thống huấn luyện hợp lệ.';
  }
}

export function CoachingQrScannerScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ targetRole?: string; c?: string; v?: string }>();
  const { activeCapability, user } = useAuth();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [permission, requestPermission] = useCameraPermissions();

  const paramTarget: CoachingQrRole | null =
    params.targetRole === 'TRAINER' || params.targetRole === 'STUDENT' ? params.targetRole : null;
  const derivedTarget: CoachingQrRole =
    paramTarget ?? (activeCapability === 'TRAINER' ? 'STUDENT' : 'TRAINER');

  const dualRole =
    Boolean(user?.capabilities?.hasStudentProfile) && Boolean(user?.capabilities?.hasTrainerProfile);

  const deepLinkRaw =
    typeof params.c === 'string' || typeof params.v === 'string'
      ? `ai-fitness-coaching://coaching/qr?v=${encodeURIComponent(params.v ?? '')}&c=${encodeURIComponent(params.c ?? '')}`
      : null;
  const deepLinkParsed = deepLinkRaw ? parseCoachingQrUri(deepLinkRaw) : null;

  const [targetRoleOverride, setTargetRoleOverride] = useState<{ userId: string | undefined; role: CoachingQrRole } | null>(null);
  const targetRole: CoachingQrRole =
    paramTarget ??
    (targetRoleOverride && targetRoleOverride.userId === user?.id ? targetRoleOverride.role : derivedTarget);
  const setTargetRole = (role: CoachingQrRole) => setTargetRoleOverride({ userId: user?.id, role });
  const [cameraActive, setCameraActive] = useState(false);
  const [scanLocked, setScanLocked] = useState(false);
  const [decodingImage, setDecodingImage] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(() =>
    deepLinkParsed && !deepLinkParsed.ok ? parseErrorMessage(deepLinkParsed.reason) : null
  );
  const [multipleCandidates, setMultipleCandidates] = useState<DecodedCandidate[]>([]);
  const mountedRef = useRef(true);
  const scanLockRef = useRef(false);
  const deepLinkHandledRef = useRef<string | null>(null);
  const currentContextKey = `${user?.id ?? ''}:${activeCapability ?? ''}:${targetRole}`;
  const userIdRef = useRef(user?.id);
  const contextKeyRef = useRef(currentContextKey);

  useEffect(() => {
    mountedRef.current = true;
    return () => {
      mountedRef.current = false;
    };
  }, []);

  useEffect(() => {
    userIdRef.current = user?.id;
    contextKeyRef.current = currentContextKey;
  }, [user?.id, currentContextKey]);

  // Stop camera when leaving screen or backgrounding app
  useFocusEffect(
    useCallback(() => {
      scanLockRef.current = false;
      setScanLocked(false);
      return () => {
        setCameraActive(false);
      };
    }, [])
  );

  useEffect(() => {
    const subscription = AppState.addEventListener('change', (state) => {
      if (state !== 'active') {
        setCameraActive(false);
      }
    });
    return () => subscription.remove();
  }, []);

  const navigateToPreview = useCallback(
    (publicCode: string, payloadVersion: number, replace = false) => {
      clearPendingCoachingQrIntent();
      setCameraActive(false);
      setMultipleCandidates([]);
      setErrorMessage(null);
      const dest = {
        pathname: '/coaching/qr-profile',
        params: {
          publicCode,
          targetRole,
          payloadVersion: String(payloadVersion),
        },
      } as never;
      if (replace) {
        router.replace(dest);
      } else {
        router.push(dest);
      }
    },
    [router, targetRole]
  );

  const handleCancelDeepLink = useCallback(() => {
    clearPendingCoachingQrIntent();
    setCameraActive(false);
    setMultipleCandidates([]);
    setErrorMessage(null);
    router.replace('/(app)/coaching' as never);
  }, [router]);

  useEffect(() => {
    if (!deepLinkRaw || !deepLinkParsed || !deepLinkParsed.ok) return;
    if (dualRole && !paramTarget && !targetRoleOverride) return;
    if (deepLinkHandledRef.current === deepLinkRaw) return;
    deepLinkHandledRef.current = deepLinkRaw;
    navigateToPreview(deepLinkParsed.publicCode, deepLinkParsed.payloadVersion, true);
  }, [deepLinkRaw, deepLinkParsed, dualRole, paramTarget, targetRoleOverride, navigateToPreview]);

  const handleOpenCamera = async () => {
    setErrorMessage(null);
    setMultipleCandidates([]);
    if (!permission?.granted) {
      const res = await requestPermission();
      if (!res.granted) {
        setCameraActive(false);
        return;
      }
    }
    scanLockRef.current = false;
    setScanLocked(false);
    setCameraActive(true);
  };

  const handleBarcodeScanned = (result: BarcodeScanningResult) => {
    if (scanLockRef.current || scanLocked) return;
    scanLockRef.current = true;
    setScanLocked(true);
    const parsed = parseCoachingQrUri(result.data ?? '');
    if (!parsed.ok) {
      setCameraActive(false);
      setErrorMessage(parseErrorMessage(parsed.reason));
      return;
    }
    navigateToPreview(parsed.publicCode, parsed.payloadVersion);
  };

  const handlePickImage = async () => {
    const startedUserId = user?.id;
    const startedContextKey = currentContextKey;
    setCameraActive(false);
    setErrorMessage(null);
    setMultipleCandidates([]);

    setDecodingImage(true);
    try {
      const picked = await ImagePicker.launchImageLibraryAsync({
        mediaTypes: ['images'],
      });
      if (!mountedRef.current || userIdRef.current !== startedUserId || contextKeyRef.current !== startedContextKey) {
        return;
      }
      if (picked.canceled || !picked.assets?.[0]?.uri) {
        return;
      }

      const scanned = await Camera.scanFromURLAsync(picked.assets[0].uri, ['qr']);
      if (!mountedRef.current || userIdRef.current !== startedUserId || contextKeyRef.current !== startedContextKey) {
        return;
      }

      if (!scanned || scanned.length === 0) {
        setErrorMessage('Ảnh đã chọn không chứa mã QR.');
        return;
      }

      const validCandidates: DecodedCandidate[] = [];
      const seenCodes = new Set<string>();
      for (const item of scanned) {
        const parsed = parseCoachingQrUri(item.data ?? '');
        if (parsed.ok && !seenCodes.has(parsed.publicCode)) {
          seenCodes.add(parsed.publicCode);
          validCandidates.push({
            raw: item.data,
            publicCode: parsed.publicCode,
            payloadVersion: parsed.payloadVersion,
          });
        }
      }

      if (validCandidates.length === 0) {
        setErrorMessage('Không tìm thấy mã QR huấn luyện hợp lệ trong ảnh đã chọn.');
        return;
      }
      if (validCandidates.length === 1) {
        navigateToPreview(validCandidates[0].publicCode, validCandidates[0].payloadVersion);
        return;
      }
      setMultipleCandidates(validCandidates);
    } catch {
      if (mountedRef.current && userIdRef.current === startedUserId && contextKeyRef.current === startedContextKey) {
        setErrorMessage('Không thể mở thư viện ảnh hoặc giải mã ảnh đã chọn. Vui lòng thử lại.');
      }
    } finally {
      if (mountedRef.current) {
        setDecodingImage(false);
      }
    }
  };

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
      <ScrollView testID="coaching-qr-scanner-screen" style={styles.screen} contentContainerStyle={styles.content}>
        <View>
          <Text style={[styles.title, { color: theme.textPrimary }]}>
            {targetRole === 'TRAINER' ? 'Quét QR tìm huấn luyện viên' : 'Quét QR tìm học viên'}
          </Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Quét bằng camera hoặc chọn ảnh mã QR từ thư viện. Ảnh chỉ được giải mã trên thiết bị.
          </Text>
        </View>

        {dualRole && !paramTarget ? (
          <View style={styles.row} testID="scanner-target-role-switcher">
            <CoachingButton
              testID="scanner-target-trainer"
              label="Tìm huấn luyện viên"
              secondary={targetRole !== 'TRAINER'}
              onPress={() => setTargetRole('TRAINER')}
            />
            <CoachingButton
              testID="scanner-target-student"
              label="Tìm học viên"
              secondary={targetRole !== 'STUDENT'}
              onPress={() => setTargetRole('STUDENT')}
            />
          </View>
        ) : null}

        {dualRole && !paramTarget && deepLinkParsed?.ok && !targetRoleOverride ? (
          <CoachingCard testID="deep-link-dual-role-prompt">
            <Text style={[styles.label, { color: theme.textPrimary }]}>Chọn tư cách để mở liên kết QR</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              Tài khoản của bạn có cả hai vai trò. Hãy chọn “Tìm huấn luyện viên” hoặc “Tìm học viên” ở trên để tiếp tục xem hồ sơ.
            </Text>
            <CoachingButton
              testID="deep-link-cancel-button"
              label="Hủy"
              secondary
              onPress={handleCancelDeepLink}
            />
          </CoachingCard>
        ) : null}

        {permission?.status === 'denied' && !permission.canAskAgain ? (
          <CoachingState
            testID="camera-permission-denied"
            title="Chưa có quyền máy ảnh"
            message="Vui lòng bật quyền máy ảnh trong Cài đặt thiết bị để quét trực tiếp, hoặc chọn ảnh QR từ thư viện."
          />
        ) : null}
        {permission?.status === 'denied' && !permission.canAskAgain ? (
          <CoachingButton testID="open-settings-button" label="Mở cài đặt" secondary onPress={() => Linking.openSettings()} />
        ) : null}

        {cameraActive && permission?.granted ? (
          <CoachingCard testID="camera-preview-card">
            <View style={localStyles.cameraBox}>
              <CameraView
                testID="qr-camera-view"
                style={StyleSheet.absoluteFill}
                facing="back"
                barcodeScannerSettings={{ barcodeTypes: ['qr'] }}
                onBarcodeScanned={scanLocked ? undefined : handleBarcodeScanned}
              />
            </View>
            <CoachingButton
              testID="stop-camera-button"
              label="Tắt camera"
              secondary
              onPress={() => setCameraActive(false)}
            />
          </CoachingCard>
        ) : (
          <CoachingButton
            testID="open-camera-button"
            label="Mở camera quét QR"
            disabled={decodingImage}
            onPress={handleOpenCamera}
          />
        )}

        <CoachingButton
          testID="pick-qr-image-button"
          label={decodingImage ? 'Đang giải mã ảnh...' : 'Chọn ảnh QR từ thư viện'}
          secondary
          busy={decodingImage}
          disabled={decodingImage}
          onPress={handlePickImage}
        />

        {decodingImage ? (
          <CoachingState testID="decoding-image-state" busy title="Đang giải mã ảnh" message="Đang đọc mã QR trên thiết bị." />
        ) : null}

        {errorMessage ? (
          <CoachingState
            testID="scanner-error-state"
            title="Không đọc được mã QR"
            message={errorMessage}
            onRetry={() => setErrorMessage(null)}
          />
        ) : null}

        {multipleCandidates.length > 1 ? (
          <CoachingCard testID="multiple-qr-picker">
            <Text style={[styles.h2, { color: theme.textPrimary }]}>Phát hiện nhiều mã QR</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>
              Vui lòng chọn mã bạn muốn xem hồ sơ:
            </Text>
            {multipleCandidates.map((candidate, index) => (
              <Pressable
                key={candidate.publicCode}
                testID={`qr-candidate-${index}`}
                accessibilityRole="button"
                accessibilityLabel={`Chọn mã QR ${index + 1}`}
                onPress={() => navigateToPreview(candidate.publicCode, candidate.payloadVersion)}
                style={[localStyles.candidateItem, { borderColor: theme.border }]}
              >
                <Text style={[styles.label, { color: theme.textPrimary }]}>Mã #{index + 1}</Text>
                <Text style={[styles.body, { color: theme.textSecondary }]} numberOfLines={1}>
                  {candidate.publicCode}
                </Text>
              </Pressable>
            ))}
          </CoachingCard>
        ) : null}
      </ScrollView>
    </SafeAreaView>
  );
}

const localStyles = StyleSheet.create({
  cameraBox: {
    height: 280,
    borderRadius: 12,
    overflow: 'hidden',
    backgroundColor: '#000000',
  },
  candidateItem: {
    borderWidth: 1,
    borderRadius: 8,
    padding: spacing.md,
    gap: spacing.xs,
  },
});
