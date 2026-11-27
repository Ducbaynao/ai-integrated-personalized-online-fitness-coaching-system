import { formatInstantVietnam } from '@/localization/instantDisplay';
import React, { useRef, useState } from 'react';
import { Modal, Pressable, ScrollView, StyleSheet, Text, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { accessLevels, coachingApi, createCommandKey } from '@/services/coachingApi';
import { DataAccessLevel, PermissionSummaryItem, SharingDecision } from '@/types/coaching';
import { canEditSharing } from './coachingPolicy';
import { accessLevelLabels, coachingErrorMessage, coachingStatusLabels, dataScopeLabels, isStaleCoachingError, permissionStateLabels } from './coachingMessages';
import { coachingQueryKeys, useRelationshipDetail, useSharingSummary } from './coachingQueries';
import { coachingUiStyles as styles } from './components/CoachingUi';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { type AppIconName } from '@/design-system/components/AppIcon';

const scopeIcons: Record<PermissionSummaryItem['dataScope'], AppIconName> = {
  FITNESS_GOAL: 'goal', WORKOUT_PLAN: 'workout', WORKOUT_HISTORY: 'history', WORKOUT_PLAN_HISTORY: 'history',
  BODY_METRICS: 'measurement', PROGRESS_PHOTOS: 'photo', NUTRITION_LOGS: 'nutrition', NUTRITION_GOAL: 'nutrition', AI_RECOMMENDATIONS: 'knowledge',
};

function PermissionRow({ item, relationshipId, editable }: { item: PermissionSummaryItem; relationshipId: string; editable: boolean }) {
  const [decision, setDecision] = useState<SharingDecision>(item.decision ?? 'ALLOW');
  const [level, setLevel] = useState<DataAccessLevel>(item.accessLevel ?? 'VIEW');
  const [confirmation, setConfirmation] = useState<'save' | 'revoke' | null>(null);
  const saveKey = useRef<string | null>(null);
  const revokeKey = useRef<string | null>(null);
  const queryClient = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const refresh = async () => queryClient.invalidateQueries({ queryKey: coachingQueryKeys.sharing(relationshipId) });
  const save = useMutation({ mutationFn: () => {
    const commandKey = saveKey.current ?? createCommandKey();
    saveKey.current = commandKey;
    return coachingApi.setSharing(relationshipId, {
      dataScope: item.dataScope,
      decision,
      accessLevel: level,
      ...(item.version !== null ? { expectedPermissionVersion: item.version } : {}),
      ...(item.historyFrom ? { historyFrom: item.historyFrom } : {}),
      ...(item.historyUntil ? { historyUntil: item.historyUntil } : {}),
      ...(item.version !== null && item.validUntil ? { validUntil: item.validUntil } : {}),
    }, commandKey);
  }, onSuccess: async () => { saveKey.current = null; setConfirmation(null); await refresh(); }, onError: async (error) => { if (isStaleCoachingError(error)) { saveKey.current = null; setConfirmation(null); await refresh(); } } });
  const revoke = useMutation({ mutationFn: () => {
    const commandKey = revokeKey.current ?? createCommandKey();
    revokeKey.current = commandKey;
    return coachingApi.revokeSharing(relationshipId, item.permissionId!, item.version!, undefined, commandKey);
  }, onSuccess: async () => { revokeKey.current = null; setConfirmation(null); await refresh(); }, onError: async (error) => { if (isStaleCoachingError(error)) { revokeKey.current = null; setConfirmation(null); await refresh(); } } });
  const busy = save.isPending || revoke.isPending;
  const closeConfirmation = () => {
    if (busy) return;
    if (confirmation === 'save') saveKey.current = null;
    if (confirmation === 'revoke') revokeKey.current = null;
    save.reset(); revoke.reset(); setConfirmation(null);
  };
  return <AppCard testID={`permission-${item.dataScope}`} title={dataScopeLabels[item.dataScope]} icon={scopeIcons[item.dataScope]}>
    <Text accessibilityRole="text" style={[styles.label, { color: theme.textPrimary }]}>{permissionStateLabels[item.state]}{item.accessLevel ? ` · ${accessLevelLabels[item.accessLevel]}` : ''}</Text>
    {item.historyFrom || item.historyUntil ? <Text style={[styles.body, { color: theme.textSecondary }]}>Phạm vi lịch sử: {item.historyFrom ? (formatInstantVietnam(item.historyFrom) ?? 'Chưa rõ thời điểm') : 'từ đầu'} – {item.historyUntil ? (formatInstantVietnam(item.historyUntil) ?? 'Chưa rõ thời điểm') : 'hiện tại'}</Text> : null}
    {item.validUntil ? <Text style={[styles.body, { color: theme.textSecondary }]}>Có hiệu lực đến {(formatInstantVietnam(item.validUntil) ?? 'Chưa rõ thời điểm')}</Text> : null}
    {editable ? <>
      <Text style={[styles.label, { color: theme.textPrimary }]}>Quyết định chia sẻ</Text>
      <View accessibilityRole="radiogroup" accessibilityLabel={`Quyết định chia sẻ ${dataScopeLabels[item.dataScope]}`} style={styles.row}>
        {(['ALLOW', 'DENY'] as SharingDecision[]).map((value) => <Pressable key={value} accessibilityRole="radio" accessibilityLabel={value === 'ALLOW' ? 'Cho phép' : 'Không chia sẻ'}
          accessibilityState={{ checked: decision === value, disabled: busy }} disabled={busy}
          onPress={() => { saveKey.current = null; setDecision(value); }}
          style={({ pressed }) => [localStyles.option, { backgroundColor: pressed || decision === value ? theme.surfaceSubtle : theme.surface, borderColor: decision === value ? theme.primary : theme.border, borderWidth: decision === value ? 2 : 1 }]}>
          <AppIcon name={decision === value ? 'check' : 'info'} color={theme.primary} />
          <Text style={[styles.label, localStyles.optionLabel, { color: theme.textPrimary }]}>{value === 'ALLOW' ? 'Cho phép' : 'Không chia sẻ'}</Text>
        </Pressable>)}
      </View>
      <Text style={[styles.label, { color: theme.textPrimary }]}>Mức quyền</Text>
      <View accessibilityRole="radiogroup" accessibilityLabel={`Mức quyền ${dataScopeLabels[item.dataScope]}`} style={styles.row}>
        {accessLevels.map((value) => <Pressable key={value} accessibilityRole="radio" accessibilityLabel={accessLevelLabels[value]}
          accessibilityState={{ checked: level === value, disabled: busy }} disabled={busy}
          onPress={() => { saveKey.current = null; setLevel(value); }}
          style={({ pressed }) => [localStyles.option, { backgroundColor: pressed || level === value ? theme.surfaceSubtle : theme.surface, borderColor: level === value ? theme.primary : theme.border, borderWidth: level === value ? 2 : 1 }]}>
          <AppIcon name={level === value ? 'check' : 'info'} color={theme.primary} />
          <Text style={[styles.label, localStyles.optionLabel, { color: theme.textPrimary }]}>{accessLevelLabels[value]}</Text>
        </Pressable>)}
      </View>
      {(save.isError || revoke.isError) ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{coachingErrorMessage(save.error ?? revoke.error)}</Text> : null}
      <View style={styles.row}><AppButton icon="check" label={item.version === null ? 'Thiết lập quyền' : 'Lưu thay đổi'} disabled={busy || (item.version !== null && decision === item.decision && level === item.accessLevel)} onPress={() => { saveKey.current = createCommandKey(); setConfirmation('save'); }} />
      {item.permissionId && item.version !== null ? <AppButton icon="logout" label="Thu hồi" variant="secondary" tone="danger" disabled={busy} onPress={() => { revokeKey.current = createCommandKey(); setConfirmation('revoke'); }} /> : null}</View>
      <Modal visible={confirmation !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeConfirmation}>
        <SafeAreaView style={{ flex: 1, backgroundColor: '#00000066' }}>
        <ScrollView contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: spacing.lg }}>
          <AppCard testID={`sharing-confirmation-${item.dataScope}`} title={confirmation === 'revoke' ? 'Xác nhận thu hồi quyền' : 'Xác nhận thay đổi quyền'} icon="shield" style={{ width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }}>
            <Text style={[styles.label, { color: theme.textPrimary }]}>{dataScopeLabels[item.dataScope]}</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>{confirmation === 'revoke' ? 'Huấn luyện viên sẽ không còn quyền trong phạm vi này sau khi hệ thống xác nhận.' : `Phạm vi sẽ được đặt thành ${decision === 'ALLOW' ? 'Cho phép' : 'Không chia sẻ'} với mức ${accessLevelLabels[level]}.`}</Text>
            {(save.isError || revoke.isError) ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{coachingErrorMessage(save.error ?? revoke.error)}</Text> : null}
            <View style={styles.row}>
              <AppButton icon="back" label="Quay lại" variant="secondary" disabled={busy} onPress={closeConfirmation} />
              <AppButton icon={confirmation === 'revoke' ? 'logout' : 'check'} label={confirmation === 'revoke' ? (revoke.isPending ? 'Đang thu hồi' : 'Xác nhận thu hồi') : (save.isPending ? 'Đang lưu' : 'Xác nhận lưu quyền')} loading={busy} disabled={busy} variant={confirmation === 'revoke' ? 'danger' : 'primary'} onPress={() => confirmation === 'revoke' ? revoke.mutate() : save.mutate()} />
            </View>
          </AppCard>
        </ScrollView>
        </SafeAreaView>
      </Modal>
    </> : null}
  </AppCard>;
}

export function SharingPermissionsScreen({ relationshipId }: { relationshipId: string }) {
  const { user, activeCapability } = useAuth();
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const query = useSharingSummary(relationshipId);
  const theme = getSemanticColors(useColorScheme() === 'dark');
  if (query.isPending || relationshipQuery.isPending) return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><View style={styles.content}><AppCard state="loading" title="Đang tải quyền chia sẻ" message="Hệ thống đang lấy trạng thái hiện tại." /></View></SafeAreaView>;
  if (query.isError || relationshipQuery.isError || !relationshipQuery.data?.relationship) return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><View style={styles.content}><AppCard state="error" testID="sharing-error" title="Không thể xem quyền chia sẻ" message={coachingErrorMessage(query.error ?? relationshipQuery.error)} onRetry={() => { query.refetch(); relationshipQuery.refetch(); }} /></View></SafeAreaView>;
  const editable = activeCapability === 'STUDENT' && Boolean(user) && canEditSharing(relationshipQuery.data.relationship, user!.id);
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="sharing-permissions-screen" style={styles.screen} contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]}>
    <Text accessibilityRole="header" aria-level={1} style={[styles.title, { color: theme.textPrimary }]}>Quyền chia sẻ dữ liệu</Text>
    <AppCard title={relationshipQuery.data.relationship.counterparty.displayName} icon="profile">
    <Text style={[styles.label, { color: theme.textPrimary }]}>Quan hệ: {coachingStatusLabels[query.data.relationshipStatus]}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>{editable ? 'Bạn quyết định từng phạm vi dữ liệu được chia sẻ.' : 'Trạng thái chỉ đọc do hệ thống xác định.'}</Text>
    <Text style={[styles.body, { color: theme.textPrimary }]}>Đã chia sẻ không đồng nghĩa đã có dữ liệu. Quyền truy cập còn phụ thuộc quan hệ, kỳ huấn luyện và thời hạn hiện hành.</Text>
    </AppCard>
    {query.data.items.map((item) => <PermissionRow key={item.dataScope} item={item} relationshipId={relationshipId} editable={editable} />)}
  </ScrollView></SafeAreaView>;
}

const localStyles = StyleSheet.create({
  option: { flexBasis: '45%', flexGrow: 1, minWidth: layout.minimumTouchTarget, minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md, padding: spacing.md, flexDirection: 'row', gap: spacing.sm, alignItems: 'center' },
  optionLabel: { flex: 1, minWidth: 0 },
});
