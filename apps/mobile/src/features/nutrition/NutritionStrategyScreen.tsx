import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { useAuth } from '@/features/auth/AuthContext';
import { useSharingSummary } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { DateInputField } from '@/components/ui/DateInputField';
import { TimezoneContextPicker } from '@/components/ui/TimezoneContextPicker';
import { getTodayDateOnly } from '@/features/shared/dateInputUtils';
import {
  formatTimezoneDisplay,
  isSupportedTimezone,
  resolveExistingRecordTimezone,
  resolveLatestStrategyVersionTimezone,
  resolveNewRecordDefaultTimezone,
  resolveStrategyGoalTimezone,
} from '@/features/shared/timezoneUtils';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { nutritionApi, type NutritionChangeBody, type NutritionStrategyProposal,
  type NutritionTargetNutrient, type NutritionTargetSpec } from './nutritionApi';

type Command =
  | { kind: 'change'; body: NutritionChangeBody }
  | { kind: 'propose'; studentId: string; body: NutritionChangeBody }
  | { kind: 'transition'; goalId: string; body: { action: 'PAUSE' | 'RESUME' | 'END'; expectedGoalVersion: number; reason: string; commandKey: string } }
  | { kind: 'decision'; proposalId: string; body: { decision: 'ACCEPT' | 'REJECT'; expectedProposalVersion: number; commandKey: string } }
  | { kind: 'dayType'; day: string; body: { timezone: string; dayType: 'REST_DAY' | 'DEFAULT'; commandKey: string } }
  | { kind: 'override'; day: string; body: { timezone: string; goalVersionId: string; nutrientId: number; unitId: number;
      targetAmount: number; reason: string; expectedOverrideVersion: number | null; commandKey: string } }
  | { kind: 'frozenCorrection'; day: string; body: { timezone: string; nutrientId: number;
      targetAmount: number; expectedVersion: number; reason: string; commandKey: string } };

type ConfirmedStrategyDayScope = { actor: string; studentId: string; day: string; timezone: string };
/** Immutable scope captured by the handlers of one render; a retained callback keeps its own copy. */
type StrategyOpScope = { session: symbol; actor: string; capability: string | null; studentId: string;
  confirmedDayScope: ConfirmedStrategyDayScope | null; day: string; timezone: string };

const dayTypes = ['DEFAULT', 'TRAINING_DAY', 'REST_DAY'] as const;
const dayTypeLabel: Record<string, string> = { DEFAULT: 'Ngày mặc định', TRAINING_DAY: 'Ngày tập', REST_DAY: 'Ngày nghỉ đã chọn' };
const proposalLabel: Record<string, string> = { PENDING: 'Chờ học viên', ACCEPTED: 'Đã chấp nhận', REJECTED: 'Đã từ chối' };
const goalStatusLabel: Record<string, string> = { ACTIVE: 'Đang áp dụng', PAUSED: 'Tạm dừng', ENDED: 'Đã kết thúc' };

export const strategyKeys = {
  student: (actor: string) => ['nutrition-strategy', actor, 'student'] as const,
  trainer: (actor: string, student: string) => ['nutrition-strategy', actor, 'trainer', student] as const,
  catalog: () => ['nutrition-strategy', 'catalog'] as const,
  day: (actor: string, day: string, zone: string) => ['nutrition-strategy', actor, 'day', day, zone] as const,
};

export function buildStrategyTargets(values: Record<string, string>, nutrients: NutritionTargetNutrient[]): NutritionTargetSpec[] {
  const result: NutritionTargetSpec[] = [];
  for (const nutrient of nutrients) for (const dayType of dayTypes) {
    const raw = values[`${nutrient.id}:${dayType}`]?.trim();
    if (!raw) continue;
    const amount = Number(raw);
    if (!Number.isFinite(amount) || amount < 0 || !/^\d+(\.\d{1,6})?$/.test(raw)) throw new Error('Mục tiêu cần là số không âm, tối đa 6 chữ số thập phân.');
    result.push({ nutrientId: nutrient.id, unitId: nutrient.defaultUnitId, dayType, targetAmount: amount });
  }
  if (!result.length) throw new Error('Cần nhập ít nhất một mục tiêu dinh dưỡng rõ ràng.');
  return result;
}

export function NutritionStrategyScreen({ studentId, relationshipId }: { studentId?: string; relationshipId?: string }) {
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const trainer = Boolean(studentId);
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const client = useQueryClient();
  const currentScope = `${actor}:${studentId ?? ''}:${activeCapability ?? ''}`;
  const [trackedScope, setTrackedScope] = useState(currentScope);
  const [session, setSession] = useState<symbol>(() => Symbol('nutrition-strategy-session'));
  const [kind, setKind] = useState<'NEW_GOAL' | 'CHANGE_TARGET'>('NEW_GOAL');
  const [title, setTitle] = useState('');
  const [goalTypeId, setGoalTypeId] = useState<number | null>(null);
  const [manualStrategyTimezone, setManualStrategyTimezone] = useState<string | null>(null);
  const [strategyTzExpanded, setStrategyTzExpanded] = useState(false);
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [targetValues, setTargetValues] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [previewChange, setPreviewChange] = useState<NutritionChangeBody | null>(null);
  const [pending, setPending] = useState<Command | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const submitting = useRef(false);
  const [day, setDay] = useState('');
  const [manualDayTimezone, setManualDayTimezone] = useState<string | null>(null);
  const [dayTzExpanded, setDayTzExpanded] = useState(false);
  const [confirmedDayScope, setConfirmedDayScope] = useState<ConfirmedStrategyDayScope | null>(null);
  const activeOpRef = useRef<symbol | null>(null);
  const mountedRef = useRef(true);
  const liveScopeRef = useRef<(StrategyOpScope & { ready: boolean; canPropose: boolean; stateReady: boolean }) | null>(null);
  if (trackedScope !== currentScope) {
    // New actor/subject/capability session: fresh identity, no inherited context, lock or operation.
    setTrackedScope(currentScope);
    setSession(Symbol('nutrition-strategy-session'));
    setBusy(false);
    setManualStrategyTimezone(null);
    setStrategyTzExpanded(false);
    setDay('');
    setManualDayTimezone(null);
    setDayTzExpanded(false);
    setConfirmedDayScope(null);
    setPreviewChange(null);
    setPending(null);
    setMessage('');
  }
  const [overrideNutrientId, setOverrideNutrientId] = useState<number | null>(null);
  const [overrideAmount, setOverrideAmount] = useState('');
  const [overrideReason, setOverrideReason] = useState('');
  const [correctionNutrientId, setCorrectionNutrientId] = useState<number | null>(null);
  const [correctionAmount, setCorrectionAmount] = useState('');
  const [correctionReason, setCorrectionReason] = useState('');
  const [confirmCorrection, setConfirmCorrection] = useState(false);
  const [decision, setDecision] = useState<{ proposal: NutritionStrategyProposal; action: 'ACCEPT' | 'REJECT' } | null>(null);
  const [transition, setTransition] = useState<'PAUSE' | 'RESUME' | 'END' | null>(null);

  const ownerKey = trainer ? strategyKeys.trainer(actor, studentId!) : strategyKeys.student(actor);
  const stateQuery = useQuery({ queryKey: ownerKey, queryFn: () => trainer ? nutritionApi.trainerStrategy(studentId!) : nutritionApi.strategy(),
    enabled: Boolean(actor && (trainer ? activeCapability === 'TRAINER' : activeCapability === 'STUDENT')),
    retry: false, staleTime: 0, gcTime: trainer ? 0 : undefined, refetchOnMount: 'always' });
  const typesQuery = useQuery({ queryKey: [...strategyKeys.catalog(), 'types'], queryFn: nutritionApi.goalTypes, enabled: Boolean(actor) });
  const nutrientsQuery = useQuery({ queryKey: [...strategyKeys.catalog(), 'nutrients'], queryFn: nutritionApi.targetNutrients, enabled: Boolean(actor) });
  const sharingQuery = useSharingSummary(relationshipId ?? '');
  const permission = sharingQuery.data?.items.find((item) => item.dataScope === 'NUTRITION_GOAL');
  const canPropose = trainer && sharingQuery.data?.relationshipStatus === 'ACTIVE' && permission?.state === 'ALLOWED'
    && ['CONTRIBUTE', 'MANAGE'].includes(permission.accessLevel ?? '');
  const concealed = stateQuery.error instanceof ApiError && [403, 404].includes(stateQuery.error.status);
  useEffect(() => {
    if (concealed && trainer) client.removeQueries({ queryKey: ['nutrition-strategy', actor, 'trainer', studentId] });
  }, [actor, client, concealed, studentId, trainer]);
  const goal = stateQuery.data?.goal ?? null;
  const latestVersion = goal?.versions.at(-1) ?? null;
  const strategyTimezone = manualStrategyTimezone ?? (
    kind === 'CHANGE_TARGET'
      ? (resolveLatestStrategyVersionTimezone(goal) ?? resolveNewRecordDefaultTimezone())
      : resolveNewRecordDefaultTimezone()
  );
  const coveringDayStrategyTimezone = day ? resolveStrategyGoalTimezone(goal, day) : null;
  const dayTimezone = manualDayTimezone ?? coveringDayStrategyTimezone ?? resolveNewRecordDefaultTimezone();
  const validDayInput = moment(day, 'YYYY-MM-DD', true).isValid() && isSupportedTimezone(dayTimezone);
  const validDay = Boolean(
    validDayInput &&
    confirmedDayScope?.actor === actor &&
    confirmedDayScope?.studentId === (studentId ?? '') &&
    confirmedDayScope?.day === day &&
    confirmedDayScope?.timezone === dayTimezone,
  );
  const dayQuery = useQuery({ queryKey: strategyKeys.day(actor, day, dayTimezone), queryFn: () => nutritionApi.targetDay(day, dayTimezone),
    enabled: Boolean(!trainer && actor && validDay), retry: false });
  const dayDataMatchesScope = Boolean(
    !trainer &&
    validDay &&
    !dayQuery.isPending &&
    !dayQuery.isFetching &&
    !dayQuery.isError &&
    dayQuery.data &&
    dayQuery.data.date === day &&
    dayQuery.data.timezone === dayTimezone,
  );
  const activeDayTimezone = dayDataMatchesScope && dayQuery.data?.timezone
    ? resolveExistingRecordTimezone(dayQuery.data.timezone, dayTimezone)
    : dayTimezone;
  const dayTimezoneConflict = validDay && dayQuery.isError && dayQuery.error instanceof ApiError && dayQuery.error.status === 409;
  const resolvedTargetDayReady = Boolean(dayDataMatchesScope && !dayTimezoneConflict);
  const correctionHistoryQuery = useQuery({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections', studentId ?? actor, day],
    queryFn: () => trainer ? nutritionApi.trainerFrozenCorrections(studentId!, day) : nutritionApi.frozenCorrections(day),
    enabled: Boolean(actor && validDay && (!trainer || (activeCapability === 'TRAINER' && studentId))), retry: false });
  useEffect(() => {
    if (trainer && correctionHistoryQuery.error instanceof ApiError
        && [403, 404].includes(correctionHistoryQuery.error.status))
      client.removeQueries({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections', studentId] });
  }, [actor, client, correctionHistoryQuery.error, studentId, trainer]);

  const opScope: StrategyOpScope = { session, actor, capability: activeCapability, studentId: studentId ?? '',
    confirmedDayScope, day, timezone: activeDayTimezone };
  useLayoutEffect(() => {
    liveScopeRef.current = { ...opScope, ready: resolvedTargetDayReady, canPropose: Boolean(canPropose),
      stateReady: Boolean(stateQuery.data) && !stateQuery.isError };
  });
  useLayoutEffect(() => {
    mountedRef.current = true;
    return () => { mountedRef.current = false; activeOpRef.current = null; };
  }, []);
  useLayoutEffect(() => {
    // An operation started in a previous session can neither keep nor release the new session's lock.
    activeOpRef.current = null;
    submitting.current = false;
  }, [session]);
  // Guard A (current scope): the callback was created for the live session/actor/capability/subject and, for
  // day-scoped work, for the live confirmation identity (null included), day and timezone. Every handler
  // checks it first, before validation, messages, operation claims or loading changes.
  const isScopeLive = (scope: StrategyOpScope, dayScoped: boolean) => {
    const live = liveScopeRef.current;
    return Boolean(mountedRef.current && live && live.session === scope.session && live.actor === scope.actor
      && live.capability === scope.capability && live.studentId === scope.studentId
      && (!dayScoped || (live.confirmedDayScope === scope.confirmedDayScope
        && live.day === scope.day && live.timezone === scope.timezone)));
  };
  // Guard B (dispatch allowed): current scope plus live readiness for this command: a ready confirmed target day,
  // the current Trainer proposal permission for its subject, or a loaded strategy state for Student decisions.
  const canDispatch = (scope: StrategyOpScope, command: Command) => {
    const isDayCommand = command.kind === 'dayType' || command.kind === 'override' || command.kind === 'frozenCorrection';
    if (!isScopeLive(scope, isDayCommand)) return false;
    const live = liveScopeRef.current;
    if (isDayCommand) return scope.confirmedDayScope !== null && Boolean(live?.ready);
    if (command.kind === 'propose') return Boolean(live?.canPropose) && command.studentId === scope.studentId;
    return Boolean(live?.stateReady);
  };
  // Runs a UI action only if the render scope that created the callback is still live, so a copy
  // retained from an older scope cannot change the new UI. Called from event handlers, never in render.
  const runInScope = (scope: typeof opScope, dayScoped: boolean, action: () => void) => {
    if (isScopeLive(scope, dayScoped)) action();
  };

  async function refresh() {
    await client.invalidateQueries({ queryKey: ownerKey });
    if (!trainer) await client.invalidateQueries({ queryKey: ['nutrition-strategy', actor, 'day'] });
    await client.invalidateQueries({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections'] });
  }
  async function send(command: Command) {
    const scope = opScope;
    const isDayCommand = command.kind === 'dayType' || command.kind === 'override' || command.kind === 'frozenCorrection';
    // Guard A: a callback retained from an earlier scope (or after unmount) is inert and silent.
    if (!isScopeLive(scope, isDayCommand)) return;
    // A Trainer proposal whose current permission is gone stays inert too.
    if (command.kind === 'propose' && !canDispatch(scope, command)) return;
    const commandMatchesScope = !('day' in command) || command.day === scope.day;
    if (isDayCommand && (!canDispatch(scope, command) || !commandMatchesScope)) {
      setMessage('Ngữ cảnh chỉ tiêu ngày chưa tải xong hoặc đang xung đột; không thể lưu thay đổi.');
      return;
    }
    if (submitting.current) return;
    const token = Symbol('nutrition-strategy-op');
    activeOpRef.current = token;
    submitting.current = true; setBusy(true); setPending(command); setMessage('');
    // Guard C (continuation): this operation still owns its outcome in a live scope. Readiness is not
    // required, so the result of a request that was really sent is not dropped by a same-scope refetch.
    const owns = () => activeOpRef.current === token && isScopeLive(scope, isDayCommand);
    // Guard B as the request runner's shouldContinue: checked before the token read, before fetch and
    // before a 401 retry, so readiness or permission lost during those awaits stops the send.
    const guard = { shouldContinue: () => activeOpRef.current === token && canDispatch(scope, command) };
    try {
      if (command.kind === 'change') await nutritionApi.changeStrategy(command.body, guard);
      if (command.kind === 'propose') await nutritionApi.proposeStrategy(command.studentId, command.body, guard);
      if (command.kind === 'transition') await nutritionApi.transitionGoal(command.goalId, command.body, guard);
      if (command.kind === 'decision') await nutritionApi.decideStrategy(command.proposalId, command.body, guard);
      if (command.kind === 'dayType') await nutritionApi.chooseDayType(command.day, command.body, guard);
      if (command.kind === 'override') await nutritionApi.overrideTarget(command.day, command.body, guard);
      if (command.kind === 'frozenCorrection') await nutritionApi.correctFrozenTarget(command.day, command.body, guard);
      // A stale continuation cannot claim or report the outcome in the new scope.
      if (!owns()) return;
      setPending(null); setPreviewChange(null); setDecision(null); setTransition(null); setConfirmCorrection(false);
      setMessage(trainer ? 'Đã gửi đề xuất. Học viên sẽ quyết định toàn bộ thay đổi.' : 'Đã lưu. Mục tiêu và nhật ký thực tế vẫn là hai dữ liệu riêng.');
      await refresh();
    } catch (error) {
      if (!owns()) return;
      if (error instanceof Error && error.name === 'AbortError') {
        // An AbortError is not evidence that the intent never reached the server: fetch may have received it,
        // and a guard stop before a 401 retry or a replay says nothing about earlier attempts. Report the outcome
        // as unknown, keep the saved intent/body/key and release the lock; dispatch stays blocked until ready.
        setMessage('Chưa xác định được kết quả của lệnh. Khi kết nối và ngữ cảnh hợp lệ, hãy thử lại cùng lệnh.');
        return;
      }
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        setPending(null); setPreviewChange(null); setDecision(null);
        client.removeQueries({ queryKey: trainer ? ['nutrition-strategy', actor, 'trainer', studentId] : ['nutrition-strategy', actor] });
        client.removeQueries({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections', studentId ?? actor] });
        setMessage('Không còn quyền truy cập dữ liệu này.');
      } else if (error instanceof ApiError && error.status === 409) {
        setPending(null); setPreviewChange(null); setDecision(null); setTransition(null);
        await refresh();
        if (!owns()) return;
        setMessage('Dữ liệu đã thay đổi. Hãy xem lại bản mới rồi xác nhận lại; ứng dụng sẽ không tự gửi lại lệnh cũ.');
      } else if (error instanceof ApiError && error.status === 400) {
        setPending(null); setMessage('Thông tin chưa hợp lệ. Hãy kiểm tra ngày, múi giờ, số lượng và lý do.');
      } else setMessage('Chưa rõ lệnh đã được lưu chưa. Có thể thử lại cùng lệnh, giữ nguyên nội dung và mã lệnh.');
    } finally {
      // Only the operation that still holds the lock may release it.
      if (activeOpRef.current === token) {
        activeOpRef.current = null;
        submitting.current = false;
        setBusy(false);
      }
    }
  }
  function prepareChange() {
    if (!isScopeLive(opScope, false)) return;
    try {
      if (!title.trim() || goalTypeId === null || !moment.tz.zone(strategyTimezone) || !moment(effectiveFrom, 'YYYY-MM-DD', true).isValid())
        throw new Error('Chọn loại mục tiêu, tên và ngày hiệu lực hợp lệ.');
      if (trainer && (!canPropose || !reason.trim())) throw new Error('Cần quyền đóng góp hiện hành và lý do đề xuất.');
      if (kind === 'CHANGE_TARGET' && (!goal || !latestVersion || latestVersion.dateSemantics !== 'HALF_OPEN'))
        throw new Error('Không có phiên bản mục tiêu phù hợp để đổi mục tiêu.');
      const body: NutritionChangeBody = { kind, goalId: kind === 'CHANGE_TARGET' ? goal!.id : null,
        expectedGoalVersion: kind === 'CHANGE_TARGET' ? goal!.aggregateVersion : null,
        baseVersionId: kind === 'CHANGE_TARGET' ? latestVersion!.id : null,
        title: title.trim(), goalTypeId, timezone: strategyTimezone, effectiveFrom,
        targets: buildStrategyTargets(targetValues, nutrientsQuery.data ?? []),
        reason: trainer ? reason.trim() : null, confirmed: !trainer, commandKey: createCommandKey() };
      setPreviewChange(body); setMessage('');
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Không thể tạo bản xem trước.'); }
  }
  function chooseOverride() {
    if (!isScopeLive(opScope, true)) return;
    if (!opScope.confirmedDayScope || !liveScopeRef.current?.ready) {
      setMessage('Ngữ cảnh chỉ tiêu ngày chưa được tải hợp lệ; chưa thể ghi đè.');
      return;
    }
    const nutrient = nutrientsQuery.data?.find((item) => item.id === overrideNutrientId);
    const data = dayQuery.data;
    const raw = overrideAmount.trim();
    if (!data?.goalVersionId || data.frozen || !nutrient || !/^\d+(\.\d{1,6})?$/.test(raw) || !overrideReason.trim()) {
      setMessage('Cần chọn chất dinh dưỡng, nhập lượng hợp lệ và lý do; ngày đã chốt không thể sửa.'); return;
    }
    const existing = data.targets.find((target) => target.nutrientId === nutrient.id && target.source === 'OVERRIDE');
    send({ kind: 'override', day, body: { timezone: activeDayTimezone, goalVersionId: data.goalVersionId, nutrientId: nutrient.id,
      unitId: nutrient.defaultUnitId, targetAmount: Number(raw), reason: overrideReason.trim(),
      expectedOverrideVersion: existing?.overrideVersion ?? null, commandKey: createCommandKey() } });
  }
  function correctFrozenTarget() {
    if (!isScopeLive(opScope, true)) return;
    if (!opScope.confirmedDayScope || !liveScopeRef.current?.ready) {
      setMessage('Ngữ cảnh chỉ tiêu ngày đã chốt chưa được tải hợp lệ; chưa thể điều chỉnh.');
      return;
    }
    const target = dayQuery.data?.targets.find((item) => item.nutrientId === correctionNutrientId);
    const amount = correctionAmount.trim();
    if (!dayQuery.data?.frozen || !target || target.correctionVersion === null
        || !/^\d+(\.\d{1,6})?$/.test(amount) || !correctionReason.trim()) {
      setMessage('Chọn chất dinh dưỡng của ngày đã chốt, nhập số hợp lệ và lý do sửa bắt buộc.'); return;
    }
    send({ kind: 'frozenCorrection', day, body: { timezone: activeDayTimezone, nutrientId: target.nutrientId,
      targetAmount: Number(amount), expectedVersion: target.correctionVersion,
      reason: correctionReason.trim(), commandKey: createCommandKey() } });
  }
  const input = (label: string, value: string, onChangeText: (value: string) => void, numeric = false) =>
    <View style={{ marginBottom: 10 }}><Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text>
      <TextInput accessibilityLabel={label} value={value} onChangeText={onChangeText} keyboardType={numeric ? 'decimal-pad' : 'default'}
        style={{ borderWidth: 1, borderColor: theme.border, color: theme.textPrimary, minHeight: 48, padding: 10, borderRadius: 8 }} /></View>;
  if (trainer ? activeCapability !== 'TRAINER' : activeCapability !== 'STUDENT')
    return <WorkoutState title="Không đúng vai trò" message="Hãy chuyển sang vai trò phù hợp để xem mục tiêu dinh dưỡng." />;
  if (stateQuery.isPending || (trainer && stateQuery.isFetching)) return <WorkoutState title="Đang tải chiến lược" message="Đang kiểm tra quyền hiện hành." busy />;
  if (stateQuery.isError || !stateQuery.data) return <WorkoutState title="Không thể xem chiến lược" message={concealed ? 'Quyền xem hiện không còn.' : 'Kiểm tra kết nối rồi thử lại.'}
    onRetry={concealed ? undefined : () => stateQuery.refetch()} />;
  return <ScrollView testID={trainer ? 'trainer-nutrition-strategy' : 'student-nutrition-strategy'} style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>{trainer ? 'Chiến lược dinh dưỡng học viên' : 'Mục tiêu dinh dưỡng'}</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Mục tiêu khác với lượng ăn đã ghi. Ngày chưa ghi không có nghĩa là 0 kcal.</Text>
    {message ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.textPrimary }]}>{message}</Text> : null}
    {pending && !busy && (!['dayType', 'override', 'frozenCorrection'].includes(pending.kind) || resolvedTargetDayReady) ? <WorkoutButton label="Thử lại cùng lệnh" onPress={() => send(pending)} /> : null}
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{goal?.title ?? 'Chưa có mục tiêu'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{goal ? `${goalStatusLabel[goal.status] ?? goal.status} · Phiên bản tổng ${goal.aggregateVersion}` : 'Chưa có mục tiêu đang xem.'}</Text>
      {goal?.versions.map((version) => <View key={version.id} style={{ marginTop: 8 }}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>Bản {version.versionNumber}: {version.effectiveFrom} → {version.effectiveUntil ?? 'chưa kết thúc'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{version.dateSemantics === 'LEGACY' ? 'Bản lịch sử; không tự diễn giải theo khoảng mới.' : `Khung giờ: ${formatTimezoneDisplay(version.timezone)}`}</Text>
        {(version.targets ?? []).map((target) => <Text key={`${target.nutrientId}:${target.dayType}`} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Chất dinh dưỡng chưa có tên'} · {dayTypeLabel[target.dayType]}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol ?? 'đơn vị chưa rõ'}
        </Text>)}
      </View>)}
    </WorkoutCard>
    {!trainer && goal && ['ACTIVE', 'PAUSED'].includes(goal.status) ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Vòng đời mục tiêu</Text>
      {input('Lý do thay đổi trạng thái', reason, setReason)}
      {!transition ? <View style={styles.row}>
        {goal.status === 'ACTIVE' ? <WorkoutButton label="Tạm dừng" secondary onPress={() => runInScope(opScope, false, () => setTransition('PAUSE'))} /> : <WorkoutButton label="Tiếp tục" onPress={() => runInScope(opScope, false, () => setTransition('RESUME'))} />}
        <WorkoutButton label="Kết thúc" secondary onPress={() => runInScope(opScope, false, () => setTransition('END'))} />
      </View> : <><Text style={[styles.body, { color: theme.textPrimary }]}>Xác nhận {transition === 'PAUSE' ? 'tạm dừng' : transition === 'RESUME' ? 'tiếp tục' : 'kết thúc'}? Mục tiêu ngày đã chốt không đổi.</Text>
        <WorkoutButton label="Xác nhận trạng thái" disabled={busy || !reason.trim()} onPress={() => send({ kind: 'transition', goalId: goal.id,
          body: { action: transition, expectedGoalVersion: goal.aggregateVersion, reason: reason.trim(), commandKey: createCommandKey() } })} />
        <WorkoutButton label="Quay lại" secondary onPress={() => runInScope(opScope, false, () => setTransition(null))} /></>}
    </WorkoutCard> : null}
    {trainer && !canPropose ? <WorkoutState title="Chỉ xem" message="Cần Coaching Period và quyền NUTRITION_GOAL Đóng góp hiện hành để đề xuất." /> : null}
    {(!trainer || canPropose) && <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{trainer ? 'Đề xuất thay đổi' : 'Xác nhận chiến lược'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{trainer ? 'Chỉ học viên có thể chấp nhận; đề xuất chưa thay đổi chỉ tiêu ngay.' : 'Thay đổi này tạo phiên bản chỉ tiêu mới, không sửa nhật ký ăn uống.'}</Text>
      <View style={styles.row}>
        <WorkoutButton label="Mục tiêu mới" secondary={kind !== 'NEW_GOAL'} onPress={() => runInScope(opScope, false, () => { setKind('NEW_GOAL'); setManualStrategyTimezone(null); setPreviewChange(null); })} />
        <WorkoutButton label="Đổi chỉ tiêu" secondary={kind !== 'CHANGE_TARGET'} disabled={!goal || !latestVersion || latestVersion.dateSemantics !== 'HALF_OPEN'}
          onPress={() => runInScope(opScope, false, () => { setKind('CHANGE_TARGET'); setManualStrategyTimezone(null); setPreviewChange(null); })} />
      </View>
      {input('Tên mục tiêu', title, (value) => { setTitle(value); setPreviewChange(null); })}
      <Text style={[styles.label, { color: theme.textPrimary }]}>Loại mục tiêu từ danh mục</Text>
      {typesQuery.isPending || nutrientsQuery.isPending ? <WorkoutState title="Đang tải danh mục" message="Vui lòng chờ." busy /> : null}
      {typesQuery.isError || nutrientsQuery.isError ? <WorkoutState title="Không tải được danh mục" message="Không thể chọn mục khi danh mục chưa có." onRetry={() => { typesQuery.refetch(); nutrientsQuery.refetch(); }} /> : null}
      {typesQuery.data?.length === 0 ? <WorkoutState title="Danh mục trống" message="Chưa có loại mục tiêu hợp lệ; không thể gửi dữ liệu tự đoán." /> : null}
      {typesQuery.data?.map((type) => <Pressable key={type.id} accessibilityRole="radio" accessibilityLabel={`Chọn loại mục tiêu ${type.name}`} accessibilityState={{ checked: goalTypeId === type.id }}
        onPress={() => runInScope(opScope, false, () => { setGoalTypeId(type.id); setPreviewChange(null); })} style={{ minHeight: 44, justifyContent: 'center' }}>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{goalTypeId === type.id ? '◉' : '○'} {type.name}</Text></Pressable>)}
      <TimezoneContextPicker
        testIDPrefix="strategy-timezone"
        label="Khung giờ áp dụng"
        value={strategyTimezone}
        expanded={strategyTzExpanded}
        onToggleExpanded={() => setStrategyTzExpanded((v) => !v)}
        extraTimezones={[goal?.timezone, latestVersion?.timezone, user?.timezone]}
        onChange={(nextZone) => {
          setManualStrategyTimezone(nextZone);
          setStrategyTzExpanded(false);
          setPreviewChange(null);
        }}
      />
      <DateInputField
        testID="strategy-effective-from-input"
        label="Có hiệu lực từ ngày"
        placeholder="Chọn ngày hiệu lực"
        value={effectiveFrom}
        onChange={(value) => { setEffectiveFrom(value); setPreviewChange(null); }}
        initialBrowseDate={getTodayDateOnly(strategyTimezone)}
        sessionKey={`${actor}:${studentId ?? ''}:${kind}`}
        accessibilityLabel="Có hiệu lực từ ngày"
      />
      <Text style={[styles.body, { color: theme.textSecondary }]}>Ngày đầu chỉ dùng khi chưa chốt chỉ tiêu; thay đổi sau phải từ ngày kế tiếp hoặc tương lai. Hệ thống sẽ kiểm tra mốc thời gian.</Text>
      {nutrientsQuery.data?.map((nutrient) => <View key={nutrient.id}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{nutrient.name} ({nutrient.unitSymbol})</Text>
        {dayTypes.map((dayType) => <React.Fragment key={dayType}>{input(`${nutrient.name} · ${dayTypeLabel[dayType]} (${nutrient.unitSymbol})`,
          targetValues[`${nutrient.id}:${dayType}`] ?? '', (value) => { setTargetValues((old) => ({ ...old, [`${nutrient.id}:${dayType}`]: value })); setPreviewChange(null); }, true)}</React.Fragment>)}
      </View>)}
      {trainer ? input('Lý do đề xuất', reason, (value) => { setReason(value); setPreviewChange(null); }) : null}
      <WorkoutButton label="Xem trước toàn bộ mục tiêu" disabled={busy || !typesQuery.data?.length || !nutrientsQuery.data?.length} onPress={prepareChange} />
      {previewChange ? <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Xem trước trước khi gửi</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{previewChange.kind === 'NEW_GOAL' ? 'Mục tiêu mới' : 'Đổi chỉ tiêu'} · {previewChange.title} · {previewChange.effectiveFrom} · {formatTimezoneDisplay(previewChange.timezone)}</Text>
        {previewChange.targets.map((target) => <Text key={`${target.nutrientId}:${target.dayType}`} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name} · {dayTypeLabel[target.dayType]}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol}
        </Text>)}
        <WorkoutButton label={trainer ? 'Gửi đề xuất cho học viên' : 'Xác nhận và áp dụng'} disabled={busy}
          onPress={() => send(trainer ? { kind: 'propose', studentId: studentId!, body: previewChange } : { kind: 'change', body: previewChange })} />
        <WorkoutButton label="Chỉnh lại" secondary onPress={() => runInScope(opScope, false, () => setPreviewChange(null))} />
      </WorkoutCard> : null}
    </WorkoutCard>}
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{trainer ? 'Đề xuất đã gửi' : 'Đề xuất từ huấn luyện viên'}</Text>
      {stateQuery.data.proposals.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có đề xuất.</Text> : null}
      {stateQuery.data.proposals.map((proposal) => <WorkoutCard key={proposal.id}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{proposal.title} · {proposalLabel[proposal.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{proposal.kind === 'NEW_GOAL' ? 'Mục tiêu mới' : 'Đổi chỉ tiêu'} · {proposal.effectiveFrom} · {formatTimezoneDisplay(proposal.timezone)}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do: {proposal.reason}</Text>
        {proposal.targets.map((target) => <Text key={`${target.nutrientId}:${target.dayType}`} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Chất dinh dưỡng chưa có tên'} · {dayTypeLabel[target.dayType]}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol ?? 'đơn vị chưa rõ'}
        </Text>)}
        {!trainer && proposal.status === 'PENDING' ? <View style={styles.row}>
          <WorkoutButton label="Xem để chấp nhận" onPress={() => runInScope(opScope, false, () => setDecision({ proposal, action: 'ACCEPT' }))} />
          <WorkoutButton label="Từ chối" secondary onPress={() => runInScope(opScope, false, () => setDecision({ proposal, action: 'REJECT' }))} />
        </View> : null}
      </WorkoutCard>)}
      {decision ? <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{decision.action === 'ACCEPT' ? 'Xác nhận áp dụng toàn bộ đề xuất?' : 'Xác nhận từ chối?'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{decision.proposal.title} · {decision.proposal.effectiveFrom} · {formatTimezoneDisplay(decision.proposal.timezone)}. Hệ thống sẽ kiểm tra lại quyền và phiên bản.</Text>
        <WorkoutButton label="Xác nhận quyết định" disabled={busy} onPress={() => send({ kind: 'decision', proposalId: decision.proposal.id,
          body: { decision: decision.action, expectedProposalVersion: decision.proposal.proposalVersion, commandKey: createCommandKey() } })} />
        <WorkoutButton label="Quay lại" secondary onPress={() => runInScope(opScope, false, () => setDecision(null))} />
      </WorkoutCard> : null}
    </WorkoutCard>
    {!trainer ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Mục tiêu cho một ngày</Text>
      <DateInputField
        testID="strategy-student-day-input"
        label="Ngày"
        placeholder="Chọn ngày"
        value={day}
        onChange={(value) => {
          setDay(value);
          setConfirmedDayScope(null);
          setConfirmCorrection(false);
          setPending((prev) => (prev && ['dayType', 'override', 'frozenCorrection'].includes(prev.kind) ? null : prev));
        }}
        initialBrowseDate={getTodayDateOnly(dayTimezone)}
        sessionKey={`${actor}:student-day`}
        accessibilityLabel="Ngày"
      />
      <TimezoneContextPicker
        testIDPrefix="strategy-day-timezone"
        label="Khung giờ của chỉ tiêu"
        value={activeDayTimezone}
        expanded={dayTzExpanded || dayTimezoneConflict}
        onToggleExpanded={() => setDayTzExpanded((v) => !v)}
        extraTimezones={[goal?.timezone, ...(goal?.versions.map((v) => v.timezone) ?? []), dayDataMatchesScope ? dayQuery.data?.timezone : null]}
        onChange={(nextZone) => {
          setManualDayTimezone(nextZone);
          setDayTzExpanded(false);
          setConfirmedDayScope(null);
          setConfirmCorrection(false);
          setPending((prev) => (prev && ['dayType', 'override', 'frozenCorrection'].includes(prev.kind) ? null : prev));
        }}
      />
      <Text style={[styles.body, { color: theme.textSecondary }]}>Dữ liệu chỉ là bản xem trước; lần ghi đầu hoặc xác nhận ngày sẽ chốt dữ liệu tại thời điểm đó.</Text>
      <WorkoutButton label="Xem ngày theo múi giờ đã chọn" disabled={!validDayInput}
        onPress={() => runInScope(opScope, true, () => setConfirmedDayScope({ actor, studentId: studentId ?? '', day, timezone: dayTimezone }))} />
      {validDay && dayQuery.isPending ? <WorkoutState title="Đang tải mục tiêu ngày" message="Vui lòng chờ." busy /> : null}
      {validDay && dayTimezoneConflict ? (
        <WorkoutState
          title="Ngày này chốt theo khung giờ khác"
          message="Mục tiêu hoặc ảnh chụp ngày này dùng một khung giờ khác. Hãy chọn lại đúng khung giờ trong danh sách thân thiện ở trên rồi xem lại."
        />
      ) : null}
      {validDay && dayQuery.isError && !dayTimezoneConflict ? <WorkoutState title="Không thể xem ngày" message="Khung giờ hoặc bản lịch sử không còn phù hợp." onRetry={() => dayQuery.refetch()} /> : null}
      {resolvedTargetDayReady && dayQuery.data ? <>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{dayTypeLabel[dayQuery.data.dayType]} · {dayQuery.data.frozen ? 'Đã chốt' : 'Xem trước, chưa chốt'}</Text>
        {dayQuery.data.targets.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Ngày này không có chỉ tiêu. Điều đó không nói gì về lượng đã ăn.</Text> : null}
        {dayQuery.data.targets.map((target) => <Text key={target.nutrientId} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Chất dinh dưỡng chưa có tên'}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol ?? 'đơn vị chưa rõ'} · {target.source === 'OVERRIDE' ? 'Ghi đè' : target.source === 'DAY_TYPE' ? 'Theo loại ngày' : 'Mặc định'}
        </Text>)}
        {dayQuery.data.frozen ? <>
          <Text style={[styles.body, { color: theme.textSecondary }]}>So sánh tiến độ dùng chỉ tiêu sau điều chỉnh. Chỉ tiêu gốc và lượng ăn thực tế không bị ghi đè.</Text>
          {dayQuery.data.targets.map((target) => <Text key={`original-${target.nutrientId}`} style={[styles.body, { color: theme.textSecondary }]}>
            {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Chất dinh dưỡng'}: gốc {target.originalTargetAmount ?? 'chưa rõ'} → hiện tại {target.targetAmount} · phiên bản sửa {target.correctionVersion ?? 0}
          </Text>)}
          {dayQuery.data.targets.map((target) => <Pressable key={`correct-${target.nutrientId}`} accessibilityRole="radio"
            accessibilityLabel={`Sửa chỉ tiêu chất dinh dưỡng ${target.nutrientId}`}
            accessibilityState={{ checked: correctionNutrientId === target.nutrientId }}
            onPress={() => runInScope(opScope, true, () => { setCorrectionNutrientId(target.nutrientId); setConfirmCorrection(false); })}
            style={{ minHeight: 44, justifyContent: 'center' }}>
            <Text style={[styles.body, { color: theme.textPrimary }]}>{correctionNutrientId === target.nutrientId ? '◉' : '○'} Sửa {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'chất dinh dưỡng'}</Text>
          </Pressable>)}
          {input('Chỉ tiêu sau điều chỉnh', correctionAmount, (value) => { setCorrectionAmount(value); setConfirmCorrection(false); }, true)}
          {input('Lý do điều chỉnh', correctionReason, (value) => { setCorrectionReason(value); setConfirmCorrection(false); })}
          {!confirmCorrection ? <WorkoutButton label="Xem lại điều chỉnh chỉ tiêu đã chốt" disabled={busy}
            onPress={() => runInScope(opScope, true, () => setConfirmCorrection(true))} /> : <>
            <Text style={[styles.body, { color: theme.textPrimary }]}>Chỉ sửa chất dinh dưỡng đã chọn của ngày {day}; chỉ tiêu gốc và lượng ăn thực tế được giữ nguyên. Lý do: {correctionReason || 'chưa nhập'}.</Text>
            <WorkoutButton label="Xác nhận điều chỉnh" disabled={busy || !resolvedTargetDayReady} onPress={correctFrozenTarget} />
            <WorkoutButton label="Quay lại" secondary onPress={() => runInScope(opScope, true, () => setConfirmCorrection(false))} />
          </>}
        </> : null}
        {!dayQuery.data.frozen && dayQuery.data.goalVersionId ? <>
          <View style={styles.row}>
            <WorkoutButton label="Chọn ngày nghỉ" secondary disabled={busy || !resolvedTargetDayReady} onPress={() => send({ kind: 'dayType', day, body: { timezone: activeDayTimezone, dayType: 'REST_DAY', commandKey: createCommandKey() } })} />
            <WorkoutButton label="Bỏ chọn ngày nghỉ" secondary disabled={busy || !resolvedTargetDayReady} onPress={() => send({ kind: 'dayType', day, body: { timezone: activeDayTimezone, dayType: 'DEFAULT', commandKey: createCommandKey() } })} />
          </View>
          <Text style={[styles.label, { color: theme.textPrimary }]}>Ghi đè chỉ tiêu ngày này</Text>
          {nutrientsQuery.data?.map((nutrient) => <Pressable key={nutrient.id} accessibilityRole="radio"
            accessibilityState={{ checked: overrideNutrientId === nutrient.id }} onPress={() => runInScope(opScope, true, () => setOverrideNutrientId(nutrient.id))}
            style={{ minHeight: 44, justifyContent: 'center' }}><Text style={[styles.body, { color: theme.textPrimary }]}>
              {overrideNutrientId === nutrient.id ? '◉' : '○'} {nutrient.name} ({nutrient.unitSymbol})</Text></Pressable>)}
          {input('Lượng ghi đè', overrideAmount, setOverrideAmount, true)}
          {input('Lý do ghi đè', overrideReason, setOverrideReason)}
          <WorkoutButton label="Lưu ghi đè theo phiên bản mục tiêu này" disabled={busy || !resolvedTargetDayReady} onPress={chooseOverride} />
        </> : null}
      </> : null}
    </WorkoutCard> : null}
    {trainer ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Lịch sử điều chỉnh chỉ tiêu đã chốt</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ đọc khi còn quyền NUTRITION_GOAL VIEW hiện hành; không có nút sửa.</Text>
      <DateInputField
        testID="strategy-trainer-day-input"
        label="Ngày"
        placeholder="Chọn ngày"
        value={day}
        onChange={(value) => { setDay(value); setConfirmedDayScope(null); }}
        initialBrowseDate={getTodayDateOnly(dayTimezone)}
        sessionKey={`${actor}:${studentId ?? ''}:trainer-day`}
        accessibilityLabel="Ngày"
      />
      <Text testID="strategy-trainer-timezone-display" style={[styles.body, { color: theme.textSecondary }]}>
        Khung giờ áp dụng: {formatTimezoneDisplay(dayTimezone)}
      </Text>
      <WorkoutButton label="Xem lịch sử ngày này" secondary
        disabled={!validDayInput}
        onPress={() => runInScope(opScope, true, () => setConfirmedDayScope({ actor, studentId: studentId ?? '', day, timezone: dayTimezone }))} />
    </WorkoutCard> : null}
    {validDay ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Lịch sử sửa chỉ tiêu</Text>
      {correctionHistoryQuery.isPending ? <WorkoutState title="Đang tải lịch sử" message="Vui lòng chờ." busy /> : null}
      {correctionHistoryQuery.isError ? <WorkoutState title="Không xem được lịch sử" message="Quyền hoặc dữ liệu ngày này không còn khả dụng." onRetry={() => correctionHistoryQuery.refetch()} /> : null}
      {correctionHistoryQuery.data?.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có điều chỉnh cho ngày này.</Text> : null}
      {correctionHistoryQuery.data?.map((entry) => <Text key={entry.id} style={[styles.body, { color: theme.textPrimary }]}>
        {nutrientsQuery.data?.find((item) => item.id === entry.nutrientId)?.name ?? 'Chất dinh dưỡng'} · gốc {entry.originalAmount} · trước {entry.beforeAmount} → sau {entry.afterAmount} · {entry.reason} · người sửa {entry.actorId} · {entry.correctedAt}
      </Text>)}
    </WorkoutCard> : null}
  </ScrollView>;
}
