import { tokenStorage } from '@/services/storage';
import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

describe('tokenStorage', () => {
  const originalPlatformOS = Platform.OS;

  afterEach(() => {
    Platform.OS = originalPlatformOS;
  });

  describe('Native SecureStore path (iOS/Android)', () => {
    beforeEach(async () => {
      Platform.OS = 'ios';
      (SecureStore as unknown as { __resetStore: () => void }).__resetStore();
      await tokenStorage.clearTokens();
    });

    it('saves and retrieves access and refresh tokens using SecureStore', async () => {
      await tokenStorage.saveTokens('native-access-token', 'native-refresh-token');

      expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
        'auth_access_token',
        'native-access-token'
      );
      expect(SecureStore.setItemAsync).toHaveBeenCalledWith(
        'auth_refresh_token',
        'native-refresh-token'
      );

      const tokens = await tokenStorage.getTokens();
      expect(tokens).toEqual({
        accessToken: 'native-access-token',
        refreshToken: 'native-refresh-token',
      });

      const accessToken = await tokenStorage.getAccessToken();
      const refreshToken = await tokenStorage.getRefreshToken();
      expect(accessToken).toBe('native-access-token');
      expect(refreshToken).toBe('native-refresh-token');
    });

    it('returns null if one or both tokens are missing in SecureStore', async () => {
      expect(await tokenStorage.getTokens()).toBeNull();

      await SecureStore.setItemAsync('auth_access_token', 'orphan-access');
      expect(await tokenStorage.getTokens()).toBeNull();
    });

    it('clears both tokens from SecureStore', async () => {
      await tokenStorage.saveTokens('access-to-delete', 'refresh-to-delete');
      expect(await tokenStorage.getTokens()).not.toBeNull();

      await tokenStorage.clearTokens();
      expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith('auth_access_token');
      expect(SecureStore.deleteItemAsync).toHaveBeenCalledWith('auth_refresh_token');
      expect(await tokenStorage.getTokens()).toBeNull();
    });
  });

  describe('Web In-Memory path (web preview)', () => {
    beforeEach(async () => {
      Platform.OS = 'web';
      jest.clearAllMocks();
      await tokenStorage.clearTokens();
    });

    it('stores and retrieves tokens in memory without calling SecureStore', async () => {
      await tokenStorage.saveTokens('web-access-token', 'web-refresh-token');

      // Must NOT invoke native SecureStore on web
      expect(SecureStore.setItemAsync).not.toHaveBeenCalled();

      const tokens = await tokenStorage.getTokens();
      expect(tokens).toEqual({
        accessToken: 'web-access-token',
        refreshToken: 'web-refresh-token',
      });

      const accessToken = await tokenStorage.getAccessToken();
      const refreshToken = await tokenStorage.getRefreshToken();
      expect(accessToken).toBe('web-access-token');
      expect(refreshToken).toBe('web-refresh-token');
      expect(SecureStore.getItemAsync).not.toHaveBeenCalled();
    });

    it('returns null when tokens are not set or incomplete on web', async () => {
      expect(await tokenStorage.getTokens()).toBeNull();
      expect(await tokenStorage.getAccessToken()).toBeNull();
      expect(await tokenStorage.getRefreshToken()).toBeNull();
    });

    it('clears tokens in memory on web without calling SecureStore.deleteItemAsync', async () => {
      await tokenStorage.saveTokens('web-access-to-clear', 'web-refresh-to-clear');
      expect(await tokenStorage.getTokens()).not.toBeNull();

      await tokenStorage.clearTokens();
      expect(SecureStore.deleteItemAsync).not.toHaveBeenCalled();
      expect(await tokenStorage.getTokens()).toBeNull();
      expect(await tokenStorage.getAccessToken()).toBeNull();
      expect(await tokenStorage.getRefreshToken()).toBeNull();
    });
  });
});
