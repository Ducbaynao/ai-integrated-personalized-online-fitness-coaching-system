package com.fitnesscoaching.platform.modules.auth.application.port.out;

import com.fitnesscoaching.platform.modules.auth.domain.UserAccountRecord;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountPort {

    Optional<UserAccountRecord> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    UserAccountRecord saveAndFlush(UserAccountRecord user);

    Optional<UserAccountRecord> findById(UUID id);

    /** Read committed Account status while holding a shared row lock until session issuance commits. */
    Optional<AccountStatus> lockStatusForSessionIssue(UUID id);

    int activatePendingUser(UUID userId, Instant now);

    int updateLastLogin(UUID userId, Instant now);
}
