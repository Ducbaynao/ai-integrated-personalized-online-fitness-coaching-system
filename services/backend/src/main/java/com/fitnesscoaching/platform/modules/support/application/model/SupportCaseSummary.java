package com.fitnesscoaching.platform.modules.support.application.model;

import java.time.Instant;
import java.util.UUID;

public record SupportCaseSummary(
        UUID id,
        String caseNumber,
        UUID requesterId,
        String requesterName,
        String requesterEmail,
        String subject,
        String description,
        String priority,
        String status,
        UUID assignedTo,
        String assignedToName,
        Instant openedAt,
        Instant resolvedAt,
        Instant updatedAt
) {
}
