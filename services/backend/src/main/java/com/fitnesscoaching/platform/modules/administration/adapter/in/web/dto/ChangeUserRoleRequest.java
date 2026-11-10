package com.fitnesscoaching.platform.modules.administration.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record ChangeUserRoleRequest(
        @NotBlank(message = "Role code is required")
        String roleCode,

        @NotBlank(message = "Action is required")
        @Pattern(regexp = "(?i)ASSIGN|REVOKE", message = "Action must be either ASSIGN or REVOKE")
        String action,

        @NotBlank(message = "Reason is required")
        @Size(min = 5, max = 2000, message = "Reason must be between 5 and 2000 characters")
        String reason,

        String stepUpPassword,
        UUID stepUpAuthenticationId
) {
}
