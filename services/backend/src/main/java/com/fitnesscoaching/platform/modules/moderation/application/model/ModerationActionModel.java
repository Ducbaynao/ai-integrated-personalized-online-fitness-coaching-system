package com.fitnesscoaching.platform.modules.moderation.application.model;

import java.time.Instant;
import java.util.UUID;

public record ModerationActionModel(
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
}
