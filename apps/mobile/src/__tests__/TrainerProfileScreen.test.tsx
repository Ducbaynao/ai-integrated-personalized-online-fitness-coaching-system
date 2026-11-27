import { expectTimeDisplay, TIME_BOUNDARY, VIETNAM_DAY } from '@/test-utils/timeDisplayRegression';
import React from 'react';
import renderer from 'react-test-renderer';
import { StyleSheet } from 'react-native';
import { SafeAreaView } from 'react-native-safe-area-context';
import { TrainerProfileScreen } from '@/features/trainer/TrainerProfileScreen';
import { layout } from '@/design-system/tokens/spacing';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { TrainerProfileResponse } from '@/types/trainer';
import { ApiError } from '@/types/auth';
import { useAuth } from '@/features/auth/AuthContext';
import * as DocumentPicker from 'expo-document-picker';
import { trainerApplicationApi } from '@/services/trainerApplicationApi';
import { queryClient } from '@/services/queryClient';
import { coachingQueryKeys } from '@/features/coaching/coachingQueries';

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

jest.mock('@/services/trainerApplicationApi', () => ({
  trainerApplicationApi: {
    submitApplication: jest.fn(),
    getCurrentApplication: jest.fn(),
    uploadDocument: jest.fn(),
  },
}));

jest.mock('expo-document-picker', () => ({
  getDocumentAsync: jest.fn(),
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

  const mockRefreshUser = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    mockRefreshUser.mockResolvedValue(undefined);
    (useAuth as jest.Mock).mockReturnValue({
      user: mockUser,
      refreshUser: mockRefreshUser,
    });
  });

  it('places Overview navigation in the safe area and keeps its route during edit', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(mockProfile);
    (trainerApplicationApi.getCurrentApplication as jest.Mock).mockResolvedValueOnce(null);
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => { tree = renderer.create(<TrainerProfileScreen />); });
    const root = tree.root;
    const safeArea = root.findByType(SafeAreaView);
    expect(safeArea.props.edges).toEqual(['top', 'bottom']);
    const overview = safeArea.findByProps({ testID: 'trainer-profile-back-button' });
    expect(overview.props.accessibilityRole).toBe('button');
    expect(StyleSheet.flatten(overview.props.style).minHeight).toBe(layout.minimumTouchTarget);
    renderer.act(() => { overview.props.onPress(); });
    expect(mockReplace).toHaveBeenCalledWith('/(app)');
    renderer.act(() => { root.findByProps({ testID: 'trainer-profile-edit-button' }).props.onPress(); });
    renderer.act(() => { root.findByProps({ testID: 'trainer-profile-back-button' }).props.onPress(); });
    expect(mockReplace).toHaveBeenCalledTimes(2);
    expect(mockReplace).toHaveBeenLastCalledWith('/(app)');
  }, 15000);

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
      verificationNode.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('Đang chờ xác minh')).length
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
      typeof n.props.children === 'string' && n.props.children.includes('đã được dùng')
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
        return text.includes('Chưa được cấp');
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
        return text.includes('Chưa được cấp');
      }).length
    ).toBeGreaterThan(0);
  });

  it('renders submit application form when NOT_SUBMITTED and submits successfully', async () => {
    const unsubmittedProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'NOT_SUBMITTED',
      coachingEligibility: {
        eligible: false,
        blockingReasons: ['APPLICATION_NOT_SUBMITTED'],
      },
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockResolvedValueOnce(unsubmittedProfile)
      .mockResolvedValueOnce({ ...unsubmittedProfile, verificationStatus: 'PENDING' });

    (trainerApplicationApi.submitApplication as jest.Mock).mockResolvedValueOnce({
      id: 'app-new',
      trainerId: 'user-trainer-1',
      status: 'PENDING',
      submittedAt: '2026-11-08T10:00:00Z',
      reviewedAt: null,
      rejectionReason: null,
      updatedAt: '2026-11-08T10:00:00Z',
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'trainer-application-not-submitted-card' })).toBeDefined();

    const noteInput = root.findByProps({ testID: 'trainer-application-note-input' });
    renderer.act(() => {
      noteInput.props.onChangeText('Experienced coach with certifications');
    });

    const submitBtn = root.findByProps({ testID: 'trainer-application-submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });

    expect(trainerApplicationApi.submitApplication).toHaveBeenCalledWith({
      applicantNote: 'Experienced coach with certifications',
    });
    expect(root.findByProps({ testID: 'trainer-application-success-banner' })).toBeDefined();
  });

  it('displays rejection reason and allows resubmitting when REJECTED', async () => {
    const rejectedProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'REJECTED',
      coachingEligibility: {
        eligible: false,
        blockingReasons: ['VERIFICATION_REJECTED'],
      },
    };
    const mockApp = {
      id: 'app-rej',
      trainerId: 'user-trainer-1',
      status: 'REJECTED',
      submittedAt: '2026-11-07T10:00:00Z',
      reviewedAt: '2026-11-07T14:00:00Z',
      rejectionReason: 'Certification expired. Please submit current certification.',
      updatedAt: '2026-11-07T14:00:00Z',
    };

    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockResolvedValueOnce(rejectedProfile)
      .mockResolvedValueOnce({ ...rejectedProfile, verificationStatus: 'PENDING' });
    (trainerApplicationApi.getCurrentApplication as jest.Mock).mockResolvedValueOnce(mockApp);

    (trainerApplicationApi.submitApplication as jest.Mock).mockResolvedValueOnce({
      ...mockApp,
      status: 'PENDING',
      rejectionReason: null,
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'trainer-application-rejected-card' })).toBeDefined();
    const reasonNode = root.findByProps({ testID: 'trainer-application-rejection-reason' });
    expect(
      reasonNode.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('Certification expired')).length
    ).toBeGreaterThan(0);

    const noteInput = root.findByProps({ testID: 'trainer-application-resubmit-note-input' });
    renderer.act(() => {
      noteInput.props.onChangeText('Uploaded renewed certificate valid through 2028');
    });

    const resubmitBtn = root.findByProps({ testID: 'trainer-application-resubmit-button' });
    await renderer.act(async () => {
      resubmitBtn.props.onPress();
    });

    expect(trainerApplicationApi.submitApplication).toHaveBeenCalledWith({
      applicantNote: 'Uploaded renewed certificate valid through 2028',
    });
  });

  it('displays verified banner when VERIFIED', async () => {
    const verifiedProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'VERIFIED',
      verifiedAt: '2026-11-01T08:00:00Z',
      coachingEligibility: {
        eligible: true,
        blockingReasons: [],
      },
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(verifiedProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'trainer-application-verified-banner' })).toBeDefined();
  });

  it('allows picking, uploading, removing documents and includes documentMediaIds on submit', async () => {
    const unsubmittedProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'NOT_SUBMITTED',
      coachingEligibility: {
        eligible: false,
        blockingReasons: ['APPLICATION_NOT_SUBMITTED'],
      },
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockResolvedValueOnce(unsubmittedProfile)
      .mockResolvedValueOnce({ ...unsubmittedProfile, verificationStatus: 'PENDING' });

    (DocumentPicker.getDocumentAsync as jest.Mock)
      .mockResolvedValueOnce({
        canceled: false,
        assets: [
          {
            uri: 'file:///data/cert1.pdf',
            name: 'cert1.pdf',
            mimeType: 'application/pdf',
            size: 102400,
          },
        ],
      })
      .mockResolvedValueOnce({
        canceled: false,
        assets: [
          {
            uri: 'file:///data/cert2.png',
            name: 'cert2.png',
            mimeType: 'image/png',
            size: 204800,
          },
        ],
      });

    (trainerApplicationApi.uploadDocument as jest.Mock)
      .mockResolvedValueOnce({
        id: 'doc-uuid-1',
        originalFilename: 'cert1.pdf',
        contentType: 'application/pdf',
        sizeBytes: 102400,
        scanStatus: 'CLEAN',
        createdAt: '2026-11-08T10:00:00Z',
      })
      .mockResolvedValueOnce({
        id: 'doc-uuid-2',
        originalFilename: 'cert2.png',
        contentType: 'image/png',
        sizeBytes: 204800,
        scanStatus: 'CLEAN',
        createdAt: '2026-11-08T10:00:00Z',
      });

    (trainerApplicationApi.submitApplication as jest.Mock).mockResolvedValueOnce({
      id: 'app-with-docs',
      trainerId: 'user-trainer-1',
      status: 'PENDING',
      submittedAt: '2026-11-08T10:00:00Z',
      reviewedAt: null,
      rejectionReason: null,
      updatedAt: '2026-11-08T10:00:00Z',
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    const uploadBtn = root.findByProps({ testID: 'trainer-document-upload-button' });

    // Upload first doc
    await renderer.act(async () => {
      await uploadBtn.props.onPress();
    });

    expect(trainerApplicationApi.uploadDocument).toHaveBeenCalledWith(
      'file:///data/cert1.pdf',
      'cert1.pdf',
      'application/pdf',
      undefined,
      expect.objectContaining({
        sessionBinding: expect.objectContaining({
          authEpoch: expect.any(Number),
          capabilityEpoch: expect.any(Number),
        }),
        shouldContinue: expect.any(Function),
      }),
    );
    expect(root.findByProps({ testID: 'uploaded-doc-item-doc-uuid-1' })).toBeDefined();

    // Upload second doc
    await renderer.act(async () => {
      await uploadBtn.props.onPress();
    });

    expect(trainerApplicationApi.uploadDocument).toHaveBeenCalledWith(
      'file:///data/cert2.png',
      'cert2.png',
      'image/png',
      undefined,
      expect.objectContaining({
        sessionBinding: expect.objectContaining({
          authEpoch: expect.any(Number),
          capabilityEpoch: expect.any(Number),
        }),
        shouldContinue: expect.any(Function),
      }),
    );
    expect(root.findByProps({ testID: 'uploaded-doc-item-doc-uuid-2' })).toBeDefined();

    // Remove second doc
    const removeBtn = root.findByProps({ testID: 'remove-doc-doc-uuid-2' });
    renderer.act(() => {
      removeBtn.props.onPress();
    });

    expect(root.findAllByProps({ testID: 'uploaded-doc-item-doc-uuid-2' }).length).toBe(0);
    expect(root.findByProps({ testID: 'uploaded-doc-item-doc-uuid-1' })).toBeDefined();

    // Submit
    const submitBtn = root.findByProps({ testID: 'trainer-application-submit-button' });
    await renderer.act(async () => {
      await submitBtn.props.onPress();
    });

    expect(trainerApplicationApi.submitApplication).toHaveBeenCalledWith({
      documentMediaIds: ['doc-uuid-1'],
    });
  });

  it('displays error and does not upload when document exceeds 10MB limit', async () => {
    const unsubmittedProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'NOT_SUBMITTED',
      coachingEligibility: {
        eligible: false,
        blockingReasons: ['APPLICATION_NOT_SUBMITTED'],
      },
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(unsubmittedProfile);

    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValueOnce({
      canceled: false,
      assets: [
        {
          uri: 'file:///data/huge.pdf',
          name: 'huge.pdf',
          mimeType: 'application/pdf',
          size: 15 * 1024 * 1024,
        },
      ],
    });

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree!.root;

    const uploadBtn = root.findByProps({ testID: 'trainer-document-upload-button' });
    await renderer.act(async () => {
      await uploadBtn.props.onPress();
    });

    expect(trainerApplicationApi.uploadDocument).not.toHaveBeenCalled();
    const errorNode = root.findByProps({ testID: 'trainer-document-upload-error' });
    expect(
      errorNode.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('giới hạn 10 MB')).length
    ).toBeGreaterThan(0);
  });

  it('syncs auth capability and coaching cache on VERIFIED load, and prompts to enable acceptingStudents when off', async () => {
    const invalidateSpy = jest.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined);
    const verifiedNotAccepting: TrainerProfileResponse = {
      ...mockProfile,
      acceptingStudents: false,
      verificationStatus: 'VERIFIED',
      coachingEligibility: {
        eligible: true,
        blockingReasons: [],
      },
      verifiedAt: '2026-09-25T10:00:00Z',
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(verifiedNotAccepting);
    (trainerApplicationApi.getCurrentApplication as jest.Mock).mockResolvedValueOnce(null);

    try {
      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<TrainerProfileScreen />);
      });
      const root = tree.root;

      expect(mockRefreshUser).toHaveBeenCalled();
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: coachingQueryKeys.all });
      expect(root.findByProps({ testID: 'trainer-accepting-students-notice' })).toBeDefined();

      const enableBtn = root.findByProps({ testID: 'trainer-enable-accepting-button' });
      renderer.act(() => {
        enableBtn.props.onPress();
      });
      expect(root.findByProps({ testID: 'trainer-profile-accepting-switch' })).toBeDefined();
    } finally {
      invalidateSpy.mockRestore();
    }
  });

  it('renders SUSPENDED verification banner when profile is suspended', async () => {
    const suspendedProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'SUSPENDED',
      coachingEligibility: {
        eligible: false,
        blockingReasons: ['VERIFICATION_REVOKED'],
      },
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(suspendedProfile);
    (trainerApplicationApi.getCurrentApplication as jest.Mock).mockResolvedValueOnce(null);

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });

    expect(tree.root.findByProps({ testID: 'trainer-application-suspended-banner' })).toBeDefined();
  });

  it('surfaces SCANNER_UNAVAILABLE error clearly and re-syncs on 409 TRAINER_APPLICATION_ALREADY_ACTIVE', async () => {
    const makeApiError = (status: number, errorCode: string, message: string) =>
      new ApiError(status, message, {
        timestamp: '2026-09-25T10:00:00Z',
        requestId: 'req-1',
        errorCode,
        message,
        fieldErrors: [],
      });

    const unsubmittedProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'NOT_SUBMITTED',
      coachingEligibility: {
        eligible: false,
        blockingReasons: ['APPLICATION_NOT_SUBMITTED'],
      },
    };
    const pendingProfile: TrainerProfileResponse = {
      ...mockProfile,
      verificationStatus: 'PENDING',
    };
    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockResolvedValueOnce(unsubmittedProfile)
      .mockResolvedValueOnce(pendingProfile);
    (trainerApplicationApi.getCurrentApplication as jest.Mock)
      .mockRejectedValueOnce(makeApiError(404, 'TRAINER_APPLICATION_NOT_FOUND', 'Not found'))
      .mockResolvedValueOnce({
        id: 'app-existing-1',
        trainerId: 'user-trainer-1',
        status: 'PENDING',
        submittedAt: '2026-09-25T10:00:00Z',
        reviewedAt: null,
        rejectionReason: null,
        applicantNote: null,
        certificateIds: [],
        documentMediaIds: [],
        createdAt: '2026-09-25T10:00:00Z',
        updatedAt: '2026-09-25T10:00:00Z',
      });
    (DocumentPicker.getDocumentAsync as jest.Mock).mockResolvedValueOnce({
      canceled: false,
      assets: [{ uri: 'file:///data/cert.pdf', name: 'cert.pdf', mimeType: 'application/pdf', size: 1024 }],
    });
    (trainerApplicationApi.uploadDocument as jest.Mock).mockRejectedValueOnce(
      makeApiError(503, 'SCANNER_UNAVAILABLE', 'Scanner down'),
    );
    (trainerApplicationApi.submitApplication as jest.Mock).mockRejectedValueOnce(
      makeApiError(409, 'TRAINER_APPLICATION_ALREADY_ACTIVE', 'Already active'),
    );

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree.root;

    await renderer.act(async () => {
      await root.findByProps({ testID: 'trainer-document-upload-button' }).props.onPress();
    });
    const uploadErr = root.findByProps({ testID: 'trainer-document-upload-error' });
    expect(uploadErr.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('quét an toàn')).length).toBeGreaterThan(0);

    await renderer.act(async () => {
      await root.findByProps({ testID: 'trainer-application-submit-button' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'trainer-application-pending-banner' })).toBeDefined();
    expect(mockRefreshUser).toHaveBeenCalled();
  });

  it('preserves dirty editor draft when a pending refresh resolves mid-edit and keeps cancel/save behavior', async () => {
    let resolveRefresh!: (value: TrainerProfileResponse) => void;
    const pendingRefresh = new Promise<TrainerProfileResponse>((resolve) => {
      resolveRefresh = resolve;
    });

    const initialProfile: TrainerProfileResponse = {
      ...mockProfile,
      bio: 'original bio',
      verificationStatus: 'NOT_SUBMITTED',
      coachingEligibility: { eligible: false, blockingReasons: ['APPLICATION_NOT_SUBMITTED'] },
    };
    const refreshedServerProfile: TrainerProfileResponse = {
      ...initialProfile,
      bio: 'server refreshed bio',
    };
    const savedProfile: TrainerProfileResponse = {
      ...refreshedServerProfile,
      bio: 'unsaved new draft',
    };

    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockResolvedValueOnce(initialProfile)
      .mockReturnValueOnce(pendingRefresh);
    (trainerProfileApi.updateMyTrainerProfile as jest.Mock).mockResolvedValueOnce(savedProfile);

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree.root;

    let refreshPromise!: Promise<void>;
    renderer.act(() => {
      refreshPromise = root.findByProps({ testID: 'trainer-profile-screen' }).props.refreshControl.props.onRefresh();
    });

    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-edit-button' }).props.onPress();
    });
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-bio-input' }).props.onChangeText('unsaved new draft');
    });
    expect(root.findByProps({ testID: 'trainer-profile-bio-input' }).props.value).toBe('unsaved new draft');

    await renderer.act(async () => {
      resolveRefresh(refreshedServerProfile);
      await refreshPromise;
    });

    expect(root.findByProps({ testID: 'trainer-profile-bio-input' }).props.value).toBe('unsaved new draft');

    await renderer.act(async () => {
      await root.findByProps({ testID: 'trainer-profile-save-button' }).props.onPress();
    });
    expect(trainerProfileApi.updateMyTrainerProfile).toHaveBeenCalledWith({ bio: 'unsaved new draft' });
    expect(root.findByProps({ testID: 'trainer-profile-success-banner' })).toBeDefined();
  });

  it('ignores stale pre-save refresh resolution after save completes', async () => {
    let resolveStaleRefresh!: (value: TrainerProfileResponse) => void;
    const pendingRefresh = new Promise<TrainerProfileResponse>((resolve) => {
      resolveStaleRefresh = resolve;
    });

    const initialProfile: TrainerProfileResponse = {
      ...mockProfile,
      bio: 'initial bio',
      verificationStatus: 'VERIFIED',
      coachingEligibility: { eligible: true, blockingReasons: [] },
    };
    const savedProfile: TrainerProfileResponse = {
      ...initialProfile,
      bio: 'newly saved bio',
    };

    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockResolvedValueOnce(initialProfile)
      .mockReturnValueOnce(pendingRefresh);
    (trainerProfileApi.updateMyTrainerProfile as jest.Mock).mockResolvedValueOnce(savedProfile);

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerProfileScreen />);
    });
    const root = tree.root;

    let refreshPromise!: Promise<void>;
    renderer.act(() => {
      refreshPromise = root.findByProps({ testID: 'trainer-profile-screen' }).props.refreshControl.props.onRefresh();
    });
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-edit-button' }).props.onPress();
    });
    renderer.act(() => {
      root.findByProps({ testID: 'trainer-profile-bio-input' }).props.onChangeText('newly saved bio');
    });
    await renderer.act(async () => {
      await root.findByProps({ testID: 'trainer-profile-save-button' }).props.onPress();
    });

    await renderer.act(async () => {
      resolveStaleRefresh(initialProfile);
      await refreshPromise;
    });

    expect(
      root.findAll((n) => typeof n.props.children === 'string' && n.props.children === 'newly saved bio').length,
    ).toBeGreaterThan(0);
    expect(
      root.findAll((n) => typeof n.props.children === 'string' && n.props.children === 'initial bio'),
    ).toHaveLength(0);
  });
  it.each(['PENDING', 'VERIFIED'])('time display: application submission and verification day are Vietnam (%s)', async (verificationStatus) => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValue({ ...mockProfile, verificationStatus, verifiedAt: TIME_BOUNDARY });
    (trainerApplicationApi.getCurrentApplication as jest.Mock).mockResolvedValue({ id: 'app-1', status: 'APPROVED', submittedAt: TIME_BOUNDARY, documents: [] });
    await expectTimeDisplay(async () => {
      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => { tree = renderer.create(<TrainerProfileScreen />); });
      return tree;
    }, [`${verificationStatus === 'PENDING' ? 'Đã gửi:' : 'Xác minh ngày:'} ${VIETNAM_DAY}`]);
  });
});
