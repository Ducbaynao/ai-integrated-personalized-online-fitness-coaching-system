package com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record RevokePrivilegedAccessDto(
        @NotBlank(message = "Revocation reason is required")
        String reason
) {
}
