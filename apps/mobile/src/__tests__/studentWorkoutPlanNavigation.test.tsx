import React from 'react';
import renderer from 'react-test-renderer';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { useAuth } from '@/features/auth/AuthContext';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));

describe('Student Workout Plan navigation', () => {
  it('provides an accessible Student Home entry', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', displayName: 'An', roles: ['STUDENT'], capabilities: { hasStudentProfile: true, hasTrainerProfile: false, canCoach: false }, settings: {} }, logout: jest.fn() });
    let tree!: renderer.ReactTestRenderer; renderer.act(() => { tree = renderer.create(<HomeScreen />); });
    const entry = tree.root.findByProps({ testID: 'view-workout-plans-button' });
    expect(entry.props.accessibilityRole).toBe('button');
    expect(entry.props.accessibilityLabel).toBe('Mở kế hoạch tập luyện');
    renderer.act(() => entry.props.onPress());
    expect(mockPush).toHaveBeenCalledWith('/workout-plans');
    renderer.act(() => tree.unmount());
  });
});
