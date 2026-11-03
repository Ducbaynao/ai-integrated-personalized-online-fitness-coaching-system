# -*- coding: utf-8 -*-
with open('apps/mobile/src/features/workout-plan/TrainerWorkoutPlanDetailScreen.tsx', 'r', encoding='utf-8') as f:
    text = f.read()

old_block = """  const [selectedVersionId, setSelectedVersionId] = useState('');
  const selectedId = selectedVersionId || (canViewCurrent ? detailQuery.data?.currentVersion?.id : '') || versions[0]?.id || '';
  const versionQuery = useWorkoutPlanVersion(studentId, planId, selectedId, Boolean(selectedId && (canViewCurrent || canViewHistory)));
  const [pendingTarget, setPendingTarget] = useState<WorkoutPlanStatus | 'ACTIVATE' | null>(null);
  const [isAdjustmentModalVisible, setIsAdjustmentModalVisible] = useState(false);

  const prescriptions: ExercisePrescriptionOption[] = useMemo(() => {
    if (!versionQuery.data?.sessions) return [];
    return versionQuery.data.sessions.flatMap((s) =>
      s.prescriptions.map((p) => ({
        id: p.id,
        exerciseName: `${p.exercise.exerciseName || 'Bài tập'} (Ngày ${s.dayNumber})`,
        dayNumber: s.dayNumber,
        targetLoad: p.targetLoad,
        loadUnit: null,
        targetSets: p.targetSets,
        targetRepsMin: p.targetRepsMin,
        targetRepsMax: p.targetRepsMax,
        durationSeconds: p.durationSeconds,
      }))
    );
  }, [versionQuery.data]);"""

new_block = """  const [selectedVersionId, setSelectedVersionId] = useState('');
  const selectedId = selectedVersionId || (canViewCurrent ? detailQuery.data?.currentVersion?.id : '') || versions[0]?.id || '';
  const versionQuery = useWorkoutPlanVersion(studentId, planId, selectedId, Boolean(selectedId && (canViewCurrent || canViewHistory)));
  const currentVersionId = canViewCurrent ? detailQuery.data?.currentVersion?.id ?? '' : '';
  const currentVersionQuery = useWorkoutPlanVersion(
    studentId,
    planId,
    currentVersionId,
    Boolean(currentVersionId && canViewCurrent && currentVersionId !== selectedId)
  );
  const [pendingTarget, setPendingTarget] = useState<WorkoutPlanStatus | 'ACTIVATE' | null>(null);
  const [isAdjustmentModalVisible, setIsAdjustmentModalVisible] = useState(false);

  const currentPrescriptions: ExercisePrescriptionOption[] = useMemo(() => {
    const currentData = selectedId === currentVersionId ? versionQuery.data : currentVersionQuery.data;
    if (!currentData?.sessions) return [];
    return currentData.sessions.flatMap((s) =>
      s.prescriptions.map((p) => ({
        id: p.id,
        exerciseName: `${p.exercise.exerciseName || 'Bài tập'} (Ngày ${s.dayNumber})`,
        dayNumber: s.dayNumber,
        targetLoad: p.targetLoad,
        loadUnit: null,
        targetSets: p.targetSets,
        targetRepsMin: p.targetRepsMin,
        targetRepsMax: p.targetRepsMax,
        durationSeconds: p.durationSeconds,
      }))
    );
  }, [selectedId, currentVersionId, versionQuery.data, currentVersionQuery.data]);"""

old_modal = """      planContext={
        detailQuery.data.currentVersion
          ? {
              planId: plan.id,
              planVersionNumber: detailQuery.data.currentVersion.versionNumber,
              planAggregateVersion: plan.aggregateVersion,
              planName: plan.name,
              prescriptions,
            }
          : undefined
      }
      onClose={() => setIsAdjustmentModalVisible(false)}
      onAdjustmentApplied={async () => {
        setIsAdjustmentModalVisible(false);
        await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
        await detailQuery.refetch();
        await versionQuery.refetch();
      }}"""

new_modal = """      planContext={
        detailQuery.data.currentVersion
          ? {
              planId: plan.id,
              planVersionNumber: detailQuery.data.currentVersion.versionNumber,
              planAggregateVersion: plan.aggregateVersion,
              planName: plan.name,
              prescriptions: currentPrescriptions,
            }
          : undefined
      }
      onClose={() => setIsAdjustmentModalVisible(false)}
      onAdjustmentApplied={async () => {
        setIsAdjustmentModalVisible(false);
        await client.invalidateQueries({ queryKey: workoutPlanQueryKeys.student(studentId) });
        await detailQuery.refetch();
        await versionQuery.refetch();
        if (currentVersionId && currentVersionId !== selectedId) {
          await currentVersionQuery.refetch();
        }
      }}"""

assert old_block in text, "old_block not found"
assert old_modal in text, "old_modal not found"

text = text.replace(old_block, new_block).replace(old_modal, new_modal)

with open('apps/mobile/src/features/workout-plan/TrainerWorkoutPlanDetailScreen.tsx', 'w', encoding='utf-8', newline='\r\n') as f:
    f.write(text)

print("Updated TrainerWorkoutPlanDetailScreen successfully!")
