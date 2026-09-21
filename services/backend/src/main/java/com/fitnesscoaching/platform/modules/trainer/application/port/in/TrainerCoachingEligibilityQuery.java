package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;

import java.util.UUID;

public interface TrainerCoachingEligibilityQuery {

    CoachingEligibility getCoachingEligibility(UUID userId);

    default boolean canCoach(UUID userId) {
        return getCoachingEligibility(userId).eligible();
    }
}
