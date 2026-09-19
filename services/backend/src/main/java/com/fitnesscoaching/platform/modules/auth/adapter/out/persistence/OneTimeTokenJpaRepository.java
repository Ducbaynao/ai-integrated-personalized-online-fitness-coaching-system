package com.fitnesscoaching.platform.modules.auth.adapter.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface OneTimeTokenJpaRepository extends JpaRepository<OneTimeTokenEntity, UUID> {

    Optional<OneTimeTokenEntity> findByTokenHashAndPurpose(String tokenHash, String purpose);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE OneTimeTokenEntity t SET t.consumedAt = :currentTime WHERE t.id = :id AND t.consumedAt IS NULL AND t.expiresAt > :currentTime")
    int consumeToken(@Param("id") UUID id, @Param("currentTime") Instant currentTime);
}
