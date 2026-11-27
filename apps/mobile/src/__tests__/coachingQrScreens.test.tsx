import React from 'react';
import renderer from 'react-test-renderer';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ApiError } from '@/types/auth';
import { coachingApi } from '@/services/coachingApi';
import { coachingErrorMessage } from '@/features/coaching/coachingMessages';
import { myCoachingQrOptions, useMyCoachingQr, useResolveCoachingQr } from '@/features/coaching/coachingQueries';
import { MyCoachingQrScreen } from '@/features/coaching/MyCoachingQrScreen';
import { CoachingQrScannerScreen } from '@/features/coaching/CoachingQrScannerScreen';
import { QrProfilePreviewScreen } from '@/features/coaching/QrProfilePreviewScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { Camera } from 'expo-camera';
import * as ImagePicker from 'expo-image-picker';
import * as MediaLibrary from 'expo-media-library';

const mockPush = jest.fn();
const mockReplace = jest.fn();
const mockBack = jest.fn();
let mockParams: Record<string, string> = {};
const mockRequestCameraPerm = jest.fn();
const mockRequestMediaPerm = jest.fn();
const mockFileWrite = jest.fn();
const mockFileDelete = jest.fn();

jest.mock('expo-router', () => {
  const React = require('react');
  return {
    useRouter: () => ({ push: mockPush, replace: mockReplace, back: mockBack }),
    useLocalSearchParams: () => mockParams,
    Redirect: (props: any) => React.createElement('Redirect', props),
    Stack: Object.assign((props: any) => React.createElement('Stack', props), {
      Screen: (props: any) => React.createElement('StackScreen', props),
    }),
    useFocusEffect: (cb: () => void | (() => void)) => {
      React.useEffect(() => cb(), [cb]);
    },
  };
});

jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));

jest.mock('@/features/coaching/coachingQueries', () => {
  const actual = jest.requireActual('@/features/coaching/coachingQueries');
  return {
    ...actual,
    useMyCoachingQr: jest.fn(),
    useResolveCoachingQr: jest.fn(),
  };
});

jest.mock('expo-camera', () => {
  const React = require('react');
  const { View } = require('react-native');
  return {
    CameraView: (props: any) => React.createElement(View, props),
    Camera: { scanFromURLAsync: jest.fn() },
    useCameraPermissions: () => [{ granted: true, status: 'granted', canAskAgain: true }, mockRequestCameraPerm],
  };
});

jest.mock('expo-image-picker', () => ({
  launchImageLibraryAsync: jest.fn(),
}));

jest.mock('expo-media-library', () => ({
  usePermissions: () => [{ granted: true, canAskAgain: true }, mockRequestMediaPerm],
  Asset: {
    create: jest.fn(),
  },
}));

jest.mock('expo-file-system', () => ({
  Paths: { cache: 'file:///cache' },
  File: jest.fn().mockImplementation((_dir: string, name: string) => ({
    uri: `file:///cache/${name}`,
    write: mockFileWrite,
    delete: mockFileDelete,
  })),
}));

jest.mock('@/services/coachingApi', () => {
  const actual = jest.requireActual('@/services/coachingApi');
  return {
    ...actual,
    coachingApi: {
      ...actual.coachingApi,
      getMyQr: jest.fn(),
      issueMyQr: jest.fn(),
      rotateMyQr: jest.fn(),
      resolveQr: jest.fn(),
      requestTrainer: jest.fn(),
      inviteStudent: jest.fn(),
    },
  };
});

const studentUser = {
  id: 'student-1',
  email: 'student@example.com',
  displayName: 'Nguyễn Văn Học Viên',
  status: 'ACTIVE',
  roles: ['STUDENT'],
  timezone: 'Asia/Ho_Chi_Minh',
  settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
};

const queryClients: QueryClient[] = [];
function makeClient() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: 0 }, mutations: { retry: false, gcTime: 0 } } });
  queryClients.push(client);
  return client;
}

describe('F12 Coaching QR mobile screens and messages', () => {
  const sampleCode = 'k8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8a';
  const sampleUri = `ai-fitness-coaching://coaching/qr?v=1&c=${sampleCode}`;

  beforeEach(() => {
    jest.clearAllMocks();
    mockParams = {};
    (useAuth as jest.Mock).mockReturnValue({
      user: studentUser,
      activeCapability: 'STUDENT',
    });
  });

  afterEach(() => { queryClients.splice(0).forEach(client => client.clear()); });

  it('maps QR errors including 429 Retry-After', () => {
    const rateLimited = new ApiError(
      429,
      'Too many requests',
      { errorCode: 'RATE_LIMIT_EXCEEDED', message: 'raw', timestamp: '', requestId: '1', fieldErrors: [] },
      undefined,
      45
    );
    expect(coachingErrorMessage(rateLimited)).toContain('45 giây');
    expect(
      coachingErrorMessage(
        new ApiError(400, 'self', { errorCode: 'COACHING_QR_SELF_SCAN', message: '', timestamp: '', requestId: '1', fieldErrors: [] })
      )
    ).toContain('tự quét');
  });

  it('SH-08 loads QR, saves PNG image, and rotates with confirmation', async () => {
    (useMyCoachingQr as jest.Mock).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        qrId: 'qr-1',
        role: 'STUDENT',
        publicCode: sampleCode,
        qrUri: sampleUri,
        payloadVersion: 1,
        displayName: 'Nguyễn Văn Học Viên',
        issuedAt: '2026-11-25T16:30:00Z',
        version: 1,
      },
      refetch: jest.fn(),
    });
    (coachingApi.rotateMyQr as jest.Mock).mockResolvedValue({
      qrId: 'qr-2',
      role: 'STUDENT',
      publicCode: 'b8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8c',
      qrUri: 'ai-fitness-coaching://coaching/qr?v=1&c=b8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8c',
      payloadVersion: 1,
      displayName: 'Nguyễn Văn Học Viên',
      issuedAt: '2026-11-25T16:35:00Z',
      version: 2,
    });

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><MyCoachingQrScreen /></QueryClientProvider>);
    });

    expect(tree.root.findByProps({ testID: 'my-qr-display-name' }).props.children).toBe('Nguyễn Văn Học Viên');
    expect(tree.root.findByProps({ testID: 'coaching-qr-matrix' })).toBeTruthy();

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'save-qr-image-button' }).props.onPress();
    });
    expect(mockFileWrite).toHaveBeenCalled();
    expect(MediaLibrary.Asset.create).toHaveBeenCalled();

    renderer.act(() => {
      tree.root.findByProps({ testID: 'rotate-qr-prompt-button' }).props.onPress();
    });
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'rotate-qr-confirm-button' }).props.onPress();
    });
    expect(coachingApi.rotateMyQr).toHaveBeenCalledWith('STUDENT', 1, expect.any(String));
    expect(tree.root.findByProps({ testID: 'rotate-qr-success' })).toBeTruthy();
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('myCoachingQrOptions only falls back to POST on COACHING_QR_NOT_FOUND, never on generic NOT_FOUND', async () => {
    (coachingApi.getMyQr as jest.Mock).mockRejectedValueOnce(
      new ApiError(404, 'Not found', { errorCode: 'NOT_FOUND', message: 'No static resource', timestamp: '', requestId: '1', fieldErrors: [] })
    );
    const qc = makeClient();
    await expect(qc.fetchQuery(myCoachingQrOptions('student-1', 'STUDENT'))).rejects.toBeInstanceOf(ApiError);
    expect(coachingApi.issueMyQr).not.toHaveBeenCalled();

    (coachingApi.getMyQr as jest.Mock).mockRejectedValueOnce(
      new ApiError(404, 'QR missing', { errorCode: 'COACHING_QR_NOT_FOUND', message: 'Not issued', timestamp: '', requestId: '2', fieldErrors: [] })
    );
    (coachingApi.issueMyQr as jest.Mock).mockResolvedValueOnce({
      qrId: 'qr-new',
      role: 'STUDENT',
      publicCode: sampleCode,
      qrUri: sampleUri,
      payloadVersion: 1,
      displayName: 'Nguyễn Văn Học Viên',
      issuedAt: '2026-11-26T00:00:00Z',
      version: 1,
    });
    const issued = await qc.fetchQuery(myCoachingQrOptions('student-1', 'STUDENT'));
    expect(coachingApi.issueMyQr).toHaveBeenCalledWith('STUDENT');
    expect(issued.qrId).toBe('qr-new');
    qc.clear();
  });

  it('SH-08 keeps immutable rotate payload across query refetch on network retry, blocks double-tap, and ignores stale account switch', async () => {
    const qrV1 = {
      qrId: 'qr-1',
      role: 'STUDENT' as const,
      publicCode: sampleCode,
      qrUri: sampleUri,
      payloadVersion: 1,
      displayName: 'Nguyễn Văn Học Viên',
      issuedAt: '2026-11-25T16:30:00Z',
      version: 1,
    };
    (useMyCoachingQr as jest.Mock).mockReturnValue({
      isPending: false,
      isError: false,
      data: qrV1,
      refetch: jest.fn(),
    });
    (coachingApi.rotateMyQr as jest.Mock)
      .mockRejectedValueOnce(new ApiError(0, 'offline'))
      .mockResolvedValueOnce({ ...qrV1, qrId: 'qr-2', version: 2 });

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><MyCoachingQrScreen /></QueryClientProvider>);
    });

    renderer.act(() => {
      tree.root.findByProps({ testID: 'rotate-qr-prompt-button' }).props.onPress();
    });
    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'rotate-qr-confirm-button' }).props.onPress();
    });
    const firstKey = (coachingApi.rotateMyQr as jest.Mock).mock.calls[0][2];
    expect(coachingApi.rotateMyQr).toHaveBeenCalledWith('STUDENT', 1, firstKey);
    expect(tree.root.findByProps({ testID: 'rotate-qr-error' })).toBeTruthy();

    // Simulate background query refetch returning version 99 before user retries
    (useMyCoachingQr as jest.Mock).mockReturnValue({
      isPending: false,
      isError: false,
      data: { ...qrV1, version: 99 },
      refetch: jest.fn(),
    });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={qc}><MyCoachingQrScreen /></QueryClientProvider>);
    });

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'rotate-qr-confirm-button' }).props.onPress();
    });
    // Must still send exact original expectedVersion=1 and commandKey
    expect((coachingApi.rotateMyQr as jest.Mock).mock.calls[1]).toEqual(['STUDENT', 1, firstKey]);
    expect(tree.root.findByProps({ testID: 'rotate-qr-success' })).toBeTruthy();

    // Double-tap & account switch mid-flight
    let resolvePending!: (v: unknown) => void;
    (coachingApi.rotateMyQr as jest.Mock).mockReturnValueOnce(new Promise((r) => { resolvePending = r; }));
    renderer.act(() => {
      tree.root.findByProps({ testID: 'rotate-qr-prompt-button' }).props.onPress();
    });
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'rotate-qr-confirm-button' }).props.onPress();
      tree.root.findByProps({ testID: 'rotate-qr-confirm-button' }).props.onPress();
      await Promise.resolve();
    });
    expect(coachingApi.rotateMyQr).toHaveBeenCalledTimes(3);

    (useAuth as jest.Mock).mockReturnValue({
      user: { ...studentUser, id: 'student-2', displayName: 'User 2' },
      activeCapability: 'STUDENT',
    });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={qc}><MyCoachingQrScreen /></QueryClientProvider>);
    });
    await renderer.act(async () => {
      resolvePending({ ...qrV1, qrId: 'qr-stale', version: 100 });
    });
    expect(qc.getQueryData(['coaching', 'qr', 'me', 'student-2', 'STUDENT'])).toBeUndefined();
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('SH-07 handles image cancel, multiple QR picker, and valid scan navigation', async () => {
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValueOnce({ canceled: true, assets: [] });

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><CoachingQrScannerScreen /></QueryClientProvider>);
    });

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'pick-qr-image-button' }).props.onPress();
    });
    expect(Camera.scanFromURLAsync).not.toHaveBeenCalled();

    const secondCode = 'z8X9mP2vQ7nR4tW1zY6bN3cF5hJ0sL8b';
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValueOnce({
      canceled: false,
      assets: [{ uri: 'file:///picked.png' }],
    });
    (Camera.scanFromURLAsync as jest.Mock).mockResolvedValueOnce([
      { type: 'qr', data: sampleUri },
      { type: 'qr', data: `ai-fitness-coaching://coaching/qr?v=1&c=${secondCode}` },
    ]);

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'pick-qr-image-button' }).props.onPress();
    });

    expect(tree.root.findByProps({ testID: 'multiple-qr-picker' })).toBeTruthy();
    renderer.act(() => {
      tree.root.findByProps({ testID: 'qr-candidate-1' }).props.onPress();
    });
    expect(mockPush).toHaveBeenCalledWith({
      pathname: '/coaching/qr-profile',
      params: { publicCode: secondCode, targetRole: 'TRAINER', payloadVersion: '1' },
    });
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('SH-09 sends Student->Trainer request with stable commandKey across retry and blocks duplicate submit', async () => {
    mockParams = { publicCode: sampleCode, targetRole: 'TRAINER', payloadVersion: '1' };
    const refetch = jest.fn();
    (useResolveCoachingQr as jest.Mock).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        counterpartyId: 'trainer-1',
        targetRole: 'TRAINER',
        displayName: 'Trần Minh PT',
        avatarUrl: null,
        trainerSummary: {
          verificationStatus: 'VERIFIED',
          specialties: ['Tăng cơ'],
          yearsExperience: 5,
          isAcceptingStudents: true,
        },
        relationshipState: {
          status: 'NONE',
          relationshipId: null,
          direction: null,
          version: null,
          canInitiate: true,
        },
      },
      refetch,
    });

    (coachingApi.requestTrainer as jest.Mock)
      .mockRejectedValueOnce(new ApiError(0, 'offline'))
      .mockResolvedValueOnce({
        relationship: {
          id: 'rel-1',
          studentId: 'student-1',
          trainerId: 'trainer-1',
          counterparty: { userId: 'trainer-1', displayName: 'Trần Minh PT' },
          status: 'PENDING',
          direction: 'OUTGOING',
          requestedBy: 'student-1',
          requestedAt: '2026-11-26T00:00:00Z',
          acceptedAt: null,
          startedAt: null,
          endedAt: null,
          version: 1,
        },
        resume: null,
        currentPeriod: null,
      });

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><QrProfilePreviewScreen /></QueryClientProvider>);
    });

    expect(tree.root.findByProps({ testID: 'qr-preview-display-name' }).props.children).toBe('Trần Minh PT');
    expect(tree.root.findByProps({ testID: 'qr-preview-avatar-placeholder' })).toBeTruthy();

    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'qr-initiate-coaching-button' }).findByProps({ accessibilityRole: 'button' }).props.onPress({ nativeEvent: { timestamp: 123 } });
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    const firstKey = (coachingApi.requestTrainer as jest.Mock).mock.calls[0][1];
    expect(tree.root.findByProps({ testID: 'qr-initiate-error' })).toBeTruthy();

    // Retry reuses the exact same commandKey
    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'qr-initiate-coaching-button' }).findByProps({ accessibilityRole: 'button' }).props.onPress({ nativeEvent: { timestamp: 123 } });
      await new Promise((resolve) => setTimeout(resolve, 10));
    });
    expect((coachingApi.requestTrainer as jest.Mock).mock.calls[1][1]).toBe(firstKey);
    expect(tree.root.findByProps({ testID: 'qr-preview-existing-relationship' })).toBeTruthy();
    expect(tree.root.findAllByProps({ testID: 'qr-initiate-coaching-button' })).toHaveLength(0);

    renderer.act(() => {
      tree.root.findByProps({ testID: 'view-existing-relationship-button' }).props.onPress();
    });
    expect(mockReplace).toHaveBeenCalledWith('/coaching/rel-1');
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('SH-09 sends Trainer->Student invitation and ignores stale result after capability switch', async () => {
    mockParams = { publicCode: sampleCode, targetRole: 'STUDENT', payloadVersion: '1' };
    (useAuth as jest.Mock).mockReturnValue({
      user: { ...studentUser, id: 'trainer-1', capabilities: { hasStudentProfile: true, hasTrainerProfile: true, canCoach: true } },
      activeCapability: 'TRAINER',
    });
    (useResolveCoachingQr as jest.Mock).mockReturnValue({
      isPending: false,
      isError: false,
      data: {
        counterpartyId: 'student-2',
        targetRole: 'STUDENT',
        displayName: 'Lê Học Viên',
        avatarUrl: null,
        trainerSummary: null,
        relationshipState: { status: 'NONE', relationshipId: null, direction: null, version: null, canInitiate: true },
      },
      refetch: jest.fn(),
    });

    let resolveInvite!: (val: unknown) => void;
    (coachingApi.inviteStudent as jest.Mock).mockReturnValue(new Promise((r) => { resolveInvite = r; }));

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><QrProfilePreviewScreen /></QueryClientProvider>);
    });

    await renderer.act(async () => {
      tree.root.findByProps({ testID: 'qr-initiate-coaching-button' }).findByProps({ accessibilityRole: 'button' }).props.onPress({ nativeEvent: { timestamp: 123 } });
      await new Promise(resolve => setTimeout(resolve, 0));
    });
    expect(coachingApi.inviteStudent).toHaveBeenCalledWith('student-2', expect.any(String));
    const pending = tree.root.findByProps({ testID: 'qr-initiate-coaching-button' }).findByProps({ accessibilityRole: 'button' });
    expect(pending.props.accessibilityState).toEqual({ disabled: true, busy: true });
    renderer.act(() => pending.props.onPress({ nativeEvent: { timestamp: 124 } }));
    expect(coachingApi.inviteStudent).toHaveBeenCalledTimes(1);

    // Switch capability mid-flight
    (useAuth as jest.Mock).mockReturnValue({
      user: { ...studentUser, id: 'trainer-1', capabilities: { hasStudentProfile: true, hasTrainerProfile: true, canCoach: true } },
      activeCapability: 'STUDENT',
    });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={qc}><QrProfilePreviewScreen /></QueryClientProvider>);
    });

    await renderer.act(async () => {
      resolveInvite({
        relationship: { id: 'rel-stale', studentId: 'student-2', trainerId: 'trainer-1', counterparty: { userId: 'student-2', displayName: 'Lê Học Viên' }, status: 'PENDING', direction: 'OUTGOING', requestedBy: 'trainer-1', requestedAt: '', acceptedAt: null, startedAt: null, endedAt: null, version: 1 },
        resume: null,
        currentPeriod: null,
      });
    });

    expect(tree.root.findAllByProps({ testID: 'qr-preview-existing-relationship' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('SH-07 handles deep link c/v=1 and locks duplicate scanner callbacks', () => {
    mockParams = { c: sampleCode, v: '1' };
    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><CoachingQrScannerScreen /></QueryClientProvider>);
    });
    expect(mockReplace).toHaveBeenCalledWith({
      pathname: '/coaching/qr-profile',
      params: { publicCode: sampleCode, targetRole: 'TRAINER', payloadVersion: '1' },
    });
    renderer.act(() => tree.unmount());

    // Scanner callback lock prevents duplicate navigation
    jest.clearAllMocks();
    mockParams = {};
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><CoachingQrScannerScreen /></QueryClientProvider>);
    });
    renderer.act(() => {
      tree.root.findByProps({ testID: 'open-camera-button' }).props.onPress();
    });
    const camera = tree.root.findByProps({ testID: 'qr-camera-view' });
    renderer.act(() => {
      camera.props.onBarcodeScanned({ type: 'qr', data: sampleUri });
      camera.props.onBarcodeScanned({ type: 'qr', data: sampleUri });
    });
    expect(mockPush).toHaveBeenCalledTimes(1);
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('SH-07 handles ImagePicker exception gracefully and ignores result after account switch', async () => {
    jest.clearAllMocks();
    mockParams = {};
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockRejectedValueOnce(new Error('picker crashed'));

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><CoachingQrScannerScreen /></QueryClientProvider>);
    });

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'pick-qr-image-button' }).props.onPress();
    });
    expect(tree.root.findByProps({ testID: 'scanner-error-state' })).toBeTruthy();

    // Account switch while picker is open ignores late result
    let resolvePicker!: (val: unknown) => void;
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockReturnValueOnce(new Promise((r) => { resolvePicker = r; }));
    renderer.act(() => {
      tree.root.findByProps({ testID: 'pick-qr-image-button' }).props.onPress();
    });
    (useAuth as jest.Mock).mockReturnValue({ user: { ...studentUser, id: 'student-other' }, activeCapability: 'STUDENT' });
    renderer.act(() => {
      tree.update(<QueryClientProvider client={qc}><CoachingQrScannerScreen /></QueryClientProvider>);
    });
    await renderer.act(async () => {
      resolvePicker({ canceled: false, assets: [{ uri: 'file:///late.png' }] });
    });
    expect(Camera.scanFromURLAsync).not.toHaveBeenCalled();
    expect(mockPush).not.toHaveBeenCalled();
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('SH-07 prompts dual-role user for deep link and clears pending intent on cancel', () => {
    jest.clearAllMocks();
    mockParams = { c: sampleCode, v: '1' };
    (useAuth as jest.Mock).mockReturnValue({
      user: { ...studentUser, capabilities: { hasStudentProfile: true, hasTrainerProfile: true, canCoach: true } },
      activeCapability: 'STUDENT',
    });

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><CoachingQrScannerScreen /></QueryClientProvider>);
    });

    expect(mockReplace).not.toHaveBeenCalled();
    expect(tree.root.findByProps({ testID: 'deep-link-dual-role-prompt' })).toBeTruthy();

    renderer.act(() => {
      tree.root.findByProps({ testID: 'deep-link-cancel-button' }).props.onPress();
    });
    expect(mockReplace).toHaveBeenCalledWith('/(app)/coaching');
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('qrPreview query key is scoped by userId and disabled when unauthenticated', () => {
    const { coachingQueryKeys, resolveCoachingQrOptions } = require('@/features/coaching/coachingQueries');
    const keyA = coachingQueryKeys.qrPreview('user-a', sampleCode, 'TRAINER');
    const keyB = coachingQueryKeys.qrPreview('user-b', sampleCode, 'TRAINER');
    expect(keyA).not.toEqual(keyB);
    expect(resolveCoachingQrOptions(undefined, sampleCode, 'TRAINER').enabled).toBe(false);
    expect(resolveCoachingQrOptions('user-a', sampleCode, 'TRAINER').enabled).toBe(true);
  });

  it('SH-07 treats canceled picker as non-error and pendingQrIntent preserves/clears deep-link state', async () => {
    jest.clearAllMocks();
    mockParams = {};
    (ImagePicker.launchImageLibraryAsync as jest.Mock).mockResolvedValueOnce({ canceled: true, assets: null });

    const {
      setPendingCoachingQrIntent,
      getPendingCoachingQrIntent,
      clearPendingCoachingQrIntent,
    } = require('@/features/coaching/pendingQrIntent');
    setPendingCoachingQrIntent({ publicCode: sampleCode, payloadVersion: 1 });
    expect(getPendingCoachingQrIntent()).toEqual({ publicCode: sampleCode, payloadVersion: 1 });
    clearPendingCoachingQrIntent();
    expect(getPendingCoachingQrIntent()).toBeNull();

    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<QueryClientProvider client={qc}><CoachingQrScannerScreen /></QueryClientProvider>);
    });
    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'pick-qr-image-button' }).props.onPress();
    });
    expect(tree.root.findAllByProps({ testID: 'scanner-error-state' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
    qc.clear();
  });

  it('AuthLayout redirects onboarded user to QR once and clears pendingQrIntent when redirecting to onboarding', () => {
    const {
      setPendingCoachingQrIntent,
      getPendingCoachingQrIntent,
      clearPendingCoachingQrIntent,
    } = require('@/features/coaching/pendingQrIntent');
    const AuthLayout = require('@/app/(auth)/_layout').default;

    clearPendingCoachingQrIntent();
    setPendingCoachingQrIntent({ publicCode: sampleCode, payloadVersion: 1 });
    (useAuth as jest.Mock).mockReturnValue({
      status: 'AUTHENTICATED',
      isLoading: false,
      user: { ...studentUser, capabilities: { hasStudentProfile: false, hasTrainerProfile: false, canCoach: false } },
    });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<AuthLayout />);
    });
    const redirectOnboarding = tree.root.findByType('Redirect' as any);
    expect(redirectOnboarding.props.href).toBe('/(onboarding)/select');
    expect(getPendingCoachingQrIntent()).toBeNull();
    renderer.act(() => tree.unmount());

    setPendingCoachingQrIntent({ publicCode: sampleCode, payloadVersion: 1 });
    (useAuth as jest.Mock).mockReturnValue({
      status: 'AUTHENTICATED',
      isLoading: false,
      user: studentUser,
    });
    renderer.act(() => {
      tree = renderer.create(<AuthLayout />);
    });
    const redirectQr = tree.root.findByType('Redirect' as any);
    expect(redirectQr.props.href).toEqual({
      pathname: '/(app)/coaching/qr',
      params: { c: sampleCode, v: '1' },
    });
    clearPendingCoachingQrIntent();
    renderer.act(() => tree.unmount());
  });

  it.each([
    { verificationStatus: 'VERIFIED', isAcceptingStudents: true, yearsExperience: 0, verificationText: 'Đã xác minh', availabilityText: 'Đang nhận học viên' },
    { verificationStatus: 'PENDING', isAcceptingStudents: false, yearsExperience: null, verificationText: 'Đang chờ xác minh', availabilityText: 'Hiện không nhận học viên' },
  ])('SH-09 presents actual verification/availability and unknown experience ($verificationStatus)', ({ verificationStatus, isAcceptingStudents, yearsExperience, verificationText, availabilityText }) => {
    mockParams = { publicCode: sampleCode, targetRole: 'TRAINER', payloadVersion: '1' };
    (useResolveCoachingQr as jest.Mock).mockReturnValue({ isPending: false, isError: false, data: {
      counterpartyId: 'trainer-1', targetRole: 'TRAINER', displayName: 'Huấn luyện viên có tên dài để xuống dòng', avatarUrl: null,
      trainerSummary: { verificationStatus, isAcceptingStudents, yearsExperience, specialties: [] },
      relationshipState: { status: 'NONE', relationshipId: null, direction: null, version: null, canInitiate: false },
    }, refetch: jest.fn() });
    const qc = makeClient();
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<QueryClientProvider client={qc}><QrProfilePreviewScreen /></QueryClientProvider>); });
    const rendered = JSON.stringify(tree.toJSON());
    expect(rendered).toContain(verificationText);
    expect(rendered).toContain(availabilityText);
    expect(rendered).toContain('Chưa cập nhật');
    if (yearsExperience === null) expect(rendered).not.toContain('Kinh nghiệm: ');
    else expect(tree.root.findAll(node => node.children.join('') === 'Kinh nghiệm: 0 năm')).not.toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'qr-initiate-coaching-button' })).toHaveLength(0);
    expect(coachingApi.requestTrainer).not.toHaveBeenCalled();
    expect(coachingApi.inviteStudent).not.toHaveBeenCalled();
    renderer.act(() => tree.unmount());
    qc.clear();
  });

});
