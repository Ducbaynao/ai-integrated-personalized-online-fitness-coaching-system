package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.user.application.model.PatchField;

import java.util.Map;

public record UpdateUserSettingsCommand(
        PatchField<Integer> weekStartsOn,
        PatchField<String> measurementSystem,
        PatchField<Map<String, Object>> accessibilityPreferences,
        PatchField<Map<String, Object>> privacyPreferences
) {
    public UpdateUserSettingsCommand {
        if (weekStartsOn == null) weekStartsOn = PatchField.omitted();
        if (measurementSystem == null) measurementSystem = PatchField.omitted();
        if (accessibilityPreferences == null) accessibilityPreferences = PatchField.omitted();
        if (privacyPreferences == null) privacyPreferences = PatchField.omitted();
    }

    public boolean hasUpdates() {
        return weekStartsOn.isSpecified()
                || measurementSystem.isSpecified()
                || accessibilityPreferences.isSpecified()
                || privacyPreferences.isSpecified();
    }

    @Override
    public String toString() {
        return "UpdateUserSettingsCommand[" +
                "weekStartsOn=" + weekStartsOn +
                ", measurementSystem=" + measurementSystem +
                ", accessibilityPreferences=" + accessibilityPreferences +
                ", privacyPreferences=" + (privacyPreferences.isSpecified() ? (privacyPreferences.isNull() ? "null" : "[REDACTED]") : "omitted") +
                "]";
    }
}
