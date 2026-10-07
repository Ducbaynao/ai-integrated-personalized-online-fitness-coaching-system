import React from 'react';
import { Text, View, useColorScheme } from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { WorkoutPlanVersionDetail } from '@/types/workoutPlan';
import { WorkoutCard, WorkoutState, workoutStyles as styles } from './WorkoutUi';
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
  return <WorkoutCard testID={testID}>
    <Text style={[styles.h2, { color: theme.textPrimary }]}>{title}</Text>
    {isPending ? <WorkoutState busy title="Đang tải buổi tập" message="Vui lòng chờ trong giây lát." /> : null}
    {isError ? <WorkoutState title="Không thể tải nội dung" message={workoutPlanErrorMessage(error)} onRetry={onRetry} /> : null}
    {detail?.sessions.length === 0 ? <Text style={[styles.body, { color: theme.textSecondary }]}>Phiên bản này chưa có buổi tập.</Text> : null}
    {detail?.sessions.map((session) => <View key={session.id}>
      <Text style={[styles.label, { color: theme.textPrimary }]}>Tuần {session.weekNumber}, ngày {session.dayNumber}: {session.name}</Text>
      {session.focus ? <Text style={[styles.body, { color: theme.textSecondary }]}>Trọng tâm: {session.focus}</Text> : null}
      {session.prescriptions.map((item) => <View key={item.id}>
        <Text style={[styles.body, { color: theme.textPrimary }]}>{item.sequenceNumber}. {item.exercise.exerciseName ?? 'Bài tập không khả dụng'}{item.exercise.variationName ? ` · ${item.exercise.variationName}` : ''}</Text>
        <Text style={[styles.body, { color: item.exercise.state === 'ACTIVE' ? theme.textSecondary : theme.warningText }]}>{workoutExerciseStateLabels[item.exercise.state]}{item.targetSets !== null ? ` · ${item.targetSets} hiệp` : ''}{item.targetRepsMin !== null ? ` · ${item.targetRepsMin}${item.targetRepsMax !== null ? `–${item.targetRepsMax}` : ''} reps` : ''}</Text>
        {item.exercise.canonicalExerciseName ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bài canonical hiện tại: {item.exercise.canonicalExerciseName} — tham khảo, không thay thế bản ghi gốc.</Text> : null}
      </View>)}
    </View>)}
  </WorkoutCard>;
}
