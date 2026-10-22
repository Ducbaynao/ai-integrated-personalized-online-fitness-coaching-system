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
  F01KnowledgeQueryResponse,
  KnowledgeCitation,
} from '@/types/aiKnowledge';

/**
 * Example questions aligned with the ACTIVE knowledge corpus (WHO Physical Activity Guidelines).
 */
const EXAMPLE_QUESTIONS = [
  'WHO khuyến nghị thời gian hoạt động thể chất hàng tuần cho người lớn là bao nhiêu?',
  'Khuyến nghị tập tăng cường cơ bắp bao nhiêu ngày mỗi tuần?',
  'Người cao tuổi nên tập luyện thăng bằng và sức mạnh như thế nào?',
];

export function AiKnowledgeScreen() {
  const router = useRouter();
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);

  const [question, setQuestion] = useState('');
  const [lastSubmittedQuestion, setLastSubmittedQuestion] = useState('');
  const [resultQuestion, setResultQuestion] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [errorMessage, setErrorMessage] = useState<string | null>(null);
  const [result, setResult] = useState<F01KnowledgeQueryResponse | null>(null);

  // In-flight guard to prevent duplicate submission
  const isSubmittingRef = useRef(false);
  // Unmounted component guard
  const isMountedRef = useRef(true);

  useEffect(() => {
    isMountedRef.current = true;
    return () => {
      isMountedRef.current = false;
    };
  }, []);

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

      isSubmittingRef.current = true;
      setIsSubmitting(true);
      setErrorMessage(null);
      setResult(null); // Clear previous result immediately
      setLastSubmittedQuestion(q);

      try {
        const response = await aiKnowledgeApi.queryKnowledge({
          question: q,
          language: 'vi',
        });

        if (!isMountedRef.current) return;
        setResult(response);
        setResultQuestion(q);
      } catch (err: unknown) {
        if (!isMountedRef.current) return;
        const message = getAiKnowledgeErrorMessage(err);
        setErrorMessage(message);
        setResult(null);
        setResultQuestion('');
      } finally {
        if (isMountedRef.current) {
          isSubmittingRef.current = false;
          setIsSubmitting(false);
        }
      }
    },
    [question]
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
              onPress={() => router.back()}
              style={[styles.backButton, { borderColor: theme.border }]}>
              <Text style={[styles.backButtonText, { color: theme.primary }]}>← Quay lại</Text>
            </Pressable>
            <Text style={[styles.title, { color: theme.textPrimary }]}>
              Kiến thức thể hình (AI)
            </Text>
            <Text style={[styles.subtitle, { color: theme.textSecondary }]}>
              Tra cứu nguyên tắc tập luyện dựa trên các tài liệu đã qua kiểm định (WHO, ISSN). Câu
              trả lời do AI tổng hợp từ nguồn trích dẫn, mang tính tham khảo và không thay thế tư vấn y khoa.
            </Text>
          </View>

          {/* Question Input Card */}
          <View style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
            <Text style={[styles.cardTitle, { color: theme.textPrimary }]}>Đặt câu hỏi</Text>

            <TextInput
              testID="ai-knowledge-question-input"
              accessibilityLabel="Nhập câu hỏi kiến thức thể hình"
              multiline
              numberOfLines={4}
              maxLength={F01_QUESTION_MAX_LENGTH}
              placeholder="Ví dụ: WHO khuyến nghị thời gian hoạt động thể chất hàng tuần là bao nhiêu?"
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
                  Câu hỏi gợi ý từ kho tri thức hiện hành:
                </Text>
                {EXAMPLE_QUESTIONS.map((example, idx) => (
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
                Hệ thống đang truy xuất tài liệu chuyên môn từ kho tri thức và xác thực độ chính xác trích dẫn.
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
                      THÔNG TIN THAM KHẢO TỪ NGUỒN ĐÃ KIỂM ĐỊNH
                    </Text>
                  </View>

                  {/* Answer Text */}
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

                  {/* Citations */}
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

                  {/* Limitations */}
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

                  {/* Missing Data */}
                  {result.missingData && result.missingData.length > 0 && (
                    <View
                      testID="ai-knowledge-missing-data-card"
                      style={[styles.card, { backgroundColor: theme.surface, borderColor: theme.border }]}>
                      <Text style={[styles.sectionTitle, { color: theme.textPrimary }]}>
                        Thông tin chưa xác định
                      </Text>
                      {result.missingData.map((item, idx) => (
                        <Text
                          key={idx}
                          testID={`ai-knowledge-missing-item-${idx}`}
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

              {/* Session Disclaimer */}
              <View style={styles.disclaimerBox}>
                <Text style={[styles.disclaimerText, { color: theme.textSecondary }]}>
                  Lưu ý: Kết quả trên là thông tin tham khảo chuyên môn (không tạo kế hoạch hoặc thay
                  đổi mục tiêu cá nhân). Kết quả được lưu tạm trong phiên làm việc hiện tại và không
                  được khôi phục sau khi rời màn hình.
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
  retryButton: {
    marginTop: spacing.sm,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.lg,
  },
  retryButtonText: {
    ...typography.label,
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
