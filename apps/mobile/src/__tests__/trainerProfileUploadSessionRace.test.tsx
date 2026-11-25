import React from 'react';
import { Pressable, View } from 'react-native';
import renderer from 'react-test-renderer';
import * as SecureStore from 'expo-secure-store';
import * as DocumentPicker from 'expo-document-picker';
import { AuthProvider, useAuth } from '@/features/auth/AuthContext';
import { TrainerProfileScreen } from '@/features/trainer/TrainerProfileScreen';
import { invalidateAuthSession } from '@/services/apiClient';
import { queryClient } from '@/services/queryClient';
import { tokenStorage } from '@/services/storage';
import { trainerApplicationApi } from '@/services/trainerApplicationApi';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { CurrentUserResponse, TokenPairResponse } from '@/types/auth';
import { TrainerProfileResponse } from '@/types/trainer';

jest.mock('expo-router', () => ({
  useRouter: () => ({
    replace: jest.fn(),
    push: jest.fn(),
  }),
}));

jest.mock('expo-document-picker', () => ({
  getDocumentAsync: jest.fn(),
}));

jest.mock('@/services/pushApi', () => ({
  pushApi: {
    unregister: jest.fn().mockResolvedValue(undefined),
  },
}));

jest.mock('@/services/trainerProfileApi', () => ({
  trainerProfileApi: {
    createTrainerProfile: jest.fn(),
    getMyTrainerProfile: jest.fn(),
    updateMyTrainerProfile: jest.fn(),
  },
}));

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

function makeDualRoleUser(id: string): CurrentUserResponse {
  return {
    id,
    email: `${id}@example.com`,
    displayName: `Trainer ${id}`,
    status: 'ACTIVE',
    preferredLocale: 'vi-VN',
    timezone: 'Asia/Ho_Chi_Minh',
    emailVerifiedAt: '2026-09-20T08:00:00Z',
    createdAt: '2026-09-20T08:00:00Z',
    phoneNumber: null,
    roles: ['STUDENT', 'TRAINER'],
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

function makeTokenPair(accessToken: string, refreshToken: string, user: CurrentUserResponse): TokenPairResponse {
  return {
    tokenType: 'Bearer',
    accessToken,
    accessTokenExpiresAt: '2026-11-25T12:00:00Z',
    refreshToken,
    refreshTokenExpiresAt: '2026-12-25T12:00:00Z',
    user,
  };
}

function makeUnsubmittedProfile(userId: string): TrainerProfileResponse {
  return {
    userId,
    publicSlug: `coach-${userId}`,
    bio: 'Certification holder',
    yearsExperience: 3,
    acceptingStudents: false,
    verificationStatus: 'NOT_SUBMITTED',
    activityStatus: 'ACTIVE',
    coachingEligibility: {
      eligible: false,
      blockingReasons: ['APPLICATION_NOT_SUBMITTED'],
    },
    verifiedAt: null,
    createdAt: '2026-10-01T00:00:00Z',
    updatedAt: '2026-10-01T00:00:00Z',
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

interface AuthHandle {
  login: ReturnType<typeof useAuth>['login'];
  logout: ReturnType<typeof useAuth>['logout'];
  setActiveCapability: ReturnType<typeof useAuth>['setActiveCapability'];
}

function Harness({ onReady }: { onReady: (handle: AuthHandle) => void }) {
  const { login, logout, setActiveCapability } = useAuth();
  onReady({ login, logout, setActiveCapability });
  return (
    <View>
      <Pressable testID="auth-harness-noop" onPress={() => {}} />
      <TrainerProfileScreen />
    </View>
  );
}

describe('TrainerProfileScreen upload UI end-to-end session/capability/operation binding (real stack)', () => {
  let secureStoreState: Map<string, string>;
  let authHandle!: AuthHandle;
  let activeFetchUser: CurrentUserResponse;
  let sentDocumentUploads: { auth?: string; body?: FormData; url: string }[];
  let uploadResponder: (init?: RequestInit) => Promise<Response>;

  beforeEach(async () => {
    jest.clearAllMocks();
    (DocumentPicker.getDocumentAsync as jest.Mock).mockReset();
    invalidateAuthSession();
    queryClient.clear();
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

    activeFetchUser = makeDualRoleUser('user-a');
    sentDocumentUploads = [];
    uploadResponder = async () =>
      jsonResponse(200, {
        id: 'doc-default',
        originalFilename: 'cert.pdf',
        contentType: 'application/pdf',
        sizeBytes: 1024,
        scanStatus: 'CLEAN',
        createdAt: '2026-11-25T10:00:00Z',
      });

    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockImplementation(async () =>
      makeUnsubmittedProfile(activeFetchUser.id),
    );

    globalThis.fetch = jest.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.startsWith('file:')) {
        return {
          ok: true,
          blob: async () => new Blob(['private-file-bytes'], { type: 'application/pdf' }),
        } as unknown as Response;
      }
      if (url.endsWith('/users/me')) {
        return jsonResponse(200, activeFetchUser);
      }
      if (url.endsWith('/auth/sessions') && init?.method === 'DELETE') {
        return jsonResponse(204, undefined);
      }
      if (url.endsWith('/auth/sessions') && init?.method === 'POST') {
        return jsonResponse(200, makeTokenPair(`token-${activeFetchUser.id}-new`, `refresh-${activeFetchUser.id}-new`, activeFetchUser));
      }
      if (url.endsWith('/trainer-applications/me/current')) {
        return jsonResponse(404, { errorCode: 'TRAINER_APPLICATION_NOT_FOUND', message: 'Not found' });
      }
      if (url.endsWith('/trainer-applications/documents')) {
        sentDocumentUploads.push({
          url,
          auth: (init?.headers as Record<string, string> | undefined)?.Authorization,
          body: init?.body as FormData | undefined,
        });
        return uploadResponder(init);
      }
      throw new Error(`Unexpected fetch URL: ${url}`);
    }) as unknown as typeof fetch;
  });

  afterAll(() => {
    globalThis.fetch = originalFetch;
  });

  async function mountScreenAs(user: CurrentUserResponse, token: string, capability: 'TRAINER' | 'STUDENT' = 'TRAINER') {
    activeFetchUser = user;
    await tokenStorage.saveTokens(token, `refresh-${user.id}`);
    await SecureStore.setItemAsync('fitness_active_capability', capability);
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <AuthProvider>
          <Harness onReady={(h) => { authHandle = h; }} />
        </AuthProvider>,
      );
    });
    return tree;
  }

  it('A: picker pending in session A -> switch to session B -> picker returns A file -> does NOT upload under B or mutate UI', async () => {
    const userA = makeDualRoleUser('user-a');
    const userB = makeDualRoleUser('user-b');
    const tree = await mountScreenAs(userA, 'token-a');

    const pendingPicker = deferred<DocumentPicker.DocumentPickerResult>();
    (DocumentPicker.getDocumentAsync as jest.Mock).mockReturnValueOnce(pendingPicker.promise);
    const uploadSpy = jest.spyOn(trainerApplicationApi, 'uploadDocument');

    let pickPromise!: Promise<void>;
    renderer.act(() => {
      pickPromise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
    });

    await renderer.act(async () => {
      await authHandle.logout();
      activeFetchUser = userB;
      await authHandle.login({ email: 'b@example.com', password: 'Secret123!' });
    });

    await renderer.act(async () => {
      pendingPicker.resolve({
        canceled: false,
        assets: [{ uri: 'file:///a-secret.pdf', name: 'a-secret.pdf', mimeType: 'application/pdf', size: 1024, lastModified: 0 }],
      });
      await pickPromise;
    });

    expect(sentDocumentUploads).toHaveLength(0);
    expect(uploadSpy).not.toHaveBeenCalled();
    expect(tree.root.findAllByProps({ testID: 'uploaded-doc-item-doc-default' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'trainer-document-upload-error' })).toHaveLength(0);

    renderer.act(() => tree.unmount());
  });

  it('B: picker pending in A -> logout and login back into same account A -> stale picker operation is ignored', async () => {
    const userA = makeDualRoleUser('user-a');
    const tree = await mountScreenAs(userA, 'token-a-first');

    const pendingPicker = deferred<DocumentPicker.DocumentPickerResult>();
    (DocumentPicker.getDocumentAsync as jest.Mock).mockReturnValueOnce(pendingPicker.promise);

    let pickPromise!: Promise<void>;
    renderer.act(() => {
      pickPromise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
    });

    await renderer.act(async () => {
      await authHandle.logout();
      activeFetchUser = userA;
      await authHandle.login({ email: 'a@example.com', password: 'Secret123!' });
    });

    await renderer.act(async () => {
      pendingPicker.resolve({
        canceled: false,
        assets: [{ uri: 'file:///a-stale.pdf', name: 'a-stale.pdf', mimeType: 'application/pdf', size: 512, lastModified: 0 }],
      });
      await pickPromise;
    });

    expect(sentDocumentUploads).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'uploaded-doc-item-doc-default' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'trainer-document-upload-error' })).toHaveLength(0);

    renderer.act(() => tree.unmount());
  });

  it('C: upload HTTP response pending -> capability switches TRAINER -> STUDENT -> TRAINER -> stale response/error does not touch UI', async () => {
    const userA = makeDualRoleUser('user-a');
    const tree = await mountScreenAs(userA, 'token-a');

    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValueOnce({
      canceled: false,
      assets: [{ uri: 'file:///cap-doc.pdf', name: 'cap-doc.pdf', mimeType: 'application/pdf', size: 512 }],
    });
    const pendingUploadHttp = deferred<Response>();
    uploadResponder = () => pendingUploadHttp.promise;

    let opPromise!: Promise<void>;
    await renderer.act(async () => {
      opPromise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
      await Promise.resolve();
    });

    expect(sentDocumentUploads).toHaveLength(1);

    await renderer.act(async () => {
      await authHandle.setActiveCapability('STUDENT');
      await authHandle.setActiveCapability('TRAINER');
    });

    await renderer.act(async () => {
      pendingUploadHttp.resolve(
        jsonResponse(503, {
          timestamp: '2026-11-25T10:00:00Z',
          requestId: 'req-stale-cap',
          errorCode: 'SCANNER_UNAVAILABLE',
          message: 'Scanner unavailable',
          fieldErrors: [],
        }),
      );
      await opPromise;
    });

    expect(tree.root.findAllByProps({ testID: 'uploaded-doc-item-doc-stale-cap' })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'trainer-document-upload-error' })).toHaveLength(0);

    // Also verify a late 200 success resolution after capability switch & back cannot append to uploadedDocs
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValueOnce({
      canceled: false,
      assets: [{ uri: 'file:///cap-doc-2.pdf', name: 'cap-doc-2.pdf', mimeType: 'application/pdf', size: 512 }],
    });
    const pendingUpload2 = deferred<Response>();
    uploadResponder = () => pendingUpload2.promise;
    let op2Promise!: Promise<void>;
    await renderer.act(async () => {
      op2Promise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
      await Promise.resolve();
    });
    await renderer.act(async () => {
      await authHandle.setActiveCapability('STUDENT');
      await authHandle.setActiveCapability('TRAINER');
    });
    await renderer.act(async () => {
      pendingUpload2.resolve(
        jsonResponse(200, {
          id: 'doc-stale-cap',
          originalFilename: 'cap-doc-2.pdf',
          contentType: 'application/pdf',
          sizeBytes: 512,
          scanStatus: 'CLEAN',
          createdAt: '2026-11-25T10:01:00Z',
        }),
      );
      await op2Promise;
    });
    expect(tree.root.findAllByProps({ testID: 'uploaded-doc-item-doc-stale-cap' })).toHaveLength(0);

    renderer.act(() => tree.unmount());
  });

  it('D: stale operation #1 rejection/cancel settling after newer operation #2 started does NOT clear loading or write error onto op #2', async () => {
    const userA = makeDualRoleUser('user-a');
    const tree = await mountScreenAs(userA, 'token-a');

    const op1Http = deferred<Response>();
    const op2Http = deferred<Response>();
    let uploadCall = 0;
    uploadResponder = () => (++uploadCall === 1 ? op1Http.promise : op2Http.promise);

    (DocumentPicker.getDocumentAsync as jest.Mock)
      .mockResolvedValueOnce({
        canceled: false,
        assets: [{ uri: 'file:///op1.pdf', name: 'op1.pdf', mimeType: 'application/pdf', size: 512 }],
      })
      .mockResolvedValueOnce({
        canceled: false,
        assets: [{ uri: 'file:///op2.pdf', name: 'op2.pdf', mimeType: 'application/pdf', size: 512 }],
      });

    let op1Promise!: Promise<void>;
    await renderer.act(async () => {
      op1Promise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
      await Promise.resolve();
    });
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(true);

    // Capability change invalidates op #1 so current session can immediately start op #2
    await renderer.act(async () => {
      await authHandle.setActiveCapability('STUDENT');
      await authHandle.setActiveCapability('TRAINER');
    });
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(false);

    let op2Promise!: Promise<void>;
    await renderer.act(async () => {
      op2Promise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
      await Promise.resolve();
    });
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(true);

    // Op #1 rejects with 503 scanner error while op #2 is still in flight
    await renderer.act(async () => {
      op1Http.reject(new Error('network fail op1'));
      await op1Promise;
    });

    // Op #2 MUST remain locked/loading and error-free
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(true);
    expect(tree.root.findAllByProps({ testID: 'trainer-document-upload-error' })).toHaveLength(0);

    // Resolve op #2 -> only op #2 doc renders and loading unlocks
    await renderer.act(async () => {
      op2Http.resolve(
        jsonResponse(200, {
          id: 'doc-op-2',
          originalFilename: 'op2.pdf',
          contentType: 'application/pdf',
          sizeBytes: 512,
          scanStatus: 'CLEAN',
          createdAt: '2026-11-25T10:05:00Z',
        }),
      );
      await op2Promise;
    });

    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(false);
    expect(tree.root.findByProps({ testID: 'uploaded-doc-item-doc-op-2' })).toBeDefined();
    expect(tree.root.findAllByProps({ testID: 'trainer-document-upload-error' })).toHaveLength(0);

    renderer.act(() => tree.unmount());
  });

  it('E: valid same-session picker -> upload updates uploadedDocs, and canceled picker cleanly resets loading without error', async () => {
    const userA = makeDualRoleUser('user-a');
    const tree = await mountScreenAs(userA, 'token-a-valid');

    (DocumentPicker.getDocumentAsync as jest.Mock)
      .mockResolvedValueOnce({ canceled: true, assets: null })
      .mockResolvedValueOnce({
        canceled: false,
        assets: [{ uri: 'file:///valid.pdf', name: 'valid.pdf', mimeType: 'application/pdf', size: 1024 }],
      });
    uploadResponder = async () =>
      jsonResponse(200, {
        id: 'doc-valid-1',
        originalFilename: 'valid.pdf',
        contentType: 'application/pdf',
        sizeBytes: 1024,
        scanStatus: 'CLEAN',
        createdAt: '2026-11-25T10:10:00Z',
      });

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
    });
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(false);
    expect(sentDocumentUploads).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'trainer-document-upload-error' })).toHaveLength(0);

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
    });
    expect(sentDocumentUploads).toHaveLength(1);
    expect(sentDocumentUploads[0].auth).toBe('Bearer token-a-valid');
    expect(tree.root.findByProps({ testID: 'uploaded-doc-item-doc-valid-1' })).toBeDefined();
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(false);

    renderer.act(() => tree.unmount());
  });

  it('F: stale canceled picker #1 settling after op #2 started does NOT clear op #2 loading, and explicit stale sessionBinding rejects before fetch(fileUri)', async () => {
    const userA = makeDualRoleUser('user-a');
    const tree = await mountScreenAs(userA, 'token-a');

    const op1Picker = deferred<DocumentPicker.DocumentPickerResult>();
    const op2Http = deferred<Response>();
    uploadResponder = () => op2Http.promise;

    (DocumentPicker.getDocumentAsync as jest.Mock)
      .mockReturnValueOnce(op1Picker.promise)
      .mockResolvedValueOnce({
        canceled: false,
        assets: [{ uri: 'file:///op2-file.pdf', name: 'op2-file.pdf', mimeType: 'application/pdf', size: 512 }],
      });

    let op1Promise!: Promise<void>;
    renderer.act(() => {
      op1Promise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
    });

    // Switch capability and back so op #2 can begin while op #1 picker stays open
    await renderer.act(async () => {
      await authHandle.setActiveCapability('STUDENT');
      await authHandle.setActiveCapability('TRAINER');
    });

    let op2Promise!: Promise<void>;
    await renderer.act(async () => {
      op2Promise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
      await Promise.resolve();
    });
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(true);

    // Late cancel of stale picker #1 MUST NOT unlock op #2 loading
    await renderer.act(async () => {
      op1Picker.resolve({ canceled: true, assets: null });
      await op1Promise;
    });
    expect(tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.disabled).toBe(true);

    // Unmount before op #2 finishes triggers no error or state leak
    renderer.act(() => tree.unmount());
    await renderer.act(async () => {
      op2Http.resolve(
        jsonResponse(200, {
          id: 'doc-after-unmount',
          originalFilename: 'op2-file.pdf',
          contentType: 'application/pdf',
          sizeBytes: 512,
          scanStatus: 'CLEAN',
          createdAt: '2026-11-25T10:12:00Z',
        }),
      );
      await op2Promise;
    });

    // Direct service guard: passing an outdated initial sessionBinding aborts before local file read
    const fetchBeforeDirectCall = (globalThis.fetch as jest.Mock).mock.calls.length;
    await expect(
      trainerApplicationApi.uploadDocument('file:///stale.pdf', 'stale.pdf', 'application/pdf', undefined, {
        sessionBinding: { authEpoch: 0, capabilityEpoch: 0 },
      }),
    ).rejects.toThrow('Phiên đăng nhập đã thay đổi.');
    expect((globalThis.fetch as jest.Mock).mock.calls.length).toBe(fetchBeforeDirectCall);
  });

  it('G: unmount while tokenStorage.getAccessToken is pending aborts without POST or session-loss error (real apiClient + tokenStorage)', async () => {
    const userA = makeDualRoleUser('user-a');
    const tree = await mountScreenAs(userA, 'token-a-unmount');

    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValueOnce({
      canceled: false,
      assets: [
        {
          uri: 'file:///unmount-during-token-read.pdf',
          name: 'unmount-during-token-read.pdf',
          mimeType: 'application/pdf',
          size: 1024,
        },
      ],
    });

    const tokenReadStarted = deferred<void>();
    const pendingTokenRead = deferred<string | null>();
    const getAccessTokenSpy = jest.spyOn(tokenStorage, 'getAccessToken').mockImplementationOnce(async () => {
      tokenReadStarted.resolve();
      return pendingTokenRead.promise;
    });
    const uploadSpy = jest.spyOn(trainerApplicationApi, 'uploadDocument');

    try {
      let opPromise!: Promise<void>;
      renderer.act(() => {
        opPromise = tree.root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
      });

      // Deterministic checkpoint: confirm tokenStorage.getAccessToken is actually pending
      await tokenReadStarted.promise;
      expect(getAccessTokenSpy).toHaveBeenCalledTimes(1);
      expect(sentDocumentUploads).toHaveLength(0);

      const servicePromise = uploadSpy.mock.results[0]?.value as Promise<unknown>;
      expect(servicePromise).toBeDefined();

      // Unmount while auth/capability epochs remain unchanged
      renderer.act(() => {
        tree.unmount();
      });

      // Release token read after unmount
      await renderer.act(async () => {
        pendingTokenRead.resolve('token-a-unmount');
        await opPromise;
      });

      await expect(servicePromise).rejects.toMatchObject({ name: 'AbortError' });
      expect(sentDocumentUploads).toHaveLength(0);
      expect(await tokenStorage.getTokens()).toEqual({
        accessToken: 'token-a-unmount',
        refreshToken: 'refresh-user-a',
      });
    } finally {
      getAccessTokenSpy.mockRestore();
      uploadSpy.mockRestore();
    }
  });
});
