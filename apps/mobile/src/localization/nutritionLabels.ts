/**
 * Display names for system nutrients seeded by Flyway V39 (English `name`: Energy, Protein, Carbohydrate,
 * Fat). Wording matches the existing Nutrition UI (NutritionScreen.nutrientLabel, FoodAnalysisScreen
 * summaries) but without a unit, because callers append the catalog unit symbol themselves.
 * Any other nutrient keeps its server/catalog name; a missing name gets a neutral fallback.
 * Callers that only have a nutrient `code` use `nutrientCodeLabel`.
 */
const SYSTEM_NUTRIENT_NAMES: Record<string, string> = {
  B07_ENERGY_KCAL: 'Năng lượng',
  B07_PROTEIN_G: 'Protein',
  B07_CARBOHYDRATE_G: 'Carb',
  B07_FAT_G: 'Chất béo',
};

/** Catalog unit symbols of the same system nutrients (V39). Only used for the day-summary label. */
const SYSTEM_NUTRIENT_UNITS: Record<string, string> = {
  B07_ENERGY_KCAL: 'kcal',
  B07_PROTEIN_G: 'g',
  B07_CARBOHYDRATE_G: 'g',
  B07_FAT_G: 'g',
};

export const UNNAMED_NUTRIENT_LABEL = 'Chất dinh dưỡng chưa có tên';

/**
 * Neutral label for a nutrient code this client does not recognise and that carries no catalog name
 * (the day summaries only expose `code`). The raw technical code is never shown, the nutrient is not
 * guessed or translated, and its value/unit/null/zero stay with the caller. Rows are keyed by code, so
 * several unrecognised nutrients stay separate rows that merely share this label.
 */
export const OTHER_NUTRIENT_LABEL = 'Chất dinh dưỡng khác';

function ownEntry(table: Record<string, string>, code?: string | null): string | null {
  return code && Object.prototype.hasOwnProperty.call(table, code) ? table[code] : null;
}

export function nutrientName(
  nutrient?: { code?: string | null; name?: string | null } | null,
  fallback: string = UNNAMED_NUTRIENT_LABEL,
): string {
  const known = ownEntry(SYSTEM_NUTRIENT_NAMES, nutrient?.code);
  if (known) return known;
  const name = nutrient?.name?.trim();
  return name ? name : fallback;
}

/**
 * Label for a nutrient row identified by `code` only (Student/Trainer day summaries): the agreed system
 * wording with its catalog unit, or the neutral "other nutrient" label for any code not recognised.
 */
export function nutrientCodeLabel(code?: string | null): string {
  const known = ownEntry(SYSTEM_NUTRIENT_NAMES, code);
  const unit = ownEntry(SYSTEM_NUTRIENT_UNITS, code);
  return known && unit ? `${known} (${unit})` : OTHER_NUTRIENT_LABEL;
}
