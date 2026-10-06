import React, { useMemo, useRef, useState } from 'react';
import { Modal, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { coachingApi, createCommandKey } from '@/services/coachingApi';
import { CoachingResumeRequest, RelationshipAction, ResumeAction } from '@/types/coaching';
import { availableCoachingActions } from './coachingPolicy';
import { coachingErrorMessage, coachingStatusLabels, isStaleCoachingError } from './coachingMessages';
import { coachingQueryKeys, useRelationshipDetail, useRelationshipHistory } from './coachingQueries';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from './components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';

type PendingAction = { kind: 'relationship'; action: RelationshipAction } | { kind: 'resume'; action: ResumeAction } | { kind: 'request-resume' };
const labels: Record<RelationshipAction | ResumeAction | 'request-resume', string> = {
  accept: 'Chấp nhận', reject: 'Từ chối', cancel: 'Hủy yêu cầu', pause: 'Tạm dừng', end: 'Kết thúc huấn luyện', 'request-resume': 'Yêu cầu tiếp tục',
};

export function RelationshipDetailScreen({ relationshipId }: { relationshipId: string }) {
  const { user } = useAuth();
  const query = useRelationshipDetail(relationshipId);
  const historyQuery = useRelationshipHistory(relationshipId);
  const queryClient = useQueryClient();
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [pendingAction, setPendingAction] = useState<PendingAction | null>(null);
  const [reason, setReason] = useState('');
  const commandKey = useRef<string | null>(null);
  const relationship = query.data?.relationship ?? null;
  const actionPolicy = useMemo(() => relationship && user ? availableCoachingActions(relationship, user.id, query.data?.resume ?? null) : null, [relationship, user, query.data?.resume]);
  const mutation = useMutation({
    mutationFn: async (action: PendingAction) => {
      if (!relationship) throw new Error('Missing relationship');
      const operationKey = commandKey.current ?? createCommandKey();
      commandKey.current = operationKey;
      if (action.kind === 'relationship') return coachingApi.relationshipAction(relationship.id, action.action, relationship.version, reason, operationKey);
      if (action.kind === 'request-resume') return coachingApi.requestResume(relationship.id, relationship.version, reason, operationKey);
      const resume = query.data?.resume as CoachingResumeRequest;
      return coachingApi.resumeAction(relationship.id, resume.id, action.action, relationship.version, resume.version, operationKey);
    },
    onSuccess: async (result, action) => {
      setPendingAction(null); setReason(''); commandKey.current = null;
      queryClient.setQueryData(coachingQueryKeys.detail(relationshipId), result);
      await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.relationships() });
      if (action.kind === 'relationship' && action.action === 'end' && result.relationship?.trainerId === user?.id) {
        queryClient.removeQueries({ queryKey: coachingQueryKeys.detail(relationshipId) });
        queryClient.removeQueries({ queryKey: coachingQueryKeys.history(relationshipId) });
        queryClient.removeQueries({ queryKey: coachingQueryKeys.sharing(relationshipId) });
        router.replace('/coaching' as never);
      } else {
        await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.history(relationshipId) });
        await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.sharing(relationshipId) });
      }
    },
    onError: async (error) => {
      if (isStaleCoachingError(error)) {
        setPendingAction(null); commandKey.current = null;
        await queryClient.invalidateQueries({ queryKey: coachingQueryKeys.detail(relationshipId) });
      }
    },
  });
  const openAction = (action: PendingAction) => { commandKey.current = createCommandKey(); setPendingAction(action); };
  const closeAction = () => { if (!mutation.isPending) { setPendingAction(null); setReason(''); commandKey.current = null; } };
  if (query.isPending) return <CoachingState busy title="Đang tải quan hệ" message="Vui lòng chờ trong giây lát." />;
  if (query.isError || !relationship) return <CoachingState testID="relationship-detail-error" title="Không thể mở quan hệ" message={coachingErrorMessage(query.error)} onRetry={() => query.refetch()} />;
  const requiresReason = pendingAction?.kind === 'relationship' && pendingAction.action === 'end';
  const submitDisabled = mutation.isPending || (requiresReason && !reason.trim());
  const history = historyQuery.data?.pages.flatMap((page) => page.items) ?? [];
  return <ScrollView testID="relationship-detail-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>{relationship.counterparty.displayName}</Text>
    <CoachingCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Trạng thái quan hệ</Text>
      <Text accessibilityRole="text" style={[styles.label, { color: theme.textPrimary }]}>{coachingStatusLabels[relationship.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản {relationship.version}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Chế độ hiện tại: {query.data?.currentPeriod ? (query.data.currentPeriod.mode === 'HUMAN_COACH' ? 'Có huấn luyện viên' : 'Tự tập') : 'Không có kỳ hiệu lực được phép xem'}</Text>
    </CoachingCard>
    {query.data?.resume?.status === 'PENDING' ? <CoachingCard testID="pending-resume-card"><Text style={[styles.h2, { color: theme.textPrimary }]}>Yêu cầu tiếp tục đang chờ</Text><Text style={[styles.body, { color: theme.textSecondary }]}>{query.data.resume.requestedBy === user?.id ? 'Bạn đã gửi yêu cầu.' : 'Đối phương đang chờ quyết định của bạn.'}</Text></CoachingCard> : null}
    {mutation.isError ? <CoachingState testID="lifecycle-error" title="Chưa thể cập nhật quan hệ" message={coachingErrorMessage(mutation.error)} /> : null}
    <View style={styles.row}>
      {actionPolicy?.relationship.map((action) => <CoachingButton key={action} label={labels[action]} danger={action === 'end' || action === 'reject'} onPress={() => openAction({ kind: 'relationship', action })} />)}
      {actionPolicy?.resume.map((action) => <CoachingButton key={`resume-${action}`} label={`${labels[action]} tiếp tục`} danger={action === 'reject'} onPress={() => openAction({ kind: 'resume', action })} />)}
      {actionPolicy?.canRequestResume ? <CoachingButton label={labels['request-resume']} onPress={() => openAction({ kind: 'request-resume' })} /> : null}
    </View>
    <CoachingButton testID="open-sharing-button" label="Xem quyền chia sẻ dữ liệu" secondary onPress={() => router.push(`/coaching/${relationship.id}/sharing` as never)} />
    <CoachingCard testID="relationship-history">
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Lịch sử quan hệ</Text>
      {historyQuery.isPending ? <CoachingState busy title="Đang tải lịch sử" message="Vui lòng chờ trong giây lát." /> : null}
      {historyQuery.isError ? <CoachingState title="Chưa thể tải lịch sử" message={coachingErrorMessage(historyQuery.error)} onRetry={() => historyQuery.refetch()} /> : null}
      {!historyQuery.isPending && !historyQuery.isError && history.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có sự kiện lịch sử.</Text> : null}
      {history.map((event) => <View key={event.id}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{coachingStatusLabels[event.toStatus]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{event.changedBy === user?.id ? 'Bạn' : event.changedBy ? 'Đối phương' : 'Hệ thống'} · {new Date(event.changedAt).toLocaleString('vi-VN')}</Text>
        {event.reason ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do: {event.reason}</Text> : null}
      </View>)}
      {historyQuery.hasNextPage ? <CoachingButton label={historyQuery.isFetchingNextPage ? 'Đang tải thêm lịch sử' : 'Tải thêm lịch sử'} busy={historyQuery.isFetchingNextPage} disabled={historyQuery.isFetchingNextPage} secondary onPress={() => historyQuery.fetchNextPage()} /> : null}
    </CoachingCard>
    <Modal visible={Boolean(pendingAction)} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeAction}>
      <View style={{ flex: 1, backgroundColor: '#00000066', justifyContent: 'center', padding: spacing.lg }}>
        <CoachingCard testID="lifecycle-confirmation">
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận thao tác</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>{pendingAction ? `${labels[pendingAction.kind === 'request-resume' ? 'request-resume' : pendingAction.action]} sẽ được backend kiểm tra lại theo trạng thái mới nhất.` : ''}</Text>
          {requiresReason ? <><Text style={[styles.label, { color: theme.textPrimary }]}>Lý do kết thúc</Text><TextInput testID="end-reason-input" accessibilityLabel="Lý do kết thúc huấn luyện" value={reason} onChangeText={setReason} maxLength={500} multiline style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} /></> : null}
          <View style={styles.row}><CoachingButton label="Quay lại" secondary disabled={mutation.isPending} onPress={closeAction} /><CoachingButton testID="confirm-lifecycle-action" label={mutation.isPending ? 'Đang xử lý' : 'Xác nhận'} busy={mutation.isPending} disabled={submitDisabled} danger={pendingAction?.kind === 'relationship' && ['end', 'reject'].includes(pendingAction.action)} onPress={() => pendingAction && mutation.mutate(pendingAction)} /></View>
        </CoachingCard>
      </View>
    </Modal>
  </ScrollView>;
}

