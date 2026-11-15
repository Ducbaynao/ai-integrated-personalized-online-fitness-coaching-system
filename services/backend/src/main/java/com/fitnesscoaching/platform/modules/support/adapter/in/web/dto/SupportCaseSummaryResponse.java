package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseSummary;

import java.time.Instant;
import java.util.UUID;

public record SupportCaseSummaryResponse(
        UUID id,
        String caseNumber,
        UUID requesterId,
        String requesterName,
        String requesterEmail,
        String subject,
        String description,
        String priority,
        String status,
        @JsonProperty("assignedToAdminId")
        UUID assignedToAdminId,
        @JsonProperty("assignedToAdminName")
        String assignedToAdminName,
        Instant openedAt,
        Instant resolvedAt,
        Instant updatedAt
) {
    public UUID assignedTo() {
        return assignedToAdminId;
    }

    public String assignedToName() {
        return assignedToAdminName;
    }

    public static SupportCaseSummaryResponse fromDomain(SupportCaseSummary model) {
        if (model == null) return null;
        return new SupportCaseSummaryResponse(
                model.id(),
                model.caseNumber(),
                model.requesterId(),
                model.requesterName(),
                model.requesterEmail(),
                model.subject(),
                model.description(),
                model.priority(),
                model.status(),
                model.assignedTo(),
                model.assignedToName(),
                model.openedAt(),
                model.resolvedAt(),
                model.updatedAt()
        );
    }
}
