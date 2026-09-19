package com.fitnesscoaching.platform.modules.user.application.model;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
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
        UserCapabilitiesView capabilities,
        UserSettingsView settings
) {
    public CurrentUserView {
        if (roles == null) {
            roles = Collections.emptyList();
        } else {
            roles = List.copyOf(roles);
        }
        if (capabilities == null) {
            capabilities = UserCapabilitiesView.none();
        }
        if (settings == null) {
            settings = UserSettingsView.defaults();
        }
    }
}
