export type TrainerVerificationStatus =
  | 'NOT_SUBMITTED'
  | 'PENDING'
  | 'VERIFIED'
  | 'REJECTED'
  | 'SUSPENDED';

export type TrainerActivityStatus = 'ACTIVE' | 'INACTIVE' | 'SUSPENDED';

export type CoachingBlockingReason =
  | 'PROFILE_NOT_FOUND'
  | 'APPLICATION_NOT_SUBMITTED'
  | 'VERIFICATION_PENDING'
  | 'VERIFICATION_REJECTED'
  | 'VERIFICATION_EXPIRED'
  | 'VERIFICATION_REVOKED'
  | 'ACTIVITY_SUSPENDED'
  | 'ACTIVITY_INACTIVE'
  | 'POLICY_RESTRICTION';

export interface CoachingEligibility {
  eligible: boolean;
  blockingReasons: CoachingBlockingReason[];
}

export interface TrainerProfileResponse {
  userId: string;
  publicSlug: string | null;
  bio: string | null;
  yearsExperience: number | null;
  acceptingStudents: boolean;
  verificationStatus: TrainerVerificationStatus;
  activityStatus: TrainerActivityStatus;
  coachingEligibility: CoachingEligibility;
  verifiedAt: string | null;
  createdAt: string;
  updatedAt: string;
}

export interface CreateTrainerProfileRequest {
  publicSlug?: string | null;
  bio?: string | null;
  yearsExperience?: number | null;
  acceptingStudents?: boolean;
}

export interface UpdateTrainerProfileRequest {
  publicSlug?: string | null;
  bio?: string | null;
  yearsExperience?: number | null;
  acceptingStudents?: boolean;
}
