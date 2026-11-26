import React from 'react';
import renderer from 'react-test-renderer';
import { StudentOnboardingScreen } from '@/features/student/StudentOnboardingScreen';
import { validateDateOfBirth } from '@/features/student/studentValidation';
import { studentProfileApi } from '@/services/studentProfileApi';
import { ApiError } from '@/types/auth';

function selectDateViaPicker(
  root: renderer.ReactTestInstance,
  triggerTestID: string,
  isoDate: string
) {
  const [yStr, mStr] = isoDate.split('-');
  renderer.act(() => {
    root.findByProps({ testID: triggerTestID }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: 'date-picker-year-toggle' }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: `date-picker-year-${Number(yStr)}` }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: 'date-picker-month-toggle' }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: `date-picker-month-${Number(mStr)}` }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: `date-picker-day-${isoDate}` }).props.onPress();
  });
  renderer.act(() => {
    root.findByProps({ testID: 'date-picker-confirm-button' }).props.onPress();
  });
}

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({
    replace: mockReplace,
    push: jest.fn(),
  }),
}));

const mockRefreshUser = jest.fn();
const mockLogout = jest.fn();

jest.mock('@/features/auth/AuthContext', () => ({
  useAuth: () => ({
    user: {
      id: 'test-user-id',
      email: 'newuser@example.com',
      displayName: 'New Athlete',
      capabilities: {
        hasStudentProfile: false,
        hasTrainerProfile: false,
        canCoach: false,
      },
    },
    refreshUser: mockRefreshUser,
    logout: mockLogout,
    isLoading: false,
    status: 'AUTHENTICATED',
  }),
}));

jest.mock('@/services/studentProfileApi', () => ({
  studentProfileApi: {
    createStudentProfile: jest.fn(),
    getMyStudentProfile: jest.fn(),
    updateMyStudentProfile: jest.fn(),
  },
}));

describe('Student Onboarding Flow', () => {
  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('renders all form inputs and action buttons', () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'dob-input' })).toBeDefined();
    expect(root.findByProps({ testID: 'gender-chip-MALE' })).toBeDefined();
    expect(root.findByProps({ testID: 'experience-chip-BEGINNER' })).toBeDefined();
    expect(root.findByProps({ testID: 'months-input' })).toBeDefined();
    expect(root.findByProps({ testID: 'days-chip-4' })).toBeDefined();
    expect(root.findByProps({ testID: 'duration-chip-60' })).toBeDefined();
    expect(root.findByProps({ testID: 'submit-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'skip-button' })).toBeDefined();
  });

  it('prevents selecting future years in date picker and rejects future dates in validator', async () => {
    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'dob-input-trigger' }).props.onPress();
    });
    renderer.act(() => {
      root.findByProps({ testID: 'date-picker-year-toggle' }).props.onPress();
    });
    expect(root.findAllByProps({ testID: 'date-picker-year-2099' }).length).toBe(0);
    renderer.act(() => {
      root.findByProps({ testID: 'date-picker-cancel-button' }).props.onPress();
    });

    expect(root.findByProps({ testID: 'dob-input-trigger' }).props.accessibilityValue.text).toBe('Chưa chọn');
    expect(validateDateOfBirth('2099-01-01')).toContain('không được ở tương lai');
  });

  it('successfully creates profile, calls refreshUser, and redirects to /(app)', async () => {
    (studentProfileApi.createStudentProfile as jest.Mock).mockResolvedValueOnce({
      userId: 'test-user-id',
      dateOfBirth: '1995-06-15',
      gender: 'MALE',
      trainingExperienceLevel: 'BEGINNER',
      trainingExperienceMonths: 12,
      availableDaysPerWeek: 4,
      preferredSessionMinutes: 60,
      onboardingCompleted: true,
      onboardingCompletedAt: '2026-09-20T10:00:00Z',
      createdAt: '2026-09-20T10:00:00Z',
      updatedAt: '2026-09-20T10:00:00Z',
    });
    mockRefreshUser.mockResolvedValueOnce(undefined);

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    // Fill in form
    selectDateViaPicker(root, 'dob-input-trigger', '1995-06-15');
    renderer.act(() => {
      root.findByProps({ testID: 'gender-chip-MALE' }).props.onPress();
      root.findByProps({ testID: 'experience-chip-BEGINNER' }).props.onPress();
      root.findByProps({ testID: 'months-input' }).props.onChangeText('12');
      root.findByProps({ testID: 'days-chip-4' }).props.onPress();
      root.findByProps({ testID: 'duration-chip-60' }).props.onPress();
    });

    const submitBtn = root.findByProps({ testID: 'submit-button' });
    await renderer.act(async () => {
      submitBtn.props.onPress();
    });

    expect(studentProfileApi.createStudentProfile).toHaveBeenCalledWith({
      dateOfBirth: '1995-06-15',
      gender: 'MALE',
      trainingExperienceLevel: 'BEGINNER',
      trainingExperienceMonths: 12,
      availableDaysPerWeek: 4,
      preferredSessionMinutes: 60,
      onboardingCompleted: true,
    });

    expect(mockRefreshUser).toHaveBeenCalledTimes(1);
    expect(mockReplace).toHaveBeenCalledWith('/(app)');
  });

  it('Continue without details submits onboardingCompleted: true, refreshes user and redirects', async () => {
    (studentProfileApi.createStudentProfile as jest.Mock).mockResolvedValueOnce({
      userId: 'test-user-id',
      onboardingCompleted: true,
    });
    mockRefreshUser.mockResolvedValueOnce({
      id: 'test-user-id',
      capabilities: { hasStudentProfile: true },
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    const skipBtn = root.findByProps({ testID: 'skip-button' });
    expect(skipBtn.props.accessibilityLabel).toBe('Tiếp tục mà chưa nhập thông tin');

    await renderer.act(async () => {
      skipBtn.props.onPress();
    });

    expect(studentProfileApi.createStudentProfile).toHaveBeenCalledWith({
      onboardingCompleted: true,
    });
    expect(mockRefreshUser).toHaveBeenCalledTimes(1);
    expect(mockReplace).toHaveBeenCalledWith('/(app)');
  });

  it('prevents duplicate submissions when submit button is pressed repeatedly before first request resolves', async () => {
    let resolveCreation!: (val: unknown) => void;
    const deferredPromise = new Promise((resolve) => {
      resolveCreation = resolve;
    });

    (studentProfileApi.createStudentProfile as jest.Mock).mockReturnValue(deferredPromise);
    mockRefreshUser.mockResolvedValueOnce({
      id: 'test-user-id',
      capabilities: { hasStudentProfile: true },
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    // Enter valid date of birth so client validation passes
    selectDateViaPicker(root, 'dob-input-trigger', '1995-06-15');

    const submitBtn = root.findByProps({ testID: 'submit-button' });

    // Rapid double press before first promise resolves
    renderer.act(() => {
      submitBtn.props.onPress();
      submitBtn.props.onPress();
    });

    // createStudentProfile should only be invoked once
    expect(studentProfileApi.createStudentProfile).toHaveBeenCalledTimes(1);

    // Resolve the deferred promise
    await renderer.act(async () => {
      resolveCreation({
        userId: 'test-user-id',
        dateOfBirth: '1995-06-15',
        onboardingCompleted: true,
      });
    });

    expect(mockRefreshUser).toHaveBeenCalledTimes(1);
    expect(mockReplace).toHaveBeenCalledWith('/(app)');
  });

  it('displays network error banner and does not redirect when request fails with unexpected network error', async () => {
    (studentProfileApi.createStudentProfile as jest.Mock).mockRejectedValueOnce(
      new Error('Network request failed')
    );

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    const skipBtn = root.findByProps({ testID: 'skip-button' });
    await renderer.act(async () => {
      skipBtn.props.onPress();
    });

    expect(mockReplace).not.toHaveBeenCalled();
    const errorBanner = root.findAll((node) =>
      typeof node.props.children === 'string' &&
      node.props.children.includes('Có lỗi xảy ra')
    );
    expect(errorBanner.length).toBeGreaterThan(0);
  });

  it('handles 409 conflict recovery by refreshing user and redirecting if hasStudentProfile is true', async () => {
    (studentProfileApi.createStudentProfile as jest.Mock).mockRejectedValueOnce(
      new ApiError(409, 'Student profile already exists')
    );
    mockRefreshUser.mockResolvedValueOnce({
      id: 'test-user-id',
      capabilities: {
        hasStudentProfile: true,
        hasTrainerProfile: false,
        canCoach: false,
      },
    });

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    const skipBtn = root.findByProps({ testID: 'skip-button' });
    await renderer.act(async () => {
      skipBtn.props.onPress();
    });

    expect(mockRefreshUser).toHaveBeenCalledTimes(1);
    expect(mockReplace).toHaveBeenCalledWith('/(app)');
  });

  it('displays fieldErrors returned from backend API', async () => {
    (studentProfileApi.createStudentProfile as jest.Mock).mockRejectedValueOnce(
      new ApiError(400, 'Validation failed', {
        errorCode: 'VALIDATION_FAILED',
        message: 'Validation failed',
        timestamp: '2026-09-20T10:00:00Z',
        requestId: 'req-400',
        fieldErrors: [
          { field: 'dateOfBirth', code: 'PAST_OR_PRESENT', message: 'Date cannot be in the future' },
        ],
      })
    );

    let tree: renderer.ReactTestRenderer;
    renderer.act(() => {
      tree = renderer.create(<StudentOnboardingScreen />);
    });
    const root = tree!.root;

    const skipBtn = root.findByProps({ testID: 'skip-button' });
    await renderer.act(async () => {
      skipBtn.props.onPress();
    });

    const errorField = root.findAll((node) =>
      typeof node.props.children === 'string' && node.props.children.includes('Giá trị chưa hợp lệ')
    );
    expect(errorField.length).toBeGreaterThan(0);
  });
});
