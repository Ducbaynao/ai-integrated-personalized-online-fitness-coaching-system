import React from 'react';
import renderer from 'react-test-renderer';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { TrainerHomeScreen } from '@/features/trainer/TrainerHomeScreen';
import { useAuth } from '@/features/auth/AuthContext';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));

const user = {
  id: 'student-1', email: 'student@example.com', displayName: 'Học viên', status: 'ACTIVE', roles: ['STUDENT'],
  timezone: 'Asia/Ho_Chi_Minh', settings: { measurementSystem: 'METRIC', weekStartsOn: 1 },
  capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false },
};

describe('coaching navigation entries', () => {
  beforeEach(() => { jest.clearAllMocks(); (useAuth as jest.Mock).mockReturnValue({ user, logout: jest.fn() }); });

  it('provides an accessible Student entry', () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<HomeScreen />); });
    const entry = tree.root.findByProps({ testID: 'view-coaching-button' });
    expect(entry.props.accessibilityRole).toBe('button');
    expect(entry.props.accessibilityLabel).toBe('Quản lý quan hệ huấn luyện');
    renderer.act(() => entry.props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/coaching');
  });

  it('provides an accessible Trainer entry even before coaching eligibility', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { ...user, roles: ['TRAINER'], capabilities: { hasStudentProfile: false, hasTrainerProfile: true, canCoach: false } }, logout: jest.fn() });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<TrainerHomeScreen />); });
    const entry = tree.root.findByProps({ testID: 'trainer-view-coaching-button' });
    expect(entry.props.accessibilityRole).toBe('button');
    renderer.act(() => entry.props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/coaching');
  });
});
