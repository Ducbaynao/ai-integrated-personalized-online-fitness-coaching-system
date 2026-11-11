package com.fitnesscoaching.platform.modules.support.application.model;

import java.time.Instant;
import java.util.UUID;

public record SupportCaseEvent(
        UUID id,
        UUID supportCaseId,
        String eventType,
        UUID actorId,
        String actorName,
        String note,
        String metadataJson,
        Instant occurredAt
) {
}
