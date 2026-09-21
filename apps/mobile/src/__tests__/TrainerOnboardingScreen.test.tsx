import React from 'react';
import renderer from 'react-test-renderer';
import { TrainerOnboardingScreen } from '@/features/trainer/TrainerOnboardingScreen';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { ApiError } from '@/types/auth';

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({
    replace: mockReplace,
    push: jest.fn(),
  }),
}));

const mockRefreshUser = jest.fn();
const mockSetActiveCapability = jest.fn();
const mockLogout = jest.fn();

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 'test-user-id',
      email: 'trainer@example.com',
      displayName: 'Coach Sam',
      capabilities: {
        hasStudentProfile: false,
        hasTrainerProfile: false,
        canCoach: false,
      },
    },
    refreshUser: mockRefreshUser,
    setActiveCapability: mockSetActiveCapability,
    logout: mockLogout,
    isLoading: false,
    status: 'AUTHENTICATED',
  }),
}));

jest.mock('@/services/trainerProfileApi', () => ({
  trainerProfileApi: {
    createTrainerProfile: jest.fn(),
    getMyTrainerProfile: jest.fn(),
    updateMyTrainerProfile: jest.fn(),
  },
}));

describe('TrainerOnboardingScreen Flow', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders all form inputs, coaching authority invariant notice, and action buttons', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'trainer-slug-input' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-bio-input' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-experience-input' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-accepting-switch' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-submit-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-skip-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'coaching-authority-notice' })).toBeDefined();
  });

  it('displays client validation error and does not call API when invalid slug is entered', async () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    const slugInput = root.findByProps({ testID: 'trainer-slug-input' });
    renderer.act(() => {
      slugInput.props.onChangeText('INVALID_SLUG!!');
    });

    const submitBtn = root.findByProps({ testID: 'trainer-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });

    expect(trainerProfileApi.createTrainerProfile).not.toHaveBeenCalled();
    const errorNode = root.findByProps({ testID: 'trainer-field-error-publicSlug' });
    expect(errorNode).toBeDefined();
  });

  it('displays client validation error when negative experience is entered', async () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    const expInput = root.findByProps({ testID: 'trainer-experience-input' });
    renderer.act(() => {
      expInput.props.onChangeText('-5');
    });

    const submitBtn = root.findByProps({ testID: 'trainer-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });

    expect(trainerProfileApi.createTrainerProfile).not.toHaveBeenCalled();
    const errorNode = root.findByProps({ testID: 'trainer-field-error-yearsExperience' });
    expect(errorNode).toBeDefined();
  });

  it('prevents duplicate concurrent submissions using synchronous ref lock', async () => {
    let resolveCreation!: (val: any) => void;
    const deferredPromise = new Promise((resolve) => {
      resolveCreation = resolve;
    });

    (trainerProfileApi.createTrainerProfile as jest.Mock).mockReturnValue(deferredPromise);
    mockRefreshUser.mockResolvedValue({
      id: 'test-user-id',
      capabilities: { hasTrainerProfile: true },
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    // Fill valid slug
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-slug-input' }).props.onChangeText('coach-alex');
    });

    const submitBtn = root.findByProps({ testID: 'trainer-submit-button' });

    // Rapid double press before first promise resolves
    renderer.act(() => {
      submitBtn.props.onPress();
      submitBtn.props.onPress();
    });

    expect(trainerProfileApi.createTrainerProfile).toHaveBeenCalledTimes(1);

    // Resolve deferred
    await renderer.act(async () => {
      resolveCreation({
        userId: 'test-user-id',
        publicSlug: 'coach-alex',
      });
    });

    expect(mockRefreshUser).toHaveBeenCalledWith('TRAINER');
    expect(mockReplace).toHaveBeenCalledWith('/(app)');
  });

  it('handles skip button by sending empty payload and activating trainer capability', async () => {
    (trainerProfileApi.createTrainerProfile as jest.Mock).mockResolvedValueOnce({
      userId: 'test-user-id',
      publicSlug: null,
    });
    mockRefreshUser.mockResolvedValueOnce({
      id: 'test-user-id',
      capabilities: { hasTrainerProfile: true },
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    const skipBtn = root.findByProps({ testID: 'trainer-skip-button' });
    await renderer.act(async () => {
      skipBtn.props.onPress();
    });

    expect(trainerProfileApi.createTrainerProfile).toHaveBeenCalledWith({});
    expect(mockRefreshUser).toHaveBeenCalledWith('TRAINER');
    expect(mockReplace).toHaveBeenCalledWith('/(app)');
  });

  it('displays field error when backend returns TRAINER_SLUG_ALREADY_EXISTS', async () => {
    (trainerProfileApi.createTrainerProfile as jest.Mock).mockRejectedValueOnce(
      new ApiError(409, 'Public slug already in use', {
        errorCode: 'TRAINER_SLUG_ALREADY_EXISTS',
        message: 'Public slug is already taken',
        timestamp: '2026-09-20T10:00:00Z',
        requestId: 'req-409',
        fieldErrors: [],
      })
    );

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'trainer-slug-input' }).props.onChangeText('taken-slug');
    });

    const submitBtn = root.findByProps({ testID: 'trainer-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });

    const errorNode = root.findByProps({ testID: 'trainer-field-error-publicSlug' });
    expect(errorNode).toBeDefined();
    expect(mockReplace).not.toHaveBeenCalled();
  });

  it('displays banner error when backend returns TRAINER_CAPABILITY_REVOKED', async () => {
    (trainerProfileApi.createTrainerProfile as jest.Mock).mockRejectedValueOnce(
      new ApiError(409, 'Capability revoked', {
        errorCode: 'TRAINER_CAPABILITY_REVOKED',
        message: 'Trainer capability revoked',
        timestamp: '2026-09-20T10:00:00Z',
        requestId: 'req-409',
        fieldErrors: [],
      })
    );

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    const skipBtn = root.findByProps({ testID: 'trainer-skip-button' });
    await renderer.act(async () => {
      skipBtn.props.onPress();
    });

    const banner = root.findByProps({ testID: 'trainer-error-banner' });
    expect(banner).toBeDefined();
    expect(mockReplace).not.toHaveBeenCalled();
  });
});
