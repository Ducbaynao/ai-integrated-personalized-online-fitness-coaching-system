import type {
  AdminExerciseDetail,
  AdminExerciseFormMetadata,
  AdminExercisePage,
  AdminExerciseSummary,
  CanonicalReplacementPreview,
} from '../types/exercise.ts'

export function createExerciseSummary(
  overrides: Partial<AdminExerciseSummary> = {},
): AdminExerciseSummary {
  return {
    id: '2c5f9430-c360-4b32-b70a-d6f92b76bfd4',
    code: 'BARBELL_SQUAT',
    name: 'Barbell Squat',
    categoryCode: 'STRENGTH',
    difficulty: 'INTERMEDIATE',
    movementPattern: 'SQUAT',
    status: 'ACTIVE',
    version: 2,
    canonicalReplacementId: null,
    variationCount: 1,
    updatedAt: '2026-09-29T08:30:00Z',
    ...overrides,
  }
}

export function createCanonicalReplacementPreview(
  overrides: Partial<CanonicalReplacementPreview> = {},
): CanonicalReplacementPreview {
  return {
    sourceExercise: {
      id: '2c5f9430-c360-4b32-b70a-d6f92b76bfd4',
      code: 'BARBELL_SQUAT',
      name: 'Barbell Squat',
      status: 'ARCHIVED',
    },
    expectedVersion: 10,
    currentTarget: null,
    usageImpact: { availability: 'NOT_AVAILABLE', count: null },
    ...overrides,
  }
}

export function createExerciseMetadata(
  overrides: Partial<AdminExerciseFormMetadata> = {},
): AdminExerciseFormMetadata {
  return {
    categories: [{ code: 'STRENGTH', name: 'Strength' }],
    muscleGroups: [{ code: 'QUADRICEPS', name: 'Quadriceps', parentCode: null }],
    equipment: [{ code: 'BARBELL', name: 'Barbell' }],
    tags: [{ code: 'COMPOUND', name: 'Compound' }],
    difficulties: ['BEGINNER', 'INTERMEDIATE', 'ADVANCED'],
    movementPatterns: [{ code: 'SQUAT', name: 'Squat' }],
    ...overrides,
  }
}

export function createExercisePage(
  overrides: Partial<AdminExercisePage> = {},
): AdminExercisePage {
  return {
    items: [createExerciseSummary()],
    page: 0,
    size: 20,
    totalItems: 1,
    totalPages: 1,
    ...overrides,
  }
}

export function createExerciseDetail(
  overrides: Partial<AdminExerciseDetail> = {},
): AdminExerciseDetail {
  return {
    ...createExerciseSummary(),
    description: 'Builds lower-body strength.',
    instructions: 'Keep your chest tall.',
    unilateral: false,
    tagCodes: ['COMPOUND'],
    variations: [
      {
        id: '3ed22f37-6c48-4781-9493-faff071269a1',
        code: 'HIGH_BAR',
        name: 'High-bar Squat',
        description: 'Upright squat variation.',
        instructions: 'Place the bar above the rear deltoids.',
        difficulty: 'INTERMEDIATE',
        defaultVariation: true,
        active: true,
        muscles: [{ muscleGroupCode: 'QUADRICEPS', involvement: 'PRIMARY' }],
        equipment: [{ equipmentCode: 'BARBELL', requirement: 'REQUIRED' }],
      },
    ],
    createdAt: '2026-09-20T08:30:00Z',
    ...overrides,
  }
}
