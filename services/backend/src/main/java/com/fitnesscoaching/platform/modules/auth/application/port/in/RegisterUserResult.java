package com.fitnesscoaching.platform.modules.auth.application.port.in;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record RegisterUserResult(
        UUID id,
        String email,
        String displayName,
        AccountStatus status,
        String preferredLocale,
        String timezone,
        Instant emailVerifiedAt,
        Instant createdAt
) {
}
