export interface StudentProfileFormValues {
  dateOfBirth?: string;
  gender?: string;
  trainingExperienceLevel?: string;
  trainingExperienceMonths?: string;
  availableDaysPerWeek?: string;
  preferredSessionMinutes?: string;
}

export interface StudentProfileValidationErrors {
  dateOfBirth?: string;
  trainingExperienceMonths?: string;
  availableDaysPerWeek?: string;
  preferredSessionMinutes?: string;
  general?: string;
  [key: string]: string | undefined;
}

/**
 * Validates date string in YYYY-MM-DD format and ensures it's not in the future.
 */
export function validateDateOfBirth(dobStr?: string): string | null {
  if (!dobStr || dobStr.trim() === '') {
    return null;
  }

  const trimmed = dobStr.trim();
  const dateRegex = /^\d{4}-\d{2}-\d{2}$/;
  if (!dateRegex.test(trimmed)) {
    return 'Date of birth must be in YYYY-MM-DD format.';
  }

  const [yearStr, monthStr, dayStr] = trimmed.split('-');
  const year = parseInt(yearStr, 10);
  const month = parseInt(monthStr, 10);
  const day = parseInt(dayStr, 10);

  const parsed = new Date(year, month - 1, day);
  if (
    parsed.getFullYear() !== year ||
    parsed.getMonth() !== month - 1 ||
    parsed.getDate() !== day
  ) {
    return 'Invalid calendar date.';
  }

  // Compare against today's date in local time
  const today = new Date();
  today.setHours(23, 59, 59, 999);
  if (parsed > today) {
    return 'Date of birth cannot be in the future.';
  }

  return null;
}

/**
 * Validates trainingExperienceMonths: null or >= 0.
 */
export function validateTrainingExperienceMonths(val?: string): string | null {
  if (!val || val.trim() === '') {
    return null;
  }

  const num = Number(val.trim());
  if (isNaN(num)) {
    return 'Experience months must be a valid number.';
  }

  if (num < 0) {
    return 'Training experience months cannot be negative.';
  }

  return null;
}

/**
 * Validates availableDaysPerWeek: null or 1..7.
 */
export function validateAvailableDaysPerWeek(val?: string): string | null {
  if (!val || val.trim() === '') {
    return null;
  }

  const num = Number(val.trim());
  if (isNaN(num) || !Number.isInteger(num)) {
    return 'Available days must be an integer between 1 and 7.';
  }

  if (num < 1 || num > 7) {
    return 'Available days per week must be between 1 and 7.';
  }

  return null;
}

/**
 * Validates preferredSessionMinutes: null or 5..480.
 */
export function validatePreferredSessionMinutes(val?: string): string | null {
  if (!val || val.trim() === '') {
    return null;
  }

  const num = Number(val.trim());
  if (isNaN(num) || !Number.isInteger(num)) {
    return 'Session duration must be an integer between 5 and 480.';
  }

  if (num < 5 || num > 480) {
    return 'Preferred session minutes must be between 5 and 480.';
  }

  return null;
}

/**
 * Validates the complete Student Profile form.
 */
export function validateStudentProfileForm(values: StudentProfileFormValues): StudentProfileValidationErrors {
  const errors: StudentProfileValidationErrors = {};

  const dobError = validateDateOfBirth(values.dateOfBirth);
  if (dobError) {
    errors.dateOfBirth = dobError;
  }

  const monthsError = validateTrainingExperienceMonths(values.trainingExperienceMonths);
  if (monthsError) {
    errors.trainingExperienceMonths = monthsError;
  }

  const daysError = validateAvailableDaysPerWeek(values.availableDaysPerWeek);
  if (daysError) {
    errors.availableDaysPerWeek = daysError;
  }

  const minutesError = validatePreferredSessionMinutes(values.preferredSessionMinutes);
  if (minutesError) {
    errors.preferredSessionMinutes = minutesError;
  }

  return errors;
}
