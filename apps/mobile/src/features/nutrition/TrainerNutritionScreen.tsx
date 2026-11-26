import React, { useEffect, useState } from 'react';
import { ScrollView, Text, useColorScheme } from 'react-native';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import moment from 'moment-timezone';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { DateInputField } from '@/components/ui/DateInputField';
import { TimezoneContextPicker } from '@/components/ui/TimezoneContextPicker';
import { getTodayDateOnly } from '@/features/shared/dateInputUtils';
import {
  DEFAULT_APP_TIMEZONE,
  formatTimezoneDisplay,
  isSupportedTimezone,
  resolveNewRecordDefaultTimezone,
  resolveStrategyGoalTimezone,
  type StrategyGoalTimezoneLike,
} from '@/features/shared/timezoneUtils';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from '@/features/workout-plan/components/WorkoutUi';
import { nutritionApi } from './nutritionApi';
import { nutrientLabel, nutrientText } from './NutritionScreen';

export function TrainerNutritionScreen({ studentId }: { studentId: string }) {
  const { activeCapability, user } = useAuth();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const actor = user?.id ?? '';
  const scopeKey = `${actor}:${studentId}`;
  const [trackedScope, setTrackedScope] = useState(scopeKey);
  const [day, setDay] = useState('');
  const [manualTimezone, setManualTimezone] = useState<string | null>(null);
  const [tzExpanded, setTzExpanded] = useState(false);
  const [confirmedScope, setConfirmedScope] = useState<{ actor: string; studentId: string; day: string; timezone: string } | null>(null);
  if (trackedScope !== scopeKey) {
    setTrackedScope(scopeKey);
    setDay('');
    setManualTimezone(null);
    setTzExpanded(false);
    setConfirmedScope(null);
  }
  const strategyEnabled = Boolean(activeCapability === 'TRAINER' && actor && studentId && typeof nutritionApi.trainerStrategy === 'function');
  const strategyQuery = useQuery({
    queryKey: ['nutrition-trainer-strategy-context', actor, studentId],
    queryFn: () => nutritionApi.trainerStrategy(studentId),
    enabled: strategyEnabled,
    retry: false,
    staleTime: 0,
    gcTime: 0,
  });
  const strategyGoal = strategyQuery.data && typeof strategyQuery.data === 'object' && 'goal' in strategyQuery.data
    ? (strategyQuery.data as { goal?: StrategyGoalTimezoneLike | null }).goal
    : null;
  const studentStrategyTimezone = resolveStrategyGoalTimezone(strategyGoal, day || getTodayDateOnly(DEFAULT_APP_TIMEZONE));
  const strategyResolved = !strategyEnabled || !strategyQuery.isPending || Boolean(manualTimezone);
  // Trainer NEVER uses their own account timezone to query a Student's nutrition day.
  const timezone = manualTimezone ?? studentStrategyTimezone ?? resolveNewRecordDefaultTimezone();
  const validDayInput = activeCapability === 'TRAINER' && Boolean(actor && studentId && moment(day, 'YYYY-MM-DD', true).isValid() && isSupportedTimezone(timezone));
  const valid = Boolean(
    validDayInput &&
    strategyResolved &&
    confirmedScope?.actor === actor &&
    confirmedScope?.studentId === studentId &&
    confirmedScope?.day === day &&
    confirmedScope?.timezone === timezone,
  );
  const key = ['nutrition-trainer', actor, studentId, day, timezone];
  const query = useQuery({
    queryKey: key,
    queryFn: () => nutritionApi.trainerDay(studentId, day, timezone),
    enabled: valid,
    retry: false,
    staleTime: 0,
    gcTime: 0,
    refetchOnMount: 'always',
  });
  const concealed = query.error instanceof ApiError && [403, 404].includes(query.error.status);
  const timezoneConflict = query.error instanceof ApiError && query.error.status === 409;
  const dayDataMatchesScope = Boolean(
    query.data &&
    (!query.data.studentId || query.data.studentId === studentId) &&
    query.data.date === day &&
    (!query.data.timezone || query.data.timezone === timezone),
  );
  useEffect(() => {
    if (concealed) client.removeQueries({ queryKey: ['nutrition-trainer', actor, studentId] });
  }, [actor, client, concealed, studentId]);
  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho huấn luyện viên" message="Chuyển sang vai trò huấn luyện viên." />;
  return <ScrollView testID="nutrition-trainer-screen" style={[styles.screen, { backgroundColor: theme.canvas }]}
    contentContainerStyle={styles.content}>
    <Text style={[styles.title, { color: theme.textPrimary }]}>Nhật ký dinh dưỡng học viên</Text>
    <Text style={[styles.body, { color: theme.textSecondary }]}>Chỉ đọc theo quyền NUTRITION_LOGS VIEW hiện hành.</Text>
    <WorkoutCard>
      <DateInputField
        testID="trainer-nutrition-day-input"
        label="Ngày xem"
        placeholder="Chọn ngày"
        value={day}
        onChange={(value) => { setDay(value); setConfirmedScope(null); }}
        initialBrowseDate={getTodayDateOnly(timezone)}
        sessionKey={scopeKey}
        accessibilityLabel="Ngày xem"
      />
      <TimezoneContextPicker
        testIDPrefix="trainer-nutrition-timezone"
        label="Khung giờ áp dụng"
        value={timezone}
        expanded={tzExpanded || timezoneConflict}
        onToggleExpanded={() => setTzExpanded((v) => !v)}
        extraTimezones={[studentStrategyTimezone, dayDataMatchesScope ? query.data?.timezone : null]}
        helperText={studentStrategyTimezone
          ? 'Đã lấy theo ngữ cảnh mục tiêu đã lưu của học viên.'
          : 'Nếu nhật ký học viên lưu theo khung giờ khác, chọn hoặc tìm thành phố/khu vực phù hợp từ danh sách.'}
        onChange={(nextZone) => {
          setManualTimezone(nextZone);
          setTzExpanded(false);
          setConfirmedScope(null);
        }}
      />
      <WorkoutButton label="Xem ngày" disabled={!validDayInput || !strategyResolved} onPress={() => setConfirmedScope({ actor, studentId, day, timezone })} />
    </WorkoutCard>
    {valid && query.isFetching ? <WorkoutState title="Đang tải" message="Đang kiểm tra quyền hiện hành." busy /> : null}
    {concealed ? <WorkoutState title="Không thể xem nhật ký" message="Quyền xem hiện không còn hoặc dữ liệu không khả dụng." /> : null}
    {valid && timezoneConflict ? (
      <WorkoutState
        title="Ngày nhật ký dùng khung giờ khác"
        message="Ngày này của học viên được ghi nhận theo một khung giờ khác. Hãy tìm và chọn đúng thành phố/khu vực từ danh sách thân thiện ở trên rồi nhấn Xem ngày."
      />
    ) : null}
    {valid && query.isError && !concealed && !timezoneConflict ? <WorkoutState title="Không thể tải nhật ký" message="Kiểm tra kết nối và khung giờ." onRetry={() => query.refetch()} /> : null}
    {valid && dayDataMatchesScope && query.data && !query.isFetching && !concealed ? <>
      <WorkoutCard>
        <Text style={[styles.h2, { color: theme.textPrimary }]}>{query.data.date} · {formatTimezoneDisplay(query.data.timezone, 'Múi giờ cũ chưa được ghi')}</Text>
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
