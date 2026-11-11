package com.fitnesscoaching.platform.modules.audit.application.model;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntry(
        long id,
        UUID actorUserId,
        String actorUserName,
        String actorRole,
        String action,
        String targetType,
        UUID targetId,
        UUID requestId,
        String ipAddress,
        String userAgent,
        String beforeDataJson,
        String afterDataJson,
        String metadataJson,
        Instant occurredAt
) {
}
