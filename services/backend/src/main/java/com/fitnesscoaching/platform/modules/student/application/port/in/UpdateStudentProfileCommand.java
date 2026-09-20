package com.fitnesscoaching.platform.modules.student.application.port.in;

import com.fitnesscoaching.platform.modules.student.application.model.PatchField;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateStudentProfileCommand(
        UUID userId,
        PatchField<LocalDate> dateOfBirth,
        PatchField<Gender> gender,
        PatchField<TrainingExperienceLevel> trainingExperienceLevel,
        PatchField<BigDecimal> trainingExperienceMonths,
        PatchField<Integer> availableDaysPerWeek,
        PatchField<Integer> preferredSessionMinutes,
        PatchField<Boolean> onboardingCompleted
) {}
