package com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessModel;

import java.time.Instant;
import java.util.UUID;

public record PrivilegedAccessResponse(
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
        Instant revokedAt,
        boolean currentlyActive
) {
    public static PrivilegedAccessResponse fromDomain(PrivilegedAccessModel model, Instant now) {
        if (model == null) return null;
        return new PrivilegedAccessResponse(
                model.id(),
                model.requestedByAdminId(),
                model.requestedByName(),
                model.approvedByAdminId(),
                model.approvedByName(),
                model.targetUserId(),
                model.targetUserName(),
                model.targetResourceType(),
                model.targetResourceId(),
                model.purpose(),
                model.accessScopeJson(),
                model.reason(),
                model.relatedSupportCaseId(),
                model.relatedModerationCaseId(),
                model.status(),
                model.requestedAt(),
                model.approvedAt(),
                model.openedAt(),
                model.expiresAt(),
                model.revokedAt(),
                model.isActive(now)
        );
    }
}
