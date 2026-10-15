import { request } from '@/services/apiClient';

export type Nutrient = { code: string; total: number | null; knownSubtotal: number | null; knownItems: number; unknownItems: number };
export type PrivateFood = {
  id: string; studentId: string; name: string; servingLabel: string; source: 'USER_REPORTED'; quantificationStatus: 'DRAFT' | 'LOGGABLE';
  energyKcal: number | null; proteinG: number | null; carbohydrateG: number | null; fatG: number | null; version: number;
};
export type NutritionItem = {
  id: string; mealId: string; kind: 'LEGACY' | 'PRIVATE_SERVING' | 'DESCRIPTION_ONLY'; name: string;
  privateFoodId: string | null; servings: number | null; servingLabel: string | null;
  consumedAt: string; version: number; removed: boolean; nutrients: Nutrient[];
};
export type NutritionDay = {
  studentId: string; date: string; timezone: string | null; completeness: 'NOT_LOGGED' | 'PARTIAL' | 'COMPLETE';
  version: number; noIntakeConfirmed: boolean; nutrients: Nutrient[]; items: NutritionItem[];
};
export type FoodBody = Pick<PrivateFood, 'name' | 'servingLabel' | 'energyKcal' | 'proteinG' | 'carbohydrateG' | 'fatG'> & {
  expectedVersion?: number; commandKey: string;
};
export type ItemBody = {
  timezone: string; consumedAt: string; mealType: 'BREAKFAST' | 'LUNCH' | 'DINNER' | 'SNACK' | 'OTHER';
  description: string | null; privateFoodId: string | null; servings: number | null; commandKey: string;
};
export type CorrectionBody = { expectedVersion: number; description: string | null; privateFoodId: string | null;
  servings: number | null; remove: boolean; commandKey: string };
export type ConfirmBody = { timezone: string; expectedDayVersion: number; noIntakeConfirmed: boolean; commandKey: string };

export const nutritionApi = {
  foods: () => request<PrivateFood[]>('/nutrition/private-foods'),
  createFood: (body: FoodBody) => request<PrivateFood>('/nutrition/private-foods', { method: 'POST', body }),
  updateFood: (id: string, body: FoodBody) => request<PrivateFood>(`/nutrition/private-foods/${id}`, { method: 'PUT', body }),
  day: (day: string, timezone: string) => request<NutritionDay>(`/nutrition/days/${day}?timezone=${encodeURIComponent(timezone)}`),
  trainerDay: (studentId: string, day: string, timezone: string) =>
    request<NutritionDay>(`/students/${studentId}/nutrition/days/${day}?timezone=${encodeURIComponent(timezone)}`),
  addItem: (day: string, body: ItemBody) => request<NutritionDay>(`/nutrition/days/${day}/items`, { method: 'POST', body }),
  correct: (itemId: string, body: CorrectionBody) => request<NutritionDay>(`/nutrition/items/${itemId}/correct`, { method: 'POST', body }),
  corrections: (itemId: string) => request<{ id: string; fromVersion: number; toVersion: number; correctedAt: string }[]>(`/nutrition/items/${itemId}/corrections`),
  confirm: (day: string, body: ConfirmBody) => request<NutritionDay>(`/nutrition/days/${day}/confirm`, { method: 'POST', body }),
};
