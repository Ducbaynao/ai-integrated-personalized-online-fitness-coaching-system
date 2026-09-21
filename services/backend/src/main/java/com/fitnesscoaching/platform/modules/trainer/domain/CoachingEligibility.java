package com.fitnesscoaching.platform.modules.trainer.domain;

import java.util.ArrayList;
import java.util.List;

public record CoachingEligibility(
        boolean eligible,
        List<String> blockingReasons
) {
    public static CoachingEligibility evaluate(
            TrainerVerificationStatus verificationStatus,
            TrainerActivityStatus activityStatus
    ) {
        List<String> reasons = new ArrayList<>();
        if (verificationStatus == TrainerVerificationStatus.NOT_SUBMITTED) {
            reasons.add("APPLICATION_NOT_SUBMITTED");
        } else if (verificationStatus == TrainerVerificationStatus.PENDING) {
            reasons.add("VERIFICATION_PENDING");
        } else if (verificationStatus == TrainerVerificationStatus.REJECTED) {
            reasons.add("VERIFICATION_REJECTED");
        } else if (verificationStatus == TrainerVerificationStatus.SUSPENDED) {
            reasons.add("VERIFICATION_REVOKED");
        }

        if (activityStatus == TrainerActivityStatus.INACTIVE) {
            reasons.add("ACTIVITY_INACTIVE");
        } else if (activityStatus == TrainerActivityStatus.SUSPENDED) {
            reasons.add("ACTIVITY_SUSPENDED");
        }

        // In Milestone M1, coaching authority is not yet grantable to self-created profiles
        if (reasons.isEmpty()) {
            reasons.add("POLICY_RESTRICTION");
        }

        return new CoachingEligibility(false, List.copyOf(reasons));
    }
}
