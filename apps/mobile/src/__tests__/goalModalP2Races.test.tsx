import React from 'react';
import { Alert } from 'react-native';
import renderer from 'react-test-renderer';
import {
  CreateGoalModal,
  resolveGoalModalErrorMessage,
} from '@/features/goal/components/CreateGoalModal';
import { CreateGoalVersionModal } from '@/features/goal/components/CreateGoalVersionModal';
import { CreateGoalTransitionModal } from '@/features/goal/components/CreateGoalTransitionModal';
import { CurrentGoalScreen } from '@/features/goal/CurrentGoalScreen';
import { fitnessGoalApi } from '@/services/fitnessGoalApi';
import { ApiError } from '@/types/auth';
import type { FitnessGoal } from '@/types/goal';

jest.mock('expo-router', () => {
  const actualReact = jest.requireActual<typeof import('react')>('react');
  return {
    useRouter: () => ({
      push: jest.fn(),
      replace: jest.fn(),
      back: jest.fn(),
      canGoBack: () => true,
    }),
    useFocusEffect: (cb: any) => {
      actualReact.useEffect(() => {
        const cleanup = cb();
        return () => {
          if (typeof cleanup === 'function') cleanup();
        };
      }, [cb]);
    },
  };
});

jest.mock('@/services/fitnessGoalApi', () => ({
  fitnessGoalApi: {
    getCurrentGoal: jest.fn(),
    getGoal: jest.fn(),
    getGoalVersions: jest.fn().mockResolvedValue({ items: [], totalPages: 1 }),
    getGoalTransitions: jest.fn().mockResolvedValue([]),
    getMyGoalProposals: jest.fn().mockResolvedValue({ items: [], totalPages: 1 }),
    pauseGoal: jest.fn(),
    resumeGoal: jest.fn(),
    createGoal: jest.fn(),
    activateGoal: jest.fn(),
    createGoalVersion: jest.fn(),
    createGoalTransition: jest.fn(),
    createGoalProposal: jest.fn(),
  },
}));

function createDeferred<T>() {
  let resolve!: (value: T | PromiseLike<T>) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

function getTextInside(node: renderer.ReactTestInstance): string {
  return node.find((n) => typeof n.props?.children === 'string').props.children;
}

describe('P7-017 P2 regression: resolveGoalModalErrorMessage order & sanitization', () => {
  it('maps 401 with goalTypeCode field error to auth message, not goal type guidance', () => {
    const err = new ApiError(401, 'Unauthorized', {
      errorCode: 'UNAUTHORIZED',
      message: 'Token expired while checking goalTypeCode',
      timestamp: '2026-11-26T00:00:00Z',
      requestId: 'req-401',
      fieldErrors: [{ field: 'goalTypeCode', code: 'Unauthorized', message: 'Invalid goal type token' }],
    });
    const msg = resolveGoalModalErrorMessage(err, 'Fallback');
    expect(msg).toBe('Phiên đăng nhập đã hết hạn. Vui lòng đăng nhập lại.');
    expect(msg).not.toContain('Loại mục tiêu');
  });

  it('never leaks raw Vietnamese server messages containing database URIs on 403 or 500', () => {
    const err403 = new ApiError(403, 'Forbidden', {
      errorCode: 'FORBIDDEN',
      message: 'Từ chối quyền tại postgres://qa_user:fake_pw@db.internal:5432/fitness_prod',
      timestamp: '2026-11-26T00:00:00Z',
      requestId: 'req-403',
      fieldErrors: [],
    });
    const msg403 = resolveGoalModalErrorMessage(err403, 'Fallback');
    expect(msg403).toBe('Bạn không có quyền thực hiện thao tác này.');
    expect(msg403).not.toContain('postgres://');
    expect(msg403).not.toContain('db.internal');

    const err500 = new ApiError(500, 'Internal error', {
      errorCode: 'INTERNAL_ERROR',
      message: 'Lỗi kết nối cơ sở dữ liệu postgres://admin:fake@10.0.0.9:5432/prod_db',
      timestamp: '2026-11-26T00:00:00Z',
      requestId: 'req-500',
      fieldErrors: [],
    });
    const msg500 = resolveGoalModalErrorMessage(err500, 'Không thể tạo mục tiêu thể chất.');
    expect(msg500).not.toContain('postgres://');
    expect(msg500).not.toContain('10.0.0.9');
    expect(msg500).not.toContain('prod_db');
  });

  it('still shows actionable goal-type message for 400/422 goalType validation errors', () => {
    const err400 = new ApiError(400, 'Goal type is invalid or does not exist', {
      errorCode: 'VALIDATION_FAILED',
      message: 'Goal type is invalid or does not exist',
      timestamp: '2026-11-26T00:00:00Z',
      requestId: 'req-400',
      fieldErrors: [{ field: 'objectives[0].goalTypeId', code: 'NotFound', message: 'Goal type does not exist' }],
    });
    expect(resolveGoalModalErrorMessage(err400, 'Fallback')).toContain('Loại mục tiêu');
  });

  it('handles 409 conflict, AbortError cancellation, network/timeout, and unrecognized errors safely', () => {
    const err409 = new ApiError(409, 'Conflict postgres://secret', {
      errorCode: 'GOAL_VERSION_CONFLICT',
      message: 'Xung đột dữ liệu postgres://secret@internal/db',
      timestamp: '2026-11-26T00:00:00Z',
      requestId: 'req-409',
      fieldErrors: [],
    });
    const msg409 = resolveGoalModalErrorMessage(err409, 'Fallback');
    expect(msg409).toBe('Dữ liệu đã thay đổi. Vui lòng tải lại và thử lại.');
    expect(msg409).not.toContain('postgres://');

    const abortErr = new Error('Aborted');
    abortErr.name = 'AbortError';
    const abortMsg = resolveGoalModalErrorMessage(abortErr, 'Fallback');
    expect(abortMsg).not.toContain('đăng nhập');

    const netErr = new ApiError(0, 'Network error');
    expect(resolveGoalModalErrorMessage(netErr, 'Fallback')).toContain('kết nối mạng');

    const timeoutErr = new Error('Timed out');
    timeoutErr.name = 'TimeoutError';
    expect(resolveGoalModalErrorMessage(timeoutErr, 'Fallback')).toContain('thời gian');

    const unknownWithSecret = new Error('Unexpected postgres://raw:uri@host/db');
    const unknownMsg = resolveGoalModalErrorMessage(unknownWithSecret, 'Thao tác thất bại.');
    expect(unknownMsg).toBe('Thao tác thất bại.');
    expect(unknownMsg).not.toContain('postgres://');
  });
});

describe('P7-017 P2 regression: stale modal submit generation isolation across all 3 modals', () => {
  type ModalSpec = {
    name: string;
    bannerTestId: string;
    submitTestId: string;
    primaryInputTestId: string;
    renderModal: (props: {
      visible: boolean;
      isSubmitting: boolean;
      onClose: () => void;
      onSubmit: (p: any) => Promise<void>;
    }) => React.ReactElement;
    fillValid: (root: renderer.ReactTestInstance, label: string) => void;
  };

  const specs: ModalSpec[] = [
    {
      name: 'CreateGoalModal',
      bannerTestId: 'create-goal-error-banner',
      submitTestId: 'create-goal-submit-button',
      primaryInputTestId: 'create-goal-title-input',
      renderModal: ({ visible, isSubmitting, onClose, onSubmit }) => (
        <CreateGoalModal visible={visible} isSubmitting={isSubmitting} isDark={false} onClose={onClose} onSubmit={onSubmit} />
      ),
      fillValid: (root, label) => {
        root.findByProps({ testID: 'create-goal-title-input' }).props.onChangeText(label);
      },
    },
    {
      name: 'CreateGoalVersionModal',
      bannerTestId: 'goal-version-error-banner',
      submitTestId: 'goal-version-submit-button',
      primaryInputTestId: 'goal-version-reason-input',
      renderModal: ({ visible, isSubmitting, onClose, onSubmit }) => (
        <CreateGoalVersionModal visible={visible} currentTitle="Base Goal" isSubmitting={isSubmitting} isDark={false} onClose={onClose} onSubmit={onSubmit} />
      ),
      fillValid: (root, label) => {
        root.findByProps({ testID: 'goal-version-reason-input' }).props.onChangeText(label);
      },
    },
    {
      name: 'CreateGoalTransitionModal',
      bannerTestId: 'goal-transition-error-banner',
      submitTestId: 'goal-transition-submit-button',
      primaryInputTestId: 'goal-transition-title-input',
      renderModal: ({ visible, isSubmitting, onClose, onSubmit }) => (
        <CreateGoalTransitionModal visible={visible} isSubmitting={isSubmitting} isDark={false} onClose={onClose} onSubmit={onSubmit} />
      ),
      fillValid: (root, label) => {
        root.findByProps({ testID: 'goal-transition-title-input' }).props.onChangeText(label);
        root.findByProps({ testID: 'goal-transition-reason-input' }).props.onChangeText(`Reason ${label}`);
      },
    },
  ];

  describe.each(specs)('$name', (spec) => {
    it('Case A & B2: stale reject & finally do not inject old banner or unlock newer pending submit after close/reopen', async () => {
      const p1 = createDeferred<void>();
      const p2 = createDeferred<void>();
      const onSubmit = jest
        .fn()
        .mockImplementationOnce(() => p1.promise)
        .mockImplementationOnce(() => p2.promise);
      const onClose = jest.fn();

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(spec.renderModal({ visible: true, isSubmitting: false, onClose, onSubmit }));
      });

      renderer.act(() => spec.fillValid(tree.root, 'Attempt 1'));
      await renderer.act(async () => {
        tree.root.findByProps({ testID: spec.submitTestId }).props.onPress();
      });
      expect(onSubmit).toHaveBeenCalledTimes(1);

      // Parent closes and reopens modal while Attempt 1 is still pending
      await renderer.act(async () => {
        tree.update(spec.renderModal({ visible: false, isSubmitting: false, onClose, onSubmit }));
      });
      await renderer.act(async () => {
        tree.update(spec.renderModal({ visible: true, isSubmitting: false, onClose, onSubmit }));
      });

      renderer.act(() => spec.fillValid(tree.root, 'Attempt 2'));
      await renderer.act(async () => {
        tree.root.findByProps({ testID: spec.submitTestId }).props.onPress();
      });
      expect(onSubmit).toHaveBeenCalledTimes(2);
      expect(tree.root.findByProps({ testID: spec.submitTestId }).props.disabled).toBe(true);

      // Old request 1 rejects after reopen + Attempt 2 started
      await renderer.act(async () => {
        p1.reject(new ApiError(400, 'Old failure'));
        await Promise.resolve();
      });

      expect(tree.root.findAllByProps({ testID: spec.bannerTestId }).length).toBe(0);
      expect(tree.root.findByProps({ testID: spec.primaryInputTestId }).props.value).toBe('Attempt 2');
      // Old finally MUST NOT unlock Attempt 2 while p2 is still pending
      expect(tree.root.findByProps({ testID: spec.submitTestId }).props.disabled).toBe(true);

      // Clean up p2
      await renderer.act(async () => {
        p2.resolve();
        await Promise.resolve();
      });
      expect(onClose).toHaveBeenCalledTimes(1);
    });

    it('Case B: stale resolve after close/reopen does not close or reset the newly opened form', async () => {
      const p1 = createDeferred<void>();
      const onSubmit = jest.fn().mockImplementationOnce(() => p1.promise);
      const onClose = jest.fn();

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(spec.renderModal({ visible: true, isSubmitting: false, onClose, onSubmit }));
      });

      renderer.act(() => spec.fillValid(tree.root, 'Attempt 1'));
      await renderer.act(async () => {
        tree.root.findByProps({ testID: spec.submitTestId }).props.onPress();
      });

      await renderer.act(async () => {
        tree.update(spec.renderModal({ visible: false, isSubmitting: false, onClose, onSubmit }));
      });
      await renderer.act(async () => {
        tree.update(spec.renderModal({ visible: true, isSubmitting: false, onClose, onSubmit }));
      });

      renderer.act(() => spec.fillValid(tree.root, 'Attempt 2 Fresh'));

      await renderer.act(async () => {
        p1.resolve();
        await Promise.resolve();
      });

      expect(onClose).not.toHaveBeenCalled();
      expect(tree.root.findByProps({ testID: spec.primaryInputTestId }).props.value).toBe('Attempt 2 Fresh');
      expect(tree.root.findAllByProps({ testID: spec.bannerTestId }).length).toBe(0);
    });

    it('Case C & D: current request error surfaces inline keeping input, and current success closes normally', async () => {
      const p1 = createDeferred<void>();
      const p2 = createDeferred<void>();
      const onSubmit = jest
        .fn()
        .mockImplementationOnce(() => p1.promise)
        .mockImplementationOnce(() => p2.promise);
      const onClose = jest.fn();

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(spec.renderModal({ visible: true, isSubmitting: false, onClose, onSubmit }));
      });

      renderer.act(() => spec.fillValid(tree.root, 'Current Draft'));
      await renderer.act(async () => {
        tree.root.findByProps({ testID: spec.submitTestId }).props.onPress();
      });

      await renderer.act(async () => {
        p1.reject(new ApiError(500, 'Database failure postgres://secret'));
        await Promise.resolve();
      });

      const banner = tree.root.findByProps({ testID: spec.bannerTestId });
      expect(getTextInside(banner)).not.toContain('postgres://');
      expect(tree.root.findByProps({ testID: spec.primaryInputTestId }).props.value).toBe('Current Draft');
      expect(onClose).not.toHaveBeenCalled();

      await renderer.act(async () => {
        tree.root.findByProps({ testID: spec.submitTestId }).props.onPress();
      });
      await renderer.act(async () => {
        p2.resolve();
        await Promise.resolve();
      });
      expect(onClose).toHaveBeenCalledTimes(1);
    });
  });

  describe('CurrentGoalScreen parent integration', () => {
    let mountedTrees: renderer.ReactTestRenderer[] = [];

    const resetGoalApiMocks = () => {
      jest.clearAllMocks();
      Object.values(fitnessGoalApi).forEach((fn) => {
        if (jest.isMockFunction(fn)) {
          fn.mockReset();
        }
      });
      (fitnessGoalApi.getGoalVersions as jest.Mock).mockResolvedValue({ items: [], totalPages: 1 });
      (fitnessGoalApi.getGoalTransitions as jest.Mock).mockResolvedValue([]);
      (fitnessGoalApi.getMyGoalProposals as jest.Mock).mockResolvedValue({ items: [], totalPages: 1 });
    };

    beforeEach(() => {
      mountedTrees = [];
      resetGoalApiMocks();
    });

    afterEach(() => {
      for (const t of mountedTrees) {
        renderer.act(() => {
          t.unmount();
        });
      }
      mountedTrees = [];
    });

    it('CurrentGoalScreen parent handler prevents old createGoal settle/finally from closing or unlocking reopened modal', async () => {
      const alertSpy = jest.spyOn(Alert, 'alert').mockImplementation(() => {});
      const p1 = createDeferred<FitnessGoal>();
      const p2 = createDeferred<FitnessGoal>();

      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockRejectedValueOnce(
        new ApiError(404, 'No goal', {
          errorCode: 'GOAL_NOT_FOUND',
          message: 'No goal',
          timestamp: '2026-11-26T00:00:00Z',
          requestId: 'req-empty',
          fieldErrors: [],
        })
      );
      (fitnessGoalApi.createGoal as jest.Mock)
        .mockImplementationOnce(() => p1.promise)
        .mockImplementationOnce(() => p2.promise);

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      mountedTrees.push(tree);
      const root = tree.root;

      // Open #1 and submit p1
      await renderer.act(async () => {
        root.findByProps({ testID: 'create-first-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'create-goal-title-input' }).props.onChangeText('Goal 1');
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'create-goal-submit-button' }).props.onPress();
      });

      // Parent modal closes & reopens (#2)
      await renderer.act(async () => {
        root.findByType(CreateGoalModal).props.onClose();
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'create-first-goal-button' }).props.onPress();
      });

      renderer.act(() => {
        root.findByProps({ testID: 'create-goal-title-input' }).props.onChangeText('Goal 2');
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'create-goal-submit-button' }).props.onPress();
      });
      expect(fitnessGoalApi.createGoal).toHaveBeenCalledTimes(2);
      expect(root.findByType(CreateGoalModal).props.isSubmitting).toBe(true);
      expect(root.findByProps({ testID: 'create-goal-submit-button' }).props.disabled).toBe(true);

      // Old p1 rejects -> must NOT alert, show banner in Modal #2, or unlock isSubmitting while p2 pending
      await renderer.act(async () => {
        p1.reject(new ApiError(500, 'Old failure'));
        await Promise.resolve();
      });

      expect(alertSpy).not.toHaveBeenCalled();
      expect(root.findAllByProps({ testID: 'create-goal-error-banner' }).length).toBe(0);
      expect(root.findByType(CreateGoalModal).props.isSubmitting).toBe(true);
      expect(root.findByProps({ testID: 'create-goal-submit-button' }).props.disabled).toBe(true);
      expect(root.findByProps({ testID: 'create-goal-modal' }).props.visible).toBe(true);

      // Resolve p2 cleanly
      const createdGoal: FitnessGoal = {
        id: 'goal-2',
        studentId: 's1',
        title: 'Goal 2',
        status: 'ACTIVE',
        createdBy: 's1',
        activatedAt: '2026-11-26T00:00:00Z',
        pausedAt: null,
        completedAt: null,
        endedAt: null,
        statusReason: null,
        createdAt: '2026-11-26T00:00:00Z',
        updatedAt: '2026-11-26T00:00:00Z',
        currentVersion: null as any,
      };
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(createdGoal);
      await renderer.act(async () => {
        p2.resolve(createdGoal);
        await Promise.resolve();
      });
      expect(root.findByProps({ testID: 'create-goal-modal' }).props.visible).toBe(false);
      alertSpy.mockRestore();
    });

    it('CurrentGoalScreen parent handler prevents old createGoal resolve after close/reopen from closing new modal while still refreshing valid server state', async () => {
      const p1 = createDeferred<FitnessGoal>();
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockRejectedValueOnce(
        new ApiError(404, 'No goal', {
          errorCode: 'GOAL_NOT_FOUND',
          message: 'No goal',
          timestamp: '2026-11-26T00:00:00Z',
          requestId: 'req-empty',
          fieldErrors: [],
        })
      );
      (fitnessGoalApi.createGoal as jest.Mock).mockImplementationOnce(() => p1.promise);

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      mountedTrees.push(tree);
      const root = tree.root;

      await renderer.act(async () => {
        root.findByProps({ testID: 'create-first-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'create-goal-title-input' }).props.onChangeText('Goal Old');
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'create-goal-submit-button' }).props.onPress();
      });

      // Close & reopen Modal #2 while p1 is pending
      await renderer.act(async () => {
        root.findByType(CreateGoalModal).props.onClose();
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'create-first-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'create-goal-title-input' }).props.onChangeText('Goal New Unsubmitted');
      });

      const createdGoal: FitnessGoal = {
        id: 'goal-1-server',
        studentId: 's1',
        title: 'Goal Old',
        status: 'ACTIVE',
        createdBy: 's1',
        activatedAt: '2026-11-26T00:00:00Z',
        pausedAt: null,
        completedAt: null,
        endedAt: null,
        statusReason: null,
        createdAt: '2026-11-26T00:00:00Z',
        updatedAt: '2026-11-26T00:00:00Z',
        currentVersion: null as any,
      };
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockResolvedValueOnce(createdGoal);

      await renderer.act(async () => {
        p1.resolve(createdGoal);
        await Promise.resolve();
      });

      // Server refresh happened, and Modal #2 was NOT closed by old p1 handler
      expect(fitnessGoalApi.getCurrentGoal).toHaveBeenCalledTimes(2);
      expect(root.findByProps({ testID: 'create-goal-modal' }).props.visible).toBe(true);
      expect(root.findByProps({ testID: 'create-goal-title-input' }).props.value).toBe('Goal New Unsubmitted');
    });

    const baseActiveGoal: FitnessGoal = {
      id: 'goal-active-1',
      studentId: 's1',
      title: 'Current Active Goal',
      status: 'ACTIVE',
      createdBy: 's1',
      activatedAt: '2026-11-26T00:00:00Z',
      pausedAt: null,
      completedAt: null,
      endedAt: null,
      statusReason: null,
      createdAt: '2026-11-26T00:00:00Z',
      updatedAt: '2026-11-26T00:00:00Z',
      currentVersion: {
        id: 'ver-1',
        versionNumber: 1,
        title: 'Current Active Goal',
        startDate: '2026-11-26',
        targetDate: null,
        durationDays: 60,
        effectiveFrom: '2026-11-26T00:00:00Z',
        effectiveUntil: null,
        resumeDate: null,
        changeReason: null,
        changeSummary: null,
        createdBy: 's1',
        sourceProposalId: null,
        createdAt: '2026-11-26T00:00:00Z',
        lockedAt: null,
        lockedBy: null,
        lockReason: null,
        isCurrent: true,
        objectives: [
          {
            id: 'obj-1',
            goalTypeId: 1,
            goalTypeCode: 'FAT_LOSS',
            goalTypeName: 'Fat Loss',
            priority: 'PRIMARY',
            sortOrder: 1,
            notes: null,
          },
        ],
        targets: [],
      },
    };

    it('preserves open CreateGoalVersionModal draft and valid goal when stale version sync GET fails transiently, and retries GET only without resetting draft', async () => {
      const pMut1 = createDeferred<FitnessGoal>();
      const pReconcileGet = createDeferred<FitnessGoal>();
      const pRetryGet = createDeferred<FitnessGoal>();
      (fitnessGoalApi.getCurrentGoal as jest.Mock)
        .mockResolvedValueOnce(baseActiveGoal)
        .mockImplementationOnce(() => pReconcileGet.promise)
        .mockImplementationOnce(() => pRetryGet.promise);
      (fitnessGoalApi.createGoalVersion as jest.Mock).mockImplementationOnce(() => pMut1.promise);

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      mountedTrees.push(tree);
      const root = tree.root;

      // Open Version Modal #1 and submit pMut1 (pending)
      renderer.act(() => {
        root.findByProps({ testID: 'adjust-goal-version-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'goal-version-reason-input' }).props.onChangeText('Old reason v1');
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'goal-version-submit-button' }).props.onPress();
      });
      expect(fitnessGoalApi.createGoalVersion).toHaveBeenCalledTimes(1);

      // Close Version Modal #1, open Version Modal #2, and enter unsaved draft
      await renderer.act(async () => {
        root.findByType(CreateGoalVersionModal).props.onClose();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'adjust-goal-version-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'goal-version-title-input' }).props.onChangeText('Draft Version v3');
        root.findByProps({ testID: 'goal-version-reason-input' }).props.onChangeText('Draft Reason v3');
      });

      // Stale pMut1 resolves on server; subsequent background GET rejects with transient network error
      const refreshedGoalV2: FitnessGoal = {
        ...baseActiveGoal,
        title: 'Server Version v2',
        currentVersion: {
          ...baseActiveGoal.currentVersion!,
          id: 'ver-2',
          versionNumber: 2,
          title: 'Server Version v2',
        },
      };

      await renderer.act(async () => {
        pMut1.resolve(refreshedGoalV2);
        await Promise.resolve();
      });
      await renderer.act(async () => {
        pReconcileGet.reject(new ApiError(0, 'Network timeout'));
        await Promise.resolve();
      });

      // Editor must stay mounted & open with user draft intact; no blocking error card; no duplicate mutation
      expect(root.findAllByProps({ testID: 'goal-error-card' })).toHaveLength(0);
      expect(root.findByProps({ testID: 'create-goal-version-modal' }).props.visible).toBe(true);
      expect(root.findByProps({ testID: 'goal-version-title-input' }).props.value).toBe('Draft Version v3');
      expect(root.findByProps({ testID: 'goal-version-reason-input' }).props.value).toBe('Draft Reason v3');
      expect(fitnessGoalApi.createGoalVersion).toHaveBeenCalledTimes(1);
      expect(root.findByProps({ testID: 'reconciliation-retry-banner' })).toBeDefined();

      // Retry sync must call GET only and preserve open draft when new snapshot arrives
      renderer.act(() => {
        root.findByProps({ testID: 'retry-reconciliation-button' }).props.onPress();
      });
      expect(fitnessGoalApi.createGoalVersion).toHaveBeenCalledTimes(1);

      await renderer.act(async () => {
        pRetryGet.resolve(refreshedGoalV2);
        await Promise.resolve();
      });

      expect(root.findAllByProps({ testID: 'reconciliation-retry-banner' })).toHaveLength(0);
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Server Version v2');
      expect(root.findByProps({ testID: 'create-goal-version-modal' }).props.visible).toBe(true);
      expect(root.findByProps({ testID: 'goal-version-title-input' }).props.value).toBe('Draft Version v3');
      expect(root.findByProps({ testID: 'goal-version-reason-input' }).props.value).toBe('Draft Reason v3');
    });

    it('preserves open CreateGoalTransitionModal draft and valid goal when stale transition sync GET fails transiently, and retries GET only without resetting draft', async () => {
      const pMut1 = createDeferred<FitnessGoal>();
      const pReconcileGet = createDeferred<FitnessGoal>();
      const pRetryGet = createDeferred<FitnessGoal>();
      (fitnessGoalApi.getCurrentGoal as jest.Mock)
        .mockResolvedValueOnce(baseActiveGoal)
        .mockImplementationOnce(() => pReconcileGet.promise)
        .mockImplementationOnce(() => pRetryGet.promise);
      (fitnessGoalApi.createGoalTransition as jest.Mock).mockImplementationOnce(() => pMut1.promise);

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen />);
      });
      mountedTrees.push(tree);
      const root = tree.root;

      // Open Transition Modal #1 and submit pMut1 (pending)
      renderer.act(() => {
        root.findByProps({ testID: 'transition-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'goal-transition-reason-input' }).props.onChangeText('Phase complete');
        root.findByProps({ testID: 'goal-transition-title-input' }).props.onChangeText('Old Next Goal');
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'goal-transition-submit-button' }).props.onPress();
      });
      expect(fitnessGoalApi.createGoalTransition).toHaveBeenCalledTimes(1);

      // Close Transition Modal #1, open Transition Modal #2, enter unsaved draft
      await renderer.act(async () => {
        root.findByType(CreateGoalTransitionModal).props.onClose();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'transition-goal-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'goal-transition-reason-input' }).props.onChangeText('Draft Transition Reason');
        root.findByProps({ testID: 'goal-transition-title-input' }).props.onChangeText('Draft Transition Title');
      });

      const transitionedGoal: FitnessGoal = {
        ...baseActiveGoal,
        id: 'goal-active-2',
        title: 'Old Next Goal',
      };

      // Stale pMut1 resolves on server; subsequent background GET fails with 500
      await renderer.act(async () => {
        pMut1.resolve(transitionedGoal);
        await Promise.resolve();
      });
      await renderer.act(async () => {
        pReconcileGet.reject(new ApiError(500, 'Upstream error'));
        await Promise.resolve();
      });

      expect(root.findAllByProps({ testID: 'goal-error-card' })).toHaveLength(0);
      expect(root.findByProps({ testID: 'create-goal-transition-modal' }).props.visible).toBe(true);
      expect(root.findByProps({ testID: 'goal-transition-reason-input' }).props.value).toBe('Draft Transition Reason');
      expect(root.findByProps({ testID: 'goal-transition-title-input' }).props.value).toBe('Draft Transition Title');
      expect(fitnessGoalApi.createGoalTransition).toHaveBeenCalledTimes(1);
      expect(root.findByProps({ testID: 'reconciliation-retry-banner' })).toBeDefined();

      // Retry reconciliation calls GET only and preserves draft
      renderer.act(() => {
        root.findByProps({ testID: 'retry-reconciliation-button' }).props.onPress();
      });
      expect(fitnessGoalApi.createGoalTransition).toHaveBeenCalledTimes(1);

      await renderer.act(async () => {
        pRetryGet.resolve(transitionedGoal);
        await Promise.resolve();
      });

      expect(root.findAllByProps({ testID: 'reconciliation-retry-banner' })).toHaveLength(0);
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Old Next Goal');
      expect(root.findByProps({ testID: 'create-goal-transition-modal' }).props.visible).toBe(true);
      expect(root.findByProps({ testID: 'goal-transition-reason-input' }).props.value).toBe('Draft Transition Reason');
      expect(root.findByProps({ testID: 'goal-transition-title-input' }).props.value).toBe('Draft Transition Title');
    });

    it('follows access/404 policy on background sync 401/403/404 instead of preserving stale goal data or open editor', async () => {
      const scenarios = [
        { label: '403', error: new ApiError(403, 'Forbidden'), goalId: 'goal-active-1', expectErrorCard: true },
        { label: '401', error: new ApiError(401, 'Unauthorized'), goalId: 'goal-active-1', expectErrorCard: true },
        { label: '404', error: new ApiError(404, 'Not found'), goalId: undefined, expectErrorCard: false },
      ];

      for (const scenario of scenarios) {
        resetGoalApiMocks();
        const pMut1 = createDeferred<FitnessGoal>();
        const pReconcileGet = createDeferred<FitnessGoal>();
        const loader = scenario.goalId
          ? (fitnessGoalApi.getGoal as jest.Mock)
          : (fitnessGoalApi.getCurrentGoal as jest.Mock);

        loader
          .mockResolvedValueOnce(baseActiveGoal)
          .mockImplementationOnce(() => pReconcileGet.promise);
        (fitnessGoalApi.createGoalVersion as jest.Mock).mockImplementationOnce(() => pMut1.promise);

        let tree!: renderer.ReactTestRenderer;
        await renderer.act(async () => {
          tree = renderer.create(<CurrentGoalScreen goalId={scenario.goalId} />);
        });
        mountedTrees.push(tree);
        const root = tree.root;

        renderer.act(() => {
          root.findByProps({ testID: 'adjust-goal-version-button' }).props.onPress();
        });
        renderer.act(() => {
          root.findByProps({ testID: 'goal-version-reason-input' }).props.onChangeText('Pending change');
        });
        await renderer.act(async () => {
          root.findByProps({ testID: 'goal-version-submit-button' }).props.onPress();
        });

        await renderer.act(async () => {
          root.findByType(CreateGoalVersionModal).props.onClose();
        });
        renderer.act(() => {
          root.findByProps({ testID: 'adjust-goal-version-button' }).props.onPress();
        });
        renderer.act(() => {
          root.findByProps({ testID: 'goal-version-title-input' }).props.onChangeText(`Draft before ${scenario.label}`);
        });

        await renderer.act(async () => {
          pMut1.resolve(baseActiveGoal);
          await Promise.resolve();
        });
        await renderer.act(async () => {
          pReconcileGet.reject(scenario.error);
          await Promise.resolve();
        });

        // Stale goal data & version editor must NOT remain active after confirmed 401/403/404
        expect(root.findAllByProps({ testID: 'create-goal-version-modal' })).toHaveLength(0);
        expect(root.findAllByProps({ testID: 'reconciliation-retry-banner' })).toHaveLength(0);
        if (scenario.expectErrorCard) {
          expect(root.findByProps({ testID: 'goal-error-card' })).toBeDefined();
        } else {
          expect(root.findAllByProps({ testID: 'goal-error-card' })).toHaveLength(0);
          expect(root.findByProps({ testID: 'create-first-goal-button' })).toBeDefined();
        }
      }
    });

    it('renders initial load error card on network failure and ignores stale reconciliation error after route change', async () => {
      // 1. Initial load failure still shows goal-error-card
      (fitnessGoalApi.getCurrentGoal as jest.Mock).mockRejectedValueOnce(new ApiError(0, 'Offline'));
      let initTree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        initTree = renderer.create(<CurrentGoalScreen />);
      });
      mountedTrees.push(initTree);
      expect(initTree.root.findByProps({ testID: 'goal-error-card' })).toBeDefined();

      // 2. Stale mutation on route A resolving after route changes to B must not mutate route B
      resetGoalApiMocks();
      const goalA: FitnessGoal = { ...baseActiveGoal, id: 'goal-A', title: 'Goal A Title' };
      const goalB: FitnessGoal = { ...baseActiveGoal, id: 'goal-B', title: 'Goal B Title' };
      const pMutA = createDeferred<FitnessGoal>();

      (fitnessGoalApi.getGoal as jest.Mock)
        .mockResolvedValueOnce(goalA)
        .mockResolvedValueOnce(goalB);
      (fitnessGoalApi.createGoalVersion as jest.Mock).mockImplementationOnce(() => pMutA.promise);

      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(<CurrentGoalScreen goalId="goal-A" />);
      });
      mountedTrees.push(tree);
      const root = tree.root;

      renderer.act(() => {
        root.findByProps({ testID: 'adjust-goal-version-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'goal-version-reason-input' }).props.onChangeText('Change A');
      });
      await renderer.act(async () => {
        root.findByProps({ testID: 'goal-version-submit-button' }).props.onPress();
      });

      // Route switches to goal-B before pMutA settles
      await renderer.act(async () => {
        tree.update(<CurrentGoalScreen goalId="goal-B" />);
      });
      renderer.act(() => {
        root.findByProps({ testID: 'adjust-goal-version-button' }).props.onPress();
      });
      renderer.act(() => {
        root.findByProps({ testID: 'goal-version-title-input' }).props.onChangeText('Goal B Draft');
      });

      await renderer.act(async () => {
        pMutA.resolve(goalA);
        await Promise.resolve();
      });

      expect(fitnessGoalApi.getGoal).toHaveBeenCalledTimes(2);
      expect(root.findByProps({ testID: 'goal-title' }).props.children).toBe('Goal B Title');
      expect(root.findByProps({ testID: 'create-goal-version-modal' }).props.visible).toBe(true);
      expect(root.findByProps({ testID: 'goal-version-title-input' }).props.value).toBe('Goal B Draft');
    });
  });
});
