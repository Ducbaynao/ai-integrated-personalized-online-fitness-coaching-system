import React, { useRef, useState } from 'react';
import { useRouter } from 'expo-router';
import { Pressable, ScrollView, Text, TextInput, View, useColorScheme } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { validProposalOffsets } from '@/features/workout-schedule-proposals/proposalTime';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { nutritionApi, type CorrectionBody, type FoodBody, type ItemBody, type NutritionDay, type NutritionItem, type PrivateFood } from './nutritionApi';

type Pending = { kind: 'food'; body: FoodBody } | { kind: 'foodUpdate'; id: string; body: FoodBody } |
  { kind: 'item'; day: string; body: ItemBody } |
  { kind: 'correction'; id: string; body: CorrectionBody } |
  { kind: 'confirm'; day: string; body: { timezone: string; expectedDayVersion: number; noIntakeConfirmed: boolean; commandKey: string } };

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
  const { user, activeCapability } = useAuth();
  const actor = user?.id ?? '';
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const client = useQueryClient();
  const [timezone, setTimezone] = useState(user?.timezone ?? '');
  const [day, setDay] = useState('');
  const [confirmedContext, setConfirmedContext] = useState(false);
  const [name, setName] = useState('');
  const [servingLabel, setServingLabel] = useState('');
  const [kcal, setKcal] = useState('');
  const [protein, setProtein] = useState('');
  const [carbs, setCarbs] = useState('');
  const [fat, setFat] = useState('');
  const [foodId, setFoodId] = useState('');
  const [editingFood, setEditingFood] = useState<PrivateFood | null>(null);
  const [description, setDescription] = useState('');
  const [servings, setServings] = useState('1');
  const [mealType, setMealType] = useState<ItemBody['mealType']>('OTHER');
  const [localTime, setLocalTime] = useState(() => moment.utc().tz(user?.timezone || 'Etc/UTC').format('HH:mm'));
  const [chosenOffset, setChosenOffset] = useState('');
  const [editing, setEditing] = useState<NutritionItem | null>(null);
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [confirmDay, setConfirmDay] = useState(false);
  const [message, setMessage] = useState('');
  const [busy, setBusy] = useState(false);
  const [pending, setPending] = useState<Pending | null>(null);
  const submitting = useRef(false);
  const validContext = Boolean(actor && activeCapability === 'STUDENT' && day && /^\d{4}-\d{2}-\d{2}$/.test(day)
    && moment.tz.zone(timezone) && confirmedContext);
  const dayQuery = useQuery({ queryKey: nutritionKeys.day(actor, day, timezone), queryFn: () => nutritionApi.day(day, timezone), enabled: validContext });
  const foodQuery = useQuery({ queryKey: nutritionKeys.foods(actor), queryFn: nutritionApi.foods, enabled: Boolean(actor && activeCapability === 'STUDENT') });
  const historyQuery = useQuery({ queryKey: ['nutrition', actor, 'corrections', editing?.id],
    queryFn: () => nutritionApi.corrections(editing!.id), enabled: Boolean(actor && editing) });

  const refresh = async () => {
    await Promise.all([client.invalidateQueries({ queryKey: nutritionKeys.day(actor, day, timezone) }),
      client.invalidateQueries({ queryKey: nutritionKeys.foods(actor) })]);
  };
  const send = async (command: Pending) => {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setMessage('');
    setPending(command);
    try {
      if (command.kind === 'food') await nutritionApi.createFood(command.body);
      if (command.kind === 'foodUpdate') await nutritionApi.updateFood(command.id, command.body);
      if (command.kind === 'item') await nutritionApi.addItem(command.day, command.body);
      if (command.kind === 'correction') await nutritionApi.correct(command.id, command.body);
      if (command.kind === 'confirm') await nutritionApi.confirm(command.day, command.body);
      setPending(null);
      setEditing(null);
      setEditingFood(null);
      setConfirmRemove(false);
      setConfirmDay(false);
      setMessage('Đã lưu. Dữ liệu trong ngày đã được cập nhật.');
      await refresh();
    } catch (error) {
      if (error instanceof ApiError && [403, 404].includes(error.status)) {
        setPending(null);
        client.removeQueries({ queryKey: ['nutrition', actor] });
        setMessage('Bạn không còn quyền xem hoặc sửa dữ liệu này.');
      } else if (error instanceof ApiError && error.status === 409) {
        setPending(null);
        await refresh();
        setMessage('Dữ liệu đã thay đổi. Hãy xem lại rồi xác nhận thao tác mới.');
      } else if (error instanceof ApiError && error.status === 400) {
        setPending(null);
        setMessage('Thông tin chưa hợp lệ. Hãy kiểm tra ngày, múi giờ và các giá trị nhập.');
      } else {
        setMessage('Chưa rõ lệnh đã được lưu hay chưa. Bạn có thể thử lại đúng lệnh này.');
      }
    } finally { submitting.current = false; setBusy(false); }
  };
  const numberOrNull = (value: string) => value.trim() ? Number(value) : null;
  const validNonNegative = (value: string) => !value.trim() || (Number.isFinite(Number(value)) && Number(value) >= 0);
  const createFood = () => {
    if (!name.trim() || !servingLabel.trim() || ![kcal, protein, carbs, fat].every(validNonNegative)) {
      setMessage('Nhập tên, khẩu phần và chỉ dùng số không âm cho dinh dưỡng đã biết. Để trống nếu chưa biết.'); return;
    }
    const body: FoodBody = { name: name.trim(), servingLabel: servingLabel.trim(), energyKcal: numberOrNull(kcal),
      proteinG: numberOrNull(protein), carbohydrateG: numberOrNull(carbs), fatG: numberOrNull(fat), commandKey: createCommandKey() };
    send(editingFood ? { kind: 'foodUpdate', id: editingFood.id,
      body: { ...body, expectedVersion: editingFood.version } } : { kind: 'food', body });
  };
  const addItem = () => {
    if (!validContext) { setMessage('Hãy chọn và xác nhận ngày cùng múi giờ IANA trước khi ghi.'); return; }
    const chosenFood = foodQuery.data?.find((food) => food.id === foodId);
    const count = Number(servings);
    if (foodId && (!chosenFood || chosenFood.quantificationStatus !== 'LOGGABLE' || !Number.isFinite(count) || count <= 0)) {
      setMessage('Chọn món riêng và số khẩu phần lớn hơn 0.'); return;
    }
    if (!foodId && !description.trim()) { setMessage('Mô tả món chưa định lượng không được để trống.'); return; }
    const offsets = validProposalOffsets(timezone, `${day}T${localTime}`);
    if (offsets.length === 0) { setMessage('Giờ địa phương không tồn tại hoặc chưa đúng định dạng HH:mm.'); return; }
    const selected = offsets.length === 1 ? offsets[0] : offsets.find((option) => option.offset === chosenOffset);
    if (!selected) { setMessage('Giờ này bị lặp do đổi giờ. Hãy chọn rõ UTC offset.'); return; }
    send({ kind: 'item', day, body: { timezone, consumedAt: new Date(selected.instant).toISOString(), mealType,
      description: foodId ? null : description.trim(), privateFoodId: foodId || null,
      servings: foodId ? count : null, commandKey: createCommandKey() } });
  };
  const correct = (remove: boolean) => {
    if (!editing) return;
    const count = Number(servings);
    if (!remove && foodId && (!foodQuery.data?.some((food) => food.id === foodId && food.quantificationStatus === 'LOGGABLE') || !Number.isFinite(count) || count <= 0)) {
      setMessage('Số khẩu phần phải lớn hơn 0.'); return;
    }
    if (!remove && !foodId && !description.trim()) { setMessage('Hãy nhập mô tả món.'); return; }
    send({ kind: 'correction', id: editing.id, body: { expectedVersion: editing.version,
      description: remove ? null : foodId ? null : description.trim(), privateFoodId: remove ? null : foodId || null,
      servings: remove || !foodId ? null : count, remove, commandKey: createCommandKey() } });
  };
  const confirm = (data: NutritionDay) => send({ kind: 'confirm', day, body: { timezone,
    expectedDayVersion: data.version, noIntakeConfirmed: data.items.length === 0, commandKey: createCommandKey() } });
  const input = (label: string, value: string, change: (s: string) => void, keyboardType: 'default' | 'decimal-pad' = 'default') =>
    <View style={{ marginBottom: 10 }}><Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text>
      <TextInput accessibilityLabel={label} value={value} onChangeText={change} keyboardType={keyboardType}
        style={{ borderWidth: 1, borderColor: theme.border, color: theme.textPrimary, minHeight: 48, padding: 10, borderRadius: 8 }} /></View>;

  if (activeCapability !== 'STUDENT') return <WorkoutState title="Chỉ dành cho học viên" message="Chuyển sang vai trò học viên để ghi dinh dưỡng." />;
  return <ScrollView testID="nutrition-student-screen" style={[styles.screen, { backgroundColor: theme.canvas }]}
    contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Nhật ký dinh dưỡng</Text>
    <WorkoutButton label="Mục tiêu dinh dưỡng và đề xuất" secondary onPress={() => router.push('/nutrition/strategy' as never)} />
    <Text style={[styles.body, { color: theme.textSecondary }]}>Dữ liệu bạn tự khai báo. Chưa biết không có nghĩa là 0.</Text>
    <WorkoutCard>
      {input('Ngày ghi (YYYY-MM-DD)', day, (value) => { setDay(value); setConfirmedContext(false); })}
      {input('Múi giờ IANA', timezone, (value) => { setTimezone(value); setConfirmedContext(false); })}
      <WorkoutButton label={confirmedContext ? 'Đã xác nhận ngày và múi giờ' : 'Xác nhận ngày và múi giờ'}
        disabled={!day || !moment(day, 'YYYY-MM-DD', true).isValid() || !moment.tz.zone(timezone)}
        onPress={() => setConfirmedContext(true)} />
    </WorkoutCard>
    {message ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.textPrimary }]}>{message}</Text> : null}
    {pending && !busy ? <WorkoutButton label="Thử lại cùng lệnh" onPress={() => send(pending)} /> : null}
    {validContext && dayQuery.isPending ? <WorkoutState title="Đang tải nhật ký" message="Vui lòng chờ." busy /> : null}
    {validContext && dayQuery.isError ? <WorkoutState title="Không tải được nhật ký" message="Kiểm tra kết nối hoặc múi giờ." onRetry={() => dayQuery.refetch()} /> : null}
    {validContext && dayQuery.data ? <>
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
        <Text style={[styles.body, { color: theme.textSecondary }]}>{item.kind === 'DESCRIPTION_ONLY' ? 'Chưa định lượng' : `${item.servings ?? '?'} ${item.servingLabel ?? 'khẩu phần'}`}</Text>
        <WorkoutButton label={`Sửa ${item.name}`} secondary onPress={() => {
          setEditing(item); setConfirmRemove(false); setFoodId(item.privateFoodId ?? ''); setDescription(item.kind === 'DESCRIPTION_ONLY' ? item.name : '');
          setServings(item.servings === null ? '1' : String(item.servings));
        }} />
      </WorkoutCard>)}
      {dayQuery.data.completeness !== 'COMPLETE' ? <>
        {!confirmDay ? <WorkoutButton label="Xác nhận hoàn tất ngày" onPress={() => setConfirmDay(true)} /> :
          <WorkoutCard><Text style={[styles.body, { color: theme.textPrimary }]}>
            {dayQuery.data.items.length === 0 ? 'Xác nhận không ăn uống trong ngày này?' : 'Xác nhận đã ghi xong ngày này?'}
          </Text><WorkoutButton label="Xác nhận" disabled={busy} onPress={() => confirm(dayQuery.data)} />
            <WorkoutButton label="Quay lại" secondary onPress={() => setConfirmDay(false)} /></WorkoutCard>}
      </> : null}
    </> : null}
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{editing ? `Sửa ${editing.name}` : 'Ghi một món'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Chọn món riêng hoặc mô tả món chưa định lượng.</Text>
      {foodQuery.data?.map((food) => <Pressable key={food.id} accessibilityRole="radio"
        accessibilityState={{ checked: foodId === food.id, disabled: food.quantificationStatus === 'DRAFT' }}
        disabled={food.quantificationStatus === 'DRAFT'}
        accessibilityLabel={`Chọn ${food.name}, nguồn tự khai báo`} onPress={() => setFoodId(food.id)} style={{ minHeight: 44, paddingVertical: 10 }}>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{foodId === food.id ? '◉' : '○'} {food.name} · {food.servingLabel}</Text>
        {food.quantificationStatus === 'DRAFT' ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bản nháp: chưa có dữ liệu dinh dưỡng; không thể ghi theo khẩu phần.</Text> : null}
      </Pressable>)}
      {foodQuery.data?.filter((food) => food.quantificationStatus === 'DRAFT').map((food) =>
        <WorkoutButton key={`edit-${food.id}`} label={`Bổ sung dinh dưỡng cho ${food.name}`} secondary onPress={() => {
          setEditingFood(food); setName(food.name); setServingLabel(food.servingLabel);
          setKcal(food.energyKcal === null ? '' : String(food.energyKcal));
          setProtein(food.proteinG === null ? '' : String(food.proteinG));
          setCarbs(food.carbohydrateG === null ? '' : String(food.carbohydrateG));
          setFat(food.fatG === null ? '' : String(food.fatG));
        }} />)}
      <WorkoutButton label="Món chỉ có mô tả" secondary onPress={() => setFoodId('')} />
      {!foodId ? input('Mô tả món', description, setDescription) : input('Số khẩu phần', servings, setServings, 'decimal-pad')}
      {!editing ? <>
        {input('Giờ ăn địa phương (HH:mm)', localTime, (value) => { setLocalTime(value); setChosenOffset(''); })}
        {validContext && validProposalOffsets(timezone, `${day}T${localTime}`).length > 1 ?
          <View><Text style={[styles.body, { color: theme.textPrimary }]}>Giờ này xuất hiện hai lần. Chọn UTC offset:</Text>
            {validProposalOffsets(timezone, `${day}T${localTime}`).map((option) =>
              <Pressable key={option.offset} accessibilityRole="radio" accessibilityState={{ checked: chosenOffset === option.offset }}
                onPress={() => setChosenOffset(option.offset)} style={{ minHeight: 44 }}>
                <Text style={[styles.body, { color: theme.textPrimary }]}>{chosenOffset === option.offset ? '◉' : '○'} {option.offset}</Text>
              </Pressable>)}</View> : null}
        {(['BREAKFAST', 'LUNCH', 'DINNER', 'SNACK', 'OTHER'] as const).map((type) =>
          <Pressable key={type} accessibilityRole="radio" accessibilityState={{ checked: mealType === type }}
            onPress={() => setMealType(type)} style={{ minHeight: 44 }}><Text style={[styles.body, { color: theme.textPrimary }]}>
              {mealType === type ? '◉' : '○'} {({ BREAKFAST: 'Bữa sáng', LUNCH: 'Bữa trưa', DINNER: 'Bữa tối', SNACK: 'Bữa phụ', OTHER: 'Khác' })[type]}
            </Text></Pressable>)}
        <WorkoutButton label="Ghi món" disabled={busy || !validContext} onPress={addItem} />
      </> : <>
        <WorkoutButton label="Lưu correction" disabled={busy} onPress={() => correct(false)} />
        {!confirmRemove ? <WorkoutButton label="Xóa món (giữ lịch sử)" secondary disabled={busy} onPress={() => setConfirmRemove(true)} /> :
          <><Text style={[styles.body, { color: theme.textPrimary }]}>Xác nhận xóa món khỏi ngày? Lịch sử correction vẫn được giữ.</Text>
            <WorkoutButton label="Xác nhận xóa món" disabled={busy} onPress={() => correct(true)} />
            <WorkoutButton label="Không xóa" secondary onPress={() => setConfirmRemove(false)} /></>}
        {historyQuery.data?.map((entry) => <Text key={entry.id} style={[styles.body, { color: theme.textSecondary }]}>
          Đã sửa phiên bản {entry.fromVersion} → {entry.toVersion} · {entry.correctedAt}
        </Text>)}
        <WorkoutButton label="Không sửa nữa" secondary onPress={() => setEditing(null)} />
      </>}
    </WorkoutCard>
    <WorkoutCard>
      <Text style={[styles.h2, { color: theme.textPrimary }]}>{editingFood ? `Bổ sung dinh dưỡng cho ${editingFood.name}` : 'Tạo món riêng'}</Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Nguồn tự khai báo; để trống chất dinh dưỡng chưa biết, chỉ nhập 0 nếu thực sự bằng 0. Món chưa có nutrient nào là bản nháp, chưa thể ghi theo khẩu phần.</Text>
      {input('Tên món', name, setName)}{input('Tên một khẩu phần', servingLabel, setServingLabel)}
      {input('Kcal mỗi khẩu phần', kcal, setKcal, 'decimal-pad')}{input('Protein (g)', protein, setProtein, 'decimal-pad')}
      {input('Carb (g)', carbs, setCarbs, 'decimal-pad')}{input('Chất béo (g)', fat, setFat, 'decimal-pad')}
      <WorkoutButton label={editingFood ? 'Lưu dinh dưỡng món nháp' : 'Lưu món riêng'} disabled={busy} onPress={createFood} />
      {editingFood ? <WorkoutButton label="Hủy chỉnh món nháp" secondary onPress={() => setEditingFood(null)} /> : null}
    </WorkoutCard>
  </ScrollView>;
}
