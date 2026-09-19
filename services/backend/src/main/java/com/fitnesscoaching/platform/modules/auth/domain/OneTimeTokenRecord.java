package com.fitnesscoaching.platform.modules.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record OneTimeTokenRecord(
        UUID id,
        UUID userId,
        String tokenType,
        String tokenHash,
        Instant expiresAt,
        Instant consumedAt,
        Instant createdAt
) {
    public static final String PURPOSE_EMAIL_VERIFICATION = "EMAIL_VERIFICATION";

    public static OneTimeTokenRecord newVerificationToken(
            UUID userId,
            String tokenHash,
            Instant expiresAt,
            Instant now
    ) {
        return new OneTimeTokenRecord(
                null,
                userId,
                PURPOSE_EMAIL_VERIFICATION,
                tokenHash,
                expiresAt,
                null,
                now
        );
    }
}
