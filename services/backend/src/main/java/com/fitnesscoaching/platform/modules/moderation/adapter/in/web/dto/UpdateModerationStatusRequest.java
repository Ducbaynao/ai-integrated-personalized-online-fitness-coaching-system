package com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateModerationStatusRequest(
        @NotBlank(message = "Status is required")
        String status,
        String resolution
) {
}
