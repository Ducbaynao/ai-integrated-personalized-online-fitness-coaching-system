package com.fitnesscoaching.platform.modules.moderation.application.model;

import java.time.Instant;
import java.util.UUID;

public record ModerationCaseSummary(
        UUID id,
        UUID reportId,
        UUID subjectUserId,
        String subjectUserName,
        String subjectUserEmail,
        String priority,
        String status,
        UUID assignedTo,
        String assignedToName,
        Instant openedAt,
        Instant resolvedAt,
        String resolution,
        Instant updatedAt
) {
}
