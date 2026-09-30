import type {
  AdminExerciseDetail,
  AdminExerciseDraftRequest,
  EquipmentRequirement,
  ExerciseDifficulty,
  ExerciseMovementPattern,
  MuscleInvolvement,
} from '../../types/exercise.ts'
import type { ApiFieldError } from '../../services/apiClient.ts'

export const MAX_COLLECTION_ITEMS = 100

let clientKeySequence = 0

function createClientKey(prefix: string): string {
  clientKeySequence += 1
  return `${prefix}-${clientKeySequence}`
}

export interface ExerciseMuscleFormValue {
  clientKey: string
  muscleGroupCode: string
  involvement: MuscleInvolvement
}

export interface ExerciseEquipmentFormValue {
  clientKey: string
  equipmentCode: string
  requirement: EquipmentRequirement
}

export interface ExerciseVariationFormValue {
  clientKey: string
  code: string
  name: string
  description: string
  instructions: string
  difficulty: ExerciseDifficulty | ''
  defaultVariation: boolean
  active: boolean
  muscles: ExerciseMuscleFormValue[]
  equipment: ExerciseEquipmentFormValue[]
}

export interface ExerciseDraftFormValue {
  code: string
  name: string
  categoryCode: string
  description: string
  instructions: string
  difficulty: ExerciseDifficulty | ''
  movementPattern: string
  unilateral: boolean
  tagCodes: string[]
  variations: ExerciseVariationFormValue[]
}

export type ExerciseDraftFormErrors = Record<string, string>

export function createEmptyExerciseDraft(): ExerciseDraftFormValue {
  return {
    code: '',
    name: '',
    categoryCode: '',
    description: '',
    instructions: '',
    difficulty: '',
    movementPattern: '',
    unilateral: false,
    tagCodes: [],
    variations: [],
  }
}

export function createEmptyVariation(): ExerciseVariationFormValue {
  return {
    clientKey: createClientKey('variation'),
    code: '',
    name: '',
    description: '',
    instructions: '',
    difficulty: '',
    defaultVariation: false,
    active: true,
    muscles: [],
    equipment: [],
  }
}

export function createEmptyMuscle(): ExerciseMuscleFormValue {
  return {
    clientKey: createClientKey('muscle'),
    muscleGroupCode: '',
    involvement: 'PRIMARY',
  }
}

export function createEmptyEquipment(): ExerciseEquipmentFormValue {
  return {
    clientKey: createClientKey('equipment'),
    equipmentCode: '',
    requirement: 'REQUIRED',
  }
}

export function exerciseDetailToFormValue(exercise: AdminExerciseDetail): ExerciseDraftFormValue {
  return {
    code: exercise.code,
    name: exercise.name,
    categoryCode: exercise.categoryCode ?? '',
    description: exercise.description ?? '',
    instructions: exercise.instructions ?? '',
    difficulty: exercise.difficulty ?? '',
    movementPattern: exercise.movementPattern ?? '',
    unilateral: exercise.unilateral,
    tagCodes: [...exercise.tagCodes],
    variations: exercise.variations.map((variation) => ({
      clientKey: createClientKey('variation'),
      code: variation.code,
      name: variation.name,
      description: variation.description ?? '',
      instructions: variation.instructions ?? '',
      difficulty: variation.difficulty ?? '',
      defaultVariation: variation.defaultVariation,
      active: variation.active,
      muscles: variation.muscles.map((muscle) => ({
        clientKey: createClientKey('muscle'),
        ...muscle,
      })),
      equipment: variation.equipment.map((equipment) => ({
        clientKey: createClientKey('equipment'),
        ...equipment,
      })),
    })),
  }
}

function optionalText(value: string): string | null {
  return value === '' ? null : value
}

export function toAdminExerciseDraftRequest(
  value: ExerciseDraftFormValue,
): AdminExerciseDraftRequest {
  return {
    code: value.code,
    name: value.name,
    categoryCode: optionalText(value.categoryCode),
    description: optionalText(value.description),
    instructions: optionalText(value.instructions),
    difficulty: value.difficulty || null,
    movementPattern: optionalText(value.movementPattern) as ExerciseMovementPattern | null,
    unilateral: value.unilateral,
    tagCodes: [...value.tagCodes],
    variations: value.variations.map((variation) => ({
      code: variation.code,
      name: variation.name,
      description: optionalText(variation.description),
      instructions: optionalText(variation.instructions),
      difficulty: variation.difficulty || null,
      defaultVariation: variation.defaultVariation,
      active: variation.active,
      muscles: variation.muscles.map(({ muscleGroupCode, involvement }) => ({
        muscleGroupCode,
        involvement,
      })),
      equipment: variation.equipment.map(({ equipmentCode, requirement }) => ({
        equipmentCode,
        requirement,
      })),
    })),
  }
}

function required(errors: ExerciseDraftFormErrors, path: string, value: string, label: string) {
  if (value.trim().length === 0) errors[path] = `${label} là bắt buộc.`
}

function maxLength(
  errors: ExerciseDraftFormErrors,
  path: string,
  value: string,
  maximum: number,
  label: string,
) {
  if (value.length > maximum) errors[path] = `${label} không được vượt quá ${maximum} ký tự.`
}

function duplicateIndexes(values: string[]): Set<number> {
  const seen = new Map<string, number>()
  const duplicates = new Set<number>()
  values.forEach((value, index) => {
    const normalized = value.trim().toUpperCase()
    if (!normalized) return
    const firstIndex = seen.get(normalized)
    if (firstIndex !== undefined) {
      duplicates.add(firstIndex)
      duplicates.add(index)
    } else {
      seen.set(normalized, index)
    }
  })
  return duplicates
}

export function validateExerciseDraft(value: ExerciseDraftFormValue): ExerciseDraftFormErrors {
  const errors: ExerciseDraftFormErrors = {}
  required(errors, 'code', value.code, 'Mã bài tập')
  required(errors, 'name', value.name, 'Tên bài tập')
  maxLength(errors, 'code', value.code, 100, 'Mã bài tập')
  maxLength(errors, 'name', value.name, 180, 'Tên bài tập')
  maxLength(errors, 'categoryCode', value.categoryCode, 60, 'Mã danh mục')
  maxLength(errors, 'movementPattern', value.movementPattern, 60, 'Kiểu vận động')

  if (value.tagCodes.length > MAX_COLLECTION_ITEMS) {
    errors.tagCodes = `Không được chọn quá ${MAX_COLLECTION_ITEMS} nhãn.`
  }
  if (new Set(value.tagCodes).size !== value.tagCodes.length) {
    errors.tagCodes = 'Mỗi nhãn chỉ được chọn một lần.'
  }
  if (value.variations.length > MAX_COLLECTION_ITEMS) {
    errors.variations = `Không được thêm quá ${MAX_COLLECTION_ITEMS} biến thể.`
  }

  const duplicateVariationCodes = duplicateIndexes(value.variations.map((variation) => variation.code))
  value.variations.forEach((variation, variationIndex) => {
    const prefix = `variations.${variationIndex}`
    required(errors, `${prefix}.code`, variation.code, 'Mã biến thể')
    required(errors, `${prefix}.name`, variation.name, 'Tên biến thể')
    maxLength(errors, `${prefix}.code`, variation.code, 120, 'Mã biến thể')
    maxLength(errors, `${prefix}.name`, variation.name, 180, 'Tên biến thể')
    if (duplicateVariationCodes.has(variationIndex)) {
      errors[`${prefix}.code`] = 'Mã biến thể không được trùng trong cùng bài tập.'
    }
    if (variation.muscles.length > MAX_COLLECTION_ITEMS) {
      errors[`${prefix}.muscles`] = `Không được thêm quá ${MAX_COLLECTION_ITEMS} nhóm cơ.`
    }
    if (variation.equipment.length > MAX_COLLECTION_ITEMS) {
      errors[`${prefix}.equipment`] = `Không được thêm quá ${MAX_COLLECTION_ITEMS} thiết bị.`
    }

    const duplicateMuscles = duplicateIndexes(
      variation.muscles.map((muscle) => muscle.muscleGroupCode),
    )
    variation.muscles.forEach((muscle, muscleIndex) => {
      const path = `${prefix}.muscles.${muscleIndex}.muscleGroupCode`
      required(errors, path, muscle.muscleGroupCode, 'Nhóm cơ')
      maxLength(errors, path, muscle.muscleGroupCode, 60, 'Mã nhóm cơ')
      if (duplicateMuscles.has(muscleIndex)) {
        errors[path] = 'Nhóm cơ không được trùng trong cùng biến thể.'
      }
    })

    const duplicateEquipment = duplicateIndexes(
      variation.equipment.map((equipment) => equipment.equipmentCode),
    )
    variation.equipment.forEach((equipment, equipmentIndex) => {
      const path = `${prefix}.equipment.${equipmentIndex}.equipmentCode`
      required(errors, path, equipment.equipmentCode, 'Thiết bị')
      maxLength(errors, path, equipment.equipmentCode, 60, 'Mã thiết bị')
      if (duplicateEquipment.has(equipmentIndex)) {
        errors[path] = 'Thiết bị không được trùng trong cùng biến thể.'
      }
    })
  })

  return errors
}

const BACKEND_FIELD_ALIASES: Record<string, string> = {
  category: 'categoryCode',
  movementPattern: 'movementPattern',
  tags: 'tagCodes',
}

export function mapApiFieldErrors(fieldErrors: ApiFieldError[]): ExerciseDraftFormErrors {
  return fieldErrors.reduce<ExerciseDraftFormErrors>((errors, fieldError) => {
    const path = fieldError.field
      .replaceAll('[', '.')
      .replaceAll(']', '')
      .replace(/^exercise\./, '')
    const mappedPath = BACKEND_FIELD_ALIASES[path] ?? path
    if (mappedPath === 'code') {
      errors.code = 'Mã bài tập không hợp lệ hoặc đã được sử dụng.'
    } else if (mappedPath in BACKEND_FIELD_ALIASES || /^(name|categoryCode|movementPattern|tagCodes|variations)(\.|$)/.test(mappedPath)) {
      errors[mappedPath] = 'Giá trị này không hợp lệ. Vui lòng kiểm tra lại.'
    }
    return errors
  }, {})
}
