import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { createExerciseMetadata } from '../../test/exerciseTestData.ts'
import { ExerciseDraftForm } from './ExerciseDraftForm.tsx'
import {
  MAX_COLLECTION_ITEMS,
  createEmptyExerciseDraft,
  createEmptyEquipment,
  createEmptyMuscle,
  createEmptyVariation,
  toAdminExerciseDraftRequest,
  validateExerciseDraft,
} from './exerciseDraftForm.ts'

describe('exercise draft form foundation', () => {
  it('renders semantic Vietnamese fields and API metadata names unchanged', () => {
    render(
      <ExerciseDraftForm
        mode="create"
        value={createEmptyExerciseDraft()}
        metadata={createExerciseMetadata()}
        busy={false}
        onChange={vi.fn()}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    )

    expect(screen.getByRole('form', { name: 'Biểu mẫu tạo bài tập' })).toBeInTheDocument()
    expect(screen.getByRole('group', { name: 'Thông tin chung' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Strength' })).toBeInTheDocument()
    expect(screen.getByRole('option', { name: 'Squat' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Tạo bản nháp' })).toBeInTheDocument()
  })

  it('focuses a safe Vietnamese error summary and links invalid fields', async () => {
    const user = userEvent.setup()
    render(
      <ExerciseDraftForm
        mode="create"
        value={createEmptyExerciseDraft()}
        metadata={createExerciseMetadata()}
        busy={false}
        onChange={vi.fn()}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    )

    await user.click(screen.getByRole('button', { name: 'Tạo bản nháp' }))

    const summary = screen.getByRole('alert')
    expect(summary).toHaveFocus()
    expect(screen.getByLabelText(/Mã bài tập/)).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByLabelText(/Tên bài tập/)).toHaveAttribute('aria-describedby', 'exercise-name-error')
  })

  it('uses stable client keys but excludes them from the API payload', () => {
    const value = createEmptyExerciseDraft()
    const variation = createEmptyVariation()
    variation.code = 'HIGH_BAR'
    variation.name = 'High-bar Squat'
    variation.muscles = [{ ...createEmptyMuscle(), muscleGroupCode: 'QUADRICEPS' }]
    variation.equipment = [{ ...createEmptyEquipment(), equipmentCode: 'BARBELL' }]
    value.code = 'BARBELL_SQUAT'
    value.name = 'Barbell Squat'
    value.variations = [variation]

    const payload = toAdminExerciseDraftRequest(value)

    expect(JSON.stringify(payload)).not.toContain('clientKey')
    expect(payload.variations[0].name).toBe('High-bar Squat')
    expect(payload.variations[0].muscles).toEqual([
      { muscleGroupCode: 'QUADRICEPS', involvement: 'PRIMARY' },
    ])
  })

  it('enforces collection limits and uniqueness without truncating values', () => {
    const value = createEmptyExerciseDraft()
    value.code = 'DRAFT'
    value.name = 'Draft'
    value.tagCodes = Array.from({ length: MAX_COLLECTION_ITEMS + 1 }, (_, index) => `TAG_${index}`)
    value.variations = [createEmptyVariation(), createEmptyVariation()]
    value.variations[0].code = 'DUPLICATE'
    value.variations[0].name = 'First'
    value.variations[1].code = 'duplicate'
    value.variations[1].name = 'Second'

    const errors = validateExerciseDraft(value)

    expect(errors.tagCodes).toContain('100')
    expect(errors['variations.0.code']).toContain('không được trùng')
    expect(errors['variations.1.code']).toContain('không được trùng')
    expect(toAdminExerciseDraftRequest(value).tagCodes).toHaveLength(101)
  })

  it('shows current unavailable metadata without silently deleting it', () => {
    const value = createEmptyExerciseDraft()
    value.code = 'LEGACY_DRAFT'
    value.name = 'Legacy Draft'
    value.movementPattern = 'RETIRED_PATTERN'
    value.tagCodes = ['RETIRED_TAG']

    render(
      <ExerciseDraftForm
        mode="edit"
        value={value}
        metadata={createExerciseMetadata()}
        busy={false}
        onChange={vi.fn()}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    )

    expect(screen.getByRole('option', { name: 'RETIRED_PATTERN — Không còn khả dụng' })).toBeDisabled()
    expect(screen.getByText('RETIRED_TAG — Không còn khả dụng')).toBeInTheDocument()
    expect(toAdminExerciseDraftRequest(value).movementPattern).toBe('RETIRED_PATTERN')
    expect(toAdminExerciseDraftRequest(value).tagCodes).toEqual(['RETIRED_TAG'])
  })

  it('disables submit while a mutation is running', () => {
    render(
      <ExerciseDraftForm
        mode="edit"
        value={{ ...createEmptyExerciseDraft(), code: 'DRAFT', name: 'Draft' }}
        metadata={createExerciseMetadata()}
        busy
        onChange={vi.fn()}
        onSubmit={vi.fn()}
        onCancel={vi.fn()}
      />,
    )

    expect(screen.getByRole('button', { name: 'Đang lưu…' })).toBeDisabled()
    expect(screen.getByRole('form')).toHaveAttribute('aria-busy', 'true')
  })

  it('adds and removes nested collections with accessible item actions', async () => {
    const user = userEvent.setup()
    function Harness() {
      const [value, setValue] = useState(() => ({
        ...createEmptyExerciseDraft(),
        code: 'DRAFT',
        name: 'Draft',
      }))
      return (
        <ExerciseDraftForm
          mode="edit"
          value={value}
          metadata={createExerciseMetadata()}
          busy={false}
          onChange={setValue}
          onSubmit={vi.fn()}
          onCancel={vi.fn()}
        />
      )
    }
    render(<Harness />)

    await user.click(screen.getByRole('button', { name: 'Thêm biến thể' }))
    expect(screen.getByRole('group', { name: 'Biến thể 1' })).toBeInTheDocument()
    await user.type(screen.getByLabelText('Mã biến thể *'), 'STANDARD')
    await user.type(screen.getByLabelText('Tên biến thể *'), 'Standard')
    await user.click(screen.getByRole('button', { name: 'Thêm nhóm cơ' }))
    await user.click(screen.getByRole('button', { name: 'Thêm thiết bị' }))
    expect(screen.getByRole('button', { name: 'Xóa nhóm cơ 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xóa thiết bị 1' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Xóa biến thể Standard' })).toBeInTheDocument()
  })
})
