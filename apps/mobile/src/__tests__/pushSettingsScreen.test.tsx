import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import PushSettingsScreen from '@/features/chat/PushSettingsScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { pushApi, reminderApi } from '@/services/pushApi';

jest.mock('expo-router', () => ({ useRouter: () => ({ back: jest.fn() }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/workout-schedule-proposals/proposalTime', () => ({
  proposalTimezones: () => ['Asia/Ho_Chi_Minh'],
}));
jest.mock('@/services/pushApi', () => ({
  pushCategories: [['CHAT', 'Tin nhắn']],
  pushApi: { list: jest.fn(), set: jest.fn() },
  reminderApi: { list: jest.fn(), set: jest.fn() },
}));

describe('B08 push settings screen', () => {
  it('shows default OFF and requires an explicit IANA timezone before opt-in', async () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1' }, activeCapability: 'STUDENT' });
    (pushApi.list as jest.Mock).mockResolvedValue([]);
    (reminderApi.list as jest.Mock).mockResolvedValue([]);
    (reminderApi.set as jest.Mock).mockResolvedValue({ type: 'APPOINTMENT_REMINDER', enabled: true });
    (pushApi.set as jest.Mock).mockResolvedValue({ eventType: 'CHAT', enabled: true });
    const cache = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { gcTime: 0 } } });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={cache}><PushSettingsScreen /></QueryClientProvider>);
    });
    await renderer.act(async () => { await new Promise(resolve => setTimeout(resolve, 20)); });
    expect(JSON.stringify(tree.toJSON())).toContain('Đang tắt');
    await renderer.act(async () => {
      tree.root.findByProps({ accessibilityLabel: 'Bật nhắc lịch hẹn trong ứng dụng' }).props.onPress();
      await new Promise(resolve => setTimeout(resolve, 20));
    });
    expect(reminderApi.set).toHaveBeenCalledWith('APPOINTMENT_REMINDER', true);
    expect(pushApi.set).not.toHaveBeenCalled();
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Bật push Tin nhắn' }).props.onPress());
    expect(pushApi.set).not.toHaveBeenCalled();
    renderer.act(() => {
      tree.root.findByProps({ accessibilityLabel: 'Tìm múi giờ IANA' }).props.onChangeText('Ho_Chi_Minh');
    });
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Asia/Ho_Chi_Minh' }).props.onPress());
    expect(tree.root.findByProps({ accessibilityLabel: 'Asia/Ho_Chi_Minh' })).toBeDefined();
    expect(pushApi.set).not.toHaveBeenCalled();
    await cache.cancelQueries();
    renderer.act(() => tree.unmount());
    cache.clear();
  });
});
