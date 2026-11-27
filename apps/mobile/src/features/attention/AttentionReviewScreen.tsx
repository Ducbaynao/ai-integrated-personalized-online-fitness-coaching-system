import { layout, spacing } from '@/design-system/tokens/spacing';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import React, { useEffect, useMemo, useState } from 'react';
import { ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import moment from 'moment-timezone';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { attentionReviewApi, AttentionPage, AttentionSignal, CompleteReviewBody, ReviewTask, SignalActionBody } from '@/services/attentionReviewApi';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { TimezoneContextPicker } from '@/components/ui/TimezoneContextPicker';
import { formatTimezoneDisplay, resolveNewRecordDefaultTimezone } from '@/features/shared/timezoneUtils';
import { formatInstantVietnam, parseExactInstant } from '@/localization/instantDisplay';

type Pending =
  | { kind: 'timezone'; timezone: string; key: string }
  | { kind: 'complete'; id: string; body: CompleteReviewBody }
  | { kind: 'ackReview'; id: string; version: number; key: string }
  | { kind: 'signal'; id: string; body: SignalActionBody };

const labelStatus: Record<ReviewTask['status'], string> = {
  OPEN: 'Cần đánh giá', COMPLETED: 'Đã hoàn tất', CANCELLED: 'Đã hủy theo kỳ huấn luyện',
};
const signalTitle: Record<AttentionSignal['rule'], string> = {
  MISSED_PLANNED_V1: 'Buổi tập dự kiến đã lỡ',
  REVIEW_OVERDUE_V1: 'Đánh giá huấn luyện quá hạn',
  RETURN_AFTER_100_DAYS_V1: 'Trở lại tập luyện sau 100 ngày',
};
const signalStatus: Record<AttentionSignal['status'], string> = {
  OPEN: 'Cần xem xét', ACKNOWLEDGED: 'Đã ghi nhận', RESOLVED: 'Đã xử lý', DISMISSED: 'Đã bỏ qua',
};
function message(error: unknown) {
  if (error instanceof ApiError && [403, 404].includes(error.status)) return 'Không còn quyền xem dữ liệu này.';
  if (error instanceof ApiError && error.status === 409) return 'Dữ liệu đã thay đổi. Vui lòng xem lại trước khi quyết định.';
  if (error instanceof ApiError && error.status === 0) return 'Mất kết nối. Có thể thử lại cùng lệnh.';
  return 'Không thể tải hoặc lưu dữ liệu. Vui lòng thử lại.';
}
/** Shown instead of a guessed time when a timestamp is missing, date-only, offset-less or unparsable. */
const UNKNOWN_TIME_LABEL = 'Chưa rõ thời điểm';
function localDate(value: string, zone: string) {
  if (parseExactInstant(value) === null) return UNKNOWN_TIME_LABEL;
  return `${moment.tz(value, zone).format('DD/MM/YYYY HH:mm')} · ${formatTimezoneDisplay(zone, 'Chưa có múi giờ', value)}`;
}

export function AttentionReviewScreen({ relationshipId }: { relationshipId?: string }) {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const trainer = Boolean(relationshipId) && activeCapability === 'TRAINER';
  const relation = useRelationshipDetail(relationshipId ?? '', true);
  const studentId = trainer ? relation.data?.relationship?.studentId : undefined;
  const scope = useMemo(() => ['attention-review', actor, activeCapability, studentId ?? 'self'],
    [actor, activeCapability, studentId]);
  const trainerAuthorized = trainer && relation.isFetchedAfterMount
    && relation.data?.relationship?.status === 'ACTIVE'
    && relation.data.relationship.trainerId === actor
    && relation.data.currentPeriod?.mode === 'HUMAN_COACH';
  const ready = Boolean(actor) && (!relationshipId || (trainerAuthorized && Boolean(studentId)));
  const cache = useQueryClient();
  const router = useRouter();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [summary, setSummary] = useState('');
  const [evidenceText, setEvidenceText] = useState('');
  const [missingText, setMissingText] = useState('');
  const [reason, setReason] = useState('');
  const [selectedSignal, setSelectedSignal] = useState<string | null>(null);
  const [deniedReturnScope, setDeniedReturnScope] = useState<string | null>(null);
  const scopeId = scope.join(':');
  const [trackedScopeId, setTrackedScopeId] = useState(scopeId);
  const [selectedReviewZone, setSelectedReviewZone] = useState(() => resolveNewRecordDefaultTimezone());
  const [pickerExpanded, setPickerExpanded] = useState(false);
  const returnBlocked = trainer && deniedReturnScope === scopeId;
  const [pending, setPending] = useState<Pending | null>(null);
  if (trackedScopeId !== scopeId) {
    setTrackedScopeId(scopeId);
    setSelectedReviewZone(resolveNewRecordDefaultTimezone());
    setPickerExpanded(false);
    setPending(null);
  }
  const timezone = useQuery({ queryKey: [...scope, 'timezone'], enabled: ready && !trainer,
    gcTime: 0, queryFn: attentionReviewApi.timezone });
  const targetReviewZone = selectedReviewZone;
  const reviews = useInfiniteQuery({ queryKey: [...scope, 'reviews'], enabled: ready,
    gcTime: 0, initialPageParam: 0,
    queryFn: ({ pageParam }) => attentionReviewApi.reviews(pageParam, trainer ? studentId : undefined),
    getNextPageParam: last => last.hasMore ? last.page + 1 : undefined });
  const signals = useInfiniteQuery({ queryKey: [...scope, 'signals'], enabled: ready,
    gcTime: 0, initialPageParam: 0,
    queryFn: ({ pageParam }) => attentionReviewApi.signals(pageParam, trainer ? studentId : undefined),
    getNextPageParam: last => last.hasMore ? last.page + 1 : undefined });
  const returnStatus = useQuery({ queryKey: [...scope, 'return-status'], enabled: ready && !returnBlocked,
    gcTime: 0, queryFn: async () => {
      try { return await attentionReviewApi.returnStatus(trainer ? studentId : undefined); }
      catch (error) {
        if (trainer && error instanceof ApiError && [403, 404].includes(error.status))
          setDeniedReturnScope(scopeId);
        throw error;
      }
    } });
  const history = useQuery({ queryKey: [...scope, 'signal-history', selectedSignal],
    enabled: ready && Boolean(selectedSignal), gcTime: 0,
    queryFn: () => attentionReviewApi.signalHistory(selectedSignal!) });
  const mutation = useMutation({ mutationFn: async (command: Pending) => {
    switch (command.kind) {
      case 'timezone': return attentionReviewApi.confirmTimezone(command.timezone, command.key);
      case 'complete': return attentionReviewApi.complete(command.id, command.body);
      case 'ackReview': return attentionReviewApi.acknowledgeReview(command.id, command.version, command.key);
      case 'signal': return attentionReviewApi.act(command.id, command.body);
    }
  }, onSuccess: async () => {
    setPending(null); setSummary(''); setEvidenceText(''); setMissingText(''); setReason('');
    await cache.invalidateQueries({ queryKey: scope });
  }, onError: async error => {
    if (error instanceof ApiError && error.status === 409) {
      setPending(null);
      await cache.invalidateQueries({ queryKey: scope });
    } else if (error instanceof ApiError && [403, 404].includes(error.status)) {
      setPending(null);
      cache.removeQueries({ queryKey: scope });
    }
  } });
  const concealed = [relation, reviews, signals].some(query => query.isError
    && query.error instanceof ApiError && [403, 404].includes(query.error.status));
  useEffect(() => { if (concealed) cache.removeQueries({ queryKey: scope }); }, [concealed, cache, scope]);
  const returnDenied = returnBlocked || (trainer && returnStatus.isError && returnStatus.error instanceof ApiError
    && [403, 404].includes(returnStatus.error.status));
  useEffect(() => {
    if (!returnBlocked) return;
    cache.removeQueries({ queryKey: [...scope, 'return-status'], exact: true });
    cache.setQueryData<InfiniteData<AttentionPage<AttentionSignal>>>([...scope, 'signals'], old => old && ({
      ...old, pages: old.pages.map(page => ({ ...page,
        items: page.items.filter(signal => signal.rule === 'REVIEW_OVERDUE_V1') })),
    }));
    if (selectedSignal && signals.data?.pages.some(page => page.items.some(signal =>
      signal.id === selectedSignal && signal.rule !== 'REVIEW_OVERDUE_V1'))) {
      cache.removeQueries({ queryKey: [...scope, 'signal-history', selectedSignal], exact: true });
    }
  }, [returnBlocked, scope, cache, selectedSignal, signals.data]);
  const run = (command: Pending) => { if (!mutation.isPending) { setPending(command); mutation.mutate(command); } };
  const retry = () => { if (pending && !mutation.isPending) mutation.mutate(pending); };
  const reviewRows = reviews.data?.pages.flatMap(page => page.items) ?? [];
  const signalRows = (signals.data?.pages.flatMap(page => page.items) ?? [])
    .filter(signal => !returnDenied || signal.rule === 'REVIEW_OVERDUE_V1');
  const canView = activeCapability === (trainer ? 'TRAINER' : 'STUDENT') && ready && !concealed;
  const uuid = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
    <ScrollView testID="attention-review-screen" style={styles.screen} contentContainerStyle={[styles.content, { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' }]}>
      <View style={{ flexDirection: 'row', gap: spacing.md, alignItems: 'center' }}><AppIcon name="attention" color={theme.primary} /><Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary, flex: 1 }]}>Đánh giá và tín hiệu</Text></View>
      <AppButton icon="back" label="Quay lại" variant="ghost" onPress={() => {
        if (router.canGoBack()) router.back();
        else router.replace(relationshipId ? `/coaching/${relationshipId}` as never : '/coaching' as never);
      }} />
      {relationshipId && (relation.isPending || (!relation.isFetchedAfterMount && relation.isFetching))
        ? <AppCard state="loading" title="Đang kiểm tra quyền" message="Vui lòng chờ." /> : null}
      {!canView && (!relationshipId || !relation.isFetching)
        ? <AppCard state="info" icon="shield" title="Không thể xem dữ liệu" message="Quyền hoặc kỳ huấn luyện không còn hiệu lực." /> : null}
      {canView && !trainer ? <AppCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Múi giờ đánh giá</Text>
        {timezone.isPending ? <AppCard state="loading" title="Đang tải múi giờ" message="Vui lòng chờ." /> : null}
        {timezone.isError ? <AppCard state="error" title="Không thể tải múi giờ" message={message(timezone.error)} onRetry={() => timezone.refetch()} /> : null}
        <Text style={[styles.body, { color: theme.textSecondary }]}>{timezone.data
          ? `Đã xác nhận: ${formatTimezoneDisplay(timezone.data.timezone)}`
          : 'Chưa xác nhận. Múi giờ tài khoản hoặc thiết bị không được dùng ngầm cho chu kỳ đánh giá.'}</Text>
        <Text testID="review-timezone-target" style={[styles.body, { color: theme.textPrimary }]}>
          Khung giờ xác nhận: {formatTimezoneDisplay(targetReviewZone)}
        </Text>
        <TimezoneContextPicker
          testIDPrefix="review-timezone"
          label="Khung giờ muốn xác nhận"
          value={targetReviewZone}
          expanded={pickerExpanded}
          onToggleExpanded={() => setPickerExpanded(v => !v)}
          extraTimezones={[timezone.data?.timezone, user?.timezone]}
          onChange={zone => { setSelectedReviewZone(zone); setPickerExpanded(false); }}
        />
        <AppButton icon="check" label="Xác nhận múi giờ" disabled={mutation.isPending}
          onPress={() => run({ kind: 'timezone', timezone: targetReviewZone, key: createCommandKey() })} />
      </AppCard> : null}
      {canView ? <>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Chu kỳ đánh giá</Text>
        {reviews.isPending ? <AppCard state="loading" title="Đang tải đánh giá" message="Vui lòng chờ." /> : null}
        {reviews.isError ? <AppCard state="error" title="Không thể tải đánh giá" message={message(reviews.error)} onRetry={() => reviews.refetch()} /> : null}
        {!reviews.isPending && !reviews.isError && reviewRows.length === 0
          ? <AppCard state="empty" title="Chưa có nhiệm vụ đánh giá" message="Nhiệm vụ cần kỳ huấn luyện con người và múi giờ đã được học viên xác nhận." /> : null}
        {reviewRows.map(task => <AppCard key={task.id} testID={`review-${task.id}`}>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>{labelStatus[task.status]}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Chu kỳ {task.cycleStartDate} · hạn {task.dueDate} · {formatTimezoneDisplay(task.timezone)}</Text>
          {task.summary ? <Text style={[styles.body, { color: theme.textPrimary }]}>Tóm tắt: {task.summary}</Text> : null}
          {task.evidenceRefs.length ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bằng chứng buổi tập: {task.evidenceRefs.length} mục</Text> : null}
          {task.missingData.length ? <Text style={[styles.body, { color: theme.textSecondary }]}>Dữ liệu thiếu/không xác định: {task.missingData.join('; ')}</Text> : null}
          {!trainer && task.status === 'COMPLETED' && !task.studentAcknowledgedAt
            ? <AppButton label="Xác nhận đã xem" disabled={mutation.isPending}
                onPress={() => run({ kind: 'ackReview', id: task.id, version: task.version, key: createCommandKey() })} /> : null}
          {trainer && task.status === 'OPEN' ? <View>
            <Text style={[styles.label, { color: theme.textPrimary }]}>Tóm tắt đánh giá</Text>
            <TextInput accessibilityLabel="Tóm tắt đánh giá" value={summary} onChangeText={setSummary}
              multiline maxLength={4000} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
            <Text style={[styles.label, { color: theme.textPrimary }]}>ID buổi tập làm bằng chứng (phân tách dấu phẩy, tùy chọn)</Text>
            <TextInput accessibilityLabel="ID buổi tập làm bằng chứng" value={evidenceText} onChangeText={setEvidenceText}
              style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
            <Text style={[styles.label, { color: theme.textPrimary }]}>Dữ liệu thiếu/không xác định (phân tách dấu phẩy)</Text>
            <TextInput accessibilityLabel="Dữ liệu thiếu hoặc không xác định" value={missingText} onChangeText={setMissingText}
              style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
            <AppButton label="Hoàn tất đánh giá" disabled={mutation.isPending || !summary.trim()
              || evidenceText.split(',').map(x => x.trim()).filter(Boolean).some(id => !uuid.test(id))}
              onPress={() => run({ kind: 'complete', id: task.id, body: {
                expectedVersion: task.version, commandKey: createCommandKey(), summary: summary.trim(),
                evidenceRefs: evidenceText.split(',').map(x => x.trim()).filter(Boolean),
                missingData: missingText.split(',').map(x => x.trim()).filter(Boolean),
              } })} />
          </View> : null}
        </AppCard>)}
        {reviews.hasNextPage ? <AppButton label="Tải thêm đánh giá" variant="secondary" disabled={reviews.isFetchingNextPage}
          loading={reviews.isFetchingNextPage} onPress={() => reviews.fetchNextPage()} /> : null}
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Tín hiệu từ quy tắc</Text>
        {!returnDenied && returnStatus.isPending ? <AppCard state="loading" title="Đang kiểm tra bằng chứng 100 ngày" message="Vui lòng chờ." /> : null}
        {returnDenied ? <AppCard state="error" title="Chưa có quyền xem lịch sử tập luyện"
          message="Chu kỳ đánh giá và tín hiệu quá hạn vẫn có thể xem."
          onRetry={() => {
            setDeniedReturnScope(null);
            void cache.invalidateQueries({ queryKey: [...scope, 'signals'], exact: true });
          }} /> : null}
        {!returnDenied && returnStatus.isError
          ? <AppCard state="error" title="Không thể kiểm tra bằng chứng 100 ngày" message={message(returnStatus.error)}
              onRetry={() => returnStatus.refetch()} /> : null}
        {!returnDenied && returnStatus.data ? <AppCard testID="return-100-day-status">
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Trở lại sau 100 ngày</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>{returnStatus.data.status === 'UNKNOWN'
            ? returnStatus.data.reason === 'TIMEZONE_UNCONFIRMED'
              ? 'Chưa xác định: Học viên chưa xác nhận múi giờ đánh giá.'
              : returnStatus.data.reason === 'COMPLETION_TIME_MISSING'
                ? 'Chưa xác định: một buổi tập hoàn tất thiếu thời gian kết thúc.'
                : 'Chưa xác định: chưa đủ hai buổi tập thực sự hoàn tất.'
            : returnStatus.data.status === 'DETECTED'
              ? 'Có hai buổi tập hoàn tất cách nhau ít nhất 100 ngày lịch. Xem tín hiệu và bằng chứng bên dưới.'
              : 'Đã đủ dữ liệu, chưa có hai buổi tập hoàn tất cách nhau 100 ngày lịch.'}</Text>
          {returnStatus.data.timezone ? <Text style={[styles.body, { color: theme.textSecondary }]}>
            Múi giờ đã xác nhận: {formatTimezoneDisplay(returnStatus.data.timezone)}
          </Text> : null}
        </AppCard> : null}
        {signals.isPending ? <AppCard state="loading" title="Đang tải tín hiệu" message="Vui lòng chờ." /> : null}
        {signals.isError ? <AppCard state="error" title="Không thể tải tín hiệu" message={message(signals.error)} onRetry={() => signals.refetch()} /> : null}
        {!signals.isPending && !signals.isError && signalRows.length === 0
          ? <AppCard state="empty" title="Chưa có tín hiệu" message="Nếu thiếu buổi tập hoàn tất trước đó hoặc múi giờ đã xác nhận, kết quả 100 ngày là chưa xác định, không phải cảnh báo." /> : null}
        {signalRows.map(signal => <AppCard key={signal.id} testID={`attention-${signal.id}`}>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>{signalTitle[signal.rule]}</Text>
          <Text style={[styles.label, { color: theme.textPrimary }]}>Quy tắc hệ thống · {signal.severity === 'MEDIUM' ? 'Ưu tiên vừa' : 'Ưu tiên thấp'} · {signalStatus[signal.status]}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Phát hiện {formatInstantVietnam(signal.detectedAt) ?? UNKNOWN_TIME_LABEL}</Text>
          {signal.plannedStartAt && signal.plannedEndAt
            ? <Text style={[styles.body, { color: theme.textSecondary }]}>Khoảng dự kiến: {localDate(signal.plannedStartAt, 'Etc/UTC')} – {localDate(signal.plannedEndAt, 'Etc/UTC')}</Text> : null}
          {signal.reviewTaskId ? <Text style={[styles.body, { color: theme.textSecondary }]}>Nguồn: nhiệm vụ đánh giá</Text> : null}
          {signal.returnEvidence ? <Text style={[styles.body, { color: theme.textSecondary }]}>
            Hai buổi tập đã hoàn tất: {signal.returnEvidence.previousLocalDate} → {signal.returnEvidence.currentLocalDate}
            {' · '}{signal.returnEvidence.calendarDays} ngày lịch · {formatTimezoneDisplay(signal.returnEvidence.timezone)}.
            Đây là tín hiệu để xem lại, không tự đổi kế hoạch tập.
          </Text> : null}
          <AppButton label="Xem lịch sử xử lý" variant="secondary" onPress={() => setSelectedSignal(signal.id)} />
          {selectedSignal === signal.id ? <View>
            {history.isPending ? <AppCard state="loading" title="Đang tải lịch sử" message="Vui lòng chờ." /> : null}
            {history.isError ? <AppCard state="error" title="Không thể tải lịch sử" message={message(history.error)} onRetry={() => history.refetch()} /> : null}
            {history.data?.map((event, index) => <Text key={`${event.occurredAt}-${index}`} style={[styles.body, { color: theme.textSecondary }]}>
              {event.eventType} · {formatInstantVietnam(event.occurredAt) ?? UNKNOWN_TIME_LABEL} · {event.resultingSeverity}
              {event.reason ? ` · ${event.reason}` : ''}
            </Text>)}
          </View> : null}
          {trainer && !['RESOLVED', 'DISMISSED'].includes(signal.status) ? <View>
            <Text style={[styles.label, { color: theme.textPrimary }]}>Lý do xử lý/bỏ qua</Text>
            <TextInput accessibilityLabel="Lý do xử lý tín hiệu" value={reason} onChangeText={setReason}
              maxLength={2000} multiline style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
            <View style={styles.row}>
              {signal.status === 'OPEN' ? <AppButton label="Ghi nhận" variant="secondary" disabled={mutation.isPending}
                onPress={() => run({ kind: 'signal', id: signal.id,
                  body: { action: 'ACK', expectedVersion: signal.version, commandKey: createCommandKey() } })} /> : null}
              <AppButton label="Đã xử lý" disabled={mutation.isPending || !reason.trim()}
                onPress={() => run({ kind: 'signal', id: signal.id,
                  body: { action: 'RESOLVE', expectedVersion: signal.version, commandKey: createCommandKey(), reason: reason.trim() } })} />
              <AppButton label="Bỏ qua" variant="danger" disabled={mutation.isPending || !reason.trim()}
                onPress={() => run({ kind: 'signal', id: signal.id,
                  body: { action: 'DISMISS', expectedVersion: signal.version, commandKey: createCommandKey(), reason: reason.trim() } })} />
            </View>
          </View> : null}
        </AppCard>)}
        {signals.hasNextPage ? <AppButton label="Tải thêm tín hiệu" variant="secondary" disabled={signals.isFetchingNextPage}
          loading={signals.isFetchingNextPage} onPress={() => signals.fetchNextPage()} /> : null}
      </> : null}
      {mutation.isError ? <AppCard state="error" title="Chưa thể lưu thao tác" message={message(mutation.error)} /> : null}
      {pending && mutation.isError && mutation.error instanceof ApiError && mutation.error.status === 0
        ? <AppButton label="Thử lại cùng lệnh" disabled={mutation.isPending} onPress={retry} /> : null}
    </ScrollView>
  </SafeAreaView>;
}
