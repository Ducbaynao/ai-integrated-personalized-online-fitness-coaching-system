export type Gender =
  | 'MALE'
  | 'FEMALE'
  | 'NON_BINARY'
  | 'OTHER'
  | 'PREFER_NOT_TO_SAY';

export type TrainingExperienceLevel =
  | 'BEGINNER'
  | 'INTERMEDIATE'
  | 'ADVANCED';

export interface StudentProfileResponse {
  userId: string;
  dateOfBirth: string | null;
  gender: Gender | null;
  trainingExperienceLevel: TrainingExperienceLevel | null;
  trainingExperienceMonths: number | null;
  availableDaysPerWeek: number | null;
  preferredSessionMinutes: number | null;
  onboardingCompleted: boolean;
  onboardingCompletedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateStudentProfileRequest {
  dateOfBirth?: string | null;
  gender?: Gender | null;
  trainingExperienceLevel?: TrainingExperienceLevel | null;
  trainingExperienceMonths?: number | null;
  availableDaysPerWeek?: number | null;
  preferredSessionMinutes?: number | null;
  onboardingCompleted?: boolean;
}

export interface UpdateStudentProfileRequest {
  dateOfBirth?: string | null;
  gender?: Gender | null;
  trainingExperienceLevel?: TrainingExperienceLevel | null;
  trainingExperienceMonths?: number | null;
  availableDaysPerWeek?: number | null;
  preferredSessionMinutes?: number | null;
  onboardingCompleted?: boolean;
}
