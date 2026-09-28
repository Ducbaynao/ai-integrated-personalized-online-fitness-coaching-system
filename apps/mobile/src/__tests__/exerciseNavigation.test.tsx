import React from 'react';
import renderer from 'react-test-renderer';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { TrainerHomeScreen } from '@/features/trainer/TrainerHomeScreen';
import { useAuth } from '@/features/auth/AuthContext';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));

const baseUser = {
  id: 'user-1',
  email: 'user@example.com',
  displayName: 'Người dùng',
  status: 'ACTIVE',
  roles: ['STUDENT'],
  timezone: 'Asia/Ho_Chi_Minh',
  settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
};

describe('exercise catalog navigation access', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: baseUser, logout: jest.fn() });
  });

  it('provides an accessible Student entry', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<HomeScreen />); });
    const entry = tree!.root.findByProps({ testID: 'view-exercise-library-button' });
    expect(entry.props.accessibilityRole).toBe('button');
    expect(entry.props.accessibilityLabel).toBe('Mở thư viện bài tập');
    renderer.act(() => entry.props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/exercises');
  });

  it('provides an accessible Trainer entry regardless of coaching authority', () => {
    (useAuth as jest.Mock).mockReturnValue({
      user: { ...baseUser, roles: ['TRAINER'], capabilities: { hasStudentProfile: false, hasTrainerProfile: true, canCoach: false } },
      logout: jest.fn(),
    });
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<TrainerHomeScreen />); });
    const entry = tree!.root.findByProps({ testID: 'trainer-view-exercise-library-button' });
    expect(entry.props.accessibilityRole).toBe('button');
    expect(entry.props.accessibilityLabel).toBe('Mở thư viện bài tập');
    renderer.act(() => entry.props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/exercises');
  });
});
