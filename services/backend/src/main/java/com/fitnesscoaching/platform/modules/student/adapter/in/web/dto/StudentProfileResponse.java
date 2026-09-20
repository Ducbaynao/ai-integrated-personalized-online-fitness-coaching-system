package com.fitnesscoaching.platform.modules.student.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record StudentProfileResponse(
        UUID userId,
        LocalDate dateOfBirth,
        Gender gender,
        TrainingExperienceLevel trainingExperienceLevel,
        BigDecimal trainingExperienceMonths,
        Integer availableDaysPerWeek,
        Integer preferredSessionMinutes,
        boolean onboardingCompleted,
        Instant onboardingCompletedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static StudentProfileResponse fromDomain(StudentProfile domain) {
        return new StudentProfileResponse(
                domain.userId(),
                domain.dateOfBirth(),
                domain.gender(),
                domain.trainingExperienceLevel(),
                domain.trainingExperienceMonths(),
                domain.availableDaysPerWeek(),
                domain.preferredSessionMinutes(),
                domain.isOnboardingCompleted(),
                domain.onboardingCompletedAt(),
                domain.createdAt(),
                domain.updatedAt()
        );
    }
}
