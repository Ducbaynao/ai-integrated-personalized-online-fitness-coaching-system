package com.fitnesscoaching.platform.modules.auth.application.port.out;

import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountPort {

    Optional<UserAccountRecord> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    UserAccountRecord saveAndFlush(UserAccountRecord user);

    Optional<UserAccountRecord> findById(UUID id);

    int activatePendingUser(UUID userId, Instant now);
}
