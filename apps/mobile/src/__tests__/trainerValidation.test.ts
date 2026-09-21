import {
  validateBio,
  validatePublicSlug,
  validateTrainerProfileForm,
  validateYearsExperience,
} from '@/features/trainer/trainerValidation';

describe('trainerValidation', () => {
  describe('validatePublicSlug', () => {
    it('returns null for undefined, null, or empty string (optional)', () => {
      expect(validatePublicSlug(undefined)).toBeNull();
      expect(validatePublicSlug('')).toBeNull();
      expect(validatePublicSlug('   ')).toBeNull();
    });

    it('returns null for valid slugs', () => {
      expect(validatePublicSlug('coach-alex')).toBeNull();
      expect(validatePublicSlug('alex123')).toBeNull();
      expect(validatePublicSlug('fitness-coach-2026')).toBeNull();
    });

    it('returns error if slug exceeds 100 characters', () => {
      const longSlug = 'a'.repeat(101);
      expect(validatePublicSlug(longSlug)).toBe(
        'Public slug cannot exceed 100 characters.'
      );
    });

    it('returns error for invalid slug patterns', () => {
      expect(validatePublicSlug('Coach-Alex')).toBe(
        'Public slug must contain only lowercase letters, numbers, and hyphens without consecutive hyphens.'
      );
      expect(validatePublicSlug('-coach')).toBe(
        'Public slug must contain only lowercase letters, numbers, and hyphens without consecutive hyphens.'
      );
      expect(validatePublicSlug('coach-')).toBe(
        'Public slug must contain only lowercase letters, numbers, and hyphens without consecutive hyphens.'
      );
      expect(validatePublicSlug('coach--alex')).toBe(
        'Public slug must contain only lowercase letters, numbers, and hyphens without consecutive hyphens.'
      );
      expect(validatePublicSlug('coach alex')).toBe(
        'Public slug must contain only lowercase letters, numbers, and hyphens without consecutive hyphens.'
      );
      expect(validatePublicSlug('coach_alex')).toBe(
        'Public slug must contain only lowercase letters, numbers, and hyphens without consecutive hyphens.'
      );
    });
  });

  describe('validateBio', () => {
    it('returns null for undefined, null, or empty string (optional)', () => {
      expect(validateBio(undefined)).toBeNull();
      expect(validateBio('')).toBeNull();
      expect(validateBio('   ')).toBeNull();
    });

    it('returns null for bio under 4000 characters', () => {
      expect(validateBio('Experienced personal trainer with 5 years in powerlifting.')).toBeNull();
    });

    it('returns error for bio exceeding 4000 characters', () => {
      const longBio = 'x'.repeat(4001);
      expect(validateBio(longBio)).toBe('Bio cannot exceed 4000 characters.');
    });
  });

  describe('validateYearsExperience', () => {
    it('returns null for undefined, null, or empty string (optional)', () => {
      expect(validateYearsExperience(undefined)).toBeNull();
      expect(validateYearsExperience('')).toBeNull();
      expect(validateYearsExperience('   ')).toBeNull();
    });

    it('returns null for valid numeric experience', () => {
      expect(validateYearsExperience('0')).toBeNull();
      expect(validateYearsExperience('5')).toBeNull();
      expect(validateYearsExperience('3.5')).toBeNull();
      expect(validateYearsExperience('99.99')).toBeNull();
    });

    it('returns error for negative experience', () => {
      expect(validateYearsExperience('-1')).toBe(
        'Years of experience cannot be negative.'
      );
    });

    it('returns error for experience exceeding 99.99', () => {
      expect(validateYearsExperience('100')).toBe(
        'Years of experience cannot exceed 99.99.'
      );
    });

    it('returns error for non-numeric input', () => {
      expect(validateYearsExperience('abc')).toBe(
        'Years of experience must be a valid number.'
      );
    });
  });

  describe('validateTrainerProfileForm', () => {
    it('returns empty error object when all fields are valid or omitted', () => {
      expect(
        validateTrainerProfileForm({
          publicSlug: 'coach-sam',
          bio: 'Strength coach',
          yearsExperience: '4',
          acceptingStudents: true,
        })
      ).toEqual({});

      expect(validateTrainerProfileForm({})).toEqual({});
    });

    it('collects all validation errors', () => {
      const errors = validateTrainerProfileForm({
        publicSlug: 'INVALID SLUG',
        bio: 'a'.repeat(4005),
        yearsExperience: '-2',
      });

      expect(errors.publicSlug).toBeDefined();
      expect(errors.bio).toBeDefined();
      expect(errors.yearsExperience).toBeDefined();
    });
  });
});
