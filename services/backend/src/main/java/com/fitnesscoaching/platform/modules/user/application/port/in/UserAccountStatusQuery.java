package com.fitnesscoaching.platform.modules.user.application.port.in;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface UserAccountStatusQuery {

    Optional<AccountStatus> getAccountStatus(UUID userId);

    default boolean isAccountSessionValid(UUID userId, Instant tokenIssuedAt) {
        return getAccountStatus(userId).orElse(null) == AccountStatus.ACTIVE;
    }
}
