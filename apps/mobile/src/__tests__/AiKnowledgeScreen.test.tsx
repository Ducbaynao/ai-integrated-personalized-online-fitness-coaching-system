import React from 'react';
import renderer from 'react-test-renderer';
import { AiKnowledgeScreen } from '@/features/ai-knowledge/AiKnowledgeScreen';
import { aiKnowledgeApi } from '@/services/aiKnowledgeApi';
import { ApiError } from '@/types/auth';
import { F01KnowledgeQueryResponse } from '@/types/aiKnowledge';

const mockBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ back: mockBack }),
}));

jest.mock('@/services/aiKnowledgeApi', () => {
  const actual = jest.requireActual('@/services/aiKnowledgeApi');
  return {
    ...actual,
    aiKnowledgeApi: {
      queryKnowledge: jest.fn(),
    },
  };
});

describe('AiKnowledgeScreen', () => {
  const sampleSuccessResponse: F01KnowledgeQueryResponse = {
    runId: 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa',
    correlationId: 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    runStatus: 'SUCCEEDED',
    resultType: 'INFORMATION',
    reasonCode: 'NONE',
    answer:
      'Người trưởng thành nên thực hiện 150-300 phút hoạt động thể lực vừa phải hoặc 75-150 phút cường độ mạnh mỗi tuần.',
    citations: [
      {
        documentId: 'WHO-PA-SB-2020-VI-01',
        version: '1.0.0',
        sourceId: 'WHO-PA-SB-2020',
        locator: {
          sourcePath: 'who-pa-sb-2020/01-adults.vi.md',
          heading: 'Khuyến nghị cho người trưởng thành',
          pageRange: [12, 16],
        },
        quoteOrExcerpt: 'Ít nhất 150-300 phút hoạt động thể lực cường độ vừa',
      },
    ],
    limitations: ['Áp dụng cho người lớn từ 18-64 tuổi không có chống chỉ định y khoa'],
    missingData: ['Tình trạng chấn thương hoặc bệnh nền nếu có'],
    asOf: '2026-10-22T10:00:00Z',
    usage: {
      promptTokens: 200,
      completionTokens: 80,
      totalTokens: 280,
      latencyMs: 1500,
    },
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  const renderScreen = () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<AiKnowledgeScreen />);
    });
    return tree;
  };

  const settle = async () => {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
  };

  it('renders initial state with empty input, example chips, and disabled submit button', () => {
    const tree = renderScreen();

    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    expect(input.props.value).toBe('');

    const charCount = tree.root.findByProps({ testID: 'ai-knowledge-char-count' });
    expect(charCount.props.children).toEqual([0, '/', 1000]);

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    expect(submitBtn.props.accessibilityState.disabled).toBe(true);

    const exampleChip = tree.root.findByProps({ testID: 'ai-knowledge-example-0' });
    expect(exampleChip).toBeDefined();
  });

  it('navigates back when back button is pressed', () => {
    const tree = renderScreen();
    const backBtn = tree.root.findByProps({ testID: 'ai-knowledge-back-button' });

    renderer.act(() => {
      backBtn.props.onPress();
    });

    expect(mockBack).toHaveBeenCalledTimes(1);
  });

  it('populates input when an example chip is pressed and enables submit button', () => {
    const tree = renderScreen();
    const exampleChip = tree.root.findByProps({ testID: 'ai-knowledge-example-0' });

    renderer.act(() => {
      exampleChip.props.onPress();
    });

    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    expect(input.props.value.length).toBeGreaterThanOrEqual(5);

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    expect(submitBtn.props.accessibilityState.disabled).toBe(false);
  });

  it('clearing input resets text, error message, and previous result state', async () => {
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });

    renderer.act(() => {
      input.props.onChangeText('Câu hỏi cần xóa');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    expect(tree.root.findByProps({ testID: 'ai-knowledge-answer-text' })).toBeDefined();

    // Now clear
    const clearBtn = tree.root.findByProps({ testID: 'ai-knowledge-clear-button' });
    renderer.act(() => {
      clearBtn.props.onPress();
    });

    expect(tree.root.findByProps({ testID: 'ai-knowledge-question-input' }).props.value).toBe('');
    expect(tree.root.findAllByProps({ testID: 'ai-knowledge-answer-text' })).toHaveLength(0);
  });

  it('submits question and renders INFORMATION answer, citations, limitations, and missingData', async () => {
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });

    renderer.act(() => {
      input.props.onChangeText('Thời gian tập luyện khuyến nghị của WHO là bao nhiêu?');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });

    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenCalledWith({
      question: 'Thời gian tập luyện khuyến nghị của WHO là bao nhiêu?',
      language: 'vi',
    });

    // Check Answer Text
    const answerText = tree.root.findByProps({ testID: 'ai-knowledge-answer-text' });
    expect(answerText.props.children).toContain('150-300 phút hoạt động thể lực vừa phải');

    // Check Citation
    const citation = tree.root.findByProps({ testID: 'ai-knowledge-citation-0' });
    expect(citation).toBeDefined();

    // Check Limitations
    const limitation = tree.root.findByProps({ testID: 'ai-knowledge-limitation-0' });
    expect(limitation.props.children[1]).toContain('Áp dụng cho người lớn từ 18-64 tuổi');

    // Check Missing Data
    const missingItem = tree.root.findByProps({ testID: 'ai-knowledge-missing-item-0' });
    expect(missingItem.props.children[1]).toContain('Tình trạng chấn thương hoặc bệnh nền nếu có');
  });

  it('prevents duplicate submission while a request is in flight', async () => {
    let resolvePromise: (value: F01KnowledgeQueryResponse) => void;
    const pendingPromise = new Promise<F01KnowledgeQueryResponse>((resolve) => {
      resolvePromise = resolve;
    });

    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockReturnValue(pendingPromise);

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });

    renderer.act(() => {
      input.props.onChangeText('Tập tạ có giúp tăng mật độ xương không?');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });

    // First tap
    renderer.act(() => {
      submitBtn.props.onPress();
    });

    // Second tap immediately while in flight
    renderer.act(() => {
      submitBtn.props.onPress();
    });

    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenCalledTimes(1);

    // Resolve the promise
    await renderer.act(async () => {
      resolvePromise!(sampleSuccessResponse);
    });
    await settle();

    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenCalledTimes(1);
  });

  it('renders INSUFFICIENT_DATA result state correctly', async () => {
    const insufficientResponse: F01KnowledgeQueryResponse = {
      runId: 'cccccccc-cccc-cccc-cccc-cccccccccccc',
      runStatus: 'SUCCEEDED',
      resultType: 'INSUFFICIENT_DATA',
      reasonCode: 'NO_RELEVANT_EVIDENCE',
      answer: 'Kho tri thức hiện hành chưa có tài liệu về vấn đề này.',
      citations: [],
      missingData: ['Tài liệu chuyên môn về kỹ thuật được yêu cầu'],
      asOf: '2026-10-22T10:00:00Z',
    };

    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(insufficientResponse);

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });

    renderer.act(() => {
      input.props.onChangeText('Một câu hỏi chưa có trong tài liệu');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });

    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    const insufficientCard = tree.root.findByProps({ testID: 'ai-knowledge-insufficient-card' });
    expect(insufficientCard).toBeDefined();
  });

  it('renders BLOCKED result state correctly', async () => {
    const blockedResponse: F01KnowledgeQueryResponse = {
      runId: 'dddddddd-dddd-dddd-dddd-dddddddddddd',
      runStatus: 'REJECTED_BY_VALIDATOR',
      resultType: 'BLOCKED',
      reasonCode: 'RULE_BLOCKED',
      answer: 'Yêu cầu bị từ chối do chính sách an toàn: không cung cấp chẩn đoán y khoa.',
      citations: [],
      asOf: '2026-10-22T10:00:00Z',
    };

    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(blockedResponse);

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });

    renderer.act(() => {
      input.props.onChangeText('Chẩn đoán bệnh đau khớp gối của tôi');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });

    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    const blockedCard = tree.root.findByProps({ testID: 'ai-knowledge-blocked-card' });
    expect(blockedCard).toBeDefined();
  });

  it('renders error card and retries the exact question that previously failed', async () => {
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockRejectedValueOnce(
      new ApiError(504, 'Gateway Timeout')
    );

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });

    renderer.act(() => {
      input.props.onChangeText('Câu hỏi bị lỗi timeout');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });

    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    const errorState = tree.root.findByProps({ testID: 'ai-knowledge-error-state' });
    expect(errorState).toBeDefined();

    // User modifies input to something completely different
    renderer.act(() => {
      input.props.onChangeText('Câu hỏi mới gõ dở dang');
    });

    // Verify retry button is present and press Retry
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);
    const retryBtn = tree.root.findByProps({ testID: 'ai-knowledge-retry-button' });

    await renderer.act(async () => {
      retryBtn.props.onPress();
    });
    await settle();

    // Must have retried the FAILED question ('Câu hỏi bị lỗi timeout'), NOT the modified input
    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenLastCalledWith({
      question: 'Câu hỏi bị lỗi timeout',
      language: 'vi',
    });
    expect(tree.root.findByProps({ testID: 'ai-knowledge-answer-text' })).toBeDefined();
  });

  it('unmounting while request is in flight does not trigger state updates or throw', async () => {
    let resolvePromise: (value: F01KnowledgeQueryResponse) => void;
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockReturnValue(
      new Promise<F01KnowledgeQueryResponse>((resolve) => {
        resolvePromise = resolve;
      })
    );

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    renderer.act(() => {
      input.props.onChangeText('Câu hỏi trước khi unmount');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    renderer.act(() => {
      submitBtn.props.onPress();
    });

    // Unmount component while request is still pending
    renderer.act(() => {
      tree.unmount();
    });

    // Resolve after unmount
    await renderer.act(async () => {
      resolvePromise!(sampleSuccessResponse);
    });
    await settle();
  });

  it('VERIFIES INVARIANT: INFORMATION result has NO Accept or Apply action buttons anywhere', async () => {
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });

    renderer.act(() => {
      input.props.onChangeText('Kiểm tra invariant không có apply');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });

    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    // Search all pressable/button labels in the tree
    const allButtons = tree.root.findAll((node) => node.props.accessibilityRole === 'button');
    const buttonLabels = allButtons.map((b) => String(b.props.accessibilityLabel ?? ''));

    for (const label of buttonLabels) {
      expect(label.toLowerCase()).not.toContain('áp dụng');
      expect(label.toLowerCase()).not.toContain('chấp nhận');
      expect(label.toLowerCase()).not.toContain('apply');
      expect(label.toLowerCase()).not.toContain('accept');
    }
  });
});
