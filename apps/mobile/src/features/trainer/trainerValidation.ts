export interface TrainerProfileFormValues {
  publicSlug?: string;
  bio?: string;
  yearsExperience?: string;
  acceptingStudents?: boolean;
}

export interface TrainerProfileValidationErrors {
  publicSlug?: string;
  bio?: string;
  yearsExperience?: string;
  general?: string;
  [key: string]: string | undefined;
}

const PUBLIC_SLUG_REGEX = /^[a-z0-9]+(?:-[a-z0-9]+)*$/;

/**
 * Validates public slug: optional, max 100 characters, pattern ^[a-z0-9]+(?:-[a-z0-9]+)*$.
 */
export function validatePublicSlug(slug?: string): string | null {
  if (!slug || slug.trim() === '') {
    return null;
  }

  const trimmed = slug.trim();
  if (trimmed.length > 100) {
    return 'Đường dẫn công khai không được quá 100 ký tự.';
  }

  if (!PUBLIC_SLUG_REGEX.test(trimmed)) {
    return 'Đường dẫn công khai chỉ được gồm chữ thường, số và dấu gạch nối; không dùng hai dấu gạch nối liên tiếp.';
  }

  return null;
}

/**
 * Validates bio: optional, max 4000 characters.
 */
export function validateBio(bio?: string): string | null {
  if (!bio || bio.trim() === '') {
    return null;
  }

  if (bio.length > 4000) {
    return 'Giới thiệu không được quá 4.000 ký tự.';
  }

  return null;
}

/**
 * Validates yearsExperience: optional, number between 0 and 99.99.
 */
export function validateYearsExperience(val?: string): string | null {
  if (!val || val.trim() === '') {
    return null;
  }

  const num = Number(val.trim());
  if (isNaN(num)) {
    return 'Số năm kinh nghiệm phải là số hợp lệ.';
  }

  if (num < 0) {
    return 'Số năm kinh nghiệm không được âm.';
  }

  if (num > 99.99) {
    return 'Số năm kinh nghiệm không được quá 99,99.';
  }

  return null;
}

/**
 * Validates the complete Trainer Profile form.
 */
export function validateTrainerProfileForm(
  values: TrainerProfileFormValues
): TrainerProfileValidationErrors {
  const errors: TrainerProfileValidationErrors = {};

  const slugError = validatePublicSlug(values.publicSlug);
  if (slugError) {
    errors.publicSlug = slugError;
  }

  const bioError = validateBio(values.bio);
  if (bioError) {
    errors.bio = bioError;
  }

  const yearsError = validateYearsExperience(values.yearsExperience);
  if (yearsError) {
    errors.yearsExperience = yearsError;
  }

  return errors;
}
