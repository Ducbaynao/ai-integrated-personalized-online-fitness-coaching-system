import React from 'react';
import renderer from 'react-test-renderer';
import { CapabilitySwitcher } from '@/components/CapabilitySwitcher';
import { useAuth } from '@/features/auth/AuthContext';
import { CurrentUserResponse } from '@/types/auth';

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

describe('CapabilitySwitcher Component', () => {
  const createMockUser = (hasStudentProfile: boolean, hasTrainerProfile: boolean): CurrentUserResponse => ({
    id: 'user-123',
    email: 'user@example.com',
    displayName: 'Dual Athlete',
    status: 'ACTIVE',
    preferredLocale: 'vi-VN',
    timezone: 'Asia/Ho_Chi_Minh',
    emailVerifiedAt: '2026-09-20T08:00:00Z',
    createdAt: '2026-09-20T08:00:00Z',
    phoneNumber: null,
    roles: ['STUDENT', 'TRAINER'],
    capabilities: {
      hasStudentProfile,
      hasTrainerProfile,
      canCoach: false,
    },
    settings: {
      weekStartsOn: 1,
      measurementSystem: 'METRIC',
      accessibilityPreferences: {},
      privacyPreferences: {},
    },
  });

  const mockSetActiveCapability = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders nothing when user is student-only', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: createMockUser(true, false),
      activeCapability: 'STUDENT',
      setActiveCapability: mockSetActiveCapability,
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<CapabilitySwitcher />);
    });
    expect(tree!.toJSON()).toBeNull();
  });

  it('renders nothing when user is trainer-only', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: createMockUser(false, true),
      activeCapability: 'TRAINER',
      setActiveCapability: mockSetActiveCapability,
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<CapabilitySwitcher />);
    });
    expect(tree!.toJSON()).toBeNull();
  });

  it('renders switcher tabs when user is dual-role', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: createMockUser(true, true),
      activeCapability: 'STUDENT',
      setActiveCapability: mockSetActiveCapability,
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<CapabilitySwitcher />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'capability-switcher' })).toBeDefined();
    expect(root.findByProps({ testID: 'switch-to-student' })).toBeDefined();
    expect(root.findByProps({ testID: 'switch-to-trainer' })).toBeDefined();
  });

  it('switches capability to TRAINER when trainer tab is tapped', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: createMockUser(true, true),
      activeCapability: 'STUDENT',
      setActiveCapability: mockSetActiveCapability,
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<CapabilitySwitcher />);
    });
    const root = tree!.root;

    const trainerTab = root.findByProps({ testID: 'switch-to-trainer' });
    renderer.act(() => {
      trainerTab.props.onPress();
    });

    expect(mockSetActiveCapability).toHaveBeenCalledWith('TRAINER');
  });

  it('switches capability to STUDENT when student tab is tapped', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: createMockUser(true, true),
      activeCapability: 'TRAINER',
      setActiveCapability: mockSetActiveCapability,
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<CapabilitySwitcher />);
    });
    const root = tree!.root;

    const studentTab = root.findByProps({ testID: 'switch-to-student' });
    renderer.act(() => {
      studentTab.props.onPress();
    });

    expect(mockSetActiveCapability).toHaveBeenCalledWith('STUDENT');
  });

  it('does not invoke setActiveCapability if already in selected mode', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: createMockUser(true, true),
      activeCapability: 'STUDENT',
      setActiveCapability: mockSetActiveCapability,
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<CapabilitySwitcher />);
    });
    const root = tree!.root;

    const studentTab = root.findByProps({ testID: 'switch-to-student' });
    renderer.act(() => {
      studentTab.props.onPress();
    });

    expect(mockSetActiveCapability).not.toHaveBeenCalled();
  });
});
