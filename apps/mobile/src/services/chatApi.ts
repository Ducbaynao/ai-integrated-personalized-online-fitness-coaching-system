import { downloadAuthenticated, request } from '@/services/apiClient';

export type ChatPage<T> = { items: T[]; page: number; size: number; hasMore: boolean };
export type ChatConversation = {
  id: string; coachingPeriodId: string; studentId: string; trainerId: string;
  createdAt: string; updatedAt: string; sendAllowed: boolean;
};
export type ChatMessage = {
  id: string; conversationId: string; senderId: string; body: string;
  status: 'SENT' | 'DELIVERED' | 'READ'; sentAt: string;
  deliveredAt: string | null; readAt: string | null;
  attachmentId: string | null; attachmentName: string | null;
  attachmentContentType: string | null; attachmentSize: number | null;
};
export type ChatNotification = {
  id: string; eventType: string; title: string; body: string;
  messageId: string | null; relatedEntityType: string | null; relatedEntityId: string | null;
  coachingPeriodId: string | null; createdAt: string; readAt: string | null;
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
  async sendAttachment(studentId: string, file: { uri: string; name: string; mimeType: string; size: number }, commandKey: string) {
    const body = new FormData();
    body.append('commandKey', commandKey);
    // Expo's fetch converts Blob/File bytes, but rejects React Native's legacy { uri } form part.
    const localResponse = await fetch(file.uri);
    if (!localResponse.ok) throw new Error('Cannot read selected attachment');
    const localBlob = await localResponse.blob();
    body.append('file', localBlob.slice(0, localBlob.size, file.mimeType), file.name);
    return request<ChatMessage>(`/chat/students/${encodeURIComponent(studentId)}/attachments`, { method: 'POST', body });
  },
  attachmentContent(id: string) {
    return downloadAuthenticated(`/chat/attachments/${encodeURIComponent(id)}/content`);
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
