import React from 'react';
import renderer from 'react-test-renderer';
import { HomeScreen } from '@/features/auth/HomeScreen';
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

describe('Progress Summary Navigation Entries', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders student progress summary navigation entry and navigates to /progress-summary', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: studentUser,
      logout: jest.fn(),
    });

    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<HomeScreen />);
    });

    const button = tree.root.findByProps({ testID: 'view-progress-summary-button' });
    expect(button.props.accessibilityRole).toBe('button');
    expect(button.props.accessibilityLabel).toBe('Xem tóm tắt tiến độ AI');

    renderer.act(() => {
      button.props.onPress();
    });

    expect(mockPush).toHaveBeenCalledWith('/progress-summary');
  });
});
