import {
  validateAvailableDaysPerWeek,
  validateDateOfBirth,
  validatePreferredSessionMinutes,
  validateStudentProfileForm,
  validateTrainingExperienceMonths,
} from '@/features/student/studentValidation';

describe('Student Profile Validation', () => {
  describe('validateDateOfBirth', () => {
    it('allows undefined, null, or empty string', () => {
      expect(validateDateOfBirth(undefined)).toBeNull();
      expect(validateDateOfBirth('')).toBeNull();
      expect(validateDateOfBirth('   ')).toBeNull();
    });

    it('rejects invalid date format', () => {
      expect(validateDateOfBirth('15-06-1995')).toContain('YYYY-MM-DD');
      expect(validateDateOfBirth('1995/06/15')).toContain('YYYY-MM-DD');
      expect(validateDateOfBirth('invalid-date')).toContain('YYYY-MM-DD');
    });

    it('rejects invalid calendar dates', () => {
      expect(validateDateOfBirth('2023-02-30')).toContain('Invalid calendar date');
      expect(validateDateOfBirth('2023-04-31')).toContain('Invalid calendar date');
    });

    it('rejects future dates', () => {
      expect(validateDateOfBirth('2099-12-31')).toContain('cannot be in the future');
    });

    it('accepts valid past dates', () => {
      expect(validateDateOfBirth('1995-06-15')).toBeNull();
      expect(validateDateOfBirth('2000-01-01')).toBeNull();
    });
  });

  describe('validateTrainingExperienceMonths', () => {
    it('allows empty or undefined', () => {
      expect(validateTrainingExperienceMonths(undefined)).toBeNull();
      expect(validateTrainingExperienceMonths('')).toBeNull();
    });

    it('rejects non-numeric input', () => {
      expect(validateTrainingExperienceMonths('twelve')).toContain('valid number');
    });

    it('rejects negative months', () => {
      expect(validateTrainingExperienceMonths('-1')).toContain('cannot be negative');
      expect(validateTrainingExperienceMonths('-0.5')).toContain('cannot be negative');
    });

    it('accepts 0 and positive numbers', () => {
      expect(validateTrainingExperienceMonths('0')).toBeNull();
      expect(validateTrainingExperienceMonths('12')).toBeNull();
      expect(validateTrainingExperienceMonths('24.5')).toBeNull();
    });
  });

  describe('validateAvailableDaysPerWeek', () => {
    it('allows empty or undefined', () => {
      expect(validateAvailableDaysPerWeek(undefined)).toBeNull();
      expect(validateAvailableDaysPerWeek('')).toBeNull();
    });

    it('rejects values outside 1 to 7', () => {
      expect(validateAvailableDaysPerWeek('0')).toContain('between 1 and 7');
      expect(validateAvailableDaysPerWeek('8')).toContain('between 1 and 7');
      expect(validateAvailableDaysPerWeek('-2')).toContain('between 1 and 7');
    });

    it('rejects non-integers', () => {
      expect(validateAvailableDaysPerWeek('3.5')).toContain('integer between 1 and 7');
    });

    it('accepts integers between 1 and 7', () => {
      for (let i = 1; i <= 7; i++) {
        expect(validateAvailableDaysPerWeek(i.toString())).toBeNull();
      }
    });
  });

  describe('validatePreferredSessionMinutes', () => {
    it('allows empty or undefined', () => {
      expect(validatePreferredSessionMinutes(undefined)).toBeNull();
      expect(validatePreferredSessionMinutes('')).toBeNull();
    });

    it('rejects values outside 5 to 480', () => {
      expect(validatePreferredSessionMinutes('4')).toContain('between 5 and 480');
      expect(validatePreferredSessionMinutes('481')).toContain('between 5 and 480');
      expect(validatePreferredSessionMinutes('0')).toContain('between 5 and 480');
    });

    it('rejects non-integers', () => {
      expect(validatePreferredSessionMinutes('45.5')).toContain('integer between 5 and 480');
    });

    it('accepts valid session durations in 5..480', () => {
      expect(validatePreferredSessionMinutes('5')).toBeNull();
      expect(validatePreferredSessionMinutes('45')).toBeNull();
      expect(validatePreferredSessionMinutes('60')).toBeNull();
      expect(validatePreferredSessionMinutes('480')).toBeNull();
    });
  });

  describe('validateStudentProfileForm', () => {
    it('returns no errors for valid form data', () => {
      const errors = validateStudentProfileForm({
        dateOfBirth: '1995-06-15',
        trainingExperienceMonths: '12',
        availableDaysPerWeek: '4',
        preferredSessionMinutes: '60',
      });
      expect(Object.keys(errors)).toHaveLength(0);
    });

    it('returns multiple errors when several fields are invalid', () => {
      const errors = validateStudentProfileForm({
        dateOfBirth: '2099-01-01',
        trainingExperienceMonths: '-5',
        availableDaysPerWeek: '9',
        preferredSessionMinutes: '2',
      });
      expect(errors.dateOfBirth).toBeDefined();
      expect(errors.trainingExperienceMonths).toBeDefined();
      expect(errors.availableDaysPerWeek).toBeDefined();
      expect(errors.preferredSessionMinutes).toBeDefined();
    });
  });
});
