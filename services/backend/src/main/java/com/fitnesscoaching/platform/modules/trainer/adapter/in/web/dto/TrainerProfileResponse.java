package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerActivityStatus;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TrainerProfileResponse(
        UUID userId,
        String publicSlug,
        String bio,
        BigDecimal yearsExperience,
        boolean acceptingStudents,
        TrainerVerificationStatus verificationStatus,
        TrainerActivityStatus activityStatus,
        CoachingEligibilityDto coachingEligibility,
        Instant verifiedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static TrainerProfileResponse fromDomain(TrainerProfile domain) {
        return new TrainerProfileResponse(
                domain.userId(),
                domain.publicSlug(),
                domain.bio(),
                domain.yearsExperience(),
                domain.isAcceptingStudents(),
                domain.verificationStatus(),
                domain.activityStatus(),
                CoachingEligibilityDto.fromDomain(domain.getCoachingEligibility()),
                domain.verifiedAt(),
                domain.createdAt(),
                domain.updatedAt()
        );
    }
}
