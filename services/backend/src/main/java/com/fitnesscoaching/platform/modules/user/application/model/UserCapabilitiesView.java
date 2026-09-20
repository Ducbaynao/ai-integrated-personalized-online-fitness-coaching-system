package com.fitnesscoaching.platform.modules.user.application.model;

public record UserCapabilitiesView(
        boolean hasStudentProfile,
        boolean hasTrainerProfile,
        boolean canCoach
) {
    public static UserCapabilitiesView none() {
        return new UserCapabilitiesView(false, false, false);
    }
}
