import React, { useState, useEffect } from 'react';
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
import {
  aiWorkoutProposalApi,
  getWorkoutProposalErrorMessage,
  getWorkoutProposalFieldErrors,
} from '@/services/aiWorkoutProposalApi';
import { workoutPlanApi } from '@/services/workoutPlanApi';
import { studentProfileApi } from '@/services/studentProfileApi';
import { WorkoutPlanDetail } from '@/types/workoutPlan';
import {
  WorkoutProposalPayload,
  WorkoutProposalResponse,
  WorkoutProposalSession,
  WorkoutProposalPreferences,
} from '@/types/aiWorkoutProposal';
import { WorkoutButton, WorkoutCard } from './WorkoutUi';

interface ModalProposalState extends WorkoutProposalResponse {
  revision?: number;
  isCustomized?: boolean;
  originalPayload?: WorkoutProposalPayload;
}

interface Props {
  visible: boolean;
  studentId?: string;
  isTrainer?: boolean;
  onClose: () => void;
  onPlanApplied?: (planId: string) => void;
}

function getTomorrowLocalDate(): string {
  const d = new Date();
  d.setDate(d.getDate() + 1);
  const year = d.getFullYear();
  const month = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

export function AiWorkoutProposalModal({
  visible,
  studentId,
  isTrainer = false,
  onClose,
  onPlanApplied,
}: Props) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);

  const [daysPerWeek, setDaysPerWeek] = useState<number>(3);
  const [sessionMinutes, setSessionMinutes] = useState<number>(60);
  const [startDate, setStartDate] = useState<string>(() => getTomorrowLocalDate());
  const currentStudentRef = React.useRef(studentId);
  currentStudentRef.current = studentId;
  const activeRequestIdRef = React.useRef<number>(0);
  const [allowBodyweight, setAllowBodyweight] = useState<boolean>(true);
  const [selectedEquipment, setSelectedEquipment] = useState<string[]>(['BODYWEIGHT']);
  const [hasPhysicalLimitations, setHasPhysicalLimitations] = useState<boolean | null>(null);
  const [limitationsDescription, setLimitationsDescription] = useState<string>('');
  const [preferencesNote, setPreferencesNote] = useState<string>('');
  const [decisionNote, setDecisionNote] = useState<string>('');

  const [fieldErrors, setFieldErrors] = useState<Record<string, string>>({});
  const [isGenerating, setIsGenerating] = useState<boolean>(false);
  const [isDeciding, setIsDeciding] = useState<boolean>(false);
  const [isSavingDraft, setIsSavingDraft] = useState<boolean>(false);
  const [isEditing, setIsEditing] = useState<boolean>(false);
  const [showOriginalComparison, setShowOriginalComparison] = useState<boolean>(false);

  const [proposal, setProposal] = useState<ModalProposalState | null>(null);
  const [editablePayload, setEditablePayload] = useState<WorkoutProposalPayload | null>(null);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [appliedPlanId, setAppliedPlanId] = useState<string | null>(null);
  const [currentActivePlan, setCurrentActivePlan] = useState<WorkoutPlanDetail | null>(null);

  // Reset all modal state when visibility changes or when studentId changes to prevent data leakage
  useEffect(() => {
    if (!visible) return;

    activeRequestIdRef.current += 1;
    const effectRequestId = activeRequestIdRef.current;

    // Reset proposals, active plan, editing, and error state
    setProposal(null);
    setEditablePayload(null);
    setCurrentActivePlan(null);
    setAppliedPlanId(null);
    setErrorMessage(null);
    setFieldErrors({});
    setIsEditing(false);
    setShowOriginalComparison(false);
    setIsGenerating(false);
    setIsDeciding(false);
    setIsSavingDraft(false);

    // Reset user-specific inputs so they never leak to another student
    setDaysPerWeek(3);
    setSessionMinutes(60);
    setStartDate(getTomorrowLocalDate());
    setSelectedEquipment(['BODYWEIGHT']);
    setAllowBodyweight(true);
    setHasPhysicalLimitations(null);
    setLimitationsDescription('');
    setPreferencesNote('');
    setDecisionNote('');

    // Pre-fill preferences ONLY for Student viewing their own profile (Trainer does not use trainer profile)
    if (!isTrainer) {
      studentProfileApi.getMyStudentProfile()
        .then((profile) => {
          if (activeRequestIdRef.current !== effectRequestId) return;
          if (profile.availableDaysPerWeek && (profile.availableDaysPerWeek === 3 || profile.availableDaysPerWeek === 4)) {
            setDaysPerWeek(profile.availableDaysPerWeek);
          }
          if (profile.preferredSessionMinutes && profile.preferredSessionMinutes >= 15 && profile.preferredSessionMinutes <= 180) {
            setSessionMinutes(profile.preferredSessionMinutes);
          }
        })
        .catch(() => {});
    }

    return () => {
      activeRequestIdRef.current += 1;
    };
  }, [visible, studentId, isTrainer]);

  const toggleEquipment = (eq: string) => {
    if (eq === 'BODYWEIGHT') {
      const willEnable = !selectedEquipment.includes('BODYWEIGHT');
      setAllowBodyweight(willEnable);
      if (willEnable) {
        setSelectedEquipment([...selectedEquipment, 'BODYWEIGHT']);
      } else {
        setSelectedEquipment(selectedEquipment.filter((x) => x !== 'BODYWEIGHT'));
      }
      return;
    }
    if (selectedEquipment.includes(eq)) {
      setSelectedEquipment(selectedEquipment.filter((x) => x !== eq));
    } else {
      setSelectedEquipment([...selectedEquipment, eq]);
    }
  };

  const handleGenerate = async () => {
    const thisRequestId = ++activeRequestIdRef.current;
    setIsGenerating(true);
    setErrorMessage(null);
    setFieldErrors({});
    setIsEditing(false);
    setShowOriginalComparison(false);

    const equipList = [...selectedEquipment];
    if (allowBodyweight && !equipList.includes('BODYWEIGHT')) {
      equipList.push('BODYWEIGHT');
    }

    const localTimezone = (() => {
      try {
        return Intl.DateTimeFormat().resolvedOptions().timeZone;
      } catch {
        return undefined;
      }
    })();

    const prefs: WorkoutProposalPreferences = {
      preferredDaysPerWeek: daysPerWeek,
      daysPerWeek: daysPerWeek,
      preferredSessionMinutes: sessionMinutes,
      startDate: startDate.trim(),
      equipment: equipList,
      allowBodyweight: allowBodyweight,
      hasPhysicalLimitations: hasPhysicalLimitations !== null ? hasPhysicalLimitations : undefined,
      limitationsConfirmed: hasPhysicalLimitations !== null,
      limitationsDescription: hasPhysicalLimitations ? limitationsDescription.trim() : undefined,
      note: preferencesNote.trim(),
      timezone: localTimezone,
    };

    const targetStudentId = studentId;

    try {
      const [res, activePlan] = await Promise.all([
        aiWorkoutProposalApi.generate({
          studentId: targetStudentId,
          preferences: prefs,
        }),
        workoutPlanApi.current(targetStudentId).catch(() => null),
      ]);
      if (activeRequestIdRef.current !== thisRequestId || currentStudentRef.current !== targetStudentId) {
        return; // Guard against stale response if studentId or request changed
      }
      setProposal({
        ...res,
        revision: 1,
        isCustomized: false,
        originalPayload: res.structuredPayload,
      });
      setCurrentActivePlan(activePlan);
    } catch (err) {
      if (activeRequestIdRef.current !== thisRequestId || currentStudentRef.current !== targetStudentId) {
        return;
      }
      setErrorMessage(getWorkoutProposalErrorMessage(err));
      setFieldErrors(getWorkoutProposalFieldErrors(err));
    } finally {
      if (activeRequestIdRef.current === thisRequestId && currentStudentRef.current === targetStudentId) {
        setIsGenerating(false);
      }
    }
  };

  const handleStartEdit = () => {
    if (!proposal?.structuredPayload) return;
    setEditablePayload(JSON.parse(JSON.stringify(proposal.structuredPayload)));
    setIsEditing(true);
    setErrorMessage(null);
  };

  const handleCancelEdit = () => {
    setIsEditing(false);
    setEditablePayload(null);
    setErrorMessage(null);
  };

  const handleSaveDraft = async () => {
    if (!proposal || !editablePayload) return;
    const thisRequestId = ++activeRequestIdRef.current;
    setIsSavingDraft(true);
    setErrorMessage(null);
    try {
      const currentRev = proposal.revision ?? 1;
      const res = await aiWorkoutProposalApi.updateDraft(proposal.recommendationId, {
        expectedRevision: currentRev,
        title: editablePayload.plan.name,
        explanation: editablePayload.summary,
        structuredPayload: editablePayload,
      });

      if (activeRequestIdRef.current !== thisRequestId) return;
      setProposal({
        ...proposal,
        revision: res.revision,
        isCustomized: res.isCustomized,
        title: res.title,
        explanation: res.explanation,
        structuredPayload: res.structuredPayload,
        originalPayload: res.originalPayload ?? proposal.originalPayload,
      });
      setIsEditing(false);
      setEditablePayload(null);
    } catch (err) {
      if (activeRequestIdRef.current !== thisRequestId) return;
      setErrorMessage(getWorkoutProposalErrorMessage(err));
    } finally {
      if (activeRequestIdRef.current === thisRequestId) {
        setIsSavingDraft(false);
      }
    }
  };

  const handleUpdateSession = (sessionIndex: number, updates: Partial<WorkoutProposalSession>) => {
    if (!editablePayload) return;
    const newSessions = [...editablePayload.plan.sessions];
    newSessions[sessionIndex] = {
      ...newSessions[sessionIndex],
      ...updates,
    };
    setEditablePayload({
      ...editablePayload,
      plan: {
        ...editablePayload.plan,
        sessions: newSessions,
      },
    });
  };

  const handleUpdatePrescription = (
    sessionIndex: number,
    prescriptionIndex: number,
    field: string,
    value: string
  ) => {
    if (!editablePayload) return;
    const newSessions = [...editablePayload.plan.sessions];
    const newPrescriptions = [...newSessions[sessionIndex].prescriptions];
    const currentP = { ...newPrescriptions[prescriptionIndex] };

    if (field === 'targetSets') {
      currentP.targetSets = parseInt(value, 10) || 1;
    } else if (field === 'targetRepsMin') {
      currentP.targetRepsMin = value ? parseInt(value, 10) || undefined : undefined;
    } else if (field === 'targetRepsMax') {
      currentP.targetRepsMax = value ? parseInt(value, 10) || undefined : undefined;
    } else if (field === 'restSeconds') {
      currentP.restSeconds = value ? parseInt(value, 10) || undefined : undefined;
    } else if (field === 'instructions') {
      currentP.instructions = value;
    }

    newPrescriptions[prescriptionIndex] = currentP;
    newSessions[sessionIndex] = {
      ...newSessions[sessionIndex],
      prescriptions: newPrescriptions,
    };
    setEditablePayload({
      ...editablePayload,
      plan: {
        ...editablePayload.plan,
        sessions: newSessions,
      },
    });
  };

  const handleAccept = async () => {
    if (!proposal) return;
    const thisRequestId = ++activeRequestIdRef.current;
    setIsDeciding(true);
    setErrorMessage(null);
    try {
      const res = await aiWorkoutProposalApi.accept(
        proposal.recommendationId,
        decisionNote.trim() || undefined,
        undefined,
        proposal.revision ?? 1,
        currentActivePlan ? currentActivePlan.plan.id : undefined,
        currentActivePlan ? currentActivePlan.plan.aggregateVersion : undefined,
        currentActivePlan ? false : true
      );
      if (activeRequestIdRef.current !== thisRequestId) return;
      setProposal({
        ...proposal,
        status: 'APPLIED',
      });
      setAppliedPlanId(res.resultingEntityId);
      if (onPlanApplied) {
        onPlanApplied(res.resultingEntityId);
      }
    } catch (err) {
      if (activeRequestIdRef.current !== thisRequestId) return;
      setErrorMessage(getWorkoutProposalErrorMessage(err));
    } finally {
      if (activeRequestIdRef.current === thisRequestId) {
        setIsDeciding(false);
      }
    }
  };

  const handleReject = async () => {
    if (!proposal) return;
    const thisRequestId = ++activeRequestIdRef.current;
    setIsDeciding(true);
    setErrorMessage(null);
    try {
      await aiWorkoutProposalApi.reject(
        proposal.recommendationId,
        decisionNote.trim() || 'Người dùng từ chối đề xuất'
      );
      if (activeRequestIdRef.current !== thisRequestId) return;
      setProposal({
        ...proposal,
        status: 'REJECTED',
      });
    } catch (err) {
      if (activeRequestIdRef.current !== thisRequestId) return;
      setErrorMessage(getWorkoutProposalErrorMessage(err));
    } finally {
      if (activeRequestIdRef.current === thisRequestId) {
        setIsDeciding(false);
      }
    }
  };

  const payload: WorkoutProposalPayload | null = proposal?.structuredPayload ?? null;
  const originalPayload: WorkoutProposalPayload | null = proposal?.originalPayload ?? null;
  const hasAuthority = proposal
    ? proposal.decisionAuthority === 'TRAINER'
      ? isTrainer
      : !isTrainer
    : false;

  return (
    <Modal
      visible={visible}
      animationType="slide"
      transparent={false}
      onRequestClose={onClose}
    >
      <View style={[styles.container, { backgroundColor: theme.canvas }]}>
        <View style={[styles.header, { borderBottomColor: theme.border }]}>
          <Text accessibilityRole="header" style={[styles.title, { color: theme.textPrimary }]}>
            Đề xuất giáo án 1 tuần (AI)
          </Text>
          <TouchableOpacity
            testID="close-ai-proposal-modal-button"
            accessibilityLabel="Đóng"
            onPress={onClose}
            style={styles.closeBtn}
          >
            <Text style={[styles.closeText, { color: theme.textSecondary }]}>✕</Text>
          </TouchableOpacity>
        </View>

        <ScrollView contentContainerStyle={styles.scrollContent}>
          {errorMessage ? (
            <WorkoutCard testID="ai-proposal-error-banner">
              <Text style={[styles.errorTitle, { color: theme.dangerText }]}>Lỗi xử lý</Text>
              <Text style={[styles.errorBody, { color: theme.textPrimary }]}>{errorMessage}</Text>
              {proposal ? (
                <TouchableOpacity
                  testID="reload-ai-proposal-button"
                  style={[styles.smallActionBtn, { borderColor: theme.border, marginTop: 8, alignSelf: 'flex-start' }]}
                  onPress={async () => {
                    try {
                      const [latest, activePlan] = await Promise.all([
                        aiWorkoutProposalApi.getRecommendation(proposal.recommendationId),
                        workoutPlanApi.current(studentId).catch(() => null),
                      ]);
                      setProposal({
                        ...proposal,
                        revision: latest.revision,
                        isCustomized: latest.isCustomized,
                        title: latest.title,
                        explanation: latest.explanation,
                        structuredPayload: latest.structuredPayload,
                        originalPayload: latest.originalPayload ?? proposal.originalPayload,
                      });
                      setCurrentActivePlan(activePlan);
                      setErrorMessage(null);
                      setIsEditing(false);
                      setEditablePayload(null);
                    } catch (e) {
                      setErrorMessage(getWorkoutProposalErrorMessage(e));
                    }
                  }}
                >
                  <Text style={[styles.smallActionBtnText, { color: theme.primary }]}>
                    Tải lại đề xuất
                  </Text>
                </TouchableOpacity>
              ) : null}
            </WorkoutCard>
          ) : null}

          {!proposal ? (
            <WorkoutCard testID="ai-proposal-request-form">
              <Text style={[styles.h2, { color: theme.textPrimary }]}>Tùy chọn tạo giáo án</Text>
              <Text style={[styles.body, { color: theme.textSecondary }]}>
                Hệ thống AI sẽ đối chiếu mục tiêu và dữ liệu tập luyện hiện tại để thiết kế giáo án mẫu chuẩn xác trong phạm vi đúng 1 tuần.
              </Text>

              {/* Goal or Profile readiness warning banner */}
              {fieldErrors['fitnessGoal'] || fieldErrors['trainingExperienceLevel'] ? (
                <View style={[styles.alertBox, { backgroundColor: theme.surfaceSubtle, borderColor: theme.dangerText }]} testID="readiness-guidance-card">
                  <Text style={[styles.alertTitle, { color: theme.dangerText }]}>Cần bổ sung thông tin hồ sơ</Text>
                  {fieldErrors['fitnessGoal'] ? (
                    <Text style={[styles.alertBody, { color: theme.textPrimary }]}>
                      • Mục tiêu tập luyện: Chưa có Mục tiêu thể hình (Goal) ACTIVE. Vui lòng vào màn hình Mục tiêu để tạo và kích hoạt mục tiêu trước khi thiết kế giáo án.
                    </Text>
                  ) : null}
                  {fieldErrors['trainingExperienceLevel'] ? (
                    <Text style={[styles.alertBody, { color: theme.textPrimary }]}>
                      • Trình độ tập luyện: Hồ sơ học viên chưa cập nhật trình độ tập luyện. Vui lòng vào phần Hồ sơ cá nhân để thiết lập trình độ (Beginner / Intermediate / Advanced).
                    </Text>
                  ) : null}
                </View>
              ) : null}

              {/* 1. Days per week */}
              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Số ngày tập mỗi tuần (*):
              </Text>
              <View style={styles.daysSelector}>
                {[3, 4].map((days) => (
                  <TouchableOpacity
                    key={days}
                    testID={`days-per-week-${days}`}
                    style={[
                      styles.dayOption,
                      {
                        backgroundColor: daysPerWeek === days ? theme.primary : theme.surfaceSubtle,
                      },
                    ]}
                    onPress={() => {
                      setDaysPerWeek(days);
                      setFieldErrors((prev) => {
                        const copy = { ...prev };
                        delete copy.preferredDaysPerWeek;
                        delete copy.daysPerWeek;
                        return copy;
                      });
                    }}
                  >
                    <Text
                      style={[
                        styles.dayOptionText,
                        { color: daysPerWeek === days ? theme.textOnPrimary : theme.textPrimary },
                      ]}
                    >
                      {days} buổi / tuần
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
              {fieldErrors['preferredDaysPerWeek'] || fieldErrors['daysPerWeek'] ? (
                <Text style={[styles.fieldErrorText, { color: theme.dangerText }]}>
                  {fieldErrors['preferredDaysPerWeek'] || fieldErrors['daysPerWeek']}
                </Text>
              ) : null}

              {/* 2. Session Duration */}
              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Thời lượng mỗi buổi tập (*):
              </Text>
              <View style={styles.daysSelector}>
                {[30, 45, 60, 90].map((mins) => (
                  <TouchableOpacity
                    key={mins}
                    testID={`session-minutes-${mins}`}
                    style={[
                      styles.dayOption,
                      {
                        backgroundColor: sessionMinutes === mins ? theme.primary : theme.surfaceSubtle,
                      },
                    ]}
                    onPress={() => {
                      setSessionMinutes(mins);
                      setFieldErrors((prev) => {
                        const copy = { ...prev };
                        delete copy.preferredSessionMinutes;
                        return copy;
                      });
                    }}
                  >
                    <Text
                      style={[
                        styles.dayOptionText,
                        { color: sessionMinutes === mins ? theme.textOnPrimary : theme.textPrimary },
                      ]}
                    >
                      {mins} phút
                    </Text>
                  </TouchableOpacity>
                ))}
              </View>
              {fieldErrors['preferredSessionMinutes'] ? (
                <Text style={[styles.fieldErrorText, { color: theme.dangerText }]}>
                  {fieldErrors['preferredSessionMinutes']}
                </Text>
              ) : null}

              {/* 3. Start Date */}
              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Ngày bắt đầu giáo án (YYYY-MM-DD) (*):
              </Text>
              <TextInput
                testID="ai-proposal-start-date-input"
                style={[
                  styles.textInput,
                  {
                    color: theme.textPrimary,
                    borderColor: fieldErrors['startDate'] ? theme.dangerText : theme.border,
                    backgroundColor: theme.surfaceSubtle,
                  },
                ]}
                placeholder="YYYY-MM-DD"
                placeholderTextColor={theme.textSecondary}
                value={startDate}
                onChangeText={(val) => {
                  setStartDate(val);
                  setFieldErrors((prev) => {
                    const copy = { ...prev };
                    delete copy.startDate;
                    return copy;
                  });
                }}
              />
              {fieldErrors['startDate'] ? (
                <Text style={[styles.fieldErrorText, { color: theme.dangerText }]}>
                  {fieldErrors['startDate']}
                </Text>
              ) : null}

              {/* 4. Equipment Selection */}
              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Thiết bị tập luyện sẵn có (*):
              </Text>
              <View style={styles.chipsRow}>
                {[
                  { id: 'BODYWEIGHT', label: 'Bodyweight' },
                  { id: 'DUMBBELL', label: 'Tạ đơn (Dumbbell)' },
                  { id: 'BARBELL', label: 'Thanh đòn (Barbell)' },
                  { id: 'RESISTANCE_BAND', label: 'Dây kháng lực' },
                  { id: 'CABLE', label: 'Máy cáp (Cable)' },
                  { id: 'MACHINE', label: 'Máy tạ (Machine)' },
                ].map((item) => {
                  const isSelected = selectedEquipment.includes(item.id);
                  return (
                    <TouchableOpacity
                      key={item.id}
                      testID={`equipment-chip-${item.id}`}
                      style={[
                        styles.chip,
                        {
                          backgroundColor: isSelected ? theme.primary : theme.surfaceSubtle,
                          borderColor: isSelected ? theme.primary : theme.border,
                        },
                      ]}
                      onPress={() => {
                        toggleEquipment(item.id);
                        setFieldErrors((prev) => {
                          const copy = { ...prev };
                          delete copy.equipment;
                          return copy;
                        });
                      }}
                    >
                      <Text
                        style={[
                          styles.chipText,
                          { color: isSelected ? theme.textOnPrimary : theme.textPrimary },
                        ]}
                      >
                        {item.label}
                      </Text>
                    </TouchableOpacity>
                  );
                })}
              </View>
              {fieldErrors['equipment'] ? (
                <Text style={[styles.fieldErrorText, { color: theme.dangerText }]}>
                  {fieldErrors['equipment']}
                </Text>
              ) : null}

              {/* 5. Health & Physical Limitations Confirmation */}
              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Tình trạng chấn thương / hạn chế thể chất (*):
              </Text>
              <Text style={[styles.subLabel, { color: theme.textSecondary }]}>
                Bắt buộc xác nhận trung thực để đảm bảo an toàn tập luyện. Không tự động điền câu trả lời.
              </Text>
              <View style={styles.limitationChoiceRow}>
                <TouchableOpacity
                  testID="limitations-none-button"
                  style={[
                    styles.limitationChoiceBtn,
                    {
                      backgroundColor: hasPhysicalLimitations === false ? theme.primary : theme.surfaceSubtle,
                      borderColor: hasPhysicalLimitations === false ? theme.primary : theme.border,
                    },
                  ]}
                  onPress={() => {
                    setHasPhysicalLimitations(false);
                    setLimitationsDescription('');
                    setFieldErrors((prev) => {
                      const copy = { ...prev };
                      delete copy.limitations;
                      delete copy.limitationsConfirmed;
                      delete copy.limitationsDescription;
                      return copy;
                    });
                  }}
                >
                  <Text
                    style={[
                      styles.choiceBtnText,
                      { color: hasPhysicalLimitations === false ? theme.textOnPrimary : theme.textPrimary },
                    ]}
                  >
                    ✓ Không có hạn chế
                  </Text>
                </TouchableOpacity>

                <TouchableOpacity
                  testID="limitations-yes-button"
                  style={[
                    styles.limitationChoiceBtn,
                    {
                      backgroundColor: hasPhysicalLimitations === true ? theme.primary : theme.surfaceSubtle,
                      borderColor: hasPhysicalLimitations === true ? theme.primary : theme.border,
                    },
                  ]}
                  onPress={() => {
                    setHasPhysicalLimitations(true);
                    setFieldErrors((prev) => {
                      const copy = { ...prev };
                      delete copy.limitations;
                      delete copy.limitationsConfirmed;
                      return copy;
                    });
                  }}
                >
                  <Text
                    style={[
                      styles.choiceBtnText,
                      { color: hasPhysicalLimitations === true ? theme.textOnPrimary : theme.textPrimary },
                    ]}
                  >
                    ⚠ Có hạn chế / chấn thương
                  </Text>
                </TouchableOpacity>
              </View>
              {fieldErrors['limitations'] || fieldErrors['limitationsConfirmed'] ? (
                <Text style={[styles.fieldErrorText, { color: theme.dangerText }]}>
                  {fieldErrors['limitations'] || fieldErrors['limitationsConfirmed']}
                </Text>
              ) : null}

              {hasPhysicalLimitations === true ? (
                <View style={{ marginTop: 8 }}>
                  <Text style={[styles.subLabel, { color: theme.textPrimary }]}>
                    Mô tả chấn thương hoặc các bài tập cần tránh (*):
                  </Text>
                  <TextInput
                    testID="ai-proposal-limitations-input"
                    style={[
                      styles.textInput,
                      {
                        color: theme.textPrimary,
                        borderColor: fieldErrors['limitationsDescription'] ? theme.dangerText : theme.border,
                        backgroundColor: theme.surfaceSubtle,
                      },
                    ]}
                    placeholder="Ví dụ: Đau khớp gối nhẹ khi gập sâu, tránh tạ nặng bài squat..."
                    placeholderTextColor={theme.textSecondary}
                    value={limitationsDescription}
                    onChangeText={(val) => {
                      setLimitationsDescription(val);
                      setFieldErrors((prev) => {
                        const copy = { ...prev };
                        delete copy.limitationsDescription;
                        return copy;
                      });
                    }}
                  />
                  {fieldErrors['limitationsDescription'] ? (
                    <Text style={[styles.fieldErrorText, { color: theme.dangerText }]}>
                      {fieldErrors['limitationsDescription']}
                    </Text>
                  ) : null}
                </View>
              ) : null}

              {/* 6. Notes */}
              <Text style={[styles.label, { color: theme.textPrimary, marginTop: 12 }]}>
                Ghi chú hoặc ưu tiên sở thích cá nhân (Tùy chọn):
              </Text>
              <Text style={[styles.subLabel, { color: theme.textSecondary }]}>
                Văn bản tự do chỉ mang tính tham khảo sở thích cá nhân, không thay thế dữ liệu y tế hoặc chẩn đoán chấn thương đã xác thực.
              </Text>
              <TextInput
                testID="ai-proposal-notes-input"
                style={[
                  styles.textInput,
                  {
                    color: theme.textPrimary,
                    borderColor: theme.border,
                    backgroundColor: theme.surfaceSubtle,
                  },
                ]}
                placeholder="Ví dụ: Ưu tiên thân trên, thích các bài tập tạ đơn..."
                placeholderTextColor={theme.textSecondary}
                multiline
                numberOfLines={3}
                value={preferencesNote}
                onChangeText={setPreferencesNote}
              />

              <WorkoutButton
                testID="generate-ai-proposal-button"
                label={isGenerating ? 'Đang phân tích và tạo giáo án...' : 'Tạo đề xuất giáo án (AI)'}
                busy={isGenerating}
                disabled={isGenerating}
                onPress={handleGenerate}
              />
            </WorkoutCard>
          ) : (
            <View testID="ai-proposal-detail-view">
              <WorkoutCard testID="ai-proposal-summary-card">
                <View style={styles.badgeRow}>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.badgeText, { color: theme.textPrimary }]}>
                      Trạng thái: {proposal.status}
                    </Text>
                  </View>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.badgeText, { color: theme.textPrimary }]}>
                      Thẩm quyền: {proposal.decisionAuthority}
                    </Text>
                  </View>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]} testID="ai-proposal-revision-badge">
                    <Text style={[styles.badgeText, { color: theme.textPrimary }]}>
                      v{proposal.revision ?? 1}
                    </Text>
                  </View>
                  <View
                    style={[
                      styles.badge,
                      { backgroundColor: proposal.isCustomized ? theme.primary : theme.surfaceSubtle },
                    ]}
                    testID="ai-proposal-provenance-badge"
                  >
                    <Text
                      style={[
                        styles.badgeText,
                        { color: proposal.isCustomized ? theme.textOnPrimary : theme.textSecondary },
                      ]}
                    >
                      {proposal.isCustomized ? 'Đã tùy chỉnh' : 'Đề xuất AI gốc'}
                    </Text>
                  </View>
                </View>

                <Text style={[styles.h2, { color: theme.textPrimary }]}>
                  {proposal.title}
                </Text>
                <Text style={[styles.body, { color: theme.textSecondary }]}>
                  {proposal.explanation}
                </Text>

                {payload?.confidence ? (
                  <Text style={[styles.metaText, { color: theme.textSecondary, marginTop: 4 }]}>
                    Độ tự tin từ mô hình (tự đánh giá, chưa qua kiểm chứng lâm sàng): {(payload.confidence * 100).toFixed(0)}%
                  </Text>
                ) : null}

                {/* Draft editing and comparison controls for PENDING */}
                {proposal.status === 'PENDING' && !isEditing ? (
                  <View style={styles.draftControlsRow}>
                    {hasAuthority ? (
                      <TouchableOpacity
                        testID="start-edit-draft-button"
                        style={[styles.smallActionBtn, { borderColor: theme.border }]}
                        onPress={handleStartEdit}
                      >
                        <Text style={[styles.smallActionBtnText, { color: theme.primary }]}>
                          Chỉnh sửa bản thảo
                        </Text>
                      </TouchableOpacity>
                    ) : null}

                    {proposal.isCustomized && originalPayload ? (
                      <TouchableOpacity
                        testID="toggle-original-comparison-button"
                        style={[styles.smallActionBtn, { borderColor: theme.border }]}
                        onPress={() => setShowOriginalComparison(!showOriginalComparison)}
                      >
                        <Text style={[styles.smallActionBtnText, { color: theme.textSecondary }]}>
                          {showOriginalComparison ? 'Ẩn bản AI gốc' : 'So sánh với bản AI gốc'}
                        </Text>
                      </TouchableOpacity>
                    ) : null}
                  </View>
                ) : null}
              </WorkoutCard>

              {/* Original AI Proposal comparison view */}
              {showOriginalComparison && originalPayload ? (
                <WorkoutCard testID="original-ai-proposal-card">
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle, alignSelf: 'flex-start', marginBottom: 6 }]}>
                    <Text style={[styles.badgeText, { color: theme.textSecondary }]}>
                      Bản gốc từ AI (chưa sửa)
                    </Text>
                  </View>
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>
                    {originalPayload.plan.name}
                  </Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    {originalPayload.summary}
                  </Text>
                  {originalPayload.plan.sessions.map((s, idx) => (
                    <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {s.name} ({s.prescriptions.length} bài tập)
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Editing Form when isEditing is true */}
              {isEditing && editablePayload ? (
                <WorkoutCard testID="ai-proposal-edit-form">
                  <Text style={[styles.h3, { color: theme.primary }]}>Chỉnh sửa bản thảo giáo án</Text>

                  <Text style={[styles.label, { color: theme.textPrimary, marginTop: 8 }]}>
                    Tên kế hoạch:
                  </Text>
                  <TextInput
                    testID="edit-plan-name-input"
                    style={[
                      styles.textInput,
                      { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                    ]}
                    value={editablePayload.plan.name}
                    onChangeText={(val) =>
                      setEditablePayload({
                        ...editablePayload,
                        plan: { ...editablePayload.plan, name: val },
                      })
                    }
                  />

                  <Text style={[styles.label, { color: theme.textPrimary, marginTop: 8 }]}>
                    Tóm tắt & mục tiêu:
                  </Text>
                  <TextInput
                    testID="edit-plan-summary-input"
                    style={[
                      styles.textInput,
                      { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                    ]}
                    multiline
                    numberOfLines={2}
                    value={editablePayload.summary}
                    onChangeText={(val) =>
                      setEditablePayload({
                        ...editablePayload,
                        summary: val,
                      })
                    }
                  />

                  <Text style={[styles.h3, { color: theme.textPrimary, marginTop: 12 }]}>
                    Chi tiết các buổi tập ({editablePayload.plan.sessions.length} buổi)
                  </Text>

                  {editablePayload.plan.sessions.map((session, sIdx) => (
                    <View key={sIdx} style={[styles.editSessionBlock, { borderColor: theme.border }]}>
                      <Text style={[styles.label, { color: theme.textPrimary }]}>
                        Buổi {sIdx + 1}: Tên buổi tập
                      </Text>
                      <TextInput
                        testID={`edit-session-name-${sIdx}`}
                        style={[
                          styles.textInput,
                          { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                        ]}
                        value={session.name}
                        onChangeText={(val) => handleUpdateSession(sIdx, { name: val })}
                      />

                      <Text style={[styles.label, { color: theme.textPrimary }]}>Trọng tâm:</Text>
                      <TextInput
                        testID={`edit-session-focus-${sIdx}`}
                        style={[
                          styles.textInput,
                          { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                        ]}
                        value={session.focus ?? ''}
                        onChangeText={(val) => handleUpdateSession(sIdx, { focus: val })}
                      />

                      <Text style={[styles.label, { color: theme.textPrimary }]}>Thời lượng (phút):</Text>
                      <TextInput
                        testID={`edit-session-duration-${sIdx}`}
                        keyboardType="numeric"
                        style={[
                          styles.textInput,
                          { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                        ]}
                        value={session.estimatedDurationMinutes ? String(session.estimatedDurationMinutes) : ''}
                        onChangeText={(val) =>
                          handleUpdateSession(sIdx, { estimatedDurationMinutes: parseInt(val, 10) || undefined })
                        }
                      />

                      {session.prescriptions.map((p, pIdx) => (
                        <View key={pIdx} style={[styles.editPrescriptionRow, { borderTopColor: theme.border }]}>
                          <Text style={[styles.prescriptionTitle, { color: theme.textPrimary }]}>
                            Bài {pIdx + 1} (ID: {p.exerciseVariationId.slice(0, 8)}...)
                          </Text>
                          <View style={styles.prescriptionInputRow}>
                            <View style={styles.halfInput}>
                              <Text style={[styles.subLabel, { color: theme.textSecondary }]}>Số hiệp:</Text>
                              <TextInput
                                testID={`edit-prescription-sets-${sIdx}-${pIdx}`}
                                keyboardType="numeric"
                                style={[
                                  styles.textInput,
                                  { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                                ]}
                                value={String(p.targetSets)}
                                onChangeText={(val) => handleUpdatePrescription(sIdx, pIdx, 'targetSets', val)}
                              />
                            </View>
                            <View style={styles.halfInput}>
                              <Text style={[styles.subLabel, { color: theme.textSecondary }]}>Số lần (min):</Text>
                              <TextInput
                                testID={`edit-prescription-reps-min-${sIdx}-${pIdx}`}
                                keyboardType="numeric"
                                style={[
                                  styles.textInput,
                                  { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                                ]}
                                value={p.targetRepsMin ? String(p.targetRepsMin) : ''}
                                onChangeText={(val) => handleUpdatePrescription(sIdx, pIdx, 'targetRepsMin', val)}
                              />
                            </View>
                            <View style={styles.halfInput}>
                              <Text style={[styles.subLabel, { color: theme.textSecondary }]}>Số lần (max):</Text>
                              <TextInput
                                testID={`edit-prescription-reps-max-${sIdx}-${pIdx}`}
                                keyboardType="numeric"
                                style={[
                                  styles.textInput,
                                  { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                                ]}
                                value={p.targetRepsMax ? String(p.targetRepsMax) : ''}
                                onChangeText={(val) => handleUpdatePrescription(sIdx, pIdx, 'targetRepsMax', val)}
                              />
                            </View>
                          </View>
                          <View style={styles.prescriptionInputRow}>
                            <View style={styles.fullInput}>
                              <Text style={[styles.subLabel, { color: theme.textSecondary }]}>Nghỉ giữa hiệp (giây):</Text>
                              <TextInput
                                testID={`edit-prescription-rest-${sIdx}-${pIdx}`}
                                keyboardType="numeric"
                                style={[
                                  styles.textInput,
                                  { color: theme.textPrimary, borderColor: theme.border, backgroundColor: theme.surfaceSubtle },
                                ]}
                                value={p.restSeconds ? String(p.restSeconds) : ''}
                                onChangeText={(val) => handleUpdatePrescription(sIdx, pIdx, 'restSeconds', val)}
                              />
                            </View>
                          </View>
                        </View>
                      ))}
                    </View>
                  ))}

                  <View style={styles.editActionRow}>
                    <WorkoutButton
                      testID="save-draft-button"
                      label={isSavingDraft ? 'Đang lưu bản thảo...' : 'Lưu bản thảo'}
                      busy={isSavingDraft}
                      disabled={isSavingDraft}
                      onPress={handleSaveDraft}
                    />
                    <WorkoutButton
                      testID="cancel-edit-draft-button"
                      label="Hủy chỉnh sửa"
                      secondary
                      disabled={isSavingDraft}
                      onPress={handleCancelEdit}
                    />
                  </View>
                </WorkoutCard>
              ) : null}

              {/* Reasoning */}
              {!isEditing && payload?.reasoning && payload.reasoning.length > 0 ? (
                <WorkoutCard testID="ai-proposal-reasoning-card">
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>Căn cứ đề xuất</Text>
                  {payload.reasoning.map((item, idx) => (
                    <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {item}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Missing data & limitations */}
              {!isEditing && payload?.missingData && payload.missingData.length > 0 ? (
                <WorkoutCard testID="ai-proposal-missing-data-card">
                  <Text style={[styles.h3, { color: theme.warningText }]}>
                    Dữ liệu còn thiếu
                  </Text>
                  {payload.missingData.map((item, idx) => (
                    <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {item}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {!isEditing && payload?.limitations && payload.limitations.length > 0 ? (
                <WorkoutCard testID="ai-proposal-limitations-card">
                  <Text style={[styles.h3, { color: theme.textSecondary }]}>Giới hạn lâm sàng & thực hành</Text>
                  {payload.limitations.map((item, idx) => (
                    <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                      • {item}
                    </Text>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Sessions & Prescriptions View Mode */}
              {!isEditing && payload?.plan?.sessions ? (
                <WorkoutCard testID="ai-proposal-sessions-card">
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>
                    Lịch tập 1 tuần ({payload.plan.sessions.length} buổi)
                  </Text>
                  {payload.plan.sessions.map((session, sIdx) => (
                    <View key={sIdx} style={[styles.sessionBlock, { borderTopColor: theme.border }]}>
                      <Text style={[styles.sessionTitle, { color: theme.textPrimary }]}>
                        {session.name} {session.focus ? `· ${session.focus}` : ''}
                      </Text>
                      {session.estimatedDurationMinutes ? (
                        <Text style={[styles.sessionMeta, { color: theme.textSecondary }]}>
                          Thời lượng ước tính: {session.estimatedDurationMinutes} phút
                        </Text>
                      ) : null}
                      {session.prescriptions.map((p, pIdx) => (
                        <View key={pIdx} style={styles.prescriptionRow}>
                          <Text style={[styles.prescriptionText, { color: theme.textPrimary }]}>
                            {pIdx + 1}. Bài tập (ID: {p.exerciseVariationId.slice(0, 8)}...): {p.targetSets} hiệp
                            {p.targetRepsMin ? ` x ${p.targetRepsMin}-${p.targetRepsMax ?? p.targetRepsMin} lần` : ''}
                            {p.restSeconds ? ` (Nghỉ ${p.restSeconds}s)` : ''}
                          </Text>
                        </View>
                      ))}
                    </View>
                  ))}
                </WorkoutCard>
              ) : null}

              {/* Before / After & Plan Impact Card */}
              {!isEditing && proposal.status === 'PENDING' ? (
                <WorkoutCard testID="ai-proposal-plan-impact-card">
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>
                    Đối chiếu kế hoạch & Tác động (Before / After)
                  </Text>
                  {currentActivePlan ? (
                    <View style={{ marginTop: 6 }}>
                      <Text style={[styles.subLabel, { color: theme.textSecondary }]}>
                        Kế hoạch hiện tại (Trước):
                      </Text>
                      <Text style={[styles.body, { color: theme.textPrimary, fontWeight: '600' }]}>
                        {currentActivePlan.plan.name} (Phiên bản công bố: {currentActivePlan.currentVersion?.versionNumber ? `v${currentActivePlan.currentVersion.versionNumber}` : 'Chưa có phiên bản'}) · Chủ thể: {currentActivePlan.plan.decisionOwnerType === 'TRAINER' ? 'Huấn luyện viên' : 'Học viên tự quản'}
                      </Text>
                      <Text style={[styles.subLabel, { color: theme.textSecondary, marginTop: 2, fontSize: 12 }]}>
                        [ID: {currentActivePlan.plan.id.slice(0, 8)}... · Aggregate Version: {currentActivePlan.plan.aggregateVersion}]
                      </Text>

                      <Text style={[styles.subLabel, { color: theme.textSecondary, marginTop: 8 }]}>
                        Kế hoạch đề xuất (Sau khi áp dụng):
                      </Text>
                      <Text style={[styles.body, { color: theme.textPrimary, fontWeight: '600' }]}>
                        {proposal.title} (Phiên bản xuất bản mới: v{(currentActivePlan.currentVersion?.versionNumber ?? 0) + 1}{proposal.structuredPayload?.plan?.sessions ? ` · ${proposal.structuredPayload.plan.sessions.length} buổi/tuần` : ''})
                      </Text>

                      {proposal.structuredPayload?.plan?.sessions ? (
                        <>
                          <Text style={[styles.subLabel, { color: theme.textSecondary, marginTop: 4 }]}>
                            Cấu trúc buổi tập đề xuất:
                          </Text>
                          {proposal.structuredPayload.plan.sessions.map((s, idx) => (
                            <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary, marginLeft: 4 }]}>
                              • Buổi {s.dayNumber ?? (idx + 1)}: {s.name} ({s.prescriptions.length} bài tập{s.focus ? ` · ${s.focus}` : ''})
                            </Text>
                          ))}
                        </>
                      ) : null}

                      <Text style={[styles.body, { color: theme.primary, marginTop: 6, fontSize: 13, fontStyle: 'italic' }]}>
                        • Phạm vi tác động lịch tập: Toàn bộ buổi tập đã lên lịch sẵn (materialized) và lịch sử tập luyện đã thực hiện được giữ nguyên vẹn không bị ghi đè. Các đợt tạo lịch tập mới tiếp theo sẽ áp dụng theo cấu trúc của phiên bản xuất bản mới này.
                      </Text>
                    </View>
                  ) : (
                    <View style={{ marginTop: 6 }}>
                      <Text style={[styles.subLabel, { color: theme.textSecondary }]}>
                        Kế hoạch hiện tại (Trước):
                      </Text>
                      <Text style={[styles.body, { color: theme.textPrimary }]}>
                        Chưa có kế hoạch nào đang áp dụng.
                      </Text>

                      <Text style={[styles.subLabel, { color: theme.textSecondary, marginTop: 8 }]}>
                        Kế hoạch đề xuất (Sau khi áp dụng):
                      </Text>
                      <Text style={[styles.body, { color: theme.textPrimary, fontWeight: '600' }]}>
                        {proposal.title} (Kích hoạt mới v1{proposal.structuredPayload?.plan?.sessions ? ` · ${proposal.structuredPayload.plan.sessions.length} buổi/tuần` : ''})
                      </Text>

                      {proposal.structuredPayload?.plan?.sessions ? (
                        <>
                          <Text style={[styles.subLabel, { color: theme.textSecondary, marginTop: 4 }]}>
                            Cấu trúc buổi tập đề xuất:
                          </Text>
                          {proposal.structuredPayload.plan.sessions.map((s, idx) => (
                            <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary, marginLeft: 4 }]}>
                              • Buổi {s.dayNumber ?? (idx + 1)}: {s.name} ({s.prescriptions.length} bài tập{s.focus ? ` · ${s.focus}` : ''})
                            </Text>
                          ))}
                        </>
                      ) : null}

                      <Text style={[styles.body, { color: theme.primary, marginTop: 6, fontSize: 13, fontStyle: 'italic' }]}>
                        • Kế hoạch mới sẽ được kích hoạt ngay sau khi chấp thuận.
                      </Text>
                    </View>
                  )}
                </WorkoutCard>
              ) : null}

              {/* Decision Section */}
              {!isEditing && proposal.status === 'PENDING' ? (
                <WorkoutCard testID="ai-proposal-decision-card">
                  <Text style={[styles.h3, { color: theme.textPrimary }]}>Quyết định thẩm quyền</Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    {proposal.decisionAuthority === 'TRAINER' && !isTrainer
                      ? 'Lưu ý: Bạn đang ở chế độ Huấn luyện viên. Chỉ Huấn luyện viên phụ trách mới có thẩm quyền chấp thuận đề xuất này.'
                      : 'Khi chấp thuận đề xuất 1 tuần (phiên bản v' +
                        (proposal.revision ?? 1) +
                        '):\n• Nếu chưa có giáo án đang áp dụng: Kế hoạch mới sẽ được tạo và kích hoạt.\n• Nếu đã có giáo án đang áp dụng: Phiên bản mới sẽ được xuất bản cho giáo án hiện tại. Lịch sử các buổi tập đã thực hiện và buổi tập đang diễn ra được bảo toàn nguyên vẹn.'}
                  </Text>

                  <Text style={[styles.label, { color: theme.textPrimary, marginTop: 8 }]}>
                    Ghi chú quyết định:
                  </Text>
                  <TextInput
                    testID="ai-decision-note-input"
                    style={[
                      styles.textInput,
                      {
                        color: theme.textPrimary,
                        borderColor: theme.border,
                        backgroundColor: theme.surfaceSubtle,
                      },
                    ]}
                    placeholder="Ghi chú lý do chấp thuận hoặc từ chối..."
                    placeholderTextColor={theme.textSecondary}
                    value={decisionNote}
                    onChangeText={setDecisionNote}
                  />

                  <View style={styles.decisionActions}>
                    <WorkoutButton
                      testID="accept-ai-proposal-button"
                      label={isDeciding ? 'Đang áp dụng...' : 'Chấp thuận & Áp dụng'}
                      busy={isDeciding}
                      disabled={isDeciding || !hasAuthority}
                      onPress={handleAccept}
                    />
                    <WorkoutButton
                      testID="reject-ai-proposal-button"
                      label="Từ chối đề xuất"
                      secondary
                      disabled={isDeciding || !hasAuthority}
                      onPress={handleReject}
                    />
                  </View>
                </WorkoutCard>
              ) : null}

              {proposal.status === 'APPLIED' ? (
                <WorkoutCard testID="ai-proposal-applied-success">
                  <Text style={[styles.h3, { color: theme.successText }]}>Đã áp dụng thành công</Text>
                  <Text style={[styles.body, { color: theme.textPrimary }]}>
                    Giáo án đã được tạo và kích hoạt. ID kế hoạch: {appliedPlanId ?? 'N/A'}.
                  </Text>
                </WorkoutCard>
              ) : null}

              {proposal.status === 'REJECTED' ? (
                <WorkoutCard testID="ai-proposal-rejected-notice">
                  <Text style={[styles.h3, { color: theme.textSecondary }]}>Đã từ chối đề xuất</Text>
                  <Text style={[styles.body, { color: theme.textSecondary }]}>
                    Đề xuất giáo án này đã bị từ chối và không làm thay đổi kế hoạch tập luyện hiện hành.
                  </Text>
                </WorkoutCard>
              ) : null}
            </View>
          )}
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
    paddingHorizontal: 16,
    paddingVertical: 12,
    borderBottomWidth: StyleSheet.hairlineWidth,
  },
  title: {
    fontSize: 18,
    fontWeight: '700',
  },
  closeBtn: {
    padding: 8,
  },
  closeText: {
    fontSize: 20,
    fontWeight: '600',
  },
  scrollContent: {
    padding: 16,
    gap: 12,
  },
  h2: {
    fontSize: 16,
    fontWeight: '700',
    marginBottom: 4,
  },
  h3: {
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 6,
  },
  body: {
    fontSize: 14,
    lineHeight: 20,
  },
  label: {
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 4,
  },
  subLabel: {
    fontSize: 12,
    lineHeight: 16,
    marginBottom: 4,
  },
  daysSelector: {
    flexDirection: 'row',
    gap: 8,
    marginVertical: 6,
  },
  dayOption: {
    paddingVertical: 8,
    paddingHorizontal: 14,
    borderRadius: 8,
  },
  dayOptionText: {
    fontSize: 13,
    fontWeight: '600',
  },
  textInput: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    fontSize: 14,
    marginVertical: 6,
  },
  badgeRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 8,
    marginBottom: 8,
  },
  badge: {
    paddingHorizontal: 8,
    paddingVertical: 4,
    borderRadius: 6,
  },
  badgeText: {
    fontSize: 12,
    fontWeight: '600',
  },
  metaText: {
    fontSize: 12,
  },
  bulletItem: {
    fontSize: 13,
    lineHeight: 18,
    marginBottom: 4,
  },
  sessionBlock: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: 8,
    marginTop: 8,
  },
  sessionTitle: {
    fontSize: 14,
    fontWeight: '600',
  },
  sessionMeta: {
    fontSize: 12,
    marginTop: 2,
  },
  prescriptionRow: {
    marginTop: 4,
  },
  prescriptionText: {
    fontSize: 13,
    lineHeight: 18,
  },
  decisionActions: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 12,
  },
  errorTitle: {
    fontSize: 14,
    fontWeight: '700',
    marginBottom: 4,
  },
  errorBody: {
    fontSize: 13,
    lineHeight: 18,
  },
  draftControlsRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 10,
  },
  smallActionBtn: {
    borderWidth: 1,
    borderRadius: 6,
    paddingHorizontal: 10,
    paddingVertical: 6,
  },
  smallActionBtnText: {
    fontSize: 12,
    fontWeight: '600',
  },
  editSessionBlock: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    marginVertical: 8,
  },
  editPrescriptionRow: {
    borderTopWidth: StyleSheet.hairlineWidth,
    paddingTop: 8,
    marginTop: 8,
  },
  prescriptionTitle: {
    fontSize: 13,
    fontWeight: '600',
    marginBottom: 4,
  },
  prescriptionInputRow: {
    flexDirection: 'row',
    gap: 8,
  },
  halfInput: {
    flex: 1,
  },
  fullInput: {
    flex: 1,
  },
  editActionRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 12,
  },
  chipsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: 6,
    marginTop: 6,
  },
  chip: {
    borderWidth: 1,
    borderRadius: 16,
    paddingHorizontal: 12,
    paddingVertical: 6,
  },
  chipText: {
    fontSize: 12,
    fontWeight: '600',
  },
  limitationChoiceRow: {
    flexDirection: 'row',
    gap: 8,
    marginTop: 6,
  },
  limitationChoiceBtn: {
    flex: 1,
    borderWidth: 1,
    borderRadius: 8,
    paddingVertical: 10,
    paddingHorizontal: 8,
    alignItems: 'center',
    justifyContent: 'center',
  },
  choiceBtnText: {
    fontSize: 12,
    fontWeight: '600',
  },
  fieldErrorText: {
    fontSize: 12,
    marginTop: 4,
    fontWeight: '500',
  },
  alertBox: {
    borderWidth: 1,
    borderRadius: 8,
    padding: 10,
    marginTop: 10,
    marginBottom: 6,
  },
  alertTitle: {
    fontSize: 13,
    fontWeight: '700',
    marginBottom: 4,
  },
  alertBody: {
    fontSize: 12,
    lineHeight: 16,
    marginTop: 2,
  },
});
