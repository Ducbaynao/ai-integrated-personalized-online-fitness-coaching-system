import React from 'react';
import renderer from 'react-test-renderer';
import { GOAL_TYPE_OPTIONS } from '@/features/goal/goalTypeOptions';
import { CreateGoalModal } from '@/features/goal/components/CreateGoalModal';
import { CreateGoalProposalModal } from '@/features/goal/components/CreateGoalProposalModal';
import { CreateGoalTransitionModal } from '@/features/goal/components/CreateGoalTransitionModal';
import { CreateGoalVersionModal } from '@/features/goal/components/CreateGoalVersionModal';

describe('P7-008 Goal Type Options & Version Primary Lock', () => {
  it('exports the exact 5 canonical goal type options from shared goalTypeOptions module', () => {
    expect(GOAL_TYPE_OPTIONS).toEqual([
      { code: 'FAT_LOSS', label: 'Giảm mỡ' },
      { code: 'MUSCLE_GAIN', label: 'Tăng cơ' },
      { code: 'STRENGTH', label: 'Tăng sức mạnh' },
      { code: 'IMPROVE_FITNESS', label: 'Cải thiện thể lực' },
      { code: 'MAINTAIN', label: 'Duy trì thể lực' },
    ]);
    const codes = GOAL_TYPE_OPTIONS.map((opt) => opt.code);
    expect(codes).not.toContain('WEIGHT_LOSS');
    expect(codes).not.toContain('FITNESS_IMPROVEMENT');
    expect(codes).not.toContain('MAINTENANCE');
  });

  it('submits IMPROVE_FITNESS and MAINTAIN from CreateGoalModal', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <CreateGoalModal
          visible
          isSubmitting={false}
          isDark={false}
          sessionId={1}
          onClose={jest.fn()}
          onSubmit={onSubmit}
        />
      );
    });

    await renderer.act(async () => {
      tree.root
        .findByProps({ testID: 'create-goal-title-input' })
        .props.onChangeText('Cải thiện sức bền');
      tree.root
        .findByProps({ accessibilityLabel: 'Chọn Cải thiện thể lực' })
        .props.onPress();
    });
    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'create-goal-submit-button' }).props.onPress();
    });

    expect(onSubmit).toHaveBeenLastCalledWith(
      expect.objectContaining({
        title: 'Cải thiện sức bền',
        goalTypeCode: 'IMPROVE_FITNESS',
      })
    );

    await renderer.act(async () => {
      tree.root
        .findByProps({ testID: 'create-goal-title-input' })
        .props.onChangeText('Duy trì thể lực');
      tree.root
        .findByProps({ accessibilityLabel: 'Chọn Duy trì thể lực' })
        .props.onPress();
    });
    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'create-goal-submit-button' }).props.onPress();
    });

    expect(onSubmit).toHaveBeenLastCalledWith(
      expect.objectContaining({
        goalTypeCode: 'MAINTAIN',
      })
    );
    renderer.act(() => {
      tree.unmount();
    });
  });

  it('renders canonical goal type chips in CreateGoalProposalModal and submits MAINTAIN', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <CreateGoalProposalModal
          visible
          isSubmitting={false}
          isDark={false}
          currentTitle="Giữ phong độ"
          onClose={jest.fn()}
          onSubmit={onSubmit}
        />
      );
    });

    await renderer.act(async () => {
      tree.root
        .findByProps({ testID: 'goal-proposal-reason-input' })
        .props.onChangeText('Điều chỉnh thời lượng duy trì');
      tree.root
        .findByProps({ testID: 'goal-type-chip-MAINTAIN' })
        .props.onPress();
    });
    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'goal-proposal-submit-button' }).props.onPress();
    });

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        goalTypeCode: 'MAINTAIN',
      })
    );
    renderer.act(() => {
      tree.unmount();
    });
  });

  it('keeps CreateGoalTransitionModal able to select any of the 5 canonical goal types', async () => {
    const onSubmit = jest.fn().mockResolvedValue(undefined);
    let tree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      tree = renderer.create(
        <CreateGoalTransitionModal
          visible
          isSubmitting={false}
          isDark={false}
          sessionId={1}
          onClose={jest.fn()}
          onSubmit={onSubmit}
        />
      );
    });

    await renderer.act(async () => {
      tree.root
        .findByProps({ testID: 'goal-transition-reason-input' })
        .props.onChangeText('Chuyển sang giai đoạn tăng sức mạnh');
      tree.root
        .findByProps({ testID: 'goal-transition-title-input' })
        .props.onChangeText('Chu kỳ sức mạnh mới');
      const selectContainer = tree.root.findByProps({ testID: 'goal-transition-type-select' });
      const strengthChip = selectContainer.find(
        (node) =>
          typeof node.props?.onPress === 'function' &&
          node.findAll((child) => child.props?.children === 'Tăng sức mạnh').length > 0
      );
      strengthChip.props.onPress();
    });
    await renderer.act(async () => {
      await tree.root.findByProps({ testID: 'goal-transition-submit-button' }).props.onPress();
    });

    expect(onSubmit).toHaveBeenCalledWith(
      expect.objectContaining({
        goalTypeCode: 'STRENGTH',
      })
    );
    renderer.act(() => {
      tree.unmount();
    });
  });

  it('P2-1: blocks submit and never falls back to FAT_LOSS when PRIMARY goalTypeCode is missing, null, or blank', async () => {
    for (const missingCode of [undefined, null, '', '   ']) {
      const onSubmit = jest.fn().mockResolvedValue(undefined);
      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(
          <CreateGoalVersionModal
            visible
            currentTitle="Mục tiêu thiếu primary"
            currentGoalTypeCode={missingCode}
            isSubmitting={false}
            isDark={false}
            sessionId={1}
            onClose={jest.fn()}
            onSubmit={onSubmit}
          />
        );
      });

      expect(tree.root.findByProps({ testID: 'goal-version-primary-missing' })).toBeDefined();
      const submitBtn = tree.root.findByProps({ testID: 'goal-version-submit-button' });
      expect(submitBtn.props.disabled).toBe(true);

      await renderer.act(async () => {
        tree.root
          .findByProps({ testID: 'goal-version-reason-input' })
          .props.onChangeText('Thử lưu khi thiếu primary');
      });
      await renderer.act(async () => {
        await submitBtn.props.onPress();
      });

      expect(onSubmit).not.toHaveBeenCalled();
      renderer.act(() => {
        tree.unmount();
      });
    }
  });

  it('P2-2: renders PRIMARY goalTypeCode read-only with no switcher controls and preserves WEIGHT_LOSS, MUSCLE_GAIN, and non-5-option codes', async () => {
    for (const code of ['WEIGHT_LOSS', 'MUSCLE_GAIN', 'WEIGHT_GAIN', 'CUSTOM_ENDURANCE']) {
      const onSubmit = jest.fn().mockResolvedValue(undefined);
      let tree!: renderer.ReactTestRenderer;
      await renderer.act(async () => {
        tree = renderer.create(
          <CreateGoalVersionModal
            visible
            currentTitle={`Mục tiêu ${code}`}
            currentGoalTypeCode={code}
            isSubmitting={false}
            isDark={false}
            sessionId={1}
            onClose={jest.fn()}
            onSubmit={onSubmit}
          />
        );
      });

      expect(tree.root.findByProps({ testID: 'goal-version-primary-readonly' })).toBeDefined();
      const fatLossSwitchers = tree.root.findAll(
        (node) =>
          typeof node.props?.onPress === 'function' &&
          (node.props?.accessibilityLabel === 'Chọn Giảm mỡ' ||
            node.props?.testID === 'goal-type-chip-FAT_LOSS')
      );
      expect(fatLossSwitchers).toHaveLength(0);

      await renderer.act(async () => {
        tree.root
          .findByProps({ testID: 'goal-version-reason-input' })
          .props.onChangeText('Điều chỉnh chỉ tiêu cùng hành trình');
      });
      await renderer.act(async () => {
        await tree.root.findByProps({ testID: 'goal-version-submit-button' }).props.onPress();
      });

      expect(onSubmit).toHaveBeenCalledWith(
        expect.objectContaining({
          goalTypeCode: code,
        })
      );
      renderer.act(() => {
        tree.unmount();
      });
    }
  });

  it('P2-1/P2-2: ignores context goalTypeCode changes while modal remains open in the same session', async () => {
    const onSubmitMissing = jest.fn().mockResolvedValue(undefined);
    let missingTree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      missingTree = renderer.create(
        <CreateGoalVersionModal
          visible
          currentTitle="Draft thiếu primary"
          currentGoalTypeCode={undefined}
          isSubmitting={false}
          isDark={false}
          sessionId={1}
          onClose={jest.fn()}
          onSubmit={onSubmitMissing}
        />
      );
    });

    await renderer.act(async () => {
      missingTree.update(
        <CreateGoalVersionModal
          visible
          currentTitle="Draft thiếu primary"
          currentGoalTypeCode="FAT_LOSS"
          isSubmitting={false}
          isDark={false}
          sessionId={1}
          onClose={jest.fn()}
          onSubmit={onSubmitMissing}
        />
      );
    });
    await renderer.act(async () => {
      missingTree.root
        .findByProps({ testID: 'goal-version-reason-input' })
        .props.onChangeText('Vẫn phải bị chặn');
    });
    await renderer.act(async () => {
      await missingTree.root.findByProps({ testID: 'goal-version-submit-button' }).props.onPress();
    });
    expect(onSubmitMissing).not.toHaveBeenCalled();
    renderer.act(() => {
      missingTree.unmount();
    });

    const onSubmitLocked = jest.fn().mockResolvedValue(undefined);
    let lockedTree!: renderer.ReactTestRenderer;
    await renderer.act(async () => {
      lockedTree = renderer.create(
        <CreateGoalVersionModal
          visible
          currentTitle="Giảm cân Q4"
          currentGoalTypeCode="WEIGHT_LOSS"
          isSubmitting={false}
          isDark={false}
          sessionId={2}
          onClose={jest.fn()}
          onSubmit={onSubmitLocked}
        />
      );
    });
    await renderer.act(async () => {
      lockedTree.update(
        <CreateGoalVersionModal
          visible
          currentTitle="Giảm cân Q4"
          currentGoalTypeCode="MUSCLE_GAIN"
          isSubmitting={false}
          isDark={false}
          sessionId={2}
          onClose={jest.fn()}
          onSubmit={onSubmitLocked}
        />
      );
    });
    await renderer.act(async () => {
      lockedTree.root
        .findByProps({ testID: 'goal-version-reason-input' })
        .props.onChangeText('Giữ WEIGHT_LOSS của phiên đang mở');
    });
    await renderer.act(async () => {
      await lockedTree.root.findByProps({ testID: 'goal-version-submit-button' }).props.onPress();
    });
    expect(onSubmitLocked).toHaveBeenCalledWith(
      expect.objectContaining({
        goalTypeCode: 'WEIGHT_LOSS',
      })
    );
    renderer.act(() => {
      lockedTree.unmount();
    });
  });
});
