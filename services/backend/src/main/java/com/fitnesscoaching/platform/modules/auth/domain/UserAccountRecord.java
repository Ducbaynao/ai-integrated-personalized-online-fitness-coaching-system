package com.fitnesscoaching.platform.modules.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record UserAccountRecord(
        UUID id,
        String email,
        String passwordHash,
        String displayName,
        AccountStatus status,
        String preferredLocale,
        String timezone,
        Instant emailVerifiedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public static UserAccountRecord newPendingUser(
            String email,
            String passwordHash,
            String displayName,
            String preferredLocale,
            String timezone,
            Instant now
    ) {
        return new UserAccountRecord(
                null,
                email,
                passwordHash,
                displayName,
                AccountStatus.PENDING_VERIFICATION,
                preferredLocale,
                timezone,
                null,
                now,
                now
        );
    }
}
