import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import React from 'react';
import { Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { WorkoutPlanVersionDetail } from '@/types/workoutPlan';
import { workoutStyles as styles } from './WorkoutUi';
import { workoutExerciseStateLabels, workoutPlanErrorMessage } from '../workoutPlanMessages';

interface Props {
  title: string;
  detail?: WorkoutPlanVersionDetail;
  isPending: boolean;
  isError: boolean;
  error: unknown;
  onRetry: () => void;
  testID?: string;
}

export function WorkoutPlanVersionContent({ title, detail, isPending, isError, error, onRetry, testID }: Props) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return <AppCard testID={testID} title={title} icon="workout">
    {isPending ? <AppCard title="Đang tải buổi tập" message="Vui lòng chờ trong giây lát." state="loading" /> : null}
    {isError ? <AppCard title="Không thể tải nội dung" message={workoutPlanErrorMessage(error)} onRetry={onRetry} state="error" /> : null}
    {detail?.sessions.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản này chưa có buổi tập.</Text> : null}
    {detail?.sessions.map((session) => <AppCard key={session.id}>
      <View style={{ flexDirection: 'row', gap: spacing.sm, alignItems: 'center' }}><AppIcon name="calendar" color={theme.primary} /><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary, flex: 1 }]}>Tuần {session.weekNumber}, ngày {session.dayNumber}: {session.name}</Text></View>
      {session.focus ? <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {session.focus}</Text> : null}
      {session.prescriptions.map((item) => <View key={item.id} style={{ gap: spacing.sm, padding: spacing.md, borderRadius: radius.md, backgroundColor: theme.surfaceSubtle }}>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{item.sequenceNumber}. {item.exercise.exerciseName ?? 'Bài tập không khả dụng'}{item.exercise.variationName ? ` · ${item.exercise.variationName}` : ''}</Text>
        <Text style={[styles.body, { color: item.exercise.state === 'ACTIVE' ? theme.textSecondary : theme.warningText }]}>{workoutExerciseStateLabels[item.exercise.state]}{item.targetSets !== null ? ` · ${item.targetSets} hiệp` : ''}{item.targetRepsMin !== null ? ` · ${item.targetRepsMin}${item.targetRepsMax !== null ? `–${item.targetRepsMax}` : ''} lần` : ''}</Text>
        {item.exercise.canonicalExerciseName ? <Text style={[styles.body, { color: theme.textSecondary }]}>Tên bài tập chuẩn hiện tại: {item.exercise.canonicalExerciseName} — tham khảo, không thay thế bản ghi gốc.</Text> : null}
      </View>)}
    </AppCard>)}
  </AppCard>;
}
