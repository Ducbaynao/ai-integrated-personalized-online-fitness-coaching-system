import { request } from '@/services/apiClient';
import { nutritionApi } from '@/features/nutrition/nutritionApi';
import { nutrientText } from '@/features/nutrition/NutritionScreen';

jest.mock('@/services/apiClient', () => ({ request: jest.fn() }));
jest.mock('@/features/auth/AuthContext', () => ({ useAuth: jest.fn() }));

describe('B07 basic nutrition contract', () => {
  beforeEach(() => { jest.clearAllMocks(); (request as jest.Mock).mockResolvedValue({}); });

  it('keeps null unknown distinct from explicitly entered zero and labels known subtotal', () => {
    expect(nutrientText(null, null)).toBe('Chưa xác định');
    expect(nutrientText(null, 25.5)).toBe('Chưa xác định (phần đã biết: 25.5)');
    expect(nutrientText(0, 0)).toBe('0');
  });

  it('sends actor-scoped command identity and original serving facts without unit conversion', async () => {
    const food = { name: 'Món riêng', servingLabel: 'chén', energyKcal: 0, proteinG: null,
      carbohydrateG: 12.5, fatG: null, commandKey: 'food-command' };
    await nutritionApi.createFood(food);
    const item = { timezone: 'Asia/Ho_Chi_Minh', consumedAt: '2026-10-13T12:00:00Z', mealType: 'OTHER' as const,
      description: null, privateFoodId: 'food-id', servings: 1.25, commandKey: 'item-command' };
    await nutritionApi.addItem('2026-10-13', item);
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/private-foods', { method: 'POST', body: food });
    expect(request).toHaveBeenNthCalledWith(2, '/nutrition/days/2026-10-13/items', { method: 'POST', body: item });
  });

  it('sends an explicit zero and expected version when reconciling a draft food', async () => {
    const update = { name: 'Món riêng', servingLabel: 'chén', energyKcal: null, proteinG: 0,
      carbohydrateG: null, fatG: null, expectedVersion: 2, commandKey: 'reconcile-command' };
    await nutritionApi.updateFood('draft-id', update);
    expect(request).toHaveBeenCalledWith('/nutrition/private-foods/draft-id', { method: 'PUT', body: update });
  });

  it('sends versioned correction and explicit empty-day confirmation', async () => {
    const correction = { expectedVersion: 4, description: 'Món chưa định lượng', privateFoodId: null,
      servings: null, remove: false, commandKey: 'correct-command' };
    await nutritionApi.correct('item-id', correction);
    const confirmation = { timezone: 'Asia/Ho_Chi_Minh', expectedDayVersion: 2,
      noIntakeConfirmed: true, commandKey: 'confirm-command' };
    await nutritionApi.confirm('2026-10-13', confirmation);
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/items/item-id/correct', { method: 'POST', body: correction });
    expect(request).toHaveBeenNthCalledWith(2, '/nutrition/days/2026-10-13/confirm', { method: 'POST', body: confirmation });
  });

  it('uses Student-owned and Trainer-grant-aware read paths separately', async () => {
    await nutritionApi.day('2026-10-13', 'Asia/Ho_Chi_Minh');
    await nutritionApi.trainerDay('student-id', '2026-10-13', 'Asia/Ho_Chi_Minh');
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/days/2026-10-13?timezone=Asia%2FHo_Chi_Minh');
    expect(request).toHaveBeenNthCalledWith(2, '/students/student-id/nutrition/days/2026-10-13?timezone=Asia%2FHo_Chi_Minh');
  });

  it('logs a pinned catalog revision with explicit portion and preserves retry payload', async () => {
    const body = { timezone: 'Asia/Ho_Chi_Minh', consumedAt: '2026-10-16T12:00:00Z',
      mealType: 'LUNCH' as const, catalogFoodId: 'food-id', expectedRevisionId: 'revision-id',
      portionId: 'portion-id', quantityBasis: 'SERVING' as const, quantity: 1.25,
      commandKey: 'catalog-command' };
    await nutritionApi.catalogFoods();
    await nutritionApi.addCatalogItem('2026-10-16', body);
    await nutritionApi.addCatalogItem('2026-10-16', body);
    expect(request).toHaveBeenNthCalledWith(1, '/nutrition/catalog/foods');
    expect(request).toHaveBeenNthCalledWith(2, '/nutrition/days/2026-10-16/catalog-items',
      { method: 'POST', body });
    expect(request).toHaveBeenNthCalledWith(3, '/nutrition/days/2026-10-16/catalog-items',
      { method: 'POST', body });
  });
});
