import React from 'react';
import { ActivityIndicator, StyleSheet } from 'react-native';
import renderer from 'react-test-renderer';
import { AppButton } from '@/design-system/components/AppButton';
import { lightSemanticColors as theme } from '@/design-system/tokens/colors';

jest.mock('@/design-system/components/AppIcon', () => ({ AppIcon: () => null }));

function renderButton(props: Partial<React.ComponentProps<typeof AppButton>> = {}) {
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<AppButton label="Hành động có nhãn tiếng Việt dài" onPress={jest.fn()} {...props} />); });
  return { tree, button: tree.root.findByProps({ accessibilityRole: 'button' }) };
}

describe('AppButton semantics and visual states', () => {
  it.each(['primary', 'secondary', 'ghost', 'danger'] as const)('%s keeps its label and callback', variant => {
    const onPress = jest.fn();
    const { tree, button } = renderButton({ variant, onPress });
    expect(button.props.accessibilityRole).toBe('button');
    expect(button.props.accessibilityLabel).toBe('Hành động có nhãn tiếng Việt dài');
    expect(button.props.accessibilityState).toEqual({ disabled: false, busy: false });
    expect(button.props.disabled).toBe(false);
    renderer.act(() => button.props.onPress());
    expect(onPress).toHaveBeenCalledTimes(1);
    const style = StyleSheet.flatten(button.props.style({ pressed: false }));
    expect(style.minHeight).toBeGreaterThanOrEqual(44);
    expect(style.minWidth).toBeGreaterThanOrEqual(44);
    expect(style.backgroundColor).toBe(variant === 'primary' ? theme.primary : variant === 'danger' ? theme.dangerPressed : variant === 'secondary' ? theme.surface : 'transparent');
    renderer.act(() => tree.unmount());
  });

  it('exposes loading as busy and disables the native action while keeping its label', () => {
    const { tree, button } = renderButton({ loading: true });
    expect(button.props.disabled).toBe(true);
    expect(button.props.accessibilityState).toEqual({ disabled: true, busy: true });
    expect(button.props['aria-busy']).toBe(true);
    expect(tree.root.findByType(ActivityIndicator)).toBeDefined();
    expect(JSON.stringify(tree.toJSON())).toContain('Hành động có nhãn tiếng Việt dài');
    renderer.act(() => tree.unmount());
  });

  it('distinguishes disabled, pressed and keyboard focus states', () => {
    const { tree, button } = renderButton();
    expect(StyleSheet.flatten(button.props.style({ pressed: true })).backgroundColor).toBe(theme.primaryPressed);
    renderer.act(() => button.props.onFocus());
    expect(StyleSheet.flatten(tree.root.findByProps({ accessibilityRole: 'button' }).props.style({ pressed: false })).borderColor).toBe(theme.textPrimary);
    renderer.act(() => tree.update(<AppButton label="Đã khóa" onPress={jest.fn()} disabled />));
    const disabled = tree.root.findByProps({ accessibilityRole: 'button' });
    expect(disabled.props.disabled).toBe(true);
    expect(disabled.props.accessibilityState).toEqual({ disabled: true, busy: false });
    expect(StyleSheet.flatten(disabled.props.style({ pressed: false })).backgroundColor).toBe(theme.surfaceSubtle);
    renderer.act(() => tree.unmount());
  });
});
