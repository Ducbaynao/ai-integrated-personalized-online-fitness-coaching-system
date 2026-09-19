package com.fitnesscoaching.platform.modules.auth.domain;

import java.time.Instant;
import java.util.UUID;

public record RefreshTokenRecord(
        UUID id,
        UUID userId,
        String tokenHash,
        String deviceName,
        Instant issuedAt,
        Instant expiresAt,
        UUID rotatedFromId,
        Instant revokedAt,
        String revokeReason
) {
    public static RefreshTokenRecord issue(
            UUID userId,
            String tokenHash,
            String deviceName,
            Instant issuedAt,
            Instant expiresAt,
            UUID rotatedFromId
    ) {
        return new RefreshTokenRecord(
                null, userId, tokenHash, deviceName, issuedAt, expiresAt,
                rotatedFromId, null, null
        );
    }
}
