package com.fitnesscoaching.platform.modules.student.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StudentProfile(
        UUID userId,
        LocalDate dateOfBirth,
        Gender gender,
        TrainingExperienceLevel trainingExperienceLevel,
        BigDecimal trainingExperienceMonths,
        Integer availableDaysPerWeek,
        Integer preferredSessionMinutes,
        Instant onboardingCompletedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public boolean isOnboardingCompleted() {
        return onboardingCompletedAt != null;
    }
}
