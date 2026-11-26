import React, { useState, useRef, useEffect, useCallback, useMemo } from 'react';
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
import { aiRecommendationStatusLabels } from '@/localization/displayLabels';
import { spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import {
  aiWorkoutAdjustmentApi,
  getWorkoutAdjustmentErrorMessage,
  getWorkoutAdjustmentFieldErrors,
} from '@/services/aiWorkoutAdjustmentApi';
import { exerciseApi } from '@/services/exerciseApi';
import { measurementApi } from '@/services/measurementApi';
import { createCommandKey } from '@/services/coachingApi';
import {
  AdjustmentChangeType,
  AdjustmentTargetScope,
  AdjustmentTargetRef,
  WorkoutAdjustmentApiResponse,
  AiWorkoutAdjustmentDetail,
  TypedChange,
  SubstitutionBefore,
  SubstitutionAfter,
  LoadBefore,
  LoadAfter,
  VolumeBefore,
  VolumeAfter,
  F03WorkoutAdjustmentPayload,
  F03DraftDiff,
  AdjustmentRecommendationStatus,
  AdjustmentDecisionAuthority,
} from '@/types/aiWorkoutAdjustment';
import { ExerciseCatalogItem, ExerciseVariation } from '@/types/exercise';
import { WorkoutButton, WorkoutCard } from './WorkoutUi';

export interface ExercisePrescriptionOption {
  id: string;
  exerciseName: string;
  dayNumber?: number;
  targetLoad?: number | null;
  loadUnit?: string | null;
  loadUnitId?: number | null;
  targetSets?: number | null;
  targetRepsMin?: number | null;
  targetRepsMax?: number | null;
  durationSeconds?: number | null;
}

export const LB_TO_KG_MULTIPLIER = 0.45359237;
export const KG_TO_LB_MULTIPLIER = 1 / 0.45359237;

export function normalizeLoadUnitCode(rawUnit?: string | null): 'KG' | 'LB' | null {
  if (!rawUnit) return null;
  const upper = rawUnit.trim().toUpperCase();
  if (upper === 'LBS' || upper === 'LB') return 'LB';
  if (upper === 'KG') return 'KG';
  return null;
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

export interface ActiveProposalState {
  recommendationId: string;
  runId?: string;
  status: AdjustmentRecommendationStatus;
  decisionAuthority: AdjustmentDecisionAuthority;
  title: string;
  explanation: string;
  expiresAt?: string | null;
  createdAt?: string;
  updatedAt?: string;
  revision: number;
  isCustomized: boolean;
  structuredPayload: F03WorkoutAdjustmentPayload;
  originalPayload?: F03WorkoutAdjustmentPayload | null;
  sharedWithStudent?: boolean;
  sharedRevision?: number | null;
  sharedAt?: string | null;
  sharedBy?: string | null;
  diff?: F03DraftDiff | null;
}

export interface AiWorkoutAdjustmentModalProps {
  visible: boolean;
  studentId?: string;
  isTrainer?: boolean;
  initialScope?: AdjustmentTargetScope;
  initialRecommendationId?: string;
  initialProposal?: WorkoutAdjustmentApiResponse | AiWorkoutAdjustmentDetail | ActiveProposalState | null;
  occurrenceContext?: OccurrenceContext;
  planContext?: PlanContext;
  unitReferences?: { code: string; id: number }[];
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

const UUID_REGEX = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const INTEGER_REGEX = /^\d+$/;

function toActiveProposal(
  raw: WorkoutAdjustmentApiResponse | AiWorkoutAdjustmentDetail | ActiveProposalState
): ActiveProposalState {
  const p = (raw as any).structuredPayload || (raw as any).payload || { changes: [] };
  return {
    recommendationId: (raw as any).recommendationId || (raw as any).id,
    runId: (raw as any).runId || (raw as any).aiRunId,
    status: raw.status,
    decisionAuthority: raw.decisionAuthority,
    title: raw.title,
    explanation: raw.explanation,
    expiresAt: raw.expiresAt,
    createdAt: raw.createdAt,
    updatedAt: (raw as any).updatedAt,
    revision: (raw as any).revision ?? 1,
    isCustomized: (raw as any).isCustomized ?? false,
    structuredPayload: p,
    originalPayload: (raw as any).originalPayload ?? null,
    sharedWithStudent: (raw as any).sharedWithStudent ?? false,
    sharedRevision: (raw as any).sharedRevision ?? null,
    sharedAt: (raw as any).sharedAt ?? null,
    sharedBy: (raw as any).sharedBy ?? null,
    diff: (raw as any).diff ?? null,
  };
}

export function AiWorkoutAdjustmentModal({
  visible,
  studentId,
  isTrainer = false,
  initialScope = 'ONE_MATERIALIZED_OCCURRENCE',
  initialRecommendationId,
  initialProposal,
  occurrenceContext,
  planContext,
  unitReferences,
  onClose,
  onAdjustmentApplied,
}: AiWorkoutAdjustmentModalProps) {
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

  // Proposal State
  const [proposal, setProposal] = useState<ActiveProposalState | null>(() =>
    initialProposal ? toActiveProposal(initialProposal) : null
  );
  const [lookupId, setLookupId] = useState<string>(initialRecommendationId || '');
  const [isLoadingDetail, setIsLoadingDetail] = useState<boolean>(false);

  // Decision State
  const [decisionNote, setDecisionNote] = useState<string>('');
  const [rejectReason, setRejectReason] = useState<string>('');
  const [isRejecting, setIsRejecting] = useState<boolean>(false);
  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [conflictGuidance, setConflictGuidance] = useState<string | null>(null);
  const [shareSuccessMessage, setShareSuccessMessage] = useState<string | null>(null);

  // Draft Editing State
  const [isEditing, setIsEditing] = useState<boolean>(false);
  const [isSavingDraft, setIsSavingDraft] = useState<boolean>(false);
  const isSavingDraftRef = useRef<boolean>(false);
  const [editTitle, setEditTitle] = useState<string>('');
  const [editExplanation, setEditExplanation] = useState<string>('');

  // Exercise Substitution editing state
  const [editReplacementVariationId, setEditReplacementVariationId] = useState<string>('');
  const [editReplacementExerciseName, setEditReplacementExerciseName] = useState<string>('');
  const [editMovementPattern, setEditMovementPattern] = useState<string>('');
  const [editEquipmentType, setEditEquipmentType] = useState<string>('');
  const [editSubstitutionCategory, setEditSubstitutionCategory] = useState<string>('EQUIPMENT_UNAVAILABLE');

  // Catalog picker state for exercise substitution
  const [isCatalogPickerOpen, setIsCatalogPickerOpen] = useState<boolean>(false);
  const [catalogSearchQuery, setCatalogSearchQuery] = useState<string>('');
  const [isSearchingCatalog, setIsSearchingCatalog] = useState<boolean>(false);
  const [catalogExercises, setCatalogExercises] = useState<ExerciseCatalogItem[]>([]);
  const [selectedCatalogExercise, setSelectedCatalogExercise] = useState<ExerciseCatalogItem | null>(null);
  const [catalogVariations, setCatalogVariations] = useState<ExerciseVariation[]>([]);
  const [isLoadingVariations, setIsLoadingVariations] = useState<boolean>(false);

  // Load Adjustment editing state
  const [editTargetLoad, setEditTargetLoad] = useState<string>('');
  const [editLoadUnit, setEditLoadUnit] = useState<string>('KG');
  const [editLoadUnitId, setEditLoadUnitId] = useState<number | null>(null);

  // Volume Adjustment editing state
  const [editTargetSets, setEditTargetSets] = useState<string>('');
  const [editTargetRepsMin, setEditTargetRepsMin] = useState<string>('');
  const [editTargetRepsMax, setEditTargetRepsMax] = useState<string>('');
  const [editDurationSeconds, setEditDurationSeconds] = useState<string>('');
  const [editFieldErrors, setEditFieldErrors] = useState<Record<string, string>>({});

  // Draft Sharing State
  const [isSharing, setIsSharing] = useState<boolean>(false);
  const isSharingRef = useRef<boolean>(false);

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

  // State isolation: Interaction session counter incremented on modal open/close or student/target/recId change
  const activeSessionIdRef = useRef<number>(1);

  // Compute available prescriptions based on scope
  const availablePrescriptions = useMemo(
    () => scope === 'ONE_MATERIALIZED_OCCURRENCE'
      ? occurrenceContext?.prescriptions ?? []
      : planContext?.prescriptions ?? [],
    [scope, occurrenceContext?.prescriptions, planContext?.prescriptions]
  );

  // Authoritative metric units mapped from measurement catalog
  const [metricUnitsMap, setMetricUnitsMap] = useState<Map<string, number>>(new Map());

  useEffect(() => {
    if (!visible) return;
    let isCancelled = false;
    const fetchMetricUnits = async () => {
      try {
        const defs = await measurementApi.getMetricDefinitions();
        if (!isCancelled && defs && Array.isArray(defs)) {
          const map = new Map<string, number>();
          for (const def of defs) {
            if (def.defaultUnitCode && def.defaultUnitId != null) {
              const code = normalizeLoadUnitCode(def.defaultUnitCode);
              if (code) {
                map.set(code, def.defaultUnitId);
              }
            }
          }
          setMetricUnitsMap(map);
        }
      } catch {
        // Silently ignore if offline or mock doesn't implement getMetricDefinitions
      }
    };
    fetchMetricUnits();
    return () => {
      isCancelled = true;
    };
  }, [visible]);

  const resolveUnitId = useCallback(
    (unitCode?: string | null): number | null => {
      const norm = normalizeLoadUnitCode(unitCode);
      if (!norm) return null;

      // 1. Check unitReferences prop
      if (unitReferences && unitReferences.length > 0) {
        for (const ref of unitReferences) {
          if (ref.id != null && normalizeLoadUnitCode(ref.code) === norm) {
            return ref.id;
          }
        }
      }

      // 2. Check availablePrescriptions
      if (availablePrescriptions && availablePrescriptions.length > 0) {
        for (const p of availablePrescriptions) {
          if (p.loadUnitId != null && normalizeLoadUnitCode(p.loadUnit) === norm) {
            return p.loadUnitId;
          }
        }
      }

      // 3. Check proposal changes (before / after)
      if (proposal?.structuredPayload?.changes) {
        for (const ch of proposal.structuredPayload.changes) {
          if (ch.changeType === 'LOAD_ADJUSTMENT') {
            const b = ch.before as LoadBefore;
            const a = ch.after as LoadAfter;
            if (b?.loadUnitId != null && normalizeLoadUnitCode(b.loadUnit) === norm) {
              return b.loadUnitId;
            }
            if (a?.loadUnitId != null && normalizeLoadUnitCode(a.loadUnit) === norm) {
              return a.loadUnitId;
            }
          }
        }
      }

      // 4. Check metricUnitsMap loaded from metric definitions
      if (metricUnitsMap.has(norm)) {
        return metricUnitsMap.get(norm)!;
      }

      return null;
    },
    [availablePrescriptions, proposal, unitReferences, metricUnitsMap]
  );

  // Track previous inputs for state synchronization during render
  const [prevVisible, setPrevVisible] = useState(visible);
  const [prevStudentId, setPrevStudentId] = useState(studentId);
  const currentTargetId = occurrenceContext?.plannedWorkoutId || planContext?.planId;
  const [prevTargetId, setPrevTargetId] = useState(currentTargetId);
  const [prevInitialRecId, setPrevInitialRecId] = useState(initialRecommendationId);

  if (
    visible !== prevVisible ||
    studentId !== prevStudentId ||
    currentTargetId !== prevTargetId ||
    initialRecommendationId !== prevInitialRecId
  ) {
    setPrevVisible(visible);
    setPrevStudentId(studentId);
    setPrevTargetId(currentTargetId);
    setPrevInitialRecId(initialRecommendationId);

    setProposal(initialProposal ? toActiveProposal(initialProposal) : null);
    setAppliedResult(null);
    setErrorMessage(null);
    setConflictGuidance(null);
    setShareSuccessMessage(null);
    setFieldErrors({});
    setEditFieldErrors({});
    setDecisionNote('');
    setRejectReason('');
    setIsRejecting(false);
    setIsEditing(false);
    setIsGenerating(false);
    setIsDeciding(false);
    setIsSavingDraft(false);
    setIsSharing(false);
    setIsLoadingDetail(false);
    setIsCatalogPickerOpen(false);
    setLookupId(initialRecommendationId || '');

    setGenerateCommandKey(createCommandKey());
    setAcceptCommandKey(createCommandKey());
    if (availablePrescriptions.length > 0) {
      setSelectedPrescriptionId(availablePrescriptions[0].id);
    }
  }

  const handleLookupProposal = useCallback(
    async (id: string) => {
      if (!id.trim() || isGeneratingRef.current || isDecidingRef.current) return;
      setErrorMessage(null);
      setConflictGuidance(null);
      setShareSuccessMessage(null);
      setIsLoadingDetail(true);
      const currentSession = activeSessionIdRef.current;
      try {
        const detail = await aiWorkoutAdjustmentApi.getRecommendation(id.trim());
        if (currentSession !== activeSessionIdRef.current || !visible) return;
        setProposal(toActiveProposal(detail));
        setAcceptCommandKey(createCommandKey());
      } catch (err) {
        if (currentSession !== activeSessionIdRef.current || !visible) return;
        setErrorMessage(getWorkoutAdjustmentErrorMessage(err));
      } finally {
        if (currentSession === activeSessionIdRef.current) {
          setIsLoadingDetail(false);
        }
      }
    },
    [visible]
  );

  // Update session refs on visible/target/student/initialRecId change
  // Note: MUST NOT depend on `proposal` to avoid circular renders or invalidating active session
  useEffect(() => {
    activeSessionIdRef.current += 1;
    isGeneratingRef.current = false;
    isDecidingRef.current = false;
    isSavingDraftRef.current = false;
    isSharingRef.current = false;

    if (visible && initialRecommendationId) {
      const currentSession = activeSessionIdRef.current;
      Promise.resolve().then(() => {
        if (currentSession === activeSessionIdRef.current) {
          setIsLoadingDetail(true);
          aiWorkoutAdjustmentApi
            .getRecommendation(initialRecommendationId.trim())
            .then((detail) => {
              if (currentSession === activeSessionIdRef.current) {
                setProposal(toActiveProposal(detail));
                setAcceptCommandKey(createCommandKey());
                setIsLoadingDetail(false);
              }
            })
            .catch((err) => {
              if (currentSession === activeSessionIdRef.current) {
                setErrorMessage(getWorkoutAdjustmentErrorMessage(err));
                setIsLoadingDetail(false);
              }
            });
        }
      });
    }
  }, [visible, studentId, currentTargetId, initialRecommendationId]);

  const handleClose = () => {
    activeSessionIdRef.current += 1;
    isGeneratingRef.current = false;
    isDecidingRef.current = false;
    isSavingDraftRef.current = false;
    isSharingRef.current = false;
    setIsGenerating(false);
    setIsDeciding(false);
    setIsSavingDraft(false);
    setIsSharing(false);
    setIsEditing(false);
    setIsCatalogPickerOpen(false);
    onClose();
  };

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

  const handleReloadProposal = async () => {
    if (!proposal) return;
    await handleLookupProposal(proposal.recommendationId);
  };

  const handleGenerate = async () => {
    if (isGeneratingRef.current || isDecidingRef.current || isSavingDraftRef.current || isSharingRef.current) return;

    setFieldErrors({});
    setErrorMessage(null);
    setConflictGuidance(null);
    setShareSuccessMessage(null);

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

      if (currentSession !== activeSessionIdRef.current || !visible) {
        return;
      }

      setProposal(toActiveProposal(response));
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

  const handleStartEdit = () => {
    if (!proposal) return;
    setEditTitle(proposal.title || '');
    setEditExplanation(proposal.explanation || '');
    setEditFieldErrors({});
    setErrorMessage(null);
    setShareSuccessMessage(null);

    const ch = proposal.structuredPayload?.changes?.[0];
    if (ch) {
      if (ch.changeType === 'EXERCISE_SUBSTITUTION') {
        const after = ch.after as SubstitutionAfter;
        setEditReplacementVariationId(after?.replacementVariationId || '');
        setEditReplacementExerciseName(after?.replacementExerciseName || '');
        setEditMovementPattern(after?.movementPattern || '');
        setEditEquipmentType(after?.equipmentType || '');
        setEditSubstitutionCategory(after?.substitutionCategory || 'EQUIPMENT_UNAVAILABLE');
      } else if (ch.changeType === 'LOAD_ADJUSTMENT') {
        const after = ch.after as LoadAfter;
        const before = ch.before as LoadBefore;
        setEditTargetLoad(after?.targetLoad != null ? String(after.targetLoad) : '');
        const initialUnit =
          normalizeLoadUnitCode(after?.loadUnit) ||
          normalizeLoadUnitCode(before?.loadUnit) ||
          (after?.loadUnit ? after.loadUnit.trim().toUpperCase() : (before?.loadUnit ? before.loadUnit.trim().toUpperCase() : ''));
        setEditLoadUnit(initialUnit);
        let matchedId: number | null = null;
        if (after?.loadUnitId != null && normalizeLoadUnitCode(after.loadUnit) === initialUnit) {
          matchedId = after.loadUnitId;
        } else if (before?.loadUnitId != null && normalizeLoadUnitCode(before.loadUnit) === initialUnit) {
          matchedId = before.loadUnitId;
        } else {
          matchedId = resolveUnitId(initialUnit);
        }
        setEditLoadUnitId(matchedId);
      } else if (ch.changeType === 'VOLUME_ADJUSTMENT') {
        const after = ch.after as VolumeAfter;
        setEditTargetSets(after?.targetSets != null ? String(after.targetSets) : '');
        setEditTargetRepsMin(after?.targetRepsMin != null ? String(after.targetRepsMin) : '');
        setEditTargetRepsMax(after?.targetRepsMax != null ? String(after.targetRepsMax) : '');
        setEditDurationSeconds(after?.durationSeconds != null ? String(after.durationSeconds) : '');
      }
    }
    setIsEditing(true);
  };

  // Exercise catalog search & variation selection handlers
  const handleSearchCatalog = async () => {
    setIsSearchingCatalog(true);
    try {
      const page = await exerciseApi.getCatalog({
        query: catalogSearchQuery.trim() || undefined,
        size: 15,
      });
      setCatalogExercises(page.items);
      setSelectedCatalogExercise(null);
      setCatalogVariations([]);
    } catch {
      setCatalogExercises([]);
    } finally {
      setIsSearchingCatalog(false);
    }
  };

  const handleSelectCatalogExercise = async (exercise: ExerciseCatalogItem) => {
    setSelectedCatalogExercise(exercise);
    setIsLoadingVariations(true);
    try {
      const detail = await exerciseApi.getDetail(exercise.id);
      setCatalogVariations(detail.variations || []);
    } catch {
      setCatalogVariations([]);
    } finally {
      setIsLoadingVariations(false);
    }
  };

  const handleSelectVariation = (variation: ExerciseVariation) => {
    setEditReplacementVariationId(variation.id);
    setEditReplacementExerciseName(variation.name);
    if (selectedCatalogExercise?.movementPattern) {
      setEditMovementPattern(selectedCatalogExercise.movementPattern);
    }
    const equip = variation.equipment?.[0]?.code || selectedCatalogExercise?.equipment?.[0]?.code || '';
    if (equip) {
      setEditEquipmentType(equip);
    }
    setIsCatalogPickerOpen(false);
    setSelectedCatalogExercise(null);
    setCatalogVariations([]);
  };

  const handleSaveDraft = async () => {
    if (!proposal || isSavingDraftRef.current || isDecidingRef.current) return;
    const errors: Record<string, string> = {};
    if (!editTitle.trim()) {
      errors.editTitle = 'Tiêu đề đề xuất không được để trống.';
    }

    const firstChange = proposal.structuredPayload?.changes?.[0];

    // Validate Exercise Substitution
    if (firstChange?.changeType === 'EXERCISE_SUBSTITUTION') {
      const varId = editReplacementVariationId.trim();
      if (!varId) {
        errors.editReplacementVariationId = 'Vui lòng chọn hoặc nhập biến thể bài tập thay thế.';
      } else if (!UUID_REGEX.test(varId)) {
        errors.editReplacementVariationId = 'Mã biến thể bài tập (UUID) không hợp lệ.';
      }
      const beforeVarId = (firstChange.before as SubstitutionBefore)?.exerciseVariationId;
      if (beforeVarId && varId.toLowerCase() === beforeVarId.toLowerCase()) {
        errors.editReplacementVariationId = 'Biến thể thay thế không được trùng với biến thể bài tập gốc.';
      }
      if (!editReplacementExerciseName.trim()) {
        errors.editReplacementExerciseName = 'Tên bài tập thay thế không được để trống.';
      }
    }

    // Validate Load Adjustment
    if (firstChange?.changeType === 'LOAD_ADJUSTMENT') {
      const beforeLoadObj = firstChange.before as LoadBefore;
      const beforeTargetLoad = beforeLoadObj?.targetLoad;

      if (!editTargetLoad.trim()) {
        errors.editTargetLoad = 'Vui lòng nhập mức tạ mục tiêu!';
      } else {
        const parsedTarget = Number(editTargetLoad.trim());
        if (isNaN(parsedTarget)) {
          errors.editTargetLoad = 'Mức tạ phải là một số hợp lệ!';
        } else if (parsedTarget < 0) {
          errors.editTargetLoad = 'Mức tạ không được âm!';
        } else if (beforeTargetLoad == null) {
          // Baseline load is unknown, missing, or bodyweight.
          // Missing data is unknown, never zero. Cannot deduce load increase or delta from unknown baseline.
          errors.editTargetLoad = 'Không thể tính chênh lệch tải khi mức tạ ban đầu chưa xác định (unknown) hoặc là bài tự trọng.';
        } else {
          const beforeLoad = Number(beforeTargetLoad);
          const beforeUnit = normalizeLoadUnitCode(beforeLoadObj?.loadUnit) || 'KG';
          const targetUnit = editLoadUnit.trim()
            ? (normalizeLoadUnitCode(editLoadUnit) || editLoadUnit.trim().toUpperCase())
            : beforeUnit;

          // Validate unit ID resolution
          const resolvedId = (targetUnit && editLoadUnitId != null && resolveUnitId(targetUnit) === editLoadUnitId)
            ? editLoadUnitId
            : resolveUnitId(targetUnit);

          if (resolvedId == null) {
            errors.editLoadUnit = `Không thể xác định mã định danh đơn vị cho "${editLoadUnit.trim()}". Vui lòng chọn đơn vị có trong giáo án hoặc danh mục hệ thống.`;
          }

          let beforeInTargetUnit = beforeLoad;
          if (beforeUnit !== targetUnit) {
            if (beforeUnit === 'LB' && targetUnit === 'KG') {
              beforeInTargetUnit = Number((beforeLoad * LB_TO_KG_MULTIPLIER).toFixed(2));
            } else if (beforeUnit === 'KG' && targetUnit === 'LB') {
              beforeInTargetUnit = Number((beforeLoad * KG_TO_LB_MULTIPLIER).toFixed(2));
            }
          }

          const deltaLoad = Number((parsedTarget - beforeInTargetUnit).toFixed(2));
          if (Math.abs(deltaLoad) < 0.001) {
            errors.editTargetLoad = 'Mức tạ mới phải khác mức tạ hiện tại (tăng hoặc giảm)!';
          }
        }
      }
    }

    // Validate Volume Adjustment
    if (firstChange?.changeType === 'VOLUME_ADJUSTMENT') {
      let parsedSets: number | null = null;
      if (editTargetSets.trim()) {
        if (!INTEGER_REGEX.test(editTargetSets.trim())) {
          errors.editTargetSets = 'Số hiệp phải là số nguyên!';
        } else {
          parsedSets = parseInt(editTargetSets.trim(), 10);
          if (parsedSets < 1 || parsedSets > 20) {
            errors.editTargetSets = 'Số hiệp phải từ 1 đến 20 hiệp!';
          }
        }
      }

      let parsedRepsMin: number | null = null;
      if (editTargetRepsMin.trim()) {
        if (!INTEGER_REGEX.test(editTargetRepsMin.trim())) {
          errors.editTargetRepsMin = 'Số reps tối thiểu phải là số nguyên!';
        } else {
          parsedRepsMin = parseInt(editTargetRepsMin.trim(), 10);
          if (parsedRepsMin < 0 || parsedRepsMin > 100) {
            errors.editTargetRepsMin = 'Số reps tối thiểu phải từ 0 đến 100!';
          }
        }
      }

      let parsedRepsMax: number | null = null;
      if (editTargetRepsMax.trim()) {
        if (!INTEGER_REGEX.test(editTargetRepsMax.trim())) {
          errors.editTargetRepsMax = 'Số reps tối đa phải là số nguyên!';
        } else {
          parsedRepsMax = parseInt(editTargetRepsMax.trim(), 10);
          if (parsedRepsMax < 0 || parsedRepsMax > 100) {
            errors.editTargetRepsMax = 'Số reps tối đa phải từ 0 đến 100!';
          }
        }
      }

      if (parsedRepsMin !== null && parsedRepsMax !== null && parsedRepsMax < parsedRepsMin) {
        errors.editTargetRepsMax = 'Số reps tối đa không được nhỏ hơn số reps tối thiểu!';
      }

      if (editDurationSeconds.trim()) {
        if (!INTEGER_REGEX.test(editDurationSeconds.trim())) {
          errors.editDurationSeconds = 'Thời lượng giữ phải là số nguyên!';
        } else {
          const parsedDuration = parseInt(editDurationSeconds.trim(), 10);
          if (parsedDuration < 0 || parsedDuration > 3600) {
            errors.editDurationSeconds = 'Thời lượng giữ phải từ 0 đến 3600 giây!';
          }
        }
      }
    }

    if (Object.keys(errors).length > 0) {
      setEditFieldErrors(errors);
      return;
    }

    const currentSession = activeSessionIdRef.current;
    isSavingDraftRef.current = true;
    setIsSavingDraft(true);
    setErrorMessage(null);
    setConflictGuidance(null);

    const updatedChanges = (proposal.structuredPayload?.changes || []).map((ch, idx) => {
      if (idx !== 0) return ch;

      if (ch.changeType === 'EXERCISE_SUBSTITUTION') {
        const beforeSub = ch.before as SubstitutionBefore;
        const afterSub = ch.after as SubstitutionAfter;
        return {
          ...ch,
          after: {
            ...ch.after,
            replacementVariationId: editReplacementVariationId.trim(),
            replacementExerciseName: editReplacementExerciseName.trim(),
            movementPattern: editMovementPattern || afterSub?.movementPattern || beforeSub?.movementPattern || 'UNKNOWN',
            equipmentType: editEquipmentType || afterSub?.equipmentType || beforeSub?.equipmentType || 'UNKNOWN',
            substitutionCategory: editSubstitutionCategory.trim() || 'EQUIPMENT_UNAVAILABLE',
          },
        };
      }

      if (ch.changeType === 'LOAD_ADJUSTMENT') {
        const beforeLoadObj = ch.before as LoadBefore;
        const beforeTargetLoad = beforeLoadObj?.targetLoad;
        const parsedTarget = Number(editTargetLoad.trim());

        const beforeUnit = normalizeLoadUnitCode(beforeLoadObj?.loadUnit) || 'KG';
        const targetUnit = editLoadUnit.trim()
          ? (normalizeLoadUnitCode(editLoadUnit) || editLoadUnit.trim().toUpperCase())
          : beforeUnit;

        let beforeInTargetUnit = beforeTargetLoad != null ? Number(beforeTargetLoad) : 0;
        if (beforeTargetLoad != null && beforeUnit !== targetUnit) {
          if (beforeUnit === 'LB' && targetUnit === 'KG') {
            beforeInTargetUnit = Number((Number(beforeTargetLoad) * LB_TO_KG_MULTIPLIER).toFixed(2));
          } else if (beforeUnit === 'KG' && targetUnit === 'LB') {
            beforeInTargetUnit = Number((Number(beforeTargetLoad) * KG_TO_LB_MULTIPLIER).toFixed(2));
          }
        }

        const deltaLoad = beforeTargetLoad != null ? Number((parsedTarget - beforeInTargetUnit).toFixed(2)) : null;
        const direction = deltaLoad != null ? (deltaLoad > 0 ? 'INCREASE' : 'DECREASE') : 'MAINTAIN';
        const deltaPercent = beforeTargetLoad != null && beforeInTargetUnit > 0
          ? Number((((parsedTarget - beforeInTargetUnit) / beforeInTargetUnit) * 100).toFixed(1))
          : null;

        // Authoritative unit ID resolution without hardcoded 1/2
        const resolvedUnitId = (targetUnit && editLoadUnitId != null && resolveUnitId(targetUnit) === editLoadUnitId)
          ? editLoadUnitId
          : resolveUnitId(targetUnit);

        return {
          ...ch,
          after: {
            ...ch.after,
            targetLoad: parsedTarget,
            loadUnit: targetUnit,
            loadUnitId: resolvedUnitId,
            direction,
            deltaLoad,
            deltaPercent,
          },
        };
      }

      if (ch.changeType === 'VOLUME_ADJUSTMENT') {
        const beforeVol = ch.before as VolumeBefore;
        const parsedSets = editTargetSets.trim() ? parseInt(editTargetSets.trim(), 10) : null;
        const parsedRepsMin = editTargetRepsMin.trim() ? parseInt(editTargetRepsMin.trim(), 10) : null;
        const parsedRepsMax = editTargetRepsMax.trim() ? parseInt(editTargetRepsMax.trim(), 10) : null;
        const parsedDuration = editDurationSeconds.trim() ? parseInt(editDurationSeconds.trim(), 10) : null;

        const beforeSets = beforeVol?.targetSets != null ? Number(beforeVol.targetSets) : null;
        const deltaSets = parsedSets !== null && beforeSets !== null ? parsedSets - beforeSets : 0;

        const beforeRepsMin = beforeVol?.targetRepsMin != null ? Number(beforeVol.targetRepsMin) : null;
        const deltaRepsMin = parsedRepsMin !== null && beforeRepsMin !== null ? parsedRepsMin - beforeRepsMin : null;

        const beforeRepsMax = beforeVol?.targetRepsMax != null ? Number(beforeVol.targetRepsMax) : null;
        const deltaRepsMax = parsedRepsMax !== null && beforeRepsMax !== null ? parsedRepsMax - beforeRepsMax : null;

        const beforeDuration = beforeVol?.durationSeconds != null ? Number(beforeVol.durationSeconds) : null;
        const deltaDurationSeconds = parsedDuration !== null && beforeDuration !== null ? parsedDuration - beforeDuration : null;

        return {
          ...ch,
          after: {
            ...ch.after,
            targetSets: parsedSets,
            targetRepsMin: parsedRepsMin,
            targetRepsMax: parsedRepsMax,
            durationSeconds: parsedDuration,
            deltaSets,
            deltaRepsMin,
            deltaRepsMax,
            deltaDurationSeconds,
          },
        };
      }

      return ch;
    });

    const updatedPayload: F03WorkoutAdjustmentPayload = {
      ...proposal.structuredPayload,
      changes: updatedChanges,
    };

    try {
      const res = await aiWorkoutAdjustmentApi.updateDraft(proposal.recommendationId, {
        expectedRevision: proposal.revision,
        title: editTitle.trim(),
        explanation: editExplanation.trim(),
        structuredPayload: updatedPayload,
      });

      if (currentSession !== activeSessionIdRef.current || !visible) return;

      setProposal((prev) =>
        prev
          ? {
              ...prev,
              revision: res.revision,
              isCustomized: res.isCustomized,
              status: res.status,
              title: res.title,
              explanation: res.explanation,
              updatedAt: res.updatedAt,
              structuredPayload: res.structuredPayload,
              originalPayload: res.originalPayload,
              diff: res.diff,
            }
          : null
      );
      setIsEditing(false);
    } catch (err: any) {
      if (currentSession !== activeSessionIdRef.current || !visible) return;
      if (err?.status === 409) {
        setConflictGuidance(
          'Bản thảo đã bị chỉnh sửa ở một phiên khác (xung đột phiên bản). Vui lòng tải lại đề xuất mới nhất.'
        );
      }
      setErrorMessage(getWorkoutAdjustmentErrorMessage(err));
    } finally {
      if (currentSession === activeSessionIdRef.current) {
        isSavingDraftRef.current = false;
        setIsSavingDraft(false);
      }
    }
  };

  const handleShareDraft = async () => {
    if (!proposal || isSharingRef.current || isDecidingRef.current) return;

    const currentSession = activeSessionIdRef.current;
    isSharingRef.current = true;
    setIsSharing(true);
    setErrorMessage(null);
    setConflictGuidance(null);
    setShareSuccessMessage(null);

    try {
      const res = await aiWorkoutAdjustmentApi.shareDraft(proposal.recommendationId, {
        expectedRevision: proposal.revision,
      });

      if (currentSession !== activeSessionIdRef.current || !visible) return;

      setProposal((prev) =>
        prev
          ? {
              ...prev,
              sharedWithStudent: res.sharedWithStudent,
              sharedRevision: res.sharedRevision,
              sharedAt: res.sharedAt,
              sharedBy: res.sharedBy,
            }
          : null
      );
      setShareSuccessMessage(`Đã chia sẻ bản thảo v${res.sharedRevision} cho học viên thành công.`);
    } catch (err: any) {
      if (currentSession !== activeSessionIdRef.current || !visible) return;
      if (err?.status === 409) {
        setConflictGuidance(
          'Bản thảo đã có chỉnh sửa mới hoặc trạng thái buổi tập thay đổi. Vui lòng tải lại trước khi chia sẻ.'
        );
      }
      setErrorMessage(getWorkoutAdjustmentErrorMessage(err));
    } finally {
      if (currentSession === activeSessionIdRef.current) {
        isSharingRef.current = false;
        setIsSharing(false);
      }
    }
  };

  const handleAccept = async () => {
    if (!proposal || isDecidingRef.current || isGeneratingRef.current) return;

    setErrorMessage(null);
    setConflictGuidance(null);
    setShareSuccessMessage(null);

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
        expectedRevision: proposal.revision,
        expectedTargetVersion,
      });

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
    setShareSuccessMessage(null);

    const currentSession = activeSessionIdRef.current;
    isDecidingRef.current = true;
    setIsDeciding(true);

    try {
      await aiWorkoutAdjustmentApi.reject(proposal.recommendationId, {
        reason: rejectReason.trim() || undefined,
      });

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

  // Authority rules
  const canUserDecide =
    proposal &&
    ((proposal.decisionAuthority === 'STUDENT' && !isTrainer) ||
      (proposal.decisionAuthority === 'TRAINER' && isTrainer));

  const canUserEdit =
    proposal &&
    ((proposal.decisionAuthority === 'TRAINER' && isTrainer) ||
      (proposal.decisionAuthority === 'STUDENT' && !isTrainer));

  const canUserShare =
    proposal &&
    isTrainer &&
    proposal.decisionAuthority === 'TRAINER';

  const isAlreadyDecided =
    proposal?.status === 'APPLIED' ||
    proposal?.status === 'REJECTED' ||
    proposal?.status === 'EXPIRED' ||
    proposal?.status === 'CANCELLED';

  const isStudentInHumanCoach =
    proposal?.decisionAuthority === 'TRAINER' && !isTrainer;

  const hasUnsharedChanges = Boolean(
    proposal?.sharedWithStudent &&
    proposal?.sharedRevision != null &&
    proposal.sharedRevision < (proposal.revision ?? 1)
  );

  const isAnyOperationInFlight =
    isGenerating || isDeciding || isSavingDraft || isSharing || isLoadingDetail;

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

      const isConfirmedBodyweight = (
        val: number | null | undefined,
        unit: string | null | undefined
      ): boolean => {
        if (val !== null && val !== undefined) return false;
        if (unit !== null && unit !== undefined) return false;

        const text = `${proposal?.title || ''} ${proposal?.explanation || ''} ${proposal?.structuredPayload?.summary || ''} ${change?.reason || ''}`.toLowerCase();
        if (
          text.includes('bodyweight') ||
          text.includes('tự trọng') ||
          text.includes('trọng lượng cơ thể')
        ) {
          return true;
        }

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
          return 'Trọng lượng cơ thể';
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

  const firstChange = proposal?.structuredPayload?.changes?.[0];

  return (
    <Modal visible={visible} animationType="slide" transparent={false} onRequestClose={handleClose}>
      <View
        testID="ai-workout-adjustment-modal"
        style={[styles.container, { backgroundColor: theme.canvas }]}
      >
        {/* Header */}
        <View style={[styles.header, { borderBottomColor: theme.border }]}>
          <Text accessibilityRole="header" style={[styles.headerTitle, { color: theme.textPrimary }]}>
            Đề xuất điều chỉnh giáo án bằng AI
          </Text>
          <TouchableOpacity
            testID="close-adjustment-modal"
            accessibilityRole="button"
            accessibilityLabel="Đóng"
            disabled={isAnyOperationInFlight}
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

          {/* Conflict Guidance & Reload Button */}
          {conflictGuidance ? (
            <WorkoutCard testID="adjustment-conflict-guidance">
              <Text style={[styles.conflictTitle, { color: theme.warningText }]}>
                Xung đột phiên bản
              </Text>
              <Text style={[styles.conflictBody, { color: theme.textSecondary }]}>
                {conflictGuidance}
              </Text>
              {proposal ? (
                <View style={{ marginTop: spacing.sm }}>
                  <WorkoutButton
                    testID="adjustment-reload-conflict-button"
                    label="Tải lại dữ liệu đề xuất mới nhất"
                    secondary
                    disabled={isAnyOperationInFlight}
                    onPress={handleReloadProposal}
                  />
                </View>
              ) : null}
            </WorkoutCard>
          ) : null}

          {/* Share Success Message */}
          {shareSuccessMessage ? (
            <WorkoutCard testID="adjustment-share-success">
              <Text style={[styles.appliedTitle, { color: theme.primary }]}>
                {shareSuccessMessage}
              </Text>
            </WorkoutCard>
          ) : null}

          {/* Form View (Visible when no proposal) */}
          {!proposal ? (
            <View testID="adjustment-request-form" style={styles.formContainer}>
              {/* Lookup Proposal by ID */}
              <WorkoutCard>
                <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                  Tìm đề xuất đã có
                </Text>
                <Text style={[styles.hintText, { color: theme.textSecondary }]}>
                  Nhập mã đề xuất để xem bản thảo hoặc đề xuất được chia sẻ.
                </Text>
                <View style={styles.row}>
                  <TextInput
                    testID="adjustment-lookup-input"
                    accessibilityLabel="Mã đề xuất"
                    placeholder="Nhập mã đề xuất (UUID)..."
                    value={lookupId}
                    onChangeText={setLookupId}
                    style={[styles.input, { flex: 1, borderColor: theme.border, color: theme.textPrimary }]}
                  />
                  <WorkoutButton
                    testID="adjustment-lookup-button"
                    label={isLoadingDetail ? 'Đang tải...' : 'Xem'}
                    disabled={isAnyOperationInFlight || !lookupId.trim()}
                    busy={isLoadingDetail}
                    onPress={() => handleLookupProposal(lookupId.trim())}
                  />
                </View>
              </WorkoutCard>

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
                    disabled={isAnyOperationInFlight}
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
                    disabled={isAnyOperationInFlight}
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
                          disabled={isAnyOperationInFlight}
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
                    disabled={isAnyOperationInFlight}
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
                      Thay bài tập tương đương
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    testID="adjustment-change-type-load"
                    accessibilityRole="button"
                    disabled={isAnyOperationInFlight}
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
                      Điều chỉnh mức tạ
                    </Text>
                  </TouchableOpacity>

                  <TouchableOpacity
                    testID="adjustment-change-type-volume"
                    accessibilityRole="button"
                    disabled={isAnyOperationInFlight}
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
                      Điều chỉnh hiệp / lần lặp / thời lượng
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
                        disabled={isAnyOperationInFlight}
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
                    disabled={isAnyOperationInFlight}
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

                {/* Allow Load Increase */}
                {changeType !== 'EXERCISE_SUBSTITUTION' ? (
                  <View style={{ marginTop: spacing.md }}>
                    <TouchableOpacity
                      testID="adjustment-allow-load-increase-toggle"
                      accessibilityRole="button"
                      disabled={isAnyOperationInFlight}
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
                disabled={isAnyOperationInFlight || availablePrescriptions.length === 0}
                busy={isGenerating}
                onPress={handleGenerate}
              />
            </View>
          ) : null}

          {/* Proposal Review / Edit View */}
          {proposal ? (
            <View testID="adjustment-proposal-view" style={styles.proposalContainer}>
              {/* Proposal Header */}
              <WorkoutCard>
                <View style={styles.proposalHeaderRow}>
                  <Text style={[styles.badge, { backgroundColor: theme.primary, color: theme.textOnPrimary }]}>
                    {aiRecommendationStatusLabels[proposal.status]}
                  </Text>
                  <Text testID="adjustment-revision-badge" style={[styles.badge, { backgroundColor: theme.surface, color: theme.textPrimary, borderWidth: 1, borderColor: theme.border }]}>
                    v{proposal.revision} {proposal.isCustomized ? '(Đã tùy chỉnh)' : '(Bản gốc AI)'}
                  </Text>
                  <Text style={[styles.authorityLabel, { color: theme.textSecondary }]}>
                    Thẩm quyền: {proposal.decisionAuthority}
                  </Text>
                </View>

                {/* Sharing Status Badge for Trainer / Student */}
                <View style={styles.sharingStatusRow}>
                  {canUserShare ? (
                    proposal.sharedWithStudent ? (
                      <Text testID="adjustment-shared-badge" style={[styles.badge, { backgroundColor: theme.primary, color: theme.textOnPrimary }]}>
                        {`Đã chia sẻ (v${proposal.sharedRevision})`}
                      </Text>
                    ) : (
                      <Text testID="adjustment-private-badge" style={[styles.badge, { backgroundColor: theme.surface, color: theme.textSecondary, borderWidth: 1, borderColor: theme.border }]}>
                        Bản nháp riêng tư
                      </Text>
                    )
                  ) : null}

                  {isStudentInHumanCoach && proposal.sharedWithStudent ? (
                    <Text testID="adjustment-student-shared-badge" style={[styles.badge, { backgroundColor: theme.primary, color: theme.textOnPrimary }]}>
                      Bản chia sẻ (v{proposal.sharedRevision ?? proposal.revision})
                    </Text>
                  ) : null}
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

              {/* Student Shared Banner in HUMAN_COACH when proposal is shared */}
              {isStudentInHumanCoach && proposal.sharedWithStudent ? (
                <WorkoutCard testID="adjustment-student-shared-banner">
                  <Text style={[styles.warningTitle, { color: theme.primary }]}>
                    Đề xuất từ Huấn luyện viên (Bản nháp tham khảo)
                  </Text>
                  <Text style={[styles.warningBody, { color: theme.textSecondary }]}>
                    Huấn luyện viên đã chia sẻ bản thảo điều chỉnh này (phiên bản v{proposal.sharedRevision ?? proposal.revision}). Bạn có thể xem trước nội dung bên dưới. Quyết định áp dụng thuộc quyền Huấn luyện viên.
                  </Text>
                </WorkoutCard>
              ) : null}

              {/* Trainer Unshared Changes Warning */}
              {canUserShare && hasUnsharedChanges ? (
                <WorkoutCard testID="adjustment-unshared-warning">
                  <Text style={[styles.warningTitle, { color: theme.warningText }]}>
                    Có chỉnh sửa mới chưa chia sẻ
                  </Text>
                  <Text style={[styles.warningBody, { color: theme.textSecondary }]}>
                    Bạn đang có chỉnh sửa mới (v{proposal.revision}) chưa chia sẻ lại cho học viên. Học viên hiện đang thấy phiên bản đã chia sẻ v{proposal.sharedRevision}.
                  </Text>
                  <View style={{ marginTop: spacing.sm }}>
                    <WorkoutButton
                      testID="adjustment-reshare-button"
                      label={isSharing ? 'Đang chia sẻ...' : 'Chia sẻ lại cho học viên'}
                      busy={isSharing}
                      disabled={isAnyOperationInFlight}
                      onPress={handleShareDraft}
                    />
                  </View>
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

              {/* Structured Diff Card (when diff is available) */}
              {proposal.diff && proposal.diff.hasChanges ? (
                <WorkoutCard testID="adjustment-diff-summary-card">
                  <View style={styles.diffSummaryHeader}>
                    <Text style={[styles.sectionTitle, { color: theme.primary }]}>
                      So sánh bản sửa đổi
                    </Text>
                    <Text style={[styles.badge, { backgroundColor: theme.primary, color: theme.textOnPrimary }]}>
                      {proposal.diff.changeType || 'CUSTOMIZED'}
                    </Text>
                  </View>
                  <Text style={[styles.hintText, { color: theme.textSecondary }]}>
                    {proposal.diff.summary || 'Các thay đổi đã lưu:'}
                  </Text>
                  {proposal.diff.fieldDiffs.map((fd, idx) => (
                    <View key={idx} testID={`field-diff-${fd.field}`} style={styles.fieldDiffRow}>
                      <Text style={[styles.fieldDiffName, { color: theme.textPrimary }]}>
                        {fd.field}:
                      </Text>
                      <Text style={[styles.fieldDiffBefore, { color: theme.dangerText }]}>
                        {String(('originalValue' in fd ? fd.originalValue : fd.before) ?? 'null')}
                      </Text>
                      <Text style={[styles.fieldDiffArrow, { color: theme.textSecondary }]}>→</Text>
                      <Text style={[styles.fieldDiffAfter, { color: theme.primary }]}>
                        {String(('customizedValue' in fd ? fd.customizedValue : fd.after) ?? 'null')}
                      </Text>
                      {fd.delta !== null && fd.delta !== undefined ? (
                        <Text style={[styles.fieldDiffDelta, { color: theme.warningText }]}>
                          ({fd.delta > 0 ? `+${fd.delta}` : fd.delta})
                        </Text>
                      ) : null}
                    </View>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Edit Mode Card */}
              {isEditing ? (
                <WorkoutCard testID="adjustment-edit-card">
                  <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                    Chỉnh sửa bản thảo đề xuất (v{proposal.revision} → v{proposal.revision + 1})
                  </Text>

                  {/* Title & Explanation */}
                  <Text style={[styles.label, { color: theme.textPrimary }]}>Tiêu đề đề xuất</Text>
                  <TextInput
                    testID="adjustment-edit-title-input"
                    accessibilityLabel="Tiêu đề đề xuất"
                    value={editTitle}
                    onChangeText={setEditTitle}
                    style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                  />
                  {editFieldErrors.editTitle ? (
                    <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                      {editFieldErrors.editTitle}
                    </Text>
                  ) : null}

                  <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                    Giải thích đề xuất
                  </Text>
                  <TextInput
                    testID="adjustment-edit-explanation-input"
                    accessibilityLabel="Giải thích đề xuất"
                    value={editExplanation}
                    onChangeText={setEditExplanation}
                    multiline
                    style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                  />

                  {/* Typed Fields according to changeType */}
                  {firstChange?.changeType === 'EXERCISE_SUBSTITUTION' ? (
                    <View style={{ marginTop: spacing.md }}>
                      <Text style={[styles.label, { color: theme.textPrimary }]}>
                        Biến thể bài tập thay thế
                      </Text>

                      {/* Display current selected variation info */}
                      {editReplacementVariationId ? (
                        <View testID="adjustment-selected-variation-info" style={styles.selectedVariationBox}>
                          <Text style={[styles.selectedVariationTitle, { color: theme.textPrimary }]}>
                            {editReplacementExerciseName || 'Chưa có tên biến thể'}
                          </Text>
                          <Text style={[styles.selectedVariationSub, { color: theme.textSecondary }]}>
                            ID: {editReplacementVariationId}
                          </Text>
                        </View>
                      ) : null}

                      {/* Button to open catalog picker */}
                      <WorkoutButton
                        testID="adjustment-open-catalog-picker-button"
                        label="Chọn từ danh mục bài tập"
                        secondary
                        disabled={isAnyOperationInFlight}
                        onPress={() => {
                          setIsCatalogPickerOpen(true);
                          if (catalogExercises.length === 0) {
                            handleSearchCatalog();
                          }
                        }}
                      />

                      {/* Direct inputs for variation ID, name and category */}
                      <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                        Mã biến thể bài tập (UUID)
                      </Text>
                      <TextInput
                        testID="adjustment-edit-replacement-id-input"
                        accessibilityLabel="Mã biến thể bài tập thay thế"
                        value={editReplacementVariationId}
                        onChangeText={setEditReplacementVariationId}
                        placeholder="xxxxxxxx-xxxx-xxxx-xxxx-xxxxxxxxxxxx"
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editReplacementVariationId ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editReplacementVariationId}
                        </Text>
                      ) : null}

                      <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                        Tên bài tập thay thế
                      </Text>
                      <TextInput
                        testID="adjustment-edit-replacement-name-input"
                        accessibilityLabel="Tên bài tập thay thế"
                        value={editReplacementExerciseName}
                        onChangeText={setEditReplacementExerciseName}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editReplacementExerciseName ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editReplacementExerciseName}
                        </Text>
                      ) : null}

                      <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                        Phân loại lý do thay thế
                      </Text>
                      <TextInput
                        testID="adjustment-edit-substitution-category-input"
                        accessibilityLabel="Phân loại thay thế"
                        value={editSubstitutionCategory}
                        onChangeText={setEditSubstitutionCategory}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                    </View>
                  ) : null}

                  {firstChange?.changeType === 'LOAD_ADJUSTMENT' ? (
                    <View style={{ marginTop: spacing.md }}>
                      <Text style={[styles.label, { color: theme.textPrimary }]}>
                        Mức tạ mục tiêu mới
                      </Text>
                      <TextInput
                        testID="adjustment-edit-target-load-input"
                        accessibilityLabel="Mức tạ mục tiêu"
                        keyboardType="numeric"
                        value={editTargetLoad}
                        onChangeText={setEditTargetLoad}
                        placeholder="Ví dụ: 82.5"
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editTargetLoad ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editTargetLoad}
                        </Text>
                      ) : null}

                      <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                        Đơn vị tạ
                      </Text>
                      <TextInput
                        testID="adjustment-edit-load-unit-input"
                        accessibilityLabel="Đơn vị tạ"
                        value={editLoadUnit}
                        onChangeText={(txt) => {
                          setEditLoadUnit(txt);
                          const norm = normalizeLoadUnitCode(txt);
                          const resolved = resolveUnitId(norm);
                          setEditLoadUnitId(resolved);
                          if (txt.trim() && !resolved) {
                            setEditFieldErrors((prev) => ({
                              ...prev,
                              editLoadUnit: `Không thể xác định mã định danh đơn vị cho "${txt.trim()}". Vui lòng chọn đơn vị có trong giáo án hoặc danh mục hệ thống.`,
                            }));
                          } else {
                            setEditFieldErrors((prev) => {
                              const next = { ...prev };
                              delete next.editLoadUnit;
                              return next;
                            });
                          }
                        }}
                        placeholder="KG hoặc LBS"
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editLoadUnit ? (
                        <Text testID="adjustment-edit-load-unit-error" style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editLoadUnit}
                        </Text>
                      ) : null}
                    </View>
                  ) : null}

                  {firstChange?.changeType === 'VOLUME_ADJUSTMENT' ? (
                    <View style={{ marginTop: spacing.md }}>
                      <Text style={[styles.label, { color: theme.textPrimary }]}>Số hiệp (Sets, 1–20)</Text>
                      <TextInput
                        testID="adjustment-edit-sets-input"
                        accessibilityLabel="Số hiệp"
                        keyboardType="numeric"
                        value={editTargetSets}
                        onChangeText={setEditTargetSets}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editTargetSets ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editTargetSets}
                        </Text>
                      ) : null}

                      <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                        Số reps tối thiểu
                      </Text>
                      <TextInput
                        testID="adjustment-edit-reps-min-input"
                        accessibilityLabel="Số reps tối thiểu"
                        keyboardType="numeric"
                        value={editTargetRepsMin}
                        onChangeText={setEditTargetRepsMin}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editTargetRepsMin ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editTargetRepsMin}
                        </Text>
                      ) : null}

                      <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                        Số reps tối đa
                      </Text>
                      <TextInput
                        testID="adjustment-edit-reps-max-input"
                        accessibilityLabel="Số reps tối đa"
                        keyboardType="numeric"
                        value={editTargetRepsMax}
                        onChangeText={setEditTargetRepsMax}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editTargetRepsMax ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editTargetRepsMax}
                        </Text>
                      ) : null}

                      <Text style={[styles.label, { color: theme.textPrimary, marginTop: spacing.sm }]}>
                        Thời lượng giữ (giây, durationSeconds)
                      </Text>
                      <TextInput
                        testID="adjustment-edit-duration-input"
                        accessibilityLabel="Thời lượng giữ"
                        keyboardType="numeric"
                        value={editDurationSeconds}
                        onChangeText={setEditDurationSeconds}
                        style={[styles.input, { borderColor: theme.border, color: theme.textPrimary }]}
                      />
                      {editFieldErrors.editDurationSeconds ? (
                        <Text style={[styles.fieldError, { color: theme.dangerText }]}>
                          {editFieldErrors.editDurationSeconds}
                        </Text>
                      ) : null}
                    </View>
                  ) : null}

                  {/* Edit Actions */}
                  <View style={styles.actionRow}>
                    <WorkoutButton
                      testID="adjustment-save-draft-button"
                      label={isSavingDraft ? 'Đang lưu bản nháp...' : 'Lưu bản chỉnh sửa'}
                      busy={isSavingDraft}
                      disabled={isAnyOperationInFlight}
                      onPress={handleSaveDraft}
                    />
                    <WorkoutButton
                      testID="adjustment-cancel-edit-button"
                      label="Hủy chỉnh sửa"
                      secondary
                      disabled={isAnyOperationInFlight}
                      onPress={() => setIsEditing(false)}
                    />
                  </View>
                </WorkoutCard>
              ) : null}

              {/* Before / After Diff Cards */}
              {!isEditing ? (
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
              ) : null}

              {/* Reasoning, Used Data & Missing Data */}
              {!isEditing && proposal.structuredPayload?.reasoning?.length ? (
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

              {!isEditing && proposal.structuredPayload?.missingData?.length ? (
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

              {/* Trainer / Authority Action Buttons: Edit Draft & Share Draft */}
              {!isEditing && !isAlreadyDecided && (canUserEdit || canUserShare) ? (
                <WorkoutCard testID="adjustment-draft-controls-card">
                  <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                    Thao tác bản thảo
                  </Text>
                  <View style={styles.actionRow}>
                    {canUserEdit ? (
                      <WorkoutButton
                        testID="adjustment-start-edit-button"
                        label="Chỉnh sửa bản thảo"
                        secondary
                        disabled={isAnyOperationInFlight}
                        onPress={handleStartEdit}
                      />
                    ) : null}

                    {canUserShare && !proposal.sharedWithStudent ? (
                      <WorkoutButton
                        testID="adjustment-share-button"
                        label={isSharing ? 'Đang chia sẻ...' : 'Chia sẻ cho học viên'}
                        busy={isSharing}
                        disabled={isAnyOperationInFlight}
                        onPress={handleShareDraft}
                      />
                    ) : null}

                    {canUserShare && proposal.sharedWithStudent && !hasUnsharedChanges ? (
                      <WorkoutButton
                        testID="adjustment-reshare-button"
                        label={isSharing ? 'Đang chia sẻ...' : 'Chia sẻ lại'}
                        secondary
                        busy={isSharing}
                        disabled={isAnyOperationInFlight}
                        onPress={handleShareDraft}
                      />
                    ) : null}
                  </View>
                </WorkoutCard>
              ) : null}

              {/* Accept & Reject Action Section (Only for decision authority, not student in HUMAN_COACH) */}
              {!isEditing && canUserDecide && !isAlreadyDecided ? (
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
                          disabled={isAnyOperationInFlight}
                          busy={isDeciding}
                          onPress={handleAccept}
                        />
                        <WorkoutButton
                          testID="adjustment-start-reject-button"
                          label="Từ chối"
                          secondary
                          danger
                          disabled={isAnyOperationInFlight}
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
                          disabled={isAnyOperationInFlight}
                          busy={isDeciding}
                          onPress={handleReject}
                        />
                        <WorkoutButton
                          testID="adjustment-cancel-reject-button"
                          label="Hủy thao tác"
                          secondary
                          disabled={isAnyOperationInFlight}
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
                    Đề xuất đã ở trạng thái {aiRecommendationStatusLabels[proposal.status]}.
                  </Text>
                </WorkoutCard>
              ) : null}

              {/* Create new proposal button */}
              <WorkoutButton
                testID="adjustment-reset-button"
                label="Tạo đề xuất điều chỉnh khác"
                secondary
                disabled={isAnyOperationInFlight}
                onPress={() => {
                  setProposal(null);
                  setAppliedResult(null);
                  setErrorMessage(null);
                  setConflictGuidance(null);
                  setShareSuccessMessage(null);
                  setIsEditing(false);
                  setGenerateCommandKey(createCommandKey());
                }}
              />
            </View>
          ) : null}
        </ScrollView>

        {/* Modal for Selecting Exercise Variation from Catalog */}
        <Modal
          visible={isCatalogPickerOpen}
          animationType="slide"
          onRequestClose={() => setIsCatalogPickerOpen(false)}
        >
          <View testID="adjustment-catalog-picker-modal" style={[styles.container, { backgroundColor: theme.canvas }]}>
            <View style={[styles.header, { borderBottomColor: theme.border }]}>
              <Text accessibilityRole="header" style={[styles.headerTitle, { color: theme.textPrimary }]}>
                Chọn biến thể từ danh mục
              </Text>
              <TouchableOpacity
                testID="close-catalog-picker"
                accessibilityRole="button"
                accessibilityLabel="Đóng"
                onPress={() => setIsCatalogPickerOpen(false)}
                style={styles.closeButton}
              >
                <Text style={[styles.closeText, { color: theme.textSecondary }]}>✕</Text>
              </TouchableOpacity>
            </View>

            <ScrollView contentContainerStyle={styles.content}>
              {/* Search Bar */}
              <WorkoutCard>
                <Text style={[styles.label, { color: theme.textPrimary }]}>Tìm kiếm bài tập</Text>
                <View style={styles.row}>
                  <TextInput
                    testID="adjustment-catalog-search-input"
                    accessibilityLabel="Tìm bài tập"
                    placeholder="Nhập tên bài tập..."
                    value={catalogSearchQuery}
                    onChangeText={setCatalogSearchQuery}
                    style={[styles.input, { flex: 1, borderColor: theme.border, color: theme.textPrimary }]}
                  />
                  <WorkoutButton
                    testID="adjustment-catalog-search-button"
                    label={isSearchingCatalog ? 'Đang tìm...' : 'Tìm'}
                    disabled={isSearchingCatalog}
                    busy={isSearchingCatalog}
                    onPress={handleSearchCatalog}
                  />
                </View>
              </WorkoutCard>

              {/* Exercises List */}
              {!selectedCatalogExercise ? (
                <WorkoutCard>
                  <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                    Danh sách bài tập ({catalogExercises.length})
                  </Text>
                  <View style={styles.columnGap}>
                    {catalogExercises.map((ex) => (
                      <TouchableOpacity
                        key={ex.id}
                        testID={`catalog-exercise-${ex.id}`}
                        accessibilityRole="button"
                        accessibilityLabel={ex.name}
                        onPress={() => handleSelectCatalogExercise(ex)}
                        style={[styles.choiceItem, { backgroundColor: theme.surface, borderColor: theme.border }]}
                      >
                        <Text style={{ ...typography.label, color: theme.textPrimary }}>{ex.name}</Text>
                        <Text style={{ ...typography.caption, color: theme.textSecondary }}>
                          Dạng: {ex.movementPattern || 'Chưa có'} · {ex.variationCount} biến thể
                        </Text>
                      </TouchableOpacity>
                    ))}
                    {catalogExercises.length === 0 && !isSearchingCatalog ? (
                      <Text style={[styles.emptyPrescriptionText, { color: theme.textSecondary }]}>
                        Không tìm thấy bài tập phù hợp.
                      </Text>
                    ) : null}
                  </View>
                </WorkoutCard>
              ) : (
                /* Selected Exercise Variations List */
                <WorkoutCard>
                  <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                    Biến thể của {selectedCatalogExercise.name}
                  </Text>
                  {isLoadingVariations ? (
                    <Text style={{ ...typography.body, color: theme.textSecondary }}>Đang tải danh sách biến thể...</Text>
                  ) : (
                    <View style={styles.columnGap}>
                      {catalogVariations.map((v) => (
                        <TouchableOpacity
                          key={v.id}
                          testID={`catalog-variation-${v.id}`}
                          accessibilityRole="button"
                          accessibilityLabel={v.name}
                          onPress={() => handleSelectVariation(v)}
                          style={[styles.choiceItem, { backgroundColor: theme.surface, borderColor: theme.border }]}
                        >
                          <Text style={{ ...typography.label, color: theme.textPrimary }}>{v.name}</Text>
                          <Text style={{ ...typography.caption, color: theme.textSecondary }}>
                            Thiết bị: {v.equipment?.[0]?.name || v.equipment?.[0]?.code || 'Mặc định'}
                          </Text>
                        </TouchableOpacity>
                      ))}
                    </View>
                  )}
                  <View style={{ marginTop: spacing.md }}>
                    <WorkoutButton
                      label="Quay lại danh sách bài tập"
                      secondary
                      onPress={() => {
                        setSelectedCatalogExercise(null);
                        setCatalogVariations([]);
                      }}
                    />
                  </View>
                </WorkoutCard>
              )}
            </ScrollView>
          </View>
        </Modal>
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
  sharingStatusRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
    marginTop: spacing.xs,
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
  diffSummaryHeader: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
    alignItems: 'center',
    justifyContent: 'space-between',
    marginBottom: spacing.xs,
  },
  fieldDiffRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    alignItems: 'center',
    gap: spacing.xs,
    marginTop: spacing.xxs,
  },
  fieldDiffName: {
    ...typography.bodySmall,
    fontWeight: 'bold',
  },
  fieldDiffBefore: {
    ...typography.bodySmall,
    textDecorationLine: 'line-through',
  },
  fieldDiffArrow: {
    ...typography.bodySmall,
  },
  fieldDiffAfter: {
    ...typography.bodySmall,
    fontWeight: 'bold',
  },
  fieldDiffDelta: {
    ...typography.bodySmall,
  },
  selectedVariationBox: {
    borderWidth: 1,
    borderRadius: radius.md,
    borderColor: '#7C3AED',
    padding: spacing.sm,
    marginBottom: spacing.sm,
    backgroundColor: '#F3E8FF',
  },
  selectedVariationTitle: {
    ...typography.label,
    fontWeight: 'bold',
  },
  selectedVariationSub: {
    ...typography.caption,
    marginTop: 2,
  },
});
