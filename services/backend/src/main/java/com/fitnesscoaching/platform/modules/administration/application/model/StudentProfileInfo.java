package com.fitnesscoaching.platform.modules.administration.application.model;

import java.time.LocalDate;

public record StudentProfileInfo(
        LocalDate dateOfBirth,
        String gender,
        String trainingExperienceLevel,
        Number trainingExperienceMonths,
        Integer availableDaysPerWeek,
        Integer preferredSessionMinutes,
        boolean onboardingCompleted
) {
}
