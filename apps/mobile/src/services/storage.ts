import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

const ACCESS_TOKEN_KEY = 'auth_access_token';
const REFRESH_TOKEN_KEY = 'auth_refresh_token';

export interface StoredTokens {
  accessToken: string;
  refreshToken: string;
}

/**
 * In-memory fallback for web preview.
 *
 * Web browsers lack native hardware keystore/keychain equivalents.
 * Storing authentication tokens in localStorage, sessionStorage, unprotected
 * cookies, or IndexedDB introduces serious XSS token extraction vulnerabilities.
 *
 * To preserve strict security invariants, tokens on web are stored purely in memory.
 * Web sessions will reset upon page reload; this is an intentional security constraint
 * for development web preview until a backend-for-frontend (BFF) or HttpOnly cookie
 * architecture is introduced.
 */
const webMemoryStore = new Map<string, string>();

export const tokenStorage = {
  async saveTokens(accessToken: string, refreshToken: string): Promise<void> {
    if (Platform.OS === 'web') {
      webMemoryStore.set(ACCESS_TOKEN_KEY, accessToken);
      webMemoryStore.set(REFRESH_TOKEN_KEY, refreshToken);
      return;
    }

    await SecureStore.setItemAsync(ACCESS_TOKEN_KEY, accessToken);
    await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, refreshToken);
  },

  async getTokens(): Promise<StoredTokens | null> {
    if (Platform.OS === 'web') {
      const accessToken = webMemoryStore.get(ACCESS_TOKEN_KEY);
      const refreshToken = webMemoryStore.get(REFRESH_TOKEN_KEY);

      if (!accessToken || !refreshToken) {
        return null;
      }

      return { accessToken, refreshToken };
    }

    const accessToken = await SecureStore.getItemAsync(ACCESS_TOKEN_KEY);
    const refreshToken = await SecureStore.getItemAsync(REFRESH_TOKEN_KEY);

    if (!accessToken || !refreshToken) {
      return null;
    }

    return { accessToken, refreshToken };
  },

  async getAccessToken(): Promise<string | null> {
    if (Platform.OS === 'web') {
      return webMemoryStore.get(ACCESS_TOKEN_KEY) ?? null;
    }
    return SecureStore.getItemAsync(ACCESS_TOKEN_KEY);
  },

  async getRefreshToken(): Promise<string | null> {
    if (Platform.OS === 'web') {
      return webMemoryStore.get(REFRESH_TOKEN_KEY) ?? null;
    }
    return SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
  },

  async clearTokens(): Promise<void> {
    if (Platform.OS === 'web') {
      webMemoryStore.clear();
      return;
    }

    await Promise.all([
      SecureStore.deleteItemAsync(ACCESS_TOKEN_KEY),
      SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY),
    ]);
  },
};

const ACTIVE_CAPABILITY_KEY = 'active_mobile_capability';

export const capabilityStorage = {
  async saveActiveCapability(capability: string): Promise<void> {
    if (Platform.OS === 'web') {
      webMemoryStore.set(ACTIVE_CAPABILITY_KEY, capability);
      return;
    }
    await SecureStore.setItemAsync(ACTIVE_CAPABILITY_KEY, capability);
  },

  async getActiveCapability(): Promise<string | null> {
    if (Platform.OS === 'web') {
      return webMemoryStore.get(ACTIVE_CAPABILITY_KEY) ?? null;
    }
    return SecureStore.getItemAsync(ACTIVE_CAPABILITY_KEY);
  },

  async clearActiveCapability(): Promise<void> {
    if (Platform.OS === 'web') {
      webMemoryStore.delete(ACTIVE_CAPABILITY_KEY);
      return;
    }
    await SecureStore.deleteItemAsync(ACTIVE_CAPABILITY_KEY);
  },
};
