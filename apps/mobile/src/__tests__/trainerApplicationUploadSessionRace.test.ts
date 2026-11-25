import * as SecureStore from 'expo-secure-store';
import { authApi, invalidateAuthSession, invalidateCapabilitySession } from '@/services/apiClient';
import { tokenStorage } from '@/services/storage';
import { trainerApplicationApi } from '@/services/trainerApplicationApi';
import { ApiError, CurrentUserResponse, TokenPairResponse } from '@/types/auth';

const originalFetch = globalThis.fetch;

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function makeUser(id: string): CurrentUserResponse {
  return {
    id,
    email: `${id}@example.com`,
    displayName: `User ${id}`,
    status: 'ACTIVE',
    preferredLocale: 'vi-VN',
    timezone: 'Asia/Ho_Chi_Minh',
    emailVerifiedAt: '2026-09-20T08:00:00Z',
    createdAt: '2026-09-20T08:00:00Z',
    phoneNumber: null,
    roles: ['TRAINER'],
    permissions: [],
    capabilities: {
      hasStudentProfile: true,
      hasTrainerProfile: true,
      canCoach: false,
    },
    settings: {
      weekStartsOn: 1,
      measurementSystem: 'METRIC',
      accessibilityPreferences: {},
      privacyPreferences: {},
    },
  };
}

function makeTokenPair(accessToken: string, refreshToken: string, userId: string): TokenPairResponse {
  return {
    tokenType: 'Bearer',
    accessToken,
    accessTokenExpiresAt: '2026-11-25T12:00:00Z',
    refreshToken,
    refreshTokenExpiresAt: '2026-12-25T12:00:00Z',
    user: makeUser(userId),
  };
}

function jsonResponse(status: number, body: unknown): Response {
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: () => 'application/json' },
    json: async () => body,
    text: async () => JSON.stringify(body),
  } as unknown as Response;
}

describe('trainerApplicationApi document upload session/capability binding (real apiClient + storage)', () => {
  let secureStoreState: Map<string, string>;

  beforeEach(() => {
    jest.clearAllMocks();
    invalidateAuthSession();
    secureStoreState = new Map<string, string>();
    (SecureStore.setItemAsync as jest.Mock).mockImplementation(async (key: string, value: string) => {
      secureStoreState.set(key, value);
    });
    (SecureStore.getItemAsync as jest.Mock).mockImplementation(async (key: string) => {
      return secureStoreState.get(key) ?? null;
    });
    (SecureStore.deleteItemAsync as jest.Mock).mockImplementation(async (key: string) => {
      secureStoreState.delete(key);
    });
  });

  afterEach(() => {
    globalThis.fetch = originalFetch;
    jest.restoreAllMocks();
  });

  it('blocks uploading file from session A under session B credentials when account switches during pending local file read', async () => {
    await tokenStorage.saveTokens('token-A', 'refresh-A');
    const localRead = deferred<{ ok: boolean; blob: () => Promise<Blob> }>();
    const sentUploads: { url: string; auth?: string; body: unknown }[] = [];

    globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('file:')) {
        return localRead.promise as unknown as Response;
      }
      if (url.endsWith('/auth/sessions') && init?.method === 'POST') {
        return jsonResponse(200, makeTokenPair('token-B', 'refresh-B', 'user-B'));
      }
      if (url.endsWith('/auth/sessions') && init?.method === 'DELETE') {
        return jsonResponse(204, undefined);
      }
      if (url.endsWith('/trainer-applications/documents')) {
        sentUploads.push({
          url,
          auth: (init?.headers as Record<string, string> | undefined)?.Authorization,
          body: init?.body,
        });
        return jsonResponse(200, { id: 'doc-should-never-send' });
      }
      throw new Error(`Unexpected fetch URL: ${url}`);
    }) as unknown as typeof fetch;

    const pendingUpload = trainerApplicationApi.uploadDocument('file:///A-private.pdf', 'A-private.pdf', 'application/pdf');
    await authApi.logout();
    await authApi.login({ email: 'b@example.com', password: 'Secret123!' });

    localRead.resolve({
      ok: true,
      blob: async () => new Blob(['private-A-bytes'], { type: 'application/pdf' }),
    });

    await expect(pendingUpload).rejects.toThrow(ApiError);
    expect(sentUploads).toHaveLength(0);
    expect(await tokenStorage.getAccessToken()).toBe('token-B');
  });

  it('blocks stale upload when user logs out and logs back into the same account A during pending file read', async () => {
    await tokenStorage.saveTokens('token-A-1', 'refresh-A-1');
    const localRead = deferred<{ ok: boolean; blob: () => Promise<Blob> }>();
    const sentUploads: unknown[] = [];

    globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('file:')) {
        return localRead.promise as unknown as Response;
      }
      if (url.endsWith('/auth/sessions') && init?.method === 'POST') {
        return jsonResponse(200, makeTokenPair('token-A-2', 'refresh-A-2', 'user-A'));
      }
      if (url.endsWith('/auth/sessions') && init?.method === 'DELETE') {
        return jsonResponse(204, undefined);
      }
      if (url.endsWith('/trainer-applications/documents')) {
        sentUploads.push(init);
        return jsonResponse(200, { id: 'doc-stale' });
      }
      throw new Error(`Unexpected fetch URL: ${url}`);
    }) as unknown as typeof fetch;

    const pendingUpload = trainerApplicationApi.uploadDocument('file:///A-old.pdf', 'A-old.pdf', 'application/pdf');
    await authApi.logout();
    await authApi.login({ email: 'a@example.com', password: 'Secret123!' });

    localRead.resolve({
      ok: true,
      blob: async () => new Blob(['old-A-bytes'], { type: 'application/pdf' }),
    });

    await expect(pendingUpload).rejects.toThrow(ApiError);
    expect(sentUploads).toHaveLength(0);
  });

  it('blocks stale upload when capability changes or session expires while reading tokenStorage inside apiClient.request', async () => {
    const tokenRead = deferred<string | null>();
    jest.spyOn(tokenStorage, 'getAccessToken').mockImplementation(() => tokenRead.promise);
    const sentUploads: unknown[] = [];

    globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('file:')) {
        return {
          ok: true,
          blob: async () => new Blob(['cert-bytes'], { type: 'application/pdf' }),
        } as unknown as Response;
      }
      if (url.endsWith('/trainer-applications/documents')) {
        sentUploads.push(init);
        return jsonResponse(200, { id: 'doc-stale-cap' });
      }
      throw new Error(`Unexpected fetch URL: ${url}`);
    }) as unknown as typeof fetch;

    const pendingCapUpload = trainerApplicationApi.uploadDocument('file:///cap.pdf', 'cap.pdf', 'application/pdf');
    // Allow local fetch/blob promises to settle until request() awaits tokenStorage.getAccessToken
    await Promise.resolve();
    await Promise.resolve();
    invalidateCapabilitySession();
    tokenRead.resolve('token-after-cap-change');

    await expect(pendingCapUpload).rejects.toThrow(ApiError);
    expect(sentUploads).toHaveLength(0);
  });

  it('uploads file with matching file/body/name/type and Authorization in the same session without 401 refresh-retry', async () => {
    await tokenStorage.saveTokens('token-A-valid', 'refresh-A-valid');
    const sentUploads: { url: string; method?: string; auth?: string; body?: FormData }[] = [];

    globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('file:')) {
        return {
          ok: true,
          blob: async () => new Blob(['valid-cert-bytes'], { type: 'application/pdf' }),
        } as unknown as Response;
      }
      if (url.endsWith('/trainer-applications/documents')) {
        sentUploads.push({
          url,
          method: init?.method,
          auth: (init?.headers as Record<string, string> | undefined)?.Authorization,
          body: init?.body as FormData,
        });
        return jsonResponse(200, {
          id: 'doc-ok-1',
          originalFilename: 'cert.pdf',
          contentType: 'application/pdf',
          sizeBytes: 16,
          scanStatus: 'CLEAN',
          createdAt: '2026-11-25T10:00:00Z',
        });
      }
      throw new Error(`Unexpected fetch URL: ${url}`);
    }) as unknown as typeof fetch;

    const res = await trainerApplicationApi.uploadDocument('file:///cert.pdf', 'cert.pdf', 'application/pdf');
    expect(res.id).toBe('doc-ok-1');
    expect(sentUploads).toHaveLength(1);
    expect(sentUploads[0].method).toBe('POST');
    expect(sentUploads[0].auth).toBe('Bearer token-A-valid');
    const uploadedPart = sentUploads[0].body?.get('file') as File | Blob;
    expect(uploadedPart).toBeTruthy();
    expect(uploadedPart.type).toBe('application/pdf');
    expect(await uploadedPart.text()).toBe('valid-cert-bytes');
  });
});
