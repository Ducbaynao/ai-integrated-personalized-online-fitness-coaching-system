import React from 'react';
import { Alert } from 'react-native';
import renderer from 'react-test-renderer';
import { GoalProposalDetailScreen } from '@/features/goal/GoalProposalDetailScreen';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/types/auth';
import { GoalProposal } from '@/types/goal';

const mockBack = jest.fn();
jest.mock('expo-router', () => ({
  useRouter: () => ({
    push: jest.fn(),
    back: mockBack,
    replace: jest.fn(),
  }),
}));

jest.mock('@/services/fitnessGoalApi', () => ({
  fitnessGoalApi: {
    getGoalProposal: jest.fn(),
    getGoal: jest.fn(),
    decideGoalProposal: jest.fn(),
  },
}));

describe('GoalProposalDetailScreen', () => {
  const pendingProposal: GoalProposal = {
    id: 'prop-123',
    studentId: 'student-abc',
    fitnessGoalId: 'goal-123',
    baseGoalVersionId: 'version-1',
    source: 'TRAINER',
    createdBy: 'trainer-xyz',
    proposedTitle: 'Strength and Power Progression',
    proposedStartDate: '2026-10-01',
    proposedTargetDate: '2027-01-01',
    proposedDurationDays: 92,
    reason: 'Progression to power block based on strong hypertrophy adaptation',
    status: 'PENDING',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    createdAt: '2026-09-25T10:00:00Z',
    updatedAt: '2026-09-25T10:00:00Z',
    objectives: [
      {
        id: 'prop-obj-1',
        goalTypeId: 2,
        goalTypeCode: 'STRENGTH',
        goalTypeName: 'Strength Focus',
        priority: 'PRIMARY',
        sortOrder: 1,
        notes: 'Compound 5-rep max progression',
      },
    ],
    targets: [
      {
        id: 'prop-tgt-1',
        metricDefinitionId: 11,
        metricCode: 'BARBELL_SQUAT_1RM',
        metricDisplayName: 'Barbell Back Squat',
        startValue: 100,
        targetValue: 120,
        unitId: 1,
        unitCode: 'KG',
        unitSymbol: 'kg',
        targetDate: '2027-01-01',
        notes: 'Maintain parallel depth',
      },
    ],
    baseVersion: {
      id: 'version-1',
      versionNumber: 1,
      title: 'Strength and Hypertrophy Focus',
      startDate: '2026-09-01',
      targetDate: '2026-12-01',
      durationDays: 91,
      effectiveFrom: '2026-09-01T10:00:00Z',
      effectiveUntil: null,
      resumeDate: null,
      changeReason: 'Baseline',
      changeSummary: null,
      createdBy: 'student-abc',
      sourceProposalId: null,
      createdAt: '2026-09-01T10:00:00Z',
      lockedAt: null,
      lockedBy: null,
      lockReason: null,
      isCurrent: true,
      objectives: [],
      targets: [],
    },
  };

  beforeEach(() => {
    jest.clearAllMocks();
    jest.spyOn(Alert, 'alert').mockImplementation(() => {});
  });

  it('renders pending proposal comparison details and action buttons', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    expect(fitnessGoalApi.getGoalProposal).toHaveBeenCalledWith('prop-123');
    expect(root.findByProps({ testID: 'proposal-status-banner-pending' })).toBeDefined();
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'reject-proposal-button' })).toBeDefined();
  });

  it('allows student to accept proposal with optional note, immediate state update, and confirmation', async () => {
    const acceptedProposal: GoalProposal = {
      ...pendingProposal,
      status: 'ACCEPTED',
      decidedAt: '2026-09-26T12:00:00Z',
      decisionNote: 'Looking forward to the strength phase!',
    };
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockResolvedValueOnce(acceptedProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    // Click Accept button
    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    // Check character count indicator
    expect(root.findByProps({ testID: 'proposal-note-char-count' }).props.children).toEqual([
      0,
      '/2000',
    ]);

    // Enter optional note
    renderer.act(() => {
      root
        .findByProps({ testID: 'proposal-decision-note-input' })
        .props.onChangeText('Looking forward to the strength phase!');
    });

    expect(root.findByProps({ testID: 'proposal-note-char-count' }).props.children).toEqual([
      38,
      '/2000',
    ]);

    // Confirm decision
    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(fitnessGoalApi.decideGoalProposal).toHaveBeenCalledWith(
      'prop-123',
      'ACCEPT',
      'Looking forward to the strength phase!'
    );

    // State updated immediately from decideGoalProposal response without needing second fetch
    expect(root.findByProps({ testID: 'proposal-status-banner-accepted' })).toBeDefined();
  });

  it('requires a mandatory non-blank note when rejecting proposal', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    // Click Reject button
    renderer.act(() => {
      root.findByProps({ testID: 'reject-proposal-button' }).props.onPress();
    });

    // Try to confirm without note
    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    // decideGoalProposal must NOT be called
    expect(fitnessGoalApi.decideGoalProposal).not.toHaveBeenCalled();

    // Validation error must appear
    const noteError = root.findByProps({ testID: 'proposal-note-error' });
    expect(noteError).toBeDefined();
    expect(noteError.props.children).toContain('rejection note is required');

    // Now type a whitespace note and confirm - should still fail
    renderer.act(() => {
      root
        .findByProps({ testID: 'proposal-decision-note-input' })
        .props.onChangeText('     ');
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });
    expect(fitnessGoalApi.decideGoalProposal).not.toHaveBeenCalled();

    // Now enter valid note and confirm
    const rejectedProposal: GoalProposal = {
      ...pendingProposal,
      status: 'REJECTED',
      decidedAt: '2026-09-26T12:00:00Z',
      decisionNote: 'Currently recovering from back stiffness, need to maintain hypertrophy.',
    };
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockResolvedValueOnce(rejectedProposal);

    renderer.act(() => {
      root
        .findByProps({ testID: 'proposal-decision-note-input' })
        .props.onChangeText('Currently recovering from back stiffness, need to maintain hypertrophy.');
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(fitnessGoalApi.decideGoalProposal).toHaveBeenCalledWith(
      'prop-123',
      'REJECT',
      'Currently recovering from back stiffness, need to maintain hypertrophy.'
    );

    // State updated immediately
    expect(root.findByProps({ testID: 'proposal-status-banner-rejected' })).toBeDefined();
  });

  it('supports up to 2000 character note in input', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    const input = root.findByProps({ testID: 'proposal-decision-note-input' });
    expect(input.props.maxLength).toBe(2000);

    const longText = 'A'.repeat(2000);
    renderer.act(() => {
      input.props.onChangeText(longText);
    });

    expect(root.findByProps({ testID: 'proposal-note-char-count' }).props.children).toEqual([
      2000,
      '/2000',
    ]);
  });

  it('does not render accept/reject buttons when proposal is already decided', async () => {
    const acceptedProposal: GoalProposal = {
      ...pendingProposal,
      status: 'ACCEPTED',
      decidedAt: '2026-09-26T10:00:00Z',
      decisionNote: 'Approved by student',
    };
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(acceptedProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    expect(root.findByProps({ testID: 'proposal-status-banner-accepted' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
  });

  it('handles 409 GOAL_PROPOSAL_ALREADY_DECIDED, locks action buttons even if reload returns PENDING or fails', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'GOAL_PROPOSAL_ALREADY_DECIDED',
      message: 'Proposal has already been decided',
      timestamp: '2026-09-26T00:00:00Z',
      requestId: 'req-2',
      fieldErrors: [],
    });
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockRejectedValueOnce(conflictError);
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(Alert.alert).toHaveBeenCalledWith(
      'Decision Failed',
      'This proposal has already been decided by another request and cannot be decided again.'
    );

    expect(fitnessGoalApi.getGoalProposal).toHaveBeenCalledTimes(2);
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-blocked-message' }).props.children).toContain(
      'This proposal has already been decided'
    );
  });

  it('handles 409 STALE_GOAL_PROPOSAL and locks action buttons even when backend returns PENDING', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'STALE_GOAL_PROPOSAL',
      message: 'Base goal version mismatch',
      timestamp: '2026-09-26T00:00:00Z',
      requestId: 'req-3',
      fieldErrors: [],
    });
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockRejectedValueOnce(conflictError);
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(Alert.alert).toHaveBeenCalledWith(
      'Decision Failed',
      'The base goal version has changed. This proposal is outdated and cannot be applied. Please refresh to see the latest goal.'
    );
    expect(fitnessGoalApi.getGoalProposal).toHaveBeenCalledTimes(2);
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-blocked-message' }).props.children).toContain(
      'The base goal version has changed'
    );
  });

  it('handles 409 GOAL_PROPOSAL_EXPIRED and locks action buttons even when backend returns PENDING', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'GOAL_PROPOSAL_EXPIRED',
      message: 'Proposal expired',
      timestamp: '2026-09-26T00:00:00Z',
      requestId: 'req-4',
      fieldErrors: [],
    });
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockRejectedValueOnce(conflictError);
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(Alert.alert).toHaveBeenCalledWith(
      'Decision Failed',
      'This proposal has expired and can no longer be decided.'
    );
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-blocked-message' }).props.children).toContain(
      'This proposal has expired and can no longer be decided'
    );
  });

  it('handles 409 GOAL_VERSION_NO_CHANGES and locks action buttons even when backend returns PENDING', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'GOAL_VERSION_NO_CHANGES',
      message: 'No changes detected',
      timestamp: '2026-09-26T00:00:00Z',
      requestId: 'req-5',
      fieldErrors: [],
    });
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockRejectedValueOnce(conflictError);
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(Alert.alert).toHaveBeenCalledWith(
      'Decision Failed',
      'The proposal contains no effective changes from the current active version.'
    );
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-blocked-message' }).props.children).toContain(
      'The proposal contains no effective changes'
    );
  });

  it('does not render action buttons when proposal has expiresAt in the past on initial load', async () => {
    const expiredProposal: GoalProposal = {
      ...pendingProposal,
      expiresAt: '2020-01-01T00:00:00Z',
    };
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(expiredProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-blocked-banner' })).toBeDefined();
    expect(root.findByProps({ testID: 'proposal-blocked-message' }).props.children).toContain(
      'This proposal has expired'
    );
  });

  it('resets terminal-conflict state when switching to a different proposalId', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'STALE_GOAL_PROPOSAL',
      message: 'Base goal version mismatch',
      timestamp: '2026-09-26T00:00:00Z',
      requestId: 'req-term',
      fieldErrors: [],
    });
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockRejectedValueOnce(conflictError);
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });
    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-blocked-banner' })).toBeDefined();

    const newProposal: GoalProposal = {
      ...pendingProposal,
      id: 'prop-456',
      proposedTitle: 'Different Valid Proposal',
    };
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(newProposal);

    await renderer.act(async () => {
      tree.update(<GoalProposalDetailScreen proposalId="prop-456" />);
    });

    expect(root.findAllByProps({ testID: 'proposal-blocked-banner' }).length).toBe(0);
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'reject-proposal-button' })).toBeDefined();
  });

  it('generic 409 keeps server message and does NOT lock buttons if reloaded proposal remains PENDING', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'TEMPORARY_CONCURRENCY_LOCK',
      message: 'Resource is temporarily locked by maintenance',
      timestamp: '2026-09-26T00:00:00Z',
      requestId: 'req-gen',
      fieldErrors: [],
    });
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockRejectedValueOnce(conflictError);
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    expect(Alert.alert).toHaveBeenCalledWith(
      'Decision Failed',
      'Resource is temporarily locked by maintenance'
    );
    expect(root.findAllByProps({ testID: 'proposal-blocked-banner' }).length).toBe(0);
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();
    expect(root.findByProps({ testID: 'reject-proposal-button' })).toBeDefined();
  });

  it('prevents older stale response from overwriting newer response when proposalId changes', async () => {
    let resolveSlowRequest!: (val: any) => void;
    const slowPromise = new Promise((resolve) => {
      resolveSlowRequest = resolve;
    });

    const slowProposal: GoalProposal = {
      ...pendingProposal,
      id: 'prop-slow-1',
      proposedTitle: 'Old Stale Slow Proposal Title',
    };

    const fastProposal: GoalProposal = {
      ...pendingProposal,
      id: 'prop-fast-2',
      proposedTitle: 'New Fast Proposal Title',
    };

    (fitnessGoalApi.getGoalProposal as jest.Mock).mockReturnValueOnce(slowPromise);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-slow-1" />);
    });

    // Switch proposalId to prop-fast-2
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(fastProposal);
    await renderer.act(async () => {
      tree.update(<GoalProposalDetailScreen proposalId="prop-fast-2" />);
    });

    // Fast proposal should be displayed
    const root = tree!.root;
    expect(root.findByProps({ testID: 'proposal-proposed-title' }).props.children).toBe(
      'New Fast Proposal Title'
    );

    // Now resolve slow proposal
    await renderer.act(async () => {
      resolveSlowRequest(slowProposal);
    });

    // The fast proposal must NOT be overwritten by the stale slow proposal
    expect(root.findByProps({ testID: 'proposal-proposed-title' }).props.children).toBe(
      'New Fast Proposal Title'
    );
  });

  it('displays accurate, neutral confirmation copy on ACCEPT without claiming always new version', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    // Open ACCEPT modal
    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });

    const modalDesc = root.findByProps({ testID: 'proposal-modal-desc' });
    const descText = Array.isArray(modalDesc.props.children)
      ? modalDesc.props.children.join('')
      : String(modalDesc.props.children);

    // Assert: does NOT state that accepting will always create a new version and lock
    expect(descText).not.toContain('will create a new Goal Version and lock');

    // Assert: describes both updating current goal version and starting a new goal journey
    expect(descText).toContain('create a new version of your current goal');
    expect(descText).toContain('start a new goal journey');

    // Assert: explicitly confirms history preservation
    expect(descText).toContain('fitness history will remain preserved');
  });

  it('prevents stale proposal A from displaying or submitting when switching to proposal B with deferred promise', async () => {
    const proposalA: GoalProposal = {
      ...pendingProposal,
      id: 'prop-A',
      proposedTitle: 'Proposal A Original Title',
    };
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(proposalA);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-A" />);
    });
    let root = tree!.root;

    // Proposal A is loaded and visible
    expect(root.findByProps({ testID: 'proposal-proposed-title' }).props.children).toBe(
      'Proposal A Original Title'
    );
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();

    // Prepare deferred promise for Proposal B
    let resolveProposalB!: (val: any) => void;
    const deferredPromiseB = new Promise<GoalProposal>((resolve) => {
      resolveProposalB = resolve;
    });
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockReturnValueOnce(deferredPromiseB);

    // Switch proposalId to prop-B
    await renderer.act(async () => {
      tree.update(<GoalProposalDetailScreen proposalId="prop-B" />);
    });
    root = tree!.root;

    // While proposal B is pending, skeleton is displayed (isDisplayLoading === true)
    // Old proposal A title, action buttons, and modal should NOT be displayed
    expect(root.findAllByProps({ testID: 'proposal-proposed-title' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);

    // Now resolve Proposal B
    const proposalB: GoalProposal = {
      ...pendingProposal,
      id: 'prop-B',
      proposedTitle: 'Proposal B Fresh Title',
    };
    await renderer.act(async () => {
      resolveProposalB(proposalB);
    });
    root = tree!.root;

    // Proposal B is now rendered
    expect(root.findByProps({ testID: 'proposal-proposed-title' }).props.children).toBe(
      'Proposal B Fresh Title'
    );
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();

    // Delayed late response for proposal A does not overwrite proposal B
    expect(root.findByProps({ testID: 'proposal-proposed-title' }).props.children).toBe(
      'Proposal B Fresh Title'
    );
  });

  it('Test 1 - handles initial load failure: exits skeleton, renders error message, Go Back and Retry buttons, and recovers on retry', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockRejectedValueOnce(
      new Error('Failed to load proposal details')
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    // Skeleton must NOT be visible
    expect(root.findAllByProps({ testID: 'proposal-loading-skeleton' }).length).toBe(0);

    // Error message must be rendered
    const errorMsg = root.findByProps({ testID: 'proposal-error-message' });
    expect(errorMsg.props.children).toBe('Failed to load proposal details');

    // Action buttons must NOT exist
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);

    // Go Back and Retry buttons exist
    const backBtn = root.findByProps({ testID: 'proposal-error-back-button' });
    const retryBtn = root.findByProps({ testID: 'proposal-error-retry-button' });
    expect(backBtn).toBeDefined();
    expect(retryBtn).toBeDefined();

    // Verify Go Back calls router.back
    renderer.act(() => {
      backBtn.props.onPress();
    });
    expect(mockBack).toHaveBeenCalled();

    // Verify Retry button functions and recovers screen on success
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    await renderer.act(async () => {
      retryBtn.props.onPress();
    });

    // Error message must be cleared and proposal rendered
    expect(root.findAllByProps({ testID: 'proposal-error-message' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-status-banner-pending' })).toBeDefined();
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();
  });

  it('Test 2 - handles proposal switch failure: renders skeleton during switch, shows proposal B error, hides proposal A data', async () => {
    const proposalA: GoalProposal = {
      ...pendingProposal,
      id: 'prop-A',
      proposedTitle: 'Proposal A Original Title',
    };
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(proposalA);

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-A" />);
    });
    let root = tree!.root;

    expect(root.findByProps({ testID: 'proposal-proposed-title' }).props.children).toBe(
      'Proposal A Original Title'
    );
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();

    // Prepare deferred rejection for Proposal B
    let rejectProposalB!: (err: any) => void;
    const deferredPromiseB = new Promise<GoalProposal>((_, reject) => {
      rejectProposalB = reject;
    });
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockReturnValueOnce(deferredPromiseB);

    // Switch to Proposal B
    await renderer.act(async () => {
      tree.update(<GoalProposalDetailScreen proposalId="prop-B" />);
    });
    root = tree!.root;

    // While pending: skeleton is displayed, Proposal A data/actions are hidden
    expect(root.findByProps({ testID: 'proposal-loading-skeleton' })).toBeDefined();
    expect(root.findAllByProps({ testID: 'proposal-proposed-title' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);

    // Now reject Proposal B
    await renderer.act(async () => {
      rejectProposalB(new Error('Proposal B network timeout'));
    });
    root = tree!.root;

    // Skeleton must disappear
    expect(root.findAllByProps({ testID: 'proposal-loading-skeleton' }).length).toBe(0);

    // Error for Proposal B is displayed
    const errorMsg = root.findByProps({ testID: 'proposal-error-message' });
    expect(errorMsg.props.children).toBe('Proposal B network timeout');

    // Proposal A data and actions must still NOT be displayed
    expect(root.findAllByProps({ testID: 'proposal-proposed-title' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
  });

  it('Test 3 - isolates errors across proposals: Proposal A error does not leak to Proposal B while loading or after success', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockRejectedValueOnce(
      new Error('Proposal A failed to load')
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-A" />);
    });
    let root = tree!.root;

    // Proposal A error is displayed
    expect(root.findByProps({ testID: 'proposal-error-message' }).props.children).toBe(
      'Proposal A failed to load'
    );

    // Switch to Proposal B with deferred promise
    let resolveProposalB!: (val: any) => void;
    const deferredPromiseB = new Promise<GoalProposal>((resolve) => {
      resolveProposalB = resolve;
    });
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockReturnValueOnce(deferredPromiseB);

    await renderer.act(async () => {
      tree.update(<GoalProposalDetailScreen proposalId="prop-B" />);
    });
    root = tree!.root;

    // While B is loading, Proposal A's error is immediately gone and skeleton is shown
    expect(root.findAllByProps({ testID: 'proposal-error-message' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-loading-skeleton' })).toBeDefined();

    // Proposal B loads successfully
    const proposalB: GoalProposal = {
      ...pendingProposal,
      id: 'prop-B',
      proposedTitle: 'Proposal B Success Title',
    };
    await renderer.act(async () => {
      resolveProposalB(proposalB);
    });
    root = tree!.root;

    // Proposal B is displayed normally, no error from A remains
    expect(root.findAllByProps({ testID: 'proposal-error-message' }).length).toBe(0);
    expect(root.findByProps({ testID: 'proposal-proposed-title' }).props.children).toBe(
      'Proposal B Success Title'
    );
    expect(root.findByProps({ testID: 'accept-proposal-button' })).toBeDefined();
  });

  it('Test 4 - handles suppressed background reload failure: keeps terminal-conflict banner without switching to full-screen error', async () => {
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockResolvedValueOnce(pendingProposal);
    const conflictError = new ApiError(409, 'Conflict', {
      errorCode: 'STALE_GOAL_PROPOSAL',
      message: 'Base goal version mismatch',
      timestamp: '2026-09-26T00:00:00Z',
      requestId: 'req-stale',
      fieldErrors: [],
    });
    (fitnessGoalApi.decideGoalProposal as jest.Mock).mockRejectedValueOnce(conflictError);
    // Background reload fails!
    (fitnessGoalApi.getGoalProposal as jest.Mock).mockRejectedValueOnce(
      new Error('Background reload network failure')
    );

    let tree: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(<GoalProposalDetailScreen proposalId="prop-123" />);
    });
    const root = tree!.root;

    // Open decision modal and confirm
    renderer.act(() => {
      root.findByProps({ testID: 'accept-proposal-button' }).props.onPress();
    });
    await renderer.act(async () => {
      await root.findByProps({ testID: 'modal-confirm-button' }).props.onPress();
    });

    // Alert for decision failed was shown
    expect(Alert.alert).toHaveBeenCalledWith(
      'Decision Failed',
      expect.stringContaining('The base goal version has changed')
    );

    // Full screen error must NOT be shown!
    expect(root.findAllByProps({ testID: 'proposal-error-message' }).length).toBe(0);

    // Terminal conflict banner remains visible
    expect(root.findByProps({ testID: 'proposal-blocked-banner' })).toBeDefined();
    expect(root.findByProps({ testID: 'proposal-blocked-message' }).props.children).toContain(
      'The base goal version has changed'
    );

    // Action buttons remain locked/hidden
    expect(root.findAllByProps({ testID: 'accept-proposal-button' }).length).toBe(0);
    expect(root.findAllByProps({ testID: 'reject-proposal-button' }).length).toBe(0);
  });
});
