import React from 'react';
import renderer from 'react-test-renderer';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { useAuth } from '@/features/auth/AuthContext';

const mockPush = jest.fn();
jest.mock('expo-router', () => ({ useRouter: () => ({ push: mockPush }) }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/design-system/components/AppIcon', () => ({ AppIcon: () => null }));

const entries = [
  ['view-workout-plans-button', '/workout-plans'],
  ['view-fitness-goals-button', '/goals'],
  ['view-exercise-library-button', '/exercises'],
  ['view-nutrition-button', '/nutrition'],
  ['view-coaching-button', '/coaching'],
  ['view-progress-dashboard-button', '/progress'],
  ['view-measurements-button', '/measurements'],
  ['view-ai-knowledge-button', '/ai-knowledge'],
  ['view-progress-summary-button', '/progress-summary'],
  ['view-student-profile-button', '/profile'],
] as const;

function renderHome(hasStudentProfile = true, hasTrainerProfile = false, logout = jest.fn()) {
  (useAuth as jest.Mock).mockReturnValue({
    user: { displayName: 'Học viên có tên tiếng Việt dài', email: 'layout-preview@example.test', roles: ['STUDENT'], status: 'ACTIVE',
      capabilities: { hasStudentProfile, hasTrainerProfile, canCoach: false } },
    activeCapability: 'STUDENT', logout, setActiveCapability: jest.fn(),
  });
  let tree!: renderer.ReactTestRenderer;
  renderer.act(() => { tree = renderer.create(<HomeScreen />); });
  return tree;
}

function action(tree: renderer.ReactTestRenderer, testID: string) {
  return tree.root.findByProps({ testID }).findByProps({ accessibilityRole: 'button' });
}

describe('Student Home polish preserves existing behavior', () => {
  beforeEach(() => jest.clearAllMocks());

  it('retains all ten accessible navigation destinations and trainer activation', () => {
    const tree = renderHome();
    for (const [testID, route] of entries) {
      const button = action(tree, testID);
      expect(button.props.accessibilityRole).toBe('button');
      expect(button.props.accessibilityLabel.length).toBeGreaterThan(0);
      renderer.act(() => button.props.onPress());
      expect(mockPush).toHaveBeenLastCalledWith(route);
    }
    renderer.act(() => action(tree, 'student-cross-activate-trainer-button').props.onPress());
    expect(mockPush).toHaveBeenLastCalledWith('/(onboarding)/trainer');
    renderer.act(() => tree.unmount());
  });

  it('hides Student actions without the Student capability', () => {
    const tree = renderHome(false, true);
    for (const [testID] of entries) expect(tree.root.findAllByProps({ testID })).toHaveLength(0);
    expect(tree.root.findAllByProps({ testID: 'student-cross-activate-card' })).toHaveLength(0);
    expect(action(tree, 'logout-button')).toBeDefined();
    renderer.act(() => tree.unmount());
  });

  it('keeps the dual capability switcher and hides duplicate trainer activation', () => {
    const tree = renderHome(true, true);
    expect(tree.root.findByProps({ testID: 'capability-switcher' })).toBeDefined();
    expect(tree.root.findAllByProps({ testID: 'student-cross-activate-card' })).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('blocks repeated logout while the existing async operation is pending', async () => {
    let finish!: () => void;
    const logout = jest.fn(() => new Promise<void>(resolve => { finish = resolve; }));
    const tree = renderHome(true, false, logout);
    renderer.act(() => { void action(tree, 'logout-button').props.onPress(); });
    const pending = action(tree, 'logout-button');
    expect(pending.props.disabled).toBe(true);
    expect(pending.props.accessibilityState).toEqual({ disabled: true, busy: true });
    renderer.act(() => { void pending.props.onPress(); });
    expect(logout).toHaveBeenCalledTimes(1);
    await renderer.act(async () => { finish(); });
    expect(action(tree, 'logout-button').props.disabled).toBe(false);
    renderer.act(() => tree.unmount());
  });
});
