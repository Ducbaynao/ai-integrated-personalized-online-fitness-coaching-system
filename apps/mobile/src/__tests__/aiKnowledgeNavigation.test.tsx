import React from 'react';
import renderer from 'react-test-renderer';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { TrainerHomeScreen } from '@/features/trainer/TrainerHomeScreen';
import { useAuth } from '@/features/auth/AuthContext';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({ push: mockPush }),
}));
jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: jest.fn(),
}));

const studentUser = {
  id: 'student-1',
  email: 'student@example.com',
  displayName: 'Học viên Test',
  status: 'ACTIVE',
  roles: ['STUDENT'],
  timezone: 'Asia/Ho_Chi_Minh',
  settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
};

const trainerUser = {
  id: 'trainer-1',
  email: 'trainer@example.com',
  displayName: 'Coach Test',
  status: 'ACTIVE',
  roles: ['TRAINER'],
  timezone: 'Asia/Ho_Chi_Minh',
  settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: false, hasTrainerProfile: true, canCoach: true },
};

describe('AI Knowledge Navigation Entries', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders student AI Knowledge navigation entry and navigates to /ai-knowledge', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: studentUser,
      logout: jest.fn(),
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<HomeScreen />);
    });

    const button = tree.root.findByProps({ testID: 'view-ai-knowledge-button' });
    expect(button.props.accessibilityRole).toBe('button');
    expect(button.props.accessibilityLabel).toBe('Tra cứu kiến thức thể hình AI');

    renderer.act(() => {
      button.props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/ai-knowledge');
  });

  it('renders trainer AI Knowledge navigation entry and navigates to /ai-knowledge', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: trainerUser,
      logout: jest.fn(),
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerHomeScreen />);
    });

    const button = tree.root.findByProps({ testID: 'trainer-view-ai-knowledge-button' });
    expect(button.props.accessibilityRole).toBe('button');
    expect(button.props.accessibilityLabel).toBe('Tra cứu kiến thức thể hình AI');

    renderer.act(() => {
      button.props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/ai-knowledge');
  });
});
