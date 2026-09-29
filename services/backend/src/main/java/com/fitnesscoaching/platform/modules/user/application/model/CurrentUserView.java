package com.fitnesscoaching.platform.modules.user.application.model;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record CurrentUserView(
        UUID id,
        String email,
        String displayName,
        AccountStatus status,
        String preferredLocale,
        String timezone,
        Instant emailVerifiedAt,
        Instant createdAt,
        String phoneNumber,
        List<String> roles,
        List<String> permissions,
        UserCapabilitiesView capabilities,
        UserSettingsView settings
) {
    public CurrentUserView {
        if (roles == null) {
            roles = Collections.emptyList();
        } else {
            roles = List.copyOf(roles);
        }
        permissions = permissions == null
                ? List.of()
                : permissions.stream().filter(Objects::nonNull).distinct().sorted().toList();
        if (capabilities == null) {
            capabilities = UserCapabilitiesView.none();
        }
        if (settings == null) {
            settings = UserSettingsView.defaults();
        }
    }

    public CurrentUserView(
            UUID id,
            String email,
            String displayName,
            AccountStatus status,
            String preferredLocale,
            String timezone,
            Instant emailVerifiedAt,
            Instant createdAt,
            String phoneNumber,
            List<String> roles,
            UserCapabilitiesView capabilities,
            UserSettingsView settings
    ) {
        this(id, email, displayName, status, preferredLocale, timezone, emailVerifiedAt, createdAt,
                phoneNumber, roles, List.of(), capabilities, settings);
    }

    @Override
    public String toString() {
        return "CurrentUserView[" +
                "id=" + id +
                ", email=" + email +
                ", displayName=" + displayName +
                ", status=" + status +
                ", preferredLocale=" + preferredLocale +
                ", timezone=" + timezone +
                ", emailVerifiedAt=" + emailVerifiedAt +
                ", createdAt=" + createdAt +
                ", phoneNumber=" + (phoneNumber != null ? "[REDACTED]" : "null") +
                ", roles=" + roles +
                ", capabilities=" + capabilities +
                ", settings=" + settings +
                "]";
    }
}
