import React, { useLayoutEffect, useRef, useState } from 'react';
import { useLocalSearchParams, useRouter } from 'expo-router';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { DateInputField } from '@/components/ui/DateInputField';
import { MealTypeSelector } from '@/components/ui/MealTypeSelector';
import { TimezoneContextPicker } from '@/components/ui/TimezoneContextPicker';
import { getTodayDateOnly } from '@/features/shared/dateInputUtils';
import {
  DEFAULT_APP_TIMEZONE,
  isSupportedTimezone,
  resolveExistingRecordTimezone,
  resolveNewRecordDefaultTimezone,
  resolveStrategyGoalTimezone,
  type StrategyGoalTimezoneLike,
} from '@/features/shared/timezoneUtils';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { nutritionApi, type CatalogItemBody, type CorrectionBody, type FoodBody, type ItemBody, type NutritionDay, type NutritionItem, type PrivateFood } from './nutritionApi';

type Pending = { kind: 'food'; body: FoodBody } | { kind: 'foodUpdate'; id: string; body: FoodBody } |
  { kind: 'item'; day: string; body: ItemBody } |
  { kind: 'catalogItem'; day: string; body: CatalogItemBody } |
  { kind: 'correction'; id: string; body: CorrectionBody } |
  { kind: 'confirm'; day: string; body: { timezone: string; expectedDayVersion: number; noIntakeConfirmed: boolean; commandKey: string } };

type ConfirmedDayScope = { actor: string; day: string; timezone: string };
/** Immutable scope captured by the handlers of one render; a retained callback keeps its own copy. */
type NutritionOpScope = { session: symbol; actor: string; capability: string | null;
  confirmedScope: ConfirmedDayScope | null; day: string; timezone: string };

export const nutritionKeys = {
  day: (actor: string, day: string, timezone: string) => ['nutrition', actor, 'day', day, timezone] as const,
  foods: (actor: string) => ['nutrition', actor, 'foods'] as const,
};

export function nutrientText(total: number | null, subtotal: number | null): string {
  if (total !== null) return String(total);
  return subtotal === null ? 'Chưa xác định' : `Chưa xác định (phần đã biết: ${subtotal})`;
}

export function nutrientLabel(code: string): string {
  return ({ B07_ENERGY_KCAL: 'Năng lượng (kcal)', B07_PROTEIN_G: 'Protein (g)',
    B07_CARBOHYDRATE_G: 'Carb (g)', B07_FAT_G: 'Chất béo (g)' } as Record<string, string>)[code] ?? code;
}

export function NutritionScreen() {
  const router = useRouter();
  const params = useLocalSearchParams<{ day?: string; timezone?: string }>();
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const client = useQueryClient();
  const paramDay = params.day && /^\d{4}-\d{2}-\d{2}$/.test(params.day) ? params.day : '';
  const paramTimezone = params.timezone && isSupportedTimezone(params.timezone) ? params.timezone : null;
  const sessionKey = `${actor}:${activeCapability ?? ''}`;
  const [trackedSession, setTrackedSession] = useState(sessionKey);
  const [session, setSession] = useState<symbol>(() => Symbol('nutrition-session'));
  const [manualTimezone, setManualTimezone] = useState<string | null>(null);
  const [tzExpanded, setTzExpanded] = useState(false);
  const [day, setDay] = useState(() => paramDay);
  const [confirmedScope, setConfirmedScope] = useState<ConfirmedDayScope | null>(null);
  const [name, setName] = useState('');
  const [servingLabel, setServingLabel] = useState('');
  const [kcal, setKcal] = useState('');
  const [protein, setProtein] = useState('');
  const [carbs, setCarbs] = useState('');
  const [fat, setFat] = useState('');
  const [foodId, setFoodId] = useState('');
  const [catalogFoodId, setCatalogFoodId] = useState('');
  const [catalogPortionId, setCatalogPortionId] = useState('');
  const [catalogBasis, setCatalogBasis] = useState<CatalogItemBody['quantityBasis']>('GRAM');
  const [catalogQuantity, setCatalogQuantity] = useState('');
  const [editingFood, setEditingFood] = useState<PrivateFood | null>(null);
  const [description, setDescription] = useState('');
  const [servings, setServings] = useState('1');
  const [mealType, setMealType] = useState<ItemBody['mealType']>('OTHER');
  const [localTime, setLocalTime] = useState(() => moment.utc().tz(paramTimezone ?? resolveNewRecordDefaultTimezone()).format('HH:mm'));
  const [chosenOffset, setChosenOffset] = useState('');
  const [editing, setEditing] = useState<NutritionItem | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [confirmDay, setConfirmDay] = useState(false);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const submitting = useRef(false);
  const activeOpRef = useRef<symbol | null>(null);
  const mountedRef = useRef(true);
  const liveScopeRef = useRef<(NutritionOpScope & { ready: boolean }) | null>(null);
  if (trackedSession !== sessionKey) {
    // New actor/capability session: fresh identity, no inherited context, lock or operation.
    setTrackedSession(sessionKey);
    setSession(Symbol('nutrition-session'));
    setBusy(false);
    const resetTz = paramTimezone ?? resolveNewRecordDefaultTimezone();
    setManualTimezone(null);
    setTzExpanded(false);
    setDay(paramDay);
    setConfirmedScope(null);
    setEditing(null);
    setConfirmRemove(false);
    setConfirmDay(false);
    setPending(null);
    setMessage('');
    setChosenOffset('');
    setLocalTime(moment.utc().tz(resetTz).format('HH:mm'));
  }
  const strategyEnabled = Boolean(actor && activeCapability === 'STUDENT' && typeof nutritionApi.strategy === 'function');
  const strategyQuery = useQuery({
    queryKey: ['nutrition', actor, 'strategy-context'],
    queryFn: () => nutritionApi.strategy(),
    enabled: strategyEnabled,
    retry: false,
  });
  const strategyGoal = strategyQuery.data && typeof strategyQuery.data === 'object' && 'goal' in strategyQuery.data
    ? (strategyQuery.data as { goal?: StrategyGoalTimezoneLike | null }).goal
    : null;
  const strategyLookupDay = day || getTodayDateOnly(DEFAULT_APP_TIMEZONE);
  const strategyTimezone = resolveStrategyGoalTimezone(strategyGoal, strategyLookupDay);
  const strategyResolved = !strategyEnabled || !strategyQuery.isPending || Boolean(manualTimezone || paramTimezone);
  // New log intents default to Vietnam time unless explicit route or covering strategy context applies.
  const timezone = manualTimezone ?? paramTimezone ?? strategyTimezone ?? resolveNewRecordDefaultTimezone();
  const validDayInput = Boolean(actor && activeCapability === 'STUDENT' && day && /^\d{4}-\d{2}-\d{2}$/.test(day) && isSupportedTimezone(timezone));
  const validContext = Boolean(
    validDayInput &&
    strategyResolved &&
    confirmedScope?.actor === actor &&
    confirmedScope?.day === day &&
    confirmedScope?.timezone === timezone,
  );
  const dayQuery = useQuery({ queryKey: nutritionKeys.day(actor, day, timezone), queryFn: () => nutritionApi.day(day, timezone), enabled: validContext });
  const dayDataMatchesScope = Boolean(
    dayQuery.data &&
    (!dayQuery.data.studentId || dayQuery.data.studentId === actor) &&
    dayQuery.data.date === day &&
    dayQuery.data.timezone === timezone,
  );
  const activeDayTimezone = dayDataMatchesScope && dayQuery.data?.timezone
    ? resolveExistingRecordTimezone(dayQuery.data.timezone, timezone)
    : timezone;
  const dayTimezoneConflict = validContext && dayQuery.isError && dayQuery.error instanceof ApiError && dayQuery.error.status === 409;
  const resolvedDayReady = Boolean(
    validContext &&
    !dayQuery.isPending &&
    !dayQuery.isFetching &&
    !dayQuery.isError &&
    !dayTimezoneConflict &&
    dayDataMatchesScope,
  );
  const foodQuery = useQuery({ queryKey: nutritionKeys.foods(actor), queryFn: nutritionApi.foods, enabled: Boolean(actor && activeCapability === 'STUDENT') });
  const catalogQuery = useQuery({ queryKey: ['nutrition-catalog', 'published'], queryFn: nutritionApi.catalogFoods,
    enabled: Boolean(actor && activeCapability === 'STUDENT') });
  const chosenCatalog = catalogQuery.data?.find((food) => food.id === catalogFoodId);
  const historyQuery = useQuery({ queryKey: ['nutrition', actor, 'corrections', editing?.id],
    queryFn: () => nutritionApi.corrections(editing!.id), enabled: Boolean(actor && editing) });

  const opScope: NutritionOpScope = { session, actor, capability: activeCapability, confirmedScope, day, timezone: activeDayTimezone };
  useLayoutEffect(() => {
    liveScopeRef.current = { ...opScope, ready: resolvedDayReady };
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
  // Guard A (current scope): the callback was created for the live session/actor/capability and, for
  // day-scoped work, for the live confirmation identity (null included), day and timezone. Every handler
  // checks it first, before validation, messages, operation claims or loading changes.
  const isScopeLive = (scope: NutritionOpScope, dayScoped: boolean) => {
    const live = liveScopeRef.current;
    return Boolean(mountedRef.current && live && live.session === scope.session && live.actor === scope.actor
      && live.capability === scope.capability
      && (!dayScoped || (live.confirmedScope === scope.confirmedScope
        && live.day === scope.day && live.timezone === scope.timezone)));
  };
  // Guard B (dispatch allowed): current scope plus a confirmed day context that is ready right now.
  const canDispatch = (scope: NutritionOpScope, dayScoped: boolean) => isScopeLive(scope, dayScoped)
    && (!dayScoped || (scope.confirmedScope !== null && Boolean(liveScopeRef.current?.ready)));
  // Runs a UI action only if the render scope that created the callback is still live, so a copy
  // retained from an older scope cannot change the new UI. Called from event handlers, never in render.
  const runInScope = (scope: typeof opScope, dayScoped: boolean, action: () => void) => {
    if (isScopeLive(scope, dayScoped)) action();
  };

  const refresh = async () => {
    await Promise.all([client.invalidateQueries({ queryKey: nutritionKeys.day(actor, day, timezone) }),
      client.invalidateQueries({ queryKey: nutritionKeys.foods(actor) })]);
  };
  const send = async (command: Pending) => {
    const scope = opScope;
    const isDayCommand = command.kind === 'item' || command.kind === 'catalogItem' || command.kind === 'confirm' || command.kind === 'correction';
    // Guard A: a callback retained from an earlier scope (or after unmount) is inert and silent.
    if (!isScopeLive(scope, isDayCommand)) return;
    const commandMatchesScope = !('day' in command) || command.day === scope.day;
    if (isDayCommand && (!canDispatch(scope, true) || !commandMatchesScope)) {
      setMessage('Ngữ cảnh ngày và khung giờ chưa tải xong hoặc đang xung đột; không thể ghi.');
      return;
    }
    if (submitting.current) return;
    const token = Symbol('nutrition-op');
    activeOpRef.current = token;
    submitting.current = true;
    setBusy(true);
    setMessage('');
    setPending(command);
    // Guard C (continuation): this operation still owns its outcome in a live scope. Readiness is not
    // required, so the result of a request that was really sent is not dropped by a same-scope refetch.
    const owns = () => activeOpRef.current === token && isScopeLive(scope, isDayCommand);
    // Guard B as the request runner's shouldContinue: checked before the token read, before fetch and
    // before a 401 retry, so readiness lost during those awaits (for example a same-scope 409) stops the send.
    const guard = { shouldContinue: () => activeOpRef.current === token && canDispatch(scope, isDayCommand) };
    try {
      if (command.kind === 'food') await nutritionApi.createFood(command.body, guard);
      if (command.kind === 'foodUpdate') await nutritionApi.updateFood(command.id, command.body, guard);
      if (command.kind === 'item') await nutritionApi.addItem(command.day, command.body, guard);
      if (command.kind === 'catalogItem') await nutritionApi.addCatalogItem(command.day, command.body, guard);
      if (command.kind === 'correction') await nutritionApi.correct(command.id, command.body, guard);
      if (command.kind === 'confirm') await nutritionApi.confirm(command.day, command.body, guard);
      // A stale continuation cannot claim or report the outcome in the new scope.
      if (!owns()) return;
      setPending(null);
      setEditing(null);
      setEditingFood(null);
      setConfirmRemove(false);
      setConfirmDay(false);
      setMessage('Đã lưu. Dữ liệu trong ngày đã được cập nhật.');
      await refresh();
      if (command.kind === 'catalogItem') await client.invalidateQueries({ queryKey: ['nutrition-catalog'] });
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
        setPending(null);
        client.removeQueries({ queryKey: ['nutrition', actor] });
        setMessage('Bạn không còn quyền xem hoặc sửa dữ liệu này.');
      } else if (error instanceof ApiError && error.status === 409) {
        setPending(null);
        await refresh();
        if (!owns()) return;
        setMessage('Dữ liệu đã thay đổi. Hãy xem lại rồi xác nhận thao tác mới.');
      } else if (error instanceof ApiError && error.status === 400) {
        setPending(null);
        setMessage('Thông tin chưa hợp lệ. Hãy kiểm tra ngày, múi giờ và các giá trị nhập.');
      } else {
        setMessage('Chưa rõ lệnh đã được lưu hay chưa. Bạn có thể thử lại đúng lệnh này.');
      }
    } finally {
      // Only the operation that still holds the lock may release it.
      if (activeOpRef.current === token) {
        activeOpRef.current = null;
        submitting.current = false;
        setBusy(false);
      }
    }
  };
  const numberOrNull = (value: string) => value.trim() ? Number(value) : null;
  const validNonNegative = (value: string) => !value.trim() || (Number.isFinite(Number(value)) && Number(value) >= 0);
  const createFood = () => {
    if (!isScopeLive(opScope, false)) return;
    if (!name.trim() || !servingLabel.trim() || ![kcal, protein, carbs, fat].every(validNonNegative)) {
      setMessage('Nhập tên, khẩu phần và chỉ dùng số không âm cho dinh dưỡng đã biết. Để trống nếu chưa biết.'); return;
    }
    const body: FoodBody = { name: name.trim(), servingLabel: servingLabel.trim(), energyKcal: numberOrNull(kcal),
      proteinG: numberOrNull(protein), carbohydrateG: numberOrNull(carbs), fatG: numberOrNull(fat), commandKey: createCommandKey() };
    send(editingFood ? { kind: 'foodUpdate', id: editingFood.id,
      body: { ...body, expectedVersion: editingFood.version } } : { kind: 'food', body });
  };
  const addItem = () => {
    if (!isScopeLive(opScope, true)) return;
    if (!canDispatch(opScope, true)) { setMessage('Hãy chọn, xác nhận ngày cùng khung giờ và đợi tải xong nhật ký trước khi ghi.'); return; }
    const chosenFood = foodQuery.data?.find((food) => food.id === foodId);
    const count = Number(servings);
    if (foodId && (!chosenFood || chosenFood.quantificationStatus !== 'LOGGABLE' || !Number.isFinite(count) || count <= 0)) {
      setMessage('Chọn món riêng và số khẩu phần lớn hơn 0.'); return;
    }
    if (!foodId && !description.trim()) { setMessage('Mô tả món chưa định lượng không được để trống.'); return; }
    const offsets = validProposalOffsets(activeDayTimezone, `${day}T${localTime}`);
    if (offsets.length === 0) { setMessage('Giờ địa phương không tồn tại hoặc chưa đúng định dạng HH:mm.'); return; }
    const selected = offsets.length === 1 ? offsets[0] : offsets.find((option) => option.offset === chosenOffset);
    if (!selected) { setMessage('Giờ này bị lặp do đổi giờ. Hãy chọn rõ UTC offset.'); return; }
    send({ kind: 'item', day, body: { timezone: activeDayTimezone, consumedAt: new Date(selected.instant).toISOString(), mealType,
      description: foodId ? null : description.trim(), privateFoodId: foodId || null,
      servings: foodId ? count : null, commandKey: createCommandKey() } });
  };
  const addCatalogItem = () => {
    if (!isScopeLive(opScope, true)) return;
    if (!canDispatch(opScope, true) || !chosenCatalog) { setMessage('Hãy chọn ngày, khung giờ đã tải thành công và thực phẩm đã công bố.'); return; }
    const quantity = Number(catalogQuantity);
    const portion = chosenCatalog.portions.find((item) => item.id === catalogPortionId);
    const canConvert = catalogBasis === 'GRAM' || (catalogBasis === 'SERVING' && portion?.gramsPerServing != null)
      || (catalogBasis === 'MILLILITRE' && portion?.gramsPerMl != null);
    if (!/^(?:\d+)(?:\.\d{1,6})?$/.test(catalogQuantity) || !Number.isFinite(quantity) || quantity <= 0 || !canConvert) {
      setMessage('Số lượng hoặc bằng chứng quy đổi chưa hợp lệ. Chỉ dùng đơn vị đã được xác nhận cho món này.'); return;
    }
    const offsets = validProposalOffsets(activeDayTimezone, `${day}T${localTime}`);
    const selected = offsets.length === 1 ? offsets[0] : offsets.find((option) => option.offset === chosenOffset);
    if (!selected) { setMessage('Giờ địa phương không tồn tại hoặc bị lặp; hãy chọn UTC offset hợp lệ.'); return; }
    send({ kind: 'catalogItem', day, body: { timezone: activeDayTimezone, consumedAt: new Date(selected.instant).toISOString(),
      mealType, catalogFoodId: chosenCatalog.id, expectedRevisionId: chosenCatalog.revisionId,
      portionId: catalogBasis === 'GRAM' ? null : catalogPortionId,
      quantityBasis: catalogBasis, quantity, commandKey: createCommandKey() } });
  };
  const correct = (remove: boolean) => {
    if (!isScopeLive(opScope, true)) return;
    if (!editing || !canDispatch(opScope, true)) { setMessage('Ngữ cảnh ngày chưa được tải hợp lệ; chưa thể điều chỉnh.'); return; }
    const count = Number(servings);
    if (!remove && foodId && (!foodQuery.data?.some((food) => food.id === foodId && food.quantificationStatus === 'LOGGABLE') || !Number.isFinite(count) || count <= 0)) {
      setMessage('Số khẩu phần phải lớn hơn 0.'); return;
    }
    if (!remove && !foodId && !description.trim()) { setMessage('Hãy nhập mô tả món.'); return; }
    send({ kind: 'correction', id: editing.id, body: { expectedVersion: editing.version,
      description: remove ? null : foodId ? null : description.trim(), privateFoodId: remove ? null : foodId || null,
      servings: remove || !foodId ? null : count, remove, commandKey: createCommandKey() } });
  };
  const confirm = (data: NutritionDay) => {
    if (!isScopeLive(opScope, true)) return;
    if (!canDispatch(opScope, true)) { setMessage('Ngữ cảnh ngày chưa được tải hợp lệ; chưa thể xác nhận hoàn tất.'); return; }
    send({ kind: 'confirm', day, body: { timezone: activeDayTimezone,
      expectedDayVersion: data.version, noIntakeConfirmed: data.items.length === 0, commandKey: createCommandKey() } });
  };
  const input = (label: string, value: string, change: (s: string) => void, keyboardType: 'default' | 'decimal-pad' = 'default') =>
    <View style={{ marginBottom: 10 }}><Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text>
      <TextInput accessibilityLabel={label} value={value} onChangeText={change} keyboardType={keyboardType}
        style={{ borderWidth: 1, borderColor: theme.border, color: theme.textPrimary, minHeight: 48, padding: 10, borderRadius: 8 }} /></View>;

  if (activeCapability !== 'STUDENT') {
    return (
      <SafeAreaView edges={['top', 'bottom']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
        <WorkoutState title="Chỉ dành cho học viên" message="Chuyển sang vai trò học viên để ghi dinh dưỡng." />
      </SafeAreaView>
    );
  }
  return (
    <SafeAreaView edges={['top', 'bottom']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
      <ScrollView testID="nutrition-student-screen" style={styles.screen}
        contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Nhật ký dinh dưỡng</Text>
    <WorkoutButton label="Mục tiêu dinh dưỡng và đề xuất" secondary onPress={() => router.push('/nutrition/strategy' as never)} />
    <WorkoutButton testID="entry-food-text-analysis" label="Ước tính từ mô tả bữa ăn (AI)" secondary onPress={() => router.push({ pathname: '/nutrition/food-analysis', params: resolvedDayReady ? { day, timezone: activeDayTimezone, mealType } : { mealType } } as never)} />
    <Text style={[styles.body, { color: theme.textSecondary }]}>Dữ liệu bạn tự khai báo. Chưa biết không có nghĩa là 0.</Text>
    <WorkoutCard>
      <DateInputField
        testID="nutrition-day-input"
        label="Ngày ghi"
        placeholder="Chọn ngày ghi"
        value={day}
        onChange={(value) => {
          setDay(value);
          setConfirmedScope(null);
          setEditing(null);
          setConfirmRemove(false);
          setConfirmDay(false);
          setPending((prev) => (prev && (prev.kind === 'food' || prev.kind === 'foodUpdate') ? prev : null));
        }}
        initialBrowseDate={getTodayDateOnly(timezone)}
        sessionKey={actor}
        accessibilityLabel="Ngày ghi"
      />
      <TimezoneContextPicker
        testIDPrefix="nutrition-timezone"
        label="Khung giờ áp dụng"
        value={activeDayTimezone}
        expanded={tzExpanded || dayTimezoneConflict}
        onToggleExpanded={() => setTzExpanded((v) => !v)}
        extraTimezones={[dayQuery.data?.timezone, strategyTimezone, paramTimezone]}
        onChange={(nextZone) => {
          setManualTimezone(nextZone);
          setTzExpanded(false);
          setConfirmedScope(null);
          setChosenOffset('');
          setEditing(null);
          setConfirmRemove(false);
          setConfirmDay(false);
          setPending((prev) => (prev && (prev.kind === 'food' || prev.kind === 'foodUpdate') ? prev : null));
        }}
      />
      <WorkoutButton label={validContext ? 'Đã xác nhận ngày và múi giờ' : 'Xác nhận ngày và múi giờ'}
        disabled={!validDayInput || !strategyResolved}
        onPress={() => runInScope(opScope, true, () => setConfirmedScope({ actor, day, timezone }))} />
    </WorkoutCard>
    {message ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.textPrimary }]}>{message}</Text> : null}
    {pending && !busy && (pending.kind === 'food' || pending.kind === 'foodUpdate' || resolvedDayReady) ? <WorkoutButton label="Thử lại cùng lệnh" onPress={() => send(pending)} /> : null}
    {validContext && dayQuery.isPending ? <WorkoutState title="Đang tải nhật ký" message="Vui lòng chờ." busy /> : null}
    {validContext && dayTimezoneConflict ? (
      <WorkoutState
        title="Ngày này dùng khung giờ khác"
        message="Ngày bạn chọn đã được ghi nhận theo một khung giờ khác. Hãy chọn lại đúng khung giờ trong danh sách thân thiện ở trên rồi xác nhận lại; hệ thống không tự đổi ngày hoặc thử hàng loạt."
      />
    ) : null}
    {validContext && dayQuery.isError && !dayTimezoneConflict ? <WorkoutState title="Không tải được nhật ký" message="Kiểm tra kết nối hoặc khung giờ." onRetry={() => dayQuery.refetch()} /> : null}
    {resolvedDayReady && dayQuery.data ? <>
      <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>Ngày {day} · {dayQuery.data.completeness === 'COMPLETE' ? 'Đã xác nhận' : dayQuery.data.completeness === 'PARTIAL' ? 'Đang ghi' : 'Chưa ghi'}</Text>
        {dayQuery.data.noIntakeConfirmed ? <Text style={styles.body}>Đã xác nhận không ăn uống trong ngày.</Text> : null}
        {dayQuery.data.nutrients.map((nutrient) => <Text key={nutrient.code} style={[styles.body, { color: theme.textPrimary }]}>
          {nutrientLabel(nutrient.code)}: {nutrientText(nutrient.total, nutrient.knownSubtotal)}
        </Text>)}
      </WorkoutCard>
      {dayQuery.data.items.length === 0 ? <WorkoutState title="Chưa có món trong ngày" message="Ngày chưa ghi không được tính là 0 kcal." /> : null}
      {dayQuery.data.items.map((item) => <WorkoutCard key={item.id}>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{item.name}</Text>
        {item.kind === 'CATALOG_QUANTIFIED' ? <Text style={[styles.body, { color: theme.textSecondary }]}>
          Nguồn danh mục đã duyệt · {item.servings} {item.quantityBasis === 'GRAM' ? 'g' : item.quantityBasis === 'MILLILITRE' ? 'ml' : item.servingLabel ?? 'khẩu phần'} · bản sửa đổi đã chốt trong lịch sử
        </Text> : null}
        <Text style={[styles.body, { color: theme.textSecondary }]}>{item.kind === 'DESCRIPTION_ONLY' ? 'Chưa định lượng' : `${item.servings ?? '?'} ${item.servingLabel ?? 'khẩu phần'}`}</Text>
        <WorkoutButton label={`Sửa ${item.name}`} secondary onPress={() => runInScope(opScope, true, () => {
          setEditing(item); setConfirmRemove(false); setFoodId(item.privateFoodId ?? ''); setDescription(item.kind === 'DESCRIPTION_ONLY' ? item.name : '');
          setServings(item.servings === null ? '1' : String(item.servings));
        })} />
      </WorkoutCard>)}
      {dayQuery.data.completeness !== 'COMPLETE' ? <>
        {!confirmDay ? <WorkoutButton label="Xác nhận hoàn tất ngày" disabled={busy || !resolvedDayReady} onPress={() => runInScope(opScope, true, () => setConfirmDay(true))} /> :
          <WorkoutCard><Text style={[styles.body, { color: theme.textPrimary }]}>
            {dayQuery.data.items.length === 0 ? 'Xác nhận không ăn uống trong ngày này?' : 'Xác nhận đã ghi xong ngày này?'}
          </Text><WorkoutButton label="Xác nhận" disabled={busy || !resolvedDayReady} onPress={() => confirm(dayQuery.data)} />
            <WorkoutButton label="Quay lại" secondary onPress={() => runInScope(opScope, true, () => setConfirmDay(false))} /></WorkoutCard>}
      </> : null}
    </> : null}
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{editing ? `Sửa ${editing.name}` : 'Ghi một món'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn món riêng hoặc mô tả món chưa định lượng.</Text>
      {foodQuery.data?.map((food) => <Pressable key={food.id} accessibilityRole="radio"
        accessibilityState={{ checked: foodId === food.id, disabled: food.quantificationStatus === 'DRAFT' }}
        disabled={food.quantificationStatus === 'DRAFT'}
        accessibilityLabel={`Chọn ${food.name}, nguồn tự khai báo`} onPress={() => runInScope(opScope, false, () => setFoodId(food.id))} style={{ minHeight: 44, paddingVertical: 10 }}>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{foodId === food.id ? '◉' : '○'} {food.name} · {food.servingLabel}</Text>
        {food.quantificationStatus === 'DRAFT' ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bản nháp: chưa có dữ liệu dinh dưỡng; không thể ghi theo khẩu phần.</Text> : null}
      </Pressable>)}
      {foodQuery.data?.filter((food) => food.quantificationStatus === 'DRAFT').map((food) =>
        <WorkoutButton key={`edit-${food.id}`} label={`Bổ sung dinh dưỡng cho ${food.name}`} secondary onPress={() => runInScope(opScope, false, () => {
          setEditingFood(food); setName(food.name); setServingLabel(food.servingLabel);
          setKcal(food.energyKcal === null ? '' : String(food.energyKcal));
          setProtein(food.proteinG === null ? '' : String(food.proteinG));
          setCarbs(food.carbohydrateG === null ? '' : String(food.carbohydrateG));
          setFat(food.fatG === null ? '' : String(food.fatG));
        })} />)}
      <WorkoutButton label="Món chỉ có mô tả" secondary onPress={() => runInScope(opScope, false, () => setFoodId(''))} />
      {!foodId ? input('Mô tả món', description, setDescription) : input('Số khẩu phần', servings, setServings, 'decimal-pad')}
      {!editing ? <>
        {input('Giờ ăn địa phương (HH:mm)', localTime, (value) => { setLocalTime(value); setChosenOffset(''); })}
        {validContext && validProposalOffsets(activeDayTimezone, `${day}T${localTime}`).length > 1 ?
          <View><Text style={[styles.body, { color: theme.textPrimary }]}>Giờ này xuất hiện hai lần. Chọn UTC offset:</Text>
            {validProposalOffsets(activeDayTimezone, `${day}T${localTime}`).map((option) =>
              <Pressable key={option.offset} accessibilityRole="radio" accessibilityState={{ checked: chosenOffset === option.offset }}
                onPress={() => setChosenOffset(option.offset)} style={{ minHeight: 44 }}>
                <Text style={[styles.body, { color: theme.textPrimary }]}>{chosenOffset === option.offset ? '◉' : '○'} {option.offset}</Text>
              </Pressable>)}</View> : null}
        <MealTypeSelector
          value={mealType}
          onChange={setMealType}
          disabled={busy}
          testID="nutrition-meal-selector"
          testIDPrefix="nutrition-meal"
        />
        <WorkoutButton label="Ghi món" disabled={busy || !resolvedDayReady} onPress={addItem} />
      </> : <>
        <WorkoutButton label="Lưu điều chỉnh" disabled={busy || !resolvedDayReady} onPress={() => correct(false)} />
        {!confirmRemove ? <WorkoutButton label="Xóa món (giữ lịch sử)" secondary disabled={busy || !resolvedDayReady} onPress={() => runInScope(opScope, true, () => setConfirmRemove(true))} /> :
          <><Text style={[styles.body, { color: theme.textPrimary }]}>Xác nhận xóa món khỏi ngày? Lịch sử điều chỉnh vẫn được giữ.</Text>
            <WorkoutButton label="Xác nhận xóa món" disabled={busy || !resolvedDayReady} onPress={() => correct(true)} />
            <WorkoutButton label="Không xóa" secondary onPress={() => runInScope(opScope, true, () => setConfirmRemove(false))} /></>}
        {historyQuery.data?.map((entry) => <Text key={entry.id} style={[styles.body, { color: theme.textSecondary }]}>
          Đã sửa phiên bản {entry.fromVersion} → {entry.toVersion} · {entry.correctedAt}
        </Text>)}
        <WorkoutButton label="Không sửa nữa" secondary onPress={() => runInScope(opScope, true, () => setEditing(null))} />
      </>}
    </WorkoutCard>
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{editingFood ? `Bổ sung dinh dưỡng cho ${editingFood.name}` : 'Tạo món riêng'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Nguồn tự khai báo; để trống chất dinh dưỡng chưa biết, chỉ nhập 0 nếu thực sự bằng 0. Món chưa có chất dinh dưỡng nào là bản nháp, chưa thể ghi theo khẩu phần.</Text>
      {input('Tên món', name, setName)}{input('Tên một khẩu phần', servingLabel, setServingLabel)}
      {input('Kcal mỗi khẩu phần', kcal, setKcal, 'decimal-pad')}{input('Protein (g)', protein, setProtein, 'decimal-pad')}
      {input('Carb (g)', carbs, setCarbs, 'decimal-pad')}{input('Chất béo (g)', fat, setFat, 'decimal-pad')}
      <WorkoutButton label={editingFood ? 'Lưu dinh dưỡng món nháp' : 'Lưu món riêng'} disabled={busy} onPress={createFood} />
      {editingFood ? <WorkoutButton label="Hủy chỉnh món nháp" secondary onPress={() => runInScope(opScope, false, () => setEditingFood(null))} /> : null}
    </WorkoutCard>
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>Thực phẩm trong danh mục đã duyệt</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Tập nhỏ USDA Foundation Foods do quản trị viên công bố. Giá trị thiếu là chưa biết, không phải 0.</Text>
      {catalogQuery.isPending ? <WorkoutState title="Đang tải danh mục" message="Vui lòng chờ." busy /> : null}
      {catalogQuery.isError ? <WorkoutState title="Không tải được danh mục" message="Kiểm tra kết nối rồi thử lại." onRetry={() => catalogQuery.refetch()} /> : null}
      {catalogQuery.data?.length === 0 ? <WorkoutState title="Chưa có thực phẩm được công bố" message="Bạn vẫn có thể ghi món riêng do mình khai báo." /> : null}
      {catalogQuery.data?.map((food) => <Pressable key={food.id} accessibilityRole="radio"
        accessibilityLabel={`Chọn ${food.name}, USDA FDC ${food.fdcId}`}
        accessibilityState={{ checked: catalogFoodId === food.id }} onPress={() => { setCatalogFoodId(food.id); setCatalogPortionId(''); }}
        style={{ minHeight: 44, justifyContent: 'center' }}>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{catalogFoodId === food.id ? '◉' : '○'} {food.name} · USDA FDC {food.fdcId}</Text>
      </Pressable>)}
      {chosenCatalog ? <>
        <Text style={[styles.body, { color: theme.textSecondary }]}>Nguồn: {chosenCatalog.sourceName} · {chosenCatalog.sourceRelease} · bản sửa đổi {chosenCatalog.revisionNumber}</Text>
        {(['GRAM', 'SERVING', 'MILLILITRE'] as const).map((basis) => {
          const allowed = basis === 'GRAM' || chosenCatalog.portions.some((part) => basis === 'SERVING'
            ? part.gramsPerServing != null : part.gramsPerMl != null);
          return <Pressable key={basis} accessibilityRole="radio"
            accessibilityLabel={`Đơn vị ${basis === 'GRAM' ? 'gam' : basis === 'SERVING' ? 'khẩu phần' : 'mililít'}`}
            accessibilityState={{ checked: catalogBasis === basis, disabled: !allowed }} disabled={!allowed}
            onPress={() => { setCatalogBasis(basis); setCatalogPortionId(''); }} style={{ minHeight: 44, justifyContent: 'center' }}>
            <Text style={[styles.body, { color: theme.textPrimary }]}>{catalogBasis === basis ? '◉' : '○'} {basis === 'GRAM' ? 'Gam (g)' : basis === 'SERVING' ? 'Khẩu phần có khối lượng đã duyệt' : 'Mililít (ml) có bằng chứng quy đổi'}{allowed ? '' : ' · chưa có dữ liệu'}</Text>
          </Pressable>;
        })}
        {catalogBasis !== 'GRAM' ? chosenCatalog.portions.filter((part) => catalogBasis === 'SERVING'
          ? part.gramsPerServing != null : part.gramsPerMl != null).map((part) =>
          <Pressable key={part.id} accessibilityRole="radio" accessibilityState={{ checked: catalogPortionId === part.id }}
            accessibilityLabel={`Khẩu phần ${part.label}, nguồn ${catalogBasis === 'SERVING' ? part.factorSource ?? 'chưa rõ' : part.mlFactorSource ?? 'chưa rõ'}`}
            onPress={() => setCatalogPortionId(part.id)} style={{ minHeight: 44, justifyContent: 'center' }}>
            <Text style={[styles.body, { color: theme.textPrimary }]}>{catalogPortionId === part.id ? '◉' : '○'} {part.label} · {catalogBasis === 'SERVING' ? part.factorSource : part.mlFactorSource}</Text>
          </Pressable>) : null}
        {input('Số lượng theo đơn vị đã chọn', catalogQuantity, setCatalogQuantity, 'decimal-pad')}
        <WorkoutButton label="Ghi thực phẩm trong danh mục" disabled={busy || !resolvedDayReady} onPress={addCatalogItem} />
      </> : null}
    </WorkoutCard>
      </ScrollView>
    </SafeAreaView>
  );
}
