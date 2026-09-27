import React from 'react';
import renderer from 'react-test-renderer';
import { colors, getSemanticColors, darkSemanticColors, lightSemanticColors } from '@/design-system/tokens/colors';
import { layout } from '@/design-system/tokens/spacing';
import { SignInScreen } from '@/features/auth/SignInScreen';
import { RegisterScreen } from '@/features/auth/RegisterScreen';
import { VerifyEmailScreen } from '@/features/auth/VerifyEmailScreen';
import { HomeScreen } from '@/features/auth/HomeScreen';
import { SessionGateScreen } from '@/features/auth/SessionGateScreen';
import { ChoosePurposeScreen } from '@/features/onboarding/ChoosePurposeScreen';
import { TrainerOnboardingScreen } from '@/features/trainer/TrainerOnboardingScreen';

// Mock AuthContext for rendering screens
jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 'test-user-id',
      email: 'athlete@example.com',
      displayName: 'Alex Athlete',
      status: 'ACTIVE',
      roles: ['STUDENT'],
      capabilities: {
        hasStudentProfile: true,
        hasTrainerProfile: false,
        canCoach: false,
      },
      settings: {
        measurementSystem: 'METRIC',
        weekStartsOn: 1,
      },
    },
    login: jest.fn(),
    register: jest.fn(),
    confirmEmail: jest.fn(),
    logout: jest.fn(),
    refreshUser: jest.fn(),
    isLoading: false,
    isAuthenticated: true,
  }),
}));

describe('Design Tokens & Semantic Colors', () => {
  it('returns light semantic colors by default', () => {
    const light = getSemanticColors(false);
    expect(light).toEqual(lightSemanticColors);
    expect(light.canvas).toBe(colors.neutral[50]);
    expect(light.surface).toBe(colors.neutral[0]);
    expect(light.textPrimary).toBe(colors.neutral[900]);
    expect(light.textSecondary).toBe(colors.neutral[500]);
    expect(light.border).toBe(colors.neutral[200]);
    expect(light.primary).toBe(colors.brand[600]);
  });

  it('returns dark semantic colors when dark mode is enabled', () => {
    const dark = getSemanticColors(true);
    expect(dark).toEqual(darkSemanticColors);
    expect(dark.canvas).toBe(colors.neutral[950]);
    expect(dark.surface).toBe(colors.neutral[850]);
    expect(dark.textPrimary).toBe(colors.neutral[0]);
    expect(dark.textSecondary).toBe(colors.neutral[600]);
    expect(dark.border).toBe(colors.neutral[800]);
    expect(dark.primary).toBe(colors.brand[500]);
    expect(dark.dangerPressed).toBe(colors.danger[700]);
  });

  it('enforces minimum touch target of at least 44x44', () => {
    expect(layout.minimumTouchTarget).toBeGreaterThanOrEqual(44);
  });
});

describe('Accessibility Attributes on Auth Screens', () => {
  it('SessionGateScreen exposes a retryable alert when restoration fails', () => {
    const onRetry = jest.fn();
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(
        <SessionGateScreen mode="error" message="Không có kết nối." onRetry={onRetry} />
      );
    });

    const root = tree!.root;
    expect(root.findByProps({ testID: 'session-restore-error-screen' }).props.accessibilityRole).toBe(
      'alert'
    );

    renderer.act(() => {
      root.findByProps({ testID: 'retry-session-restore' }).props.onPress();
    });
    expect(onRetry).toHaveBeenCalledTimes(1);
  });

  it('SignInScreen defines proper accessibility roles and labels', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<SignInScreen />);
    });
    const root = tree!.root;

    // Email Input
    const emailInput = root.findByProps({ testID: 'email-input' });
    expect(emailInput.props.accessibilityLabel).toBe('Email');
    expect(emailInput.props.accessibilityHint).toBeDefined();

    // Password Input
    const passwordInput = root.findByProps({ testID: 'password-input' });
    expect(passwordInput.props.accessibilityLabel).toBe('Mật khẩu');
    expect(passwordInput.props.accessibilityHint).toBeDefined();

    // Submit Button
    const submitBtn = root.findByProps({ testID: 'submit-sign-in' });
    expect(submitBtn.props.accessibilityRole).toBe('button');
    expect(submitBtn.props.accessibilityLabel).toBe('Đăng nhập');
    expect(submitBtn.props.accessibilityState).toEqual({ disabled: false, busy: false });

    // Register Link
    const registerLink = root.findByProps({ testID: 'link-to-register' });
    expect(registerLink.props.accessibilityRole).toBe('link');

    // Verify Email Link
    const verifyLink = root.findByProps({ testID: 'link-to-verify-email' });
    expect(verifyLink.props.accessibilityRole).toBe('link');
  });

  it('RegisterScreen defines proper accessibility roles and labels', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<RegisterScreen />);
    });
    const root = tree!.root;

    // Display Name Input
    const nameInput = root.findByProps({ testID: 'display-name-input' });
    expect(nameInput.props.accessibilityLabel).toBe('Họ và tên');
    expect(nameInput.props.accessibilityHint).toBeDefined();

    // Email Input
    const emailInput = root.findByProps({ testID: 'email-input' });
    expect(emailInput.props.accessibilityLabel).toBe('Email');

    // Password Input
    const passwordInput = root.findByProps({ testID: 'password-input' });
    expect(passwordInput.props.accessibilityLabel).toBe('Mật khẩu');

    // Submit Button
    const submitBtn = root.findByProps({ testID: 'submit-register' });
    expect(submitBtn.props.accessibilityRole).toBe('button');
    expect(submitBtn.props.accessibilityLabel).toBe('Đăng ký');
    expect(submitBtn.props.accessibilityState).toEqual({ disabled: false, busy: false });

    // Sign In Link
    const signInLink = root.findByProps({ testID: 'link-to-sign-in' });
    expect(signInLink.props.accessibilityRole).toBe('link');
  });

  it('VerifyEmailScreen defines proper accessibility roles and labels', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<VerifyEmailScreen />);
    });
    const root = tree!.root;

    // Token Input
    const tokenInput = root.findByProps({ testID: 'token-input' });
    expect(tokenInput.props.accessibilityLabel).toBe('Mã xác minh');
    expect(tokenInput.props.accessibilityHint).toBeDefined();

    // Submit Button
    const submitBtn = root.findByProps({ testID: 'submit-verify' });
    expect(submitBtn.props.accessibilityRole).toBe('button');
    expect(submitBtn.props.accessibilityLabel).toBe('Xác nhận mã xác minh');
    expect(submitBtn.props.accessibilityState).toEqual({ disabled: false, busy: false });

    // Back to Sign In Link
    const backLink = root.findByProps({ testID: 'back-to-sign-in' });
    expect(backLink.props.accessibilityRole).toBe('link');
  });

  it('HomeScreen defines proper accessibility role and state for logout action', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<HomeScreen />);
    });
    const root = tree!.root;

    const logoutBtn = root.findByProps({ testID: 'logout-button' });
    expect(logoutBtn.props.accessibilityRole).toBe('button');
    expect(logoutBtn.props.accessibilityLabel).toBe('Đăng xuất');
    expect(logoutBtn.props.accessibilityState).toEqual({ disabled: false, busy: false });
  });

  it('ChoosePurposeScreen defines proper accessibility roles and labels', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<ChoosePurposeScreen />);
    });
    const root = tree!.root;

    const studentBtn = root.findByProps({ testID: 'select-student-purpose-button' });
    expect(studentBtn.props.accessibilityRole).toBe('button');
    expect(studentBtn.props.accessibilityLabel).toBeDefined();

    const trainerBtn = root.findByProps({ testID: 'select-trainer-purpose-button' });
    expect(trainerBtn.props.accessibilityRole).toBe('button');
    expect(trainerBtn.props.accessibilityLabel).toBeDefined();
  });

  it('TrainerOnboardingScreen defines proper accessibility roles and labels', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<TrainerOnboardingScreen />);
    });
    const root = tree!.root;

    const slugInput = root.findByProps({ testID: 'trainer-slug-input' });
    expect(slugInput.props.accessibilityLabel).toBe('Public Profile Handle');

    const submitBtn = root.findByProps({ testID: 'trainer-submit-button' });
    expect(submitBtn.props.accessibilityRole).toBe('button');
    expect(submitBtn.props.accessibilityLabel).toBe('Create Trainer Profile');

    const notice = root.findByProps({ testID: 'coaching-authority-notice' });
    expect(notice.props.accessibilityRole).toBe('alert');
  });
});
