export type ExerciseLifecycleStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type ExerciseDifficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'
export type ExerciseMovementPattern =
  | 'SQUAT'
  | 'HINGE'
  | 'LUNGE'
  | 'PUSH'
  | 'PULL'
  | 'CARRY'
  | 'ROTATION'
  | 'CORE_STABILITY'
  | 'LOCOMOTION'
  | 'ISOLATION'
  | 'MOBILITY'
  | 'BALANCE'
export type MuscleInvolvement = 'PRIMARY' | 'SECONDARY' | 'STABILIZER'
export type EquipmentRequirement = 'REQUIRED' | 'OPTIONAL' | 'ALTERNATIVE'

export interface CatalogOption {
  code: string
  name: string
}

export interface MuscleGroupOption extends CatalogOption {
  parentCode: string | null
}

export interface AdminExerciseFormMetadata {
  categories: CatalogOption[]
  muscleGroups: MuscleGroupOption[]
  equipment: CatalogOption[]
  tags: CatalogOption[]
  difficulties: ExerciseDifficulty[]
  movementPatterns: CatalogOption[]
}

export interface AdminExerciseSummary {
  id: string
  code: string
  name: string
  categoryCode: string | null
  difficulty: ExerciseDifficulty | null
  movementPattern: ExerciseMovementPattern | null
  status: ExerciseLifecycleStatus
  version: number
  canonicalReplacementId: string | null
  variationCount: number
  updatedAt: string
}

export interface AdminExercisePage {
  items: AdminExerciseSummary[]
  page: number
  size: number
  totalItems: number
  totalPages: number
}

export interface AdminExerciseVariation {
  id: string
  code: string
  name: string
  description: string | null
  instructions: string | null
  difficulty: ExerciseDifficulty | null
  defaultVariation: boolean
  active: boolean
  muscles: Array<{
    muscleGroupCode: string
    involvement: MuscleInvolvement
  }>
  equipment: Array<{
    equipmentCode: string
    requirement: EquipmentRequirement
  }>
}

export interface AdminExerciseDetail {
  id: string
  code: string
  name: string
  categoryCode: string | null
  description: string | null
  instructions: string | null
  difficulty: ExerciseDifficulty | null
  movementPattern: string | null
  unilateral: boolean
  status: ExerciseLifecycleStatus
  version: number
  canonicalReplacementId: string | null
  tagCodes: string[]
  variations: AdminExerciseVariation[]
  createdAt: string
  updatedAt: string
}

export interface ExerciseSearchParams {
  query?: string
  status?: ExerciseLifecycleStatus
  page: number
  size: number
}

export interface AdminExerciseMuscleInput {
  muscleGroupCode: string
  involvement: MuscleInvolvement
}

export interface AdminExerciseEquipmentInput {
  equipmentCode: string
  requirement: EquipmentRequirement
}

export interface AdminExerciseVariationInput {
  code: string
  name: string
  description: string | null
  instructions: string | null
  difficulty: ExerciseDifficulty | null
  defaultVariation: boolean
  active: boolean
  muscles: AdminExerciseMuscleInput[]
  equipment: AdminExerciseEquipmentInput[]
}

export interface AdminExerciseDraftRequest {
  code: string
  name: string
  categoryCode: string | null
  description: string | null
  instructions: string | null
  difficulty: ExerciseDifficulty | null
  movementPattern: ExerciseMovementPattern | null
  unilateral: boolean
  tagCodes: string[]
  variations: AdminExerciseVariationInput[]
}

export interface UpdateAdminExerciseRequest {
  expectedVersion: number
  exercise: AdminExerciseDraftRequest
}
