import { useEffect, useMemo, useRef, useState } from 'react'
import type { FormEvent, ReactNode, SelectHTMLAttributes } from 'react'
import type { AdminExerciseFormMetadata, CatalogOption } from '../../types/exercise.ts'
import {
  MAX_COLLECTION_ITEMS,
  createEmptyEquipment,
  createEmptyMuscle,
  createEmptyVariation,
  toAdminExerciseDraftRequest,
  validateExerciseDraft,
} from './exerciseDraftForm.ts'
import type {
  ExerciseDraftFormErrors,
  ExerciseDraftFormValue,
  ExerciseVariationFormValue,
} from './exerciseDraftForm.ts'
import { difficultyLabels } from './exercisePresentation.ts'

interface ExerciseDraftFormProps {
  mode: 'create' | 'edit'
  value: ExerciseDraftFormValue
  metadata: AdminExerciseFormMetadata
  busy: boolean
  locked?: boolean
  serverErrors?: ExerciseDraftFormErrors
  formError?: string
  onChange: (value: ExerciseDraftFormValue) => void
  onSubmit: (value: ReturnType<typeof toAdminExerciseDraftRequest>) => void
  onCancel: () => void
}

export function ExerciseDraftForm({
  mode,
  value,
  metadata,
  busy,
  locked = false,
  serverErrors = {},
  formError,
  onChange,
  onSubmit,
  onCancel,
}: ExerciseDraftFormProps) {
  const [clientErrors, setClientErrors] = useState<ExerciseDraftFormErrors>({})
  const errorSummaryRef = useRef<HTMLDivElement>(null)
  const errors = useMemo(
    () => ({ ...clientErrors, ...serverErrors }),
    [clientErrors, serverErrors],
  )
  const errorMessages = [...new Set(Object.values(errors))]

  useEffect(() => {
    if (formError || errorMessages.length > 0) errorSummaryRef.current?.focus()
  }, [formError, errorMessages.length])

  const update = <K extends keyof ExerciseDraftFormValue>(
    field: K,
    nextValue: ExerciseDraftFormValue[K],
  ) => {
    setClientErrors((current) => {
      const next = { ...current }
      delete next[field]
      return next
    })
    onChange({ ...value, [field]: nextValue })
  }

  const updateVariation = (index: number, variation: ExerciseVariationFormValue) => {
    const variations = [...value.variations]
    variations[index] = variation
    onChange({ ...value, variations })
  }

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    if (busy || locked) return
    const nextErrors = validateExerciseDraft(value)
    setClientErrors(nextErrors)
    if (Object.keys(nextErrors).length > 0) return
    onSubmit(toAdminExerciseDraftRequest(value))
  }

  return (
    <form
      className="exercise-form page-stack"
      aria-label={mode === 'create' ? 'Biểu mẫu tạo bài tập' : 'Biểu mẫu chỉnh sửa bài tập'}
      onSubmit={handleSubmit}
      noValidate
      aria-busy={busy}
    >
      {formError || errorMessages.length > 0 ? (
        <div className="form-error-summary" role="alert" tabIndex={-1} ref={errorSummaryRef}>
          <h2>Chưa thể lưu bài tập</h2>
          {formError ? <p>{formError}</p> : null}
          {errorMessages.length > 0 ? (
            <ul>{errorMessages.map((message) => <li key={message}>{message}</li>)}</ul>
          ) : null}
        </div>
      ) : null}

      <fieldset className="surface form-section" disabled={busy || locked}>
        <legend>Thông tin chung</legend>
        <div className="form-grid">
          <FormField label="Mã bài tập" required error={errors.code} fieldId="exercise-code">
            <input
              id="exercise-code"
              value={value.code}
              maxLength={100}
              aria-invalid={Boolean(errors.code)}
              aria-describedby={errors.code ? 'exercise-code-error' : undefined}
              onChange={(event) => update('code', event.target.value)}
            />
          </FormField>
          <FormField label="Tên bài tập" required error={errors.name} fieldId="exercise-name">
            <input
              id="exercise-name"
              value={value.name}
              maxLength={180}
              aria-invalid={Boolean(errors.name)}
              aria-describedby={errors.name ? 'exercise-name-error' : undefined}
              onChange={(event) => update('name', event.target.value)}
            />
          </FormField>
          <FormField label="Danh mục" error={errors.categoryCode} fieldId="exercise-category">
            <MetadataSelect
              id="exercise-category"
              value={value.categoryCode}
              options={metadata.categories}
              emptyLabel="Không chọn"
              aria-invalid={Boolean(errors.categoryCode)}
              aria-describedby={errors.categoryCode ? 'exercise-category-error' : undefined}
              onValueChange={(next) => update('categoryCode', next)}
            />
          </FormField>
          <FormField label="Độ khó" error={errors.difficulty} fieldId="exercise-difficulty">
            <select
              id="exercise-difficulty"
              value={value.difficulty}
              aria-invalid={Boolean(errors.difficulty)}
              aria-describedby={errors.difficulty ? 'exercise-difficulty-error' : undefined}
              onChange={(event) => update('difficulty', event.target.value as ExerciseDraftFormValue['difficulty'])}
            >
              <option value="">Không chọn</option>
              {metadata.difficulties.map((difficulty) => (
                <option key={difficulty} value={difficulty}>{difficultyLabels[difficulty]}</option>
              ))}
            </select>
          </FormField>
          <FormField label="Kiểu vận động" error={errors.movementPattern} fieldId="exercise-movement">
            <MetadataSelect
              id="exercise-movement"
              value={value.movementPattern}
              options={metadata.movementPatterns}
              emptyLabel="Không chọn"
              aria-invalid={Boolean(errors.movementPattern)}
              aria-describedby={errors.movementPattern ? 'exercise-movement-error' : undefined}
              onValueChange={(next) => update('movementPattern', next)}
            />
          </FormField>
          <label className="checkbox-field checkbox-field--standalone">
            <input
              type="checkbox"
              checked={value.unilateral}
              onChange={(event) => update('unilateral', event.target.checked)}
            />
            Bài tập một bên cơ thể
          </label>
        </div>
        <FormField label="Mô tả" error={errors.description} fieldId="exercise-description">
          <textarea
            id="exercise-description"
            rows={4}
            value={value.description}
            aria-invalid={Boolean(errors.description)}
            aria-describedby={errors.description ? 'exercise-description-error' : undefined}
            onChange={(event) => update('description', event.target.value)}
          />
        </FormField>
        <FormField label="Hướng dẫn" error={errors.instructions} fieldId="exercise-instructions">
          <textarea
            id="exercise-instructions"
            rows={6}
            value={value.instructions}
            aria-invalid={Boolean(errors.instructions)}
            aria-describedby={errors.instructions ? 'exercise-instructions-error' : undefined}
            onChange={(event) => update('instructions', event.target.value)}
          />
        </FormField>
      </fieldset>

      <fieldset className="surface form-section" disabled={busy || locked}>
        <legend>Nhãn</legend>
        <p className="form-help">Chọn các nhãn phù hợp từ danh mục đang khả dụng.</p>
        <CheckboxOptions
          options={metadata.tags}
          selectedCodes={value.tagCodes}
          onChange={(tagCodes) => update('tagCodes', tagCodes)}
        />
        <FieldError id="tagCodes-error" message={errors.tagCodes} />
      </fieldset>

      <section className="surface form-section" aria-labelledby="variations-form-heading">
        <div className="section-heading-row">
          <div>
            <h2 id="variations-form-heading">Biến thể</h2>
            <p className="form-help">Có thể lưu bản nháp chưa có biến thể. Dữ liệu bắt buộc sẽ được kiểm tra khi kích hoạt.</p>
          </div>
          <button
            type="button"
            className="button-secondary"
            disabled={busy || locked || value.variations.length >= MAX_COLLECTION_ITEMS}
            onClick={() => update('variations', [...value.variations, createEmptyVariation()])}
          >
            Thêm biến thể
          </button>
        </div>
        <FieldError id="variations-error" message={errors.variations} />
        {value.variations.length === 0 ? <p className="muted">Chưa có biến thể.</p> : null}
        <div className="variation-editor-list">
          {value.variations.map((variation, index) => (
            <VariationEditor
              key={variation.clientKey}
              index={index}
              value={variation}
              metadata={metadata}
              errors={errors}
              disabled={busy || locked}
              onChange={(next) => updateVariation(index, next)}
              onRemove={() => update('variations', value.variations.filter((_, itemIndex) => itemIndex !== index))}
            />
          ))}
        </div>
      </section>

      <div className="form-actions">
        <button type="button" className="button-secondary" disabled={busy} onClick={onCancel}>
          Hủy
        </button>
        <button type="submit" disabled={busy || locked} aria-live="polite">
          {busy ? 'Đang lưu…' : mode === 'create' ? 'Tạo bản nháp' : 'Lưu thay đổi'}
        </button>
      </div>
    </form>
  )
}

function VariationEditor({
  index,
  value,
  metadata,
  errors,
  disabled,
  onChange,
  onRemove,
}: {
  index: number
  value: ExerciseVariationFormValue
  metadata: AdminExerciseFormMetadata
  errors: ExerciseDraftFormErrors
  disabled: boolean
  onChange: (value: ExerciseVariationFormValue) => void
  onRemove: () => void
}) {
  const prefix = `variations.${index}`
  const update = <K extends keyof ExerciseVariationFormValue>(
    field: K,
    nextValue: ExerciseVariationFormValue[K],
  ) => onChange({ ...value, [field]: nextValue })

  return (
    <fieldset className="variation-editor" disabled={disabled}>
      <legend>Biến thể {(index + 1).toLocaleString('vi-VN')}</legend>
      <div className="section-heading-row section-heading-row--compact">
        <span className="muted">Thiết lập nội dung và metadata cho biến thể này.</span>
        <button
          type="button"
          className="button-danger-text"
          onClick={onRemove}
          aria-label={`Xóa biến thể ${value.name || index + 1}`}
        >
          Xóa biến thể
        </button>
      </div>
      <div className="form-grid">
        <FormField label="Mã biến thể" required error={errors[`${prefix}.code`]} fieldId={`${prefix}-code`}>
          <input
            id={`${prefix}-code`}
            value={value.code}
            maxLength={120}
            aria-invalid={Boolean(errors[`${prefix}.code`])}
            aria-describedby={errors[`${prefix}.code`] ? `${prefix}-code-error` : undefined}
            onChange={(event) => update('code', event.target.value)}
          />
        </FormField>
        <FormField label="Tên biến thể" required error={errors[`${prefix}.name`]} fieldId={`${prefix}-name`}>
          <input
            id={`${prefix}-name`}
            value={value.name}
            maxLength={180}
            aria-invalid={Boolean(errors[`${prefix}.name`])}
            aria-describedby={errors[`${prefix}.name`] ? `${prefix}-name-error` : undefined}
            onChange={(event) => update('name', event.target.value)}
          />
        </FormField>
        <FormField label="Độ khó" error={errors[`${prefix}.difficulty`]} fieldId={`${prefix}-difficulty`}>
          <select
            id={`${prefix}-difficulty`}
            value={value.difficulty}
            onChange={(event) => update('difficulty', event.target.value as ExerciseVariationFormValue['difficulty'])}
          >
            <option value="">Không chọn</option>
            {metadata.difficulties.map((difficulty) => (
              <option key={difficulty} value={difficulty}>{difficultyLabels[difficulty]}</option>
            ))}
          </select>
        </FormField>
        <div className="checkbox-stack">
          <label className="checkbox-field">
            <input type="checkbox" checked={value.defaultVariation} onChange={(event) => update('defaultVariation', event.target.checked)} />
            Biến thể mặc định
          </label>
          <label className="checkbox-field">
            <input type="checkbox" checked={value.active} onChange={(event) => update('active', event.target.checked)} />
            Biến thể đang hoạt động
          </label>
        </div>
      </div>
      <FormField label="Mô tả biến thể" fieldId={`${prefix}-description`}>
        <textarea id={`${prefix}-description`} rows={3} value={value.description} onChange={(event) => update('description', event.target.value)} />
      </FormField>
      <FormField label="Hướng dẫn biến thể" fieldId={`${prefix}-instructions`}>
        <textarea id={`${prefix}-instructions`} rows={4} value={value.instructions} onChange={(event) => update('instructions', event.target.value)} />
      </FormField>

      <NestedMetadataEditor
        title="Nhóm cơ"
        itemLabel="nhóm cơ"
        values={value.muscles}
        options={metadata.muscleGroups}
        codeKey="muscleGroupCode"
        secondaryKey="involvement"
        secondaryOptions={[
          ['PRIMARY', 'Chính'],
          ['SECONDARY', 'Phụ'],
          ['STABILIZER', 'Ổn định'],
        ]}
        errorPrefix={`${prefix}.muscles`}
        errors={errors}
        createItem={createEmptyMuscle}
        onChange={(muscles) => update('muscles', muscles)}
      />
      <NestedMetadataEditor
        title="Thiết bị"
        itemLabel="thiết bị"
        values={value.equipment}
        options={metadata.equipment}
        codeKey="equipmentCode"
        secondaryKey="requirement"
        secondaryOptions={[
          ['REQUIRED', 'Bắt buộc'],
          ['OPTIONAL', 'Tùy chọn'],
          ['ALTERNATIVE', 'Thay thế'],
        ]}
        errorPrefix={`${prefix}.equipment`}
        errors={errors}
        createItem={createEmptyEquipment}
        onChange={(equipment) => update('equipment', equipment)}
      />
    </fieldset>
  )
}

interface NestedValue {
  clientKey: string
}

function NestedMetadataEditor<T extends NestedValue>({
  title,
  itemLabel,
  values,
  options,
  codeKey,
  secondaryKey,
  secondaryOptions,
  errorPrefix,
  errors,
  createItem,
  onChange,
}: {
  title: string
  itemLabel: string
  values: T[]
  options: CatalogOption[]
  codeKey: keyof T & string
  secondaryKey: keyof T & string
  secondaryOptions: Array<[string, string]>
  errorPrefix: string
  errors: ExerciseDraftFormErrors
  createItem: () => T
  onChange: (values: T[]) => void
}) {
  return (
    <fieldset className="nested-editor">
      <legend>{title}</legend>
      <button
        type="button"
        className="button-secondary button-compact"
        disabled={values.length >= MAX_COLLECTION_ITEMS}
        onClick={() => onChange([...values, createItem()])}
      >
        Thêm {itemLabel}
      </button>
      <FieldError id={`${errorPrefix}-error`} message={errors[errorPrefix]} />
      {values.map((item, itemIndex) => {
        const fieldPath = `${errorPrefix}.${itemIndex}.${codeKey}`
        const fieldId = `${errorPrefix}-${itemIndex}-${codeKey}`
        return (
          <div className="nested-editor-row" key={item.clientKey}>
            <label>
              <span className="sr-only">{title} {(itemIndex + 1).toLocaleString('vi-VN')}</span>
              <MetadataSelect
                id={fieldId}
                value={(item as unknown as Record<string, string>)[codeKey]}
                options={options}
                emptyLabel={`Chọn ${itemLabel}`}
                aria-invalid={Boolean(errors[fieldPath])}
                aria-describedby={errors[fieldPath] ? `${fieldId}-error` : undefined}
                onValueChange={(next) => {
                  const updated = [...values]
                  updated[itemIndex] = { ...item, [codeKey]: next } as T
                  onChange(updated)
                }}
              />
              <FieldError id={`${fieldId}-error`} message={errors[fieldPath]} />
            </label>
            <label>
              <span className="sr-only">Vai trò {itemLabel} {(itemIndex + 1).toLocaleString('vi-VN')}</span>
              <select
                value={(item as unknown as Record<string, string>)[secondaryKey]}
                onChange={(event) => {
                  const updated = [...values]
                  updated[itemIndex] = { ...item, [secondaryKey]: event.target.value } as T
                  onChange(updated)
                }}
              >
                {secondaryOptions.map(([code, label]) => <option key={code} value={code}>{label}</option>)}
              </select>
            </label>
            <button
              type="button"
              className="button-danger-text"
              aria-label={`Xóa ${itemLabel} ${itemIndex + 1}`}
              onClick={() => onChange(values.filter((_, index) => index !== itemIndex))}
            >
              Xóa
            </button>
          </div>
        )
      })}
    </fieldset>
  )
}

function MetadataSelect({
  value,
  options,
  emptyLabel,
  onValueChange,
  ...selectProps
}: {
  value: string
  options: CatalogOption[]
  emptyLabel: string
  onValueChange: (value: string) => void
} & Omit<SelectHTMLAttributes<HTMLSelectElement>, 'value' | 'onChange'>) {
  const hasCurrentOption = !value || options.some((option) => option.code === value)
  return (
    <select value={value} onChange={(event) => onValueChange(event.target.value)} {...selectProps}>
      <option value="">{emptyLabel}</option>
      {!hasCurrentOption ? <option value={value} disabled>{value} — Không còn khả dụng</option> : null}
      {options.map((option) => <option key={option.code} value={option.code}>{option.name}</option>)}
    </select>
  )
}

function CheckboxOptions({
  options,
  selectedCodes,
  onChange,
}: {
  options: CatalogOption[]
  selectedCodes: string[]
  onChange: (codes: string[]) => void
}) {
  const unavailableCodes = selectedCodes.filter(
    (code) => !options.some((option) => option.code === code),
  )
  return (
    <div className="checkbox-options">
      {options.length === 0 && unavailableCodes.length === 0 ? (
        <p className="muted">Không có nhãn khả dụng.</p>
      ) : null}
      {options.map((option) => (
        <label className="checkbox-field" key={option.code}>
          <input
            type="checkbox"
            checked={selectedCodes.includes(option.code)}
            disabled={!selectedCodes.includes(option.code) && selectedCodes.length >= MAX_COLLECTION_ITEMS}
            onChange={(event) => onChange(
              event.target.checked
                ? [...selectedCodes, option.code]
                : selectedCodes.filter((code) => code !== option.code),
            )}
          />
          {option.name}
        </label>
      ))}
      {unavailableCodes.map((code) => (
        <label className="checkbox-field checkbox-field--unavailable" key={code}>
          <input type="checkbox" checked disabled />
          {code} — Không còn khả dụng
        </label>
      ))}
    </div>
  )
}

function FormField({
  label,
  required,
  error,
  fieldId,
  children,
}: {
  label: string
  required?: boolean
  error?: string
  fieldId: string
  children: ReactNode
}) {
  return (
    <label className="form-field" htmlFor={fieldId}>
      <span>{label}{required ? <span aria-hidden="true"> *</span> : null}</span>
      {children}
      <FieldError id={`${fieldId}-error`} message={error} />
    </label>
  )
}

function FieldError({ id, message }: { id: string; message?: string }) {
  return message ? <span id={id} className="field-error">{message}</span> : null
}
