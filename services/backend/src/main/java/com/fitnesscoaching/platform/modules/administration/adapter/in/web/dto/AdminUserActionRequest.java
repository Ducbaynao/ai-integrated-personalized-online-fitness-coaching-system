package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record AdminUserActionRequest(
        @NotBlank(message = "Reason is required")
        @Size(min = 5, max = 2000, message = "Reason must be between 5 and 2000 characters")
        String reason,
        String stepUpPassword,
        UUID stepUpAuthenticationId
) {
}
