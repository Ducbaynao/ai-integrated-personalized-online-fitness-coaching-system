package com.fitnesscoaching.platform.modules.auth.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.auth.application.port.out.UserAccountPort;
import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class UserAccountPersistenceAdapter implements UserAccountPort {

    private final UserAccountJpaRepository repository;

    public UserAccountPersistenceAdapter(UserAccountJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UserAccountRecord> findByEmailIgnoreCase(String email) {
        return repository.findByEmailIgnoreCase(email).map(this::toRecord);
    }

    @Override
    public boolean existsByEmailIgnoreCase(String email) {
        return repository.existsByEmailIgnoreCase(email);
    }

    @Override
    public UserAccountRecord saveAndFlush(UserAccountRecord userRecord) {
        UserAccountEntity entity = toEntity(userRecord);
        UserAccountEntity saved = repository.saveAndFlush(entity);
        return toRecord(saved);
    }

    @Override
    public Optional<UserAccountRecord> findById(UUID id) {
        return repository.findById(id).map(this::toRecord);
    }

    @Override
    public int activatePendingUser(UUID userId, Instant now) {
        return repository.activatePendingUser(userId, now);
    }

    @Override
    public int updateLastLogin(UUID userId, Instant now) {
        return repository.updateLastLogin(userId, now);
    }

    private UserAccountRecord toRecord(UserAccountEntity entity) {
        return new UserAccountRecord(
                entity.getId(),
                entity.getEmail(),
                entity.getPasswordHash(),
                entity.getDisplayName(),
                entity.getStatus(),
                entity.getPreferredLocale(),
                entity.getTimezone(),
                entity.getEmailVerifiedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt()
        );
    }

    private UserAccountEntity toEntity(UserAccountRecord record) {
        UserAccountEntity entity = new UserAccountEntity(
                record.email(),
                record.passwordHash(),
                record.displayName(),
                record.preferredLocale(),
                record.timezone(),
                record.status(),
                record.createdAt(),
                record.updatedAt()
        );
        if (record.id() != null) {
            entity.setId(record.id());
        }
        if (record.emailVerifiedAt() != null) {
            entity.setEmailVerifiedAt(record.emailVerifiedAt());
        }
        return entity;
    }
}
