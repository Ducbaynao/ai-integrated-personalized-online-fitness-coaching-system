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
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenEntity, UUID> {

    Optional<RefreshTokenEntity> findByTokenHash(String tokenHash);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenEntity t SET t.revokedAt = :now, t.revokeReason = 'ROTATED' " +
            "WHERE t.id = :id AND t.revokedAt IS NULL AND t.expiresAt > :now")
    int revokeForRotation(@Param("id") UUID id, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenEntity t SET t.revokedAt = :now, t.revokeReason = 'LOGOUT' " +
            "WHERE t.id = :id AND t.userId = :userId AND t.revokedAt IS NULL AND t.expiresAt > :now")
    int revokeForLogout(
            @Param("id") UUID id,
            @Param("userId") UUID userId,
            @Param("now") Instant now
    );

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("UPDATE RefreshTokenEntity t SET t.revokedAt = :now, t.revokeReason = :reason " +
            "WHERE t.userId = :userId AND t.revokedAt IS NULL")
    int revokeActiveForUser(
            @Param("userId") UUID userId,
            @Param("now") Instant now,
            @Param("reason") String reason
    );
}
