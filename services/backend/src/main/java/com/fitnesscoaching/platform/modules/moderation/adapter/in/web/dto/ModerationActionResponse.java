package com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationActionModel;

import java.time.Instant;
import java.util.UUID;

public record ModerationActionResponse(
        UUID id,
        UUID moderationCaseId,
        String actionType,
        UUID targetUserId,
        String targetUserName,
        UUID performedBy,
        String performedByName,
        String reason,
        String metadataJson,
        Instant occurredAt
) {
    @JsonProperty("performedByAdminId")
    public UUID getPerformedByAdminId() {
        return performedBy;
    }

    @JsonProperty("performedByAdminName")
    public String getPerformedByAdminName() {
        return performedByName;
    }

    @JsonProperty("performedAt")
    public Instant getPerformedAt() {
        return occurredAt;
    }

    public static ModerationActionResponse fromDomain(ModerationActionModel model) {
        if (model == null) return null;
        return new ModerationActionResponse(
                model.id(),
                model.moderationCaseId(),
                model.actionType(),
                model.targetUserId(),
                model.targetUserName(),
                model.performedBy(),
                model.performedByName(),
                model.reason(),
                model.metadataJson(),
                model.occurredAt()
        );
    }
}
