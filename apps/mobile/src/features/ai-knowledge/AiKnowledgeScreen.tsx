import React, { useCallback, useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  KeyboardAvoidingView,
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
import { useRouter } from 'expo-router';
import { useAuth } from '@/features/auth/AuthContext';
import { coachingApi } from '@/services/coachingApi';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { layout, spacing } from '@/design-system/tokens/spacing';
import { radius } from '@/design-system/tokens/radius';
import { typography } from '@/design-system/tokens/typography';
import {
  aiKnowledgeApi,
  countCodePoints,
  getAiKnowledgeErrorMessage,
  F01_QUESTION_MIN_LENGTH,
  F01_QUESTION_MAX_LENGTH,
} from '@/services/aiKnowledgeApi';
import {
  AiRunResponse,
  F01KnowledgeQueryResponse,
  KnowledgeCitation,
} from '@/types/aiKnowledge';
import { CoachingRelationship } from '@/types/coaching';

/**
 * Example questions aligned with the ACTIVE knowledge corpus (WHO & ISSN guidelines).
 */
const GENERAL_EXAMPLE_QUESTIONS = [
  'WHO khuyến nghị thời gian hoạt động thể chất hàng tuần cho người lớn là bao nhiêu?',
  'Khuyến nghị tập tăng cường cơ bắp bao nhiêu ngày mỗi tuần?',
  'Người cao tuổi nên tập luyện thăng bằng và sức mạnh như thế nào?',
];

const PERSONAL_EXAMPLE_QUESTIONS = [
  'Tôi nên phân bổ thời gian nghỉ giữa các hiệp tập Squat như thế nào để vừa an toàn vừa tăng cơ tốt?',
  'Bài tập thay thế nào cho Barbell Bench Press nếu phòng tập chỉ có tạ đơn?',
  'Mức độ phục hồi và khối lượng tập luyện phù hợp với thể trạng hiện tại của tôi là bao nhiêu?',
];

export interface AiKnowledgeScreenProps {
  studentId?: string;
  relationshipId?: string;
  initialRunId?: string;
  onBack?: () => void;
}

export type ContextMode = 'GENERAL' | 'PERSONAL';

export interface ManagedStudent {
  studentId: string;
  displayName: string;
}

export function AiKnowledgeScreen({
  studentId: propStudentId,
  relationshipId,
  initialRunId,
  onBack,
}: AiKnowledgeScreenProps = {}) {
  const router = useRouter();
  const { user, activeCapability } = useAuth();
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);

  const isTrainer =
    activeCapability === 'TRAINER' ||
    (!user?.capabilities?.hasStudentProfile && Boolean(user?.capabilities?.hasTrainerProfile));

  // Context mode: GENERAL or PERSONAL
  const [contextMode, setContextMode] = useState<ContextMode>(() =>
    propStudentId ? 'PERSONAL' : 'GENERAL'
  );

  // Student selection for Trainer
  const [selectedStudentId, setSelectedStudentId] = useState<string | undefined>(propStudentId);
  const [managedStudents, setManagedStudents] = useState<ManagedStudent[]>([]);
  const [isLoadingStudents, setIsLoadingStudents] = useState(false);
  const [studentPage, setStudentPage] = useState(0);
  const [hasMoreStudents, setHasMoreStudents] = useState(false);
  const [isLoadingMoreStudents, setIsLoadingMoreStudents] = useState(false);

  // Question & Query State
  const [question, setQuestion] = useState('');
  const [lastSubmittedQuestion, setLastSubmittedQuestion] = useState('');
  const [resultQuestion, setResultQuestion] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<F01KnowledgeQueryResponse | null>(null);

  // Readback Run State
  const [readbackRun, setReadbackRun] = useState<AiRunResponse | null>(null);
  const [isLoadingRun, setIsLoadingRun] = useState(() => Boolean(initialRunId));

  // In-flight guard and cancellation controller
  const isSubmittingRef = useRef(false);
  const isMountedRef = useRef(true);
  const activeRequestIdRef = useRef(0);
  const inFlightAbortRef = useRef<AbortController | null>(null);

  // Cleanup on unmount
  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
      if (inFlightAbortRef.current) {
        inFlightAbortRef.current.abort();
        inFlightAbortRef.current = null;
      }
    };
  }, []);

  const fetchStudents = useCallback(
    async (pageToFetch = 0, isAppend = false) => {
      if (!isTrainer || propStudentId) return;
      if (isAppend) {
        setIsLoadingMoreStudents(true);
      } else {
        setIsLoadingStudents(true);
      }
      try {
        const pageSize = 20;
        const page = await coachingApi.getRelationships(pageToFetch, pageSize);
        if (!isMountedRef.current) return;
        const activeRels = (page.items ?? []).filter(
          (r: CoachingRelationship) => r.status === 'ACTIVE'
        );
        const newStudents: ManagedStudent[] = activeRels.map((r: CoachingRelationship) => ({
          studentId: r.studentId,
          displayName: r.counterparty?.displayName || `Học viên (${r.studentId.slice(0, 8)})`,
        }));

        setManagedStudents((prev) => {
          if (!isAppend) return newStudents;
          const existingIds = new Set(prev.map((s) => s.studentId));
          const uniqueNew = newStudents.filter((s) => !existingIds.has(s.studentId));
          return [...prev, ...uniqueNew];
        });

        setStudentPage(pageToFetch);
        setHasMoreStudents((page.items ?? []).length >= pageSize);

        if (!isAppend && newStudents.length > 0) {
          setSelectedStudentId((prev) => prev || newStudents[0].studentId);
        }
      } catch {
        // Gracefully handle relationship query error
      } finally {
        if (isMountedRef.current) {
          setIsLoadingStudents(false);
          setIsLoadingMoreStudents(false);
        }
      }
    },
    [isTrainer, propStudentId]
  );

  // Fetch active managed students for Trainer on mount
  useEffect(() => {
    if (isTrainer && !propStudentId && isMountedRef.current) {
      fetchStudents(0, false);
    }
  }, [isTrainer, propStudentId, fetchStudents]);

  const handleLoadMoreStudents = () => {
    if (hasMoreStudents && !isLoadingMoreStudents && !isLoadingStudents) {
      fetchStudents(studentPage + 1, true);
    }
  };

  const handleRefreshStudents = () => {
    fetchStudents(0, false);
  };

  // Readback run verification if initialRunId prop is provided
  useEffect(() => {
    if (!initialRunId) return;
    const controller = new AbortController();
    queueMicrotask(() => {
      if (isMountedRef.current && !controller.signal.aborted) {
        setIsLoadingRun(true);
        setErrorMessage(null);
      }
    });

    aiKnowledgeApi
      .getRun(initialRunId, controller.signal)
      .then((run) => {
        if (isMountedRef.current && !controller.signal.aborted) {
          setReadbackRun(run);
        }
      })
      .catch((err) => {
        if (isMountedRef.current && !controller.signal.aborted) {
          setErrorMessage(getAiKnowledgeErrorMessage(err));
        }
      })
      .finally(() => {
        if (isMountedRef.current && !controller.signal.aborted) {
          setIsLoadingRun(false);
        }
      });

    return () => {
      controller.abort();
    };
  }, [initialRunId]);

  // Context mode change handler with anti-stale cancellation
  const handleContextModeChange = (mode: ContextMode) => {
    if (mode === contextMode) return;
    if (inFlightAbortRef.current) {
      inFlightAbortRef.current.abort();
      inFlightAbortRef.current = null;
    }
    activeRequestIdRef.current++;
    isSubmittingRef.current = false;
    setIsSubmitting(false);
    setResult(null);
    setResultQuestion('');
    setErrorMessage(null);
    setContextMode(mode);
  };

  // Student selection change handler with anti-stale cancellation
  const handleSelectStudent = (id: string) => {
    if (id === selectedStudentId) return;
    if (inFlightAbortRef.current) {
      inFlightAbortRef.current.abort();
      inFlightAbortRef.current = null;
    }
    activeRequestIdRef.current++;
    isSubmittingRef.current = false;
    setIsSubmitting(false);
    setResult(null);
    setResultQuestion('');
    setErrorMessage(null);
    setSelectedStudentId(id);
  };

  const trimmedQuestion = question.trim();
  const codePoints = countCodePoints(trimmedQuestion);
  const isValidLength =
    codePoints >= F01_QUESTION_MIN_LENGTH && codePoints <= F01_QUESTION_MAX_LENGTH;

  const handleSubmit = useCallback(
    async (questionToAsk?: string) => {
      const q = (questionToAsk ?? question).trim();
      const qCodePoints = countCodePoints(q);

      if (isSubmittingRef.current) {
        return; // Prevent duplicate submission
      }

      if (qCodePoints < F01_QUESTION_MIN_LENGTH) {
        setErrorMessage(`Câu hỏi phải có ít nhất ${F01_QUESTION_MIN_LENGTH} ký tự.`);
        return;
      }

      if (qCodePoints > F01_QUESTION_MAX_LENGTH) {
        setErrorMessage(`Câu hỏi không được vượt quá ${F01_QUESTION_MAX_LENGTH} ký tự.`);
        return;
      }

      // Enforce Trainer must have selected student when querying personalized context
      if (isTrainer && contextMode === 'PERSONAL' && !selectedStudentId) {
        setErrorMessage('Vui lòng chọn học viên để tra cứu theo ngữ cảnh cá nhân.');
        return;
      }

      if (inFlightAbortRef.current) {
        inFlightAbortRef.current.abort();
      }
      const controller = new AbortController();
      inFlightAbortRef.current = controller;
      const reqId = ++activeRequestIdRef.current;

      isSubmittingRef.current = true;
      setIsSubmitting(true);
      setErrorMessage(null);
      setResult(null); // Clear previous result immediately
      setLastSubmittedQuestion(q);

      try {
        const includePersonal = contextMode === 'PERSONAL';
        // Student is strictly bound to self (user.id). Trainer uses selectedStudentId.
        const targetStudent = includePersonal
          ? (isTrainer ? selectedStudentId : user?.id)
          : undefined;

        const response = await aiKnowledgeApi.queryKnowledge(
          {
            question: q,
            language: 'vi',
            includePersonalContext: includePersonal,
            ...(targetStudent ? { studentId: targetStudent } : {}),
          },
          { signal: controller.signal }
        );

        if (!isMountedRef.current || activeRequestIdRef.current !== reqId) return;
        setResult(response);
        setResultQuestion(q);
      } catch (err: unknown) {
        if (!isMountedRef.current || activeRequestIdRef.current !== reqId) return;
        const message = getAiKnowledgeErrorMessage(err);
        setErrorMessage(message);
        setResult(null);
        setResultQuestion('');
      } finally {
        if (isMountedRef.current && activeRequestIdRef.current === reqId) {
          isSubmittingRef.current = false;
          setIsSubmitting(false);
        }
      }
    },
    [question, isTrainer, contextMode, selectedStudentId, user]
  );

  const handleClear = () => {
    setQuestion('');
    setErrorMessage(null);
    setResult(null);
    setResultQuestion('');
    setLastSubmittedQuestion('');
  };

  const handleSelectExample = (example: string) => {
    setQuestion(example);
    setErrorMessage(null);
  };

  const handleBack = () => {
    if (onBack) {
      onBack();
    } else {
      router.back();
    }
  };

  const activeExamples =
    contextMode === 'PERSONAL' ? PERSONAL_EXAMPLE_QUESTIONS : GENERAL_EXAMPLE_QUESTIONS;

  return (
    <SafeAreaView edges={['top']} style={[styles.screen, { backgroundColor: theme.canvas }]}>
      <KeyboardAvoidingView
        style={styles.screen}
        behavior={Platform.OS === 'ios' ? 'padding' : undefined}>
        <ScrollView
          testID="ai-knowledge-screen"
          contentContainerStyle={styles.scrollContent}
          keyboardShouldPersistTaps="handled">
          {/* Header */}
          <View style={styles.header}>
            <Pressable
              testID="ai-knowledge-back-button"
              accessibilityRole="button"
              accessibilityLabel="Quay lại"
              onPress={handleBack}
              style={[styles.backButton, { borderColor: theme.border }]}>
              <Text style={[styles.backButtonText, { color: theme.primary }]}>← Quay lại</Text>
            </Pressable>
            <Text style={[styles.title, { color: theme.textPrimary }]}>
              Trợ lý Kiến thức Thể hình (AI)
            </Text>
            <Text style={[styles.subtitle, { color: theme.textSecondary }]}>
              Tra cứu nguyên tắc vận động và khoa học thể hình dựa trên tài liệu đã kiểm định (WHO, ISSN).
              Câu trả lời chỉ mang tính chất tham khảo (INFORMATION), không thay thế tư vấn y khoa hay tự động áp dụng kế hoạch tập.
            </Text>
          </View>

          {/* Context Selector Card */}
          <View
            testID="ai-knowledge-context-selector-card"
            style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
            <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>
              Ngữ cảnh tra cứu
            </Text>
            <View style={styles.contextTabsRow}>
              <Pressable
                testID="ai-knowledge-context-general-tab"
                accessibilityRole="tab"
                accessibilityLabel="Tra cứu kiến thức chung"
                accessibilityState={{ selected: contextMode === 'GENERAL' }}
                onPress={() => handleContextModeChange('GENERAL')}
                style={[
                  styles.contextTab,
                  {
                    backgroundColor:
                      contextMode === 'GENERAL' ? theme.primary : theme.surfaceSubtle,
                    borderColor: theme.border,
                  },
                ]}>
                <Text
                  style={[
                    styles.contextTabText,
                    {
                      color:
                        contextMode === 'GENERAL' ? theme.textOnPrimary : theme.textPrimary,
                    },
                  ]}>
                  Kiến thức chung
                </Text>
              </Pressable>

              <Pressable
                testID="ai-knowledge-context-personal-tab"
                accessibilityRole="tab"
                accessibilityLabel={
                  isTrainer ? 'Ngữ cảnh học viên' : 'Cá nhân hóa (Hồ sơ của tôi)'
                }
                accessibilityState={{ selected: contextMode === 'PERSONAL' }}
                onPress={() => handleContextModeChange('PERSONAL')}
                style={[
                  styles.contextTab,
                  {
                    backgroundColor:
                      contextMode === 'PERSONAL' ? theme.primary : theme.surfaceSubtle,
                    borderColor: theme.border,
                  },
                ]}>
                <Text
                  style={[
                    styles.contextTabText,
                    {
                      color:
                        contextMode === 'PERSONAL' ? theme.textOnPrimary : theme.textPrimary,
                    },
                  ]}>
                  {isTrainer ? 'Ngữ cảnh học viên' : 'Cá nhân hóa (Hồ sơ của tôi)'}
                </Text>
              </Pressable>
            </View>

            {/* Trainer Student Selection */}
            {isTrainer && contextMode === 'PERSONAL' && (
              <View testID="ai-knowledge-trainer-student-section" style={styles.studentPickerSection}>
                <View style={styles.studentPickerHeaderRow}>
                  <Text style={[styles.subLabel, { color: theme.textSecondary }]}>
                    {propStudentId ? 'Học viên áp dụng:' : 'Chọn học viên cần tư vấn:'}
                  </Text>
                  {!propStudentId && (
                    <Pressable
                      testID="ai-knowledge-refresh-students-button"
                      accessibilityRole="button"
                      accessibilityLabel="Làm mới danh sách học viên"
                      onPress={handleRefreshStudents}
                      disabled={isLoadingStudents}>
                      <Text style={[styles.refreshText, { color: theme.primary }]}>
                        {isLoadingStudents ? 'Đang tải...' : 'Làm mới'}
                      </Text>
                    </Pressable>
                  )}
                </View>

                {propStudentId ? (
                  <View
                    testID="ai-knowledge-locked-student-badge"
                    style={[styles.studentLockedBadge, { backgroundColor: theme.surfaceSubtle, borderColor: theme.border }]}>
                    <Text style={[styles.studentLockedText, { color: theme.textPrimary }]}>
                      Học viên ID: {propStudentId}
                    </Text>
                  </View>
                ) : isLoadingStudents && managedStudents.length === 0 ? (
                  <ActivityIndicator size="small" color={theme.primary} />
                ) : managedStudents.length === 0 ? (
                  <Text
                    testID="ai-knowledge-no-students-warning"
                    style={[styles.noStudentsText, { color: theme.dangerText }]}>
                    Bạn chưa có học viên nào đang trong kỳ huấn luyện hoạt động (ACTIVE).
                  </Text>
                ) : (
                  <>
                    <View testID="ai-knowledge-student-chips-container" style={styles.studentChipsRow}>
                      {managedStudents.map((s) => {
                        const isSelected = s.studentId === selectedStudentId;
                        return (
                          <Pressable
                            key={s.studentId}
                            testID={`ai-knowledge-student-option-${s.studentId}`}
                            accessibilityRole="button"
                            accessibilityLabel={`Chọn học viên ${s.displayName}`}
                            onPress={() => handleSelectStudent(s.studentId)}
                            style={[
                              styles.studentChip,
                              {
                                backgroundColor: isSelected ? theme.primary : theme.surfaceSubtle,
                                borderColor: isSelected ? theme.primary : theme.border,
                              },
                            ]}>
                            <Text
                              style={[
                                styles.studentChipText,
                                { color: isSelected ? theme.textOnPrimary : theme.textPrimary },
                              ]}>
                              {s.displayName}
                            </Text>
                          </Pressable>
                        );
                      })}
                    </View>

                    {hasMoreStudents && (
                      <Pressable
                        testID="ai-knowledge-load-more-students-button"
                        accessibilityRole="button"
                        accessibilityLabel="Tải thêm học viên"
                        onPress={handleLoadMoreStudents}
                        disabled={isLoadingMoreStudents}
                        style={[styles.loadMoreButton, { borderColor: theme.border }]}>
                        {isLoadingMoreStudents ? (
                          <ActivityIndicator size="small" color={theme.primary} />
                        ) : (
                          <Text style={[styles.loadMoreButtonText, { color: theme.primary }]}>
                            Tải thêm học viên
                          </Text>
                        )}
                      </Pressable>
                    )}
                  </>
                )}
              </View>
            )}

            {/* Student Context Note */}
            {!isTrainer && contextMode === 'PERSONAL' && (
              <View testID="ai-knowledge-student-scope-note" style={styles.personalScopeNote}>
                <Text style={[styles.personalScopeNoteText, { color: theme.textSecondary }]}>
                  Áp dụng thông tin từ mục tiêu tập luyện, mức độ kinh nghiệm và giới hạn vận động của bạn.
                </Text>
              </View>
            )}
          </View>

          {/* Readback / History Card (when initialRunId is provided) */}
          {isLoadingRun && (
            <View
              testID="ai-knowledge-run-loading"
              style={[styles.stateCard, { backgroundColor: theme.surface, borderColor: theme.border }]}>
              <ActivityIndicator size="small" color={theme.primary} />
              <Text style={[styles.stateBody, { color: theme.textSecondary }]}>
                Đang xác thực thông tin tra cứu lịch sử ({initialRunId})...
              </Text>
            </View>
          )}

          {readbackRun && !isLoadingRun && (
            <View
              testID="ai-knowledge-readback-card"
              style={[styles.card, { backgroundColor: theme.surfaceSubtle, borderColor: theme.border }]}>
              <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>
                Lịch sử tra cứu (Run ID: {readbackRun.runId})
              </Text>
              <Text style={[styles.stateBody, { color: theme.textSecondary }]}>
                Trạng thái: {readbackRun.runStatus} • Loại yêu cầu: {readbackRun.requestType}
              </Text>
              {readbackRun.completedAt && (
                <Text style={[styles.charCount, { color: theme.textSecondary }]}>
                  Thời gian hoàn tất: {readbackRun.completedAt}
                </Text>
              )}
            </View>
          )}

          {/* Question Input Card */}
          <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
            <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>Đặt câu hỏi</Text>

            <TextInput
              testID="ai-knowledge-question-input"
              accessibilityLabel="Nhập câu hỏi kiến thức thể hình"
              multiline
              numberOfLines={4}
              maxLength={F01_QUESTION_MAX_LENGTH}
              placeholder={
                contextMode === 'PERSONAL'
                  ? 'Ví dụ: Tôi nên phân bổ thời gian nghỉ giữa các hiệp tập Squat như thế nào?'
                  : 'Ví dụ: WHO khuyến nghị thời gian hoạt động thể chất hàng tuần là bao nhiêu?'
              }
              placeholderTextColor={theme.textSecondary}
              value={question}
              onChangeText={(text) => {
                setQuestion(text);
              }}
              editable={!isSubmitting}
              style={[
                styles.textInput,
                {
                  backgroundColor: theme.surfaceSubtle,
                  borderColor: theme.border,
                  color: theme.textPrimary,
                },
              ]}
            />

            {/* Input Controls */}
            <View style={styles.inputControlsRow}>
              <Text
                testID="ai-knowledge-char-count"
                style={[styles.charCount, { color: theme.textSecondary }]}>
                {countCodePoints(question)}/{F01_QUESTION_MAX_LENGTH}
              </Text>

              {question.length > 0 && !isSubmitting && (
                <Pressable
                  testID="ai-knowledge-clear-button"
                  accessibilityRole="button"
                  accessibilityLabel="Xóa nội dung câu hỏi"
                  onPress={handleClear}>
                  <Text style={[styles.clearButtonText, { color: theme.textSecondary }]}>
                    Xóa
                  </Text>
                </Pressable>
              )}
            </View>

            {/* Submit Button */}
            <Pressable
              testID="ai-knowledge-submit-button"
              accessibilityRole="button"
              accessibilityLabel="Gửi câu hỏi tra cứu kiến thức"
              accessibilityState={{ disabled: isSubmitting || !isValidLength, busy: isSubmitting }}
              disabled={isSubmitting || !isValidLength}
              onPress={() => handleSubmit()}
              style={({ pressed }) => [
                styles.submitButton,
                {
                  backgroundColor: theme.primary,
                  opacity: isSubmitting || !isValidLength ? 0.4 : pressed ? 0.8 : 1,
                },
              ]}>
              {isSubmitting ? (
                <View style={styles.submittingRow}>
                  <ActivityIndicator size="small" color={theme.textOnPrimary} />
                  <Text style={[styles.submitButtonText, { color: theme.textOnPrimary }]}>
                    Đang tra cứu...
                  </Text>
                </View>
              ) : (
                <Text style={[styles.submitButtonText, { color: theme.textOnPrimary }]}>
                  Gửi câu hỏi
                </Text>
              )}
            </Pressable>

            {/* Example Queries */}
            {!result && !isSubmitting && (
              <View style={styles.examplesSection}>
                <Text style={[styles.examplesLabel, { color: theme.textSecondary }]}>
                  Câu hỏi gợi ý ({contextMode === 'PERSONAL' ? 'Cá nhân hóa' : 'Kiến thức chung'}):
                </Text>
                {activeExamples.map((example, idx) => (
                  <Pressable
                    key={idx}
                    testID={`ai-knowledge-example-${idx}`}
                    accessibilityRole="button"
                    accessibilityLabel={`Chọn câu hỏi gợi ý: ${example}`}
                    style={[styles.exampleChip, { borderColor: theme.border }]}
                    onPress={() => handleSelectExample(example)}>
                    <Text style={[styles.exampleChipText, { color: theme.primary }]}>
                      • {example}
                    </Text>
                  </Pressable>
                ))}
              </View>
            )}
          </View>

          {/* Loading State */}
          {isSubmitting && (
            <View
              testID="ai-knowledge-loading-state"
              accessibilityRole="progressbar"
              style={[styles.stateCard, { backgroundColor: theme.surface, borderColor: theme.border }]}>
              <ActivityIndicator size="large" color={theme.primary} />
              <Text style={[styles.stateTitle, { color: theme.textPrimary }]}>
                Đang xử lý câu hỏi...
              </Text>
              <Text style={[styles.stateBody, { color: theme.textSecondary }]}>
                Hệ thống đang truy xuất tài liệu chuyên môn và tổng hợp căn cứ khoa học theo ngữ cảnh của bạn.
              </Text>
            </View>
          )}

          {/* Error State */}
          {errorMessage && !isSubmitting && (
            <View
              testID="ai-knowledge-error-state"
              accessibilityRole="alert"
              style={[
                styles.stateCard,
                {
                  backgroundColor: theme.surface,
                  borderColor: theme.dangerText,
                },
              ]}>
              <Text style={[styles.stateTitle, { color: theme.dangerText }]}>
                Không thể hoàn thành tra cứu
              </Text>
              <Text style={[styles.stateBody, { color: theme.textPrimary }]}>
                {errorMessage}
              </Text>
              {lastSubmittedQuestion.length > 0 && (
                <Text style={[styles.retryQuestionPreview, { color: theme.textSecondary }]}>
                  Câu hỏi: “{lastSubmittedQuestion}”
                </Text>
              )}
              <View style={styles.errorButtonsRow}>
                <Pressable
                  testID="ai-knowledge-retry-button"
                  accessibilityRole="button"
                  accessibilityLabel="Thử lại câu hỏi vừa gửi"
                  style={[styles.retryButton, { borderColor: theme.primary }]}
                  onPress={() => handleSubmit(lastSubmittedQuestion)}>
                  <Text style={[styles.retryButtonText, { color: theme.primary }]}>
                    Thử lại
                  </Text>
                </Pressable>

                {isTrainer && contextMode === 'PERSONAL' && !propStudentId && (
                  <Pressable
                    testID="ai-knowledge-error-refresh-students-button"
                    accessibilityRole="button"
                    accessibilityLabel="Làm mới danh sách học viên khi gặp lỗi quyền truy cập"
                    style={[styles.refreshStudentsErrorButton, { borderColor: theme.border }]}
                    onPress={handleRefreshStudents}>
                    <Text style={[styles.refreshStudentsErrorButtonText, { color: theme.textPrimary }]}>
                      Đồng bộ lại học viên
                    </Text>
                  </Pressable>
                )}
              </View>
            </View>
          )}

          {/* Result Presentation */}
          {result && !isSubmitting && (
            <View testID="ai-knowledge-result-container" style={styles.resultContainer}>
              {/* INFORMATION Result */}
              {result.resultType === 'INFORMATION' && (
                <>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.badgeText, { color: theme.primary }]}>
                      THÔNG TIN THAM KHẢO TỪ NGUỒN ĐÃ KIỂM ĐỊNH (CHỈ XEM)
                    </Text>
                  </View>

                  {/* Data Freshness (asOf) */}
                  {result.asOf && (
                    <View style={styles.asOfRow}>
                      <Text
                        testID="ai-knowledge-as-of"
                        style={[styles.asOfText, { color: theme.textSecondary }]}>
                        Thời điểm dữ liệu: {result.asOf}
                      </Text>
                    </View>
                  )}

                  {/* Missing Data Card / Banner */}
                  {result.missingData && result.missingData.length > 0 && (
                    <View
                      testID="ai-knowledge-missing-data-card"
                      style={[
                        styles.card,
                        {
                          backgroundColor: theme.surfaceSubtle,
                          borderColor: theme.border,
                        },
                      ]}>
                      <Text style={[styles.missingDataTitle, { color: theme.textPrimary }]}>
                        Lưu ý thông tin chưa xác định ({result.missingData.length})
                      </Text>
                      <Text style={[styles.missingDataHint, { color: theme.textSecondary }]}>
                        Thông tin phản hồi hiện chỉ áp dụng mức chung do bạn chưa thiết lập đầy đủ mục tiêu hoặc dữ liệu thể chất:
                      </Text>
                      {result.missingData.map((item, idx) => (
                        <Text
                          key={idx}
                          testID={`ai-knowledge-missing-item-${idx}`}
                          style={[styles.bulletItem, { color: theme.textPrimary }]}>
                          • {item}
                        </Text>
                      ))}
                    </View>
                  )}

                  {/* Answer Text Card */}
                  <View
                    testID="ai-knowledge-answer-card"
                    style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                    {resultQuestion.length > 0 && (
                      <Text style={[styles.answeredQuestionText, { color: theme.textSecondary }]}>
                        Câu hỏi: “{resultQuestion}”
                      </Text>
                    )}
                    <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                      Câu trả lời
                    </Text>
                    <Text
                      testID="ai-knowledge-answer-text"
                      style={[styles.answerText, { color: theme.textPrimary }]}>
                      {result.answer ?? 'Không có nội dung trả lời.'}
                    </Text>
                  </View>

                  {/* Citations List */}
                  {result.citations && result.citations.length > 0 && (
                    <View
                      testID="ai-knowledge-citations-list"
                      style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                      <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                        Nguồn trích dẫn ({result.citations.length})
                      </Text>

                      {result.citations.map((citation: KnowledgeCitation, idx: number) => (
                        <View
                          key={idx}
                          testID={`ai-knowledge-citation-${idx}`}
                          style={[
                            styles.citationCard,
                            {
                              backgroundColor: theme.surfaceSubtle,
                              borderColor: theme.border,
                            },
                          ]}>
                          <View style={styles.citationHeader}>
                            <View style={[styles.citationTag, { backgroundColor: theme.canvas }]}>
                              <Text style={[styles.citationTagText, { color: theme.primary }]}>
                                {citation.documentId} v{citation.version}
                              </Text>
                            </View>
                            <Text style={[styles.citationSource, { color: theme.textSecondary }]}>
                              {citation.sourceId}
                            </Text>
                          </View>

                          {citation.locator?.heading && (
                            <Text style={[styles.citationHeading, { color: theme.textPrimary }]}>
                              Mục: {citation.locator.heading}
                            </Text>
                          )}

                          {citation.locator?.pageRange && citation.locator.pageRange.length === 2 && (
                            <Text style={[styles.citationPageRange, { color: theme.textSecondary }]}>
                              Trang: {citation.locator.pageRange[0]}–{citation.locator.pageRange[1]}
                            </Text>
                          )}

                          {citation.quoteOrExcerpt && (
                            <View
                              style={[
                                styles.excerptBox,
                                {
                                  borderLeftColor: theme.primary,
                                  backgroundColor: theme.canvas,
                                },
                              ]}>
                              <Text
                                style={[styles.excerptText, { color: theme.textSecondary }]}>
                                “{citation.quoteOrExcerpt}”
                              </Text>
                            </View>
                          )}
                        </View>
                      ))}
                    </View>
                  )}

                  {/* Limitations List */}
                  {result.limitations && result.limitations.length > 0 && (
                    <View
                      testID="ai-knowledge-limitations-card"
                      style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                      <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                        Phạm vi & Giới hạn áp dụng
                      </Text>
                      {result.limitations.map((item, idx) => (
                        <Text
                          key={idx}
                          testID={`ai-knowledge-limitation-${idx}`}
                          style={[styles.bulletItem, { color: theme.textSecondary }]}>
                          • {item}
                        </Text>
                      ))}
                    </View>
                  )}
                </>
              )}

              {/* INSUFFICIENT_DATA Result */}
              {result.resultType === 'INSUFFICIENT_DATA' && (
                <View
                  testID="ai-knowledge-insufficient-card"
                  style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.badgeText, { color: theme.textSecondary }]}>
                      CHƯA ĐỦ DỮ LIỆU CHỨNG THỰC
                    </Text>
                  </View>
                  <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                    Không đủ bằng chứng trong kho tri thức
                  </Text>
                  <Text style={[styles.answerText, { color: theme.textSecondary }]}>
                    {result.answer ??
                      'Kho tri thức hiện hành chưa có đủ dữ liệu đã kiểm định để trả lời câu hỏi này một cách chính xác.'}
                  </Text>
                  {result.missingData && result.missingData.length > 0 && (
                    <View style={styles.subList}>
                      <Text style={[styles.subListTitle, { color: theme.textPrimary }]}>
                        Các thông tin cần bổ sung:
                      </Text>
                      {result.missingData.map((item, idx) => (
                        <Text key={idx} style={[styles.bulletItem, { color: theme.textSecondary }]}>
                          • {item}
                        </Text>
                      ))}
                    </View>
                  )}
                </View>
              )}

              {/* BLOCKED Result */}
              {result.resultType === 'BLOCKED' && (
                <View
                  testID="ai-knowledge-blocked-card"
                  style={[
                    styles.card,
                    {
                      backgroundColor: theme.surface,
                      borderColor: theme.dangerText,
                    },
                  ]}>
                  <View style={[styles.badge, { backgroundColor: theme.surfaceSubtle }]}>
                    <Text style={[styles.badgeText, { color: theme.dangerText }]}>
                      TỪ CHỐI BỞI CHÍNH SÁCH AN TOÀN
                    </Text>
                  </View>
                  <Text style={[styles.sectionTitle, { color: theme.dangerText }]}>
                    Câu hỏi ngoài phạm vi hỗ trợ
                  </Text>
                  <Text style={[styles.answerText, { color: theme.textPrimary }]}>
                    {result.answer ??
                      'Câu hỏi không thể được xử lý do vi phạm chính sách an toàn hoặc nằm ngoài phạm vi thể hình và khoa học vận động.'}
                  </Text>
                </View>
              )}

              {/* Session Disclaimer (strictly read-only INFORMATION) */}
              <View style={styles.disclaimerBox}>
                <Text style={[styles.disclaimerText, { color: theme.textSecondary }]}>
                  Lưu ý: Kết quả trên là thông tin giải thích chuyên môn (kết quả INFORMATION chỉ xem, không tự động sửa đổi mục tiêu hay kế hoạch).
                  Kết quả hiển thị trong phiên làm việc hiện tại và không được lưu giữ vĩnh viễn trên màn hình.
                </Text>
              </View>
            </View>
          )}
        </ScrollView>
      </KeyboardAvoidingView>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  screen: {
    flex: 1,
  },
  scrollContent: {
    padding: layout.mobileScreenPadding,
    paddingBottom: spacing.xxl,
    gap: spacing.lg,
  },
  header: {
    gap: spacing.xs,
  },
  backButton: {
    alignSelf: 'flex-start',
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    marginBottom: spacing.xs,
  },
  backButtonText: {
    ...typography.label,
  },
  title: {
    ...typography.h1,
  },
  subtitle: {
    ...typography.bodySmall,
    lineHeight: 20,
  },
  card: {
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.lg,
    gap: spacing.md,
  },
  cardTitle: {
    ...typography.h3,
  },
  contextTabsRow: {
    flexDirection: 'row',
    gap: spacing.sm,
  },
  contextTab: {
    flex: 1,
    paddingVertical: spacing.sm,
    paddingHorizontal: spacing.md,
    borderRadius: radius.md,
    borderWidth: 1,
    alignItems: 'center',
    justifyContent: 'center',
  },
  contextTabText: {
    ...typography.label,
    fontSize: 13,
  },
  studentPickerSection: {
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  studentPickerHeaderRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  refreshText: {
    ...typography.caption,
    textDecorationLine: 'underline',
  },
  loadMoreButton: {
    alignSelf: 'flex-start',
    marginTop: spacing.xs,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.md,
    borderWidth: 1,
  },
  loadMoreButtonText: {
    ...typography.caption,
    fontWeight: '600',
  },
  subLabel: {
    ...typography.caption,
    fontWeight: '600',
  },
  studentChipsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  studentChip: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
  },
  studentChipText: {
    ...typography.bodySmall,
    fontWeight: '500',
  },
  studentLockedBadge: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
  },
  studentLockedText: {
    ...typography.bodySmall,
    fontWeight: '600',
  },
  noStudentsText: {
    ...typography.caption,
    fontStyle: 'italic',
  },
  personalScopeNote: {
    marginTop: spacing.xxs,
  },
  personalScopeNoteText: {
    ...typography.caption,
    fontStyle: 'italic',
    lineHeight: 16,
  },
  textInput: {
    minHeight: 96,
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    textAlignVertical: 'top',
    ...typography.body,
  },
  inputControlsRow: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
  },
  charCount: {
    ...typography.caption,
  },
  clearButtonText: {
    ...typography.caption,
    textDecorationLine: 'underline',
  },
  submitButton: {
    minHeight: layout.minimumTouchTarget,
    borderRadius: radius.md,
    alignItems: 'center',
    justifyContent: 'center',
    paddingHorizontal: spacing.lg,
  },
  submittingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  submitButtonText: {
    ...typography.label,
  },
  examplesSection: {
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  examplesLabel: {
    ...typography.caption,
    fontWeight: '600',
  },
  exampleChip: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
  },
  exampleChipText: {
    ...typography.bodySmall,
  },
  stateCard: {
    borderWidth: 1,
    borderRadius: radius.lg,
    padding: spacing.xl,
    alignItems: 'center',
    gap: spacing.sm,
  },
  stateTitle: {
    ...typography.h3,
    textAlign: 'center',
  },
  stateBody: {
    ...typography.bodySmall,
    textAlign: 'center',
    lineHeight: 20,
  },
  retryQuestionPreview: {
    ...typography.caption,
    fontStyle: 'italic',
    textAlign: 'center',
  },
  errorButtonsRow: {
    flexDirection: 'row',
    flexWrap: 'wrap',
    gap: spacing.sm,
    justifyContent: 'center',
    alignItems: 'center',
    marginTop: spacing.xs,
  },
  retryButton: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.lg,
  },
  retryButtonText: {
    ...typography.label,
  },
  refreshStudentsErrorButton: {
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.md,
  },
  refreshStudentsErrorButtonText: {
    ...typography.caption,
    fontWeight: '600',
  },
  resultContainer: {
    gap: spacing.lg,
  },
  badge: {
    alignSelf: 'flex-start',
    paddingVertical: spacing.xxs,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.full,
  },
  badgeText: {
    ...typography.caption,
    fontWeight: '700',
    letterSpacing: 0.5,
  },
  asOfRow: {
    marginTop: -spacing.sm,
    marginBottom: -spacing.xs,
  },
  asOfText: {
    ...typography.caption,
    fontStyle: 'italic',
  },
  missingDataTitle: {
    ...typography.label,
    fontWeight: '700',
  },
  missingDataHint: {
    ...typography.caption,
    lineHeight: 18,
  },
  sectionTitle: {
    ...typography.h3,
  },
  answeredQuestionText: {
    ...typography.caption,
    fontStyle: 'italic',
  },
  answerText: {
    ...typography.body,
    lineHeight: 24,
  },
  citationCard: {
    borderWidth: 1,
    borderRadius: radius.md,
    padding: spacing.md,
    gap: spacing.xs,
  },
  citationHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    flexWrap: 'wrap',
    gap: spacing.xs,
  },
  citationTag: {
    paddingVertical: 2,
    paddingHorizontal: spacing.xs,
    borderRadius: radius.sm,
  },
  citationTagText: {
    ...typography.caption,
    fontWeight: '600',
  },
  citationSource: {
    ...typography.caption,
  },
  citationHeading: {
    ...typography.bodySmall,
    fontWeight: '600',
  },
  citationPageRange: {
    ...typography.caption,
  },
  excerptBox: {
    borderLeftWidth: 3,
    paddingLeft: spacing.sm,
    paddingVertical: spacing.xs,
    borderRadius: radius.sm,
  },
  excerptText: {
    ...typography.bodySmall,
    fontStyle: 'italic',
    lineHeight: 18,
  },
  bulletItem: {
    ...typography.bodySmall,
    lineHeight: 20,
  },
  subList: {
    gap: spacing.xs,
    marginTop: spacing.xs,
  },
  subListTitle: {
    ...typography.label,
  },
  disclaimerBox: {
    padding: spacing.sm,
  },
  disclaimerText: {
    ...typography.caption,
    fontStyle: 'italic',
    textAlign: 'center',
    lineHeight: 16,
  },
});
