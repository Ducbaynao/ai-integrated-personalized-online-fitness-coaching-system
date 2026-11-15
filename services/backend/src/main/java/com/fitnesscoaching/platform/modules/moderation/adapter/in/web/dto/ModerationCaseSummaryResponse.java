package com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseSummary;

import java.time.Instant;
import java.util.UUID;

public record ModerationCaseSummaryResponse(
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
    @JsonProperty("targetType")
    public String getTargetType() {
        return "USER";
    }

    @JsonProperty("targetId")
    public UUID getTargetId() {
        return subjectUserId;
    }

    @JsonProperty("targetDisplayName")
    public String getTargetDisplayName() {
        return subjectUserName != null ? subjectUserName : (subjectUserEmail != null ? subjectUserEmail : (subjectUserId != null ? subjectUserId.toString() : null));
    }

    @JsonProperty("assignedToAdminId")
    public UUID getAssignedToAdminId() {
        return assignedTo;
    }

    @JsonProperty("assignedToAdminName")
    public String getAssignedToAdminName() {
        return assignedToName;
    }

    @JsonProperty("createdReason")
    public String getCreatedReason() {
        return resolution != null ? resolution : "Vi phạm tiêu chuẩn cộng đồng";
    }

    @JsonProperty("notes")
    public String getNotes() {
        return null;
    }

    public static ModerationCaseSummaryResponse fromDomain(ModerationCaseSummary model) {
        if (model == null) return null;
        return new ModerationCaseSummaryResponse(
                model.id(),
                model.reportId(),
                model.subjectUserId(),
                model.subjectUserName(),
                model.subjectUserEmail(),
                model.priority(),
                model.status(),
                model.assignedTo(),
                model.assignedToName(),
                model.openedAt(),
                model.resolvedAt(),
                model.resolution(),
                model.updatedAt()
        );
    }
}
