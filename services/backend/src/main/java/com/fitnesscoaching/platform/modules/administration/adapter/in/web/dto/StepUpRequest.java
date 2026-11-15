package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record StepUpRequest(
        @NotBlank(message = "Action code is required")
        String actionCode,

        @NotBlank(message = "Password is required")
        String password
) {
}
