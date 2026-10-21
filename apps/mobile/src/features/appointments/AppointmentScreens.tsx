import React, { useEffect, useMemo, useRef, useState } from 'react';
import { ScrollView, Text, TextInput, useColorScheme, View } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useRouter } from 'expo-router';
import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { CoachingButton, CoachingCard, CoachingState, coachingUiStyles as styles } from '@/features/coaching/components/CoachingUi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { createCommandKey } from '@/services/coachingApi';
import { appointmentApi, AppointmentInterval } from '@/services/appointmentApi';
import { validProposalOffsets, proposalTimezones } from '@/features/workout-schedule-proposals/proposalTime';
import { ApiError } from '@/types/auth';

const root = (actor: string, role: string) => ['appointments', actor, role];
const isConcealed = (error: unknown) => error instanceof ApiError && [403, 404].includes(error.status);
const isConflict = (error: unknown) => error instanceof ApiError && error.status === 409;
const message = (error: unknown) => isConcealed(error) ? 'Bạn không còn quyền xem dữ liệu này.'
  : isConflict(error) ? 'Dữ liệu đã thay đổi. Hãy xem lại trước khi quyết định.'
    : 'Không thể kết nối. Hãy thử lại; lệnh cũ vẫn được giữ nguyên nếu chưa biết kết quả.';
const label = (status: string) => ({ PENDING: 'Chờ quyết định', ACCEPTED: 'Đã chấp nhận', REJECTED: 'Đã từ chối',
  CONFIRMED: 'Đã xác nhận', CANCELLED: 'Đã hủy', COMPLETED: 'Đã hoàn tất', ABSENT: 'Vắng mặt', RESCHEDULED: 'Đã đổi lịch' })[status] ?? status;
const localText = (interval: { localStart: string | null; localEnd: string | null; timezone: string;
  startUtcOffset: string | null; endUtcOffset: string | null } | null) => interval
  && interval.localStart && interval.localEnd && interval.startUtcOffset && interval.endUtcOffset
  ? `${interval.localStart} ${interval.startUtcOffset} → ${interval.localEnd} ${interval.endUtcOffset} · ${interval.timezone}`
  : 'Lịch cũ thiếu dữ liệu thời gian; không thể xác nhận yêu cầu mới.';

function useGuidanceNow() {
  const [now, setNow] = useState(0);
  useEffect(() => {
    const update = () => setNow(Date.now());
    const initial = setTimeout(update, 0);
    const interval = setInterval(update, 60_000);
    return () => { clearTimeout(initial); clearInterval(interval); };
  }, []);
  return now;
}

export function AppointmentHomeScreen({ studentId }: { studentId?: string }) {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const role = activeCapability ?? '';
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [from, setFrom] = useState('');
  const [until, setUntil] = useState('');
  useEffect(() => {
    const initial = setTimeout(() => {
      const now = Date.now();
      setFrom(new Date(now - 30 * 86_400_000).toISOString());
      setUntil(new Date(now + 60 * 86_400_000).toISOString());
    }, 0);
    return () => clearTimeout(initial);
  }, []);
  const requests = useInfiniteQuery({ queryKey: [...root(actor, role), 'requests'], enabled: !!actor,
    gcTime: 0, initialPageParam: 0, queryFn: ({ pageParam }) => appointmentApi.requests(pageParam),
    getNextPageParam: page => (page.page + 1) * page.size < page.totalItems ? page.page + 1 : undefined });
  const appointments = useInfiniteQuery({ queryKey: [...root(actor, role), 'calendar', from, until], enabled: !!actor && !!from && !!until,
    gcTime: 0, initialPageParam: 0, queryFn: ({ pageParam }) => appointmentApi.appointments(from, until, pageParam),
    getNextPageParam: page => (page.page + 1) * page.size < page.totalItems ? page.page + 1 : undefined });
  const denied = isConcealed(requests.error) || isConcealed(appointments.error);
  useEffect(() => { if (denied) cache.removeQueries({ queryKey: root(actor, role) }); }, [actor, cache, denied, role]);
  const requestRows = requests.data?.pages.flatMap(page => page.items) ?? [];
  const appointmentRows = appointments.data?.pages.flatMap(page => page.items) ?? [];
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="appointment-home" style={styles.screen} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Lịch hẹn huấn luyện</Text>
    <CoachingButton label="Quay lại" secondary onPress={() => router.back()} />
    {studentId ? <CoachingButton label="Đề xuất lịch hẹn" onPress={() => router.push(`/appointments/new?studentId=${studentId}` as never)} /> : null}
    <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ bên còn lại có thể chấp nhận hoặc từ chối yêu cầu. Lịch hẹn không tự tạo hay đổi lịch tập.</Text>
    <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Yêu cầu</Text>
    {requests.isPending ? <CoachingState busy title="Đang tải yêu cầu" message="Vui lòng chờ." /> : null}
    {requests.isError ? <CoachingState title="Không thể tải yêu cầu" message={message(requests.error)} onRetry={() => requests.refetch()} /> : null}
    {!requests.isPending && !requests.isError && !requestRows.length ? <CoachingState title="Chưa có yêu cầu" message="Lịch hẹn được tạo khi bên còn lại chấp nhận." /> : null}
    {!denied && requestRows.map(item => <CoachingCard key={item.requestId}>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{item.kind === 'CREATE' ? 'Hẹn mới' : 'Đề xuất đổi lịch'} · {label(item.status)}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{localText(item.interval)}</Text>
      <CoachingButton label="Xem yêu cầu" secondary onPress={() => router.push(`/appointments/requests/${item.requestId}` as never)} />
    </CoachingCard>)}
    {requests.hasNextPage && !denied ? <CoachingButton label="Tải thêm yêu cầu" secondary busy={requests.isFetchingNextPage} onPress={() => requests.fetchNextPage()} /> : null}
    <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Lịch đã xác nhận và lịch sử</Text>
    <TextInput accessibilityLabel="Từ thời điểm ISO có múi giờ" value={from} onChangeText={setFrom} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
    <TextInput accessibilityLabel="Đến thời điểm ISO có múi giờ" value={until} onChangeText={setUntil} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
    {appointments.isPending ? <CoachingState busy title="Đang tải lịch hẹn" message="Vui lòng chờ." /> : null}
    {appointments.isError ? <CoachingState title="Không thể tải lịch hẹn" message={message(appointments.error)} onRetry={() => appointments.refetch()} /> : null}
    {!appointments.isPending && !appointments.isError && !appointmentRows.length ? <CoachingState title="Không có lịch trong khoảng đã chọn" message="Bạn có thể đổi khoảng thời gian để xem lại." /> : null}
    {!denied && appointmentRows.map(item => <CoachingCard key={item.appointmentId}>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{label(item.status)}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{localText(item)}</Text>
      <CoachingButton label="Xem lịch hẹn" secondary onPress={() => router.push(`/appointments/${item.appointmentId}` as never)} />
    </CoachingCard>)}
    {appointments.hasNextPage && !denied ? <CoachingButton label="Tải thêm lịch hẹn" secondary busy={appointments.isFetchingNextPage} onPress={() => appointments.fetchNextPage()} /> : null}
  </ScrollView></SafeAreaView>;
}

export function AppointmentCreateScreen({ studentId, appointmentId }: { studentId: string; appointmentId?: string }) {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const role = activeCapability ?? '';
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const guidanceNow = useGuidanceNow();
  const source = useQuery({ queryKey: [...root(actor, role), 'detail', appointmentId], enabled: !!actor && !!appointmentId,
    gcTime: 0, queryFn: () => appointmentApi.detail(appointmentId!) });
  const [search, setSearch] = useState('');
  const [timezone, setTimezone] = useState('');
  const [localStart, setLocalStart] = useState('');
  const [localEnd, setLocalEnd] = useState('');
  const [startUtcOffset, setStartUtcOffset] = useState('');
  const [endUtcOffset, setEndUtcOffset] = useState('');
  const [preview, setPreview] = useState(false);
  const [pending, setPending] = useState<(AppointmentInterval & { commandKey: string; expectedAppointmentVersion?: number }) | null>(null);
  const [stale, setStale] = useState(false);
  const starts = useMemo(() => validProposalOffsets(timezone, localStart), [timezone, localStart]);
  const ends = useMemo(() => validProposalOffsets(timezone, localEnd), [timezone, localEnd]);
  const zones = useMemo(() => search.trim().length < 2 ? [] : proposalTimezones(search), [search]);
  const start = starts.find(item => item.offset === startUtcOffset);
  const end = ends.find(item => item.offset === endUtcOffset);
  const valid = !!start && !!end && end.instant > start.instant && guidanceNow > 0 && start.instant > guidanceNow;
  const reset = () => { setPreview(false); setPending(null); setStale(false); };
  const create = useMutation({ mutationFn: (body: AppointmentInterval & { commandKey: string; expectedAppointmentVersion?: number }) =>
    appointmentId ? appointmentApi.reschedule(appointmentId, { ...body, expectedAppointmentVersion: body.expectedAppointmentVersion! })
      : appointmentApi.create(studentId, body),
    onSuccess: async result => { setPending(null); await cache.invalidateQueries({ queryKey: root(actor, role) }); router.replace(`/appointments/requests/${result.request.requestId}` as never); },
    onError: async error => {
      if (isConflict(error)) { setPending(null); setPreview(false); setStale(true); await cache.invalidateQueries({ queryKey: root(actor, role) }); }
      else if (isConcealed(error)) { setPending(null); setPreview(false); cache.removeQueries({ queryKey: root(actor, role) }); }
    },
  });
  const submit = () => { if (!preview || !valid || create.isPending || stale) return;
    if (appointmentId && !source.data) return;
    const body = pending ?? { localStart, localEnd, timezone, startUtcOffset, endUtcOffset,
      expectedAppointmentVersion: source.data?.version, commandKey: createCommandKey() };
    setPending(body); create.mutate(body);
  };
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="appointment-create" style={styles.screen} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>{appointmentId ? 'Đề xuất đổi lịch hẹn' : 'Đề xuất lịch hẹn'}</Text>
    <CoachingButton label="Quay lại" secondary onPress={() => router.back()} />
    {appointmentId && source.isPending ? <CoachingState busy title="Đang tải lịch hẹn hiện tại" message="Vui lòng chờ." /> : null}
    {appointmentId && source.isError ? <CoachingState title="Không thể tải lịch hiện tại" message={message(source.error)} onRetry={() => source.refetch()} /> : null}
    {source.data ? <CoachingCard><Text style={[styles.body, { color: theme.textPrimary }]}>Lịch hiện tại: {localText(source.data)}</Text></CoachingCard> : null}
    <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn rõ múi giờ IANA và UTC offset. Không lấy múi giờ thiết bị; người còn lại sẽ xác nhận toàn bộ yêu cầu.</Text>
    <TextInput accessibilityLabel="Tìm múi giờ IANA" value={search} onChangeText={setSearch} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
    {zones.map(zone => <CoachingButton key={zone} label={zone} secondary={zone !== timezone} onPress={() => { setTimezone(zone); setStartUtcOffset(''); setEndUtcOffset(''); reset(); }} />)}
    <Text style={[styles.label, { color: theme.textPrimary }]}>Múi giờ: {timezone || 'Chưa chọn'}</Text>
    <TextInput accessibilityLabel="Bắt đầu YYYY-MM-DDTHH:mm" placeholder="2026-10-25T09:00" value={localStart} onChangeText={value => { setLocalStart(value); setStartUtcOffset(''); reset(); }} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
    {starts.map(choice => <CoachingButton key={choice.offset} label={`Bắt đầu UTC ${choice.offset}`} secondary={choice.offset !== startUtcOffset} onPress={() => { setStartUtcOffset(choice.offset); reset(); }} />)}
    <TextInput accessibilityLabel="Kết thúc YYYY-MM-DDTHH:mm" placeholder="2026-10-25T10:00" value={localEnd} onChangeText={value => { setLocalEnd(value); setEndUtcOffset(''); reset(); }} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
    {ends.map(choice => <CoachingButton key={choice.offset} label={`Kết thúc UTC ${choice.offset}`} secondary={choice.offset !== endUtcOffset} onPress={() => { setEndUtcOffset(choice.offset); reset(); }} />)}
    {!valid ? <CoachingState title="Chưa thể gửi" message="Chọn giờ hợp lệ trong tương lai và UTC offset cho từng mốc. Giờ không tồn tại do DST không được chọn." /> : null}
    {!preview ? <CoachingButton label="Xem trước yêu cầu" disabled={!valid} onPress={() => setPreview(true)} /> : <CoachingCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Xác nhận trước khi gửi</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{localText({ localStart, localEnd, timezone, startUtcOffset, endUtcOffset })}</Text>
      <CoachingButton label={pending && create.isError ? 'Thử lại cùng lệnh' : 'Gửi yêu cầu'} busy={create.isPending} disabled={create.isPending || stale || (!!appointmentId && !source.data)} onPress={submit} />
      <CoachingButton label="Sửa yêu cầu" secondary onPress={reset} />
    </CoachingCard>}
    {create.isError ? <CoachingState title="Chưa gửi được" message={message(create.error)} /> : null}
  </ScrollView></SafeAreaView>;
}

export function AppointmentRequestScreen({ requestId }: { requestId: string }) {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const role = activeCapability ?? '';
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [confirm, setConfirm] = useState<'ACCEPT' | 'REJECT' | null>(null);
  const pending = useRef<{ decision: 'ACCEPT' | 'REJECT'; expectedRequestVersion: number; expectedAppointmentVersion?: number; commandKey: string } | null>(null);
  const query = useQuery({ queryKey: [...root(actor, role), 'request', requestId], enabled: !!actor,
    gcTime: 0, queryFn: () => appointmentApi.requestDetail(requestId) });
  const appointment = useQuery({ queryKey: [...root(actor, role), 'detail', query.data?.appointmentId],
    gcTime: 0, enabled: !!actor && query.data?.kind === 'RESCHEDULE' && !!query.data.appointmentId,
    queryFn: () => appointmentApi.detail(query.data!.appointmentId!) });
  const denied = isConcealed(query.error) || isConcealed(appointment.error);
  useEffect(() => { if (denied) cache.removeQueries({ queryKey: root(actor, role) }); }, [actor, cache, denied, role]);
  const decide = useMutation({ mutationFn: async (body: NonNullable<typeof pending.current>) =>
    query.data?.kind === 'RESCHEDULE'
      ? appointmentApi.decideReschedule(requestId, { ...body, expectedAppointmentVersion: body.expectedAppointmentVersion! })
      : appointmentApi.decide(requestId, body),
    onSuccess: async () => { pending.current = null; setConfirm(null); await cache.invalidateQueries({ queryKey: root(actor, role) }); },
    onError: async error => {
      if (isConflict(error)) { pending.current = null; setConfirm(null); await cache.invalidateQueries({ queryKey: root(actor, role) }); }
      else if (isConcealed(error)) { pending.current = null; setConfirm(null); cache.removeQueries({ queryKey: root(actor, role) }); }
    },
  });
  const item = query.data;
  const submit = () => { if (!confirm || !item || decide.isPending || (item.kind === 'RESCHEDULE' && !appointment.data)) return;
    const body = pending.current ?? { decision: confirm, expectedRequestVersion: item.version,
      expectedAppointmentVersion: item.kind === 'RESCHEDULE' ? appointment.data!.version : undefined, commandKey: createCommandKey() };
    pending.current = body; decide.mutate(body);
  };
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="appointment-request" style={styles.screen} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Yêu cầu lịch hẹn</Text>
    <CoachingButton label="Quay lại" secondary onPress={() => router.back()} />
    {query.isPending ? <CoachingState busy title="Đang tải yêu cầu" message="Vui lòng chờ." /> : null}
    {query.isError ? <CoachingState title="Không thể xem yêu cầu" message={message(query.error)} onRetry={() => query.refetch()} /> : null}
    {item && !denied ? <CoachingCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{item.kind === 'CREATE' ? 'Hẹn mới' : 'Đổi lịch hẹn'} · {label(item.status)}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Đề xuất: {localText(item.interval)}</Text>
      {item.kind === 'RESCHEDULE' ? <Text style={[styles.body, { color: theme.textSecondary }]}>Hiện tại: {appointment.isPending ? 'Đang tải…' : appointment.data ? localText(appointment.data) : 'Chưa thể tải lịch hiện tại'}</Text> : null}
      <Text style={[styles.label, { color: theme.textSecondary }]}>Phiên bản yêu cầu {item.version}</Text>
    </CoachingCard> : null}
    {item?.status === 'PENDING' && item.requestedBy !== actor && !denied ? <View>
      {!confirm ? <><CoachingButton label="Chấp nhận" onPress={() => setConfirm('ACCEPT')} /><CoachingButton label="Từ chối" secondary onPress={() => setConfirm('REJECT')} /></> : <CoachingCard>
        <Text style={[styles.body, { color: theme.textPrimary }]}>Xác nhận {confirm === 'ACCEPT' ? 'chấp nhận' : 'từ chối'} toàn bộ yêu cầu? Máy chủ sẽ kiểm tra lại quyền, thời gian và lịch trùng.</Text>
        <CoachingButton label="Xác nhận quyết định" busy={decide.isPending} disabled={decide.isPending || (item.kind === 'RESCHEDULE' && !appointment.data)} onPress={submit} />
        <CoachingButton label="Xem lại" secondary onPress={() => { pending.current = null; setConfirm(null); }} />
      </CoachingCard>}
    </View> : null}
    {decide.isError ? <CoachingState title="Chưa quyết định được" message={message(decide.error)} /> : null}
  </ScrollView></SafeAreaView>;
}

export function AppointmentDetailScreen({ appointmentId }: { appointmentId: string }) {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const role = activeCapability ?? '';
  const router = useRouter();
  const cache = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const guidanceNow = useGuidanceNow();
  const [reason, setReason] = useState('');
  const [confirmCancel, setConfirmCancel] = useState(false);
  const pending = useRef<{ expectedAppointmentVersion: number; reason: string; commandKey: string } | null>(null);
  const query = useQuery({ queryKey: [...root(actor, role), 'detail', appointmentId], enabled: !!actor,
    gcTime: 0, queryFn: () => appointmentApi.detail(appointmentId) });
  const denied = isConcealed(query.error);
  useEffect(() => { if (denied) cache.removeQueries({ queryKey: root(actor, role) }); }, [actor, cache, denied, role]);
  const cancel = useMutation({ mutationFn: (body: NonNullable<typeof pending.current>) => appointmentApi.cancel(appointmentId, body),
    onSuccess: async () => { pending.current = null; setConfirmCancel(false); await cache.invalidateQueries({ queryKey: root(actor, role) }); },
    onError: async error => {
      if (isConflict(error)) { pending.current = null; setConfirmCancel(false); await cache.invalidateQueries({ queryKey: root(actor, role) }); }
      else if (isConcealed(error)) { pending.current = null; setConfirmCancel(false); cache.removeQueries({ queryKey: root(actor, role) }); }
    },
  });
  const item = query.data;
  const submitCancel = () => { if (!item || !reason.trim() || cancel.isPending) return;
    const body = pending.current ?? { expectedAppointmentVersion: item.version, reason: reason.trim(), commandKey: createCommandKey() };
    pending.current = body; cancel.mutate(body);
  };
  return <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView testID="appointment-detail" style={styles.screen} contentContainerStyle={styles.content}>
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chi tiết lịch hẹn</Text>
    <CoachingButton label="Quay lại" secondary onPress={() => router.back()} />
    {query.isPending ? <CoachingState busy title="Đang tải lịch hẹn" message="Vui lòng chờ." /> : null}
    {query.isError ? <CoachingState title="Không thể xem lịch hẹn" message={message(query.error)} onRetry={() => query.refetch()} /> : null}
    {item && !denied ? <CoachingCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{label(item.status)}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{localText(item)}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản {item.version}{item.plannedWorkoutId ? ' · Có liên kết buổi tập' : ''}</Text>
      {item.status === 'CONFIRMED' && guidanceNow > 0 && Date.parse(item.startAt) > guidanceNow ? <>
        <CoachingButton label="Đề xuất đổi lịch hẹn" secondary onPress={() => router.push(`/appointments/${appointmentId}/reschedule` as never)} />
        {!confirmCancel ? <CoachingButton label="Hủy lịch hẹn" danger onPress={() => setConfirmCancel(true)} /> : <View>
          <Text style={[styles.body, { color: theme.textPrimary }]}>Hủy lịch hẹn không tự hủy lịch tập liên kết; máy chủ sẽ chặn nếu buổi tập cần huấn luyện viên còn phụ thuộc.</Text>
          <TextInput accessibilityLabel="Lý do hủy lịch hẹn" value={reason} onChangeText={value => { setReason(value); pending.current = null; }} multiline maxLength={1000} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} />
          <CoachingButton label={cancel.isError ? 'Thử lại cùng lệnh' : 'Xác nhận hủy'} danger busy={cancel.isPending} disabled={cancel.isPending || !reason.trim()} onPress={submitCancel} />
          <CoachingButton label="Giữ lịch hẹn" secondary onPress={() => { pending.current = null; setConfirmCancel(false); }} />
        </View>}
      </> : null}
    </CoachingCard> : null}
    {cancel.isError ? <CoachingState title="Chưa hủy được" message={message(cancel.error)} /> : null}
  </ScrollView></SafeAreaView>;
}
