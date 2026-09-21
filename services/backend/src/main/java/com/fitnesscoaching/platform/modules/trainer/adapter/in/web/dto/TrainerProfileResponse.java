package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerProfileView;
import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
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
    public static TrainerProfileResponse fromView(TrainerProfileView view) {
        TrainerProfile domain = view.profile();
        return new TrainerProfileResponse(
                domain.userId(),
                domain.publicSlug(),
                domain.bio(),
                domain.yearsExperience(),
                domain.isAcceptingStudents(),
                domain.verificationStatus(),
                domain.activityStatus(),
                CoachingEligibilityDto.fromDomain(view.coachingEligibility()),
                domain.verifiedAt(),
                domain.createdAt(),
                domain.updatedAt()
        );
    }

    public static TrainerProfileResponse fromView(TrainerProfile profile, CoachingEligibility coachingEligibility) {
        return fromView(new TrainerProfileView(profile, coachingEligibility));
    }
}
