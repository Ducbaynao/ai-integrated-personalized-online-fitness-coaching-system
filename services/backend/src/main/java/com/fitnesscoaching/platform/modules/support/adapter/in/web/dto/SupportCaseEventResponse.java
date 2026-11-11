package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseEvent;

import java.time.Instant;
import java.util.UUID;

public record SupportCaseEventResponse(
        UUID id,
        UUID supportCaseId,
        String eventType,
        UUID actorId,
        String actorName,
        String note,
        String metadataJson,
        Instant occurredAt
) {
    public static SupportCaseEventResponse fromDomain(SupportCaseEvent model) {
        if (model == null) return null;
        return new SupportCaseEventResponse(
                model.id(),
                model.supportCaseId(),
                model.eventType(),
                model.actorId(),
                model.actorName(),
                model.note(),
                model.metadataJson(),
                model.occurredAt()
        );
    }
}
