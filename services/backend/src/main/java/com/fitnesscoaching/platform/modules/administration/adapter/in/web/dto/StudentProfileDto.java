package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.application.model.StudentProfileInfo;

import java.time.LocalDate;

public record StudentProfileDto(
        LocalDate dateOfBirth,
        String gender,
        String trainingExperienceLevel,
        Number trainingExperienceMonths,
        Integer availableDaysPerWeek,
        Integer preferredSessionMinutes,
        boolean onboardingCompleted
) {
    public static StudentProfileDto fromDomain(StudentProfileInfo info) {
        if (info == null) return null;
        return new StudentProfileDto(
                info.dateOfBirth(),
                info.gender(),
                info.trainingExperienceLevel(),
                info.trainingExperienceMonths(),
                info.availableDaysPerWeek(),
                info.preferredSessionMinutes(),
                info.onboardingCompleted()
        );
    }
}
