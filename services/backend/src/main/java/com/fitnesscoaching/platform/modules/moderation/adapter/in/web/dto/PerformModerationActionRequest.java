package com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;
import java.util.UUID;

public record PerformModerationActionRequest(
        @NotBlank(message = "Action type is required")
        String actionType,
        UUID targetUserId,
        @NotBlank(message = "Reason is mandatory for moderation actions")
        String reason,
        String stepUpPassword,
        UUID stepUpAuthenticationId,
        Map<String, Object> metadata
) {
}
