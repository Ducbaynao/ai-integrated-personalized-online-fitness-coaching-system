import React from 'react';
import { FlatList, Text, TextInput } from 'react-native';
import renderer from 'react-test-renderer';
import { ExerciseCatalogScreen } from '@/features/exercise/ExerciseCatalogScreen';
import { useExerciseCatalog, useExerciseFilterMetadata } from '@/features/exercise/exerciseQueries';
import { ExerciseCatalogItem, ExerciseFilterMetadata } from '@/types/exercise';
import { ApiError } from '@/types/auth';

jest.mock('expo-router', () => ({ useRouter: () => ({ back: jest.fn() }) }));
jest.mock('@/hooks/use-debounced-value', () => ({ useDebouncedValue: (value: string) => value }));
jest.mock('@/features/exercise/exerciseQueries', () => ({
  useExerciseCatalog: jest.fn(),
  useExerciseFilterMetadata: jest.fn(),
}));

const exercise: ExerciseCatalogItem = {
  id: 'exercise-1',
  code: 'BACK_SQUAT',
  name: 'Back Squat',
  category: { code: 'STRENGTH', name: 'Sức mạnh' },
  description: 'Compound squat',
  difficulty: 'INTERMEDIATE',
  movementPattern: 'SQUAT',
  unilateral: false,
  primaryMuscles: [{ code: 'QUADRICEPS', name: 'Đùi trước' }],
  equipment: [{ code: 'BARBELL', name: 'Đòn tạ' }],
  tags: [],
  variationCount: 1,
  mediaAvailable: false,
};

const metadata: ExerciseFilterMetadata = {
  categories: [{ code: 'STRENGTH', name: 'Sức mạnh' }],
  muscleGroups: [{ code: 'QUADRICEPS', name: 'Đùi trước', parentCode: null }],
  equipment: [{ code: 'BARBELL', name: 'Đòn tạ' }],
  tags: [{ code: 'COMPOUND', name: 'Đa khớp' }],
  difficulties: ['BEGINNER', 'INTERMEDIATE'],
  movementPatterns: ['SQUAT'],
};

const page = (items: ExerciseCatalogItem[] = [exercise]) => ({
  pages: [{ items, page: 0, size: 20, totalItems: items.length, totalPages: items.length ? 1 : 0 }],
  pageParams: [0],
});

function catalogState(overrides: Record<string, unknown> = {}) {
  return {
    data: page(),
    error: null,
    isPending: false,
    isError: false,
    isRefetchError: false,
    isFetchNextPageError: false,
    isFetchingNextPage: false,
    isRefetching: false,
    hasNextPage: false,
    refetch: jest.fn().mockResolvedValue(undefined),
    fetchNextPage: jest.fn().mockResolvedValue(undefined),
    ...overrides,
  };
}

const mountedTrees: renderer.ReactTestRenderer[] = [];

function renderScreen() {
  let tree: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<ExerciseCatalogScreen />); });
  mountedTrees.push(tree!);
  return tree!;
}

describe('ExerciseCatalogScreen', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState());
    (useExerciseFilterMetadata as jest.Mock).mockReturnValue({
      data: metadata,
      isPending: false,
      isError: false,
      refetch: jest.fn(),
    });
  });

  afterEach(() => {
    renderer.act(() => {
      mountedTrees.splice(0).forEach((tree) => tree.unmount());
    });
  });

  it('shows an accessible skeleton during the initial request', () => {
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ data: undefined, isPending: true }));
    const root = renderScreen().root;
    expect(root.findByProps({ testID: 'exercise-catalog-loading' }).props.accessibilityRole).toBe('progressbar');
  });

  it('renders catalog content and the unavailable-media placeholder', () => {
    const root = renderScreen().root;
    const row = root.findByProps({ testID: 'exercise-row-exercise-1' });
    expect(row.findAllByType(Text).some((node) => node.props.children === 'Back Squat')).toBe(true);
    expect(root.findByProps({ testID: 'exercise-media-exercise-1' }).props.accessibilityLabel).toBe('Bài tập chưa có nội dung hướng dẫn');
  });

  it('renders the main static copy and accessibility text in Vietnamese', () => {
    const root = renderScreen().root;
    expect(root.findAllByType(Text).some((node) => node.props.children === 'Thư viện bài tập')).toBe(true);
    expect(root.findByProps({ testID: 'exercise-search-input' }).props).toEqual(
      expect.objectContaining({
        placeholder: 'Tìm bài tập',
        accessibilityLabel: 'Tìm kiếm bài tập',
        accessibilityHint: 'Nhập tên hoặc từ khóa bài tập',
      })
    );
    expect(root.findByProps({ testID: 'exercise-filter-button' }).props).toEqual(
      expect.objectContaining({
        accessibilityLabel: 'Lọc bài tập',
        accessibilityHint: 'Mở các lựa chọn lọc danh mục bài tập',
        accessibilityState: { expanded: false },
      })
    );
  });

  it('distinguishes an empty catalog from no search results', () => {
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ data: page([]) }));
    let tree = renderScreen();
    expect(tree.root.findByProps({ testID: 'exercise-empty-catalog' })).toBeDefined();

    renderer.act(() => tree.root.findByProps({ testID: 'exercise-search-input' }).props.onChangeText('squat'));
    expect(tree.root.findByProps({ testID: 'exercise-no-results' })).toBeDefined();
    renderer.act(() => tree.root.findByProps({ testID: 'clear-search-and-filters' }).props.onPress());
    expect((tree.root.findByProps({ testID: 'exercise-search-input' }) as renderer.ReactTestInstance).props.value).toBe('');
  });

  it('shows the initial error and retries', () => {
    const refetch = jest.fn();
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ data: undefined, isError: true, error: new Error('offline'), refetch }));
    const root = renderScreen().root;
    expect(root.findByProps({ testID: 'exercise-catalog-error' })).toBeDefined();
    renderer.act(() => root.findByProps({ testID: 'retry-exercise-catalog' }).props.onPress());
    expect(refetch).toHaveBeenCalled();
  });

  it('maps a stable permission error code to Vietnamese and hides stale catalog data', () => {
    const error = new ApiError(403, 'Access is denied.', {
      errorCode: 'ACCESS_DENIED',
      message: 'Access is denied.',
      timestamp: '2026-09-28T00:00:00Z',
      requestId: 'request-1',
      fieldErrors: [],
    });
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ isError: true, error }));
    const root = renderScreen().root;

    expect(root.findAllByType(Text).some((node) => node.props.children === 'Bạn chưa có quyền xem thư viện')).toBe(true);
    expect(root.findAllByProps({ testID: 'exercise-row-exercise-1' })).toHaveLength(0);
    expect(root.findAllByProps({ testID: 'retry-exercise-catalog' })).toHaveLength(0);
  });

  it('keeps stale content visible when a background refetch fails', () => {
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ isRefetchError: true, error: new Error('offline') }));
    const root = renderScreen().root;
    expect(root.findByProps({ testID: 'exercise-background-error' })).toBeDefined();
    expect(root.findByProps({ testID: 'exercise-row-exercise-1' })).toBeDefined();
  });

  it('loads the next page only once while pagination is idle', () => {
    const fetchNextPage = jest.fn().mockResolvedValue(undefined);
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ hasNextPage: true, fetchNextPage }));
    const root = renderScreen().root;
    renderer.act(() => root.findByType(FlatList).props.onEndReached());
    expect(fetchNextPage).toHaveBeenCalledTimes(1);
  });

  it('announces load-more progress in Vietnamese', () => {
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ isFetchingNextPage: true }));
    const indicator = renderScreen().root.findByProps({ testID: 'exercise-load-more-indicator' });
    expect(indicator.props.accessibilityRole).toBe('progressbar');
    expect(indicator.props.accessibilityLabel).toBe('Đang tải thêm bài tập');
  });

  it('renders and retries a load-more error', () => {
    const fetchNextPage = jest.fn().mockResolvedValue(undefined);
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ hasNextPage: true, isFetchNextPageError: true, fetchNextPage }));
    const root = renderScreen().root;
    expect(root.findByProps({ testID: 'exercise-load-more-error' })).toBeDefined();
    renderer.act(() => root.findByProps({ testID: 'retry-load-more' }).props.onPress());
    expect(fetchNextPage).toHaveBeenCalled();
  });

  it('passes search changes to the catalog query', () => {
    const tree = renderScreen();
    renderer.act(() => tree.root.findByType(TextInput).props.onChangeText(' squat '));
    expect(useExerciseCatalog).toHaveBeenLastCalledWith(expect.objectContaining({ query: ' squat ' }));
  });

  it('applies and removes filter chips with accessible controls', () => {
    const tree = renderScreen();
    renderer.act(() => tree.root.findByProps({ testID: 'exercise-filter-button' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'exercise-filter-button' }).props.accessibilityState).toEqual({ expanded: true });
    expect(tree.root.findByProps({ testID: 'filter-option-categoryCodes-STRENGTH' }).props.accessibilityState).toEqual({ checked: false });
    renderer.act(() => tree.root.findByProps({ testID: 'filter-option-categoryCodes-STRENGTH' }).props.onPress());
    expect(tree.root.findByProps({ testID: 'filter-option-categoryCodes-STRENGTH' }).props.accessibilityState).toEqual({ checked: true });
    renderer.act(() => tree.root.findByProps({ testID: 'apply-exercise-filters' }).props.onPress());
    expect(useExerciseCatalog).toHaveBeenLastCalledWith(expect.objectContaining({ categoryCodes: ['STRENGTH'] }));
    const chip = tree.root.findByProps({ testID: 'remove-filter-categoryCodes:STRENGTH' });
    expect(chip.props.accessibilityRole).toBe('button');
    renderer.act(() => chip.props.onPress());
    expect(useExerciseCatalog).toHaveBeenLastCalledWith(expect.objectContaining({ categoryCodes: [] }));
  });

  it('clears draft filters before applying them', () => {
    const tree = renderScreen();
    renderer.act(() => tree.root.findByProps({ testID: 'exercise-filter-button' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'filter-option-categoryCodes-STRENGTH' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'clear-exercise-filters' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ testID: 'apply-exercise-filters' }).props.onPress());
    expect(useExerciseCatalog).toHaveBeenLastCalledWith(expect.not.objectContaining({ categoryCodes: ['STRENGTH'] }));
  });
});
