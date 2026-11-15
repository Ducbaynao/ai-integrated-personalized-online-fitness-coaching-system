package com.fitnesscoaching.platform.modules.administration.application.model;

import java.time.Instant;
import java.util.UUID;

public record TrainerProfileInfo(
        String publicSlug,
        String bio,
        Number yearsExperience,
        String verificationStatus,
        String activityStatus,
        Instant verifiedAt,
        UUID verifiedBy,
        boolean isAcceptingStudents,
        boolean isActive
) {
}
