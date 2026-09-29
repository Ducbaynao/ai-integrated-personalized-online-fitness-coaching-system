export type ExerciseDifficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED';

export const EXERCISE_FILTER_MAX_VALUES_PER_DIMENSION = 20;

export interface CatalogOption {
  code: string;
  name: string;
}

export interface MuscleGroupOption extends CatalogOption {
  parentCode: string | null;
}

export interface ExerciseCatalogItem {
  id: string;
  code: string;
  name: string;
  category: CatalogOption | null;
  description: string | null;
  difficulty: ExerciseDifficulty | null;
  movementPattern: string | null;
  unilateral: boolean;
  primaryMuscles: CatalogOption[];
  equipment: CatalogOption[];
  tags: CatalogOption[];
  variationCount: number;
  mediaAvailable: boolean;
}

export type ExerciseSummary = ExerciseCatalogItem;

export interface ExerciseCatalogPage {
  items: ExerciseCatalogItem[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

export type ExerciseMuscleInvolvement = 'PRIMARY' | 'SECONDARY' | 'STABILIZER';
export type ExerciseEquipmentRequirement = 'REQUIRED' | 'OPTIONAL' | 'ALTERNATIVE';
export type ExerciseMediaPurpose =
  | 'THUMBNAIL'
  | 'DEMO_IMAGE'
  | 'DEMO_VIDEO'
  | 'TECHNIQUE_GUIDE';

export interface ExerciseMuscle extends CatalogOption {
  involvement: ExerciseMuscleInvolvement;
}

export interface ExerciseEquipment extends CatalogOption {
  requirement: ExerciseEquipmentRequirement;
}

export interface ExerciseMediaReference {
  mediaId: string;
  purpose: ExerciseMediaPurpose;
  sortOrder: number;
  contentType: string | null;
  sizeBytes: number | null;
  available: boolean;
}

export interface ExerciseVariation {
  id: string;
  code: string;
  name: string;
  description: string | null;
  instructions: string | null;
  difficulty: ExerciseDifficulty | null;
  defaultVariation: boolean;
  muscles: ExerciseMuscle[];
  equipment: ExerciseEquipment[];
  media: ExerciseMediaReference[];
}

export type ExerciseGuidanceType =
  | 'COMMON_MISTAKE'
  | 'COACHING_CUE'
  | 'SAFETY_NOTE'
  | 'REGRESSION'
  | 'PROGRESSION';

export interface ExerciseGuidance {
  id: string;
  exerciseVariationId: string | null;
  type: ExerciseGuidanceType;
  title: string;
  description: string;
  correction: string | null;
  severity: string | null;
  sortOrder: number;
}

export interface ExerciseDetail {
  id: string;
  code: string;
  name: string;
  category: CatalogOption | null;
  description: string | null;
  instructions: string | null;
  difficulty: ExerciseDifficulty | null;
  movementPattern: string | null;
  unilateral: boolean;
  variations: ExerciseVariation[];
  tags: CatalogOption[];
  guidance: ExerciseGuidance[];
  mediaAvailable: boolean;
}

export interface ExerciseFilterMetadata {
  categories: CatalogOption[];
  muscleGroups: MuscleGroupOption[];
  equipment: CatalogOption[];
  tags: CatalogOption[];
  difficulties: ExerciseDifficulty[];
  movementPatterns: string[];
}

export interface ExerciseCatalogFilters {
  query?: string;
  categoryCodes?: readonly string[];
  muscleGroupCodes?: readonly string[];
  equipmentCodes?: readonly string[];
  tagCodes?: readonly string[];
  difficulties?: readonly ExerciseDifficulty[];
  movementPatterns?: readonly string[];
}

export interface ExerciseCatalogParams extends ExerciseCatalogFilters {
  page?: number;
  size?: number;
}

