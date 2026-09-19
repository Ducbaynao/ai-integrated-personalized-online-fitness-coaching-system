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
public interface UserAccountJpaRepository extends JpaRepository<UserAccountEntity, UUID> {

    @Query(value = "SELECT * FROM fitness.users WHERE email = :email", nativeQuery = true)
    Optional<UserAccountEntity> findByEmailIgnoreCase(@Param("email") String email);

    @Query(value = "SELECT EXISTS(SELECT 1 FROM fitness.users WHERE email = :email)", nativeQuery = true)
    boolean existsByEmailIgnoreCase(@Param("email") String email);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE fitness.users SET status = 'ACTIVE', email_verified_at = :now, updated_at = :now WHERE id = :userId AND status = 'PENDING_VERIFICATION'", nativeQuery = true)
    int activatePendingUser(@Param("userId") UUID userId, @Param("now") Instant now);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "UPDATE fitness.users SET last_login_at = :now, updated_at = :now WHERE id = :userId", nativeQuery = true)
    int updateLastLogin(@Param("userId") UUID userId, @Param("now") Instant now);
}
