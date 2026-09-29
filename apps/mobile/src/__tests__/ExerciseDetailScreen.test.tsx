import React from 'react';
import { Text } from 'react-native';
import renderer from 'react-test-renderer';
import { ExerciseDetailScreen } from '@/features/exercise/ExerciseDetailScreen';
import { useExerciseDetail } from '@/features/exercise/exerciseQueries';
import { ApiError, ErrorResponse } from '@/types/auth';
import { ExerciseDetail } from '@/types/exercise';

const mockBack = jest.fn();
const mockReplace = jest.fn();
const mockCanGoBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ back: mockBack, replace: mockReplace, canGoBack: mockCanGoBack }),
}));
jest.mock('@/features/exercise/exerciseQueries', () => ({ useExerciseDetail: jest.fn() }));

const exerciseId = '10000000-0000-0000-0000-000000000001';
const detail: ExerciseDetail = {
  id: exerciseId,
  code: 'BARBELL_SQUAT',
  name: 'Barbell Squat',
  category: { code: 'STRENGTH', name: 'Strength' },
  description: 'A compound lower-body exercise.',
  instructions: 'Keep your torso stable and descend with control.',
  difficulty: 'INTERMEDIATE',
  movementPattern: 'SQUAT',
  unilateral: false,
  variations: [
    {
      id: '20000000-0000-0000-0000-000000000001',
      code: 'HIGH_BAR_SQUAT',
      name: 'High Bar Squat',
      description: 'Bar rests high on the upper back.',
      instructions: 'Keep the chest tall.',
      difficulty: 'INTERMEDIATE',
      defaultVariation: true,
      muscles: [
        { code: 'QUADRICEPS', name: 'Quadriceps', involvement: 'PRIMARY' },
        { code: 'GLUTES', name: 'Glutes', involvement: 'SECONDARY' },
      ],
      equipment: [{ code: 'BARBELL', name: 'Barbell', requirement: 'REQUIRED' }],
      media: [],
    },
  ],
  tags: [{ code: 'COMPOUND', name: 'Compound' }],
  guidance: [
    {
      id: '30000000-0000-0000-0000-000000000001',
      exerciseVariationId: null,
      type: 'SAFETY_NOTE',
      title: 'Maintain a stable spine',
      description: 'Use a load you can control.',
      correction: 'Reduce the load if form changes.',
      severity: null,
      sortOrder: 0,
    },
  ],
  mediaAvailable: false,
};

function queryState(overrides: Record<string, unknown> = {}) {
  return {
    data: detail,
    error: null,
    isPending: false,
    isError: false,
    isRefetchError: false,
    isRefetching: false,
    refetch: jest.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

function apiError(status: number, errorCode: string, message: string): ApiError {
  const response: ErrorResponse = {
    errorCode,
    message,
    timestamp: '2026-09-28T00:00:00Z',
    requestId: 'request-1',
    fieldErrors: [],
  };
  return new ApiError(status, message, response);
}

const mountedTrees: renderer.ReactTestRenderer[] = [];

function renderScreen(id: unknown = exerciseId) {
  let tree: renderer.ReactTestRenderer;
  renderer.act(() => {
    tree = renderer.create(<ExerciseDetailScreen exerciseId={id} />);
  });
  mountedTrees.push(tree!);
  return tree!;
}

describe('ExerciseDetailScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    mockCanGoBack.mockReturnValue(true);
    (useExerciseDetail as jest.Mock).mockReturnValue(queryState());
  });

  afterEach(() => {
    renderer.act(() => {
      mountedTrees.splice(0).forEach((tree) => tree.unmount());
    });
  });

  it('shows an accessible loading state', () => {
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({ data: undefined, isPending: true })
    );
    const loading = renderScreen().root.findByProps({ testID: 'exercise-detail-loading' });

    expect(loading.props.accessibilityRole).toBe('progressbar');
    expect(loading.props.accessibilityLabel).toBe('Đang tải chi tiết bài tập');
  });

  it('renders Vietnamese static labels while preserving API-provided English names', () => {
    const root = renderScreen().root;
    const text = root.findAllByType(Text).map((node) => node.props.children);

    expect(root.findByProps({ testID: 'exercise-detail-name' }).props.children).toBe(
      'Barbell Squat'
    );
    expect(text).toContain('Mô tả');
    expect(text).toContain('Hướng dẫn thực hiện');
    expect(text).toContain('Nhóm cơ');
    expect(text).toContain('Thiết bị');
    expect(text).toContain('Nhãn');
    expect(text).toContain('Lưu ý kỹ thuật');
    expect(text).toContain('Biến thể');
    expect(text).toContain('High Bar Squat');
  });

  it('renders the unavailable-media placeholder accessibly', () => {
    const media = renderScreen().root.findByProps({ testID: 'exercise-detail-media' });

    expect(media.props.accessibilityLabel).toBe('Bài tập chưa có nội dung hướng dẫn');
    expect(media.findAllByType(Text).some((node) => node.props.children === 'Chưa có nội dung hướng dẫn')).toBe(true);
  });

  it('hides optional sections when the contract fields are empty or null', () => {
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({
        data: {
          ...detail,
          category: null,
          description: null,
          instructions: null,
          difficulty: null,
          movementPattern: null,
          variations: [],
          tags: [],
          guidance: [],
        },
      })
    );
    const root = renderScreen().root;

    expect(root.findAllByProps({ testID: 'exercise-detail-description' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'exercise-detail-instructions' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'exercise-detail-muscles' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'exercise-detail-equipment' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'exercise-detail-tags' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'exercise-detail-guidance' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'exercise-detail-variations' })).toHaveLength(0);
  });

  it('exposes a variation with only its API-provided name as a heading', () => {
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({
        data: {
          ...detail,
          variations: [
            {
              ...detail.variations[0],
              description: null,
              instructions: null,
              difficulty: null,
              defaultVariation: false,
            },
          ],
        },
      })
    );
    const variation = renderScreen().root.findByProps({
      testID: 'exercise-variation-20000000-0000-0000-0000-000000000001',
    });
    const text = variation.findAllByType(Text);

    expect(variation.props.accessible).toBeUndefined();
    expect(variation.props.accessibilityLabel).toBeUndefined();
    expect(text).toHaveLength(1);
    expect(text[0].props.children).toBe('High Bar Squat');
    expect(text[0].props.accessibilityRole).toBe('header');
  });

  it('keeps the default-variation marker accessible as visible text', () => {
    const variation = renderScreen().root.findByProps({
      testID: 'exercise-variation-20000000-0000-0000-0000-000000000001',
    });

    expect(variation.findAllByType(Text).some((node) => node.props.children === 'Mặc định')).toBe(true);
  });

  it('keeps variation difficulty accessible as visible text', () => {
    const variation = renderScreen().root.findByProps({
      testID: 'exercise-variation-20000000-0000-0000-0000-000000000001',
    });

    expect(
      variation.findAllByType(Text).some((node) =>
        JSON.stringify(node.props.children).includes('Độ khó')
      )
    ).toBe(true);
  });

  it('keeps variation description accessible as visible text', () => {
    const variation = renderScreen().root.findByProps({
      testID: 'exercise-variation-20000000-0000-0000-0000-000000000001',
    });

    expect(
      variation.findAllByType(Text).some((node) => node.props.children === 'Bar rests high on the upper back.')
    ).toBe(true);
  });

  it('keeps variation instructions accessible as visible text', () => {
    const variation = renderScreen().root.findByProps({
      testID: 'exercise-variation-20000000-0000-0000-0000-000000000001',
    });

    expect(
      variation.findAllByType(Text).some((node) =>
        JSON.stringify(node.props.children).includes('Keep the chest tall.')
      )
    ).toBe(true);
  });

  it('does not aggregate or omit full variation content from the accessibility tree', () => {
    const variation = renderScreen().root.findByProps({
      testID: 'exercise-variation-20000000-0000-0000-0000-000000000001',
    });
    const readableContent = variation
      .findAllByType(Text)
      .map((node) => JSON.stringify(node.props.children))
      .join(' ');

    expect(variation.props.accessible).toBeUndefined();
    expect(variation.props.accessibilityLabel).toBeUndefined();
    expect(readableContent).toContain('High Bar Squat');
    expect(readableContent).toContain('Mặc định');
    expect(readableContent).toContain('Độ khó');
    expect(readableContent).toContain('Bar rests high on the upper back.');
    expect(readableContent).toContain('Keep the chest tall.');
    expect(readableContent).not.toContain('undefined');
    expect(readableContent).not.toContain('""');
  });

  it('does not request a missing or invalid route parameter', () => {
    const root = renderScreen(['not-a-valid-id']).root;

    expect(useExerciseDetail).toHaveBeenCalledWith(null);
    expect(root.findByProps({ testID: 'exercise-detail-invalid-route' })).toBeDefined();
  });

  it('uses a Vietnamese unavailable state for 404 without exposing backend details', () => {
    const rawMessage = 'Archived exercise BARBELL_SQUAT exists';
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({
        data: undefined,
        isError: true,
        error: apiError(404, 'EXERCISE_NOT_FOUND', rawMessage),
      })
    );
    const root = renderScreen().root;
    const text = root.findAllByType(Text).map((node) => node.props.children).join(' ');

    expect(root.findByProps({ testID: 'exercise-detail-unavailable' })).toBeDefined();
    expect(text).toContain('Bài tập này không tồn tại hoặc hiện không khả dụng.');
    expect(text).not.toContain(rawMessage);
    expect(root.findAllByProps({ testID: 'retry-exercise-detail' })).toHaveLength(0);
  });

  it.each([
    [403, 'ACCESS_DENIED', 'exercise-detail-permission', 'Bạn chưa có quyền xem bài tập'],
    [401, 'AUTH_TOKEN_EXPIRED', 'exercise-detail-session', 'Phiên đăng nhập không còn hiệu lực'],
  ])('renders a non-retryable access state for HTTP %s', (status, code, testID, title) => {
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({ data: undefined, isError: true, error: apiError(status, code, 'Raw error') })
    );
    const root = renderScreen().root;

    expect(root.findByProps({ testID })).toBeDefined();
    expect(root.findAllByType(Text).some((node) => node.props.children === title)).toBe(true);
    expect(root.findAllByProps({ testID: 'retry-exercise-detail' })).toHaveLength(0);
  });

  it.each([
    [404, 'EXERCISE_NOT_FOUND', 'exercise-detail-unavailable'],
    [403, 'ACCESS_DENIED', 'exercise-detail-permission'],
    [403, 'ACCOUNT_UNAVAILABLE', 'exercise-detail-permission'],
    [409, 'STUDENT_CAPABILITY_UNAVAILABLE', 'exercise-detail-permission'],
    [401, 'AUTH_SESSION_REVOKED', 'exercise-detail-session'],
  ])(
    'hides cached detail when a terminal HTTP %s refetch fails',
    (status, code, expectedState) => {
      const rawMessage = `Sensitive backend detail for ${code}`;
      (useExerciseDetail as jest.Mock).mockReturnValue(
        queryState({
          isError: true,
          isRefetchError: true,
          error: apiError(status, code, rawMessage),
        })
      );
      const root = renderScreen().root;
      const renderedText = root.findAllByType(Text).map((node) => node.props.children).join(' ');

      expect(root.findByProps({ testID: expectedState })).toBeDefined();
      expect(root.findAllByProps({ testID: 'exercise-detail-name' })).toHaveLength(0);
      expect(root.findAllByProps({ testID: 'exercise-detail-description' })).toHaveLength(0);
      expect(root.findAllByProps({ testID: 'exercise-detail-instructions' })).toHaveLength(0);
      expect(root.findAllByProps({ testID: 'exercise-detail-variations' })).toHaveLength(0);
      expect(root.findAllByProps({ testID: 'exercise-detail-media' })).toHaveLength(0);
      expect(renderedText).not.toContain(rawMessage);
    }
  );

  it.each([
    [400, 'VALIDATION_FAILED', 'exercise-detail-validation'],
    [409, 'UNEXPECTED_CONFLICT', 'exercise-detail-generic'],
  ])('fails closed for cached non-transient HTTP %s errors', (status, code, expectedState) => {
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({ isError: true, isRefetchError: true, error: apiError(status, code, 'Raw backend message') })
    );
    const root = renderScreen().root;

    expect(root.findByProps({ testID: expectedState })).toBeDefined();
    expect(root.findAllByProps({ testID: 'exercise-detail-name' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'retry-exercise-detail' })).toHaveLength(0);
  });

  it.each([
    [0, 'exercise-detail-network'],
    [503, 'exercise-detail-server'],
  ])('offers retry for HTTP %s failures', (status, testID) => {
    const refetch = jest.fn();
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({ data: undefined, isError: true, error: new ApiError(status, 'Raw error'), refetch })
    );
    const root = renderScreen().root;

    expect(root.findByProps({ testID })).toBeDefined();
    renderer.act(() => {
      root.findByProps({ testID: 'retry-exercise-detail' }).props.onPress();
    });
    expect(refetch).toHaveBeenCalled();
  });

  it('keeps stale detail visible when a network refetch fails', () => {
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({ isError: true, isRefetchError: true, error: new ApiError(0, 'Offline') })
    );
    const root = renderScreen().root;

    expect(root.findByProps({ testID: 'exercise-detail-background-error' })).toBeDefined();
    expect(root.findByProps({ testID: 'exercise-detail-name' }).props.children).toBe('Barbell Squat');
  });

  it('keeps stale detail visible and retries when a server refetch fails', () => {
    const refetch = jest.fn().mockResolvedValue(undefined);
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({
        isError: true,
        isRefetchError: true,
        error: new ApiError(503, 'Internal database detail'),
        refetch,
      })
    );
    const root = renderScreen().root;
    const renderedText = root.findAllByType(Text).map((node) => node.props.children).join(' ');

    expect(root.findByProps({ testID: 'exercise-detail-background-error' })).toBeDefined();
    expect(root.findByProps({ testID: 'exercise-detail-name' }).props.children).toBe('Barbell Squat');
    expect(renderedText).not.toContain('Internal database detail');
    renderer.act(() => {
      root.findByProps({ testID: 'retry-exercise-detail-background' }).props.onPress();
    });
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('uses navigation history for the accessible back action', () => {
    const back = renderScreen().root.findByProps({ testID: 'exercise-detail-back' });

    expect(back.props.accessibilityRole).toBe('button');
    expect(back.props.accessibilityLabel).toBe('Quay lại thư viện bài tập');
    expect(back.props.accessibilityHint).toBe('Trở về màn hình trước');
    renderer.act(() => back.props.onPress());
    expect(mockBack).toHaveBeenCalled();
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('replaces a direct deep link with the catalog when no history exists', () => {
    mockCanGoBack.mockReturnValue(false);
    const back = renderScreen().root.findByProps({ testID: 'exercise-detail-back' });

    renderer.act(() => back.props.onPress());

    expect(mockBack).not.toHaveBeenCalled();
    expect(mockReplace).toHaveBeenCalledTimes(1);
    expect(mockReplace).toHaveBeenCalledWith('/(app)/exercises');
  });

  it('uses the same catalog fallback from a deep-linked unavailable state without looping', () => {
    mockCanGoBack.mockReturnValue(false);
    (useExerciseDetail as jest.Mock).mockReturnValue(
      queryState({
        data: undefined,
        isError: true,
        error: apiError(404, 'EXERCISE_NOT_FOUND', 'Raw backend message'),
      })
    );
    const root = renderScreen().root;

    expect(root.findByProps({ testID: 'exercise-detail-unavailable' })).toBeDefined();
    renderer.act(() => root.findByProps({ testID: 'exercise-detail-back' }).props.onPress());

    expect(mockBack).not.toHaveBeenCalled();
    expect(mockReplace).toHaveBeenCalledTimes(1);
    expect(mockReplace).toHaveBeenCalledWith('/(app)/exercises');
  });
});
