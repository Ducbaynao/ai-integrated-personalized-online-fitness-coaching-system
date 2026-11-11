package com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto;

import com.fasterxml.jackson.annotation.JsonAlias;

import java.util.UUID;

public record CreateModerationCaseRequest(
        UUID reportId,
        @JsonAlias({"targetId", "userId"})
        UUID subjectUserId,
        String targetType,
        String reason,
        String priority,
        String notes
) {
}
