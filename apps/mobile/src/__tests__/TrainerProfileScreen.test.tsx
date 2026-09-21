import React from 'react';
import renderer from 'react-test-renderer';
import { TrainerProfileScreen } from '@/features/trainer/TrainerProfileScreen';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { TrainerProfileResponse } from '@/types/trainer';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({
    replace: mockReplace,
    push: jest.fn(),
  }),
}));

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('@/services/trainerProfileApi', () => ({
  trainerProfileApi: {
    createTrainerProfile: jest.fn(),
    getMyTrainerProfile: jest.fn(),
    updateMyTrainerProfile: jest.fn(),
  },
}));

describe('TrainerProfileScreen Component', () => {
  const mockUser = {
    id: 'user-trainer-1',
    email: 'trainer@example.com',
    capabilities: {
      hasStudentProfile: false,
      hasTrainerProfile: true,
      canCoach: false,
    },
  };
  const mockProfile: TrainerProfileResponse = {
    userId: 'user-trainer-1',
    publicSlug: 'coach-sam',
    bio: 'Experienced weightlifting trainer',
    yearsExperience: 4.5,
    acceptingStudents: true,
    verificationStatus: 'PENDING',
    activityStatus: 'ACTIVE',
    coachingEligibility: {
      eligible: false,
      blockingReasons: ['VERIFICATION_PENDING'],
    },
    verifiedAt: null,
    createdAt: '2026-09-20T10:00:00Z',
    updatedAt: '2026-09-20T10:00:00Z',
  };

  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({
      user: mockUser,
    });
  });

  it('renders loading state initially and then displays loaded trainer profile details', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'trainer-profile-screen' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-profile-verification-status' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-profile-coaching-authority' })).toBeDefined();

    const verificationNode = root.findByProps({ testID: 'trainer-profile-verification-status' });
    expect(
      verificationNode.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('PENDING')).length
    ).toBeGreaterThan(0);
  });

  it('displays load error and retry button when fetching profile fails', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockRejectedValueOnce(
      new Error('Failed to load profile')
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'trainer-profile-load-error' })).toBeDefined();
    expect(root.findByProps({ testID: 'trainer-profile-retry-button' })).toBeDefined();
  });

  it('toggles edit mode and saves modified fields with partial update semantics', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);
    const updatedProfile = { ...mockProfile, bio: 'Newly updated coaching bio' };
    (trainerProfileApi.updateMyTrainerProfile as jest.Mock).mockResolvedValueOnce(updatedProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode
    const editBtn = root.findByProps({ testID: 'trainer-profile-edit-button' });
    renderer.act(() => {
      editBtn.props.onPress();
    });

    const bioInput = root.findByProps({ testID: 'trainer-profile-bio-input' });
    renderer.act(() => {
      bioInput.props.onChangeText('Newly updated coaching bio');
    });

    const saveBtn = root.findByProps({ testID: 'trainer-profile-save-button' });
    await renderer.act(async () => {
      saveBtn.props.onPress();
    });

    expect(trainerProfileApi.updateMyTrainerProfile).toHaveBeenCalledWith({
      bio: 'Newly updated coaching bio',
    });

    const successBanner = root.findByProps({ testID: 'trainer-profile-success-banner' });
    expect(successBanner).toBeDefined();
  });

  it('sends explicit null when a string field is cleared', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);
    const clearedProfile = { ...mockProfile, publicSlug: null };
    (trainerProfileApi.updateMyTrainerProfile as jest.Mock).mockResolvedValueOnce(clearedProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-edit-button' }).props.onPress();
    });

    // Clear slug
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-slug-input' }).props.onChangeText('');
    });

    await renderer.act(async () => {
      root.findByProps({ testID: 'trainer-profile-save-button' }).props.onPress();
    });

    expect(trainerProfileApi.updateMyTrainerProfile).toHaveBeenCalledWith({
      publicSlug: null,
    });
  });

  it('does not call updateMyTrainerProfile API if no changes are made', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode and immediately save
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-edit-button' }).props.onPress();
    });

    await renderer.act(async () => {
      root.findByProps({ testID: 'trainer-profile-save-button' }).props.onPress();
    });

    expect(trainerProfileApi.updateMyTrainerProfile).not.toHaveBeenCalled();
  });

  it('prevents duplicate concurrent submissions during save using synchronous ref lock', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);

    let resolveUpdate!: (val: any) => void;
    const deferredPromise = new Promise((resolve) => {
      resolveUpdate = resolve;
    });
    (trainerProfileApi.updateMyTrainerProfile as jest.Mock).mockReturnValue(deferredPromise);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-edit-button' }).props.onPress();
    });

    // Change experience
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-experience-input' }).props.onChangeText('8');
    });

    const saveBtn = root.findByProps({ testID: 'trainer-profile-save-button' });

    // Rapid double press
    renderer.act(() => {
      saveBtn.props.onPress();
      saveBtn.props.onPress();
    });

    expect(trainerProfileApi.updateMyTrainerProfile).toHaveBeenCalledTimes(1);

    await renderer.act(async () => {
      resolveUpdate({ ...mockProfile, yearsExperience: 8 });
    });
  });

  it('displays error when update returns 409 slug conflict', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);
    (trainerProfileApi.updateMyTrainerProfile as jest.Mock).mockRejectedValueOnce(
      new ApiError(409, 'Public slug is already taken', {
        errorCode: 'TRAINER_SLUG_ALREADY_EXISTS',
        message: 'Public slug taken',
        timestamp: '2026-09-20T10:00:00Z',
        requestId: 'req-409',
        fieldErrors: [],
      })
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-edit-button' }).props.onPress();
    });

    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-slug-input' }).props.onChangeText('taken-slug');
    });

    await renderer.act(async () => {
      root.findByProps({ testID: 'trainer-profile-save-button' }).props.onPress();
    });

    const errorField = root.findAll((n) =>
      typeof n.props.children === 'string' && n.props.children.includes('already in use')
    );
    expect(errorField.length).toBeGreaterThan(0);
  });

  it('displays coaching eligibility badge as separate from coaching authority', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'trainer-profile-coaching-eligibility' })).toBeDefined();
    const authorityNode = root.findByProps({ testID: 'trainer-profile-coaching-authority' });
    expect(
      authorityNode.findAll((n) => {
        const text = Array.isArray(n.props.children)
          ? n.props.children.join('')
          : String(n.props.children ?? '');
        return text.includes('Unavailable');
      }).length
    ).toBeGreaterThan(0);
  });

  it('does not display Authorized even if coachingEligibility.eligible is true when canCoach is false', async () => {
    const eligibleProfile: TrainerProfileResponse = {
      ...mockProfile,
      coachingEligibility: {
        eligible: true,
        blockingReasons: [],
      },
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(eligibleProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    const authorityNode = root.findByProps({ testID: 'trainer-profile-coaching-authority' });
    expect(
      authorityNode.findAll((n) => {
        const text = Array.isArray(n.props.children)
          ? n.props.children.join('')
          : String(n.props.children ?? '');
        return text.includes('Unavailable');
      }).length
    ).toBeGreaterThan(0);
  });
});
