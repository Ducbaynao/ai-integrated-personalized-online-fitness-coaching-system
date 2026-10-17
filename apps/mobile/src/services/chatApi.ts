import { request } from '@/services/apiClient';

export type ChatPage<T> = { items: T[]; page: number; size: number; hasMore: boolean };
export type ChatConversation = {
  id: string; coachingPeriodId: string; studentId: string; trainerId: string;
  createdAt: string; updatedAt: string; sendAllowed: boolean;
};
export type ChatMessage = {
  id: string; conversationId: string; senderId: string; body: string;
  status: 'SENT' | 'DELIVERED' | 'READ'; sentAt: string;
  deliveredAt: string | null; readAt: string | null;
};
export type ChatNotification = {
  id: string; eventType: 'CHAT_MESSAGE'; title: string; body: string;
  messageId: string; coachingPeriodId: string; createdAt: string; readAt: string | null;
};
export type SendChatBody = { body: string; commandKey: string };

export const chatApi = {
  conversations(page = 0) {
    return request<ChatPage<ChatConversation>>(`/chat/conversations?page=${page}&size=20`);
  },
  conversation(id: string) {
    return request<ChatConversation>(`/chat/conversations/${encodeURIComponent(id)}`);
  },
  messages(id: string, page = 0) {
    return request<ChatPage<ChatMessage>>(`/chat/conversations/${encodeURIComponent(id)}/messages?page=${page}&size=30`);
  },
  send(studentId: string, body: SendChatBody) {
    return request<ChatMessage>(`/chat/students/${encodeURIComponent(studentId)}/messages`, { method: 'POST', body });
  },
  receipt(messageId: string, kind: 'DELIVERED' | 'READ') {
    return request<ChatMessage>(`/chat/messages/${encodeURIComponent(messageId)}/receipts`, {
      method: 'POST', body: { kind },
    });
  },
  notifications(page = 0) {
    return request<ChatPage<ChatNotification>>(`/notifications/me?page=${page}&size=20`);
  },
  readNotification(id: string) {
    return request<ChatNotification>(`/notifications/me/${encodeURIComponent(id)}/read`, { method: 'POST' });
  },
};
