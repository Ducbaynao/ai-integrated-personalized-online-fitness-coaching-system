import { Platform } from 'react-native';
import * as SecureStore from 'expo-secure-store';

const ACCESS_TOKEN_KEY = 'auth_access_token';
const REFRESH_TOKEN_KEY = 'auth_refresh_token';
const ACTIVE_CAPABILITY_KEY = 'active_mobile_capability';

export interface StoredTokens {
  accessToken: string;
  refreshToken: string;
}

export interface StorageMutationOptions {
  /** Checked inside the serialized queue right before performing storage writes/deletes. */
  shouldApply?: () => boolean;
}

export class StaleStorageMutationError extends Error {
  constructor(message = 'Stale session storage mutation aborted.') {
    super(message);
    this.name = 'StaleStorageMutationError';
  }
}

function createSerialQueue() {
  let tail: Promise<void> = Promise.resolve();
  return function enqueue<T>(task: () => Promise<T>): Promise<T> {
    const run = tail.then(task, task);
    // Keep the queue healthy if any operation fails so subsequent session operations never stall.
    tail = run.then(
      () => undefined,
      () => undefined
    );
    return run;
  };
}

const enqueueTokenOp = createSerialQueue();
const enqueueCapabilityOp = createSerialQueue();

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
  saveTokens(
    accessToken: string,
    refreshToken: string,
    options?: StorageMutationOptions
  ): Promise<void> {
    return enqueueTokenOp(async () => {
      if (options?.shouldApply && !options.shouldApply()) {
        throw new StaleStorageMutationError();
      }

      if (Platform.OS === 'web') {
        webMemoryStore.set(ACCESS_TOKEN_KEY, accessToken);
        webMemoryStore.set(REFRESH_TOKEN_KEY, refreshToken);
        return;
      }

      await SecureStore.setItemAsync(ACCESS_TOKEN_KEY, accessToken);
      await SecureStore.setItemAsync(REFRESH_TOKEN_KEY, refreshToken);
    });
  },

  getTokens(): Promise<StoredTokens | null> {
    return enqueueTokenOp(async () => {
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
    });
  },

  getAccessToken(): Promise<string | null> {
    return enqueueTokenOp(async () => {
      if (Platform.OS === 'web') {
        return webMemoryStore.get(ACCESS_TOKEN_KEY) ?? null;
      }
      return SecureStore.getItemAsync(ACCESS_TOKEN_KEY);
    });
  },

  getRefreshToken(): Promise<string | null> {
    return enqueueTokenOp(async () => {
      if (Platform.OS === 'web') {
        return webMemoryStore.get(REFRESH_TOKEN_KEY) ?? null;
      }
      return SecureStore.getItemAsync(REFRESH_TOKEN_KEY);
    });
  },

  clearTokens(options?: StorageMutationOptions): Promise<void> {
    return enqueueTokenOp(async () => {
      if (options?.shouldApply && !options.shouldApply()) {
        return;
      }

      if (Platform.OS === 'web') {
        webMemoryStore.delete(ACCESS_TOKEN_KEY);
        webMemoryStore.delete(REFRESH_TOKEN_KEY);
        return;
      }

      const results = await Promise.allSettled([
        SecureStore.deleteItemAsync(ACCESS_TOKEN_KEY),
        SecureStore.deleteItemAsync(REFRESH_TOKEN_KEY),
      ]);
      const rejected = results.find(
        (result): result is PromiseRejectedResult => result.status === 'rejected'
      );
      if (rejected) {
        throw rejected.reason;
      }
    });
  },
};

export const capabilityStorage = {
  saveActiveCapability(
    capability: string,
    options?: StorageMutationOptions
  ): Promise<void> {
    return enqueueCapabilityOp(async () => {
      if (options?.shouldApply && !options.shouldApply()) {
        return;
      }

      if (Platform.OS === 'web') {
        webMemoryStore.set(ACTIVE_CAPABILITY_KEY, capability);
        return;
      }
      await SecureStore.setItemAsync(ACTIVE_CAPABILITY_KEY, capability);
    });
  },

  getActiveCapability(): Promise<string | null> {
    return enqueueCapabilityOp(async () => {
      if (Platform.OS === 'web') {
        return webMemoryStore.get(ACTIVE_CAPABILITY_KEY) ?? null;
      }
      return SecureStore.getItemAsync(ACTIVE_CAPABILITY_KEY);
    });
  },

  clearActiveCapability(options?: StorageMutationOptions): Promise<void> {
    return enqueueCapabilityOp(async () => {
      if (options?.shouldApply && !options.shouldApply()) {
        return;
      }

      if (Platform.OS === 'web') {
        webMemoryStore.delete(ACTIVE_CAPABILITY_KEY);
        return;
      }
      await SecureStore.deleteItemAsync(ACTIVE_CAPABILITY_KEY);
    });
  },
};
