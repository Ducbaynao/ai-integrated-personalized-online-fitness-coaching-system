import { formatInstantVietnam } from '@/localization/instantDisplay';
import React, { useMemo, useRef, useState } from 'react';
import { KeyboardAvoidingView, Modal, Platform, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { coachingApi, createCommandKey } from '@/services/coachingApi';
import { CoachingResumeRequest, RelationshipAction, ResumeAction } from '@/types/coaching';
import { availableCoachingActions } from './coachingPolicy';
import { coachingErrorMessage, coachingStatusLabels, isStaleCoachingError } from './coachingMessages';
import { coachingQueryKeys, useRelationshipDetail, useRelationshipHistory } from './coachingQueries';
import { coachingUiStyles as styles } from './components/CoachingUi';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { type AppIconName } from '@/design-system/components/AppIcon';
import { NavigationAction } from '@/design-system/components/NavigationAction';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { removeStudentWorkoutPlanCache } from '@/features/workout-plan/workoutPlanCache';

type PendingAction = { kind: 'relationship'; action: RelationshipAction } | { kind: 'resume'; action: ResumeAction } | { kind: 'request-resume' };
const labels: Record<RelationshipAction | ResumeAction | 'request-resume', string> = {
  accept: 'Chấp nhận', reject: 'Từ chối', cancel: 'Hủy yêu cầu', pause: 'Tạm dừng', end: 'Kết thúc huấn luyện', 'request-resume': 'Yêu cầu tiếp tục',
};
const actionIcons: Record<RelationshipAction | ResumeAction | 'request-resume', AppIconName> = {
  accept: 'check', reject: 'back', cancel: 'back', pause: 'pause', end: 'logout', 'request-resume': 'play',
};
const actionImpacts: Record<RelationshipAction | 'request-resume', string> = {
  accept: 'Chấp nhận sẽ bắt đầu một kỳ huấn luyện mới với huấn luyện viên. Lịch sử tập luyện được giữ nguyên; quyền dữ liệu vẫn theo từng phạm vi chia sẻ.',
  reject: 'Từ chối sẽ đóng lời mời đang chờ. Lời mời này sẽ không bắt đầu huấn luyện hoặc cấp quyền dữ liệu.',
  cancel: 'Hủy sẽ đóng yêu cầu bạn đã gửi. Đối phương sẽ không thể chấp nhận yêu cầu này.',
  pause: 'Tạm dừng sẽ ngừng quyền huấn luyện của PT và chuyển học viên sang tự tập. Tiếp tục cần sự đồng ý của cả hai bên; lịch sử được giữ nguyên.',
  end: 'Kết thúc sẽ ngừng quyền huấn luyện và quyền truy cập dữ liệu của PT trong quan hệ này. Học viên tiếp tục tự tập; mục tiêu, kế hoạch và lịch sử được giữ nguyên.',
  'request-resume': 'Quan hệ vẫn tạm dừng cho đến khi đối phương chấp nhận. Yêu cầu này chưa khôi phục quyền huấn luyện hoặc chia sẻ dữ liệu.',
};

export function RelationshipDetailScreen({ relationshipId }: { relationshipId: string }) {
  const { user, activeCapability } = useAuth();
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
      if (!relationship) throw new Error('Thiếu thông tin quan hệ huấn luyện');
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
      const updatedRelationship = result.relationship;
      if (updatedRelationship && updatedRelationship.trainerId === user?.id && updatedRelationship.status !== 'ACTIVE') {
        removeStudentWorkoutPlanCache(queryClient, updatedRelationship.studentId);
      }
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
  if (query.isPending) return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><View style={styles.content}><AppCard state="loading" title="Đang tải quan hệ" message="Vui lòng chờ trong giây lát." /></View></SafeAreaView>;
  if (query.isError || !relationship) return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><View style={styles.content}><AppCard state="error" testID="relationship-detail-error" title="Không thể mở quan hệ" message={coachingErrorMessage(query.error)} onRetry={() => query.refetch()} /></View></SafeAreaView>;
  const requiresReason = pendingAction?.kind === 'relationship' && pendingAction.action === 'end';
  const submitDisabled = mutation.isPending || (requiresReason && !reason.trim());
  const history = historyQuery.data?.pages.flatMap((page) => page.items) ?? [];
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="relationship-detail-screen" style={styles.screen} contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]}>
    <Text accessibilityRole="header" aria-level={1} style={[styles.title, { color: theme.textPrimary }]}>{relationship.counterparty.displayName}</Text>
    <AppCard title="Trạng thái quan hệ" icon="coaching">
      <Text accessibilityRole="text" style={[styles.label, { color: theme.textPrimary }]}>{coachingStatusLabels[relationship.status]}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản {relationship.version}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Chế độ hiện tại: {query.data?.currentPeriod ? (query.data.currentPeriod.mode === 'HUMAN_COACH' ? 'Có huấn luyện viên' : 'Tự tập') : 'Không có kỳ hiệu lực được phép xem'}</Text>
      <Text style={[styles.body, { color: theme.textPrimary }]}>Người gửi: {relationship.requestedBy === user?.id ? 'Bạn' : relationship.counterparty.displayName}</Text>
      <Text style={[styles.body, { color: theme.textPrimary }]}>Người nhận: {relationship.requestedBy === user?.id ? relationship.counterparty.displayName : 'Bạn'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Gửi lúc: {formatInstantVietnam(relationship.requestedAt) ?? 'Chưa rõ thời điểm'}</Text>
    </AppCard>
    {query.data?.currentPeriod ? <AppCard testID="current-coaching-period" title="Kỳ huấn luyện hiện tại" icon="calendar">
      <Text style={[styles.label, { color: theme.textPrimary }]}>{query.data.currentPeriod.mode === 'HUMAN_COACH' ? 'Có huấn luyện viên' : 'Tự tập'}</Text>
      <Text style={[styles.body, { color: theme.textPrimary }]}>Bắt đầu: {formatInstantVietnam(query.data.currentPeriod.startedAt) ?? 'Chưa rõ thời điểm'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Kết thúc: {query.data.currentPeriod.endedAt ? formatInstantVietnam(query.data.currentPeriod.endedAt) ?? 'Chưa rõ thời điểm' : 'Chưa có thời điểm kết thúc'}</Text>
    </AppCard> : null}
    {query.data?.resume?.status === 'PENDING' ? <AppCard testID="pending-resume-card" title="Yêu cầu tiếp tục đang chờ" icon="pause"><Text style={[styles.body, { color: theme.textSecondary }]}>{query.data.resume.requestedBy === user?.id ? 'Bạn đã gửi yêu cầu.' : 'Đối phương đang chờ quyết định của bạn.'}</Text></AppCard> : null}
    {mutation.isError ? <AppCard state="error" testID="lifecycle-error" title="Chưa thể cập nhật quan hệ" message={coachingErrorMessage(mutation.error)} /> : null}
    <View style={styles.row}>
      {actionPolicy?.relationship.map((action) => <AppButton key={action} icon={actionIcons[action]} label={labels[action]} variant={action === 'accept' ? 'primary' : 'secondary'} tone={action === 'end' || action === 'reject' ? 'danger' : 'brand'} disabled={mutation.isPending} onPress={() => openAction({ kind: 'relationship', action })} />)}
      {actionPolicy?.resume.map((action) => <AppButton key={`resume-${action}`} icon={actionIcons[action]} label={`${labels[action]} tiếp tục`} variant={action === 'accept' ? 'primary' : 'secondary'} tone={action === 'reject' ? 'danger' : 'brand'} disabled={mutation.isPending} onPress={() => openAction({ kind: 'resume', action })} />)}
      {actionPolicy?.canRequestResume ? <AppButton icon="play" label={labels['request-resume']} disabled={mutation.isPending} onPress={() => openAction({ kind: 'request-resume' })} /> : null}
    </View>
    <Text accessibilityRole="header" aria-level={2} style={[styles.h2, { color: theme.textPrimary }]}>Trao đổi và dữ liệu</Text>
    <NavigationAction testID="open-sharing-button" title="Xem quyền chia sẻ dữ liệu" accessibilityLabel="Xem quyền chia sẻ dữ liệu" icon="shield" onPress={() => router.push(`/coaching/${relationship.id}/sharing` as never)} />
    {query.data?.currentPeriod?.mode === 'HUMAN_COACH' && relationship.status === 'ACTIVE'
      ? <NavigationAction testID="open-chat-button" title="Nhắn tin trong kỳ huấn luyện" accessibilityLabel="Nhắn tin trong kỳ huấn luyện" icon="chat"
          onPress={() => router.push(`/chat/new?studentId=${relationship.studentId}` as never)} /> : null}
    {query.data?.currentPeriod?.mode === 'HUMAN_COACH' && relationship.status === 'ACTIVE'
      ? <NavigationAction testID="open-appointments-button" title="Lịch hẹn huấn luyện" accessibilityLabel="Lịch hẹn huấn luyện" icon="calendar"
          onPress={() => router.push(`/appointments?studentId=${relationship.studentId}` as never)} /> : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id ?
      <NavigationAction testID="open-student-nutrition-button" title="Xem nhật ký dinh dưỡng học viên" accessibilityLabel="Xem nhật ký dinh dưỡng học viên" icon="nutrition"
        onPress={() => router.push(`/coaching/${relationship.id}/nutrition` as never)} /> : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE' ?
      <NavigationAction testID="open-student-goal-button" title="Mục tiêu thể chất học viên" accessibilityLabel="Mục tiêu thể chất học viên" icon="goal"
        onPress={() => router.push(`/coaching/${relationship.id}/goal` as never)} /> : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE' ?
      <NavigationAction testID="open-student-nutrition-strategy-button" title="Mục tiêu dinh dưỡng học viên" accessibilityLabel="Mục tiêu dinh dưỡng học viên" icon="nutrition"
        onPress={() => router.push(`/coaching/${relationship.id}/nutrition-strategy` as never)} /> : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE' ? <NavigationAction testID="open-program-button" title="Mở chương trình tập luyện" accessibilityLabel="Mở chương trình tập luyện" icon="workout" onPress={() => router.push(`/coaching/${relationship.id}/program` as never)} /> : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE' ? <NavigationAction testID="open-recurring-proposals" title="Đề xuất lịch tập lặp" accessibilityLabel="Đề xuất lịch tập lặp" icon="calendar" onPress={() => router.push(`/coaching/${relationship.id}/planned-workout-series` as never)} /> : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE'
      && query.data?.currentPeriod?.mode === 'HUMAN_COACH'
      ? <NavigationAction testID="open-trainer-attention-button" title="Đánh giá và tín hiệu chú ý" accessibilityLabel="Đánh giá và tín hiệu chú ý" icon="attention"
          onPress={() => router.push(`/coaching/${relationship.id}/attention` as never)} /> : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE' ? (
      <NavigationAction
        testID="open-student-progress-button"
        title="Tiến độ và số đo học viên" accessibilityLabel="Tiến độ và số đo học viên" icon="measurement"

        onPress={() => router.push(`/coaching/${relationship.id}/progress` as never)}
      />
    ) : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE' ? (
      <NavigationAction
        testID="open-student-progress-summary-button"
        title="Tóm tắt tiến độ học viên (AI)" accessibilityLabel="Tóm tắt tiến độ học viên (AI)" icon="summary"

        onPress={() => router.push(`/coaching/${relationship.id}/progress-summary` as never)}
      />
    ) : null}
    {activeCapability === 'TRAINER' && relationship.trainerId === user?.id && relationship.status === 'ACTIVE' ? (
      <NavigationAction
        testID="open-student-ai-knowledge-button"
        title="Tra cứu kiến thức cá nhân hóa (AI)" accessibilityLabel="Tra cứu kiến thức cá nhân hóa (AI)" icon="knowledge"

        onPress={() => router.push(`/coaching/${relationship.id}/ai-knowledge` as never)}
      />
    ) : null}
    <AppCard testID="relationship-history" title="Lịch sử quan hệ" icon="history">
      {historyQuery.isPending ? <AppCard state="loading" title="Đang tải lịch sử" message="Vui lòng chờ trong giây lát." /> : null}
      {historyQuery.isError ? <AppCard state="error" title="Chưa thể tải lịch sử" message={coachingErrorMessage(historyQuery.error)} onRetry={() => historyQuery.refetch()} /> : null}
      {!historyQuery.isPending && !historyQuery.isError && history.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có sự kiện lịch sử.</Text> : null}
      {history.map((event) => <View key={event.id}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{coachingStatusLabels[event.toStatus]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{event.changedBy === user?.id ? 'Bạn' : event.changedBy ? 'Đối phương' : 'Hệ thống'} · {(formatInstantVietnam(event.changedAt) ?? 'Chưa rõ thời điểm')}</Text>
        {event.reason ? <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do: {event.reason}</Text> : null}
      </View>)}
      {historyQuery.hasNextPage ? <AppButton label={historyQuery.isFetchingNextPage ? 'Đang tải thêm lịch sử' : 'Tải thêm lịch sử'} loading={historyQuery.isFetchingNextPage} disabled={historyQuery.isFetchingNextPage} variant="secondary" onPress={() => historyQuery.fetchNextPage()} /> : null}
    </AppCard>
    <Modal visible={Boolean(pendingAction)} transparent animationType="fade" accessibilityViewIsModal onRequestClose={closeAction}>
      <SafeAreaView style={{ flex: 1, backgroundColor: '#00000066' }}>
      <KeyboardAvoidingView behavior={Platform.OS === 'ios' ? 'padding' : undefined} style={{ flex: 1 }}>
      <ScrollView keyboardShouldPersistTaps="handled" contentContainerStyle={{ flexGrow: 1, justifyContent: 'center', padding: spacing.lg }}>
        <AppCard testID="lifecycle-confirmation" title="Xác nhận thao tác" icon={pendingAction?.kind === 'request-resume' ? 'play' : pendingAction ? actionIcons[pendingAction.action] : 'info'} style={{ width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }}>
          <Text style={[styles.label, { color: theme.textPrimary }]}>{relationship.counterparty.displayName}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>{pendingAction ? `${labels[pendingAction.kind === 'request-resume' ? 'request-resume' : pendingAction.action]} sẽ được hệ thống kiểm tra lại theo trạng thái mới nhất.` : ''}</Text>
          {pendingAction ? <Text style={[styles.body, { color: theme.textPrimary }]}>{pendingAction.kind === 'resume'
            ? pendingAction.action === 'accept' ? actionImpacts.accept : 'Quan hệ vẫn tạm dừng. Thao tác này chỉ đóng yêu cầu tiếp tục đang chờ và không khôi phục quyền huấn luyện.'
            : actionImpacts[pendingAction.kind === 'request-resume' ? 'request-resume' : pendingAction.action]}</Text> : null}
          {mutation.isError ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{coachingErrorMessage(mutation.error)}</Text> : null}
          {requiresReason ? <><Text style={[styles.label, { color: theme.textPrimary }]}>Lý do kết thúc</Text><TextInput testID="end-reason-input" accessibilityLabel="Lý do kết thúc huấn luyện" value={reason} onChangeText={setReason} maxLength={500} multiline textAlignVertical="top" style={[styles.input, { minHeight: layout.buttonHeight * 2, paddingVertical: spacing.md }, { color: theme.textPrimary, borderColor: theme.border }]} /></> : null}
          <View style={styles.row}><AppButton icon="back" label="Quay lại" variant="secondary" disabled={mutation.isPending} onPress={closeAction} /><AppButton icon={pendingAction?.kind === 'request-resume' ? 'play' : pendingAction ? actionIcons[pendingAction.action] : 'check'} testID="confirm-lifecycle-action" label={mutation.isPending ? 'Đang xử lý' : 'Xác nhận'} loading={mutation.isPending} disabled={submitDisabled} variant={pendingAction && pendingAction.kind !== 'request-resume' && (pendingAction.action === 'end' || pendingAction.action === 'reject') ? 'danger' : 'primary'} onPress={() => pendingAction && mutation.mutate(pendingAction)} /></View>
        </AppCard>
      </ScrollView>
      </KeyboardAvoidingView>
      </SafeAreaView>
    </Modal>
  </ScrollView></SafeAreaView>;
}
