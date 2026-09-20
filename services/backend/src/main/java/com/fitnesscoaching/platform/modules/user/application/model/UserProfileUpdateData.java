package com.fitnesscoaching.platform.modules.user.application.model;

import java.time.Instant;

public record UserProfileUpdateData(
        String displayName,
        boolean phoneNumberSpecified,
        String phoneNumber,
        String preferredLocale,
        String timezone,
        Instant updatedAt
) {
    @Override
    public String toString() {
        return "UserProfileUpdateData[" +
                "displayName=" + displayName +
                ", phoneNumberSpecified=" + phoneNumberSpecified +
                ", phoneNumber=" + (phoneNumberSpecified ? (phoneNumber != null ? "[REDACTED]" : "null") : "omitted") +
                ", preferredLocale=" + preferredLocale +
                ", timezone=" + timezone +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
