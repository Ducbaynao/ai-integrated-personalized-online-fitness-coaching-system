import React from 'react';
import renderer from 'react-test-renderer';
import { AiKnowledgeScreen, AiKnowledgeScreenProps } from '@/features/ai-knowledge/AiKnowledgeScreen';
import { aiKnowledgeApi } from '@/services/aiKnowledgeApi';
import { coachingApi } from '@/services/coachingApi';
import { useAuth } from '@/features/auth/AuthContext';
import { ApiError } from '@/types/auth';
import { F01KnowledgeQueryResponse, AiRunResponse } from '@/types/aiKnowledge';

const mockBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ back: mockBack }),
}));

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('@/services/coachingApi', () => ({
  coachingApi: {
    getRelationships: jest.fn(),
  },
}));

jest.mock('@/services/aiKnowledgeApi', () => {
  const actual = jest.requireActual('@/services/aiKnowledgeApi');
  return {
    ...actual,
    aiKnowledgeApi: {
      queryKnowledge: jest.fn(),
      getRun: jest.fn(),
    },
  };
});

describe('AiKnowledgeScreen', () => {
  const defaultStudentUser = {
    id: 'student-self-id',
    email: 'student@example.com',
    displayName: 'Nguyễn Văn Học Viên',
    status: 'ACTIVE',
    roles: ['STUDENT'],
    timezone: 'Asia/Ho_Chi_Minh',
    settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
    capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
  };

  const defaultTrainerUser = {
    id: 'trainer-self-id',
    email: 'trainer@example.com',
    displayName: 'HLV Trần Văn HLV',
    status: 'ACTIVE',
    roles: ['TRAINER'],
    timezone: 'Asia/Ho_Chi_Minh',
    settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
    capabilities: { hasStudentProfile: false, hasTrainerProfile: true, canCoach: true },
  };

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
    (useAuth as jest.Mock).mockReturnValue({
      user: defaultStudentUser,
      activeCapability: 'STUDENT',
    });
    (coachingApi.getRelationships as jest.Mock).mockResolvedValue({
      items: [],
      page: 0,
      size: 50,
    });
  });

  const renderScreen = (props: AiKnowledgeScreenProps = {}) => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<AiKnowledgeScreen {...props} />);
    });
    return tree;
  };

  const settle = async () => {
    await renderer.act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 20));
    });
  };

  it('renders initial state for Student: empty input, context tabs with General active, submit disabled', () => {
    const tree = renderScreen();

    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    expect(input.props.value).toBe('');

    const charCount = tree.root.findByProps({ testID: 'ai-knowledge-char-count' });
    expect(charCount.props.children).toEqual([0, '/', 1000]);

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    expect(submitBtn.props.accessibilityState.disabled).toBe(true);

    const generalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-general-tab' });
    expect(generalTab.props.accessibilityState.selected).toBe(true);

    const personalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-personal-tab' });
    expect(personalTab.props.accessibilityState.selected).toBe(false);

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

  it('invokes onBack prop if provided', () => {
    const onBackMock = jest.fn();
    const tree = renderScreen({ onBack: onBackMock });
    const backBtn = tree.root.findByProps({ testID: 'ai-knowledge-back-button' });

    renderer.act(() => {
      backBtn.props.onPress();
    });

    expect(onBackMock).toHaveBeenCalledTimes(1);
    expect(mockBack).not.toHaveBeenCalled();
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

  it('submits general question with includePersonalContext: false and no studentId', async () => {
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

    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenCalledWith(
      expect.objectContaining({
        question: 'Thời gian tập luyện khuyến nghị của WHO là bao nhiêu?',
        language: 'vi',
        includePersonalContext: false,
      }),
      expect.any(Object)
    );

    // Answer Text
    const answerText = tree.root.findByProps({ testID: 'ai-knowledge-answer-text' });
    expect(answerText.props.children).toContain('150-300 phút hoạt động thể lực vừa phải');

    // Citations
    const citation = tree.root.findByProps({ testID: 'ai-knowledge-citation-0' });
    expect(citation).toBeDefined();

    // Limitations
    const limitation = tree.root.findByProps({ testID: 'ai-knowledge-limitation-0' });
    expect(limitation.props.children[1]).toContain('Áp dụng cho người lớn từ 18-64 tuổi');

    // Missing Data
    const missingItem = tree.root.findByProps({ testID: 'ai-knowledge-missing-item-0' });
    expect(missingItem.props.children[1]).toContain('Tình trạng chấn thương hoặc bệnh nền nếu có');

    // Data Freshness (asOf)
    const asOf = tree.root.findByProps({ testID: 'ai-knowledge-as-of' });
    expect(asOf.props.children).toContain('2026-10-22T10:00:00Z');
  });

  it('Student Personalized Mode: transmits includePersonalContext: true and own user.id', async () => {
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    const tree = renderScreen();

    // Switch to personal tab
    const personalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-personal-tab' });
    renderer.act(() => {
      personalTab.props.onPress();
    });

    // Check scope note is rendered for student
    expect(tree.root.findByProps({ testID: 'ai-knowledge-student-scope-note' })).toBeDefined();

    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    renderer.act(() => {
      input.props.onChangeText('Tôi nên nghỉ bao lâu giữa các hiệp Squat?');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenCalledWith(
      expect.objectContaining({
        question: 'Tôi nên nghỉ bao lâu giữa các hiệp Squat?',
        language: 'vi',
        includePersonalContext: true,
        studentId: 'student-self-id',
      }),
      expect.any(Object)
    );
  });

  it('Trainer Mode: lists managed students and sends selected studentId with includePersonalContext: true', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: defaultTrainerUser,
      activeCapability: 'TRAINER',
    });

    const mockRelationships = {
      items: [
        {
          id: 'rel-1',
          studentId: 'student-A-id',
          trainerId: 'trainer-self-id',
          status: 'ACTIVE',
          counterparty: { userId: 'student-A-id', displayName: 'Học viên A' },
        },
        {
          id: 'rel-2',
          studentId: 'student-B-id',
          trainerId: 'trainer-self-id',
          status: 'ACTIVE',
          counterparty: { userId: 'student-B-id', displayName: 'Học viên B' },
        },
      ],
      page: 0,
      size: 50,
    };
    (coachingApi.getRelationships as jest.Mock).mockResolvedValueOnce(mockRelationships);
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    const tree = renderScreen();
    await settle();

    // Switch to personal (student) tab
    const personalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-personal-tab' });
    renderer.act(() => {
      personalTab.props.onPress();
    });
    await settle();

    // Student chips should be rendered
    const studentChipB = tree.root.findByProps({
      testID: 'ai-knowledge-student-option-student-B-id',
    });
    expect(studentChipB).toBeDefined();

    // Select student B
    renderer.act(() => {
      studentChipB.props.onPress();
    });

    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    renderer.act(() => {
      input.props.onChangeText('Bài tập thay thế Bench Press cho học viên này?');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenCalledWith(
      expect.objectContaining({
        question: 'Bài tập thay thế Bench Press cho học viên này?',
        language: 'vi',
        includePersonalContext: true,
        studentId: 'student-B-id',
      }),
      expect.any(Object)
    );
  });

  it('Trainer Mode with prop studentId: initializes locked in PERSONAL mode and queries for that student', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: defaultTrainerUser,
      activeCapability: 'TRAINER',
    });
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    const tree = renderScreen({ studentId: 'student-from-prop-123' });
    await settle();

    // Should have locked student badge
    const lockedBadge = tree.root.findByProps({ testID: 'ai-knowledge-locked-student-badge' });
    expect(lockedBadge).toBeDefined();

    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    renderer.act(() => {
      input.props.onChangeText('Thời gian phục hồi phù hợp cho học viên này?');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenCalledWith(
      expect.objectContaining({
        question: 'Thời gian phục hồi phù hợp cho học viên này?',
        includePersonalContext: true,
        studentId: 'student-from-prop-123',
      }),
      expect.any(Object)
    );
  });

  it('Anti-stale: changing context mode immediately cancels in-flight requests and clears previous result', async () => {
    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockResolvedValueOnce(sampleSuccessResponse);

    const tree = renderScreen();
    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    renderer.act(() => {
      input.props.onChangeText('Câu hỏi kiến thức chung ban đầu');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    expect(tree.root.findByProps({ testID: 'ai-knowledge-answer-text' })).toBeDefined();

    // Switch context mode to personal
    const personalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-personal-tab' });
    renderer.act(() => {
      personalTab.props.onPress();
    });

    // Previous answer must be cleared immediately
    expect(tree.root.findAllByProps({ testID: 'ai-knowledge-answer-text' })).toHaveLength(0);
  });

  it('Readback / History: loads run detail when initialRunId is provided', async () => {
    const mockRunResponse: AiRunResponse = {
      runId: 'run-history-1111',
      runStatus: 'SUCCEEDED',
      requestType: 'F01_KNOWLEDGE_QUERY',
      completedAt: '2026-10-22T10:05:00Z',
    };
    (aiKnowledgeApi.getRun as jest.Mock).mockResolvedValueOnce(mockRunResponse);

    const tree = renderScreen({ initialRunId: 'run-history-1111' });
    await settle();

    expect(aiKnowledgeApi.getRun).toHaveBeenCalledWith('run-history-1111', expect.any(Object));
    const readbackCard = tree.root.findByProps({ testID: 'ai-knowledge-readback-card' });
    expect(readbackCard).toBeDefined();
  });

  it('Readback / History: handles 403 AI_FORBIDDEN when permissions are revoked', async () => {
    const forbiddenError = new ApiError(403, 'Forbidden', { errorCode: 'AI_FORBIDDEN' } as any);
    (aiKnowledgeApi.getRun as jest.Mock).mockRejectedValueOnce(forbiddenError);

    const tree = renderScreen({ initialRunId: 'revoked-run-2222' });
    await settle();

    const errorCard = tree.root.findByProps({ testID: 'ai-knowledge-error-state' });
    expect(errorCard).toBeDefined();
    const errorBody = errorCard.findAll((node) =>
      typeof node.props.children === 'string' &&
      node.props.children.includes('Bạn không có quyền truy cập dữ liệu cá nhân của học viên này.')
    );
    expect(errorBody.length).toBeGreaterThan(0);
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
    expect(aiKnowledgeApi.queryKnowledge).toHaveBeenLastCalledWith(
      expect.objectContaining({
        question: 'Câu hỏi bị lỗi timeout',
        language: 'vi',
      }),
      expect.any(Object)
    );
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

  it('supports student picker pagination: loads more students when load more is pressed', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: defaultTrainerUser,
      activeCapability: 'TRAINER',
    });

    // Page 0 returns 20 items (indicating more pages available)
    const page0Items = Array.from({ length: 20 }, (_, i) => ({
      id: `rel-${i}`,
      studentId: `student-${i}`,
      trainerId: 'trainer-self-id',
      status: 'ACTIVE',
      direction: 'OUTGOING',
      requestedBy: 'trainer-self-id',
      requestedAt: '2026-10-01T00:00:00Z',
      acceptedAt: '2026-10-01T00:00:00Z',
      startedAt: '2026-10-01T00:00:00Z',
      endedAt: null,
      version: 1,
      counterparty: { userId: `student-${i}`, displayName: `Học viên ${i}` },
    }));

    const page1Items = [
      {
        id: 'rel-20',
        studentId: 'student-20',
        trainerId: 'trainer-self-id',
        status: 'ACTIVE',
        direction: 'OUTGOING',
        requestedBy: 'trainer-self-id',
        requestedAt: '2026-10-01T00:00:00Z',
        acceptedAt: '2026-10-01T00:00:00Z',
        startedAt: '2026-10-01T00:00:00Z',
        endedAt: null,
        version: 1,
        counterparty: { userId: 'student-20', displayName: 'Học viên 20' },
      },
    ];

    (coachingApi.getRelationships as jest.Mock)
      .mockResolvedValueOnce({ items: page0Items, page: 0, size: 20 })
      .mockResolvedValueOnce({ items: page1Items, page: 1, size: 20 });

    const tree = renderScreen();
    await settle();

    // Switch to PERSONAL context tab
    const personalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-personal-tab' });
    renderer.act(() => {
      personalTab.props.onPress();
    });

    // Check load more button is present
    const loadMoreBtn = tree.root.findByProps({
      testID: 'ai-knowledge-load-more-students-button',
    });
    expect(loadMoreBtn).toBeDefined();

    // Press load more
    await renderer.act(async () => {
      loadMoreBtn.props.onPress();
    });
    await settle();

    expect(coachingApi.getRelationships).toHaveBeenCalledWith(1, 20);
    // Student 20 should now be rendered
    expect(
      tree.root.findByProps({ testID: 'ai-knowledge-student-option-student-20' })
    ).toBeDefined();
  });

  it('refreshes student list when refresh button is pressed', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: defaultTrainerUser,
      activeCapability: 'TRAINER',
    });

    (coachingApi.getRelationships as jest.Mock)
      .mockResolvedValueOnce({
        items: [
          {
            id: 'rel-1',
            studentId: 'student-1',
            trainerId: 'trainer-self-id',
            status: 'ACTIVE',
            direction: 'OUTGOING',
            requestedBy: 'trainer-self-id',
            requestedAt: '2026-10-01T00:00:00Z',
            acceptedAt: '2026-10-01T00:00:00Z',
            startedAt: '2026-10-01T00:00:00Z',
            endedAt: null,
            version: 1,
            counterparty: { userId: 'student-1', displayName: 'Học viên 1' },
          },
        ],
        page: 0,
        size: 20,
      })
      .mockResolvedValueOnce({
        items: [
          {
            id: 'rel-2',
            studentId: 'student-2',
            trainerId: 'trainer-self-id',
            status: 'ACTIVE',
            direction: 'OUTGOING',
            requestedBy: 'trainer-self-id',
            requestedAt: '2026-10-01T00:00:00Z',
            acceptedAt: '2026-10-01T00:00:00Z',
            startedAt: '2026-10-01T00:00:00Z',
            endedAt: null,
            version: 1,
            counterparty: { userId: 'student-2', displayName: 'Học viên 2 Mới' },
          },
        ],
        page: 0,
        size: 20,
      });

    const tree = renderScreen();
    await settle();

    // Switch to PERSONAL context tab
    const personalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-personal-tab' });
    renderer.act(() => {
      personalTab.props.onPress();
    });

    const refreshBtn = tree.root.findByProps({ testID: 'ai-knowledge-refresh-students-button' });
    await renderer.act(async () => {
      refreshBtn.props.onPress();
    });
    await settle();

    expect(coachingApi.getRelationships).toHaveBeenCalledTimes(2);
    expect(
      tree.root.findByProps({ testID: 'ai-knowledge-student-option-student-2' })
    ).toBeDefined();
  });

  it('handles readback error 403 AI_FORBIDDEN when Trainer lost coaching relationship authority', async () => {
    (aiKnowledgeApi.getRun as jest.Mock).mockRejectedValueOnce(
      new ApiError(403, 'Forbidden', {
        errorCode: 'AI_FORBIDDEN',
        message: 'Bạn không có quyền truy cập kết quả tra cứu này.',
        timestamp: '2026-10-24T10:00:00Z',
        requestId: 'req-revoked-run',
        fieldErrors: [],
      })
    );

    const tree = renderScreen({ initialRunId: 'revoked-run-id' });
    await settle();

    const errorState = tree.root.findByProps({ testID: 'ai-knowledge-error-state' });
    expect(errorState).toBeDefined();
    expect(
      errorState.findAll(
        (n) => n.props.children === 'Bạn không có quyền truy cập kết quả tra cứu này.'
      ).length
    ).toBeGreaterThanOrEqual(1);
  });

  it('shows refresh students button on error card when Trainer personal query encounters 403 AI_FORBIDDEN', async () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: defaultTrainerUser,
      activeCapability: 'TRAINER',
    });

    (coachingApi.getRelationships as jest.Mock).mockResolvedValue({
      items: [
        {
          id: 'rel-1',
          studentId: 'student-revoked',
          trainerId: 'trainer-self-id',
          status: 'ACTIVE',
          direction: 'OUTGOING',
          requestedBy: 'trainer-self-id',
          requestedAt: '2026-10-01T00:00:00Z',
          acceptedAt: '2026-10-01T00:00:00Z',
          startedAt: '2026-10-01T00:00:00Z',
          endedAt: null,
          version: 1,
          counterparty: { userId: 'student-revoked', displayName: 'Học viên Hết Hạn' },
        },
      ],
      page: 0,
      size: 20,
    });

    (aiKnowledgeApi.queryKnowledge as jest.Mock).mockRejectedValueOnce(
      new ApiError(403, 'Forbidden', {
        errorCode: 'AI_FORBIDDEN',
        message: 'Bạn không có quyền truy vấn dữ liệu của học viên này.',
        timestamp: '2026-10-24T10:00:00Z',
        requestId: 'req-forbidden-student',
        fieldErrors: [],
      })
    );

    const tree = renderScreen();
    await settle();

    // Switch to PERSONAL context tab
    const personalTab = tree.root.findByProps({ testID: 'ai-knowledge-context-personal-tab' });
    renderer.act(() => {
      personalTab.props.onPress();
    });

    const input = tree.root.findByProps({ testID: 'ai-knowledge-question-input' });
    renderer.act(() => {
      input.props.onChangeText('Câu hỏi về học viên đã hết hạn coaching');
    });

    const submitBtn = tree.root.findByProps({ testID: 'ai-knowledge-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });
    await settle();

    const errorRefreshBtn = tree.root.findByProps({
      testID: 'ai-knowledge-error-refresh-students-button',
    });
    expect(errorRefreshBtn).toBeDefined();

    // Click refresh from error card
    await renderer.act(async () => {
      errorRefreshBtn.props.onPress();
    });
    await settle();

    expect(coachingApi.getRelationships).toHaveBeenCalledTimes(2);
  });
});
