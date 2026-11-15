package com.fitnesscoaching.platform.modules.administration.application.model;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountStatusHistoryEntry(
        UUID id,
        AccountStatus fromStatus,
        AccountStatus toStatus,
        UUID changedBy,
        String changedByName,
        String reason,
        Instant changedAt
) {
}
