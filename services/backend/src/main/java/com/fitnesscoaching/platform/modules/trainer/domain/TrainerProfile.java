package com.fitnesscoaching.platform.modules.trainer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TrainerProfile(
        UUID userId,
        String publicSlug,
        String bio,
        BigDecimal yearsExperience,
        boolean isAcceptingStudents,
        TrainerVerificationStatus verificationStatus,
        TrainerActivityStatus activityStatus,
        Instant verifiedAt,
        UUID verifiedBy,
        boolean isActive,
        Instant createdAt,
        Instant updatedAt
) {
    public CoachingEligibility getCoachingEligibility() {
        return CoachingEligibility.evaluate(verificationStatus, activityStatus);
    }
}
