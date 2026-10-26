import { chatApi } from '@/services/chatApi';
import { downloadAuthenticated, request } from '@/services/apiClient';

jest.mock('@/services/apiClient', () => ({ request: jest.fn(), downloadAuthenticated: jest.fn() }));

describe('B08 chat and in-app notification API', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

  it('uses separate authorized conversation, message, and inbox reads with page coordinates', async () => {
    await chatApi.conversations(2);
    await chatApi.conversation('period-conversation');
    await chatApi.messages('period-conversation', 3);
    await chatApi.notifications(4);
    expect(request).toHaveBeenNthCalledWith(1, '/chat/conversations?page=2&size=20');
    expect(request).toHaveBeenNthCalledWith(2, '/chat/conversations/period-conversation');
    expect(request).toHaveBeenNthCalledWith(3, '/chat/conversations/period-conversation/messages?page=3&size=30');
    expect(request).toHaveBeenNthCalledWith(4, '/notifications/me?page=4&size=20');
  });

  it('preserves the caller supplied command key and body on send retry', async () => {
    const body = { body: 'Xin chào', commandKey: 'same-command-key' };
    await chatApi.send('student-1', body);
    await chatApi.send('student-1', body);
    expect(request).toHaveBeenNthCalledWith(1, '/chat/students/student-1/messages', { method: 'POST', body });
    expect(request).toHaveBeenNthCalledWith(2, '/chat/students/student-1/messages', { method: 'POST', body });
  });

  it('uses explicit recipient receipt and notification read commands only', async () => {
    await chatApi.receipt('message-1', 'DELIVERED');
    await chatApi.receipt('message-1', 'READ');
    await chatApi.readNotification('notice-1');
    expect(request).toHaveBeenNthCalledWith(1, '/chat/messages/message-1/receipts', { method: 'POST', body: { kind: 'DELIVERED' } });
    expect(request).toHaveBeenNthCalledWith(2, '/chat/messages/message-1/receipts', { method: 'POST', body: { kind: 'READ' } });
    expect(request).toHaveBeenNthCalledWith(3, '/notifications/me/notice-1/read', { method: 'POST' });
  });

  it('serializes a real multipart file and stable command key without JSON wrapping', async () => {
    const file = { uri: 'file:///picked.pdf', name: 'plan.pdf', mimeType: 'application/pdf', size: 1024 };
    const localBlob = new Blob(['%PDF-1.7']);
    const localFetch = jest.spyOn(globalThis, 'fetch').mockResolvedValue({ ok: true,
      blob: async () => localBlob } as Response);
    const append = jest.spyOn(FormData.prototype, 'append');
    await chatApi.sendAttachment('student-1', file, 'same-key');
    const [path, options] = (request as jest.Mock).mock.calls[0];
    expect(path).toBe('/chat/students/student-1/attachments');
    expect(options.method).toBe('POST');
    expect(options.body).toBeInstanceOf(FormData);
    expect(append).toHaveBeenCalledWith('commandKey', 'same-key');
    expect(append).toHaveBeenCalledWith('file', expect.any(Blob), file.name);
    expect((append.mock.calls.find(call => call[0] === 'file')?.[1] as Blob).type).toBe(file.mimeType);
    expect(localFetch).toHaveBeenCalledWith(file.uri);
    append.mockRestore();
    localFetch.mockRestore();
    await chatApi.attachmentContent('attachment-1');
    expect(downloadAuthenticated).toHaveBeenCalledWith('/chat/attachments/attachment-1/content');
  });
});
