package com.fitnesscoaching.platform.modules.student.application.port.in;

import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record CreateStudentProfileCommand(
        UUID userId,
        LocalDate dateOfBirth,
        Gender gender,
        TrainingExperienceLevel trainingExperienceLevel,
        BigDecimal trainingExperienceMonths,
        Integer availableDaysPerWeek,
        Integer preferredSessionMinutes,
        Boolean onboardingCompleted
) {}
