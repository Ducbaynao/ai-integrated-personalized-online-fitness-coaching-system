package com.fitnesscoaching.platform.modules.administration.privileged.application.model;

import java.time.Instant;
import java.util.UUID;

public record PrivilegedAccessModel(
        UUID id,
        UUID requestedByAdminId,
        String requestedByName,
        UUID approvedByAdminId,
        String approvedByName,
        UUID targetUserId,
        String targetUserName,
        String targetResourceType,
        UUID targetResourceId,
        String purpose,
        String accessScopeJson,
        String reason,
        UUID relatedSupportCaseId,
        UUID relatedModerationCaseId,
        String status,
        Instant requestedAt,
        Instant approvedAt,
        Instant openedAt,
        Instant expiresAt,
        Instant revokedAt
) {
    public boolean isActive(Instant now) {
        return "ACTIVE".equalsIgnoreCase(status) &&
                openedAt != null &&
                (expiresAt == null || expiresAt.isAfter(now)) &&
                revokedAt == null;
    }
}
