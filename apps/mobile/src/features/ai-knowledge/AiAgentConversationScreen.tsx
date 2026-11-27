import React, { useEffect, useRef, useState } from 'react';
import {
  ActivityIndicator,
  Pressable,
  ScrollView,
  StyleSheet,
  Text,
  TextInput,
  View,
  useColorScheme,
} from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { getSemanticColors } from '@/design-system/tokens/colors';
import { radius } from '@/design-system/tokens/radius';
import { spacing } from '@/design-system/tokens/spacing';
import { typography } from '@/design-system/tokens/typography';
import { useAuth } from '@/features/auth/AuthContext';
import { aiAgentApi } from '@/services/aiAgentApi';
import { AiConversationTurn } from '@/types/aiAgent';

export interface AiAgentConversationScreenProps {
  initialConversationId?: string;
  subjectId?: string;
  actorRole?: 'STUDENT' | 'TRAINER';
  onBack?: () => void;
  onReviewProposal?: (recommendationId: string, proposalType: string) => void;
}

function generateClientUuid(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID();
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0;
    const v = c === 'x' ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}

export function AiAgentConversationScreen(props: AiAgentConversationScreenProps = {}) {
  const { user, activeCapability } = useAuth();
  const scopeKey = `${user?.id ?? 'anon'}:${activeCapability ?? 'none'}:${props.subjectId ?? 'self'}:${props.initialConversationId ?? 'new'}`;
  return <AiAgentConversationInner key={scopeKey} {...props} />;
}

function AiAgentConversationInner({
  initialConversationId,
  subjectId,
  actorRole = 'STUDENT',
  onBack,
  onReviewProposal,
}: AiAgentConversationScreenProps) {
  const isDark = useColorScheme() === 'dark';
  const theme = getSemanticColors(isDark);

  const [conversationId, setConversationId] = useState<string | null>(
    initialConversationId ?? null,
  );
  const [turns, setTurns] = useState<AiConversationTurn[]>([]);
  const [input, setInput] = useState('');
  const [sending, setSending] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loadingHistory, setLoadingHistory] = useState(Boolean(initialConversationId));
  const [historyFailed, setHistoryFailed] = useState(false);
  const [historyAttempt, setHistoryAttempt] = useState(0);
  const [failedRetry, setFailedRetry] = useState<{
    message: string;
    operationKey: string;
    turnId: string;
  } | null>(null);

  const abortRef = useRef<AbortController | null>(null);
  const activeTurnRef = useRef<{ conversationId: string; turnId: string } | null>(null);

  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  useEffect(() => {
    if (!initialConversationId) return;
    const controller = new AbortController();
    void aiAgentApi.getConversation(initialConversationId, controller.signal)
      .then((detail) => {
        if (!controller.signal.aborted) setTurns(detail.turns);
      })
      .catch(() => {
        if (!controller.signal.aborted) {
          setHistoryFailed(true);
          setError('Không thể tải lịch sử hội thoại. Vui lòng thử lại.');
        }
      })
      .finally(() => {
        if (!controller.signal.aborted) setLoadingHistory(false);
      });
    return () => controller.abort();
  }, [initialConversationId, historyAttempt]);

  const sendMessage = async (messageText: string, existingOperationKey?: string, existingTurnId?: string) => {
    const trimmed = messageText.trim();
    if (!trimmed || sending || loadingHistory || historyFailed) return;

    const operationKey = existingOperationKey ?? generateClientUuid();
    const clientTurnId = existingTurnId ?? generateClientUuid();

    setSending(true);
    setError(null);
    setFailedRetry(null);
    const controller = new AbortController();
    abortRef.current = controller;

    try {
      let activeConvId = conversationId;
      if (!activeConvId) {
        const created = await aiAgentApi.createConversation({
          actorRole,
          subjectId,
          title: trimmed.slice(0, 60),
        });
        activeConvId = created.conversationId;
        if (controller.signal.aborted) return;
        setConversationId(activeConvId);
      }
      activeTurnRef.current = { conversationId: activeConvId, turnId: clientTurnId };

      const turn = await aiAgentApi.sendTurn(activeConvId, trimmed, controller.signal, {
        turnId: clientTurnId,
        operationKey,
      });
      if (controller.signal.aborted) return;
      setTurns((prev) => [...prev.filter((item) => item.turnId !== turn.turnId), turn]);
      setInput('');
    } catch (err: unknown) {
      if (err instanceof Error && err.name === 'AbortError') {
        setError('Đã hủy lượt hỏi đáp hiện tại.');
      } else {
        setError('Không thể xử lý yêu cầu lúc này. Vui lòng thử lại.');
        if (controller.signal.aborted) return;
        setFailedRetry({ message: trimmed, operationKey, turnId: clientTurnId });
      }
    } finally {
      abortRef.current = null;
      activeTurnRef.current = null;
      setSending(false);
    }
  };

  const handleSend = () => sendMessage(input);

  const handleRetry = () => {
    if (!failedRetry) return;
    void sendMessage(failedRetry.message, failedRetry.operationKey, failedRetry.turnId);
  };

  const handleCancel = () => {
    abortRef.current?.abort();
    const active = activeTurnRef.current;
    if (active) {
      void aiAgentApi.cancelTurn(active.conversationId, active.turnId).catch(() => undefined);
    }
  };

  return (
    <SafeAreaView style={[styles.container, { backgroundColor: theme.canvas }]}>
      <View style={[styles.header, { borderBottomColor: theme.border }]}>
        <View style={styles.headerRow}>
          {onBack && (
            <Pressable accessibilityRole="button" onPress={onBack}>
              <Text style={[typography.caption, { color: theme.primary }]}>Quay lại</Text>
            </Pressable>
          )}
          <Text style={[typography.h3, { color: theme.textPrimary }]}>
            Trợ lý AI Huấn luyện
          </Text>
        </View>
        <Text style={[typography.caption, { color: theme.textSecondary }]}>
          Tham khảo có nguồn · Không tự áp dụng thay đổi
        </Text>
      </View>

      <ScrollView
        style={styles.messages}
        contentContainerStyle={{ padding: spacing.md, gap: spacing.md }}
      >
        {loadingHistory && <ActivityIndicator accessibilityLabel="Đang tải lịch sử hội thoại" color={theme.primary} />}
        {!loadingHistory && !historyFailed && turns.length === 0 && (
          <Text style={[typography.body, { color: theme.textSecondary }]}>
            Hãy đặt câu hỏi về kỹ thuật tập luyện, tiến độ hoặc lịch tập của bạn.
          </Text>
        )}

        {turns.map((turn) => (
          <View key={turn.turnId} style={{ gap: spacing.xs }}>
            <View style={[styles.userBubble, { backgroundColor: theme.primary }]}>
              <Text style={[typography.body, { color: theme.textOnPrimary }]}>{turn.userMessage}</Text>
            </View>

            <View
              style={[
                styles.assistantBubble,
                { backgroundColor: theme.surface, borderColor: theme.border },
              ]}
            >
              {turn.resultType === 'CLARIFICATION_REQUIRED' && turn.clarificationQuestion ? (
                <Text style={[typography.body, { color: theme.textPrimary }]}>
                  {turn.clarificationQuestion}
                </Text>
              ) : turn.resultType === 'BLOCKED' ? (
                <Text style={[typography.body, { color: theme.dangerText }]}>
                  Yêu cầu bị từ chối theo chính sách an toàn ({turn.reasonCode ?? 'BLOCKED'}).
                </Text>
              ) : turn.resultType === 'INSUFFICIENT_DATA' ? (
                <Text style={[typography.body, { color: theme.textSecondary }]}>
                  Chưa đủ dữ liệu hoặc bằng chứng hợp lệ để kết luận.
                </Text>
              ) : (
                <Text style={[typography.body, { color: theme.textPrimary }]}>
                  {turn.assistantMessage ?? ''}
                </Text>
              )}

              {turn.citations.length > 0 && (
                <View style={styles.citationsBox}>
                  <Text style={[typography.caption, { color: theme.textSecondary }]}>
                    Nguồn tham chiếu:
                  </Text>
                  {turn.citations.map((c, idx) => (
                    <Text
                      key={`${turn.turnId}-cit-${idx}`}
                      style={[typography.caption, { color: theme.textSecondary }]}
                    >
                      • {c.documentId} (v{c.version})
                    </Text>
                  ))}
                </View>
              )}

              {turn.proposalDraft && turn.proposalDraft.recommendationId && (
                <Pressable
                  accessibilityRole="button"
                  onPress={() =>
                    onReviewProposal?.(
                      turn.proposalDraft!.recommendationId!,
                      turn.proposalDraft!.proposalType,
                    )
                  }
                  style={[styles.proposalBtn, { borderColor: theme.primary }]}
                >
                  <Text style={[typography.caption, { color: theme.primary }]}>
                    Xem bản nháp đề xuất ({turn.proposalDraft.status})
                  </Text>
                </Pressable>
              )}
            </View>
          </View>
        ))}

        {sending && (
          <View style={styles.loadingRow}>
            <ActivityIndicator size="small" color={theme.primary} />
            <Text style={[typography.caption, { color: theme.textSecondary }]}>
              Đang kiểm tra dữ liệu và nguồn...
            </Text>
            <Pressable accessibilityRole="button" onPress={handleCancel}>
              <Text style={[typography.caption, { color: theme.dangerText }]}>Hủy</Text>
            </Pressable>
          </View>
        )}

        {error && (
          <View style={styles.errorRow}>
            <Text style={[typography.caption, { color: theme.dangerText }]}>{error}</Text>
            {failedRetry && (
              <Pressable accessibilityRole="button" onPress={handleRetry}>
                <Text style={[typography.caption, { color: theme.primary }]}>Thử lại</Text>
              </Pressable>
            )}
            {historyFailed && (
              <Pressable accessibilityRole="button" onPress={() => {
                setLoadingHistory(true);
                setHistoryFailed(false);
                setError(null);
                setHistoryAttempt((value) => value + 1);
              }}>
                <Text style={[typography.caption, { color: theme.primary }]}>Thử tải lại</Text>
              </Pressable>
            )}
          </View>
        )}
      </ScrollView>

      <View style={[styles.composer, { borderTopColor: theme.border, backgroundColor: theme.surface }]}>
        <TextInput
          value={input}
          onChangeText={setInput}
          placeholder="Nhập câu hỏi..."
          placeholderTextColor={theme.textSecondary}
          style={[styles.input, { color: theme.textPrimary, borderColor: theme.border }]}
          editable={!sending && !loadingHistory && !historyFailed}
        />
        <Pressable
          accessibilityRole="button"
          onPress={handleSend}
          disabled={sending || loadingHistory || historyFailed || !input.trim()}
          style={[
            styles.sendBtn,
            { backgroundColor: sending || !input.trim() ? theme.border : theme.primary },
          ]}
        >
          <Text style={[typography.caption, { color: theme.textOnPrimary }]}>Gửi</Text>
        </Pressable>
      </View>
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  container: { flex: 1 },
  header: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderBottomWidth: 1,
    gap: 2,
  },
  headerRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  messages: { flex: 1 },
  userBubble: {
    alignSelf: 'flex-end',
    padding: spacing.sm,
    borderRadius: radius.md,
    maxWidth: '85%',
  },
  assistantBubble: {
    alignSelf: 'flex-start',
    padding: spacing.sm,
    borderRadius: radius.md,
    borderWidth: 1,
    maxWidth: '92%',
    gap: spacing.xs,
  },
  citationsBox: { marginTop: spacing.xs, gap: 2 },
  proposalBtn: {
    marginTop: spacing.xs,
    paddingVertical: spacing.xs,
    paddingHorizontal: spacing.sm,
    borderRadius: radius.sm,
    borderWidth: 1,
    alignSelf: 'flex-start',
  },
  loadingRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  errorRow: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: spacing.sm,
  },
  composer: {
    flexDirection: 'row',
    alignItems: 'center',
    padding: spacing.sm,
    borderTopWidth: 1,
    gap: spacing.sm,
  },
  input: {
    flex: 1,
    borderWidth: 1,
    borderRadius: radius.md,
    paddingHorizontal: spacing.sm,
    paddingVertical: spacing.xs,
  },
  sendBtn: {
    paddingHorizontal: spacing.md,
    paddingVertical: spacing.sm,
    borderRadius: radius.md,
  },
});
