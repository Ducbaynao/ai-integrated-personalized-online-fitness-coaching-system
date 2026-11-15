package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.application.model.TrainerProfileInfo;

import java.time.Instant;
import java.util.UUID;

public record TrainerProfileDto(
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
    public static TrainerProfileDto fromDomain(TrainerProfileInfo info) {
        if (info == null) return null;
        return new TrainerProfileDto(
                info.publicSlug(),
                info.bio(),
                info.yearsExperience(),
                info.verificationStatus(),
                info.activityStatus(),
                info.verifiedAt(),
                info.verifiedBy(),
                info.isAcceptingStudents(),
                info.isActive()
        );
    }
}
