import React, { useEffect, useRef, useState } from 'react';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { useAuth } from '@/features/auth/AuthContext';
import { useSharingSummary } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
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
  const [kind, setKind] = useState<'NEW_GOAL' | 'CHANGE_TARGET'>('NEW_GOAL');
  const [title, setTitle] = useState('');
  const [goalTypeId, setGoalTypeId] = useState<number | null>(null);
  const [timezone, setTimezone] = useState('');
  const [effectiveFrom, setEffectiveFrom] = useState('');
  const [targetValues, setTargetValues] = useState<Record<string, string>>({});
  const [reason, setReason] = useState('');
  const [previewChange, setPreviewChange] = useState<NutritionChangeBody | null>(null);
  const [pending, setPending] = useState<Command | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const submitting = useRef(false);
  const [day, setDay] = useState('');
  const [dayConfirmed, setDayConfirmed] = useState(false);
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
  const validDay = moment(day, 'YYYY-MM-DD', true).isValid() && Boolean(moment.tz.zone(timezone)) && dayConfirmed;
  const dayQuery = useQuery({ queryKey: strategyKeys.day(actor, day, timezone), queryFn: () => nutritionApi.targetDay(day, timezone),
    enabled: Boolean(!trainer && actor && validDay), retry: false });
  const correctionHistoryQuery = useQuery({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections', studentId ?? actor, day],
    queryFn: () => trainer ? nutritionApi.trainerFrozenCorrections(studentId!, day) : nutritionApi.frozenCorrections(day),
    enabled: Boolean(actor && validDay && (!trainer || (activeCapability === 'TRAINER' && studentId))), retry: false });
  useEffect(() => {
    if (trainer && correctionHistoryQuery.error instanceof ApiError
        && [403, 404].includes(correctionHistoryQuery.error.status))
      client.removeQueries({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections', studentId] });
  }, [actor, client, correctionHistoryQuery.error, studentId, trainer]);

  async function refresh() {
    await client.invalidateQueries({ queryKey: ownerKey });
    if (!trainer) await client.invalidateQueries({ queryKey: ['nutrition-strategy', actor, 'day'] });
    await client.invalidateQueries({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections'] });
  }
  async function send(command: Command) {
    if (submitting.current) return;
    submitting.current = true; setBusy(true); setPending(command); setMessage('');
    try {
      if (command.kind === 'change') await nutritionApi.changeStrategy(command.body);
      if (command.kind === 'propose') await nutritionApi.proposeStrategy(command.studentId, command.body);
      if (command.kind === 'transition') await nutritionApi.transitionGoal(command.goalId, command.body);
      if (command.kind === 'decision') await nutritionApi.decideStrategy(command.proposalId, command.body);
      if (command.kind === 'dayType') await nutritionApi.chooseDayType(command.day, command.body);
      if (command.kind === 'override') await nutritionApi.overrideTarget(command.day, command.body);
      if (command.kind === 'frozenCorrection') await nutritionApi.correctFrozenTarget(command.day, command.body);
      setPending(null); setPreviewChange(null); setDecision(null); setTransition(null); setConfirmCorrection(false);
      setMessage(trainer ? 'Đã gửi đề xuất. Học viên sẽ quyết định toàn bộ thay đổi.' : 'Đã lưu. Mục tiêu và nhật ký thực tế vẫn là hai dữ liệu riêng.');
      await refresh();
    } catch (error) {
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        setPending(null); setPreviewChange(null); setDecision(null);
        client.removeQueries({ queryKey: trainer ? ['nutrition-strategy', actor, 'trainer', studentId] : ['nutrition-strategy', actor] });
        client.removeQueries({ queryKey: ['nutrition-strategy', actor, 'frozen-corrections', studentId ?? actor] });
        setMessage('Không còn quyền truy cập dữ liệu này.');
      } else if (error instanceof ApiError && error.status === 409) {
        setPending(null); setPreviewChange(null); setDecision(null); setTransition(null);
        await refresh();
        setMessage('Dữ liệu đã thay đổi. Hãy xem lại bản mới rồi xác nhận lại; ứng dụng sẽ không tự gửi lại lệnh cũ.');
      } else if (error instanceof ApiError && error.status === 400) {
        setPending(null); setMessage('Thông tin chưa hợp lệ. Hãy kiểm tra ngày, múi giờ, số lượng và lý do.');
      } else setMessage('Chưa rõ lệnh đã được lưu chưa. Có thể thử lại cùng lệnh, giữ nguyên nội dung và mã lệnh.');
    } finally { submitting.current = false; setBusy(false); }
  }
  function prepareChange() {
    try {
      if (!title.trim() || goalTypeId === null || !moment.tz.zone(timezone) || !moment(effectiveFrom, 'YYYY-MM-DD', true).isValid())
        throw new Error('Chọn loại mục tiêu, tên, múi giờ IANA và ngày hiệu lực hợp lệ.');
      if (trainer && (!canPropose || !reason.trim())) throw new Error('Cần quyền đóng góp hiện hành và lý do đề xuất.');
      if (kind === 'CHANGE_TARGET' && (!goal || !latestVersion || latestVersion.dateSemantics !== 'HALF_OPEN'))
        throw new Error('Không có Goal Version phù hợp để đổi mục tiêu.');
      const body: NutritionChangeBody = { kind, goalId: kind === 'CHANGE_TARGET' ? goal!.id : null,
        expectedGoalVersion: kind === 'CHANGE_TARGET' ? goal!.aggregateVersion : null,
        baseVersionId: kind === 'CHANGE_TARGET' ? latestVersion!.id : null,
        title: title.trim(), goalTypeId, timezone, effectiveFrom,
        targets: buildStrategyTargets(targetValues, nutrientsQuery.data ?? []),
        reason: trainer ? reason.trim() : null, confirmed: !trainer, commandKey: createCommandKey() };
      setPreviewChange(body); setMessage('');
    } catch (error) { setMessage(error instanceof Error ? error.message : 'Không thể tạo bản xem trước.'); }
  }
  function chooseOverride() {
    const nutrient = nutrientsQuery.data?.find((item) => item.id === overrideNutrientId);
    const data = dayQuery.data;
    const raw = overrideAmount.trim();
    if (!data?.goalVersionId || data.frozen || !nutrient || !/^\d+(\.\d{1,6})?$/.test(raw) || !overrideReason.trim()) {
      setMessage('Cần chọn nutrient, nhập lượng hợp lệ và lý do; ngày đã chốt không thể sửa.'); return;
    }
    const existing = data.targets.find((target) => target.nutrientId === nutrient.id && target.source === 'OVERRIDE');
    send({ kind: 'override', day, body: { timezone, goalVersionId: data.goalVersionId, nutrientId: nutrient.id,
      unitId: nutrient.defaultUnitId, targetAmount: Number(raw), reason: overrideReason.trim(),
      expectedOverrideVersion: existing?.overrideVersion ?? null, commandKey: createCommandKey() } });
  }
  function correctFrozenTarget() {
    const target = dayQuery.data?.targets.find((item) => item.nutrientId === correctionNutrientId);
    const amount = correctionAmount.trim();
    if (!dayQuery.data?.frozen || !target || target.correctionVersion === null
        || !/^\d+(\.\d{1,6})?$/.test(amount) || !correctionReason.trim()) {
      setMessage('Chọn nutrient của ngày đã chốt, nhập số hợp lệ và lý do sửa bắt buộc.'); return;
    }
    send({ kind: 'frozenCorrection', day, body: { timezone, nutrientId: target.nutrientId,
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
    {pending && !busy ? <WorkoutButton label="Thử lại cùng lệnh" onPress={() => send(pending)} /> : null}
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{goal?.title ?? 'Chưa có mục tiêu'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{goal ? `${goalStatusLabel[goal.status] ?? goal.status} · Phiên bản tổng ${goal.aggregateVersion}` : 'Chưa có mục tiêu đang xem.'}</Text>
      {goal?.versions.map((version) => <View key={version.id} style={{ marginTop: 8 }}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>Bản {version.versionNumber}: {version.effectiveFrom} → {version.effectiveUntil ?? 'chưa kết thúc'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{version.dateSemantics === 'LEGACY' ? 'Bản lịch sử; không tự diễn giải theo khoảng mới.' : `Múi giờ ${version.timezone}`}</Text>
        {version.targets.map((target) => <Text key={`${target.nutrientId}:${target.dayType}`} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Nutrient chưa có tên'} · {dayTypeLabel[target.dayType]}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol ?? 'đơn vị chưa rõ'}
        </Text>)}
      </View>)}
    </WorkoutCard>
    {!trainer && goal && ['ACTIVE', 'PAUSED'].includes(goal.status) ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Vòng đời mục tiêu</Text>
      {input('Lý do thay đổi trạng thái', reason, setReason)}
      {!transition ? <View style={styles.row}>
        {goal.status === 'ACTIVE' ? <WorkoutButton label="Tạm dừng" secondary onPress={() => setTransition('PAUSE')} /> : <WorkoutButton label="Tiếp tục" onPress={() => setTransition('RESUME')} />}
        <WorkoutButton label="Kết thúc" secondary onPress={() => setTransition('END')} />
      </View> : <><Text style={[styles.body, { color: theme.textPrimary }]}>Xác nhận {transition === 'PAUSE' ? 'tạm dừng' : transition === 'RESUME' ? 'tiếp tục' : 'kết thúc'}? Mục tiêu ngày đã chốt không đổi.</Text>
        <WorkoutButton label="Xác nhận trạng thái" disabled={busy || !reason.trim()} onPress={() => send({ kind: 'transition', goalId: goal.id,
          body: { action: transition, expectedGoalVersion: goal.aggregateVersion, reason: reason.trim(), commandKey: createCommandKey() } })} />
        <WorkoutButton label="Quay lại" secondary onPress={() => setTransition(null)} /></>}
    </WorkoutCard> : null}
    {trainer && !canPropose ? <WorkoutState title="Chỉ xem" message="Cần Coaching Period và quyền NUTRITION_GOAL Đóng góp hiện hành để đề xuất." /> : null}
    {(!trainer || canPropose) && <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{trainer ? 'Đề xuất thay đổi' : 'Xác nhận chiến lược'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>{trainer ? 'Chỉ học viên có thể chấp nhận; đề xuất không đổi target ngay.' : 'Thay đổi này tạo Target Version mới, không sửa Food Log.'}</Text>
      <View style={styles.row}>
        <WorkoutButton label="Goal mới" secondary={kind !== 'NEW_GOAL'} onPress={() => { setKind('NEW_GOAL'); setPreviewChange(null); }} />
        <WorkoutButton label="Đổi target" secondary={kind !== 'CHANGE_TARGET'} disabled={!goal || !latestVersion || latestVersion.dateSemantics !== 'HALF_OPEN'}
          onPress={() => { setKind('CHANGE_TARGET'); setPreviewChange(null); }} />
      </View>
      {input('Tên mục tiêu', title, (value) => { setTitle(value); setPreviewChange(null); })}
      <Text style={[styles.label, { color: theme.textPrimary }]}>Loại Goal từ catalog</Text>
      {typesQuery.isPending || nutrientsQuery.isPending ? <WorkoutState title="Đang tải catalog" message="Vui lòng chờ." busy /> : null}
      {typesQuery.isError || nutrientsQuery.isError ? <WorkoutState title="Không tải được catalog" message="Không thể chọn ID khi catalog chưa có." onRetry={() => { typesQuery.refetch(); nutrientsQuery.refetch(); }} /> : null}
      {typesQuery.data?.length === 0 ? <WorkoutState title="Catalog trống" message="Chưa có loại Goal hợp lệ; không thể gửi dữ liệu tự đoán." /> : null}
      {typesQuery.data?.map((type) => <Pressable key={type.id} accessibilityRole="radio" accessibilityLabel={`Chọn loại mục tiêu ${type.name}`} accessibilityState={{ checked: goalTypeId === type.id }}
        onPress={() => { setGoalTypeId(type.id); setPreviewChange(null); }} style={{ minHeight: 44, justifyContent: 'center' }}>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{goalTypeId === type.id ? '◉' : '○'} {type.name}</Text></Pressable>)}
      {input('Múi giờ IANA của lịch target', timezone, (value) => { setTimezone(value); setDayConfirmed(false); setPreviewChange(null); })}
      {input('Có hiệu lực từ ngày (YYYY-MM-DD)', effectiveFrom, (value) => { setEffectiveFrom(value); setPreviewChange(null); })}
      <Text style={[styles.body, { color: theme.textSecondary }]}>Ngày đầu chỉ dùng khi chưa chốt target; thay đổi sau phải từ ngày kế tiếp hoặc tương lai. Backend kiểm tra mốc thời gian.</Text>
      {nutrientsQuery.data?.map((nutrient) => <View key={nutrient.id}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{nutrient.name} ({nutrient.unitSymbol})</Text>
        {dayTypes.map((dayType) => <React.Fragment key={dayType}>{input(`${nutrient.name} · ${dayTypeLabel[dayType]} (${nutrient.unitSymbol})`,
          targetValues[`${nutrient.id}:${dayType}`] ?? '', (value) => { setTargetValues((old) => ({ ...old, [`${nutrient.id}:${dayType}`]: value })); setPreviewChange(null); }, true)}</React.Fragment>)}
      </View>)}
      {trainer ? input('Lý do đề xuất', reason, (value) => { setReason(value); setPreviewChange(null); }) : null}
      <WorkoutButton label="Xem trước toàn bộ mục tiêu" disabled={busy || !typesQuery.data?.length || !nutrientsQuery.data?.length} onPress={prepareChange} />
      {previewChange ? <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Xem trước trước khi gửi</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{previewChange.kind === 'NEW_GOAL' ? 'Goal mới' : 'Đổi target'} · {previewChange.title} · {previewChange.effectiveFrom} · {previewChange.timezone}</Text>
        {previewChange.targets.map((target) => <Text key={`${target.nutrientId}:${target.dayType}`} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name} · {dayTypeLabel[target.dayType]}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol}
        </Text>)}
        <WorkoutButton label={trainer ? 'Gửi đề xuất cho học viên' : 'Xác nhận và áp dụng'} disabled={busy}
          onPress={() => send(trainer ? { kind: 'propose', studentId: studentId!, body: previewChange } : { kind: 'change', body: previewChange })} />
        <WorkoutButton label="Chỉnh lại" secondary onPress={() => setPreviewChange(null)} />
      </WorkoutCard> : null}
    </WorkoutCard>}
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{trainer ? 'Đề xuất đã gửi' : 'Đề xuất từ huấn luyện viên'}</Text>
      {stateQuery.data.proposals.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có đề xuất.</Text> : null}
      {stateQuery.data.proposals.map((proposal) => <WorkoutCard key={proposal.id}>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{proposal.title} · {proposalLabel[proposal.status]}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{proposal.kind === 'NEW_GOAL' ? 'Goal mới' : 'Đổi target'} · {proposal.effectiveFrom} · {proposal.timezone}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Lý do: {proposal.reason}</Text>
        {proposal.targets.map((target) => <Text key={`${target.nutrientId}:${target.dayType}`} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Nutrient chưa có tên'} · {dayTypeLabel[target.dayType]}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol ?? 'đơn vị chưa rõ'}
        </Text>)}
        {!trainer && proposal.status === 'PENDING' ? <View style={styles.row}>
          <WorkoutButton label="Xem để chấp nhận" onPress={() => setDecision({ proposal, action: 'ACCEPT' })} />
          <WorkoutButton label="Từ chối" secondary onPress={() => setDecision({ proposal, action: 'REJECT' })} />
        </View> : null}
      </WorkoutCard>)}
      {decision ? <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{decision.action === 'ACCEPT' ? 'Xác nhận áp dụng toàn bộ đề xuất?' : 'Xác nhận từ chối?'}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{decision.proposal.title} · {decision.proposal.effectiveFrom} · {decision.proposal.timezone}. Backend sẽ kiểm tra lại quyền và phiên bản.</Text>
        <WorkoutButton label="Xác nhận quyết định" disabled={busy} onPress={() => send({ kind: 'decision', proposalId: decision.proposal.id,
          body: { decision: decision.action, expectedProposalVersion: decision.proposal.proposalVersion, commandKey: createCommandKey() } })} />
        <WorkoutButton label="Quay lại" secondary onPress={() => setDecision(null)} />
      </WorkoutCard> : null}
    </WorkoutCard>
    {!trainer ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Mục tiêu cho một ngày</Text>
      {input('Ngày (YYYY-MM-DD)', day, (value) => { setDay(value); setDayConfirmed(false); })}
      <Text style={[styles.body, { color: theme.textSecondary }]}>Múi giờ target: {timezone || 'chưa chọn'}. GET chỉ xem trước; lần ghi đầu hoặc xác nhận ngày sẽ chốt snapshot.</Text>
      <WorkoutButton label="Xem ngày theo múi giờ đã chọn" disabled={!moment(day, 'YYYY-MM-DD', true).isValid() || !moment.tz.zone(timezone)}
        onPress={() => setDayConfirmed(true)} />
      {validDay && dayQuery.isPending ? <WorkoutState title="Đang tải mục tiêu ngày" message="Vui lòng chờ." busy /> : null}
      {validDay && dayQuery.isError ? <WorkoutState title="Không thể xem ngày" message="Múi giờ hoặc bản lịch sử không còn phù hợp." onRetry={() => dayQuery.refetch()} /> : null}
      {dayQuery.data ? <>
        <Text style={[styles.label, { color: theme.textPrimary }]}>{dayTypeLabel[dayQuery.data.dayType]} · {dayQuery.data.frozen ? 'Đã chốt' : 'Xem trước, chưa chốt'}</Text>
        {dayQuery.data.targets.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Ngày này không có target. Điều đó không nói gì về lượng đã ăn.</Text> : null}
        {dayQuery.data.targets.map((target) => <Text key={target.nutrientId} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Nutrient chưa có tên'}: {target.targetAmount} {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.unitSymbol ?? 'đơn vị chưa rõ'} · {target.source === 'OVERRIDE' ? 'Ghi đè' : target.source === 'DAY_TYPE' ? 'Theo loại ngày' : 'Mặc định'}
        </Text>)}
        {dayQuery.data.frozen ? <>
          <Text style={[styles.body, { color: theme.textSecondary }]}>So sánh tiến độ dùng target sau correction. Target gốc và lượng ăn thực tế không bị ghi đè.</Text>
          {dayQuery.data.targets.map((target) => <Text key={`original-${target.nutrientId}`} style={[styles.body, { color: theme.textSecondary }]}>
            {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'Nutrient'}: gốc {target.originalTargetAmount ?? 'chưa rõ'} → hiện tại {target.targetAmount} · phiên bản sửa {target.correctionVersion ?? 0}
          </Text>)}
          {dayQuery.data.targets.map((target) => <Pressable key={`correct-${target.nutrientId}`} accessibilityRole="radio"
            accessibilityLabel={`Sửa target nutrient ${target.nutrientId}`}
            accessibilityState={{ checked: correctionNutrientId === target.nutrientId }}
            onPress={() => { setCorrectionNutrientId(target.nutrientId); setConfirmCorrection(false); }}
            style={{ minHeight: 44, justifyContent: 'center' }}>
            <Text style={[styles.body, { color: theme.textPrimary }]}>{correctionNutrientId === target.nutrientId ? '◉' : '○'} Sửa {nutrientsQuery.data?.find((item) => item.id === target.nutrientId)?.name ?? 'nutrient'}</Text>
          </Pressable>)}
          {input('Target sau correction', correctionAmount, (value) => { setCorrectionAmount(value); setConfirmCorrection(false); }, true)}
          {input('Lý do correction', correctionReason, (value) => { setCorrectionReason(value); setConfirmCorrection(false); })}
          {!confirmCorrection ? <WorkoutButton label="Xem lại correction target đã chốt" disabled={busy}
            onPress={() => setConfirmCorrection(true)} /> : <>
            <Text style={[styles.body, { color: theme.textPrimary }]}>Chỉ sửa nutrient đã chọn của ngày {day}; target gốc và Food Log actual được giữ nguyên. Lý do: {correctionReason || 'chưa nhập'}.</Text>
            <WorkoutButton label="Xác nhận correction" disabled={busy} onPress={correctFrozenTarget} />
            <WorkoutButton label="Quay lại" secondary onPress={() => setConfirmCorrection(false)} />
          </>}
        </> : null}
        {!dayQuery.data.frozen && dayQuery.data.goalVersionId ? <>
          <View style={styles.row}>
            <WorkoutButton label="Chọn ngày nghỉ" secondary onPress={() => send({ kind: 'dayType', day, body: { timezone, dayType: 'REST_DAY', commandKey: createCommandKey() } })} />
            <WorkoutButton label="Bỏ chọn ngày nghỉ" secondary onPress={() => send({ kind: 'dayType', day, body: { timezone, dayType: 'DEFAULT', commandKey: createCommandKey() } })} />
          </View>
          <Text style={[styles.label, { color: theme.textPrimary }]}>Ghi đè target ngày này</Text>
          {nutrientsQuery.data?.map((nutrient) => <Pressable key={nutrient.id} accessibilityRole="radio"
            accessibilityState={{ checked: overrideNutrientId === nutrient.id }} onPress={() => setOverrideNutrientId(nutrient.id)}
            style={{ minHeight: 44, justifyContent: 'center' }}><Text style={[styles.body, { color: theme.textPrimary }]}>
              {overrideNutrientId === nutrient.id ? '◉' : '○'} {nutrient.name} ({nutrient.unitSymbol})</Text></Pressable>)}
          {input('Lượng ghi đè', overrideAmount, setOverrideAmount, true)}
          {input('Lý do ghi đè', overrideReason, setOverrideReason)}
          <WorkoutButton label="Lưu ghi đè theo phiên bản Goal này" disabled={busy} onPress={chooseOverride} />
        </> : null}
      </> : null}
    </WorkoutCard> : null}
    {trainer ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Lịch sử correction target đã chốt</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ đọc khi còn quyền NUTRITION_GOAL VIEW hiện hành; không có nút sửa.</Text>
      {input('Ngày (YYYY-MM-DD)', day, (value) => { setDay(value); setDayConfirmed(false); })}
      {input('Múi giờ IANA của target', timezone, (value) => { setTimezone(value); setDayConfirmed(false); })}
      <WorkoutButton label="Xem lịch sử ngày này" secondary
        disabled={!moment(day, 'YYYY-MM-DD', true).isValid() || !moment.tz.zone(timezone)}
        onPress={() => setDayConfirmed(true)} />
    </WorkoutCard> : null}
    {validDay ? <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Lịch sử sửa target</Text>
      {correctionHistoryQuery.isPending ? <WorkoutState title="Đang tải lịch sử" message="Vui lòng chờ." busy /> : null}
      {correctionHistoryQuery.isError ? <WorkoutState title="Không xem được lịch sử" message="Quyền hoặc dữ liệu ngày này không còn khả dụng." onRetry={() => correctionHistoryQuery.refetch()} /> : null}
      {correctionHistoryQuery.data?.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Chưa có correction cho ngày này.</Text> : null}
      {correctionHistoryQuery.data?.map((entry) => <Text key={entry.id} style={[styles.body, { color: theme.textPrimary }]}>
        {nutrientsQuery.data?.find((item) => item.id === entry.nutrientId)?.name ?? 'Nutrient'} · gốc {entry.originalAmount} · trước {entry.beforeAmount} → sau {entry.afterAmount} · {entry.reason} · người sửa {entry.actorId} · {entry.correctedAt}
      </Text>)}
    </WorkoutCard> : null}
  </ScrollView>;
}
