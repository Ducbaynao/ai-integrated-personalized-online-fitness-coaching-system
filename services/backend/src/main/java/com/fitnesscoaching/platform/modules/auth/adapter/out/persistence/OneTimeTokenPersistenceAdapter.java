package com.fitnesscoaching.platform.modules.auth.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.auth.application.port.out.OneTimeTokenPort;
import com.fitnesscoaching.platform.modules.auth.domain.OneTimeTokenRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class OneTimeTokenPersistenceAdapter implements OneTimeTokenPort {

    private final OneTimeTokenJpaRepository repository;

    public OneTimeTokenPersistenceAdapter(OneTimeTokenJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public OneTimeTokenRecord saveAndFlush(OneTimeTokenRecord tokenRecord) {
        OneTimeTokenEntity entity = toEntity(tokenRecord);
        OneTimeTokenEntity saved = repository.saveAndFlush(entity);
        return toRecord(saved);
    }

    @Override
    public Optional<OneTimeTokenRecord> findByTokenHashAndPurpose(String tokenHash, String purpose) {
        return repository.findByTokenHashAndPurpose(tokenHash, purpose).map(this::toRecord);
    }

    @Override
    public int consumeToken(UUID id, Instant currentTime) {
        return repository.consumeToken(id, currentTime);
    }

    private OneTimeTokenRecord toRecord(OneTimeTokenEntity entity) {
        return new OneTimeTokenRecord(
                entity.getId(),
                entity.getUserId(),
                entity.getPurpose(),
                entity.getTokenHash(),
                entity.getExpiresAt(),
                entity.getConsumedAt(),
                entity.getCreatedAt()
        );
    }

    private OneTimeTokenEntity toEntity(OneTimeTokenRecord record) {
        OneTimeTokenEntity entity = new OneTimeTokenEntity(
                record.userId(),
                record.tokenType(),
                record.tokenHash(),
                record.expiresAt(),
                record.createdAt()
        );
        if (record.id() != null) {
            entity.setId(record.id());
        }
        if (record.consumedAt() != null) {
            entity.setConsumedAt(record.consumedAt());
        }
        return entity;
    }
}
