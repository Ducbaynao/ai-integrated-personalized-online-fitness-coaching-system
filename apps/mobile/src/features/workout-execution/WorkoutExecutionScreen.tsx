import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { AppIcon } from '@/design-system/components/AppIcon';
import { formatInstantVietnam } from '@/localization/instantDisplay';
import { formatRecordedScheduleTime } from '@/features/workout-schedule/scheduleDisplay';
import React, { useEffect, useLayoutEffect, useRef, useState } from 'react';
import {
  AccessibilityInfo,
  KeyboardAvoidingView,
  Modal,
  Platform,
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
import { typography } from '@/design-system/tokens/typography';
import { iconSize, layout, spacing } from '@/design-system/tokens/spacing';
import { ExercisePicker } from '@/features/exercise/ExercisePicker';
import { useExerciseDetail } from '@/features/exercise/exerciseQueries';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { workoutExecutionApi } from '@/services/workoutExecutionApi';
import { ExerciseSummary } from '@/types/exercise';
import {
  FinishWorkoutExecutionInput,
  MeasurementUnitPresentation,
  UpsertWorkoutSetInput,
  WorkoutExecutionDetail,
  WorkoutExerciseExecution,
  WorkoutSetExecution,
  WorkoutExecutionSetCompletionStatus,
} from '@/types/workoutExecution';
import { executionStyles as styles } from './components/WorkoutExecutionUi';
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

interface ResolvedUnit {
  id: number | null;
  presentation: MeasurementUnitPresentation | null;
  label: string;
  unavailableMessage: string | null;
}

function unitLabel(unit: MeasurementUnitPresentation | null): string | null {
  const symbol = unit?.symbol.trim();
  if (symbol) return symbol;
  const code = unit?.code.trim();
  return code || null;
}

function matchingUnit(
  id: number | null,
  ...presentations: (MeasurementUnitPresentation | null | undefined)[]
): MeasurementUnitPresentation | null {
  if (id === null) return null;
  return presentations.find((unit) => unit?.id === id) ?? null;
}

function resolveSetUnit(
  kind: 'tải' | 'quãng đường',
  setUnitId: number | null,
  setUnit: MeasurementUnitPresentation | null | undefined,
  exerciseUnitId: number | null,
  exerciseUnit: MeasurementUnitPresentation | null | undefined
): ResolvedUnit {
  const id = setUnitId ?? exerciseUnitId;
  const presentation = matchingUnit(id, setUnit, exerciseUnit);
  const label = unitLabel(presentation);
  if (label) return { id, presentation, label, unavailableMessage: null };
  if (id === null) {
    return {
      id,
      presentation,
      label: 'Chưa có đơn vị',
      unavailableMessage: `Chưa có đơn vị ${kind} từ kế hoạch. Không thể nhập giá trị này.`,
    };
  }
  return {
    id,
    presentation,
    label: 'Đơn vị không xác định',
    unavailableMessage: `Không xác định được đơn vị ${kind} từ danh mục. Giá trị hiện có được giữ nguyên; không thể nhập giá trị mới.`,
  };
}

function measurementText(
  value: number,
  unitId: number | null,
  unit: MeasurementUnitPresentation | null | undefined
): string {
  const label = unitLabel(matchingUnit(unitId, unit));
  return label ? `${value} ${label}` : `${value} · đơn vị không xác định`;
}

function targetText(exercise: WorkoutExerciseExecution): string {
  const pieces: string[] = [];
  if (exercise.baselineSetCount !== null) pieces.push(`${exercise.baselineSetCount} hiệp`);
  if (exercise.targetRepsMin !== null || exercise.targetRepsMax !== null) {
    pieces.push(
      `${exercise.targetRepsMin ?? '—'}–${exercise.targetRepsMax ?? '—'} lần`
    );
  }
  if (exercise.targetLoad !== null) {
    pieces.push(`mức tải ${measurementText(exercise.targetLoad, exercise.loadUnitId, exercise.loadUnit)}`);
  }
  if (exercise.durationSeconds !== null) pieces.push(`${exercise.durationSeconds} giây`);
  if (exercise.distanceValue !== null) {
    pieces.push(
      `quãng đường ${measurementText(
        exercise.distanceValue,
        exercise.distanceUnitId,
        exercise.distanceUnit
      )}`
    );
  }
  return pieces.length ? pieces.join(' · ') : 'Không có chỉ tiêu định lượng';
}

interface SetEditorProps {
  exercise: WorkoutExerciseExecution;
  set: WorkoutSetExecution;
  busy: boolean;
  canSave: () => boolean;
  error: string | null;
  onSave: (clientSetId: string, input: UpsertWorkoutSetInput) => void;
  onRemove?: () => void;
}

function SetEditor({ exercise, set, busy, canSave, error, onSave, onRemove }: SetEditorProps) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const [status, setStatus] = useState(set.completionStatus);
  const [repetitions, setRepetitions] = useState(set.repetitions?.toString() ?? '');
  const [load, setLoad] = useState(set.loadValue?.toString() ?? '');
  const [duration, setDuration] = useState(set.durationSeconds?.toString() ?? '');
  const [distance, setDistance] = useState(set.distanceValue?.toString() ?? '');
  const [rpe, setRpe] = useState(set.rpe?.toString() ?? '');
  const [note, setNote] = useState(set.note ?? '');
  const [validation, setValidation] = useState<string | null>(null);
  const loadUnit = resolveSetUnit(
    'tải',
    set.loadUnitId,
    set.loadUnit,
    exercise.loadUnitId,
    exercise.loadUnit
  );
  const distanceUnit = resolveSetUnit(
    'quãng đường',
    set.distanceUnitId,
    set.distanceUnit,
    exercise.distanceUnitId,
    exercise.distanceUnit
  );
  const canEditLoad = loadUnit.presentation !== null;
  const canEditDistance = distanceUnit.presentation !== null;
  const showsLoad = loadUnit.id !== null || set.loadValue !== null || exercise.targetLoad !== null;
  const showsDistance =
    distanceUnit.id !== null || set.distanceValue !== null || exercise.distanceValue !== null;

  const save = () => {
    if (busy || !canSave()) return;
    const parsedRepetitions = optionalNumber(repetitions);
    const parsedLoad = canEditLoad ? optionalNumber(load) : set.loadValue;
    const parsedDuration = optionalNumber(duration);
    const parsedDistance = canEditDistance ? optionalNumber(distance) : set.distanceValue;
    const parsedRpe = optionalNumber(rpe);
    if (
      parsedRepetitions === undefined ||
      parsedLoad === undefined ||
      parsedDuration === undefined ||
      parsedDistance === undefined ||
      parsedRpe === undefined ||
      (parsedRepetitions !== null && (!Number.isInteger(parsedRepetitions) || parsedRepetitions < 0)) ||
      (parsedLoad !== null && parsedLoad < 0) ||
      (parsedDuration !== null && (!Number.isInteger(parsedDuration) || parsedDuration < 0)) ||
      (parsedDistance !== null && parsedDistance < 0) ||
      (parsedRpe !== null && (parsedRpe < 1 || parsedRpe > 10))
    ) {
      setValidation('Số lần và thời lượng phải là số nguyên không âm; mức tải và quãng đường không âm; RPE từ 1 đến 10.');
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
      loadUnitId: loadUnit.id,
      durationSeconds: parsedDuration,
      distanceValue: parsedDistance,
      distanceUnitId: distanceUnit.id,
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
      style={[local.setCard, { borderColor: theme.border, backgroundColor: theme.surfaceSubtle }]}>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
        Hiệp {set.setNumber} · {workoutSetTypeLabels[set.setType]}
      </Text>
      <Text style={[styles.body, { color: theme.textSecondary }]}>Giá trị trống là chưa ghi.</Text>
      <View accessibilityRole="radiogroup" accessibilityLabel="Trạng thái hiệp" style={styles.row}>
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
              {selected ? <AppIcon name="check" size={iconSize.small} color={theme.primary} /> : null}
              <Text style={[styles.body, { color: selected ? theme.primary : theme.textPrimary, flexShrink: 1 }]}>
                {workoutSetStatusLabels[item]}
              </Text>
            </Pressable>
          );
        })}
      </View>
      <View style={local.fieldGrid}>
        <LabeledInput label="Số lần" value={repetitions} onChangeText={setRepetitions} keyboardType="numeric" disabled={busy} />
        {showsLoad ? (
          <LabeledInput
            label="Mức tải"
            value={load}
            onChangeText={setLoad}
            keyboardType="decimal-pad"
            unitLabel={loadUnit.label}
            disabled={busy || !canEditLoad}
            helperText={loadUnit.unavailableMessage}
          />
        ) : null}
        <LabeledInput label="Thời lượng (giây)" value={duration} onChangeText={setDuration} keyboardType="numeric" disabled={busy} />
        {showsDistance ? (
          <LabeledInput
            label="Quãng đường"
            value={distance}
            onChangeText={setDistance}
            keyboardType="decimal-pad"
            unitLabel={distanceUnit.label}
            disabled={busy || !canEditDistance}
            helperText={distanceUnit.unavailableMessage}
          />
        ) : null}
        <LabeledInput label="RPE (1–10)" value={rpe} onChangeText={setRpe} keyboardType="decimal-pad" disabled={busy} />
      </View>
      <LabeledInput label="Ghi chú hiệp" value={note} onChangeText={setNote} multiline disabled={busy} />
      {validation ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{validation}</Text> : null}
      {error ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{error}</Text> : null}
      <AppButton
        testID={`save-workout-set-${set.clientSetId}`}
        label={busy ? 'Đang lưu hiệp' : 'Lưu hiệp'}
        loading={busy}
        disabled={busy}
        onPress={save} icon="check"
      />
      {onRemove ? (
        <AppButton
          label="Hủy thêm hiệp này"
          disabled={busy}
          onPress={onRemove} variant="secondary" tone="danger" icon="trash"
        />
      ) : null}
    </View>
  );
}

function SetResult({ set }: { set: WorkoutSetExecution }) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const recorded = (value: number | string | null, suffix = '') =>
    value === null || value === '' ? 'Chưa ghi' : `${value}${suffix}`;
  const rows = [
    `Số lần: ${recorded(set.repetitions)}`,
    `Mức tải: ${set.loadValue === null ? 'Chưa ghi' : measurementText(set.loadValue, set.loadUnitId, set.loadUnit)}`,
    `Thời lượng: ${recorded(set.durationSeconds, ' giây')}`,
    `Quãng đường: ${set.distanceValue === null ? 'Chưa ghi' : measurementText(set.distanceValue, set.distanceUnitId, set.distanceUnit)}`,
    `RPE: ${recorded(set.rpe)}`,
    `RIR: ${recorded(set.rir)}`,
    `Nhịp thực hiện: ${recorded(set.tempo)}`,
    `Nghỉ sau hiệp: ${recorded(set.restAfterSeconds, ' giây')}`,
    `Ghi chú hiệp: ${recorded(set.note)}`,
  ];
  return (
    <View testID={`workout-set-${set.clientSetId}`} style={[local.setCard, { borderColor: theme.border, backgroundColor: theme.surfaceSubtle }]}>
      <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
        Hiệp {set.setNumber} · {workoutSetTypeLabels[set.setType]}
      </Text>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{workoutSetStatusLabels[set.completionStatus]}</Text>
      {rows.map((row) => <Text key={row} style={[styles.body, { color: theme.textSecondary }]}>{row}</Text>)}
      {set.completedAt ? <Text style={[styles.body, { color: theme.textSecondary }]}>Hoàn thành lúc: {formatInstantVietnam(set.completedAt) ?? 'Chưa rõ thời điểm'}</Text> : null}
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
  unitLabel: trailingUnit,
  disabled = false,
  helperText,
}: {
  label: string;
  value: string;
  onChangeText: (value: string) => void;
  keyboardType?: 'numeric' | 'decimal-pad';
  multiline?: boolean;
  unitLabel?: string;
  disabled?: boolean;
  helperText?: string | null;
}) {
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const accessibilityLabel = trailingUnit ? `${label}, ${trailingUnit}` : label;
  const input = (
    <TextInput
      accessibilityLabel={accessibilityLabel}
      accessibilityHint={helperText ?? undefined}
      accessibilityState={{ disabled }}
      editable={!disabled}
      value={value}
      onChangeText={onChangeText}
      keyboardType={keyboardType}
      multiline={multiline}
      style={[
        styles.input,
        keyboardType ? local.numericInput : null,
        trailingUnit ? local.inputWithUnitControl : null,
        multiline ? local.multiline : null,
        {
          color: theme.textPrimary,
          borderColor: theme.border,
          backgroundColor: disabled ? theme.surfaceSubtle : theme.surface,
        },
      ]}
    />
  );
  return (
    <View style={local.field}>
      <Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text>
      {trailingUnit ? (
        <View
          style={[
            local.inputWithUnit,
            {
              borderColor: theme.border,
              backgroundColor: disabled ? theme.surfaceSubtle : theme.surface,
            },
          ]}>
          {input}
          <Text
            accessibilityElementsHidden
            importantForAccessibility="no"
            style={[styles.caption, local.unitSuffix, { color: theme.textSecondary }]}>
            {trailingUnit}
          </Text>
        </View>
      ) : input}
      {helperText ? (
        <Text accessibilityRole="alert" style={[styles.caption, { color: theme.warningText }]}>
          {helperText}
        </Text>
      ) : null}
    </View>
  );
}

export function CurrentWorkoutExecutionScreen() {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const studentId = user?.id ?? '';
  const allowed = user?.status === 'ACTIVE' && activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const query = useCurrentWorkoutExecution(studentId, allowed);

  if (!allowed) {
    return <AppCard title="Không có quyền truy cập buổi tập" message="Buổi tập chỉ dành cho tài khoản đang hoạt động với hồ sơ Học viên. Hãy kiểm tra tài khoản và hồ sơ hiện tại." state="info" />;
  }
  if (query.isPending) return <AppCard title="Đang tải buổi tập" message="Vui lòng chờ trong giây lát." state="loading" />;
  if (query.isError && isCurrentExecutionMissing(query.error)) {
    return (
      <AppCard
        testID="current-workout-empty"
        title="Không có buổi tập đang diễn ra"
        message="Chưa có buổi tập nào được bắt đầu. Hãy quay lại khi lịch tập của bạn đã sẵn sàng."
        state="empty">
        <AppButton label="Xem lịch sử tập luyện" variant="secondary" icon="history" onPress={() => router.push('/workouts/history' as never)} />
      </AppCard>
    );
  }
  if (query.isError || !query.data) {
    return <AppCard testID="current-workout-error" title="Không thể tải buổi tập" message={workoutExecutionErrorMessage(query.error)} onRetry={() => query.refetch()} state="error" />;
  }
  if (query.data.execution.studentId !== studentId) {
    return <AppCard title="Không thể mở buổi tập" message="Hãy tải lại buổi tập trong hồ sơ Học viên của bạn." state="info" />;
  }
  return <WorkoutExecutionContent key={`${studentId}-${query.data.execution.executionId}`} detail={query.data} currentRoute onRefresh={() => query.refetch()} />;
}

export function WorkoutExecutionDetailScreen({ executionId }: { executionId: string }) {
  const { user, activeCapability } = useAuth();
  const studentId = user?.id ?? '';
  const allowed = user?.status === 'ACTIVE' && activeCapability === 'STUDENT' && Boolean(user?.capabilities?.hasStudentProfile);
  const query = useWorkoutExecutionDetail(studentId, executionId, allowed);

  if (!allowed) return <AppCard title="Không có quyền truy cập buổi tập" message="Buổi tập chỉ dành cho tài khoản đang hoạt động với hồ sơ Học viên. Hãy kiểm tra tài khoản và hồ sơ hiện tại." state="info" />;
  if (query.isPending) return <AppCard title="Đang tải chi tiết" message="Vui lòng chờ trong giây lát." state="loading" />;
  if (query.isError || !query.data) return <AppCard testID="workout-detail-error" title="Không thể mở buổi tập" message={workoutExecutionErrorMessage(query.error)} onRetry={() => query.refetch()} state="error" />;
  if (query.data.execution.studentId !== studentId) {
    return <AppCard title="Không thể mở buổi tập" message="Hãy tải lại buổi tập trong hồ sơ Học viên của bạn." state="info" />;
  }
  return <WorkoutExecutionContent key={`${studentId}-${query.data.execution.executionId}`} detail={query.data} onRefresh={() => query.refetch()} />;
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
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const theme = getSemanticColors(useColorScheme() === 'dark');
  const studentId = user?.id ?? '';
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
  const writable = inProgress && user?.status === 'ACTIVE' && activeCapability === 'STUDENT' &&
    Boolean(user?.capabilities?.hasStudentProfile) && studentId === detail.execution.studentId;
  const live = useRef({ detail, writable, studentId, mounted: true });
  useLayoutEffect(() => {
    live.current = { detail, writable, studentId, mounted: true };
    return () => { live.current.mounted = false; };
  }, [detail, writable, studentId]);
  const requestPending = useRef(false);
  // Check the latest rendered authority/lifecycle, even for callbacks retained by an old editor.
  const canWrite = () => {
    const cached = client.getQueryData<WorkoutExecutionDetail>(workoutExecutionQueryKeys.detail(studentId, detail.execution.executionId));
    const current = client.getQueryData<WorkoutExecutionDetail>(workoutExecutionQueryKeys.current(studentId));
    const known = [cached, current].filter((item): item is WorkoutExecutionDetail =>
      Boolean(item && item.execution.executionId === detail.execution.executionId));
    return live.current.mounted && live.current.writable &&
      live.current.studentId === studentId &&
      live.current.detail.execution.executionId === detail.execution.executionId &&
      live.current.detail.execution.version === detail.execution.version && !requestPending.current &&
      known.every((item) => item.execution.status === 'IN_PROGRESS' && item.execution.version <= detail.execution.version);
  };
  const sameScope = () => live.current.mounted && live.current.studentId === studentId &&
    live.current.detail.execution.executionId === detail.execution.executionId;

  const storeUpdated = async (updated: WorkoutExecutionDetail) => {
    if (!sameScope()) return false;
    const cached = client.getQueryData<WorkoutExecutionDetail>(
      workoutExecutionQueryKeys.detail(studentId, updated.execution.executionId)
    );
    const current = client.getQueryData<WorkoutExecutionDetail>(workoutExecutionQueryKeys.current(studentId));
    if (current && current.execution.executionId !== updated.execution.executionId &&
      updated.execution.status === 'IN_PROGRESS') {
      await onRefresh();
      return false;
    }
    const known = [live.current.detail, cached, current].filter((item): item is WorkoutExecutionDetail =>
      Boolean(item && item.execution.executionId === updated.execution.executionId));
    if (known.some((item) => item.execution.version > updated.execution.version ||
      (item.execution.status !== 'IN_PROGRESS' && updated.execution.status === 'IN_PROGRESS'))) {
      await onRefresh();
      return false;
    }
    client.setQueryData(
      workoutExecutionQueryKeys.detail(studentId, updated.execution.executionId),
      updated
    );
    if (updated.execution.status === 'IN_PROGRESS') {
      client.setQueryData(workoutExecutionQueryKeys.current(studentId), updated);
    } else {
      if (!current || current.execution.executionId === updated.execution.executionId) {
        client.removeQueries({ queryKey: workoutExecutionQueryKeys.current(studentId), exact: true });
      }
    }
    await client.invalidateQueries({ queryKey: workoutExecutionQueryKeys.history(studentId) });
    return sameScope();
  };

  const setMutation = useMutation({
    mutationFn: ({ clientSetId, input, version }: { clientSetId: string; input: UpsertWorkoutSetInput; version: number }) =>
      workoutExecutionApi.upsertSet(
        detail.execution.executionId,
        clientSetId,
        version,
        input
      ),
    onMutate: ({ clientSetId }) => {
      setSetError((current) => ({ ...current, [clientSetId]: '' }));
    },
    onSuccess: async (updated, variables) => {
      if (!await storeUpdated(updated)) return;
      setExtraSets((current) => {
        const next = { ...current };
        delete next[variables.clientSetId];
        return next;
      });
      AccessibilityInfo.announceForAccessibility('Đã lưu hiệp tập.');
    },
    onError: async (error, variables) => {
      if (!sameScope()) return;
      setSetError((current) => ({
        ...current,
        [variables.clientSetId]: workoutExecutionErrorMessage(error),
      }));
      if (isWorkoutExecutionStale(error)) await onRefresh();
    },
    onSettled: () => { requestPending.current = false; },
  });

  const substitutionMutation = useMutation({
    mutationFn: ({ variationId, exerciseId, version, reason }: { variationId: string; exerciseId: string; version: number; reason: string | null }) => {
      return workoutExecutionApi.substitute(
        detail.execution.executionId,
        exerciseId,
        version,
        {
          actualExerciseVariationId: variationId,
          substitutionReason: reason,
        }
      );
    },
    onSuccess: async (updated) => {
      if (!await storeUpdated(updated)) return;
      setSubstitutionTarget(null);
      setPickerSelection(null);
      setSelectedExercise(null);
      setSubstitutionReason('');
      substitutionMutation.reset();
      AccessibilityInfo.announceForAccessibility('Đã thay bài tập cho buổi tập này.');
    },
    onError: async (error) => {
      if (sameScope() && isWorkoutExecutionStale(error)) await onRefresh();
    },
    onSettled: () => { requestPending.current = false; },
  });

  const terminalMutation = useMutation({
    mutationFn: ({ action, input, key, version }: { action: 'complete' | 'abort'; input: FinishWorkoutExecutionInput; key: string; version: number }) =>
      action === 'complete'
        ? workoutExecutionApi.complete(detail.execution.executionId, version, input, key)
        : workoutExecutionApi.abort(detail.execution.executionId, version, input, key),
    onSuccess: async (updated) => {
      if (!await storeUpdated(updated)) return;
      setTerminal(null);
      terminalKey.current = null;
      AccessibilityInfo.announceForAccessibility(
        `Buổi tập ở trạng thái ${workoutExecutionStatusLabels[updated.execution.status]}.`
      );
      if (currentRoute) router.replace(`/workouts/${updated.execution.executionId}` as never);
    },
    onError: async (error) => {
      if (!sameScope()) return;
      if (isWorkoutExecutionStale(error)) {
        terminalKey.current = null;
        setTerminal(null);
        await onRefresh();
      }
    },
    onSettled: () => { requestPending.current = false; },
  });

  const busy = setMutation.isPending || substitutionMutation.isPending || terminalMutation.isPending;
  const saveSet = (clientSetId: string, input: UpsertWorkoutSetInput) => {
    if (!canWrite()) return;
    requestPending.current = true;
    const previous = setMutation.variables;
    const retrying = setMutation.isError && !isWorkoutExecutionStale(setMutation.error) &&
      previous?.clientSetId === clientSetId && JSON.stringify(previous.input) === JSON.stringify(input);
    setMutation.mutate(retrying ? previous : { clientSetId, input, version: detail.execution.version });
  };

  const addSet = (exercise: WorkoutExerciseExecution) => {
    if (!canWrite()) return;
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
          loadUnit: exercise.loadUnit ?? null,
          durationSeconds: null,
          distanceValue: null,
          distanceUnitId: exercise.distanceUnitId,
          distanceUnit: exercise.distanceUnit ?? null,
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
    if (!canWrite()) return;
    terminalMutation.reset();
    terminalKey.current = createCommandKey();
    setOverallRpe(detail.execution.overallRpe?.toString() ?? '');
    setSessionNote(detail.execution.sessionNote ?? '');
    setTerminalValidation(null);
    setTerminal(action);
  };

  const submitTerminal = () => {
    if (!terminal || !canWrite()) return;
    const parsedRpe = optionalNumber(overallRpe);
    if (parsedRpe === undefined || (parsedRpe !== null && (parsedRpe < 1 || parsedRpe > 10))) {
      setTerminalValidation('RPE toàn buổi phải từ 1 đến 10 hoặc để trống.');
      return;
    }
    const key = terminalKey.current ?? createCommandKey();
    terminalKey.current = key;
    requestPending.current = true;
    terminalMutation.mutate({
      action: terminal,
      key,
      version: terminalMutation.variables?.key === key ? terminalMutation.variables.version : detail.execution.version,
      input: { overallRpe: parsedRpe, sessionNote: sessionNote.trim() || null },
    });
  };

  return (
    <SafeAreaView style={[styles.screen, { backgroundColor: theme.canvas }]}><ScrollView
      testID="workout-execution-screen"
      style={[styles.screen, { backgroundColor: theme.canvas }]}
      contentContainerStyle={[styles.content, local.content]}
      automaticallyAdjustKeyboardInsets
      keyboardShouldPersistTaps="handled">
      <View style={local.heading}><AppIcon name="workout" color={theme.primary} /><Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary, flex: 1 }]}>
        {inProgress ? 'Buổi tập đang diễn ra' : 'Kết quả buổi tập'}
      </Text></View>
      <View style={styles.row}>
        <AppButton label="Quay lại" onPress={() => router.back()} variant="secondary" icon="back" />
        <AppButton label="Lịch sử tập luyện" onPress={() => router.push('/workouts/history' as never)} variant="secondary" icon="history" />
      </View>
      <AppCard testID="workout-execution-summary">
        <Text style={[styles.h2, { color: theme.textPrimary }]}>
          {workoutExecutionStatusLabels[detail.execution.status]}
        </Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>
          Bắt đầu thực tế: {detail.execution.performedStartedAt ? (formatInstantVietnam(detail.execution.performedStartedAt) ?? 'Chưa rõ thời điểm') : 'Chưa có dữ liệu'}
        </Text>
        <Text style={[styles.body, { color: theme.textSecondary }]}>
          Thời gian dự kiến: {detail.execution.plannedStartAt ? (formatRecordedScheduleTime(detail.execution.plannedStartAt, null) ?? 'Chưa rõ thời điểm') : 'Chưa có dữ liệu'}
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
        {!inProgress ? <Text style={[styles.body, { color: theme.textSecondary }]}>Buổi tập đã kết thúc. Chỉ có thể xem các hiệp đã ghi.</Text> : null}
        {inProgress && !writable ? <Text style={[styles.body, { color: theme.textSecondary }]}>Bạn chỉ có quyền xem buổi tập này.</Text> : null}
        {inProgress ? <ElapsedTime startedAt={detail.execution.performedStartedAt} /> : null}
        {detail.execution.performedEndedAt ? (
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Kết thúc thực tế: {formatInstantVietnam(detail.execution.performedEndedAt) ?? 'Chưa rõ thời điểm'}
          </Text>
        ) : null}
      </AppCard>

      {detail.exercises.length === 0 ? (
        <AppCard title="Không có chi tiết bài tập" message="Bản ghi này không chứa dữ liệu bài tập có thể hiển thị." state="empty" />
      ) : null}
      {detail.exercises.map((exercise, index) => {
        const added = (writable ? Object.values(extraSets) : [])
          .filter((item) => item.exerciseExecutionId === exercise.exerciseExecutionId)
          .map((item) => item.set);
        return (
          <AppCard key={exercise.exerciseExecutionId} testID={`workout-exercise-${exercise.exerciseExecutionId}`}>
            <View style={local.heading}><AppIcon name="exercise" color={theme.primary} /><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary, flex: 1 }]}>
              {index + 1}. {displayExercise(exercise)}
            </Text></View>
            <Text style={[styles.body, { color: theme.textSecondary }]}>{targetText(exercise)}</Text>
            {exercise.instructions ? <Text style={[styles.body, { color: theme.textPrimary }]}>{exercise.instructions}</Text> : null}
            {exercise.substitutionReason ? <Text style={[styles.body, { color: theme.warningText }]}>Lý do thay bài: {exercise.substitutionReason}</Text> : null}
            {[...exercise.sets, ...added].map((set) => writable ? (
              <SetEditor
                key={`${set.clientSetId}-${detail.execution.version}`}
                exercise={exercise}
                set={set}
                busy={busy}
                canSave={canWrite}
                error={setError[set.clientSetId] || null}
                onSave={saveSet}
                onRemove={extraSets[set.clientSetId] ? () => {
                  if (!canWrite()) return;
                  setExtraSets((current) => {
                    const next = { ...current };
                    delete next[set.clientSetId];
                    return next;
                  });
                } : undefined}
              />
            ) : <SetResult key={set.clientSetId} set={set} />)}
            {writable ? (
              <View style={styles.row}>
                <AppButton label="Thêm hiệp" disabled={busy} onPress={() => addSet(exercise)} variant="secondary" icon="plus" />
                <AppButton
                  label="Thay bài tập"
                  disabled={busy}
                  onPress={() => {
                    if (!canWrite()) return;
                    substitutionMutation.reset();
                    setSubstitutionTarget(exercise);
                    setPickerSelection(null);
                    setSelectedExercise(null);
                    setSubstitutionReason('');
                  }} variant="secondary" icon="edit"
                />
              </View>
            ) : null}
          </AppCard>
        );
      })}

      {writable ? (
        <AppCard>
          <Text style={[styles.h2, { color: theme.textPrimary }]}>Kết thúc buổi tập</Text>
          <Text style={[styles.body, { color: theme.textSecondary }]}>
            Hệ thống sẽ xác định kết quả hoàn thành hoặc hoàn thành một phần từ các hiệp đã ghi.
          </Text>
          <View style={styles.row}>
            <AppButton testID="complete-workout-button" label="Hoàn thành buổi tập" disabled={busy} onPress={() => openTerminal('complete')} icon="check" />
            <AppButton testID="abort-workout-button" label="Hủy buổi tập" disabled={busy} onPress={() => openTerminal('abort')} variant="secondary" tone="danger" />
          </View>
        </AppCard>
      ) : null}

      <Modal
        visible={writable && substitutionTarget !== null}
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
            <ScrollView contentContainerStyle={[styles.content, local.content]} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets>
              <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>Chọn biến thể</Text>
              <Text style={[styles.body, { color: theme.textSecondary }]}>{selectedExercise.name}</Text>
              <LabeledInput label="Lý do thay bài (không bắt buộc)" disabled={busy} value={substitutionReason} onChangeText={(value) => { setSubstitutionReason(value); }} multiline />
              {exerciseDetail.isPending ? <AppCard title="Đang tải biến thể" message="Vui lòng chờ trong giây lát." state="loading" /> : null}
              {exerciseDetail.isError ? <AppCard title="Không thể tải biến thể" message="Hãy thử lại." onRetry={() => exerciseDetail.refetch()} state="error" /> : null}
              {exerciseDetail.data?.variations.map((variation) => (
                <AppButton
                  key={variation.id}
                  label={`${variation.name}${variation.defaultVariation ? ' · Mặc định' : ''}`}
                  loading={substitutionMutation.isPending}
                  disabled={substitutionMutation.isPending}
                  onPress={() => {
                    if (!canWrite() || !substitutionTarget) return;
                    requestPending.current = true;
                    substitutionMutation.mutate({ variationId: variation.id, exerciseId: substitutionTarget.exerciseExecutionId,
                      version: detail.execution.version, reason: substitutionReason.trim() || null });
                  }} variant="secondary"
                />
              ))}
              {substitutionMutation.isError ? <AppCard title="Chưa thể thay bài tập" message={workoutExecutionErrorMessage(substitutionMutation.error)} state="error" /> : null}
              <AppButton label="Chọn bài tập khác" disabled={substitutionMutation.isPending} onPress={() => { setSelectedExercise(null); setPickerSelection(null); }} variant="secondary" icon="back" />
              <AppButton label="Hủy thay bài" disabled={substitutionMutation.isPending} onPress={() => { substitutionMutation.reset(); setSubstitutionTarget(null); setPickerSelection(null); }} variant="secondary" icon="back" />
            </ScrollView>
          )}
        </SafeAreaView>
      </Modal>

      <Modal visible={writable && terminal !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={() => !terminalMutation.isPending && setTerminal(null)}>
        <SafeAreaView style={{ flex: 1, backgroundColor: theme.canvas }}><KeyboardAvoidingView style={[local.backdrop, { backgroundColor: theme.canvas }]} behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
          <ScrollView style={local.modalScroll} keyboardShouldPersistTaps="handled" automaticallyAdjustKeyboardInsets>
          <AppCard testID="workout-terminal-confirmation">
            <Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>
              {terminal === 'complete' ? 'Xác nhận hoàn thành' : 'Xác nhận hủy buổi tập'}
            </Text>
            <LabeledInput label="RPE toàn buổi (1–10, không bắt buộc)" disabled={busy} value={overallRpe} onChangeText={(value) => { terminalKey.current = null; setOverallRpe(value); }} keyboardType="decimal-pad" />
            <LabeledInput label="Ghi chú buổi tập" disabled={busy} value={sessionNote} onChangeText={(value) => { terminalKey.current = null; setSessionNote(value); }} multiline />
            {terminalValidation ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>{terminalValidation}</Text> : null}
            {terminalMutation.isError ? <AppCard title="Chưa thể kết thúc buổi tập" message={workoutExecutionErrorMessage(terminalMutation.error)} state="error" /> : null}
            <View style={styles.row}>
              <AppButton label="Quay lại" disabled={terminalMutation.isPending} onPress={() => { setTerminal(null); terminalKey.current = null; }} variant="secondary" icon="back" />
              <AppButton
                testID="confirm-workout-terminal"
                label={terminalMutation.isPending ? 'Đang xử lý' : 'Xác nhận'}
                loading={terminalMutation.isPending}
                disabled={terminalMutation.isPending}
                onPress={submitTerminal} variant={terminal === 'abort' ? 'danger' : 'primary'} icon="check"
              />
            </View>
          </AppCard>
          </ScrollView>
        </KeyboardAvoidingView></SafeAreaView>
      </Modal>
    </ScrollView></SafeAreaView>
  );
}

const local = StyleSheet.create({
  content: { width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' },
  heading: { flexDirection: 'row', gap: spacing.md, alignItems: 'center' },
  modalScroll: { flexGrow: 0, width: '100%', maxWidth: layout.mobileContentMaxWidth, alignSelf: 'center' },
  setCard: { borderWidth: 1, borderRadius: radius.md, padding: spacing.md, gap: spacing.md },
  choice: {
    minHeight: layout.minimumTouchTarget,
    minWidth: layout.minimumTouchTarget,
    maxWidth: '100%',
    borderWidth: 1,
    borderRadius: radius.full,
    paddingHorizontal: spacing.md,
    alignItems: 'center',
    justifyContent: 'center',
    flexDirection: 'row',
    gap: spacing.xs,
  },
  fieldGrid: { flexDirection: 'row', flexWrap: 'wrap', gap: spacing.sm },
  field: { minWidth: 0, maxWidth: '100%', flexGrow: 1, flexBasis: '45%', gap: spacing.sm },
  numericInput: { ...typography.h3, fontVariant: ['tabular-nums'] },
  inputWithUnit: {
    minHeight: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    flexDirection: 'row',
    alignItems: 'center',
  },
  inputWithUnitControl: { flex: 1, minWidth: 0, borderWidth: 0, backgroundColor: 'transparent' },
  unitSuffix: { paddingRight: spacing.md, maxWidth: '55%', flexShrink: 1 },
  multiline: { minHeight: 88, paddingVertical: spacing.md, textAlignVertical: 'top' },
  modalScreen: { flex: 1 },
  backdrop: { flex: 1, justifyContent: 'center', padding: spacing.lg },
});
