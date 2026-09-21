package com.fitnesscoaching.platform.modules.trainer.domain;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.util.ArrayList;
import java.util.List;

public record CoachingEligibility(
        boolean eligible,
        List<String> blockingReasons
) {

    public CoachingEligibility {
        blockingReasons = blockingReasons == null ? List.of() : List.copyOf(blockingReasons);
        if (eligible && !blockingReasons.isEmpty()) {
            throw new IllegalArgumentException("Eligible coaching status cannot have blocking reasons: " + blockingReasons);
        }
        if (!eligible && blockingReasons.isEmpty()) {
            throw new IllegalArgumentException("Ineligible coaching status must specify at least one blocking reason");
        }
    }

    public static CoachingEligibility evaluate(
            AccountStatus accountStatus,
            boolean hasActiveTrainerRole,
            TrainerProfile profile
    ) {
        return evaluate(accountStatus, hasActiveTrainerRole, profile, false);
    }

    public static CoachingEligibility evaluate(
            AccountStatus accountStatus,
            boolean hasActiveTrainerRole,
            TrainerProfile profile,
            boolean hasPolicyRestriction
    ) {
        List<String> reasons = new ArrayList<>();

        if (accountStatus != AccountStatus.ACTIVE) {
            reasons.add("ACCOUNT_NOT_ACTIVE");
        }

        if (!hasActiveTrainerRole) {
            reasons.add("TRAINER_ROLE_MISSING");
        }

        if (profile == null) {
            reasons.add("PROFILE_NOT_FOUND");
        } else {
            if (!profile.isActive()) {
                reasons.add("PROFILE_INACTIVE");
            }

            if (profile.verificationStatus() == TrainerVerificationStatus.NOT_SUBMITTED) {
                reasons.add("APPLICATION_NOT_SUBMITTED");
            } else if (profile.verificationStatus() == TrainerVerificationStatus.PENDING) {
                reasons.add("VERIFICATION_PENDING");
            } else if (profile.verificationStatus() == TrainerVerificationStatus.REJECTED) {
                reasons.add("VERIFICATION_REJECTED");
            } else if (profile.verificationStatus() == TrainerVerificationStatus.SUSPENDED) {
                reasons.add("VERIFICATION_REVOKED");
            }

            if (profile.activityStatus() == TrainerActivityStatus.INACTIVE) {
                reasons.add("ACTIVITY_INACTIVE");
            } else if (profile.activityStatus() == TrainerActivityStatus.SUSPENDED) {
                reasons.add("ACTIVITY_SUSPENDED");
            }
        }

        if (hasPolicyRestriction) {
            reasons.add("POLICY_RESTRICTION");
        }

        boolean eligible = reasons.isEmpty();
        return new CoachingEligibility(eligible, reasons);
    }

    public static CoachingEligibility evaluate(
            TrainerVerificationStatus verificationStatus,
            TrainerActivityStatus activityStatus
    ) {
        return evaluate(verificationStatus, activityStatus, true);
    }

    public static CoachingEligibility evaluate(
            TrainerVerificationStatus verificationStatus,
            TrainerActivityStatus activityStatus,
            boolean isActive
    ) {
        TrainerProfile stubProfile = new TrainerProfile(
                null, null, null, null, false,
                verificationStatus, activityStatus, null, null, isActive, null, null
        );
        return evaluate(AccountStatus.ACTIVE, true, stubProfile, false);
    }
}
