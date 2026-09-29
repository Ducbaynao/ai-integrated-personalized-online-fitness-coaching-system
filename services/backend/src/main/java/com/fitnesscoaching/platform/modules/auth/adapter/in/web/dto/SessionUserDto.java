package com.fitnesscoaching.platform.modules.auth.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public record SessionUserDto(
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
        UserCapabilitiesDto capabilities,
        UserSettingsDto settings
) {
    public SessionUserDto {
        roles = roles != null ? List.copyOf(roles) : List.of();
        permissions = permissions == null
                ? List.of()
                : permissions.stream().filter(Objects::nonNull).distinct().sorted().toList();
    }

    public SessionUserDto(
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
            UserCapabilitiesDto capabilities,
            UserSettingsDto settings
    ) {
        this(id, email, displayName, status, preferredLocale, timezone, emailVerifiedAt, createdAt,
                phoneNumber, roles, List.of(), capabilities, settings);
    }

    @Override
    public String toString() {
        return "SessionUserDto[" +
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
