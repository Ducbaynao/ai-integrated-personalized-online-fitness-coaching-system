import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ChatInboxScreen, ConversationScreen, NotificationsScreen } from '@/features/chat/ChatScreens';
import { useAuth } from '@/features/auth/AuthContext';
import { chatApi } from '@/services/chatApi';
import { ApiError } from '@/types/auth';
import * as DocumentPicker from 'expo-document-picker';

const mockPush = jest.fn();
const mockDeletePicked = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush, replace: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/chat/useChatUpdates', () => ({ useChatUpdates: jest.fn() }));
jest.mock('expo-document-picker', () => ({ getDocumentAsync: jest.fn() }));
jest.mock('expo-file-system', () => ({ Paths: { cache: {} }, File: jest.fn().mockImplementation(() => ({ delete: mockDeletePicked })) }));
jest.mock('expo-sharing', () => ({ isAvailableAsync: jest.fn(), shareAsync: jest.fn() }));
jest.mock('@/services/chatApi', () => ({ chatApi: {
  conversations: jest.fn(), conversation: jest.fn(), messages: jest.fn(), send: jest.fn(),
  sendAttachment: jest.fn(), attachmentContent: jest.fn(),
  receipt: jest.fn(), notifications: jest.fn(), readNotification: jest.fn(),
} }));

const clients: QueryClient[] = [];
const trees: renderer.ReactTestRenderer[] = [];
function render(element: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: {
    queries: { retry: false, gcTime: 0, refetchInterval: false },
    mutations: { retry: false, gcTime: 0 },
  } });
  clients.push(client);
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<QueryClientProvider client={client}>{element}</QueryClientProvider>); });
  trees.push(tree);
  return tree;
}
async function settle() {
  await renderer.act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
}
const conversation = {
  id: 'conversation-1', coachingPeriodId: 'period-2', studentId: 'student-1', trainerId: 'trainer-1',
  createdAt: '2026-10-17T07:00:00Z', updatedAt: '2026-10-17T08:00:00Z', sendAllowed: true,
};

describe('B08 chat screens', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
    (chatApi.conversations as jest.Mock).mockResolvedValue({ items: [conversation], page: 0, size: 20, hasMore: false });
    (chatApi.conversation as jest.Mock).mockResolvedValue(conversation);
    (chatApi.messages as jest.Mock).mockResolvedValue({ items: [], page: 0, size: 30, hasMore: false });
    (chatApi.receipt as jest.Mock).mockResolvedValue({});
    (chatApi.notifications as jest.Mock).mockResolvedValue({ items: [], page: 0, size: 20, hasMore: false });
  });
  afterEach(() => {
    renderer.act(() => { trees.splice(0).forEach(tree => tree.unmount()); });
    clients.splice(0).forEach(client => client.clear());
  });

  it('lists period conversations and navigates by actual conversation identity', async () => {
    const tree = render(<ChatInboxScreen />);
    await settle();
    const open = tree.root.find(node => typeof node.props.accessibilityLabel === 'string'
      && node.props.accessibilityLabel.startsWith('Mở hội thoại'));
    renderer.act(() => open.props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/chat/conversation-1');
  });

  it('does not mark a received message READ merely by rendering it', async () => {
    (chatApi.messages as jest.Mock).mockResolvedValue({ items: [{ id: 'message-1', conversationId: 'conversation-1',
      senderId: 'trainer-1', body: 'Xin chào', status: 'SENT', sentAt: '2026-10-17T08:00:00Z',
      deliveredAt: null, readAt: null }], page: 0, size: 30, hasMore: false });
    const tree = render(<ConversationScreen conversationId="conversation-1" />);
    await settle();
    expect(tree.root.findByProps({ accessibilityLabel: 'Đánh dấu đã đọc' })).toBeTruthy();
    expect(chatApi.receipt).toHaveBeenCalledWith('message-1', 'DELIVERED');
    expect(chatApi.receipt).not.toHaveBeenCalledWith('message-1', 'READ');
  });

  it('keeps an old period readable without offering a send action', async () => {
    (chatApi.conversation as jest.Mock).mockResolvedValue({ ...conversation, sendAllowed: false });
    const tree = render(<ConversationScreen conversationId="conversation-1" />);
    await settle();
    expect(tree.root.findAll(node => node.props.children === 'Hội thoại chỉ đọc').length).toBeGreaterThan(0);
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Gửi tin nhắn' })).toHaveLength(0);
  });

  it('retries an uncertain network send with the same body and command key', async () => {
    (chatApi.send as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'offline')).mockResolvedValueOnce({
      id: 'message-2', conversationId: 'conversation-2', senderId: 'student-1', body: 'Xin chào',
      status: 'SENT', sentAt: '2026-10-17T08:00:00Z', deliveredAt: null, readAt: null,
    });
    const tree = render(<ConversationScreen studentId="student-1" />);
    renderer.act(() => tree.root.findByProps({ testID: 'chat-draft' }).props.onChangeText('Xin chào'));
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Gửi tin nhắn' }).props.onPress());
    await settle();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử gửi lại' }).props.onPress());
    await settle();
    expect(chatApi.send).toHaveBeenCalledTimes(2);
    expect((chatApi.send as jest.Mock).mock.calls[0]).toEqual((chatApi.send as jest.Mock).mock.calls[1]);
  });

  it('picks a supported attachment and retries uncertain upload with the same file and command key', async () => {
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValue({ canceled: false, assets: [{
      uri: 'file:///private/cache/DocumentPicker/photo.png', name: 'photo.png', mimeType: 'image/jpg', size: 1024,
    }] });
    (chatApi.sendAttachment as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'offline'))
      .mockResolvedValueOnce({ id: 'message-file', conversationId: 'conversation-1' });
    const tree = render(<ConversationScreen studentId="student-1" />);
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Chọn JPEG, PNG hoặc PDF' }).props.onPress();
      await Promise.resolve();
    });
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Gửi tệp đính kèm' }).props.onPress());
    await settle();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Thử gửi lại tệp' }).props.onPress());
    await settle();
    expect(chatApi.sendAttachment).toHaveBeenCalledTimes(2);
    expect((chatApi.sendAttachment as jest.Mock).mock.calls[0]).toEqual((chatApi.sendAttachment as jest.Mock).mock.calls[1]);
    expect((chatApi.sendAttachment as jest.Mock).mock.calls[0][1].mimeType).toBe('image/jpeg');
    expect(mockDeletePicked).toHaveBeenCalledTimes(1);
  });

  it('conceals inaccessible conversation content and send action', async () => {
    (chatApi.conversation as jest.Mock).mockRejectedValue(new ApiError(404, 'hidden'));
    const tree = render(<ConversationScreen conversationId="old-period" />);
    await settle();
    expect(tree.root.findAllByProps({ accessibilityLabel: 'Gửi tin nhắn' })).toHaveLength(0);
    expect(tree.root.findAll(node => node.props.children === 'Không còn quyền xem nội dung này.').length)
      .toBeGreaterThan(0);
  });

  it('shows generic in-app notification without message body', async () => {
    (chatApi.notifications as jest.Mock).mockResolvedValue({ items: [{ id: 'notice-1', eventType: 'CHAT_MESSAGE',
      title: 'Tin nhắn mới', body: 'Bạn có tin nhắn mới', messageId: 'message-1', coachingPeriodId: 'period-2',
      createdAt: '2026-10-17T08:00:00Z', readAt: null }], page: 0, size: 20, hasMore: false });
    const tree = render(<NotificationsScreen />);
    await settle();
    expect(tree.root.findAll(node => node.props.children === 'Bạn có tin nhắn mới').length).toBeGreaterThan(0);
    expect(chatApi.readNotification).not.toHaveBeenCalled();
  });

  it('opens Attention through an authorized read surface and does not mark a notice READ on GET', async () => {
    (chatApi.notifications as jest.Mock).mockResolvedValue({ items: [{
      id: 'notice-return', eventType: 'ATTENTION_DETECTED',
      title: 'Thông báo mới', body: 'Bạn có cập nhật mới',
      messageId: null, relatedEntityType: 'WORKOUT_ATTENTION_SIGNAL', relatedEntityId: 'signal-1',
      coachingPeriodId: null, createdAt: '2026-10-23T08:00:00Z', readAt: null,
    }], page: 0, size: 20, hasMore: false });
    const tree = render(<NotificationsScreen />);
    await settle();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Xem tín hiệu và đánh giá' }).props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/attention');
    expect(chatApi.readNotification).not.toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).not.toContain('signal-1');
  });
});
