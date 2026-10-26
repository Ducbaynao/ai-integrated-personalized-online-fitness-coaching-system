import { Platform } from 'react-native';
import Constants from 'expo-constants';
import * as Device from 'expo-device';
import * as SecureStore from 'expo-secure-store';
import { request } from '@/services/apiClient';
import { createCommandKey } from '@/services/coachingApi';

export const pushCategories = [
  ['CHAT', 'Tin nhắn'], ['COACHING', 'Huấn luyện'], ['WORKOUT', 'Lịch tập'],
  ['GOAL', 'Mục tiêu'], ['NUTRITION', 'Dinh dưỡng'],
  ['ATTENTION_REVIEW', 'Tín hiệu và đánh giá'], ['APPOINTMENT', 'Lịch hẹn'],
] as const;
export type PushCategory = (typeof pushCategories)[number][0];
export type PushPreference = { eventType: PushCategory; enabled: boolean; timezone: string;
  quietHoursStart: string | null; quietHoursEnd: string | null };
export type PushPreferenceInput = Omit<PushPreference, 'eventType'>;
export type PushDeliveryState = { status: 'QUEUED' | 'TICKET_ACCEPTED' | 'PROVIDER_ACCEPTED'
  | 'DEVICE_ACKNOWLEDGED' | 'FAILED' | 'SKIPPED'; attemptedAt: string | null;
  providerAcceptedAt: string | null; deviceAcknowledgedAt: string | null };

const DEVICE_KEY = 'push_device_key_v1';
async function deviceKey(): Promise<string> {
  let key = await SecureStore.getItemAsync(DEVICE_KEY);
  if (!key) { key = createCommandKey(); await SecureStore.setItemAsync(DEVICE_KEY, key); }
  return key;
}

async function registerDevice(): Promise<void> {
  if (Constants.appOwnership === 'expo') throw new Error('Push cần bản dựng phát triển; Expo Go không hỗ trợ nhận push trên Android.');
  if (!Device.isDevice || Platform.OS === 'web') throw new Error('Push cần thiết bị Android/iOS thật.');
  const projectId = Constants.easConfig?.projectId ?? Constants.expoConfig?.extra?.eas?.projectId;
  if (!projectId) throw new Error('Chưa cấu hình EAS project ID cho push trên bản dựng này.');
  // Do not evaluate native push code while Expo Go is loading the rest of the app.
  // eslint-disable-next-line @typescript-eslint/no-require-imports
  const Notifications = require('expo-notifications') as typeof import('expo-notifications');
  if (Platform.OS === 'android') await Notifications.setNotificationChannelAsync('updates', {
    name: 'Cập nhật', importance: Notifications.AndroidImportance.DEFAULT,
  });
  let permission = await Notifications.getPermissionsAsync();
  if (!permission.granted) permission = await Notifications.requestPermissionsAsync();
  if (!permission.granted) throw new Error('Thiết bị chưa cấp quyền thông báo.');
  const token = (await Notifications.getExpoPushTokenAsync({ projectId })).data;
  await request<void>(`/notifications/me/push/devices/${await deviceKey()}`, {
    method: 'PUT', body: { expoPushToken: token },
  });
}

export const pushApi = {
  list() { return request<PushPreference[]>('/notifications/me/push/preferences'); },
  deliveries(notificationId: string) {
    return request<PushDeliveryState[]>(`/notifications/me/push/deliveries/${encodeURIComponent(notificationId)}`);
  },
  async set(category: PushCategory, input: PushPreferenceInput) {
    if (input.enabled) await registerDevice();
    return request<PushPreference>(`/notifications/me/push/preferences/${category}`, { method: 'PUT', body: input });
  },
  async unregister() {
    const key = await SecureStore.getItemAsync(DEVICE_KEY);
    if (key) await request<void>(`/notifications/me/push/devices/${key}`, { method: 'DELETE' });
  },
  async acknowledgeReceived(notificationId: string) {
    const key = await SecureStore.getItemAsync(DEVICE_KEY);
    if (key) await request<void>(`/notifications/me/push/devices/${key}/received/${notificationId}`, { method: 'POST' });
  },
};
