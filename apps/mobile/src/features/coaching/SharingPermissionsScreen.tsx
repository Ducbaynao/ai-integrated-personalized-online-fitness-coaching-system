import React, { useRef, useState } from 'react';
import { Modal, ScrollView, Text, View, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { accessLevels, coachingApi, createCommandKey } from '@/services/coachingApi';
import { DataAccessLevel, PermissionSummaryItem, SharingDecision } from '@/types/coaching';
import { canEditSharing } from './coachingPolicy';
import { accessLevelLabels, coachingErrorMessage, coachingStatusLabels, dataScopeLabels, isStaleCoachingError, permissionStateLabels } from './coachingMessages';
import { coachingQueryKeys, useRelationshipDetail, useSharingSummary } from './coachingQueries';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from './components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';

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
  return <CoachingCard testID={`permission-${item.dataScope}`}>
    <Text style={[styles.h2, { color: theme.textPrimary }]}>{dataScopeLabels[item.dataScope]}</Text>
    <Text accessibilityRole="text" style={[styles.label, { color: theme.textPrimary }]}>{permissionStateLabels[item.state]}{item.accessLevel ? ` · ${accessLevelLabels[item.accessLevel]}` : ''}</Text>
    {item.historyFrom || item.historyUntil ? <Text style={[styles.body, { color: theme.textSecondary }]}>Phạm vi lịch sử: {item.historyFrom ? new Date(item.historyFrom).toLocaleString('vi-VN') : 'từ đầu'} – {item.historyUntil ? new Date(item.historyUntil).toLocaleString('vi-VN') : 'hiện tại'}</Text> : null}
    {item.validUntil ? <Text style={[styles.body, { color: theme.textSecondary }]}>Có hiệu lực đến {new Date(item.validUntil).toLocaleString('vi-VN')}</Text> : null}
    {editable ? <>
      <View style={styles.row}>{(['ALLOW', 'DENY'] as SharingDecision[]).map((value) => <CoachingButton key={value} label={value === 'ALLOW' ? 'Cho phép' : 'Không chia sẻ'} secondary={decision !== value} disabled={busy} onPress={() => { saveKey.current = null; setDecision(value); }} />)}</View>
      <View style={styles.row}>{accessLevels.map((value) => <CoachingButton key={value} label={accessLevelLabels[value]} secondary={level !== value} disabled={busy} onPress={() => { saveKey.current = null; setLevel(value); }} />)}</View>
      {(save.isError || revoke.isError) ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{coachingErrorMessage(save.error ?? revoke.error)}</Text> : null}
      <View style={styles.row}><CoachingButton label={item.version === null ? 'Thiết lập quyền' : 'Lưu thay đổi'} disabled={busy || (item.version !== null && decision === item.decision && level === item.accessLevel)} onPress={() => { saveKey.current = createCommandKey(); setConfirmation('save'); }} />
      {item.permissionId && item.version !== null ? <CoachingButton label="Thu hồi" danger disabled={busy} onPress={() => { revokeKey.current = createCommandKey(); setConfirmation('revoke'); }} /> : null}</View>
      <Modal visible={confirmation !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeConfirmation}>
        <View style={{ flex: 1, backgroundColor: '#00000066', justifyContent: 'center', padding: spacing.lg }}>
          <CoachingCard testID={`sharing-confirmation-${item.dataScope}`}>
            <Text style={[styles.h2, { color: theme.textPrimary }]}>{confirmation === 'revoke' ? 'Xác nhận thu hồi quyền' : 'Xác nhận thay đổi quyền'}</Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>{confirmation === 'revoke' ? 'Huấn luyện viên sẽ không còn quyền trong phạm vi này sau khi backend xác nhận.' : `Phạm vi sẽ được đặt thành ${decision === 'ALLOW' ? 'Cho phép' : 'Không chia sẻ'} với mức ${accessLevelLabels[level]}.`}</Text>
            {(save.isError || revoke.isError) ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{coachingErrorMessage(save.error ?? revoke.error)}</Text> : null}
            <View style={styles.row}>
              <CoachingButton label="Quay lại" secondary disabled={busy} onPress={closeConfirmation} />
              <CoachingButton label={confirmation === 'revoke' ? (revoke.isPending ? 'Đang thu hồi' : 'Xác nhận thu hồi') : (save.isPending ? 'Đang lưu' : 'Xác nhận lưu quyền')} busy={busy} disabled={busy} danger={confirmation === 'revoke'} onPress={() => confirmation === 'revoke' ? revoke.mutate() : save.mutate()} />
            </View>
          </CoachingCard>
        </View>
      </Modal>
    </> : null}
  </CoachingCard>;
}

export function SharingPermissionsScreen({ relationshipId }: { relationshipId: string }) {
  const { user, activeCapability } = useAuth();
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const query = useSharingSummary(relationshipId);
  const theme = getSemanticColors(useColorScheme() === 'dark');
  if (query.isPending || relationshipQuery.isPending) return <CoachingState busy title="Đang tải quyền chia sẻ" message="Hệ thống đang lấy trạng thái hiện tại." />;
  if (query.isError || relationshipQuery.isError || !relationshipQuery.data?.relationship) return <CoachingState testID="sharing-error" title="Không thể xem quyền chia sẻ" message={coachingErrorMessage(query.error ?? relationshipQuery.error)} onRetry={() => { query.refetch(); relationshipQuery.refetch(); }} />;
  const editable = activeCapability === 'STUDENT' && Boolean(user) && canEditSharing(relationshipQuery.data.relationship, user!.id);
  return <ScrollView testID="sharing-permissions-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Quyền chia sẻ dữ liệu</Text>
    <Text style={[styles.label, { color: theme.textPrimary }]}>Quan hệ: {coachingStatusLabels[query.data.relationshipStatus]}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>{editable ? 'Bạn quyết định từng phạm vi dữ liệu được chia sẻ.' : 'Trạng thái chỉ đọc do backend xác định.'}</Text>
    {query.data.items.map((item) => <PermissionRow key={item.dataScope} item={item} relationshipId={relationshipId} editable={editable} />)}
  </ScrollView>;
}
