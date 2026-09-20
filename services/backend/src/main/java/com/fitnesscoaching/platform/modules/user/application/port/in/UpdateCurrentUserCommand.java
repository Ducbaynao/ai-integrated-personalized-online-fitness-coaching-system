package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.user.application.model.PatchField;

import java.util.UUID;

public record UpdateCurrentUserCommand(
        UUID userId,
        PatchField<String> displayName,
        PatchField<String> phoneNumber,
        PatchField<String> preferredLocale,
        PatchField<String> timezone,
        PatchField<UpdateUserSettingsCommand> settings
) {
    public UpdateCurrentUserCommand {
        if (displayName == null) displayName = PatchField.omitted();
        if (phoneNumber == null) phoneNumber = PatchField.omitted();
        if (preferredLocale == null) preferredLocale = PatchField.omitted();
        if (timezone == null) timezone = PatchField.omitted();
        if (settings == null) settings = PatchField.omitted();
    }

    @Override
    public String toString() {
        return "UpdateCurrentUserCommand[" +
                "userId=" + userId +
                ", displayName=" + displayName +
                ", phoneNumber=" + (phoneNumber.isSpecified() ? (phoneNumber.isNull() ? "null" : "[REDACTED]") : "omitted") +
                ", preferredLocale=" + preferredLocale +
                ", timezone=" + timezone +
                ", settings=" + settings +
                "]";
    }
}
