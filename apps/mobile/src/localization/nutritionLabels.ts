/**
 * Display names for system nutrients seeded by Flyway V39 (English `name`: Energy, Protein, Carbohydrate,
 * Fat). Wording matches the existing Nutrition UI (NutritionScreen.nutrientLabel, FoodAnalysisScreen
 * summaries) but without a unit, because callers append the catalog unit symbol themselves.
 * Any other nutrient keeps its server/catalog name; a missing name gets a neutral fallback.
 */
const SYSTEM_NUTRIENT_NAMES: Record<string, string> = {
  B07_ENERGY_KCAL: 'Năng lượng',
  B07_PROTEIN_G: 'Protein',
  B07_CARBOHYDRATE_G: 'Carb',
  B07_FAT_G: 'Chất béo',
};

export const UNNAMED_NUTRIENT_LABEL = 'Chất dinh dưỡng chưa có tên';

export function nutrientName(
  nutrient?: { code?: string | null; name?: string | null } | null,
  fallback: string = UNNAMED_NUTRIENT_LABEL,
): string {
  const code = nutrient?.code;
  if (code && Object.prototype.hasOwnProperty.call(SYSTEM_NUTRIENT_NAMES, code)) {
    return SYSTEM_NUTRIENT_NAMES[code];
  }
  const name = nutrient?.name?.trim();
  return name ? name : fallback;
}
