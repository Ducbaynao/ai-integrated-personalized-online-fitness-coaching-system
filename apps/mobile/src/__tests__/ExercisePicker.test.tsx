import React, { useState } from 'react';
import { AccessibilityInfo, Text, TextInput } from 'react-native';
import renderer from 'react-test-renderer';
import { ExercisePicker } from '@/features/exercise/ExercisePicker';
import { useExerciseCatalog, useExerciseFilterMetadata } from '@/features/exercise/exerciseQueries';
import { ExerciseCatalogItem, ExerciseFilterMetadata, ExerciseSummary } from '@/types/exercise';

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

const secondExercise: ExerciseCatalogItem = {
  ...exercise,
  id: 'exercise-2',
  code: 'BENCH_PRESS',
  name: 'Bench Press',
  primaryMuscles: [{ code: 'CHEST', name: 'Ngực' }],
};

const metadata: ExerciseFilterMetadata = {
  categories: [{ code: 'STRENGTH', name: 'Sức mạnh' }],
  muscleGroups: [{ code: 'QUADRICEPS', name: 'Đùi trước', parentCode: null }],
  equipment: [{ code: 'BARBELL', name: 'Đòn tạ' }],
  tags: [],
  difficulties: ['INTERMEDIATE'],
  movementPatterns: ['SQUAT'],
};

const page = (items: ExerciseCatalogItem[] = [exercise, secondExercise]) => ({
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

function renderPicker(
  overrides: Partial<React.ComponentProps<typeof ExercisePicker>> = {}
) {
  const props: React.ComponentProps<typeof ExercisePicker> = {
    selectedExercise: null,
    onSelectionChange: jest.fn(),
    onConfirm: jest.fn(),
    onCancel: jest.fn(),
    ...overrides,
  };
  let tree: renderer.ReactTestRenderer;
  renderer.act(() => {
    tree = renderer.create(<ExercisePicker {...props} />);
  });
  mountedTrees.push(tree!);
  return { root: tree!.root, tree: tree!, props };
}

function ControlledPicker({
  initialSelection = null,
  excludedExerciseIds = [],
}: {
  initialSelection?: ExerciseSummary | null;
  excludedExerciseIds?: readonly string[];
}) {
  const [selection, setSelection] = useState<ExerciseSummary | null>(initialSelection);
  return (
    <ExercisePicker
      selectedExercise={selection}
      onSelectionChange={setSelection}
      onConfirm={jest.fn()}
      onCancel={jest.fn()}
      excludedExerciseIds={excludedExerciseIds}
    />
  );
}

describe('ExercisePicker', () => {
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
    jest.restoreAllMocks();
  });

  it('renders the controlled initial selection with radio semantics', () => {
    const { root } = renderPicker({ selectedExercise: exercise });
    const selectedRow = root.findByProps({ testID: 'exercise-row-exercise-1' });

    expect(selectedRow.props.accessibilityRole).toBe('radio');
    expect(selectedRow.props.accessibilityState).toEqual({ checked: true, disabled: false });
    expect(root.findByProps({ testID: 'exercise-picker-selection-summary' }).props.children).toBe('Back Squat');
  });

  it('reports a selection change and announces the original API exercise name', () => {
    const onSelectionChange = jest.fn();
    const announce = jest.spyOn(AccessibilityInfo, 'announceForAccessibility').mockImplementation(() => {});
    const { root } = renderPicker({ onSelectionChange });

    renderer.act(() => root.findByProps({ testID: 'exercise-row-exercise-1' }).props.onPress());

    expect(onSelectionChange).toHaveBeenCalledWith(exercise);
    expect(announce).toHaveBeenCalledWith('Đã chọn Back Squat');
  });

  it('confirms the typed ExerciseSummary and keeps cancel independent', () => {
    const onConfirm = jest.fn();
    const onCancel = jest.fn();
    const { root } = renderPicker({ selectedExercise: exercise, onConfirm, onCancel });

    renderer.act(() => root.findByProps({ testID: 'confirm-exercise-picker' }).props.onPress());
    renderer.act(() => root.findByProps({ testID: 'cancel-exercise-picker' }).props.onPress());

    expect(onConfirm).toHaveBeenCalledWith(exercise);
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it('disables and guards confirm when no exercise is selected', () => {
    const onConfirm = jest.fn();
    const { root } = renderPicker({ onConfirm });
    const confirm = root.findByProps({ testID: 'confirm-exercise-picker' });

    expect(confirm.props.disabled).toBe(true);
    expect(confirm.props.accessibilityState).toEqual({ disabled: true });
    renderer.act(() => confirm.props.onPress());
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('prevents excluded exercises from selection and confirmation', () => {
    const onSelectionChange = jest.fn();
    const onConfirm = jest.fn();
    const { root } = renderPicker({
      selectedExercise: exercise,
      onSelectionChange,
      onConfirm,
      excludedExerciseIds: [exercise.id],
    });
    const row = root.findByProps({ testID: 'exercise-row-exercise-1' });
    const confirm = root.findByProps({ testID: 'confirm-exercise-picker' });

    expect(row.props.disabled).toBe(true);
    expect(row.props.accessibilityState).toEqual({ checked: true, disabled: true });
    expect(row.props.accessibilityLabel).toContain('Bài tập này đã được thêm');
    expect(confirm.props.disabled).toBe(true);
    renderer.act(() => row.props.onPress());
    renderer.act(() => confirm.props.onPress());
    expect(onSelectionChange).not.toHaveBeenCalled();
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('retains a valid selection when search removes it from visible results', () => {
    (useExerciseCatalog as jest.Mock).mockImplementation((filters) =>
      catalogState({ data: page(filters.query ? [] : [exercise, secondExercise]) })
    );
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ControlledPicker />);
    });
    mountedTrees.push(tree!);

    renderer.act(() => tree!.root.findByProps({ testID: 'exercise-row-exercise-1' }).props.onPress());
    renderer.act(() => tree!.root.findByType(TextInput).props.onChangeText('Bench Press'));

    expect(tree!.root.findByProps({ testID: 'exercise-picker-selection-summary' }).props.children).toBe('Back Squat');
    expect(tree!.root.findByProps({ testID: 'confirm-exercise-picker' }).props.disabled).toBe(false);
  });

  it('retains a valid selection when an applied filter hides it', () => {
    (useExerciseCatalog as jest.Mock).mockImplementation((filters) =>
      catalogState({ data: page(filters.categoryCodes?.length ? [] : [exercise, secondExercise]) })
    );
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ControlledPicker initialSelection={exercise} />);
    });
    mountedTrees.push(tree!);

    renderer.act(() => tree!.root.findByProps({ testID: 'exercise-filter-button' }).props.onPress());
    renderer.act(() =>
      tree!.root.findByProps({ testID: 'filter-option-categoryCodes-STRENGTH' }).props.onPress()
    );
    renderer.act(() => tree!.root.findByProps({ testID: 'apply-exercise-filters' }).props.onPress());

    expect(tree!.root.findByProps({ testID: 'exercise-picker-selection-summary' }).props.children).toBe('Back Squat');
    expect(tree!.root.findByProps({ testID: 'confirm-exercise-picker' }).props.disabled).toBe(false);
  });

  it('renders loading, empty, no-result and retry states through shared catalog content', () => {
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ data: undefined, isPending: true }));
    let rendered = renderPicker({ selectedExercise: exercise });
    expect(rendered.root.findByProps({ testID: 'exercise-catalog-loading' })).toBeDefined();
    expect(rendered.root.findByProps({ testID: 'confirm-exercise-picker' }).props.disabled).toBe(true);

    renderer.act(() => rendered.tree.unmount());
    mountedTrees.splice(mountedTrees.indexOf(rendered.tree), 1);
    (useExerciseCatalog as jest.Mock).mockReturnValue(catalogState({ data: page([]) }));
    rendered = renderPicker();
    expect(rendered.root.findByProps({ testID: 'exercise-empty-catalog' })).toBeDefined();
    renderer.act(() => rendered.root.findByType(TextInput).props.onChangeText('deadlift'));
    expect(rendered.root.findByProps({ testID: 'exercise-no-results' })).toBeDefined();

    renderer.act(() => rendered.tree.unmount());
    mountedTrees.splice(mountedTrees.indexOf(rendered.tree), 1);
    const refetch = jest.fn();
    (useExerciseCatalog as jest.Mock).mockReturnValue(
      catalogState({ data: undefined, isError: true, error: new Error('offline'), refetch })
    );
    rendered = renderPicker();
    renderer.act(() => rendered.root.findByProps({ testID: 'retry-exercise-catalog' }).props.onPress());
    expect(refetch).toHaveBeenCalledTimes(1);
  });

  it('keeps stale data, pagination states and media-unavailable presentation', () => {
    (useExerciseCatalog as jest.Mock).mockReturnValue(
      catalogState({ isRefetchError: true, isFetchingNextPage: true, error: new Error('offline') })
    );
    const { root } = renderPicker();

    expect(root.findByProps({ testID: 'exercise-background-error' })).toBeDefined();
    expect(root.findByProps({ testID: 'exercise-load-more-indicator' })).toBeDefined();
    expect(root.findByProps({ testID: 'exercise-media-exercise-1' }).props.accessibilityLabel).toBe(
      'Bài tập chưa có nội dung hướng dẫn'
    );
  });

  it('shows and retries a load-more error without losing the current selection', () => {
    const fetchNextPage = jest.fn().mockResolvedValue(undefined);
    (useExerciseCatalog as jest.Mock).mockReturnValue(
      catalogState({ hasNextPage: true, isFetchNextPageError: true, fetchNextPage })
    );
    const { root } = renderPicker({ selectedExercise: exercise });

    expect(root.findByProps({ testID: 'exercise-picker-selection-summary' }).props.children).toBe('Back Squat');
    renderer.act(() => root.findByProps({ testID: 'retry-load-more' }).props.onPress());
    expect(fetchNextPage).toHaveBeenCalledTimes(1);
  });

  it('disables a previously valid selection when it becomes excluded', () => {
    const onConfirm = jest.fn();
    const initialProps = {
      selectedExercise: exercise,
      onSelectionChange: jest.fn(),
      onConfirm,
      onCancel: jest.fn(),
    };
    const rendered = renderPicker(initialProps);
    expect(rendered.root.findByProps({ testID: 'confirm-exercise-picker' }).props.disabled).toBe(false);

    renderer.act(() => {
      rendered.tree.update(<ExercisePicker {...initialProps} excludedExerciseIds={[exercise.id]} />);
    });

    const confirm = rendered.root.findByProps({ testID: 'confirm-exercise-picker' });
    expect(confirm.props.disabled).toBe(true);
    expect(rendered.root.findByProps({ testID: 'exercise-picker-selection-summary' }).props.children).toBe(
      'Bài tập này đã được thêm'
    );
    renderer.act(() => confirm.props.onPress());
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('uses Vietnamese controls while preserving English exercise names', () => {
    const { root } = renderPicker({ selectedExercise: exercise });
    const text = root.findAllByType(Text).map((node) => node.props.children);

    expect(text).toContain('Chọn bài tập');
    expect(text).toContain('Xác nhận');
    expect(text).toContain('Hủy');
    expect(text).toContain('Back Squat');
    expect(root.findByProps({ testID: 'cancel-exercise-picker' }).props.accessibilityLabel).toBe('Hủy chọn bài tập');
    expect(root.findByProps({ testID: 'confirm-exercise-picker' }).props.accessibilityLabel).toBe(
      'Xác nhận bài tập đã chọn'
    );
  });
});
