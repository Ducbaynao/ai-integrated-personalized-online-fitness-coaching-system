import React, { useState, useRef, useEffect } from 'react';
import {
  Modal,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  TouchableOpacity,
  View,
  useColorScheme,
} from 'react-native';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import {
  aiWorkoutAdjustmentApi,
  getWorkoutAdjustmentErrorMessage,
  getWorkoutAdjustmentFieldErrors,
} from '@/services/aiWorkoutAdjustmentApi';
import { createCommandKey } from '@/services/coachingApi';
import {
  AdjustmentChangeType,
  AdjustmentTargetScope,
  AdjustmentTargetRef,
  WorkoutAdjustmentApiResponse,
  TypedChange,
  SubstitutionBefore,
  SubstitutionAfter,
  LoadBefore,
  LoadAfter,
  VolumeBefore,
  VolumeAfter,
} from '@/types/aiWorkoutAdjustment';
import { WorkoutButton, WorkoutCard } from './WorkoutUi';

export interface ExercisePrescriptionOption {
  id: string;
  exerciseName: string;
  dayNumber?: number;
  targetLoad?: number | null;
  loadUnit?: string | null;
  targetSets?: number | null;
  targetRepsMin?: number | null;
  targetRepsMax?: number | null;
  durationSeconds?: number | null;
}

export interface OccurrenceContext {
  plannedWorkoutId: string;
  occurrenceVersion: number;
  sessionName?: string;
  prescriptions?: ExercisePrescriptionOption[];
}

export interface PlanContext {
  planId: string;
  planVersionNumber: number;
  planAggregateVersion?: number;
  planName?: string;
  prescriptions?: ExercisePrescriptionOption[];
}

interface Props {
  visible: boolean;
  studentId?: string;
  isTrainer?: boolean;
  initialScope?: AdjustmentTargetScope;
  occurrenceContext?: OccurrenceContext;
  planContext?: PlanContext;
  onClose: () => void;
  onAdjustmentApplied?: (result: {
    resultingEntityType: 'WORKOUT_OCCURRENCE' | 'WORKOUT_PLAN';
    resultingEntityId: string;
  }) => void;
}

const COMMON_EQUIPMENT = [
  'BARBELL',
  'DUMBBELL',
  'MACHINE',
  'CABLE',
  'BODYWEIGHT',
  'KETTLEBELL',
  'RESISTANCE_BAND',
];

export function AiWorkoutAdjustmentModal({
  visible,
  studentId,
  isTrainer = false,
  initialScope = 'ONE_MATERIALIZED_OCCURRENCE',
  occurrenceContext,
  planContext,
  onClose,
  onAdjustmentApplied,
}: Props) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);

  const [scope, setScope] = useState<AdjustmentTargetScope>(initialScope);
  const [changeType, setChangeType] = useState<AdjustmentChangeType>('EXERCISE_SUBSTITUTION');
  const [selectedPrescriptionId, setSelectedPrescriptionId] = useState<string>('');
  const [userReason, setUserReason] = useState<string>('');
  const [selectedEquipment, setSelectedEquipment] = useState<string[]>(['BODYWEIGHT', 'DUMBBELL']);
  const [hasPhysicalLimitations, setHasPhysicalLimitations] = useState<boolean>(false);
  const [limitationsText, setLimitationsText] = useState<string>('');
  const [allowLoadIncrease, setAllowLoadIncrease] = useState<boolean>(false);

  // Decision & Proposal State
  const [proposal, setProposal] = useState<WorkoutAdjustmentApiResponse | null>(null);
  const [decisionNote, setDecisionNote] = useState<string>('');
  const [rejectReason, setRejectReason] = useState<string>('');
  const [isRejecting, setIsRejecting] = useState<boolean>(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [conflictGuidance, setConflictGuidance] = useState<string | null>(null);

  // In-flight & loading guards
  const [isGenerating, setIsGenerating] = useState<boolean>(false);
  const [isDeciding, setIsDeciding] = useState<boolean>(false);
  const isGeneratingRef = useRef<boolean>(false);
  const isDecidingRef = useRef<boolean>(false);
  const [appliedResult, setAppliedResult] = useState<{
    resultingEntityType: 'WORKOUT_OCCURRENCE' | 'WORKOUT_PLAN';
    resultingEntityId: string;
  } | null>(null);

  // Stable command keys across retries of the same intent
  const [generateCommandKey, setGenerateCommandKey] = useState<string>(() => createCommandKey());
  const [acceptCommandKey, setAcceptCommandKey] = useState<string>(() => createCommandKey());

  // State isolation: Interaction session counter incremented on modal open/close or student/target change
  const activeSessionIdRef = useRef<number>(1);

  // Compute available prescriptions based on scope
  const availablePrescriptions =
    scope === 'ONE_MATERIALIZED_OCCURRENCE'
      ? occurrenceContext?.prescriptions ?? []
      : planContext?.prescriptions ?? [];

  // Adjust state during render when modal visibility, studentId or target changes (React recommended pattern)
  const [prevVisible, setPrevVisible] = useState(visible);
  const [prevStudentId, setPrevStudentId] = useState(studentId);
  const currentTargetId = occurrenceContext?.plannedWorkoutId || planContext?.planId;
  const [prevTargetId, setPrevTargetId] = useState(currentTargetId);

  if (visible !== prevVisible || studentId !== prevStudentId || currentTargetId !== prevTargetId) {
    setPrevVisible(visible);
    setPrevStudentId(studentId);
    setPrevTargetId(currentTargetId);

    setProposal(null);
    setAppliedResult(null);
    setErrorMessage(null);
    setConflictGuidance(null);
    setFieldErrors({});
    setDecisionNote('');
    setRejectReason('');
    setIsRejecting(false);
    setIsGenerating(false);
    setIsDeciding(false);

    setGenerateCommandKey(createCommandKey());
    setAcceptCommandKey(createCommandKey());
    if (availablePrescriptions.length > 0) {
      setSelectedPrescriptionId(availablePrescriptions[0].id);
    }
  }

  // Update session refs in effect to strictly comply with React Compiler rules
  useEffect(() => {
    activeSessionIdRef.current += 1;
    isGeneratingRef.current = false;
    isDecidingRef.current = false;
  }, [visible, studentId, currentTargetId]);

  const handleClose = () => {
    activeSessionIdRef.current += 1;
    isGeneratingRef.current = false;
    isDecidingRef.current = false;
    setIsGenerating(false);
    setIsDeciding(false);
    onClose();
  };

  // If scope changes, auto select first prescription if none selected
  const handleScopeChange = (newScope: AdjustmentTargetScope) => {
    setScope(newScope);
    const list =
      newScope === 'ONE_MATERIALIZED_OCCURRENCE'
        ? occurrenceContext?.prescriptions ?? []
        : planContext?.prescriptions ?? [];
    if (list.length > 0) {
      setSelectedPrescriptionId(list[0].id);
    } else {
      setSelectedPrescriptionId('');
    }
  };

  const toggleEquipment = (eq: string) => {
    if (selectedEquipment.includes(eq)) {
      if (selectedEquipment.length > 1) {
        setSelectedEquipment(selectedEquipment.filter((item) => item !== eq));
      }
    } else {
      setSelectedEquipment([...selectedEquipment, eq]);
    }
  };

  const handleGenerate = async () => {
    if (isGeneratingRef.current || isDecidingRef.current) return;

    setFieldErrors({});
    setErrorMessage(null);
    setConflictGuidance(null);

    const targetPrescriptionId = selectedPrescriptionId || availablePrescriptions[0]?.id;
    const errors: Record<string, string> = {};
    if (!targetPrescriptionId) {
      errors.selectedPrescription = 'Vui lòng chọn bài tập cần điều chỉnh.';
    }
    if (hasPhysicalLimitations && !limitationsText.trim()) {
      errors.limitationsText = 'Vui lòng mô tả hạn chế hoặc chấn thương.';
    }

    if (Object.keys(errors).length > 0) {
      setFieldErrors(errors);
      return;
    }

    // Build target reference
    const targetRef: AdjustmentTargetRef = {
      scope,
    };

    if (scope === 'ONE_MATERIALIZED_OCCURRENCE') {
      if (!occurrenceContext?.plannedWorkoutId) {
        setErrorMessage('Thiếu thông tin buổi tập cần điều chỉnh.');
        return;
      }
      targetRef.plannedWorkoutId = occurrenceContext.plannedWorkoutId;
      targetRef.plannedSessionExerciseId = targetPrescriptionId;
    } else {
      if (!planContext?.planId || planContext.planVersionNumber == null) {
        setErrorMessage('Thiếu thông tin kế hoạch tập luyện cần điều chỉnh.');
        return;
      }
      targetRef.planId = planContext.planId;
      targetRef.planVersionNumber = planContext.planVersionNumber;
      targetRef.exercisePrescriptionId = targetPrescriptionId;
    }

    const physicalLimitationsList =
      hasPhysicalLimitations && limitationsText.trim()
        ? limitationsText.split(',').map((s) => s.trim()).filter(Boolean)
        : [];

    const currentSession = activeSessionIdRef.current;
    isGeneratingRef.current = true;
    setIsGenerating(true);

    try {
      const response = await aiWorkoutAdjustmentApi.generate({
        studentId,
        commandKey: generateCommandKey,
        scope,
        target: targetRef,
        requestedChangeType: changeType,
        userReason: userReason.trim() || undefined,
        physicalLimitations: physicalLimitationsList.length > 0 ? physicalLimitationsList : undefined,
        equipmentAvailable: selectedEquipment,
        allowLoadIncrease,
      });

      // State isolation check: discard if session changed or modal closed in-flight
      if (currentSession !== activeSessionIdRef.current || !visible) {
        return;
      }

      setProposal(response);
      setAcceptCommandKey(createCommandKey());
    } catch (err) {
      if (currentSession !== activeSessionIdRef.current || !visible) {
        return;
      }
      const errMap = getWorkoutAdjustmentFieldErrors(err);
      if (Object.keys(errMap).length > 0) {
        setFieldErrors(errMap);
      }
      setErrorMessage(getWorkoutAdjustmentErrorMessage(err));
    } finally {
      if (currentSession === activeSessionIdRef.current) {
        isGeneratingRef.current = false;
        setIsGenerating(false);
      }
    }
  };

  const handleAccept = async () => {
    if (!proposal || isDecidingRef.current || isGeneratingRef.current) return;

    setErrorMessage(null);
    setConflictGuidance(null);

    // Strictly resolve expectedTargetVersion according to target scope
    const effectiveScope = proposal.structuredPayload?.changes?.[0]?.scope || scope;
    let expectedTargetVersion: number | undefined;

    if (effectiveScope === 'ONE_MATERIALIZED_OCCURRENCE') {
      const occGuard = proposal.structuredPayload?.changes?.[0]?.versionGuards?.expectedOccurrenceVersion;
      if (occGuard !== undefined && occGuard !== null) {
        expectedTargetVersion = Number(occGuard);
      } else if (occurrenceContext?.occurrenceVersion !== undefined && occurrenceContext?.occurrenceVersion !== null) {
        expectedTargetVersion = Number(occurrenceContext.occurrenceVersion);
      }
    } else {
      // TEMPLATE_OR_FUTURE_OCCURRENCES
      const planGuard = proposal.structuredPayload?.changes?.[0]?.versionGuards?.expectedPlanAggregateVersion;
      if (planGuard !== undefined && planGuard !== null) {
        expectedTargetVersion = Number(planGuard);
      } else if (planContext?.planAggregateVersion !== undefined && planContext?.planAggregateVersion !== null) {
        expectedTargetVersion = Number(planContext.planAggregateVersion);
      }
    }

    const currentSession = activeSessionIdRef.current;
    isDecidingRef.current = true;
    setIsDeciding(true);

    try {
      const res = await aiWorkoutAdjustmentApi.accept(proposal.recommendationId, {
        commandKey: acceptCommandKey,
        decisionNote: decisionNote.trim() || undefined,
        expectedRevision: 1,
        expectedTargetVersion,
      });

      // State isolation check: discard if session changed or modal closed in-flight
      if (currentSession !== activeSessionIdRef.current || !visible) {
        return;
      }

      setProposal((prev) => (prev ? { ...prev, status: 'APPLIED' } : null));
      const resultData = {
        resultingEntityType: res.resultingEntityType,
        resultingEntityId: res.resultingEntityId,
      };
      setAppliedResult(resultData);
      onAdjustmentApplied?.(resultData);
    } catch (err: any) {
      if (currentSession !== activeSessionIdRef.current || !visible) {
        return;
      }
      if (err?.status === 409) {
        setConflictGuidance(
          'Dữ liệu buổi tập hoặc kế hoạch đã bị thay đổi trên hệ thống (xung đột phiên bản). Vui lòng đóng và tải lại trang để xem xét lại đề xuất mới nhất.'
        );
      }
      setErrorMessage(getWorkoutAdjustmentErrorMessage(err));
    } finally {
      if (currentSession === activeSessionIdRef.current) {
        isDecidingRef.current = false;
        setIsDeciding(false);
      }
    }
  };

  const handleReject = async () => {
    if (!proposal || isDecidingRef.current || isGeneratingRef.current) return;

    setErrorMessage(null);
    setConflictGuidance(null);

    const currentSession = activeSessionIdRef.current;
    isDecidingRef.current = true;
    setIsDeciding(true);

    try {
      await aiWorkoutAdjustmentApi.reject(proposal.recommendationId, {
        reason: rejectReason.trim() || undefined,
      });

      // State isolation check: discard if session changed or modal closed in-flight
      if (currentSession !== activeSessionIdRef.current || !visible) {
        return;
      }

      setProposal((prev) => (prev ? { ...prev, status: 'REJECTED' } : null));
      setIsRejecting(false);
    } catch (err) {
      if (currentSession !== activeSessionIdRef.current || !visible) {
        return;
      }
      setErrorMessage(getWorkoutAdjustmentErrorMessage(err));
    } finally {
      if (currentSession === activeSessionIdRef.current) {
        isDecidingRef.current = false;
        setIsDeciding(false);
      }
    }
  };

  // Determine decision permission
  // Authority rule:
  // If decisionAuthority is TRAINER and user is student (!isTrainer) -> cannot decide
  // If decisionAuthority is STUDENT and user is trainer (isTrainer) -> cannot decide
  const canUserDecide =
    proposal &&
    ((proposal.decisionAuthority === 'STUDENT' && !isTrainer) ||
      (proposal.decisionAuthority === 'TRAINER' && isTrainer));

  const isAlreadyDecided =
    proposal?.status === 'APPLIED' ||
    proposal?.status === 'REJECTED' ||
    proposal?.status === 'EXPIRED' ||
    proposal?.status === 'CANCELLED';

  const renderBeforeAfterCard = (change: TypedChange) => {
    if (change.changeType === 'EXERCISE_SUBSTITUTION') {
      const before = change.before as SubstitutionBefore;
      const after = change.after as SubstitutionAfter;
      return (
        <View testID="adjustment-diff-substitution" style={styles.diffContainer}>
          <View style={[styles.diffColumn, { backgroundColor: theme.canvas }]}>
            <Text style={[styles.diffHeader, { color: theme.dangerText }]}>TRƯỚC (HIỆN TẠI)</Text>
            <Text style={[styles.diffTitle, { color: theme.textPrimary }]}>
              {before?.exerciseName || 'Chưa xác định'}
            </Text>
            <Text style={[styles.diffMeta, { color: theme.textSecondary }]}>
              Dạng: {before?.movementPattern || 'Chưa có'}
            </Text>
            <Text style={[styles.diffMeta, { color: theme.textSecondary }]}>
              Thiết bị: {before?.equipmentType || 'Chưa có'}
            </Text>
          </View>

          <View style={[styles.diffColumn, { backgroundColor: theme.canvas }]}>
            <Text style={[styles.diffHeader, { color: theme.primary }]}>SAU (THAY THẾ)</Text>
            <Text style={[styles.diffTitle, { color: theme.textPrimary }]}>
              {after?.replacementExerciseName || 'Chưa xác định'}
            </Text>
            <Text style={[styles.diffMeta, { color: theme.textSecondary }]}>
              Dạng: {after?.movementPattern || 'Chưa có'}
            </Text>
            <Text style={[styles.diffMeta, { color: theme.textSecondary }]}>
              Thiết bị: {after?.equipmentType || 'Chưa có'}
            </Text>
            {after?.substitutionCategory ? (
              <Text style={[styles.diffCategory, { color: theme.primary }]}>
                Phân loại: {after.substitutionCategory}
              </Text>
            ) : null}
          </View>
        </View>
      );
    }

    if (change.changeType === 'LOAD_ADJUSTMENT') {
      const before = change.before as LoadBefore;
      const after = change.after as LoadAfter;

      // Bodyweight vs Unknown vs Zero determination:
      // Contract rule: changeType: LOAD_ADJUSTMENT, targetLoad: null, loadUnit: null, direction: MAINTAIN, deltaLoad: null
      // or explicit mention of bodyweight/tự trọng in summary/reason or prescription equipment
      const isConfirmedBodyweight = (
        val: number | null | undefined,
        unit: string | null | undefined
      ): boolean => {
        // Any numeric load (including 0) is a specified load, NOT bodyweight
        if (val !== null && val !== undefined) return false;
        // If an external load unit (KG, LBS) is present, it's external load with missing value, NOT bodyweight
        if (unit !== null && unit !== undefined) return false;

        const text = `${proposal?.title || ''} ${proposal?.explanation || ''} ${proposal?.structuredPayload?.summary || ''} ${change?.reason || ''}`.toLowerCase();
        if (
          text.includes('bodyweight') ||
          text.includes('tự trọng') ||
          text.includes('trọng lượng cơ thể')
        ) {
          return true;
        }

        // Check if after object matches contract definition for bodyweight:
        const afterObj = change.after as LoadAfter;
        if (
          afterObj?.direction === 'MAINTAIN' &&
          (afterObj?.deltaLoad === null || afterObj?.deltaLoad === undefined) &&
          (afterObj?.targetLoad === null || afterObj?.targetLoad === undefined) &&
          (afterObj?.loadUnit === null || afterObj?.loadUnit === undefined)
        ) {
          return true;
        }

        return false;
      };

      const formatLoad = (val: number | null | undefined, unit: string | null | undefined) => {
        if (isConfirmedBodyweight(val, unit)) {
          return 'Trọng lượng cơ thể (Bodyweight)';
        }
        if (val === null || val === undefined) {
          return unit ? `Chưa có dữ liệu (${unit})` : 'Chưa có dữ liệu';
        }
        return unit ? `${val} ${unit}` : `${val}`;
      };

      return (
        <View testID="adjustment-diff-load" style={styles.diffContainer}>
          <View style={[styles.diffColumn, { backgroundColor: theme.canvas }]}>
            <Text style={[styles.diffHeader, { color: theme.dangerText }]}>MỨC TẠ CŨ</Text>
            <Text style={[styles.diffTitle, { color: theme.textPrimary }]}>
              {formatLoad(before?.targetLoad, before?.loadUnit)}
            </Text>
          </View>

          <View style={[styles.diffColumn, { backgroundColor: theme.canvas }]}>
            <Text style={[styles.diffHeader, { color: theme.primary }]}>MỨC TẠ MỚI</Text>
            <Text style={[styles.diffTitle, { color: theme.textPrimary }]}>
              {formatLoad(after?.targetLoad, after?.loadUnit)}
            </Text>
            <Text style={[styles.diffMeta, { color: theme.textSecondary }]}>
              Hướng: {after?.direction || 'MAINTAIN'}
            </Text>
            {after?.deltaLoad !== null && after?.deltaLoad !== undefined ? (
              <Text style={[styles.diffMeta, { color: theme.textPrimary }]}>
                Chênh lệch: {after.deltaLoad > 0 ? `+${after.deltaLoad}` : after.deltaLoad}
                {after?.loadUnit ? ` ${after.loadUnit}` : ''}
                {after?.deltaPercent !== null && after?.deltaPercent !== undefined
                  ? ` (${after.deltaPercent > 0 ? `+${after.deltaPercent}` : after.deltaPercent}%)`
                  : ''}
              </Text>
            ) : null}
          </View>
        </View>
      );
    }

    if (change.changeType === 'VOLUME_ADJUSTMENT') {
      const before = change.before as VolumeBefore;
      const after = change.after as VolumeAfter;

      const formatRepsOrDuration = (
        min: number | null | undefined,
        max: number | null | undefined,
        sec: number | null | undefined
      ) => {
        if (sec !== null && sec !== undefined) {
          return `${sec} giây`;
        }
        if (min !== null && min !== undefined) {
          return max !== null && max !== undefined && max !== min
            ? `${min}–${max} reps`
            : `${min} reps`;
        }
        return 'Chưa có dữ liệu';
      };

      return (
        <View testID="adjustment-diff-volume" style={styles.diffContainer}>
          <View style={[styles.diffColumn, { backgroundColor: theme.canvas }]}>
            <Text style={[styles.diffHeader, { color: theme.dangerText }]}>VOLUME CŨ</Text>
            <Text style={[styles.diffTitle, { color: theme.textPrimary }]}>
              {before?.targetSets !== null && before?.targetSets !== undefined
                ? `${before.targetSets} hiệp`
                : 'Chưa có dữ liệu hiệp'}
            </Text>
            <Text style={[styles.diffMeta, { color: theme.textSecondary }]}>
              {formatRepsOrDuration(
                before?.targetRepsMin,
                before?.targetRepsMax,
                before?.durationSeconds
              )}
            </Text>
          </View>

          <View style={[styles.diffColumn, { backgroundColor: theme.canvas }]}>
            <Text style={[styles.diffHeader, { color: theme.primary }]}>VOLUME MỚI</Text>
            <Text style={[styles.diffTitle, { color: theme.textPrimary }]}>
              {after?.targetSets !== null && after?.targetSets !== undefined
                ? `${after.targetSets} hiệp`
                : 'Chưa có dữ liệu hiệp'}
              {after?.deltaSets !== null && after?.deltaSets !== undefined
                ? ` (${after.deltaSets > 0 ? `+${after.deltaSets}` : after.deltaSets})`
                : ''}
            </Text>
            <Text style={[styles.diffMeta, { color: theme.textSecondary }]}>
              {formatRepsOrDuration(
                after?.targetRepsMin,
                after?.targetRepsMax,
                after?.durationSeconds
              )}
              {after?.deltaDurationSeconds !== null && after?.deltaDurationSeconds !== undefined
                ? ` (${after.deltaDurationSeconds > 0 ? `+${after.deltaDurationSeconds}` : after.deltaDurationSeconds}s)`
                : ''}
            </Text>
          </View>
        </View>
      );
    }

    return null;
  };

  return (
    <Modal visible={visible} animationType="slide" transparent={false} onRequestClose={handleClose}>
      <View
        testID="ai-workout-adjustment-modal"
        style={[styles.container, { backgroundColor: theme.canvas }]}
      >
        {/* Header */}
        <View style={[styles.header, { borderBottomColor: theme.border }]}>
          <Text accessibilityRole="header" style={[styles.headerTitle, { color: theme.textPrimary }]}>
            Đề xuất điều chỉnh giáo án (AI)
          </Text>
          <TouchableOpacity
            testID="close-adjustment-modal"
            accessibilityRole="button"
            accessibilityLabel="Đóng"
            onPress={handleClose}
            style={styles.closeButton}
          >
            <Text style={[styles.closeText, { color: theme.textSecondary }]}>✕</Text>
          </TouchableOpacity>
        </View>

        <ScrollView contentContainerStyle={styles.content}>
          {/* Error Message */}
          {errorMessage ? (
            <WorkoutCard testID="adjustment-error-message">
              <Text accessibilityRole="alert" style={[styles.errorText, { color: theme.dangerText }]}>
                {errorMessage}
              </Text>
            </WorkoutCard>
          ) : null}

          {/* Conflict Guidance */}
          {conflictGuidance ? (
            <WorkoutCard testID="adjustment-conflict-guidance">
              <Text style={[styles.conflictTitle, { color: theme.warningText }]}>
                Xung đột phiên bản
              </Text>
              <Text style={[styles.conflictBody, { color: theme.textSecondary }]}>
                {conflictGuidance}
              </Text>
            </WorkoutCard>
          ) : null}

          {/* Form View (Visible when no proposal or proposal was rejected) */}
          {!proposal ? (
            <View testID="adjustment-request-form" style={styles.formContainer}>
              {/* Scope Selection */}
              <WorkoutCard>
                <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                  Phạm vi áp dụng
                </Text>
                <Text style={[styles.hintText, { color: theme.textSecondary }]}>
                  {scope === 'ONE_MATERIALIZED_OCCURRENCE'
                    ? 'Chỉ áp dụng cho 1 buổi tập cụ thể này. Không tạo phiên bản giáo án mới.'
                    : 'Áp dụng cho mẫu giáo án (template) và các buổi tương lai. Tạo phiên bản giáo án mới.'}
                </Text>
                <View style={styles.row}>
                  <TouchableOpacity
                    testID="adjustment-scope-occurrence"
                    accessibilityRole="button"
                    accessibilityLabel="Chỉ 1 buổi tập này"
                    onPress={() => handleScopeChange('ONE_MATERIALIZED_OCCURRENCE')}
                    style={[
                      styles.choiceButton,
                      {
                        backgroundColor:
                          scope === 'ONE_MATERIALIZED_OCCURRENCE'
                            ? theme.primary
                            : theme.surface,
                        borderColor: theme.border,
                      },
                    ]}
                  >
                    <Text
                      style={{
                        color:
                          scope === 'ONE_MATERIALIZED_OCCURRENCE'
                            ? theme.textOnPrimary
                            : theme.textPrimary,
                        ...typography.label,
                      }}
                    >
                      Chỉ buổi này
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    testID="adjustment-scope-template"
                    accessibilityRole="button"
                    accessibilityLabel="Mẫu giáo án & tương lai"
                    onPress={() => handleScopeChange('TEMPLATE_OR_FUTURE_OCCURRENCES')}
                    style={[
                      styles.choiceButton,
                      {
                        backgroundColor:
                          scope === 'TEMPLATE_OR_FUTURE_OCCURRENCES'
                            ? theme.primary
                            : theme.surface,
                        borderColor: theme.border,
                      },
                    ]}
                  >
                    <Text
                      style={{
                        color:
                          scope === 'TEMPLATE_OR_FUTURE_OCCURRENCES'
                            ? theme.textOnPrimary
                            : theme.textPrimary,
                        ...typography.label,
                      }}
                    >
                      Mẫu giáo án & tương lai
                    </Text>
                  </TouchableOpacity>
                </View>
              </WorkoutCard>

              {/* Target Exercise Selection */}
              <WorkoutCard>
                <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                  Bài tập cần điều chỉnh
                </Text>
                {fieldErrors.selectedPrescription ? (
                  <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                    {fieldErrors.selectedPrescription}
                  </Text>
                ) : null}
                {availablePrescriptions.length === 0 ? (
                  <Text style={[styles.emptyPrescriptionText, { color: theme.textSecondary }]}>
                    Không tìm thấy bài tập nào trong ngữ cảnh hiện tại.
                  </Text>
                ) : (
                  <View testID="adjustment-exercise-selector" style={styles.prescriptionList}>
                    {availablePrescriptions.map((p) => {
                      const isSelected = selectedPrescriptionId === p.id;
                      return (
                        <TouchableOpacity
                          key={p.id}
                          testID={`prescription-option-${p.id}`}
                          accessibilityRole="button"
                          accessibilityLabel={p.exerciseName}
                          onPress={() => setSelectedPrescriptionId(p.id)}
                          style={[
                            styles.prescriptionItem,
                            {
                              backgroundColor: isSelected ? theme.primary : theme.surface,
                              borderColor: theme.border,
                            },
                          ]}
                        >
                          <Text
                            style={[
                              styles.prescriptionItemText,
                              {
                                color: isSelected ? theme.textOnPrimary : theme.textPrimary,
                              },
                            ]}
                          >
                            {p.exerciseName}
                          </Text>
                        </TouchableOpacity>
                      );
                    })}
                  </View>
                )}
              </WorkoutCard>

              {/* Change Type Selection */}
              <WorkoutCard>
                <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                  Loại hình điều chỉnh
                </Text>
                <View style={styles.columnGap}>
                  <TouchableOpacity
                    testID="adjustment-change-type-substitution"
                    accessibilityRole="button"
                    onPress={() => setChangeType('EXERCISE_SUBSTITUTION')}
                    style={[
                      styles.choiceItem,
                      {
                        backgroundColor:
                          changeType === 'EXERCISE_SUBSTITUTION' ? theme.primary : theme.surface,
                        borderColor: theme.border,
                      },
                    ]}
                  >
                    <Text
                      style={{
                        color:
                          changeType === 'EXERCISE_SUBSTITUTION'
                            ? theme.textOnPrimary
                            : theme.textPrimary,
                        ...typography.label,
                      }}
                    >
                      Thay bài tập tương đương (Exercise Substitution)
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    testID="adjustment-change-type-load"
                    accessibilityRole="button"
                    onPress={() => setChangeType('LOAD_ADJUSTMENT')}
                    style={[
                      styles.choiceItem,
                      {
                        backgroundColor:
                          changeType === 'LOAD_ADJUSTMENT' ? theme.primary : theme.surface,
                        borderColor: theme.border,
                      },
                    ]}
                  >
                    <Text
                      style={{
                        color:
                          changeType === 'LOAD_ADJUSTMENT'
                            ? theme.textOnPrimary
                            : theme.textPrimary,
                        ...typography.label,
                      }}
                    >
                      Điều chỉnh mức tạ (Load Adjustment)
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    testID="adjustment-change-type-volume"
                    accessibilityRole="button"
                    onPress={() => setChangeType('VOLUME_ADJUSTMENT')}
                    style={[
                      styles.choiceItem,
                      {
                        backgroundColor:
                          changeType === 'VOLUME_ADJUSTMENT' ? theme.primary : theme.surface,
                        borderColor: theme.border,
                      },
                    ]}
                  >
                    <Text
                      style={{
                        color:
                          changeType === 'VOLUME_ADJUSTMENT'
                            ? theme.textOnPrimary
                            : theme.textPrimary,
                        ...typography.label,
                      }}
                    >
                      Điều chỉnh hiệp / lần lặp / thời lượng (Volume Adjustment)
                    </Text>
                  </TouchableOpacity>
                </View>
              </WorkoutCard>

              {/* Input details */}
              <WorkoutCard>
                <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                  Lý do & Ngữ cảnh
                </Text>
                <Text style={[styles.label, { color: theme.textPrimary }]}>
                  Lý do yêu cầu điều chỉnh
                </Text>
                <TextInput
                  testID="adjustment-reason-input"
                  accessibilityLabel="Lý do điều chỉnh"
                  placeholder="Ví dụ: Thiếu thiết bị, máy bận, cơ đau mỏi..."
                  value={userReason}
                  onChangeText={setUserReason}
                  multiline
                  style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                />

                {/* Equipment Checkboxes */}
                <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.md }]}>
                  Thiết bị khả dụng
                </Text>
                <View style={styles.chipRow}>
                  {COMMON_EQUIPMENT.map((eq) => {
                    const isSelected = selectedEquipment.includes(eq);
                    return (
                      <TouchableOpacity
                        key={eq}
                        testID={`equipment-chip-${eq}`}
                        accessibilityRole="button"
                        onPress={() => toggleEquipment(eq)}
                        style={[
                          styles.chip,
                          {
                            backgroundColor: isSelected ? theme.primary : theme.surface,
                            borderColor: theme.border,
                          },
                        ]}
                      >
                        <Text
                          style={{
                            color: isSelected ? theme.textOnPrimary : theme.textPrimary,
                            ...typography.bodySmall,
                          }}
                        >
                          {eq}
                        </Text>
                      </TouchableOpacity>
                    );
                  })}
                </View>

                {/* Physical Limitations */}
                <View style={{ marginTop: spacing.md }}>
                  <TouchableOpacity
                    testID="adjustment-limitations-toggle"
                    accessibilityRole="button"
                    onPress={() => setHasPhysicalLimitations(!hasPhysicalLimitations)}
                    style={styles.toggleRow}
                  >
                    <View
                      style={[
                        styles.checkbox,
                        {
                          backgroundColor: hasPhysicalLimitations ? theme.primary : 'transparent',
                          borderColor: theme.border,
                        },
                      ]}
                    />
                    <Text style={[styles.label, { color: theme.textPrimary }]}>
                      Có chấn thương hoặc hạn chế vận động
                    </Text>
                  </TouchableOpacity>

                  {hasPhysicalLimitations ? (
                    <View style={{ marginTop: spacing.sm }}>
                      <TextInput
                        testID="adjustment-limitations-input"
                        accessibilityLabel="Mô tả chấn thương hoặc hạn chế"
                        placeholder="Mô tả chi tiết (ví dụ: đau khớp cổ tay, đau lưng dưới...)"
                        value={limitationsText}
                        onChangeText={setLimitationsText}
                        style={[
                          styles.input,
                          { borderColor: theme.border, color: theme.textPrimary },
                        ]}
                      />
                      {fieldErrors.limitationsText ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {fieldErrors.limitationsText}
                        </Text>
                      ) : null}
                    </View>
                  ) : null}
                </View>

                {/* Allow Load Increase (for LOAD or VOLUME adjustment) */}
                {changeType !== 'EXERCISE_SUBSTITUTION' ? (
                  <View style={{ marginTop: spacing.md }}>
                    <TouchableOpacity
                      testID="adjustment-allow-load-increase-toggle"
                      accessibilityRole="button"
                      onPress={() => setAllowLoadIncrease(!allowLoadIncrease)}
                      style={styles.toggleRow}
                    >
                      <View
                        style={[
                          styles.checkbox,
                          {
                            backgroundColor: allowLoadIncrease ? theme.primary : 'transparent',
                            borderColor: theme.border,
                          },
                        ]}
                      />
                      <Text style={[styles.label, { color: theme.textPrimary }]}>
                        Cho phép tăng tải nếu dữ liệu tập luyện đủ điều kiện
                      </Text>
                    </TouchableOpacity>
                    <Text style={[styles.hintText, { color: theme.textSecondary }]}>
                      Theo quy định: AI chỉ đề xuất tăng tải khi có dữ liệu tập luyện và RPE tin cậy.
                    </Text>
                  </View>
                ) : null}
              </WorkoutCard>

              {/* Submit generate button */}
              <WorkoutButton
                testID="adjustment-generate-button"
                label={isGenerating ? 'AI đang phân tích...' : 'Tạo đề xuất điều chỉnh'}
                disabled={isGenerating || isDeciding || availablePrescriptions.length === 0}
                busy={isGenerating}
                onPress={handleGenerate}
              />
            </View>
          ) : null}

          {/* Proposal Review View */}
          {proposal ? (
            <View testID="adjustment-proposal-view" style={styles.proposalContainer}>
              {/* Proposal Header */}
              <WorkoutCard>
                <View style={styles.proposalHeaderRow}>
                  <Text style={[styles.badge, { backgroundColor: theme.primary, color: theme.textOnPrimary }]}>
                    {proposal.status}
                  </Text>
                  <Text style={[styles.authorityLabel, { color: theme.textSecondary }]}>
                    Thẩm quyền: {proposal.decisionAuthority}
                  </Text>
                </View>

                <Text accessibilityRole="header" style={[styles.proposalTitle, { color: theme.textPrimary }]}>
                  {proposal.title}
                </Text>
                <Text style={[styles.explanationText, { color: theme.textSecondary }]}>
                  {proposal.explanation}
                </Text>

                {proposal.expiresAt ? (
                  <Text style={[styles.metaText, { color: theme.textSecondary }]}>
                    Thời hạn hiệu lực: {new Date(proposal.expiresAt).toLocaleString('vi-VN')}
                  </Text>
                ) : null}
              </WorkoutCard>

              {/* Authority Banner if user cannot decide */}
              {!canUserDecide && !isAlreadyDecided ? (
                <WorkoutCard testID="adjustment-authority-banner">
                  <Text style={[styles.warningTitle, { color: theme.warningText }]}>
                    Quyền hạn duyệt đề xuất
                  </Text>
                  <Text style={[styles.warningBody, { color: theme.textSecondary }]}>
                    {proposal.decisionAuthority === 'TRAINER'
                      ? 'Đề xuất này do Huấn luyện viên quyết định theo chế độ HUMAN_COACH. Bạn chỉ có quyền xem, không được duyệt.'
                      : 'Đề xuất này do Học viên quyết định theo chế độ SELF_DIRECTED.'}
                  </Text>
                </WorkoutCard>
              ) : null}

              {/* Applied Result Card */}
              {appliedResult ? (
                <WorkoutCard testID="adjustment-applied-card">
                  <Text style={[styles.appliedTitle, { color: theme.primary }]}>
                    Đã áp dụng điều chỉnh thành công!
                  </Text>
                  <Text style={[styles.appliedBody, { color: theme.textSecondary }]}>
                    Đối tượng cập nhật: {appliedResult.resultingEntityType} (ID: {appliedResult.resultingEntityId})
                  </Text>
                </WorkoutCard>
              ) : null}

              {/* Before / After Diff Cards */}
              <WorkoutCard testID="adjustment-before-after-card">
                <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                  So sánh Trước & Sau (Before / After)
                </Text>
                {proposal.structuredPayload?.changes?.map((change, idx) => (
                  <View key={change.changeId || idx} style={{ marginTop: idx > 0 ? spacing.md : 0 }}>
                    {renderBeforeAfterCard(change)}
                    {change.reason ? (
                      <Text style={[styles.changeReason, { color: theme.textSecondary }]}>
                        Lý do: {change.reason}
                      </Text>
                    ) : null}
                  </View>
                ))}
              </WorkoutCard>

              {/* Reasoning, Used Data & Missing Data */}
              {proposal.structuredPayload?.reasoning?.length ? (
                <WorkoutCard>
                  <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                    Căn cứ đề xuất
                  </Text>
                  {proposal.structuredPayload.reasoning.map((r, i) => (
                    <Text key={i} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {r}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {proposal.structuredPayload?.missingData?.length ? (
                <WorkoutCard>
                  <Text style={[styles.sectionTitle, { color: theme.warningText }]}>
                    Dữ liệu chưa có / thiếu
                  </Text>
                  {proposal.structuredPayload.missingData.map((m, i) => (
                    <Text key={i} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {m}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Accept & Reject Action Section */}
              {canUserDecide && !isAlreadyDecided ? (
                <WorkoutCard testID="adjustment-actions-card">
                  <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                    Quyết định đề xuất
                  </Text>

                  {!isRejecting ? (
                    <>
                      <Text style={[styles.label, { color: theme.textPrimary }]}>
                        Ghi chú quyết định (tùy chọn)
                      </Text>
                      <TextInput
                        testID="adjustment-decision-note-input"
                        accessibilityLabel="Ghi chú quyết định"
                        placeholder="Nhập ghi chú cho buổi tập..."
                        value={decisionNote}
                        onChangeText={setDecisionNote}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />

                      <View style={styles.actionRow}>
                        <WorkoutButton
                          testID="adjustment-accept-button"
                          label={isDeciding ? 'Đang áp dụng...' : 'Chấp thuận & Áp dụng'}
                          disabled={isDeciding || isGenerating}
                          busy={isDeciding}
                          onPress={handleAccept}
                        />
                        <WorkoutButton
                          testID="adjustment-start-reject-button"
                          label="Từ chối"
                          secondary
                          danger
                          disabled={isDeciding || isGenerating}
                          onPress={() => setIsRejecting(true)}
                        />
                      </View>
                    </>
                  ) : (
                    <>
                      <Text style={[styles.label, { color: theme.dangerText }]}>
                        Lý do từ chối (tùy chọn)
                      </Text>
                      <TextInput
                        testID="adjustment-reject-reason-input"
                        accessibilityLabel="Lý do từ chối"
                        placeholder="Nhập lý do không áp dụng đề xuất..."
                        value={rejectReason}
                        onChangeText={setRejectReason}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      <View style={styles.actionRow}>
                        <WorkoutButton
                          testID="adjustment-reject-button"
                          label={isDeciding ? 'Đang từ chối...' : 'Xác nhận từ chối'}
                          danger
                          disabled={isDeciding || isGenerating}
                          busy={isDeciding}
                          onPress={handleReject}
                        />
                        <WorkoutButton
                          testID="adjustment-cancel-reject-button"
                          label="Hủy thao tác"
                          secondary
                          disabled={isDeciding || isGenerating}
                          onPress={() => setIsRejecting(false)}
                        />
                      </View>
                    </>
                  )}
                </WorkoutCard>
              ) : null}

              {/* Proposal already decided notice */}
              {isAlreadyDecided && !appliedResult ? (
                <WorkoutCard>
                  <Text style={[styles.badge, { backgroundColor: theme.surface, color: theme.textSecondary }]}>
                    Đề xuất đã ở trạng thái {proposal.status}.
                  </Text>
                </WorkoutCard>
              ) : null}

              {/* Create new proposal button */}
              <WorkoutButton
                testID="adjustment-reset-button"
                label="Tạo đề xuất điều chỉnh khác"
                secondary
                disabled={isDeciding || isGenerating}
                onPress={() => {
                  setProposal(null);
                  setAppliedResult(null);
                  setErrorMessage(null);
                  setConflictGuidance(null);
                  setGenerateCommandKey(createCommandKey());
                }}
              />
            </View>
          ) : null}
        </ScrollView>
      </View>
    </Modal>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
  },
  header: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    paddingHorizontal: spacing.lg,
    paddingTop: spacing.xl,
    paddingBottom: spacing.md,
    borderBottomWidth: 1,
  },
  headerTitle: {
    ...typography.h3,
  },
  closeButton: {
    padding: spacing.sm,
  },
  closeText: {
    ...typography.h2,
  },
  content: {
    padding: spacing.lg,
    paddingBottom: spacing.xxl,
    gap: spacing.lg,
  },
  formContainer: {
    gap: spacing.lg,
  },
  proposalContainer: {
    gap: spacing.lg,
  },
  sectionTitle: {
    ...typography.h3,
    marginBottom: spacing.xs,
  },
  hintText: {
    ...typography.bodySmall,
    marginBottom: spacing.sm,
  },
  row: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  columnGap: {
    gap: spacing.sm,
  },
  choiceButton: {
    flex: 1,
    minHeight: 44,
    borderWidth: 1,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.sm,
  },
  choiceItem: {
    minHeight: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    justifyContent: 'center',
    paddingHorizontal: spacing.md,
  },
  prescriptionList: {
    gap: spacing.xs,
  },
  prescriptionItem: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
  },
  prescriptionItemText: {
    ...typography.label,
  },
  emptyPrescriptionText: {
    ...typography.bodySmall,
    fontStyle: 'italic',
  },
  label: {
    ...typography.label,
    marginBottom: spacing.xs,
  },
  input: {
    minHeight: 48,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.md,
    ...typography.body,
  },
  chipRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  chip: {
    borderWidth: 1,
    borderRadius: radius.full,
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.xs,
  },
  toggleRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  checkbox: {
    width: 20,
    height: 20,
    borderWidth: 1,
    borderRadius: radius.sm,
  },
  fieldError: {
    ...typography.bodySmall,
    marginTop: spacing.xs,
  },
  errorText: {
    ...typography.bodySmall,
  },
  conflictTitle: {
    ...typography.label,
    fontWeight: 'bold',
  },
  conflictBody: {
    ...typography.bodySmall,
    marginTop: spacing.xs,
  },
  proposalHeaderRow: {
    flexDirection: 'row',
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs,
  },
  badge: {
    alignSelf: 'flex-start',
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xxs,
    borderRadius: radius.sm,
    ...typography.caption,
    fontWeight: 'bold',
  },
  authorityLabel: {
    ...typography.caption,
  },
  proposalTitle: {
    ...typography.h2,
    marginTop: spacing.xs,
  },
  explanationText: {
    ...typography.body,
    marginTop: spacing.xs,
  },
  metaText: {
    ...typography.caption,
    marginTop: spacing.xs,
  },
  diffContainer: {
    flexDirection: 'row',
    gap: spacing.sm,
    marginTop: spacing.xs,
  },
  diffColumn: {
    flex: 1,
    borderWidth: 1,
    borderColor: 'transparent',
    borderRadius: radius.md,
    padding: spacing.sm,
  },
  diffHeader: {
    ...typography.caption,
    fontWeight: 'bold',
    marginBottom: spacing.xxs,
  },
  diffTitle: {
    ...typography.label,
    fontWeight: 'bold',
  },
  diffMeta: {
    ...typography.bodySmall,
    marginTop: spacing.xxs,
  },
  diffCategory: {
    ...typography.caption,
    marginTop: spacing.xs,
  },
  changeReason: {
    ...typography.bodySmall,
    fontStyle: 'italic',
    marginTop: spacing.xs,
  },
  bulletItem: {
    ...typography.bodySmall,
    marginTop: spacing.xxs,
  },
  warningTitle: {
    ...typography.label,
    fontWeight: 'bold',
  },
  warningBody: {
    ...typography.bodySmall,
    marginTop: spacing.xs,
  },
  appliedTitle: {
    ...typography.h3,
    fontWeight: 'bold',
  },
  appliedBody: {
    ...typography.bodySmall,
    marginTop: spacing.xs,
  },
  actionRow: {
    marginTop: spacing.md,
    gap: spacing.sm,
  },
});
