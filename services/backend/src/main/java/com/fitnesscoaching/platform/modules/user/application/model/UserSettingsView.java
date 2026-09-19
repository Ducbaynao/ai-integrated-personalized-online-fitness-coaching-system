package com.fitnesscoaching.platform.modules.user.application.model;

import java.util.Collections;
import java.util.Map;

public record UserSettingsView(
        int weekStartsOn,
        String measurementSystem,
        Map<String, Object> accessibilityPreferences,
        Map<String, Object> privacyPreferences
) {
    public UserSettingsView {
        if (accessibilityPreferences == null) {
            accessibilityPreferences = Collections.emptyMap();
        }
        if (privacyPreferences == null) {
            privacyPreferences = Collections.emptyMap();
        }
    }

    public static UserSettingsView defaults() {
        return new UserSettingsView(1, "METRIC", Collections.emptyMap(), Collections.emptyMap());
    }
}
