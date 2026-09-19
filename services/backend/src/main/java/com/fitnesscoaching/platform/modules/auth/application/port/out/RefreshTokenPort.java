package com.fitnesscoaching.platform.modules.auth.application.port.out;

import com.fitnesscoaching.platform.modules.auth.domain.RefreshTokenRecord;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenPort {

    RefreshTokenRecord saveAndFlush(RefreshTokenRecord token);

    Optional<RefreshTokenRecord> findByTokenHash(String tokenHash);

    int revokeForRotation(UUID tokenId, Instant revokedAt);

    int revokeForLogout(UUID tokenId, UUID userId, Instant revokedAt);

    int revokeActiveForUser(UUID userId, Instant revokedAt, String reason);
}
