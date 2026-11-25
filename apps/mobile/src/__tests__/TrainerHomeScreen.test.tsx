import React from 'react';
import renderer from 'react-test-renderer';
import { ScrollView } from 'react-native';
import { TrainerHomeScreen } from '@/features/trainer/TrainerHomeScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { trainerProfileApi } from '@/services/trainerProfileApi';
import { queryClient } from '@/services/queryClient';
import { coachingQueryKeys } from '@/features/coaching/coachingQueries';
import { TrainerProfileResponse } from '@/types/trainer';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({
    push: mockPush,
    replace: jest.fn(),
  }),
}));

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

jest.mock('@/services/trainerProfileApi', () => ({
  trainerProfileApi: {
    getMyTrainerProfile: jest.fn(),
  },
}));

describe('TrainerHomeScreen', () => {
  const baseUser = {
    id: 'trainer-1',
    email: 'trainer@example.com',
    displayName: 'Minh Coach',
    status: 'ACTIVE',
    roles: ['USER', 'TRAINER'],
    timezone: 'Asia/Ho_Chi_Minh',
    settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
    capabilities: {
      hasStudentProfile: false,
      hasTrainerProfile: true,
      canCoach: false,
    },
  };

  const baseProfile: TrainerProfileResponse = {
    userId: 'trainer-1',
    publicSlug: 'minh-coach',
    bio: 'Strength training',
    yearsExperience: 5,
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

  const mockRefreshUser = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    mockRefreshUser.mockResolvedValue(undefined);
    (useAuth as jest.Mock).mockReturnValue({
      user: baseUser,
      logout: jest.fn(),
      refreshUser: mockRefreshUser,
    });
  });

  it('shows submit verification CTA for NOT_SUBMITTED and navigates to trainer-profile', async () => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce(baseProfile);

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerHomeScreen />);
    });

    const banner = tree.root.findByProps({ testID: 'trainer-authority-banner' });
    expect(
      banner.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('Chưa nộp hồ sơ xác minh')).length,
    ).toBeGreaterThan(0);

    const cta = tree.root.findByProps({ testID: 'trainer-submit-verification-cta' });
    expect(cta.props.accessibilityLabel).toBe('Nộp hồ sơ xác minh');
    renderer.act(() => {
      cta.props.onPress();
    });
    expect(mockPush).toHaveBeenCalledWith('/(app)/trainer-profile');
  });

  it.each([
    ['PENDING', 'Đang chờ Admin xét duyệt', 'Xem trạng thái hồ sơ xác minh'],
    ['REJECTED', 'Hồ sơ bị từ chối', 'Xem lý do & gửi lại hồ sơ xác minh'],
    ['SUSPENDED', 'Đang bị tạm ngưng', 'Xem chi tiết hồ sơ xác minh'],
  ] as const)('displays status-accurate guidance for %s', async (status, expectedTitlePart, expectedCtaLabel) => {
    (trainerProfileApi.getMyTrainerProfile as jest.Mock).mockResolvedValueOnce({
      ...baseProfile,
      verificationStatus: status,
    });

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerHomeScreen />);
    });

    const banner = tree.root.findByProps({ testID: 'trainer-authority-banner' });
    expect(
      banner.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes(expectedTitlePart)).length,
    ).toBeGreaterThan(0);
    expect(tree.root.findByProps({ testID: 'trainer-submit-verification-cta' }).props.accessibilityLabel).toBe(
      expectedCtaLabel,
    );
  });

  it('warns verified trainer when acceptingStudents is false and syncs auth/cache on manual refresh', async () => {
    const invalidateSpy = jest.spyOn(queryClient, 'invalidateQueries').mockResolvedValue(undefined);
    (useAuth as jest.Mock).mockReturnValue({
      user: { ...baseUser, capabilities: { ...baseUser.capabilities, canCoach: true } },
      logout: jest.fn(),
      refreshUser: mockRefreshUser,
    });
    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockResolvedValueOnce({
        ...baseProfile,
        verificationStatus: 'VERIFIED',
        acceptingStudents: false,
        coachingEligibility: { eligible: true, blockingReasons: [] },
      })
      .mockResolvedValueOnce({
        ...baseProfile,
        verificationStatus: 'VERIFIED',
        acceptingStudents: true,
        coachingEligibility: { eligible: true, blockingReasons: [] },
      });

    try {
      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<TrainerHomeScreen />);
      });

      const banner = tree.root.findByProps({ testID: 'trainer-authority-banner' });
      expect(
        banner.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('đang tắt nhận học viên')).length,
      ).toBeGreaterThan(0);

      const refreshBtn = tree.root.findByProps({ testID: 'trainer-refresh-status-button' });
      await renderer.act(async () => {
        await refreshBtn.props.onPress();
      });

      expect(trainerProfileApi.getMyTrainerProfile).toHaveBeenCalledTimes(2);
      expect(mockRefreshUser).toHaveBeenCalled();
      expect(invalidateSpy).toHaveBeenCalledWith({ queryKey: coachingQueryKeys.all });
      expect(tree.root.findByType(ScrollView).props.refreshControl).toBeDefined();
    } finally {
      invalidateSpy.mockRestore();
    }
  });

  it('ignores stale pending mount response when a newer refresh already settled with PENDING', async () => {
    let resolveMount!: (value: TrainerProfileResponse) => void;
    const pendingMount = new Promise<TrainerProfileResponse>((resolve) => {
      resolveMount = resolve;
    });

    (trainerProfileApi.getMyTrainerProfile as jest.Mock)
      .mockReturnValueOnce(pendingMount)
      .mockResolvedValueOnce({
        ...baseProfile,
        verificationStatus: 'PENDING',
      });

    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<TrainerHomeScreen />);
    });

    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'trainer-refresh-status-button' }).props.onPress();
    });

    const bannerBefore = tree.root.findByProps({ testID: 'trainer-authority-banner' });
    expect(
      bannerBefore.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('Đang chờ Admin xét duyệt')).length,
    ).toBeGreaterThan(0);

    await renderer.act(async () => {
      resolveMount(baseProfile);
      await pendingMount;
    });

    const bannerAfter = tree.root.findByProps({ testID: 'trainer-authority-banner' });
    expect(
      bannerAfter.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('Đang chờ Admin xét duyệt')).length,
    ).toBeGreaterThan(0);
    expect(
      bannerAfter.findAll((n) => typeof n.props.children === 'string' && n.props.children.includes('Chưa nộp hồ sơ xác minh')),
    ).toHaveLength(0);
  });
});
