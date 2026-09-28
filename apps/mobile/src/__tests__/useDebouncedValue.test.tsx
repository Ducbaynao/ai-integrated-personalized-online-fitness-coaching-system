import React from 'react';
import { Text } from 'react-native';
import renderer from 'react-test-renderer';
import { useDebouncedValue } from '@/hooks/use-debounced-value';

function Harness({ value }: { value: string }) {
  const debounced = useDebouncedValue(value, 350);
  return <Text testID="value">{debounced}</Text>;
}

describe('useDebouncedValue', () => {
  beforeEach(() => jest.useFakeTimers());
  afterEach(() => jest.useRealTimers());

  it('waits 350 ms and cancels obsolete rapid-search updates', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<Harness value="s" />);
    });
    renderer.act(() => tree!.update(<Harness value="sq" />));
    renderer.act(() => tree!.update(<Harness value="squat" />));

    expect(tree!.root.findByProps({ testID: 'value' }).props.children).toBe('s');
    renderer.act(() => jest.advanceTimersByTime(349));
    expect(tree!.root.findByProps({ testID: 'value' }).props.children).toBe('s');
    renderer.act(() => jest.advanceTimersByTime(1));
    expect(tree!.root.findByProps({ testID: 'value' }).props.children).toBe('squat');
  });
});

