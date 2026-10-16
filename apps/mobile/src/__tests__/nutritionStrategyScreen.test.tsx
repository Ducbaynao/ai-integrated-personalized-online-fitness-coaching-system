import React from 'react';
import renderer from 'react-test-renderer';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { useSharingSummary } from '@/features/coaching/coachingQueries';
import { createCommandKey } from '@/services/coachingApi';
import { nutritionApi } from '@/features/nutrition/nutritionApi';
import { NutritionStrategyScreen } from '@/features/nutrition/NutritionStrategyScreen';
import { ApiError } from '@/types/auth';

jest.mock('@tanstack/react-query', () => ({ useQuery: jest.fn(), useQueryClient: jest.fn() }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/features/coaching/coachingQueries', () => ({ useSharingSummary: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/features/nutrition/nutritionApi', () => ({ nutritionApi: {
  strategy: jest.fn(), trainerStrategy: jest.fn(), goalTypes: jest.fn(), targetNutrients: jest.fn(), targetDay: jest.fn(),
  changeStrategy: jest.fn(), proposeStrategy: jest.fn(), decideStrategy: jest.fn(), chooseDayType: jest.fn(),
  overrideTarget: jest.fn(), transitionGoal: jest.fn(),
  frozenCorrections: jest.fn(), trainerFrozenCorrections: jest.fn(), correctFrozenTarget: jest.fn(),
} }));

const catalog = [{ id: 12, code: 'BALANCE', name: 'Cân bằng' }];
const nutrients = [{ id: 41, code: 'ENERGY', name: 'Energy', defaultUnitId: 93,
  unitCode: 'KCAL', unitSymbol: 'kcal', dimension: 'ENERGY' }];
const proposal = { id: 'proposal-id', studentId: 'student-id', trainerId: 'trainer-id', kind: 'NEW_GOAL',
  status: 'PENDING', proposalVersion: 2, goalId: null, baseVersionId: null, baseGoalVersion: null,
  title: 'Cân bằng', goalTypeId: 12, timezone: 'Asia/Ho_Chi_Minh', effectiveFrom: '2026-10-17',
  reason: 'Theo lịch tập', targets: [{ nutrientId: 41, unitId: 93, dayType: 'DEFAULT', targetAmount: 1800 }],
  createdAt: '2026-10-15T00:00:00Z', decidedAt: null, decidedGoalId: null, decidedGoalVersionId: null };
const refresh = jest.fn().mockResolvedValue(undefined);

function press(tree: renderer.ReactTestRenderer, label: string) {
  const control = tree.root.findAll((node) => node.props.label === label && typeof node.props.onPress === 'function')[0];
  expect(control).toBeTruthy(); control.props.onPress();
}
function type(tree: renderer.ReactTestRenderer, label: string, value: string) {
  renderer.act(() => tree.root.findByProps({ accessibilityLabel: label }).props.onChangeText(value));
}
function chooseRadio(tree: renderer.ReactTestRenderer, text: string) {
  const control = tree.root.findAll((node) => node.props.accessibilityRole === 'radio'
    && node.props.accessibilityLabel === `Chọn loại mục tiêu ${text}`)[0];
  expect(control).toBeTruthy(); renderer.act(() => control.props.onPress());
}

describe('B07 nutrition strategy Mobile', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-id' }, activeCapability: 'STUDENT' });
    (useQueryClient as jest.Mock).mockReturnValue({ invalidateQueries: refresh, removeQueries: jest.fn() });
    (useSharingSummary as jest.Mock).mockReturnValue({ data: null });
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('frozen-corrections') ? [] : queryKey.includes('types') ? catalog : queryKey.includes('nutrients') ? nutrients
        : queryKey.includes('day') ? undefined : { goal: null, proposals: [proposal] },
      isPending: false, isError: false, error: null, refetch: jest.fn(),
    }));
    (createCommandKey as jest.Mock).mockReturnValue('stable-key');
    (nutritionApi.changeStrategy as jest.Mock).mockResolvedValue({});
    (nutritionApi.proposeStrategy as jest.Mock).mockResolvedValue({});
    (nutritionApi.decideStrategy as jest.Mock).mockResolvedValue({});
  });

  it('previews a Student target from catalog IDs and preserves body/key on uncertain retry', async () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionStrategyScreen />); });
    type(tree, 'Tên mục tiêu', 'Cân bằng');
    chooseRadio(tree, 'Cân bằng');
    type(tree, 'Múi giờ IANA của lịch target', 'Asia/Ho_Chi_Minh');
    type(tree, 'Có hiệu lực từ ngày (YYYY-MM-DD)', '2026-10-17');
    type(tree, 'Energy · Ngày mặc định (kcal)', '1800');
    renderer.act(() => press(tree, 'Xem trước toàn bộ mục tiêu'));
    (nutritionApi.changeStrategy as jest.Mock).mockRejectedValueOnce(new Error('network unknown'));
    await renderer.act(async () => { press(tree, 'Xác nhận và áp dụng'); });
    expect(nutritionApi.changeStrategy).toHaveBeenCalledWith(expect.objectContaining({ confirmed: true,
      goalTypeId: 12, timezone: 'Asia/Ho_Chi_Minh', commandKey: 'stable-key',
      targets: [{ nutrientId: 41, unitId: 93, dayType: 'DEFAULT', targetAmount: 1800 }] }));
    await renderer.act(async () => { press(tree, 'Thử lại cùng lệnh'); });
    expect((nutritionApi.changeStrategy as jest.Mock).mock.calls[1]).toEqual((nutritionApi.changeStrategy as jest.Mock).mock.calls[0]);
    renderer.act(() => tree.unmount());
  }, 15000);

  it('requires current CONTRIBUTE for Trainer proposal rather than deriving it from VIEW', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-id' }, activeCapability: 'TRAINER' });
    (useSharingSummary as jest.Mock).mockReturnValue({ data: { relationshipStatus: 'ACTIVE', items: [
      { dataScope: 'NUTRITION_GOAL', state: 'ALLOWED', accessLevel: 'VIEW' },
    ] } });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionStrategyScreen studentId="student-id" relationshipId="relationship-id" />); });
    expect(tree.root.findAll((node) => node.props.label === 'Xem trước toàn bộ mục tiêu')).toHaveLength(0);
    expect(JSON.stringify(tree.toJSON())).toContain('Chỉ xem');
    renderer.act(() => tree.unmount());
  });

  it('on 409 clears Student ACCEPT retry and requires a fresh decision', async () => {
    (nutritionApi.decideStrategy as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'NUTRITION_PROPOSAL_STALE'));
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionStrategyScreen />); });
    renderer.act(() => press(tree, 'Xem để chấp nhận'));
    await renderer.act(async () => { press(tree, 'Xác nhận quyết định'); });
    expect(nutritionApi.decideStrategy).toHaveBeenCalledWith('proposal-id', {
      decision: 'ACCEPT', expectedProposalVersion: 2, commandKey: 'stable-key',
    });
    expect(tree.root.findAll((node) => node.props.label === 'Thử lại cùng lệnh')).toHaveLength(0);
    expect(refresh).toHaveBeenCalled();
    renderer.act(() => tree.unmount());
  });

  it('confirms one frozen nutrient correction with original/current target and keeps retry identity', async () => {
    const frozenDay = { date: '2026-10-16', timezone: 'Asia/Ho_Chi_Minh', frozen: true,
      dayType: 'DEFAULT', goalVersionId: 'goal-version', targets: [{ nutrientId: 41, unitId: 93,
        targetAmount: 1800, originalTargetAmount: 2000, correctionVersion: 1,
        source: 'CORRECTED', sourceTargetId: 'source-target', sourceOverrideId: null,
        overrideVersion: null }] };
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('frozen-corrections') ? [] : queryKey.includes('types') ? catalog
        : queryKey.includes('nutrients') ? nutrients
          : queryKey.includes('day') ? frozenDay : { goal: null, proposals: [] },
      isPending: false, isError: false, error: null, refetch: jest.fn(),
    }));
    (nutritionApi.correctFrozenTarget as jest.Mock).mockRejectedValueOnce(new Error('uncertain network'))
      .mockResolvedValueOnce(frozenDay);
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionStrategyScreen />); });
    type(tree, 'Ngày (YYYY-MM-DD)', '2026-10-16');
    renderer.act(() => press(tree, 'Xem ngày theo múi giờ đã chọn'));
    expect(JSON.stringify(tree.toJSON())).toContain('gốc');
    expect(JSON.stringify(tree.toJSON())).toContain('2000');
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Sửa target nutrient 41' }).props.onPress());
    type(tree, 'Target sau correction', '1700');
    type(tree, 'Lý do correction', 'Đánh giá lại');
    renderer.act(() => press(tree, 'Xem lại correction target đã chốt'));
    await renderer.act(async () => { press(tree, 'Xác nhận correction'); });
    expect(nutritionApi.correctFrozenTarget).toHaveBeenCalledWith('2026-10-16',
      expect.objectContaining({ nutrientId: 41, targetAmount: 1700, expectedVersion: 1,
        reason: 'Đánh giá lại', commandKey: 'stable-key' }));
    await renderer.act(async () => { press(tree, 'Thử lại cùng lệnh'); });
    expect((nutritionApi.correctFrozenTarget as jest.Mock).mock.calls[1])
      .toEqual((nutritionApi.correctFrozenTarget as jest.Mock).mock.calls[0]);
    renderer.act(() => tree.unmount());
  });
});
