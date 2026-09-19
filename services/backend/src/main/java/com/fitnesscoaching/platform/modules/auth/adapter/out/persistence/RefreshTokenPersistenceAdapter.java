package com.fitnesscoaching.platform.modules.auth.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.auth.application.port.out.RefreshTokenPort;
import com.fitnesscoaching.platform.modules.auth.domain.RefreshTokenRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class RefreshTokenPersistenceAdapter implements RefreshTokenPort {

    private final RefreshTokenJpaRepository repository;

    public RefreshTokenPersistenceAdapter(RefreshTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public RefreshTokenRecord saveAndFlush(RefreshTokenRecord token) {
        RefreshTokenEntity entity = new RefreshTokenEntity(
                token.userId(), token.tokenHash(), token.deviceName(), token.issuedAt(),
                token.expiresAt(), token.rotatedFromId());
        return toRecord(repository.saveAndFlush(entity));
    }

    @Override
    public Optional<RefreshTokenRecord> findByTokenHash(String tokenHash) {
        return repository.findByTokenHash(tokenHash).map(this::toRecord);
    }

    @Override
    public int revokeForRotation(UUID tokenId, Instant revokedAt) {
        return repository.revokeForRotation(tokenId, revokedAt);
    }

    @Override
    public int revokeForLogout(UUID tokenId, UUID userId, Instant revokedAt) {
        return repository.revokeForLogout(tokenId, userId, revokedAt);
    }

    @Override
    public int revokeActiveForUser(UUID userId, Instant revokedAt, String reason) {
        return repository.revokeActiveForUser(userId, revokedAt, reason);
    }

    private RefreshTokenRecord toRecord(RefreshTokenEntity entity) {
        return new RefreshTokenRecord(
                entity.getId(), entity.getUserId(), entity.getTokenHash(), entity.getDeviceName(),
                entity.getIssuedAt(), entity.getExpiresAt(), entity.getRotatedFromId(),
                entity.getRevokedAt(), entity.getRevokeReason());
    }
}
