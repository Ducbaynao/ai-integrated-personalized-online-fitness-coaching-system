import { request } from '@/services/apiClient';

export type Nutrient = { code: string; total: number | null; knownSubtotal: number | null; knownItems: number; unknownItems: number };
export type PrivateFood = {
  id: string; studentId: string; name: string; servingLabel: string; source: 'USER_REPORTED'; quantificationStatus: 'DRAFT' | 'LOGGABLE';
  energyKcal: number | null; proteinG: number | null; carbohydrateG: number | null; fatG: number | null; version: number;
};
export type NutritionItem = {
  id: string; mealId: string; kind: 'LEGACY' | 'PRIVATE_SERVING' | 'DESCRIPTION_ONLY' | 'CATALOG_QUANTIFIED'; name: string;
  privateFoodId: string | null; servings: number | null; servingLabel: string | null;
  consumedAt: string; version: number; removed: boolean; nutrients: Nutrient[];
  catalogRevisionId: string | null; catalogPortionId: string | null;
  quantityBasis: 'SERVING' | 'GRAM' | 'MILLILITRE' | null;
};
export type CatalogPortion = { id: string; label: string; gramsPerServing: number | null;
  gramsPerMl: number | null; factorSource: string | null; mlFactorSource: string | null };
export type CatalogFood = { id: string; fdcId: number; dataType: 'Foundation'; sourceName: string;
  sourceRelease: string; sourceDate: string; status: 'DRAFT' | 'PUBLISHED' | 'RETIRED';
  aggregateVersion: number; revisionId: string; revisionNumber: number; name: string;
  energyKcalPer100g: number | null; proteinGPer100g: number | null;
  carbohydrateGPer100g: number | null; fatGPer100g: number | null;
  sourceRecordVersion: string; sourceUrl: string; portions: CatalogPortion[] };
export type CatalogItemBody = { timezone: string; consumedAt: string; mealType: ItemBody['mealType'];
  catalogFoodId: string; expectedRevisionId: string; portionId: string | null;
  quantityBasis: 'SERVING' | 'GRAM' | 'MILLILITRE'; quantity: number; commandKey: string };
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

export type NutritionGoalType = { id: number; code: string; name: string };
export type NutritionTargetNutrient = { id: number; code: string; name: string; defaultUnitId: number;
  unitCode: string; unitSymbol: string; dimension: string };
export type NutritionTargetSpec = { nutrientId: number; unitId: number; dayType: 'DEFAULT' | 'TRAINING_DAY' | 'REST_DAY'; targetAmount: number };
export type NutritionTargetVersion = { id: string; versionNumber: number; effectiveFrom: string; effectiveUntil: string | null;
  dateSemantics: 'LEGACY' | 'HALF_OPEN'; timezone: string | null; goalTypeId: number | null; targets: NutritionTargetSpec[] };
export type NutritionStrategyGoal = { id: string; studentId: string; title: string; status: string; aggregateVersion: number;
  timezone: string | null; versions: NutritionTargetVersion[] };
export type NutritionStrategyProposal = { id: string; studentId: string; trainerId: string; kind: 'NEW_GOAL' | 'CHANGE_TARGET';
  status: 'PENDING' | 'ACCEPTED' | 'REJECTED'; proposalVersion: number; goalId: string | null; baseVersionId: string | null;
  baseGoalVersion: number | null; title: string; goalTypeId: number; timezone: string; effectiveFrom: string; reason: string;
  targets: NutritionTargetSpec[]; createdAt: string; decidedAt: string | null; decidedGoalId: string | null; decidedGoalVersionId: string | null };
export type NutritionStrategyState = { goal: NutritionStrategyGoal | null; proposals: NutritionStrategyProposal[] };
export type NutritionResolvedTarget = { nutrientId: number; unitId: number; targetAmount: number;
  source: 'OVERRIDE' | 'DAY_TYPE' | 'DEFAULT' | 'CORRECTED'; sourceTargetId: string | null; sourceOverrideId: string | null;
  overrideVersion: number | null; originalTargetAmount: number | null; correctionVersion: number | null };
export type NutritionTargetDay = { date: string; timezone: string; frozen: boolean;
  dayType: 'DEFAULT' | 'TRAINING_DAY' | 'REST_DAY'; goalVersionId: string | null; targets: NutritionResolvedTarget[] };
export type FrozenTargetCorrection = { id: string; date: string; nutrientId: number; unitId: number;
  version: number; originalAmount: number; beforeAmount: number; afterAmount: number;
  reason: string; actorId: string; correctedAt: string };
export type NutritionChangeBody = { kind: 'NEW_GOAL' | 'CHANGE_TARGET'; goalId: string | null;
  expectedGoalVersion: number | null; baseVersionId: string | null; title: string; goalTypeId: number;
  timezone: string; effectiveFrom: string; targets: NutritionTargetSpec[]; reason: string | null;
  confirmed: boolean; commandKey: string };

export const nutritionApi = {
  catalogFoods: () => request<CatalogFood[]>('/nutrition/catalog/foods'),
  catalogFood: (id: string) => request<CatalogFood>(`/nutrition/catalog/foods/${id}`),
  addCatalogItem: (day: string, body: CatalogItemBody) => request<NutritionDay>(`/nutrition/days/${day}/catalog-items`, { method: 'POST', body }),
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
  goalTypes: () => request<NutritionGoalType[]>('/nutrition/strategy/goal-types'),
  targetNutrients: () => request<NutritionTargetNutrient[]>('/nutrition/strategy/target-nutrients'),
  strategy: () => request<NutritionStrategyState>('/nutrition/strategy/me'),
  trainerStrategy: (studentId: string) => request<NutritionStrategyState>(`/students/${studentId}/nutrition/strategy`),
  changeStrategy: (body: NutritionChangeBody) => request<NutritionStrategyGoal>('/nutrition/strategy/goals', { method: 'POST', body }),
  transitionGoal: (goalId: string, body: { action: 'PAUSE' | 'RESUME' | 'END'; expectedGoalVersion: number; reason: string; commandKey: string }) =>
    request<NutritionStrategyGoal>(`/nutrition/strategy/goals/${goalId}/transitions`, { method: 'POST', body }),
  targetDay: (day: string, timezone: string) => request<NutritionTargetDay>(`/nutrition/strategy/days/${day}?timezone=${encodeURIComponent(timezone)}`),
  frozenCorrections: (day: string) => request<FrozenTargetCorrection[]>(`/nutrition/strategy/days/${day}/frozen-target-corrections`),
  trainerFrozenCorrections: (studentId: string, day: string) => request<FrozenTargetCorrection[]>(
    `/students/${studentId}/nutrition/strategy/days/${day}/frozen-target-corrections`),
  correctFrozenTarget: (day: string, body: { timezone: string; nutrientId: number; targetAmount: number;
    expectedVersion: number; reason: string; commandKey: string }) => request<NutritionTargetDay>(
      `/nutrition/strategy/days/${day}/frozen-target-corrections`, { method: 'POST', body }),
  chooseDayType: (day: string, body: { timezone: string; dayType: 'REST_DAY' | 'DEFAULT'; commandKey: string }) =>
    request<NutritionTargetDay>(`/nutrition/strategy/days/${day}/day-type`, { method: 'POST', body }),
  overrideTarget: (day: string, body: { timezone: string; goalVersionId: string; nutrientId: number; unitId: number;
    targetAmount: number; reason: string; expectedOverrideVersion: number | null; commandKey: string }) =>
    request<NutritionTargetDay>(`/nutrition/strategy/days/${day}/overrides`, { method: 'POST', body }),
  strategyProposals: () => request<NutritionStrategyProposal[]>('/nutrition/strategy/proposals/me'),
  trainerStrategyProposals: (studentId: string) => request<NutritionStrategyProposal[]>(`/students/${studentId}/nutrition/strategy/proposals`),
  strategyProposal: (id: string) => request<NutritionStrategyProposal>(`/nutrition/strategy/proposals/${id}`),
  proposeStrategy: (studentId: string, body: NutritionChangeBody) => request<NutritionStrategyProposal>(
    `/students/${studentId}/nutrition/strategy/proposals`, { method: 'POST', body }),
  decideStrategy: (id: string, body: { decision: 'ACCEPT' | 'REJECT'; expectedProposalVersion: number; commandKey: string }) =>
    request<NutritionStrategyProposal>(`/nutrition/strategy/proposals/${id}/decisions`, { method: 'POST', body }),
};
