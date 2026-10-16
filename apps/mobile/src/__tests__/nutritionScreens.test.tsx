import React from 'react';
import renderer from 'react-test-renderer';
import moment from 'moment-timezone';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { useAuth } from '@/features/auth/AuthContext';
import { createCommandKey } from '@/services/coachingApi';
import { nutritionApi, type NutritionDay } from '@/features/nutrition/nutritionApi';
import { NutritionScreen, nutrientText } from '@/features/nutrition/NutritionScreen';
import { TrainerNutritionScreen } from '@/features/nutrition/TrainerNutritionScreen';
import { ApiError } from '@/types/auth';

jest.mock('@tanstack/react-query', () => ({ useQuery: jest.fn(), useQueryClient: jest.fn() }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));
jest.mock('@/services/coachingApi', () => ({ createCommandKey: jest.fn() }));
jest.mock('@/features/nutrition/nutritionApi', () => ({ nutritionApi: {
  foods: jest.fn(), day: jest.fn(), trainerDay: jest.fn(), createFood: jest.fn(), addItem: jest.fn(),
  catalogFoods: jest.fn(), addCatalogItem: jest.fn(),
  correct: jest.fn(), confirm: jest.fn(), updateFood: jest.fn(), corrections: jest.fn(),
} }));

const day: NutritionDay = { studentId: 'student-1', date: '2026-10-13', timezone: 'Asia/Ho_Chi_Minh',
  completeness: 'NOT_LOGGED', version: 0, noIntakeConfirmed: false,
  nutrients: [{ code: 'B07_ENERGY_KCAL', total: null, knownSubtotal: null, knownItems: 0, unknownItems: 0 }], items: [] };
const refresh = jest.fn().mockResolvedValue(undefined);

function press(tree: renderer.ReactTestRenderer, label: string) {
  const element = tree.root.findAll((node) => node.props.label === label && typeof node.props.onPress === 'function')[0];
  expect(element).toBeTruthy();
  element.props.onPress();
}
function input(tree: renderer.ReactTestRenderer, label: string, value: string) {
  const element = tree.root.findByProps({ accessibilityLabel: label });
  renderer.act(() => element.props.onChangeText(value));
}

describe('B07 nutrition screens', () => {
  beforeEach(() => {
    jest.clearAllMocks();
    moment.now = () => Date.parse('2026-10-13T12:00:00Z');
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'student-1', timezone: 'Asia/Ho_Chi_Minh',
      capabilities: { hasStudentProfile: true } }, activeCapability: 'STUDENT' });
    (useQueryClient as jest.Mock).mockReturnValue({ invalidateQueries: refresh, removeQueries: jest.fn() });
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('nutrition-catalog') ? [] : queryKey.includes('foods') ? [{ id: 'food-1', name: 'Oats', servingLabel: 'bowl', source: 'USER_REPORTED', quantificationStatus: 'LOGGABLE' }] : day,
      isPending: false, isFetching: false, isError: false, error: null, refetch: jest.fn(),
    }));
    (createCommandKey as jest.Mock).mockReturnValue('same-key');
    (nutritionApi.addItem as jest.Mock).mockResolvedValue(day);
    (nutritionApi.confirm as jest.Mock).mockResolvedValue(day);
  });
  afterEach(() => { moment.now = () => Date.now(); });

  it('requires explicit day/timezone, sends a serving payload, then retries the same ambiguous network command', async () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionScreen />); });
    input(tree, 'Ngày ghi (YYYY-MM-DD)', '2026-10-13');
    renderer.act(() => press(tree, 'Xác nhận ngày và múi giờ'));
    const choice = tree.root.findByProps({ accessibilityLabel: 'Chọn Oats, nguồn tự khai báo' });
    renderer.act(() => choice.props.onPress());
    input(tree, 'Số khẩu phần', '1.25');
    (nutritionApi.addItem as jest.Mock).mockRejectedValueOnce(new Error('network result unknown'));
    await renderer.act(async () => { press(tree, 'Ghi món'); });
    expect(nutritionApi.addItem).toHaveBeenCalledWith('2026-10-13', expect.objectContaining({
      timezone: 'Asia/Ho_Chi_Minh', privateFoodId: 'food-1', servings: 1.25, commandKey: 'same-key',
      consumedAt: '2026-10-13T12:00:00.000Z',
    }));
    await renderer.act(async () => { press(tree, 'Thử lại cùng lệnh'); });
    expect((nutritionApi.addItem as jest.Mock).mock.calls[1]).toEqual((nutritionApi.addItem as jest.Mock).mock.calls[0]);
    renderer.act(() => tree.unmount());
  });

  it('keeps all-unknown private food as draft and edits it before serving selection', async () => {
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('nutrition-catalog') ? [] : queryKey.includes('foods') ? [{ id: 'draft-1', name: 'Soup', servingLabel: 'bowl',
        source: 'USER_REPORTED', quantificationStatus: 'DRAFT', version: 2,
        energyKcal: null, proteinG: null, carbohydrateG: null, fatG: null }] : day,
      isPending: false, isFetching: false, isError: false, error: null, refetch: jest.fn(),
    }));
    (nutritionApi.updateFood as jest.Mock).mockResolvedValue({});
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionScreen />); });
    const choice = tree.root.findByProps({ accessibilityLabel: 'Chọn Soup, nguồn tự khai báo' });
    expect(choice.props.disabled).toBe(true);
    expect(JSON.stringify(tree.toJSON())).toContain('Bản nháp');
    renderer.act(() => press(tree, 'Bổ sung dinh dưỡng cho Soup'));
    input(tree, 'Protein (g)', '0');
    await renderer.act(async () => { press(tree, 'Lưu dinh dưỡng món nháp'); });
    expect(nutritionApi.updateFood).toHaveBeenCalledWith('draft-1', expect.objectContaining({
      expectedVersion: 2, energyKcal: null, proteinG: 0, commandKey: 'same-key',
    }));
    renderer.act(() => tree.unmount());
  });

  it('confirms an empty day as explicit no-intake, not an inferred zero', async () => {
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionScreen />); });
    input(tree, 'Ngày ghi (YYYY-MM-DD)', '2026-10-13');
    renderer.act(() => press(tree, 'Xác nhận ngày và múi giờ'));
    renderer.act(() => press(tree, 'Xác nhận hoàn tất ngày'));
    await renderer.act(async () => { press(tree, 'Xác nhận'); });
    expect(nutritionApi.confirm).toHaveBeenCalledWith('2026-10-13', {
      timezone: 'Asia/Ho_Chi_Minh', expectedDayVersion: 0, noIntakeConfirmed: true, commandKey: 'same-key',
    });
    renderer.act(() => tree.unmount());
  });

  it('keeps unknown distinct from zero and requires a new decision after 409', async () => {
    expect(nutrientText(null, null)).toBe('Chưa xác định');
    expect(nutrientText(null, 0)).toContain('phần đã biết: 0');
    expect(nutrientText(0, null)).toBe('0');
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionScreen />); });
    input(tree, 'Ngày ghi (YYYY-MM-DD)', '2026-10-13');
    renderer.act(() => press(tree, 'Xác nhận ngày và múi giờ'));
    input(tree, 'Mô tả món', 'Món tự nấu');
    (nutritionApi.addItem as jest.Mock).mockRejectedValueOnce(new ApiError(409, 'stale'));
    await renderer.act(async () => { press(tree, 'Ghi món'); });
    expect(refresh).toHaveBeenCalled();
    expect(JSON.stringify(tree.toJSON())).toContain('Hãy xem lại rồi xác nhận thao tác mới');
    expect(tree.root.findAll((node) => node.props.label === 'Thử lại cùng lệnh')).toHaveLength(0);
    renderer.act(() => tree.unmount());
  });

  it('only enables evidenced catalog units and sends the pinned revision with a stable retry key', async () => {
    const catalogFood = { id: 'catalog-food', fdcId: 12345, name: 'Foundation oats',
      sourceName: 'USDA FoodData Central', sourceRelease: 'Foundation 2026-10',
      revisionNumber: 1, revisionId: 'revision-id', status: 'PUBLISHED',
      portions: [{ id: 'portion-id', label: 'cup', gramsPerServing: 40,
        gramsPerMl: null, factorSource: 'USDA portion weight', mlFactorSource: null }] };
    (useQuery as jest.Mock).mockImplementation(({ queryKey }: { queryKey: string[] }) => ({
      data: queryKey.includes('nutrition-catalog') ? [catalogFood]
        : queryKey.includes('foods') ? [] : day,
      isPending: false, isFetching: false, isError: false, error: null, refetch: jest.fn(),
    }));
    (nutritionApi.addCatalogItem as jest.Mock).mockRejectedValueOnce(new Error('uncertain network'))
      .mockResolvedValueOnce(day);
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<NutritionScreen />); });
    input(tree, 'Ngày ghi (YYYY-MM-DD)', '2026-10-13');
    renderer.act(() => press(tree, 'Xác nhận ngày và múi giờ'));
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Chọn Foundation oats, USDA FDC 12345' }).props.onPress());
    expect(tree.root.findByProps({ accessibilityLabel: 'Đơn vị MILLILITRE' }).props.disabled).toBe(true);
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Đơn vị SERVING' }).props.onPress());
    renderer.act(() => tree.root.findByProps({ accessibilityLabel: 'Portion cup, nguồn USDA portion weight' }).props.onPress());
    input(tree, 'Số lượng theo đơn vị đã chọn', '1.25');
    await renderer.act(async () => { press(tree, 'Ghi thực phẩm catalog'); });
    expect(nutritionApi.addCatalogItem).toHaveBeenCalledWith('2026-10-13',
      expect.objectContaining({ catalogFoodId: 'catalog-food', expectedRevisionId: 'revision-id',
        portionId: 'portion-id', quantityBasis: 'SERVING', quantity: 1.25,
        commandKey: 'same-key' }));
    await renderer.act(async () => { press(tree, 'Thử lại cùng lệnh'); });
    expect((nutritionApi.addCatalogItem as jest.Mock).mock.calls[1])
      .toEqual((nutritionApi.addCatalogItem as jest.Mock).mock.calls[0]);
    renderer.act(() => tree.unmount());
  });

  it('Trainer screen is read-only and conceals a lost current VIEW grant', () => {
    (useAuth as jest.Mock).mockReturnValue({ user: { id: 'trainer-1' }, activeCapability: 'TRAINER' });
    (useQuery as jest.Mock).mockReturnValue({ data: day, isPending: false, isFetching: false,
      isError: true, error: new ApiError(404, 'concealed'), refetch: jest.fn() });
    let tree!: renderer.ReactTestRenderer;
    renderer.act(() => { tree = renderer.create(<TrainerNutritionScreen studentId="student-1" />); });
    input(tree, 'Ngày xem', '2026-10-13');
    input(tree, 'Múi giờ của ngày log', 'Asia/Ho_Chi_Minh');
    renderer.act(() => press(tree, 'Xem ngày'));
    expect(JSON.stringify(tree.toJSON())).not.toContain('Oats');
    expect(JSON.stringify(tree.toJSON())).not.toContain('Ghi món');
    renderer.act(() => tree.unmount());
  });
});
