package com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

public record RequestPrivilegedAccessDto(
        UUID targetUserId,
        String targetResourceType,
        UUID targetResourceId,
        @NotBlank(message = "Purpose is required (SUPPORT, MODERATION, SECURITY, LEGAL_COMPLIANCE, DATA_CORRECTION)")
        String purpose,
        Object accessScope,
        @NotBlank(message = "Reason is mandatory for privileged data access")
        String reason,
        @Min(value = 1, message = "Duration must be at least 1 minute")
        @Max(value = 480, message = "Duration cannot exceed 480 minutes")
        Integer durationMinutes,
        UUID relatedSupportCaseId,
        UUID relatedModerationCaseId,
        String stepUpPassword,
        UUID stepUpAuthenticationId
) {
}
