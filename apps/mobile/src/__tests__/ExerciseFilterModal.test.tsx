import React from 'react';
import { Text } from 'react-native';
import renderer from 'react-test-renderer';
import { ExerciseFilterModal } from '@/features/exercise/components/ExerciseFilterModal';
import { EXERCISE_FILTER_LIMIT_MESSAGE } from '@/features/exercise/exerciseMessages';
import {
  EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION,
  ExerciseCatalogFilters,
  ExerciseFilterMetadata,
} from '@/types/exercise';

const categoryOptions = Array.from(
  { length: EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION + 1 },
  (_, index) => ({ code: `CATEGORY_${index + 1}`, name: `Danh mục ${index + 1}` })
);

const muscleOptions = Array.from(
  { length: EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION + 1 },
  (_, index) => ({ code: `MUSCLE_${index + 1}`, name: `Nhóm cơ ${index + 1}`, parentCode: null })
);

const metadata: ExerciseFilterMetadata = {
  categories: categoryOptions,
  muscleGroups: muscleOptions,
  equipment: [],
  tags: [],
  difficulties: [],
  movementPatterns: [],
};

const mountedTrees: renderer.ReactTestRenderer[] = [];

function renderModal(filters: ExerciseCatalogFilters = {}) {
  const onApply = jest.fn();
  let tree: renderer.ReactTestRenderer;
  renderer.act(() => {
    tree = renderer.create(
      <ExerciseFilterModal
        visible
        filters={filters}
        metadata={metadata}
        isLoading={false}
        isError={false}
        onRetryMetadata={jest.fn()}
        onApply={onApply}
        onClose={jest.fn()}
      />
    );
  });
  mountedTrees.push(tree!);
  return { root: tree!.root, onApply };
}

describe('ExerciseFilterModal filter limits', () => {
  afterEach(() => {
    renderer.act(() => {
      mountedTrees.splice(0).forEach((tree) => tree.unmount());
    });
  });

  it('allows exactly 20 selections and disables the 21st option accessibly', () => {
    const { root, onApply } = renderModal();

    renderer.act(() => {
      categoryOptions.slice(0, EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION).forEach((option) => {
        root.findByProps({ testID: `filter-option-categoryCodes-${option.code}` }).props.onPress();
      });
    });

    const twentyFirst = root.findByProps({
      testID: 'filter-option-categoryCodes-CATEGORY_21',
    });
    expect(twentyFirst.props.disabled).toBe(true);
    expect(twentyFirst.props.accessibilityState).toEqual({ checked: false, disabled: true });
    expect(
      root.findByProps({ testID: 'filter-option-categoryCodes-CATEGORY_20' }).props
        .accessibilityState
    ).toEqual({ checked: true, disabled: false });

    renderer.act(() => root.findByProps({ testID: 'apply-exercise-filters' }).props.onPress());
    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({
        categoryCodes: categoryOptions
          .slice(0, EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION)
          .map((option) => option.code),
      })
    );
  });

  it('keeps selected options removable and re-enables another option after deselection', () => {
    const initialCodes = categoryOptions
      .slice(0, EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION)
      .map((option) => option.code);
    const { root, onApply } = renderModal({ categoryCodes: initialCodes });

    expect(
      root.findByProps({ testID: 'filter-option-categoryCodes-CATEGORY_1' }).props
        .accessibilityState
    ).toEqual({ checked: true, disabled: false });
    expect(
      root.findByProps({ testID: 'filter-option-categoryCodes-CATEGORY_21' }).props.disabled
    ).toBe(true);

    renderer.act(() =>
      root.findByProps({ testID: 'filter-option-categoryCodes-CATEGORY_1' }).props.onPress()
    );
    const newlyAvailable = root.findByProps({
      testID: 'filter-option-categoryCodes-CATEGORY_21',
    });
    expect(newlyAvailable.props.disabled).toBe(false);

    renderer.act(() => newlyAvailable.props.onPress());
    renderer.act(() => root.findByProps({ testID: 'apply-exercise-filters' }).props.onPress());

    const applied = onApply.mock.calls[0][0].categoryCodes;
    expect(applied).toHaveLength(EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION);
    expect(applied).not.toContain('CATEGORY_1');
    expect(applied).toContain('CATEGORY_21');
  });

  it('applies the limit independently to each filter dimension', () => {
    const { root, onApply } = renderModal({
      categoryCodes: categoryOptions
        .slice(0, EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION)
        .map((option) => option.code),
    });

    const firstMuscle = root.findByProps({
      testID: 'filter-option-muscleGroupCodes-MUSCLE_1',
    });
    expect(firstMuscle.props.accessibilityState).toEqual({ checked: false, disabled: false });
    renderer.act(() => firstMuscle.props.onPress());
    renderer.act(() => root.findByProps({ testID: 'apply-exercise-filters' }).props.onPress());

    expect(onApply).toHaveBeenCalledWith(
      expect.objectContaining({
        categoryCodes: expect.arrayContaining(['CATEGORY_20']),
        muscleGroupCodes: ['MUSCLE_1'],
      })
    );
  });

  it('shows the Vietnamese limit message as a live alert', () => {
    const { root } = renderModal({
      categoryCodes: categoryOptions
        .slice(0, EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION)
        .map((option) => option.code),
    });
    const message = root.findByProps({ testID: 'filter-limit-message-categoryCodes' });

    expect(message.props.accessibilityRole).toBe('alert');
    expect(message.props.accessibilityLiveRegion).toBe('polite');
    expect(message.findByType(Text).props.children).toBe(EXERCISE_FILTER_LIMIT_MESSAGE);
  });

  it('does not apply an externally supplied over-limit draft', () => {
    const { root, onApply } = renderModal({
      categoryCodes: categoryOptions.map((option) => option.code),
    });
    const applyButton = root.findByProps({ testID: 'apply-exercise-filters' });

    expect(applyButton.props.disabled).toBe(true);
    expect(applyButton.props.accessibilityState).toEqual({ disabled: true });
    renderer.act(() => applyButton.props.onPress());
    expect(onApply).not.toHaveBeenCalled();
  });
});
