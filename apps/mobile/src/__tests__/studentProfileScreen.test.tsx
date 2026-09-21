import React from 'react';
import renderer from 'react-test-renderer';
import { StudentProfileScreen } from '@/features/student/StudentProfileScreen';
import { studentProfileApi } from '@/services/studentProfileApi';
import { ApiError } from '@/types/auth';
import { StudentProfileResponse } from '@/types/student';

const mockReplace = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({
    replace: mockReplace,
    push: jest.fn(),
  }),
}));

jest.mock('@/services/studentProfileApi', () => ({
  studentProfileApi: {
    createStudentProfile: jest.fn(),
    getMyStudentProfile: jest.fn(),
    updateMyStudentProfile: jest.fn(),
  },
}));

describe('StudentProfileScreen', () => {
  const sampleProfile: StudentProfileResponse = {
    userId: 'test-user-id',
    dateOfBirth: '1995-06-15',
    gender: 'MALE',
    trainingExperienceLevel: 'INTERMEDIATE',
    trainingExperienceMonths: 18,
    availableDaysPerWeek: 4,
    preferredSessionMinutes: 60,
    onboardingCompleted: true,
    onboardingCompletedAt: '2026-09-20T10:00:00Z',
    createdAt: '2026-09-20T10:00:00Z',
    updatedAt: '2026-09-20T10:00:00Z',
  };

  beforeEach(() => {
    jest.clearAllMocks();
  });

  it('displays loading state initially and renders profile details on load', async () => {
    (studentProfileApi.getMyStudentProfile as jest.Mock).mockResolvedValueOnce(sampleProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<StudentProfileScreen />);
    });
    const root = tree!.root;

    expect(studentProfileApi.getMyStudentProfile).toHaveBeenCalledTimes(1);
    expect(root.findByProps({ testID: 'view-dob' }).props.children).toBe('1995-06-15');
    expect(root.findByProps({ testID: 'view-gender' }).props.children).toBe('MALE');
    expect(root.findByProps({ testID: 'view-level' }).props.children).toBe('INTERMEDIATE');
    expect(root.findByProps({ testID: 'view-months' }).props.children).toBe('18 months');
    expect(root.findByProps({ testID: 'view-days' }).props.children).toBe('4 days');
    expect(root.findByProps({ testID: 'view-minutes' }).props.children).toBe('60 minutes');
  });

  it('renders error state and retries successfully', async () => {
    (studentProfileApi.getMyStudentProfile as jest.Mock)
      .mockRejectedValueOnce(new ApiError(500, 'Server error'))
      .mockResolvedValueOnce(sampleProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<StudentProfileScreen />);
    });
    const root = tree!.root;

    // Verify error state is shown
    expect(root.findByProps({ testID: 'profile-error' })).toBeDefined();

    // Click retry
    const retryBtn = root.findByProps({ testID: 'retry-button' });
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });

    expect(studentProfileApi.getMyStudentProfile).toHaveBeenCalledTimes(2);
    expect(root.findByProps({ testID: 'view-dob' })).toBeDefined();
  });

  it('enters edit mode, sends only changed fields via PATCH, and updates view', async () => {
    (studentProfileApi.getMyStudentProfile as jest.Mock).mockResolvedValueOnce(sampleProfile);

    const updatedProfile: StudentProfileResponse = {
      ...sampleProfile,
      availableDaysPerWeek: 5,
    };
    (studentProfileApi.updateMyStudentProfile as jest.Mock).mockResolvedValueOnce(updatedProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<StudentProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode
    const editBtn = root.findByProps({ testID: 'edit-button' });
    renderer.act(() => {
      editBtn.props.onPress();
    });

    // Change available days from 4 to 5
    const day5Chip = root.findByProps({ testID: 'edit-days-chip-5' });
    renderer.act(() => {
      day5Chip.props.onPress();
    });

    // Save changes
    const saveBtn = root.findByProps({ testID: 'save-button' });
    await renderer.act(async () => {
      saveBtn.props.onPress();
    });

    // Verify PATCH only sent availableDaysPerWeek (partial update)
    expect(studentProfileApi.updateMyStudentProfile).toHaveBeenCalledWith({
      availableDaysPerWeek: 5,
    });

    // View should now display 5 days
    expect(root.findByProps({ testID: 'view-days' }).props.children).toBe('5 days');
  });

  it('sends null when a nullable field is cleared in edit mode', async () => {
    (studentProfileApi.getMyStudentProfile as jest.Mock).mockResolvedValueOnce(sampleProfile);

    const clearedProfile: StudentProfileResponse = {
      ...sampleProfile,
      dateOfBirth: null,
    };
    (studentProfileApi.updateMyStudentProfile as jest.Mock).mockResolvedValueOnce(clearedProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<StudentProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode
    renderer.act(() => {
      root.findByProps({ testID: 'edit-button' }).props.onPress();
    });

    // Clear date of birth
    renderer.act(() => {
      root.findByProps({ testID: 'edit-dob-input' }).props.onChangeText('');
    });

    // Save changes
    await renderer.act(async () => {
      root.findByProps({ testID: 'save-button' }).props.onPress();
    });

    expect(studentProfileApi.updateMyStudentProfile).toHaveBeenCalledWith({
      dateOfBirth: null,
    });
  });

  it('cancels edit mode without calling updateMyStudentProfile', async () => {
    (studentProfileApi.getMyStudentProfile as jest.Mock).mockResolvedValueOnce(sampleProfile);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<StudentProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode
    renderer.act(() => {
      root.findByProps({ testID: 'edit-button' }).props.onPress();
    });

    // Change input
    renderer.act(() => {
      root.findByProps({ testID: 'edit-months-input' }).props.onChangeText('24');
    });

    // Cancel edit
    renderer.act(() => {
      root.findByProps({ testID: 'cancel-button' }).props.onPress();
    });

    expect(studentProfileApi.updateMyStudentProfile).not.toHaveBeenCalled();
    // Back to view mode
    expect(root.findByProps({ testID: 'view-months' }).props.children).toBe('18 months');
  });

  it('prevents duplicate PATCH calls when save button is pressed repeatedly before resolve', async () => {
    (studentProfileApi.getMyStudentProfile as jest.Mock).mockResolvedValueOnce(sampleProfile);

    let resolveUpdate!: (val: unknown) => void;
    const deferredUpdate = new Promise((resolve) => {
      resolveUpdate = resolve;
    });

    (studentProfileApi.updateMyStudentProfile as jest.Mock).mockReturnValue(deferredUpdate);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<StudentProfileScreen />);
    });
    const root = tree!.root;

    // Switch to edit mode
    renderer.act(() => {
      root.findByProps({ testID: 'edit-button' }).props.onPress();
    });

    // Make a change
    renderer.act(() => {
      root.findByProps({ testID: 'edit-days-chip-5' }).props.onPress();
    });

    // Rapid double press on save-button before first promise resolves
    const saveBtn = root.findByProps({ testID: 'save-button' });
    renderer.act(() => {
      saveBtn.props.onPress();
      saveBtn.props.onPress();
    });

    // Expect exactly 1 call to updateMyStudentProfile
    expect(studentProfileApi.updateMyStudentProfile).toHaveBeenCalledTimes(1);

    // Resolve update
    await renderer.act(async () => {
      resolveUpdate({
        ...sampleProfile,
        availableDaysPerWeek: 5,
      });
    });

    expect(root.findByProps({ testID: 'view-days' }).props.children).toBe('5 days');
  });
});
