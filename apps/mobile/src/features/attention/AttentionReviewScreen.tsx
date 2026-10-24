import React, { useEffect, useMemo, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import moment from 'moment-timezone';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient, type InfiniteData } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail } from '@/features/coaching/coachingQueries';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { attentionReviewApi, AttentionPage, AttentionSignal, CompleteReviewBody, ReviewTask, SignalActionBody } from '@/services/attentionReviewApi';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';

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
function localDate(value: string, zone: string) {
  return `${moment.tz(value, zone).format('DD/MM/YYYY HH:mm')} · ${zone}`;
}

export function AttentionReviewScreen({ relationshipId }: { relationshipId?: string }) {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const trainer = Boolean(relationshipId) && activeCapability === 'TRAINER';
  const relation = useRelationshipDetail(relationshipId ?? '');
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
  const [zoneSearch, setZoneSearch] = useState('');
  const [selectedZone, setSelectedZone] = useState<string | null>(null);
  const [summary, setSummary] = useState('');
  const [evidenceText, setEvidenceText] = useState('');
  const [missingText, setMissingText] = useState('');
  const [reason, setReason] = useState('');
  const [selectedSignal, setSelectedSignal] = useState<string | null>(null);
  const [deniedReturnScope, setDeniedReturnScope] = useState<string | null>(null);
  const scopeId = scope.join(':');
  const returnBlocked = trainer && deniedReturnScope === scopeId;
  const [pending, setPending] = useState<Pending | null>(null);
  const zones = useMemo(() => zoneSearch.trim().length < 2 ? [] : moment.tz.names()
    .filter(name => name.toLocaleLowerCase().includes(zoneSearch.trim().toLocaleLowerCase())).slice(0, 15), [zoneSearch]);
  const timezone = useQuery({ queryKey: [...scope, 'timezone'], enabled: ready && !trainer,
    gcTime: 0, queryFn: attentionReviewApi.timezone });
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
    <ScrollView testID="attention-review-screen" style={styles.screen} contentContainerStyle={styles.content}>
      <Text style={[styles.title, { color: theme.textPrimary }]}>Đánh giá và tín hiệu</Text>
      <CoachingButton label="Quay lại" secondary onPress={() => {
        if (router.canGoBack()) router.back();
        else router.replace(relationshipId ? `/coaching/${relationshipId}` as never : '/coaching' as never);
      }} />
      {relationshipId && relation.isPending ? <CoachingState busy title="Đang kiểm tra quyền" message="Vui lòng chờ." /> : null}
      {!canView ? <CoachingState title="Không thể xem dữ liệu" message="Quyền hoặc kỳ huấn luyện không còn hiệu lực." /> : null}
      {canView && !trainer ? <CoachingCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Múi giờ đánh giá</Text>
        {timezone.isPending ? <CoachingState busy title="Đang tải múi giờ" message="Vui lòng chờ." /> : null}
        {timezone.isError ? <CoachingState title="Không thể tải múi giờ" message={message(timezone.error)} onRetry={() => timezone.refetch()} /> : null}
        <Text style={[styles.body, { color: theme.textSecondary }]}>{timezone.data
          ? `Đã xác nhận: ${timezone.data.timezone}`
          : 'Chưa xác nhận. Múi giờ tài khoản hoặc thiết bị không được dùng ngầm cho chu kỳ đánh giá.'}</Text>
        <Text style={[styles.label, { color: theme.textPrimary }]}>Tìm IANA timezone để xác nhận</Text>
        <TextInput testID="review-timezone-search" accessibilityLabel="Tìm IANA timezone" value={zoneSearch}
          onChangeText={text => { setZoneSearch(text); setSelectedZone(null); }}
          style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
        {zones.map(zone => <Pressable key={zone} accessibilityRole="button" accessibilityLabel={`Chọn ${zone}`}
          onPress={() => setSelectedZone(zone)} style={{ minHeight: 44, justifyContent: 'center' }}>
          <Text style={[styles.body, { color: selectedZone === zone ? theme.primary : theme.textPrimary }]}>{zone}</Text>
        </Pressable>)}
        {selectedZone ? <Text style={[styles.body, { color: theme.textPrimary }]}>Sẽ xác nhận: {selectedZone}</Text> : null}
        <CoachingButton label="Xác nhận múi giờ" disabled={!selectedZone || mutation.isPending}
          onPress={() => selectedZone && run({ kind: 'timezone', timezone: selectedZone, key: createCommandKey() })} />
      </CoachingCard> : null}
      {canView ? <>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Chu kỳ đánh giá</Text>
        {reviews.isPending ? <CoachingState busy title="Đang tải đánh giá" message="Vui lòng chờ." /> : null}
        {reviews.isError ? <CoachingState title="Không thể tải đánh giá" message={message(reviews.error)} onRetry={() => reviews.refetch()} /> : null}
        {!reviews.isPending && !reviews.isError && reviewRows.length === 0
          ? <CoachingState title="Chưa có nhiệm vụ đánh giá" message="Nhiệm vụ cần kỳ huấn luyện con người và múi giờ đã được học viên xác nhận." /> : null}
        {reviewRows.map(task => <CoachingCard key={task.id} testID={`review-${task.id}`}>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>{labelStatus[task.status]}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Chu kỳ {task.cycleStartDate} · hạn {task.dueDate} · {task.timezone}</Text>
          {task.summary ? <Text style={[styles.body, { color: theme.textPrimary }]}>Tóm tắt: {task.summary}</Text> : null}
          {task.evidenceRefs.length ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bằng chứng buổi tập: {task.evidenceRefs.length} mục</Text> : null}
          {task.missingData.length ? <Text style={[styles.body, { color: theme.textSecondary }]}>Dữ liệu thiếu/không xác định: {task.missingData.join('; ')}</Text> : null}
          {!trainer && task.status === 'COMPLETED' && !task.studentAcknowledgedAt
            ? <CoachingButton label="Xác nhận đã xem" disabled={mutation.isPending}
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
            <CoachingButton label="Hoàn tất đánh giá" disabled={mutation.isPending || !summary.trim()
              || evidenceText.split(',').map(x => x.trim()).filter(Boolean).some(id => !uuid.test(id))}
              onPress={() => run({ kind: 'complete', id: task.id, body: {
                expectedVersion: task.version, commandKey: createCommandKey(), summary: summary.trim(),
                evidenceRefs: evidenceText.split(',').map(x => x.trim()).filter(Boolean),
                missingData: missingText.split(',').map(x => x.trim()).filter(Boolean),
              } })} />
          </View> : null}
        </CoachingCard>)}
        {reviews.hasNextPage ? <CoachingButton label="Tải thêm đánh giá" secondary disabled={reviews.isFetchingNextPage}
          busy={reviews.isFetchingNextPage} onPress={() => reviews.fetchNextPage()} /> : null}
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Tín hiệu từ quy tắc</Text>
        {!returnDenied && returnStatus.isPending ? <CoachingState busy title="Đang kiểm tra bằng chứng 100 ngày" message="Vui lòng chờ." /> : null}
        {returnDenied ? <CoachingState title="Chưa có quyền xem lịch sử tập luyện"
          message="Chu kỳ đánh giá và tín hiệu quá hạn vẫn có thể xem."
          onRetry={() => {
            setDeniedReturnScope(null);
            void cache.invalidateQueries({ queryKey: [...scope, 'signals'], exact: true });
          }} /> : null}
        {!returnDenied && returnStatus.isError
          ? <CoachingState title="Không thể kiểm tra bằng chứng 100 ngày" message={message(returnStatus.error)}
              onRetry={() => returnStatus.refetch()} /> : null}
        {!returnDenied && returnStatus.data ? <CoachingCard testID="return-100-day-status">
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Trở lại sau 100 ngày</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>{returnStatus.data.status === 'UNKNOWN'
            ? returnStatus.data.reason === 'TIMEZONE_UNCONFIRMED'
              ? 'Chưa xác định: Học viên chưa xác nhận IANA timezone.'
              : returnStatus.data.reason === 'COMPLETION_TIME_MISSING'
                ? 'Chưa xác định: một buổi tập hoàn tất thiếu thời gian kết thúc.'
                : 'Chưa xác định: chưa đủ hai buổi tập thực sự hoàn tất.'
            : returnStatus.data.status === 'DETECTED'
              ? 'Có hai buổi tập hoàn tất cách nhau ít nhất 100 ngày lịch. Xem tín hiệu và bằng chứng bên dưới.'
              : 'Đã đủ dữ liệu, chưa có hai buổi tập hoàn tất cách nhau 100 ngày lịch.'}</Text>
          {returnStatus.data.timezone ? <Text style={[styles.body, { color: theme.textSecondary }]}>
            Múi giờ đã xác nhận: {returnStatus.data.timezone}
          </Text> : null}
        </CoachingCard> : null}
        {signals.isPending ? <CoachingState busy title="Đang tải tín hiệu" message="Vui lòng chờ." /> : null}
        {signals.isError ? <CoachingState title="Không thể tải tín hiệu" message={message(signals.error)} onRetry={() => signals.refetch()} /> : null}
        {!signals.isPending && !signals.isError && signalRows.length === 0
          ? <CoachingState title="Chưa có tín hiệu" message="Nếu thiếu buổi tập hoàn tất trước đó hoặc múi giờ đã xác nhận, kết quả 100 ngày là chưa xác định, không phải cảnh báo." /> : null}
        {signalRows.map(signal => <CoachingCard key={signal.id} testID={`attention-${signal.id}`}>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>{signalTitle[signal.rule]}</Text>
          <Text style={[styles.label, { color: theme.textPrimary }]}>Quy tắc hệ thống · {signal.severity === 'MEDIUM' ? 'Ưu tiên vừa' : 'Ưu tiên thấp'} · {signalStatus[signal.status]}</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>Phát hiện {new Date(signal.detectedAt).toLocaleString('vi-VN')}</Text>
          {signal.plannedStartAt && signal.plannedEndAt
            ? <Text style={[styles.body, { color: theme.textSecondary }]}>Khoảng dự kiến: {localDate(signal.plannedStartAt, 'Etc/UTC')} – {localDate(signal.plannedEndAt, 'Etc/UTC')}</Text> : null}
          {signal.reviewTaskId ? <Text style={[styles.body, { color: theme.textSecondary }]}>Nguồn: nhiệm vụ đánh giá</Text> : null}
          {signal.returnEvidence ? <Text style={[styles.body, { color: theme.textSecondary }]}>
            Hai buổi tập đã hoàn tất: {signal.returnEvidence.previousLocalDate} → {signal.returnEvidence.currentLocalDate}
            {' · '}{signal.returnEvidence.calendarDays} ngày lịch · {signal.returnEvidence.timezone}.
            Đây là tín hiệu để xem lại, không tự đổi kế hoạch tập.
          </Text> : null}
          <CoachingButton label="Xem lịch sử xử lý" secondary onPress={() => setSelectedSignal(signal.id)} />
          {selectedSignal === signal.id ? <View>
            {history.isPending ? <CoachingState busy title="Đang tải lịch sử" message="Vui lòng chờ." /> : null}
            {history.isError ? <CoachingState title="Không thể tải lịch sử" message={message(history.error)} onRetry={() => history.refetch()} /> : null}
            {history.data?.map((event, index) => <Text key={`${event.occurredAt}-${index}`} style={[styles.body, { color: theme.textSecondary }]}>
              {event.eventType} · {new Date(event.occurredAt).toLocaleString('vi-VN')} · {event.resultingSeverity}
              {event.reason ? ` · ${event.reason}` : ''}
            </Text>)}
          </View> : null}
          {trainer && !['RESOLVED', 'DISMISSED'].includes(signal.status) ? <View>
            <Text style={[styles.label, { color: theme.textPrimary }]}>Lý do xử lý/bỏ qua</Text>
            <TextInput accessibilityLabel="Lý do xử lý tín hiệu" value={reason} onChangeText={setReason}
              maxLength={2000} multiline style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
            <View style={styles.row}>
              {signal.status === 'OPEN' ? <CoachingButton label="Ghi nhận" secondary disabled={mutation.isPending}
                onPress={() => run({ kind: 'signal', id: signal.id,
                  body: { action: 'ACK', expectedVersion: signal.version, commandKey: createCommandKey() } })} /> : null}
              <CoachingButton label="Đã xử lý" disabled={mutation.isPending || !reason.trim()}
                onPress={() => run({ kind: 'signal', id: signal.id,
                  body: { action: 'RESOLVE', expectedVersion: signal.version, commandKey: createCommandKey(), reason: reason.trim() } })} />
              <CoachingButton label="Bỏ qua" danger disabled={mutation.isPending || !reason.trim()}
                onPress={() => run({ kind: 'signal', id: signal.id,
                  body: { action: 'DISMISS', expectedVersion: signal.version, commandKey: createCommandKey(), reason: reason.trim() } })} />
            </View>
          </View> : null}
        </CoachingCard>)}
        {signals.hasNextPage ? <CoachingButton label="Tải thêm tín hiệu" secondary disabled={signals.isFetchingNextPage}
          busy={signals.isFetchingNextPage} onPress={() => signals.fetchNextPage()} /> : null}
      </> : null}
      {mutation.isError ? <CoachingState title="Chưa thể lưu thao tác" message={message(mutation.error)} /> : null}
      {pending && mutation.isError && mutation.error instanceof ApiError && mutation.error.status === 0
        ? <CoachingButton label="Thử lại cùng lệnh" disabled={mutation.isPending} onPress={retry} /> : null}
    </ScrollView>
  </SafeAreaView>;
}
