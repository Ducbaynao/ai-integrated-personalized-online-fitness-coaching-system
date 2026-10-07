import React, { useEffect, useMemo, useRef, useState } from 'react';
import { AccessibilityInfo, Alert, Modal, Pressable, ScrollView, StyleSheet, Text, TextInput, View, useColorScheme } from 'react-native';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { useRelationshipDetail, useSharingSummary } from '@/features/coaching/coachingQueries';
import { ExercisePicker } from '@/features/exercise/ExercisePicker';
import { useExerciseDetail } from '@/features/exercise/exerciseQueries';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import { createCommandKey } from '@/services/coachingApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { ExerciseSummary } from '@/types/exercise';
import { WorkoutExerciseState, WorkoutPlanSummary, WorkoutPlanVersionDetail, WorkoutPrescriptionInput, WorkoutSessionInput } from '@/types/workoutPlan';
import { WorkoutButton, WorkoutCard, WorkoutState, workoutStyles as styles } from './components/WorkoutUi';
import { removeStudentWorkoutPlanCache } from './workoutPlanCache';
import { isWorkoutAuthorityLoss, isWorkoutStaleConflict, workoutPlanErrorMessage } from './workoutPlanMessages';
import { useWorkoutPlan, useWorkoutPlanVersion, workoutPlanQueryKeys } from './workoutPlanQueries';

type Mode = 'create' | 'draft' | 'publish';
type Actor = 'STUDENT' | 'TRAINER';
type EditorPrescription = WorkoutPrescriptionInput & { exerciseId?: string; exerciseName?: string; variationName?: string; exerciseState?: WorkoutExerciseState };
type EditorSession = Omit<WorkoutSessionInput, 'prescriptions'> & { localId: string; prescriptions: EditorPrescription[] };

interface BuilderProps { mode: Mode; planId?: string; versionId?: string; relationshipId?: string; studentId?: string }

const newSession = (index: number): EditorSession => ({ localId: createCommandKey(), weekNumber: 1, dayNumber: index + 1, sequenceNumber: index + 1, name: `Buổi ${index + 1}`, focus: null, estimatedDurationMinutes: null, notes: null, prescriptions: [] });
function move<T>(items: T[], from: number, to: number): T[] { if (to < 0 || to >= items.length) return items; const result = [...items]; const [item] = result.splice(from, 1); result.splice(to, 0, item); return result; }
function mapVersion(detail?: WorkoutPlanVersionDetail): EditorSession[] {
  return detail?.sessions.map((session) => ({ localId: session.id, weekNumber: session.weekNumber, dayNumber: session.dayNumber, sequenceNumber: session.sequenceNumber, name: session.name, focus: session.focus, estimatedDurationMinutes: session.estimatedDurationMinutes, notes: session.notes, prescriptions: session.prescriptions.map((item) => ({ exerciseVariationId: item.exerciseVariationId, sequenceNumber: item.sequenceNumber, targetSets: item.targetSets, targetRepsMin: item.targetRepsMin, targetRepsMax: item.targetRepsMax, targetLoad: item.targetLoad, restSeconds: item.restSeconds, durationSeconds: item.durationSeconds, instructions: item.instructions, exerciseId: item.exercise.exerciseId ?? undefined, exerciseName: item.exercise.exerciseName ?? undefined, variationName: item.exercise.variationName ?? undefined, exerciseState: item.exercise.state })) })) ?? [];
}
function toInput(sessions: EditorSession[]): WorkoutSessionInput[] {
  return sessions.map((session, index) => ({ weekNumber: session.weekNumber, dayNumber: session.dayNumber, sequenceNumber: index + 1, name: session.name.trim(), focus: session.focus?.trim() || null, estimatedDurationMinutes: session.estimatedDurationMinutes, notes: session.notes?.trim() || null, prescriptions: session.prescriptions.map((item, itemIndex) => ({ exerciseVariationId: item.exerciseVariationId, sequenceNumber: itemIndex + 1, targetSets: item.targetSets, targetRepsMin: item.targetRepsMin, targetRepsMax: item.targetRepsMax, targetLoad: item.targetLoad, restSeconds: item.restSeconds, durationSeconds: item.durationSeconds, instructions: item.instructions?.trim() || null })) }));
}

export function WorkoutPlanBuilderScreen(props: BuilderProps) {
  if (props.relationshipId && props.studentId) return <TrainerWorkoutPlanBuilder {...props} relationshipId={props.relationshipId} studentId={props.studentId} />;
  return <StudentWorkoutPlanBuilder {...props} />;
}

function StudentWorkoutPlanBuilder({ mode, planId = '', versionId = '' }: BuilderProps) {
  const { user, activeCapability } = useAuth();
  const studentId = user?.id ?? '';
  const planQuery = useWorkoutPlan(studentId, planId, mode !== 'create');
  const plan = planQuery.data?.plan;
  const effectiveVersionId = versionId || planQuery.data?.currentVersion?.id || '';
  const versionQuery = useWorkoutPlanVersion(studentId, planId, effectiveVersionId, mode !== 'create');
  if (activeCapability !== 'STUDENT' || !user?.capabilities?.hasStudentProfile) return <WorkoutState title="Chỉ dành cho Học viên" message="Hãy chuyển sang hồ sơ Học viên để chỉnh sửa kế hoạch." />;
  if (mode === 'create') return <WorkoutPlanEditor mode={mode} actor="STUDENT" studentId={studentId} />;
  if (planQuery.isPending || versionQuery.isPending) return <WorkoutState busy title="Đang tải trình chỉnh sửa" message="Vui lòng chờ trong giây lát." />;
  if (planQuery.isError || versionQuery.isError || !plan || !versionQuery.data) return <WorkoutState title="Không thể mở trình chỉnh sửa" message={workoutPlanErrorMessage(planQuery.error ?? versionQuery.error)} onRetry={() => { planQuery.refetch(); versionQuery.refetch(); }} />;
  const allowed = plan.decisionOwnerType === 'STUDENT' && plan.decisionOwnerId === studentId && plan.readContext === 'CURRENT' && (mode === 'draft' ? plan.status === 'DRAFT' : ['ACTIVE', 'PAUSED'].includes(plan.status));
  if (!allowed) return <WorkoutState testID="builder-denied" title="Không thể chỉnh sửa trực tiếp" message="Kế hoạch không thuộc quyền quyết định hiện tại của bạn. Kế hoạch Trainer-authored cần flow kế nhiệm riêng." />;
  return <WorkoutPlanEditor key={`STUDENT-${mode}-${effectiveVersionId}-${plan.aggregateVersion}`} mode={mode} actor="STUDENT" studentId={studentId} plan={plan} version={versionQuery.data} />;
}

function TrainerWorkoutPlanBuilder({ mode, relationshipId, studentId, planId = '', versionId = '' }: BuilderProps & { relationshipId: string; studentId: string }) {
  const { user, activeCapability } = useAuth();
  const router = useRouter();
  const client = useQueryClient();
  const relationshipQuery = useRelationshipDetail(relationshipId);
  const sharingQuery = useSharingSummary(relationshipId);
  const planQuery = useWorkoutPlan(studentId, planId, mode !== 'create');
  const plan = planQuery.data?.plan;
  const effectiveVersionId = versionId || planQuery.data?.currentVersion?.id || '';
  const versionQuery = useWorkoutPlanVersion(studentId, planId, effectiveVersionId, mode !== 'create');
  const authorityError = relationshipQuery.error ?? sharingQuery.error ?? planQuery.error ?? versionQuery.error;

  useEffect(() => {
    if (isWorkoutAuthorityLoss(authorityError)) {
      removeStudentWorkoutPlanCache(client, studentId);
      router.replace('/coaching' as never);
    }
  }, [authorityError, client, router, studentId]);

  if (activeCapability !== 'TRAINER') return <WorkoutState title="Chỉ dành cho Huấn luyện viên" message="Hãy chuyển sang hồ sơ Huấn luyện viên để chỉnh sửa kế hoạch." />;
  if (relationshipQuery.isPending || sharingQuery.isPending || (mode !== 'create' && (planQuery.isPending || versionQuery.isPending))) return <WorkoutState busy title="Đang tải trình chỉnh sửa" message="Vui lòng chờ trong giây lát." />;
  if (relationshipQuery.isError || sharingQuery.isError || (mode !== 'create' && (planQuery.isError || versionQuery.isError))) return <WorkoutState title="Không thể mở trình chỉnh sửa" message={workoutPlanErrorMessage(authorityError)} onRetry={() => { relationshipQuery.refetch(); sharingQuery.refetch(); if (mode !== 'create') { planQuery.refetch(); versionQuery.refetch(); } }} />;

  const relationship = relationshipQuery.data?.relationship;
  const currentPeriod = relationshipQuery.data?.currentPeriod;
  const permission = sharingQuery.data?.items.find((item) => item.dataScope === 'WORKOUT_PLAN');
  const relationshipValid = relationship?.trainerId === user?.id && relationship?.studentId === studentId && relationship?.status === 'ACTIVE' && currentPeriod?.mode === 'HUMAN_COACH';
  const canManage = permission?.state === 'ALLOWED' && permission.accessLevel === 'MANAGE';
  const ownerValid = mode === 'create' || (plan?.studentId === studentId && plan.decisionOwnerType === 'TRAINER' && plan.decisionOwnerId === user?.id && plan.readContext === 'CURRENT');
  const stateValid = mode === 'create' || (mode === 'draft' ? plan?.status === 'DRAFT' : ['ACTIVE', 'PAUSED'].includes(plan?.status ?? ''));
  if (!relationshipValid || !canManage || !ownerValid || !stateValid || (mode !== 'create' && (!plan || !versionQuery.data))) return <WorkoutState testID="builder-denied" title="Không thể chỉnh sửa kế hoạch" message="Quan hệ HUMAN_COACH, quyền Quản lý, quyền tác giả hoặc trạng thái kế hoạch không còn hợp lệ." />;
  return <WorkoutPlanEditor key={`TRAINER-${mode}-${effectiveVersionId}-${plan?.aggregateVersion ?? 0}`} mode={mode} actor="TRAINER" relationshipId={relationshipId} studentId={studentId} plan={plan} version={versionQuery.data} />;
}

function WorkoutPlanEditor({ mode, actor, studentId, relationshipId, plan, version }: { mode: Mode; actor: Actor; studentId: string; relationshipId?: string; plan?: WorkoutPlanSummary; version?: WorkoutPlanVersionDetail }) {
  const router = useRouter(); const client = useQueryClient(); const theme = getSemanticColors(useColorScheme() === 'dark');
  const [name, setName] = useState(plan?.name ?? ''); const [description, setDescription] = useState(plan?.description ?? '');
  const [reason, setReason] = useState(''); const [summary, setSummary] = useState(''); const [sessions, setSessions] = useState<EditorSession[]>(() => mapVersion(version));
  const [pickerSession, setPickerSession] = useState<number | null>(null); const [selectedExercise, setSelectedExercise] = useState<ExerciseSummary | null>(null); const [variationExercise, setVariationExercise] = useState<ExerciseSummary | null>(null);
  const exerciseDetail = useExerciseDetail(variationExercise?.id); const commandKey = useRef<string | null>(null);
  const dirty = () => { commandKey.current = null; };
  const updateSession = (index: number, patch: Partial<EditorSession>) => { dirty(); setSessions((items) => items.map((item, i) => i === index ? { ...item, ...patch } : item)); };
  const updatePrescription = (sessionIndex: number, itemIndex: number, patch: Partial<EditorPrescription>) => { dirty(); setSessions((items) => items.map((session, i) => i !== sessionIndex ? session : { ...session, prescriptions: session.prescriptions.map((item, j) => j === itemIndex ? { ...item, ...patch } : item) })); };
  const valid = useMemo(() => {
    if (!name.trim() || name.trim().length > 200) return false;
    if (mode === 'publish' && (!reason.trim() || reason.trim().length > 100 || sessions.length === 0)) return false;
    return sessions.every((session) => {
      if (!session.name.trim() || session.name.trim().length > 180 || session.weekNumber < 1 || session.dayNumber < 1) return false;
      if (session.estimatedDurationMinutes !== null && session.estimatedDurationMinutes < 1) return false;
      return session.prescriptions.every((item) => Boolean(item.exerciseVariationId)
        && (item.exerciseState === undefined || item.exerciseState === 'ACTIVE')
        && (item.targetSets === null || item.targetSets > 0)
        && (item.targetRepsMin === null || item.targetRepsMin >= 0)
        && (item.targetRepsMax === null || item.targetRepsMax >= 0)
        && (item.targetRepsMin === null || item.targetRepsMax === null || item.targetRepsMax >= item.targetRepsMin)
        && (item.targetLoad === null || item.targetLoad >= 0)
        && (item.restSeconds === null || item.restSeconds >= 0)
        && (item.durationSeconds === null || item.durationSeconds >= 0));
    });
  }, [mode, name, reason, sessions]);
  const mutation = useMutation({
    mutationFn: async () => {
      const key = commandKey.current ?? createCommandKey(); commandKey.current = key;
      const content = { name: name.trim(), description: description.trim() || null, sessions: toInput(sessions) };
      if (mode === 'create') return workoutPlanApi.create(studentId, content, key);
      if (!plan) throw new Error('Missing plan');
      return mode === 'draft' ? workoutPlanApi.updateDraft(plan.id, plan.aggregateVersion, content, key) : workoutPlanApi.publish(plan.id, plan.aggregateVersion, { reason: reason.trim(), summary: summary.trim() || null, sessions: content.sessions }, key);
    },
    onSuccess: async (result) => {
      commandKey.current = null;
      await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
      AccessibilityInfo.announceForAccessibility(mode === 'publish' ? 'Phiên bản chiến lược mới đã được phát hành.' : 'Bản nháp đã được lưu.');
      router.replace(actor === 'TRAINER' ? `/students/${studentId}/workout-plans/${result.planId}?relationshipId=${relationshipId}` as never : `/workout-plans/${result.planId}` as never);
    },
    onError: async (error) => {
      if (actor === 'TRAINER' && isWorkoutAuthorityLoss(error)) {
        removeStudentWorkoutPlanCache(client, studentId);
        router.replace('/coaching' as never);
      } else if (isWorkoutStaleConflict(error) && plan) {
        commandKey.current = null;
        await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.detail(studentId, plan.id) });
      }
    },
  });
  const addVariation = (variationId: string, variationName: string) => { if (pickerSession === null || !variationExercise) return; const item: EditorPrescription = { exerciseVariationId: variationId, sequenceNumber: sessions[pickerSession].prescriptions.length + 1, targetSets: 3, targetRepsMin: 8, targetRepsMax: 12, targetLoad: null, restSeconds: 60, durationSeconds: null, instructions: null, exerciseId: variationExercise.id, exerciseName: variationExercise.name, variationName, exerciseState: 'ACTIVE' }; updateSession(pickerSession, { prescriptions: [...sessions[pickerSession].prescriptions, item] }); AccessibilityInfo.announceForAccessibility(`Đã thêm ${variationExercise.name}, ${variationName}`); setPickerSession(null); setVariationExercise(null); setSelectedExercise(null); };
  const discard = () => Alert.alert('Bỏ thay đổi?', 'Các thay đổi chưa lưu sẽ bị mất.', [{ text: 'Tiếp tục chỉnh sửa', style: 'cancel' }, { text: 'Bỏ thay đổi', style: 'destructive', onPress: () => router.back() }]);

  return <ScrollView testID="workout-plan-builder-screen" style={[styles.screen, { backgroundColor: theme.canvas }]} contentContainerStyle={styles.content} keyboardShouldPersistTaps="handled">
    <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>{mode === 'create' ? actor === 'TRAINER' ? 'Tạo kế hoạch cho Học viên' : 'Tạo kế hoạch tự tập' : mode === 'draft' ? 'Chỉnh sửa bản nháp' : 'Tạo phiên bản chiến lược mới'}</Text>
    {mode === 'publish' ? <WorkoutState title="Thay đổi chiến lược" message="Các buổi tập đã được tạo trước đó sẽ không tự động bị thay đổi. Phiên bản cũ vẫn là lịch sử bất biến." /> : null}
    <WorkoutCard><Field inputTestID={actor === 'TRAINER' ? 'plan-name-input' : undefined} label="Tên kế hoạch" value={name} maxLength={200} onChange={(value) => { dirty(); setName(value); }} /><Field label="Mô tả kế hoạch" value={description} multiline onChange={(value) => { dirty(); setDescription(value); }} />{mode === 'publish' ? <><Field label="Lý do thay đổi" value={reason} maxLength={100} onChange={(value) => { dirty(); setReason(value); }} /><Field label="Tóm tắt thay đổi" value={summary} multiline onChange={(value) => { dirty(); setSummary(value); }} /></> : null}</WorkoutCard>
    {sessions.map((session, sessionIndex) => <WorkoutCard key={session.localId} testID={`builder-session-${sessionIndex}`}><Text style={[styles.h2, { color: theme.textPrimary }]}>Buổi {sessionIndex + 1}</Text><Field label={`Tên buổi ${sessionIndex + 1}`} value={session.name} maxLength={180} onChange={(value) => updateSession(sessionIndex, { name: value })} /><View style={styles.row}><NumberField label="Tuần" value={session.weekNumber} min={1} onChange={(value) => updateSession(sessionIndex, { weekNumber: value ?? 1 })} /><NumberField label="Ngày" value={session.dayNumber} min={1} onChange={(value) => updateSession(sessionIndex, { dayNumber: value ?? 1 })} /><NumberField label="Phút" value={session.estimatedDurationMinutes} min={1} onChange={(value) => updateSession(sessionIndex, { estimatedDurationMinutes: value })} /></View><Field label="Trọng tâm" value={session.focus ?? ''} maxLength={160} onChange={(value) => updateSession(sessionIndex, { focus: value })} /><Field label="Ghi chú buổi tập" value={session.notes ?? ''} multiline onChange={(value) => updateSession(sessionIndex, { notes: value })} />
      {session.prescriptions.map((item, itemIndex) => <View key={`${item.exerciseVariationId}-${itemIndex}`} style={[local.prescription, { borderColor: theme.border }]}><Text style={[styles.label, { color: theme.textPrimary }]}>{itemIndex + 1}. {item.exerciseName ?? 'Exercise'}{item.variationName ? ` · ${item.variationName}` : ''}</Text>{item.exerciseState && item.exerciseState !== 'ACTIVE' ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.warningText }]}>Exercise lịch sử này {item.exerciseState === 'ARCHIVED' ? 'đã lưu trữ' : 'không còn khả dụng'}; hãy xóa và chọn Exercise active khác trước khi lưu.</Text> : null}<View style={styles.row}><NumberField label="Hiệp" value={item.targetSets} min={1} onChange={(value) => updatePrescription(sessionIndex, itemIndex, { targetSets: value })} /><NumberField label="Reps từ" value={item.targetRepsMin} min={0} onChange={(value) => updatePrescription(sessionIndex, itemIndex, { targetRepsMin: value })} /><NumberField label="Reps đến" value={item.targetRepsMax} min={0} onChange={(value) => updatePrescription(sessionIndex, itemIndex, { targetRepsMax: value })} /><DecimalField label="Mức tạ" value={item.targetLoad} onChange={(value) => updatePrescription(sessionIndex, itemIndex, { targetLoad: value })} /><NumberField label="Nghỉ giây" value={item.restSeconds} min={0} onChange={(value) => updatePrescription(sessionIndex, itemIndex, { restSeconds: value })} /><NumberField label="Thời lượng giây" value={item.durationSeconds} min={0} onChange={(value) => updatePrescription(sessionIndex, itemIndex, { durationSeconds: value })} /></View><Field label={`Hướng dẫn bài tập ${itemIndex + 1}`} value={item.instructions ?? ''} multiline onChange={(value) => updatePrescription(sessionIndex, itemIndex, { instructions: value })} /><View style={styles.row}><WorkoutButton label="Đưa lên" secondary disabled={itemIndex === 0} onPress={() => updateSession(sessionIndex, { prescriptions: move(session.prescriptions, itemIndex, itemIndex - 1) })} /><WorkoutButton label="Đưa xuống" secondary disabled={itemIndex === session.prescriptions.length - 1} onPress={() => updateSession(sessionIndex, { prescriptions: move(session.prescriptions, itemIndex, itemIndex + 1) })} /><WorkoutButton label="Xóa bài tập" danger secondary onPress={() => updateSession(sessionIndex, { prescriptions: session.prescriptions.filter((_, i) => i !== itemIndex) })} /></View></View>)}
      <WorkoutButton label="Thêm bài tập" secondary onPress={() => { setPickerSession(sessionIndex); setSelectedExercise(null); }} /><View style={styles.row}><WorkoutButton label="Đưa buổi lên" secondary disabled={sessionIndex === 0} onPress={() => { dirty(); setSessions((items) => move(items, sessionIndex, sessionIndex - 1)); }} /><WorkoutButton label="Đưa buổi xuống" secondary disabled={sessionIndex === sessions.length - 1} onPress={() => { dirty(); setSessions((items) => move(items, sessionIndex, sessionIndex + 1)); }} /><WorkoutButton label="Xóa buổi" danger secondary onPress={() => { dirty(); setSessions((items) => items.filter((_, i) => i !== sessionIndex)); }} /></View></WorkoutCard>)}
    <WorkoutButton testID="add-session-button" label="Thêm buổi tập" secondary onPress={() => { dirty(); setSessions((items) => [...items, newSession(items.length)]); }} />
    {!valid ? <Text accessibilityRole="alert" style={[styles.body, { color: theme.dangerText }]}>Kiểm tra tên, Exercise đang khả dụng và các giá trị. Số hiệp phải lớn hơn 0; reps, mức tạ, nghỉ và thời lượng có thể bằng 0 nhưng không được âm; reps tối đa không nhỏ hơn reps tối thiểu.{mode === 'publish' ? ' Phiên bản mới cần lý do và ít nhất một buổi.' : ''}</Text> : null}
    {mutation.isError ? <WorkoutState testID="builder-error" title="Chưa thể lưu kế hoạch" message={workoutPlanErrorMessage(mutation.error)} /> : null}<View style={styles.row}><WorkoutButton label="Hủy" secondary disabled={mutation.isPending} onPress={discard} /><WorkoutButton testID={actor === 'TRAINER' ? 'save-plan-button' : 'save-workout-plan-button'} label={mutation.isPending ? 'Đang lưu' : mode === 'publish' ? 'Phát hành phiên bản' : 'Lưu bản nháp'} busy={mutation.isPending} disabled={!valid || mutation.isPending} onPress={() => mutation.mutate()} /></View>
    <Modal visible={pickerSession !== null && !variationExercise} animationType="slide" accessibilityViewIsModal onRequestClose={() => { setPickerSession(null); setSelectedExercise(null); }}><ExercisePicker selectedExercise={selectedExercise} onSelectionChange={setSelectedExercise} onConfirm={setVariationExercise} onCancel={() => { setPickerSession(null); setSelectedExercise(null); }} /></Modal>
    <Modal visible={variationExercise !== null} transparent animationType="fade" accessibilityViewIsModal onRequestClose={() => setVariationExercise(null)}><View style={local.backdrop}><WorkoutCard testID="variation-picker"><Text accessibilityRole="header" style={[styles.h2, { color: theme.textPrimary }]}>Chọn biến thể của {variationExercise?.name}</Text>{exerciseDetail.isPending ? <WorkoutState busy title="Đang tải biến thể" message="Vui lòng chờ trong giây lát." /> : null}{exerciseDetail.isError ? <WorkoutState title="Không thể tải biến thể" message="Hãy thử lại." onRetry={() => exerciseDetail.refetch()} /> : null}{exerciseDetail.data?.variations.map((variation) => <Pressable key={variation.id} accessibilityRole="button" accessibilityLabel={`Chọn biến thể ${variation.name}`} onPress={() => addVariation(variation.id, variation.name)} style={[local.variation, { borderColor: theme.border }]}><Text style={[styles.label, { color: theme.textPrimary }]}>{variation.name}</Text>{variation.defaultVariation ? <Text style={[styles.body, { color: theme.textSecondary }]}>Biến thể mặc định</Text> : null}</Pressable>)}<WorkoutButton label="Quay lại chọn Exercise" secondary onPress={() => setVariationExercise(null)} /></WorkoutCard></View></Modal>
  </ScrollView>;
}

function Field({ inputTestID, label, value, onChange, maxLength, multiline = false }: { inputTestID?: string; label: string; value: string; onChange: (value: string) => void; maxLength?: number; multiline?: boolean }) { const theme = getSemanticColors(useColorScheme() === 'dark'); return <View><Text style={[styles.label, { color: theme.textPrimary }]}>{label}</Text><TextInput testID={inputTestID} accessibilityLabel={label} value={value} onChangeText={onChange} maxLength={maxLength} multiline={multiline} style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]} /></View>; }
function NumberField({ label, value, min, onChange }: { label: string; value: number | null; min: number; onChange: (value: number | null) => void }) { const theme = getSemanticColors(useColorScheme() === 'dark'); return <View style={local.number}><Text style={[styles.body, { color: theme.textSecondary }]}>{label}</Text><TextInput accessibilityLabel={label} keyboardType="numeric" value={value === null ? '' : String(value)} onChangeText={(text) => { if (!text.trim()) onChange(null); else if (/^\d+$/.test(text)) onChange(Math.max(min, Number(text))); }} style={[local.numberInput, { color: theme.textPrimary, borderColor: theme.border }]} /></View>; }
function DecimalField({ label, value, onChange }: { label: string; value: number | null; onChange: (value: number | null) => void }) { const theme = getSemanticColors(useColorScheme() === 'dark'); return <View style={local.number}><Text style={[styles.body, { color: theme.textSecondary }]}>{label}</Text><TextInput accessibilityLabel={label} keyboardType="decimal-pad" value={value === null ? '' : String(value)} onChangeText={(text) => { if (!text.trim()) onChange(null); else if (/^\d+(?:[.,]\d*)?$/.test(text)) onChange(Number(text.replace(',', '.'))); }} style={[local.numberInput, { color: theme.textPrimary, borderColor: theme.border }]} /></View>; }
const local = StyleSheet.create({ prescription: { borderTopWidth: 1, paddingTop: spacing.md, gap: spacing.sm }, number: { minWidth: 90, flexGrow: 1, gap: spacing.xs }, numberInput: { minHeight: layout.minimumTouchTarget, borderWidth: 1, borderRadius: radius.md, paddingHorizontal: spacing.md, ...typography.body }, backdrop: { flex: 1, justifyContent: 'center', padding: spacing.lg, backgroundColor: '#00000066' }, variation: { minHeight: layout.minimumTouchTarget, borderWidth: 1, borderRadius: radius.md, padding: spacing.md, justifyContent: 'center' } });
