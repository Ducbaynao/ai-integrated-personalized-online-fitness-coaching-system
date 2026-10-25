package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import jakarta.validation.constraints.NotNull;

public record ToggleAiFeatureApiRequest(
        @NotNull(message = "enabled is required")
        Boolean enabled,
        String reason
) {}
