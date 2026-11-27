import React from 'react';
import renderer from 'react-test-renderer';
import { AppButton } from '@/design-system/components/AppButton';
import { AppCard } from '@/design-system/components/AppCard';
import { NavigationAction } from '@/design-system/components/NavigationAction';

jest.mock('@/design-system/components/AppIcon', () => ({ AppIcon: () => null }));
const press = { nativeEvent: { timestamp: 123, pageX: 20, pageY: 20 }, preventDefault: jest.fn(), stopPropagation: jest.fn() };
function render(element: React.ReactElement) {
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(element); });
  return tree;
}

describe('Shared overview actions isolate native press events', () => {
  it.each(['button', 'navigation', 'retry'])('%s invokes its callback without changing default intent', kind => {
    const observed = jest.fn();
    const callback = (retryIntent = false) => observed(retryIntent);
    const tree = render(kind === 'button' ? <AppButton label="Thử lại cùng ý định" onPress={callback} />
      : kind === 'navigation' ? <NavigationAction title="Mở lịch" icon="calendar" accessibilityLabel="Mở lịch" description="Nhãn tiếng Việt có thể xuống dòng" onPress={callback} />
      : <AppCard state="error" title="Không thể tải" message="Giữ nguyên ngữ cảnh" onRetry={callback} />);
    const target = tree.root.findByProps({ accessibilityRole: 'button' });
    renderer.act(() => target.props.onPress(press));
    expect(observed).toHaveBeenCalledTimes(1);
    expect(observed).toHaveBeenCalledWith(false);
    renderer.act(() => tree.unmount());
  });

  it.each([{ disabled: true }, { loading: true }])('does not dispatch a queued native press when unavailable: %p', props => {
    const onPress = jest.fn();
    const tree = render(<AppButton label="Gửi" onPress={onPress} {...props} />);
    const target = tree.root.findByProps({ accessibilityRole: 'button' });
    renderer.act(() => target.props.onPress(press));
    expect(onPress).not.toHaveBeenCalled();
    expect(target.props.accessibilityState.disabled).toBe(true);
    renderer.act(() => tree.unmount());
  });

  it('exposes loading and retry state without turning a card into a press target', () => {
    const tree = render(<AppCard state="loading" title="Đang kiểm tra quyền" message="Vui lòng chờ" />);
    expect(tree.root.findByProps({ accessibilityRole: 'progressbar' }).props['aria-busy']).toBe(true);
    expect(tree.root.findAllByProps({ accessibilityRole: 'button' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });
});
