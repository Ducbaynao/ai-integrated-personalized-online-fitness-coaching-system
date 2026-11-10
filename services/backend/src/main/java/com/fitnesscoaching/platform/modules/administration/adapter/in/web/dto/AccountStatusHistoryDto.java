package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.application.model.AccountStatusHistoryEntry;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;

import java.time.Instant;
import java.util.UUID;

public record AccountStatusHistoryDto(
        UUID id,
        AccountStatus fromStatus,
        AccountStatus toStatus,
        UUID changedBy,
        String changedByName,
        String reason,
        Instant changedAt
) {
    public static AccountStatusHistoryDto fromDomain(AccountStatusHistoryEntry entry) {
        return new AccountStatusHistoryDto(
                entry.id(),
                entry.fromStatus(),
                entry.toStatus(),
                entry.changedBy(),
                entry.changedByName(),
                entry.reason(),
                entry.changedAt()
        );
    }
}
