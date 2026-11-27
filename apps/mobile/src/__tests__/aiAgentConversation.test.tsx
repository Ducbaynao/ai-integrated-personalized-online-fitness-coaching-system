import React from 'react';
import { Text } from 'react-native';
import renderer, { act } from 'react-test-renderer';
import { AiAgentConversationScreen } from '@/features/ai-knowledge/AiAgentConversationScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { aiAgentApi } from '@/services/aiAgentApi';

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('@/services/aiAgentApi', () => ({
  aiAgentApi: {
    createConversation: jest.fn(),
    sendTurn: jest.fn(),
    listConversations: jest.fn(),
    getConversation: jest.fn(),
    cancelTurn: jest.fn(),
  },
}));

describe('AiAgentConversationScreen (AG-M4)', () => {
  beforeEach(() => {
    jest.resetAllMocks();
    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'user-1', capabilities: { hasStudentProfile: true, hasTrainerProfile: false } },
      activeCapability: 'STUDENT',
    });
  });

  it('loads existing conversation history before allowing a new turn', async () => {
    (aiAgentApi.getConversation as jest.Mock).mockResolvedValue({
      turns: [{ turnId: 'historic', userMessage: 'Câu hỏi trước', assistantMessage: 'Lịch sử được tải',
        resultType: 'INFORMATION', citations: [], status: 'SUCCEEDED' }],
    });
    let tree!: renderer.ReactTestRenderer;
    await act(async () => { tree = renderer.create(<AiAgentConversationScreen initialConversationId="saved" />); });
    expect(aiAgentApi.getConversation).toHaveBeenCalledWith('saved', expect.any(AbortSignal));
    expect(tree.root.findAllByType(Text).some((node) => node.props.children === 'Lịch sử được tải')).toBe(true);
    expect(aiAgentApi.createConversation).not.toHaveBeenCalled();
  });

  it('retries history loading after a failure and does not send without history', async () => {
    (aiAgentApi.getConversation as jest.Mock).mockRejectedValueOnce(new Error('offline'))
      .mockResolvedValueOnce({ turns: [] });
    let tree!: renderer.ReactTestRenderer;
    await act(async () => { tree = renderer.create(<AiAgentConversationScreen initialConversationId="saved" />); });
    expect(tree.root.findByProps({ placeholder: 'Nhập câu hỏi...' }).props.editable).toBe(false);
    const retry = tree.root.findAllByProps({ accessibilityRole: 'button' })
      .find((node) => node.findAllByType(Text).some((text) => text.props.children === 'Thử tải lại'))!;
    await act(async () => { retry.props.onPress(); });
    expect(aiAgentApi.getConversation).toHaveBeenCalledTimes(2);
    expect(tree.root.findByProps({ placeholder: 'Nhập câu hỏi...' }).props.editable).toBe(true);
  });

  it('retry preserves operation key, payload and turn ID after a lost response', async () => {
    (aiAgentApi.getConversation as jest.Mock).mockResolvedValue({ turns: [] });
    (aiAgentApi.sendTurn as jest.Mock).mockRejectedValueOnce(new Error('lost response'))
      .mockResolvedValueOnce({ turnId: 'retry-turn', userMessage: 'Câu hỏi retry', assistantMessage: 'Đã phục hồi',
        resultType: 'INFORMATION', citations: [], status: 'SUCCEEDED' });
    let tree!: renderer.ReactTestRenderer;
    await act(async () => { tree = renderer.create(<AiAgentConversationScreen initialConversationId="saved" />); });
    await act(async () => { tree.root.findByProps({ placeholder: 'Nhập câu hỏi...' }).props.onChangeText('Câu hỏi retry'); });
    const button = (label: string) => tree.root.findAllByProps({ accessibilityRole: 'button' })
      .find((node) => node.findAllByType(Text).some((text) => text.props.children === label))!;
    await act(async () => { await button('Gửi').props.onPress(); });
    await act(async () => { button('Thử lại').props.onPress(); });
    const [first, second] = (aiAgentApi.sendTurn as jest.Mock).mock.calls;
    expect(second[0]).toBe(first[0]);
    expect(second[1]).toBe(first[1]);
    expect(second[3]).toEqual(first[3]);
    expect(tree.root.findAllByType(Text).some((node) => node.props.children === 'Đã phục hồi')).toBe(true);
  });

  it('creates conversation, sends turn, and renders verified citations without apply action', async () => {
    (aiAgentApi.createConversation as jest.Mock).mockResolvedValue({
      conversationId: 'conv-1',
      actorRole: 'STUDENT',
      subjectId: 'stu-1',
      title: 'Hỏi đáp',
      status: 'ACTIVE',
      updatedAt: '2026-11-26T00:00:00Z',
    });
    (aiAgentApi.sendTurn as jest.Mock).mockResolvedValue({
      turnId: 'turn-1',
      turnIndex: 1,
      aiRunId: 'run-1',
      userMessage: 'WHO khuyến nghị vận động thế nào?',
      assistantMessage: 'Người trưởng thành nên tập 150-300 phút mỗi tuần.',
      clarificationQuestion: null,
      resultType: 'INFORMATION',
      reasonCode: 'NONE',
      citations: [
        {
          documentId: 'WHO-PA-SB-2020-VI-01',
          version: '1.1.0',
          sourceId: 'WHO-PA-SB-2020',
          locator: { sourcePath: 'who.md', heading: 'Adults', sectionIndex: 0 },
          quoteOrExcerpt: '150-300 minutes',
        },
      ],
      proposalDraft: null,
      status: 'SUCCEEDED',
      createdAt: '2026-11-26T00:00:01Z',
    });

    let tree!: renderer.ReactTestRenderer;
    await act(async () => {
      tree = renderer.create(<AiAgentConversationScreen />);
    });

    const input = tree.root.findByProps({ placeholder: 'Nhập câu hỏi...' });
    await act(async () => {
      input.props.onChangeText('WHO khuyến nghị vận động thế nào?');
    });

    const sendBtn = tree.root.findAllByProps({ accessibilityRole: 'button' })[0];
    await act(async () => {
      await sendBtn.props.onPress();
    });

    const allTexts = tree.root.findAllByType(Text).map((t) =>
      Array.isArray(t.props.children) ? t.props.children.join('') : String(t.props.children),
    );
    expect(allTexts).toContain('Người trưởng thành nên tập 150-300 phút mỗi tuần.');
    expect(allTexts).toContain('• WHO-PA-SB-2020-VI-01 (v1.1.0)');
  });

  it('calls server cancelTurn when cancelling an in-flight request', async () => {
    (aiAgentApi.createConversation as jest.Mock).mockResolvedValue({
      conversationId: 'conv-2',
      actorRole: 'STUDENT',
      subjectId: 'stu-1',
      title: 'Hỏi đáp',
      status: 'ACTIVE',
      updatedAt: '2026-11-26T00:00:00Z',
    });
    let rejectSend!: (err: Error) => void;
    (aiAgentApi.sendTurn as jest.Mock).mockImplementation(
      () =>
        new Promise((_resolve, reject) => {
          rejectSend = reject;
        }),
    );
    (aiAgentApi.cancelTurn as jest.Mock).mockResolvedValue({});

    let tree!: renderer.ReactTestRenderer;
    await act(async () => {
      tree = renderer.create(<AiAgentConversationScreen />);
    });

    const input = tree.root.findByProps({ placeholder: 'Nhập câu hỏi...' });
    await act(async () => {
      input.props.onChangeText('Hỏi kỹ thuật squat');
    });

    const sendBtn = tree.root.findAllByProps({ accessibilityRole: 'button' })[0];
    await act(async () => {
      void sendBtn.props.onPress();
    });

    const cancelBtn = tree.root
      .findAllByProps({ accessibilityRole: 'button' })
      .find((b) => b.findAllByType(Text).some((t) => t.props.children === 'Hủy'));
    expect(cancelBtn).toBeTruthy();

    await act(async () => {
      cancelBtn!.props.onPress();
      const abortErr = new Error('Aborted');
      abortErr.name = 'AbortError';
      rejectSend(abortErr);
    });

    expect(aiAgentApi.cancelTurn).toHaveBeenCalledWith('conv-2', expect.any(String));
  });

  it('clears local turns when capability or user switches', async () => {
    (aiAgentApi.createConversation as jest.Mock).mockResolvedValue({
      conversationId: 'conv-3',
      actorRole: 'STUDENT',
      subjectId: 'stu-1',
      title: 'Hỏi đáp',
      status: 'ACTIVE',
      updatedAt: '2026-11-26T00:00:00Z',
    });
    (aiAgentApi.sendTurn as jest.Mock).mockResolvedValue({
      turnId: 'turn-3',
      turnIndex: 1,
      aiRunId: 'run-3',
      userMessage: 'Câu hỏi riêng',
      assistantMessage: 'Trả lời riêng',
      clarificationQuestion: null,
      resultType: 'INFORMATION',
      reasonCode: 'NONE',
      citations: [],
      proposalDraft: null,
      status: 'SUCCEEDED',
      createdAt: '2026-11-26T00:00:01Z',
    });

    let tree!: renderer.ReactTestRenderer;
    await act(async () => {
      tree = renderer.create(<AiAgentConversationScreen />);
    });

    const input = tree.root.findByProps({ placeholder: 'Nhập câu hỏi...' });
    await act(async () => {
      input.props.onChangeText('Câu hỏi riêng');
    });
    await act(async () => {
      await tree.root.findAllByProps({ accessibilityRole: 'button' })[0].props.onPress();
    });

    (useAuth as jest.Mock).mockReturnValue({
      user: { id: 'user-2', capabilities: { hasStudentProfile: false, hasTrainerProfile: true } },
      activeCapability: 'TRAINER',
    });
    await act(async () => {
      tree.update(<AiAgentConversationScreen />);
    });

    const allTexts = tree.root.findAllByType(Text).map((t) =>
      Array.isArray(t.props.children) ? t.props.children.join('') : String(t.props.children),
    );
    expect(allTexts).not.toContain('Trả lời riêng');
  });
});
