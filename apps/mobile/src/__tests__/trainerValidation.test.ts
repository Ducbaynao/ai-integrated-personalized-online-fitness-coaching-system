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
        'Đường dẫn công khai không được quá 100 ký tự.'
      );
    });

    it('returns error for invalid slug patterns', () => {
      expect(validatePublicSlug('Coach-Alex')).toBe(
        'Đường dẫn công khai chỉ được gồm chữ thường, số và dấu gạch nối; không dùng hai dấu gạch nối liên tiếp.'
      );
      expect(validatePublicSlug('-coach')).toBe(
        'Đường dẫn công khai chỉ được gồm chữ thường, số và dấu gạch nối; không dùng hai dấu gạch nối liên tiếp.'
      );
      expect(validatePublicSlug('coach-')).toBe(
        'Đường dẫn công khai chỉ được gồm chữ thường, số và dấu gạch nối; không dùng hai dấu gạch nối liên tiếp.'
      );
      expect(validatePublicSlug('coach--alex')).toBe(
        'Đường dẫn công khai chỉ được gồm chữ thường, số và dấu gạch nối; không dùng hai dấu gạch nối liên tiếp.'
      );
      expect(validatePublicSlug('coach alex')).toBe(
        'Đường dẫn công khai chỉ được gồm chữ thường, số và dấu gạch nối; không dùng hai dấu gạch nối liên tiếp.'
      );
      expect(validatePublicSlug('coach_alex')).toBe(
        'Đường dẫn công khai chỉ được gồm chữ thường, số và dấu gạch nối; không dùng hai dấu gạch nối liên tiếp.'
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
      expect(validateBio(longBio)).toBe('Giới thiệu không được quá 4.000 ký tự.');
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
        'Số năm kinh nghiệm không được âm.'
      );
    });

    it('returns error for experience exceeding 99.99', () => {
      expect(validateYearsExperience('100')).toBe(
        'Số năm kinh nghiệm không được quá 99,99.'
      );
    });

    it('returns error for non-numeric input', () => {
      expect(validateYearsExperience('abc')).toBe(
        'Số năm kinh nghiệm phải là số hợp lệ.'
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
