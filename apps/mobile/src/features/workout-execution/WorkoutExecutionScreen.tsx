import React, { useEffect, useRef, useState } from 'react';
import {
  AccessibilityInfo,
  Modal,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { ExercisePicker } from '@/features/exercise/ExercisePicker';
import { useExerciseDetail } from '@/features/exercise/exerciseQueries';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutExecutionApi } from '@/services/workoutExecutionApi';
import { ExerciseSummary } from '@/types/exercise';
import {
  FinishWorkoutExecutionInput,
  UpsertWorkoutSetInput,
  WorkoutExecutionDetail,
  WorkoutExerciseExecution,
  WorkoutSetExecution,
  WorkoutExecutionSetCompletionStatus,
} from '@/types/workoutExecution';
import {
  ExecutionButton,
  ExecutionCard,
  ExecutionState,
  executionStyles as styles,
} from './components/WorkoutExecutionUi';
import {
  useCurrentWorkoutExecution,
  useWorkoutExecutionDetail,
  workoutExecutionQueryKeys,
} from './workoutExecutionQueries';
import {
  isCurrentExecutionMissing,
  isWorkoutExecutionStale,
  supervisionLabels,
  workoutExecutionErrorMessage,
  workoutExecutionStatusLabels,
  workoutSetStatusLabels,
  workoutSetTypeLabels,
} from './workoutExecutionMessages';

const setStatuses: WorkoutExecutionSetCompletionStatus[] = [
  'PLANNED',
  'COMPLETED',
  'SKIPPED',
  'FAILED',
];

function optionalNumber(value: string): number | null | undefined {
  const trimmed = value.trim();
  if (!trimmed) return null;
  const parsed = Number(trimmed.replace(',', '.'));
  return Number.isFinite(parsed) ? parsed : undefined;
}

function displayExercise(exercise: WorkoutExerciseExecution): string {
  const presentation =
    exercise.actualVariationPresentation ?? exercise.prescribedVariationPresentation;
  if (!presentation) return 'Bài tập không còn khả dụng';
  return [presentation.exerciseName, presentation.variationName].filter(Boolean).join(' · ');
}

function formatDate(value: string | null, timezone?: string): string {
  if (!value) return 'Chưa có dữ liệu';
  return new Date(value).toLocaleString('vi-VN', timezone ? { timeZone: timezone } : undefined);
}

function targetText(exercise: WorkoutExerciseExecution): string {
  const pieces: string[] = [];
  if (exercise.baselineSetCount !== null) pieces.push(`${exercise.baselineSetCount} hiệp`);
  if (exercise.targetRepsMin !== null || exercise.targetRepsMax !== null) {
    pieces.push(
      `${exercise.targetRepsMin ?? '—'}–${exercise.targetRepsMax ?? '—'} lần`
    );
  }
  if (exercise.targetLoad !== null) pieces.push(`mức tải ${exercise.targetLoad}`);
  if (exercise.durationSeconds !== null) pieces.push(`${exercise.durationSeconds} giây`);
  return pieces.length ? pieces.join(' · ') : 'Không có chỉ tiêu định lượng';
}

interface SetEditorProps {
  exercise: WorkoutExerciseExecution;
  set: WorkoutSetExecution;
  busy: boolean;
  error: string | null;
  onSave: (clientSetId: string, input: UpsertWorkoutSetInput) => void;
  onRemove?: () => void;
}

function SetEditor({ exercise, set, busy, error, onSave, onRemove }: SetEditorProps) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [status, setStatus] = useState(set.completionStatus);
  const [repetitions, setRepetitions] = useState(set.repetitions?.toString() ?? '');
  const [load, setLoad] = useState(set.loadValue?.toString() ?? '');
  const [duration, setDuration] = useState(set.durationSeconds?.toString() ?? '');
  const [rpe, setRpe] = useState(set.rpe?.toString() ?? '');
  const [note, setNote] = useState(set.note ?? '');
  const [validation, setValidation] = useState<string | null>(null);

  const save = () => {
    const parsedRepetitions = optionalNumber(repetitions);
    const parsedLoad = optionalNumber(load);
    const parsedDuration = optionalNumber(duration);
    const parsedRpe = optionalNumber(rpe);
    if (
      parsedRepetitions === undefined ||
      parsedLoad === undefined ||
      parsedDuration === undefined ||
      parsedRpe === undefined ||
      (parsedRepetitions !== null && (!Number.isInteger(parsedRepetitions) || parsedRepetitions < 0)) ||
      (parsedLoad !== null && parsedLoad < 0) ||
      (parsedDuration !== null && (!Number.isInteger(parsedDuration) || parsedDuration < 0)) ||
      (parsedRpe !== null && (parsedRpe < 1 || parsedRpe > 10))
    ) {
      setValidation('Số lần và thời lượng phải là số nguyên không âm; mức tải không âm; RPE từ 1 đến 10.');
      return;
    }
    setValidation(null);
    onSave(set.clientSetId, {
      exerciseExecutionId: exercise.exerciseExecutionId,
      baselineSetNumber: set.baselineSetNumber,
      setNumber: set.setNumber,
      setType: set.setType,
      completionStatus: status,
      repetitions: parsedRepetitions,
      loadValue: parsedLoad,
      loadUnitId: set.loadUnitId ?? exercise.loadUnitId,
      durationSeconds: parsedDuration,
      distanceValue: set.distanceValue,
      distanceUnitId: set.distanceUnitId ?? exercise.distanceUnitId,
      rpe: parsedRpe,
      rir: set.rir,
      tempo: set.tempo,
      restAfterSeconds: set.restAfterSeconds,
      note: note.trim() || null,
    });
  };

  return (
    <View
      testID={`workout-set-${set.clientSetId}`}
      style={[local.setCard, { borderColor: theme.border }]}>
      <Text style={[styles.label, { color: theme.textPrimary }]}>
        Hiệp {set.setNumber} · {workoutSetTypeLabels[set.setType]}
      </Text>
      <View accessibilityRole="radiogroup" style={styles.row}>
        {setStatuses.map((item) => {
          const selected = status === item;
          return (
            <Pressable
              key={item}
              accessibilityRole="radio"
              accessibilityLabel={workoutSetStatusLabels[item]}
              accessibilityState={{ checked: selected, disabled: busy }}
              disabled={busy}
              onPress={() => setStatus(item)}
              style={[
                local.choice,
                {
                  borderColor: selected ? theme.primary : theme.border,
                  backgroundColor: selected ? theme.brandSoft : theme.surface,
                },
              ]}>
              <Text style={[styles.caption, { color: selected ? theme.primary : theme.textSecondary }]}>
                {workoutSetStatusLabels[item]}
              </Text>
            </Pressable>
          );
        })}
      </View>
      <View style={local.fieldGrid}>
        <LabeledInput label="Số lần" value={repetitions} onChangeText={setRepetitions} keyboardType="numeric" />
        <LabeledInput label="Mức tải" value={load} onChangeText={setLoad} keyboardType="decimal-pad" />
        <LabeledInput label="Thời lượng (giây)" value={duration} onChangeText={setDuration} keyboardType="numeric" />
        <LabeledInput label="RPE (1–10)" value={rpe} onChangeText={setRpe} keyboardType="decimal-pad" />
      </View>
      <LabeledInput label="Ghi chú hiệp" value={note} onChangeText={setNote} multiline />
      {validation ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{validation}</Text> : null}
      {error ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{error}</Text> : null}
      <ExecutionButton
        testID={`save-workout-set-${set.clientSetId}`}
        label={busy ? 'Đang lưu hiệp' : 'Lưu hiệp'}
        busy={busy}
        disabled={busy}
        onPress={save}
      />
      {onRemove ? (
        <ExecutionButton
          label="Hủy thêm hiệp này"
          secondary
          danger
          disabled={busy}
          onPress={onRemove}
        />
      ) : null}
    </View>
  );
}

function ElapsedTime({ startedAt }: { startedAt: string }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [now, setNow] = useState(() => Date.now());
  useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 60_000);
    return () => clearInterval(timer);
  }, []);
  const started = new Date(startedAt).getTime();
  const totalMinutes = Number.isFinite(started) ? Math.max(0, Math.floor((now - started) / 60_000)) : 0;
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  const label = hours > 0 ? `${hours} giờ ${minutes} phút` : `${minutes} phút`;
  return (
    <Text
      accessibilityLabel={`Thời gian đã tập ${label}`}
      style={[styles.label, { color: theme.primary }]}>
      Thời gian đã tập: {label}
    </Text>
  );
}

function LabeledInput({
  label,
  value,
  onChangeText,
  keyboardType,
  multiline = false,
}: {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  keyboardType?: 'numeric' | 'decimal-pad';
  multiline?: boolean;
}) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  return (
    <View style={local.field}>
      <Text style={[styles.caption, { color: theme.textSecondary }]}>{label}</Text>
      <TextInput
        accessibilityLabel={label}
        value={value}
        onChangeText={onChangeText}
        keyboardType={keyboardType}
        multiline={multiline}
        style={[
          styles.input,
          multiline ? local.multiline : null,
          { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surface },
        ]}
      />
    </View>
  );
}

export function CurrentWorkoutExecutionScreen() {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const query = useCurrentWorkoutExecution(studentId, allowed);

  if (!allowed) {
    return <ExecutionState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để ghi buổi tập của bạn." />;
  }
  if (query.isPending) return <ExecutionState busy title="Đang tải buổi tập" message="Vui lòng chờ trong giây lát." />;
  if (query.isError && isCurrentExecutionMissing(query.error)) {
    return (
      <ExecutionState
        testID="current-workout-empty"
        title="Không có buổi tập đang diễn ra"
        message="Chưa có buổi tập nào được bắt đầu. Hãy quay lại khi lịch tập của bạn đã sẵn sàng."
        actionLabel="Xem lịch sử tập luyện"
        onAction={() => router.push('/workouts/history' as never)}
      />
    );
  }
  if (query.isError || !query.data) {
    return <ExecutionState testID="current-workout-error" title="Không thể tải buổi tập" message={workoutExecutionErrorMessage(query.error)} onRetry={() => query.refetch()} />;
  }
  return <WorkoutExecutionContent detail={query.data} currentRoute onRefresh={() => query.refetch()} />;
}

export function WorkoutExecutionDetailScreen({ executionId }: { executionId: string }) {
  const { user, activeCapability } = useAuth();
  const studentId = user?.id ?? '';
  const allowed = activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const query = useWorkoutExecutionDetail(studentId, executionId, allowed);

  if (!allowed) return <ExecutionState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để xem buổi tập của bạn." />;
  if (query.isPending) return <ExecutionState busy title="Đang tải chi tiết" message="Vui lòng chờ trong giây lát." />;
  if (query.isError || !query.data) return <ExecutionState testID="workout-detail-error" title="Không thể mở buổi tập" message={workoutExecutionErrorMessage(query.error)} onRetry={() => query.refetch()} />;
  return <WorkoutExecutionContent detail={query.data} onRefresh={() => query.refetch()} />;
}

function WorkoutExecutionContent({
  detail,
  currentRoute = false,
  onRefresh,
}: {
  detail: WorkoutExecutionDetail;
  currentRoute?: boolean;
  onRefresh: () => Promise<unknown>;
}) {
  const { user } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
  const [setBusy, setSetBusy] = useState<string | null>(null);
  const [setError, setSetError] = useState<Record<string, string>>({});
  const [extraSets, setExtraSets] = useState<
    Record<string, { exerciseExecutionId: string; set: WorkoutSetExecution }>
  >({});
  const [substitutionTarget, setSubstitutionTarget] = useState<WorkoutExerciseExecution | null>(null);
  const [pickerSelection, setPickerSelection] = useState<ExerciseSummary | null>(null);
  const [selectedExercise, setSelectedExercise] = useState<ExerciseSummary | null>(null);
  const [substitutionReason, setSubstitutionReason] = useState('');
  const [terminal, setTerminal] = useState<'complete' | 'abort' | null>(null);
  const [overallRpe, setOverallRpe] = useState('');
  const [sessionNote, setSessionNote] = useState('');
  const [terminalValidation, setTerminalValidation] = useState<string | null>(null);
  const terminalKey = useRef<string | null>(null);
  const exerciseDetail = useExerciseDetail(selectedExercise?.id);
  const inProgress = detail.execution.status === 'IN_PROGRESS';

  const storeUpdated = async (updated: WorkoutExecutionDetail) => {
    client.setQueryData(
      workoutExecutionQueryKeys.detail(studentId, updated.execution.executionId),
      updated
    );
    if (updated.execution.status === 'IN_PROGRESS') {
      client.setQueryData(workoutExecutionQueryKeys.current(studentId), updated);
    } else {
      client.removeQueries({ queryKey: workoutExecutionQueryKeys.current(studentId), exact: true });
    }
    await client.invalidateQueries({ queryKey: workoutExecutionQueryKeys.history(studentId) });
  };

  const setMutation = useMutation({
    mutationFn: ({ clientSetId, input }: { clientSetId: string; input: UpsertWorkoutSetInput }) =>
      workoutExecutionApi.upsertSet(
        detail.execution.executionId,
        clientSetId,
        detail.execution.version,
        input
      ),
    onMutate: ({ clientSetId }) => {
      setSetBusy(clientSetId);
      setSetError((current) => ({ ...current, [clientSetId]: '' }));
    },
    onSuccess: async (updated, variables) => {
      setExtraSets((current) => {
        const next = { ...current };
        delete next[variables.clientSetId];
        return next;
      });
      await storeUpdated(updated);
      AccessibilityInfo.announceForAccessibility('Đã lưu hiệp tập.');
    },
    onError: async (error, variables) => {
      setSetError((current) => ({
        ...current,
        [variables.clientSetId]: workoutExecutionErrorMessage(error),
      }));
      if (isWorkoutExecutionStale(error)) await onRefresh();
    },
    onSettled: () => setSetBusy(null),
  });

  const substitutionMutation = useMutation({
    mutationFn: (variationId: string) => {
      if (!substitutionTarget) throw new Error('Missing exercise target');
      return workoutExecutionApi.substitute(
        detail.execution.executionId,
        substitutionTarget.exerciseExecutionId,
        detail.execution.version,
        {
          actualExerciseVariationId: variationId,
          substitutionReason: substitutionReason.trim() || null,
        }
      );
    },
    onSuccess: async (updated) => {
      setSubstitutionTarget(null);
      setPickerSelection(null);
      setSelectedExercise(null);
      setSubstitutionReason('');
      substitutionMutation.reset();
      await storeUpdated(updated);
      AccessibilityInfo.announceForAccessibility('Đã thay bài tập cho buổi tập này.');
    },
    onError: async (error) => {
      if (isWorkoutExecutionStale(error)) await onRefresh();
    },
  });

  const terminalMutation = useMutation({
    mutationFn: ({ action, input, key }: { action: 'complete' | 'abort'; input: FinishWorkoutExecutionInput; key: string }) =>
      action === 'complete'
        ? workoutExecutionApi.complete(detail.execution.executionId, detail.execution.version, input, key)
        : workoutExecutionApi.abort(detail.execution.executionId, detail.execution.version, input, key),
    onSuccess: async (updated) => {
      setTerminal(null);
      terminalKey.current = null;
      await storeUpdated(updated);
      AccessibilityInfo.announceForAccessibility(
        `Buổi tập ở trạng thái ${workoutExecutionStatusLabels[updated.execution.status]}.`
      );
      if (currentRoute) router.replace(`/workouts/${updated.execution.executionId}` as never);
    },
    onError: async (error) => {
      if (isWorkoutExecutionStale(error)) {
        terminalKey.current = null;
        setTerminal(null);
        await onRefresh();
      }
    },
  });

  const addSet = (exercise: WorkoutExerciseExecution) => {
    const clientSetId = createCommandKey();
    const numbers = [
      ...exercise.sets.map((item) => item.setNumber),
      ...Object.values(extraSets)
        .filter((item) => item.exerciseExecutionId === exercise.exerciseExecutionId)
        .map((item) => item.set.setNumber),
    ];
    const setNumber = Math.max(0, ...numbers) + 1;
    setExtraSets((current) => ({
      ...current,
      [clientSetId]: {
        exerciseExecutionId: exercise.exerciseExecutionId,
        set: {
          clientSetId,
          baselineSetNumber: null,
          setNumber,
          setType: 'WORKING',
          completionStatus: 'PLANNED',
          repetitions: null,
          loadValue: null,
          loadUnitId: exercise.loadUnitId,
          durationSeconds: null,
          distanceValue: null,
          distanceUnitId: exercise.distanceUnitId,
          rpe: null,
          rir: null,
          tempo: null,
          restAfterSeconds: exercise.restSeconds,
          note: null,
          completedAt: null,
        },
      },
    }));
  };

  const openTerminal = (action: 'complete' | 'abort') => {
    terminalMutation.reset();
    terminalKey.current = createCommandKey();
    setOverallRpe(detail.execution.overallRpe?.toString() ?? '');
    setSessionNote(detail.execution.sessionNote ?? '');
    setTerminalValidation(null);
    setTerminal(action);
  };

  const submitTerminal = () => {
    if (!terminal) return;
    const parsedRpe = optionalNumber(overallRpe);
    if (parsedRpe === undefined || (parsedRpe !== null && (parsedRpe < 1 || parsedRpe > 10))) {
      setTerminalValidation('RPE toàn buổi phải từ 1 đến 10 hoặc để trống.');
      return;
    }
    const key = terminalKey.current ?? createCommandKey();
    terminalKey.current = key;
    terminalMutation.mutate({
      action: terminal,
      key,
      input: { overallRpe: parsedRpe, sessionNote: sessionNote.trim() || null },
    });
  };

  return (
    <ScrollView
      testID="workout-execution-screen"
      style={[styles.screen, { backgroundColor: theme.canvas }]}
      contentContainerStyle={styles.content}
      keyboardShouldPersistTaps="handled">
      <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>
        {inProgress ? 'Buổi tập đang diễn ra' : 'Kết quả buổi tập'}
      </Text>
      <View style={styles.row}>
        <ExecutionButton label="Quay lại" secondary onPress={() => router.back()} />
        <ExecutionButton label="Lịch sử tập luyện" secondary onPress={() => router.push('/workouts/history' as never)} />
      </View>
      <ExecutionCard testID="workout-execution-summary">
        <Text style={[styles.h2, { color: theme.textPrimary }]}>
          {workoutExecutionStatusLabels[detail.execution.status]}
        </Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>
          Bắt đầu thực tế: {formatDate(detail.execution.performedStartedAt, user?.timezone)}
        </Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>
          Thời gian dự kiến: {formatDate(detail.execution.plannedStartAt, user?.timezone)}
        </Text>
        {detail.execution.supervisionRequirement ? (
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            {supervisionLabels[detail.execution.supervisionRequirement]}
          </Text>
        ) : null}
        {detail.execution.snapshotMode === 'LEGACY_REFERENCE_ONLY' ? (
          <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>
            Đây là bản ghi cũ; một số chỉ tiêu và liên kết kế hoạch không có dữ liệu.
          </Text>
        ) : null}
        {detail.execution.overallRpe !== null ? (
          <Text style={[styles.body, { color: theme.textPrimary }]}>RPE toàn buổi: {detail.execution.overallRpe}</Text>
        ) : null}
        {detail.execution.sessionNote ? (
          <Text style={[styles.body, { color: theme.textPrimary }]}>{detail.execution.sessionNote}</Text>
        ) : null}
        {inProgress ? <ElapsedTime startedAt={detail.execution.performedStartedAt} /> : null}
        {detail.execution.performedEndedAt ? (
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Kết thúc thực tế: {formatDate(detail.execution.performedEndedAt, user?.timezone)}
          </Text>
        ) : null}
      </ExecutionCard>

      {detail.exercises.length === 0 ? (
        <ExecutionState title="Không có chi tiết bài tập" message="Bản ghi này không chứa dữ liệu bài tập có thể hiển thị." />
      ) : null}
      {detail.exercises.map((exercise, index) => {
        const added = Object.values(extraSets)
          .filter((item) => item.exerciseExecutionId === exercise.exerciseExecutionId)
          .map((item) => item.set);
        return (
          <ExecutionCard key={exercise.exerciseExecutionId} testID={`workout-exercise-${exercise.exerciseExecutionId}`}>
            <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
              {index + 1}. {displayExercise(exercise)}
            </Text>
            <Text style={[styles.body, { color: theme.textSecondary }]}>{targetText(exercise)}</Text>
            {exercise.instructions ? <Text style={[styles.body, { color: theme.textPrimary }]}>{exercise.instructions}</Text> : null}
            {exercise.substitutionReason ? <Text style={[styles.body, { color: theme.warningText }]}>Lý do thay bài: {exercise.substitutionReason}</Text> : null}
            {[...exercise.sets, ...added].map((set) => (
              <SetEditor
                key={`${set.clientSetId}-${detail.execution.version}`}
                exercise={exercise}
                set={set}
                busy={setBusy === set.clientSetId}
                error={setError[set.clientSetId] || null}
                onSave={(clientSetId, input) => setMutation.mutate({ clientSetId, input })}
                onRemove={extraSets[set.clientSetId] ? () => {
                  setExtraSets((current) => {
                    const next = { ...current };
                    delete next[set.clientSetId];
                    return next;
                  });
                } : undefined}
              />
            ))}
            {inProgress ? (
              <View style={styles.row}>
                <ExecutionButton label="Thêm hiệp" secondary disabled={setMutation.isPending} onPress={() => addSet(exercise)} />
                <ExecutionButton
                  label="Thay bài tập"
                  secondary
                  disabled={substitutionMutation.isPending || setMutation.isPending}
                  onPress={() => {
                    substitutionMutation.reset();
                    setSubstitutionTarget(exercise);
                    setPickerSelection(null);
                    setSelectedExercise(null);
                    setSubstitutionReason('');
                  }}
                />
              </View>
            ) : null}
          </ExecutionCard>
        );
      })}

      {inProgress ? (
        <ExecutionCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Kết thúc buổi tập</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Backend sẽ xác định kết quả hoàn thành hoặc hoàn thành một phần từ các hiệp đã ghi.
          </Text>
          <View style={styles.row}>
            <ExecutionButton testID="complete-workout-button" label="Hoàn thành buổi tập" disabled={setMutation.isPending} onPress={() => openTerminal('complete')} />
            <ExecutionButton testID="abort-workout-button" label="Hủy buổi tập" secondary danger disabled={setMutation.isPending} onPress={() => openTerminal('abort')} />
          </View>
        </ExecutionCard>
      ) : null}

      <Modal
        visible={substitutionTarget !== null}
        animationType="slide"
        accessibilityViewIsModal
        onRequestClose={() => !substitutionMutation.isPending && setSubstitutionTarget(null)}>
        <SafeAreaView style={[local.modalScreen, { backgroundColor: theme.canvas }]}>
          {!selectedExercise ? (
            <ExercisePicker
              selectedExercise={pickerSelection}
              onSelectionChange={setPickerSelection}
              onConfirm={setSelectedExercise}
              onCancel={() => {
                substitutionMutation.reset();
                setSubstitutionTarget(null);
                setPickerSelection(null);
              }}
              excludedExerciseIds={[
                substitutionTarget?.actualVariationPresentation?.exerciseId ??
                  substitutionTarget?.prescribedVariationPresentation?.exerciseId ??
                  '',
              ].filter(Boolean)}
            />
          ) : (
            <ScrollView contentContainerStyle={styles.content}>
              <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chọn biến thể</Text>
              <Text style={[styles.body, { color: theme.textSecondary }]}>{selectedExercise.name}</Text>
              <LabeledInput label="Lý do thay bài (không bắt buộc)" value={substitutionReason} onChangeText={(value) => { setSubstitutionReason(value); }} multiline />
              {exerciseDetail.isPending ? <ExecutionState busy title="Đang tải biến thể" message="Vui lòng chờ trong giây lát." /> : null}
              {exerciseDetail.isError ? <ExecutionState title="Không thể tải biến thể" message="Hãy thử lại." onRetry={() => exerciseDetail.refetch()} /> : null}
              {exerciseDetail.data?.variations.map((variation) => (
                <ExecutionButton
                  key={variation.id}
                  label={`${variation.name}${variation.defaultVariation ? ' · Mặc định' : ''}`}
                  secondary
                  busy={substitutionMutation.isPending}
                  disabled={substitutionMutation.isPending}
                  onPress={() => substitutionMutation.mutate(variation.id)}
                />
              ))}
              {substitutionMutation.isError ? <ExecutionState title="Chưa thể thay bài tập" message={workoutExecutionErrorMessage(substitutionMutation.error)} /> : null}
              <ExecutionButton label="Chọn bài tập khác" secondary disabled={substitutionMutation.isPending} onPress={() => { setSelectedExercise(null); setPickerSelection(null); }} />
              <ExecutionButton label="Hủy thay bài" secondary disabled={substitutionMutation.isPending} onPress={() => { substitutionMutation.reset(); setSubstitutionTarget(null); setPickerSelection(null); }} />
            </ScrollView>
          )}
        </SafeAreaView>
      </Modal>

      <Modal visible={terminal !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={() => !terminalMutation.isPending && setTerminal(null)}>
        <View style={[local.backdrop, { backgroundColor: theme.canvas }]}>
          <ExecutionCard testID="workout-terminal-confirmation">
            <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
              {terminal === 'complete' ? 'Xác nhận hoàn thành' : 'Xác nhận hủy buổi tập'}
            </Text>
            <LabeledInput label="RPE toàn buổi (1–10, không bắt buộc)" value={overallRpe} onChangeText={(value) => { terminalKey.current = null; setOverallRpe(value); }} keyboardType="decimal-pad" />
            <LabeledInput label="Ghi chú buổi tập" value={sessionNote} onChangeText={(value) => { terminalKey.current = null; setSessionNote(value); }} multiline />
            {terminalValidation ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{terminalValidation}</Text> : null}
            {terminalMutation.isError ? <ExecutionState title="Chưa thể kết thúc buổi tập" message={workoutExecutionErrorMessage(terminalMutation.error)} /> : null}
            <View style={styles.row}>
              <ExecutionButton label="Quay lại" secondary disabled={terminalMutation.isPending} onPress={() => { setTerminal(null); terminalKey.current = null; }} />
              <ExecutionButton
                testID="confirm-workout-terminal"
                label={terminalMutation.isPending ? 'Đang xử lý' : 'Xác nhận'}
                busy={terminalMutation.isPending}
                disabled={terminalMutation.isPending}
                danger={terminal === 'abort'}
                onPress={submitTerminal}
              />
            </View>
          </ExecutionCard>
        </View>
      </Modal>
    </ScrollView>
  );
}

const local = StyleSheet.create({
  setCard: { borderTopWidth: 1, paddingTop: spacing.md, gap: spacing.sm },
  choice: {
    minHeight: layout.minimumTouchTarget,
    borderWidth: 1,
    borderRadius: radius.full,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
  },
  fieldGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  field: { minWidth: 132, flexGrow: 1, flexBasis: 132, gap: spacing.xs },
  multiline: { minHeight: 88, paddingVertical: spacing.md, textAlignVertical: 'top' },
  modalScreen: { flex: 1 },
  backdrop: { flex: 1, justifyContent: 'center', padding: spacing.lg },
});
