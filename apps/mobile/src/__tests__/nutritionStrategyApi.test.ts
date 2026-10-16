import { request } from '@/services/apiClient';
import { nutritionApi, type NutritionChangeBody } from '@/features/nutrition/nutritionApi';
import { buildStrategyTargets } from '@/features/nutrition/NutritionStrategyScreen';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));

const nutrients = [{ id: 41, code: 'ENERGY', name: 'Energy', defaultUnitId: 93,
  unitCode: 'KCAL', unitSymbol: 'kcal', dimension: 'ENERGY' }];

describe('B07 nutrition strategy contract', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

  it('builds targets from backend identities, preserving explicit zero and omitting unknown', () => {
    expect(buildStrategyTargets({ '41:DEFAULT': '0', '41:REST_DAY': '' }, nutrients)).toEqual([
      { nutrientId: 41, unitId: 93, dayType: 'DEFAULT', targetAmount: 0 },
    ]);
    expect(() => buildStrategyTargets({ '41:DEFAULT': '' }, nutrients)).toThrow();
    expect(() => buildStrategyTargets({ '41:DEFAULT': '-1' }, nutrients)).toThrow();
  });

  it('sends direct and Trainer proposals with exact immutable source tokens and body', async () => {
    const body: NutritionChangeBody = { kind: 'CHANGE_TARGET', goalId: 'goal-id', expectedGoalVersion: 7,
      baseVersionId: 'version-id', title: 'Duy trì', goalTypeId: 12, timezone: 'Asia/Ho_Chi_Minh',
      effectiveFrom: '2026-10-17', targets: [{ nutrientId: 41, unitId: 93, dayType: 'DEFAULT', targetAmount: 1800 }],
      reason: null, confirmed: true, commandKey: 'student-key' };
    await nutritionApi.changeStrategy(body);
    const proposal = { ...body, reason: 'Cân đối lịch tập', confirmed: false, commandKey: 'trainer-key' };
    await nutritionApi.proposeStrategy('student-id', proposal);
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/strategy/goals', { method: 'POST', body });
    expect(request).toHaveBeenNthCalledWith(2, '/students/student-id/nutrition/strategy/proposals', { method: 'POST', body: proposal });
  });

  it('keeps proposal version, override version and command keys distinct', async () => {
    const decision = { decision: 'ACCEPT' as const, expectedProposalVersion: 2, commandKey: 'decision-key' };
    await nutritionApi.decideStrategy('proposal-id', decision);
    const override = { timezone: 'Asia/Ho_Chi_Minh', goalVersionId: 'version-id', nutrientId: 41,
      unitId: 93, targetAmount: 0, reason: 'Ngày đặc biệt', expectedOverrideVersion: 3, commandKey: 'override-key' };
    await nutritionApi.overrideTarget('2026-10-18', override);
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/strategy/proposals/proposal-id/decisions', { method: 'POST', body: decision });
    expect(request).toHaveBeenNthCalledWith(2, '/nutrition/strategy/days/2026-10-18/overrides', { method: 'POST', body: override });
  });

  it('uses separate Student/Trainer reads and encoded target timezone', async () => {
    await nutritionApi.strategy();
    await nutritionApi.trainerStrategy('student-id');
    await nutritionApi.targetDay('2026-10-18', 'Asia/Ho_Chi_Minh');
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/strategy/me');
    expect(request).toHaveBeenNthCalledWith(2, '/students/student-id/nutrition/strategy');
    expect(request).toHaveBeenNthCalledWith(3, '/nutrition/strategy/days/2026-10-18?timezone=Asia%2FHo_Chi_Minh');
  });

  it('sends frozen target correction separately from Food Log actual and exposes history reads', async () => {
    const correction = { timezone: 'Asia/Ho_Chi_Minh', nutrientId: 41, targetAmount: 1800,
      expectedVersion: 2, reason: 'Student reviewed target', commandKey: 'correction-command' };
    await nutritionApi.correctFrozenTarget('2026-10-16', correction);
    await nutritionApi.frozenCorrections('2026-10-16');
    await nutritionApi.trainerFrozenCorrections('student-id', '2026-10-16');
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/strategy/days/2026-10-16/frozen-target-corrections',
      { method: 'POST', body: correction });
    expect(request).toHaveBeenNthCalledWith(2, '/nutrition/strategy/days/2026-10-16/frozen-target-corrections');
    expect(request).toHaveBeenNthCalledWith(3,
      '/students/student-id/nutrition/strategy/days/2026-10-16/frozen-target-corrections');
  });
});
