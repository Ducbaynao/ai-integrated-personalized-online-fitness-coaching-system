package com.fitnesscoaching.platform.modules.user.adapter.in.web.dto;

public record UserCapabilitiesDto(
        boolean hasStudentProfile,
        boolean hasTrainerProfile,
        boolean canCoach
) {
}
