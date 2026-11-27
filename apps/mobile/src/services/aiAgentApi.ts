import { request } from '@/services/apiClient';
import {
  AiConversationDetail,
  AiConversationSummary,
  AiConversationTurn,
} from '@/types/aiAgent';

export const aiAgentApi = {
  createConversation(params?: {
    actorRole?: 'STUDENT' | 'TRAINER' | 'ADMIN';
    subjectId?: string;
    title?: string;
  }): Promise<AiConversationSummary> {
    return request<AiConversationSummary>('/api/v1/ai/conversations', {
      method: 'POST',
      body: JSON.stringify(params ?? {}),
    });
  },

  listConversations(): Promise<AiConversationSummary[]> {
    return request<AiConversationSummary[]>('/api/v1/ai/conversations', {
      method: 'GET',
    });
  },

  getConversation(conversationId: string, signal?: AbortSignal): Promise<AiConversationDetail> {
    return request<AiConversationDetail>(`/api/v1/ai/conversations/${conversationId}`, {
      method: 'GET',
      signal,
    });
  },

  sendTurn(
    conversationId: string,
    message: string,
    signal?: AbortSignal,
    options?: { turnId?: string; operationKey?: string },
  ): Promise<AiConversationTurn> {
    return request<AiConversationTurn>(`/api/v1/ai/conversations/${conversationId}/turns`, {
      method: 'POST',
      body: JSON.stringify({
        message,
        turnId: options?.turnId,
        operationKey: options?.operationKey,
      }),
      signal,
    });
  },

  cancelTurn(conversationId: string, turnId: string): Promise<AiConversationTurn> {
    return request<AiConversationTurn>(
      `/api/v1/ai/conversations/${conversationId}/turns/${turnId}/cancel`,
      { method: 'POST' },
    );
  },
};
