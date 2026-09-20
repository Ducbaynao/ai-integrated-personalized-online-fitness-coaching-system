package com.fitnesscoaching.platform.modules.user.application.model;

import java.time.Instant;
import java.util.Map;

public record UserSettingsData(
        int weekStartsOn,
        String measurementSystem,
        Map<String, Object> accessibilityPreferences,
        Map<String, Object> privacyPreferences,
        Instant updatedAt
) {
    public UserSettingsData {
        accessibilityPreferences = accessibilityPreferences != null ? Map.copyOf(accessibilityPreferences) : Map.of();
        privacyPreferences = privacyPreferences != null ? Map.copyOf(privacyPreferences) : Map.of();
    }

    @Override
    public String toString() {
        return "UserSettingsData[" +
                "weekStartsOn=" + weekStartsOn +
                ", measurementSystem=" + measurementSystem +
                ", accessibilityPreferences=" + accessibilityPreferences +
                ", privacyPreferences=[REDACTED]" +
                ", updatedAt=" + updatedAt +
                "]";
    }
}
