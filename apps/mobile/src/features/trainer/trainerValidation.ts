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
    return 'Public slug cannot exceed 100 characters.';
  }

  if (!PUBLIC_SLUG_REGEX.test(trimmed)) {
    return 'Public slug must contain only lowercase letters, numbers, and hyphens without consecutive hyphens.';
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
    return 'Bio cannot exceed 4000 characters.';
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
    return 'Years of experience must be a valid number.';
  }

  if (num < 0) {
    return 'Years of experience cannot be negative.';
  }

  if (num > 99.99) {
    return 'Years of experience cannot exceed 99.99.';
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
