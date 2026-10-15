import React, { useEffect, useState } from 'react';
import { ScrollView, Text, TextInput, useColorScheme } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { nutritionApi } from './nutritionApi';
import { nutrientLabel, nutrientText } from './NutritionScreen';

export function TrainerNutritionScreen({ studentId }: { studentId: string }) {
  const { activeCapability, user } = useAuth();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [day, setDay] = useState('');
  const [timezone, setTimezone] = useState('');
  const [confirmed, setConfirmed] = useState(false);
  const valid = activeCapability === 'TRAINER' && Boolean(user?.id && studentId && confirmed &&
    moment(day, 'YYYY-MM-DD', true).isValid() && moment.tz.zone(timezone));
  const key = ['nutrition-trainer', user?.id, studentId, day, timezone];
  const query = useQuery({ queryKey: key, queryFn: () => nutritionApi.trainerDay(studentId, day, timezone),
    enabled: valid, retry: false, staleTime: 0, gcTime: 0, refetchOnMount: 'always' });
  const concealed = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  useEffect(() => {
    if (concealed) client.removeQueries({ queryKey: ['nutrition-trainer', user?.id, studentId] });
  }, [client, concealed, studentId, user?.id]);
  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Chuyển sang vai trò huấn luyện viên." />;
  return <ScrollView testID="nutrition-trainer-screen" style={[styles.screen, { backgroundColor: theme.canvas }]}
    contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Nhật ký dinh dưỡng học viên</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ đọc theo quyền NUTRITION_LOGS VIEW hiện hành.</Text>
    <WorkoutCard>
      <Text style={[styles.label, { color: theme.textPrimary }]}>Ngày (YYYY-MM-DD)</Text>
      <TextInput accessibilityLabel="Ngày xem" value={day} onChangeText={(value) => { setDay(value); setConfirmed(false); }}
        style={{ borderWidth: 1, borderColor: theme.border, color: theme.textPrimary, minHeight: 48, padding: 10 }} />
      <Text style={[styles.label, { color: theme.textPrimary }]}>Múi giờ IANA của ngày log</Text>
      <TextInput accessibilityLabel="Múi giờ của ngày log" value={timezone} onChangeText={(value) => { setTimezone(value); setConfirmed(false); }}
        style={{ borderWidth: 1, borderColor: theme.border, color: theme.textPrimary, minHeight: 48, padding: 10 }} />
      <WorkoutButton label="Xem ngày" disabled={!moment(day, 'YYYY-MM-DD', true).isValid() || !moment.tz.zone(timezone)} onPress={() => setConfirmed(true)} />
    </WorkoutCard>
    {valid && query.isFetching ? <WorkoutState title="Đang tải" message="Đang kiểm tra quyền hiện hành." busy /> : null}
    {concealed ? <WorkoutState title="Không thể xem nhật ký" message="Quyền xem hiện không còn hoặc dữ liệu không khả dụng." /> : null}
    {valid && query.isError && !concealed ? <WorkoutState title="Không thể tải nhật ký" message="Kiểm tra kết nối và múi giờ." onRetry={() => query.refetch()} /> : null}
    {valid && query.data && !query.isFetching && !concealed ? <>
      <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{query.data.date} · {query.data.timezone ?? 'Múi giờ cũ chưa được ghi'}</Text>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{query.data.completeness === 'NOT_LOGGED' ? 'Chưa ghi' : query.data.completeness === 'COMPLETE' ? 'Đã xác nhận' : 'Đang ghi'}</Text>
        {query.data.nutrients.map((value) => <Text key={value.code} style={[styles.body, { color: theme.textPrimary }]}>{nutrientLabel(value.code)}: {nutrientText(value.total, value.knownSubtotal)}</Text>)}
      </WorkoutCard>
      {query.data.items.map((item) => <WorkoutCard key={item.id}><Text style={[styles.h2, { color: theme.textPrimary }]}>{item.name}</Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>{item.kind === 'DESCRIPTION_ONLY' ? 'Chưa định lượng' : `${item.servings ?? '?'} ${item.servingLabel ?? 'khẩu phần'}`}</Text>
      </WorkoutCard>)}
      {query.data.items.length === 0 ? <WorkoutState title="Chưa có món" message="Không suy intake bằng 0 từ ngày chưa ghi." /> : null}
    </> : null}
  </ScrollView>;
}
