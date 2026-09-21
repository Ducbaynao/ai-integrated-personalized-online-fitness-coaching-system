import React from 'react';
import renderer from 'react-test-renderer';
import { ChoosePurposeScreen } from '@/features/onboarding/ChoosePurposeScreen';
import { useAuth } from '@/features/auth/AuthContext';
import { useRouter } from 'expo-router';

jest.mock('expo-router', () => ({
  useRouter: jest.fn(),
}));

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

describe('ChoosePurposeScreen Component', () => {
  const mockPush = jest.fn();
  const mockLogout = jest.fn();

  beforeEach(() => {
    jest.clearAllMocks();
    (useRouter as jest.Mock).mockReturnValue({
      push: mockPush,
    });
    (useAuth as jest.Mock).mockReturnValue({
      user: { email: 'newuser@example.com' },
      logout: mockLogout,
    });
  });

  it('renders welcome header, purpose cards, and user info', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ChoosePurposeScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'choose-purpose-screen' })).toBeDefined();
    expect(root.findByProps({ testID: 'select-student-purpose-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'select-trainer-purpose-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'choose-purpose-logout-button' })).toBeDefined();
  });

  it('navigates to student onboarding when student card is selected', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ChoosePurposeScreen />);
    });
    const root = tree!.root;

    const studentBtn = root.findByProps({ testID: 'select-student-purpose-button' });
    renderer.act(() => {
      studentBtn.props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/(onboarding)/student');
  });

  it('navigates to trainer onboarding when trainer card is selected', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ChoosePurposeScreen />);
    });
    const root = tree!.root;

    const trainerBtn = root.findByProps({ testID: 'select-trainer-purpose-button' });
    renderer.act(() => {
      trainerBtn.props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/(onboarding)/trainer');
  });

  it('calls logout when sign out button is pressed', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ChoosePurposeScreen />);
    });
    const root = tree!.root;

    const logoutBtn = root.findByProps({ testID: 'choose-purpose-logout-button' });
    renderer.act(() => {
      logoutBtn.props.onPress();
    });

    expect(mockLogout).toHaveBeenCalled();
  });
});
