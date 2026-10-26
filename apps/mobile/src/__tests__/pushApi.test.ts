import { pushApi } from '@/services/pushApi';
import { request } from '@/services/apiClient';
import * as Notifications from 'expo-notifications';
import Constants from 'expo-constants';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: () => 'device-uuid' }));
jest.mock('expo-device', () => ({ isDevice: true }));
jest.mock('expo-constants', () => ({ __esModule: true, default: { appOwnership: 'standalone', easConfig: { projectId: 'project-id' } } }));
jest.mock('expo-notifications', () => ({
  AndroidImportance: { DEFAULT: 3 }, setNotificationChannelAsync: jest.fn(),
  getPermissionsAsync: jest.fn(), requestPermissionsAsync: jest.fn(), getExpoPushTokenAsync: jest.fn(),
}));
jest.mock('expo-secure-store', () => ({ getItemAsync: jest.fn(), setItemAsync: jest.fn() }));

describe('B08 push opt-in API', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (request as jest.Mock).mockResolvedValue({});
    (Notifications.getPermissionsAsync as jest.Mock).mockResolvedValue({ granted: true });
    (Notifications.getExpoPushTokenAsync as jest.Mock).mockResolvedValue({ data: 'ExpoPushToken[token]' });
  });
  it('leaves in-app independent, registers a device only on explicit opt-in, and sends timezone/quiet hours', async () => {
    await pushApi.list();
    await pushApi.set('CHAT', { enabled: false, timezone: 'Asia/Ho_Chi_Minh', quietHoursStart: null, quietHoursEnd: null });
    expect(Notifications.getExpoPushTokenAsync).not.toHaveBeenCalled();
    await pushApi.set('CHAT', { enabled: true, timezone: 'Asia/Ho_Chi_Minh',
      quietHoursStart: '22:00', quietHoursEnd: '07:00' });
    expect(request).toHaveBeenCalledWith('/notifications/me/push/devices/device-uuid', {
      method: 'PUT', body: { expoPushToken: 'ExpoPushToken[token]' },
    });
    expect(request).toHaveBeenLastCalledWith('/notifications/me/push/preferences/CHAT', {
      method: 'PUT', body: { enabled: true, timezone: 'Asia/Ho_Chi_Minh',
        quietHoursStart: '22:00', quietHoursEnd: '07:00' },
    });
  });
  it('does not register a device or enable push when Android permission is denied', async () => {
    (Notifications.getPermissionsAsync as jest.Mock).mockResolvedValue({ granted: false });
    (Notifications.requestPermissionsAsync as jest.Mock).mockResolvedValue({ granted: false });
    await expect(pushApi.set('CHAT', { enabled: true, timezone: 'Asia/Ho_Chi_Minh',
      quietHoursStart: null, quietHoursEnd: null })).rejects.toThrow();
    expect(Notifications.getExpoPushTokenAsync).not.toHaveBeenCalled();
    expect(request).not.toHaveBeenCalled();
  });
  it('keeps the in-app inbox usable in Expo Go without trying native push registration', async () => {
    const config = Constants as unknown as { appOwnership: string };
    config.appOwnership = 'expo';
    try {
      await expect(pushApi.set('CHAT', { enabled: true, timezone: 'Asia/Ho_Chi_Minh',
        quietHoursStart: null, quietHoursEnd: null })).rejects.toThrow('Expo Go');
      expect(Notifications.getExpoPushTokenAsync).not.toHaveBeenCalled();
      expect(request).not.toHaveBeenCalled();
      await pushApi.list();
      expect(request).toHaveBeenCalledWith('/notifications/me/push/preferences');
    } finally { config.appOwnership = 'standalone'; }
  });
});
