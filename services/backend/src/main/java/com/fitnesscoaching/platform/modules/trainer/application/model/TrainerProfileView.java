package com.fitnesscoaching.platform.modules.trainer.application.model;

import com.fitnesscoaching.platform.modules.trainer.domain.CoachingEligibility;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;

import java.util.Objects;

/**
 * Application-level view combining the domain TrainerProfile with its
 * canonical evaluated CoachingEligibility.
 */
public record TrainerProfileView(
        TrainerProfile profile,
        CoachingEligibility coachingEligibility
) {
    public TrainerProfileView {
        Objects.requireNonNull(profile, "TrainerProfile cannot be null");
        Objects.requireNonNull(coachingEligibility, "CoachingEligibility cannot be null");
    }
}
