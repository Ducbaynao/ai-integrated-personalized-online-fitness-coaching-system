package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import java.util.Map;

public record UserSettingsDto(
        int weekStartsOn,
        String measurementSystem,
        Map<String, Object> accessibilityPreferences,
        Map<String, Object> privacyPreferences
) {
    public static UserSettingsDto defaults() {
        return new UserSettingsDto(1, "METRIC", Map.of(), Map.of());
    }

    @Override
    public String toString() {
        return "UserSettingsDto[" +
                "weekStartsOn=" + weekStartsOn +
                ", measurementSystem=" + measurementSystem +
                ", accessibilityPreferences=" + accessibilityPreferences +
                ", privacyPreferences=[REDACTED]" +
                "]";
    }
}
