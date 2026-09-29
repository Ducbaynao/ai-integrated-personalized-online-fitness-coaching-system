export type ExerciseLifecycleStatus = 'DRAFT' | 'ACTIVE' | 'ARCHIVED'
export type ExerciseDifficulty = 'BEGINNER' | 'INTERMEDIATE' | 'ADVANCED'

export interface AdminExerciseSummary {
  id: string
  code: string
  name: string
  categoryCode: string | null
  difficulty: ExerciseDifficulty | null
  movementPattern: string | null
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
    involvement: 'PRIMARY' | 'SECONDARY' | 'STABILIZER'
  }>
  equipment: Array<{
    equipmentCode: string
    requirement: 'REQUIRED' | 'OPTIONAL' | 'ALTERNATIVE'
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
