package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

public record UserCapabilitiesDto(
        boolean hasStudentProfile,
        boolean hasTrainerProfile,
        boolean canCoach
) {
    public static UserCapabilitiesDto none() {
        return new UserCapabilitiesDto(false, false, false);
    }
}
