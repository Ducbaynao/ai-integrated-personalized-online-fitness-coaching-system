package com.fitnesscoaching.platform.modules.audit.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogEntry;

import java.time.Instant;
import java.util.UUID;

public record AuditLogEntryResponse(
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
    public static AuditLogEntryResponse fromDomain(AuditLogEntry model) {
        if (model == null) return null;
        return new AuditLogEntryResponse(
                model.id(),
                model.actorUserId(),
                model.actorUserName(),
                model.actorRole(),
                model.action(),
                model.targetType(),
                model.targetId(),
                model.requestId(),
                model.ipAddress(),
                model.userAgent(),
                model.beforeDataJson(),
                model.afterDataJson(),
                model.metadataJson(),
                model.occurredAt()
        );
    }
}
