package com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;
import java.util.UUID;

public record ExecuteDataCorrectionRequest(
        @NotNull(message = "Support case ID is required")
        UUID supportCaseId,
        @NotBlank(message = "Target type is required (e.g. MEASUREMENT, USER_PROFILE)")
        String targetType,
        @NotNull(message = "Target ID is required")
        UUID targetId,
        @NotBlank(message = "Reason is mandatory for data correction")
        String reason,
        @NotNull(message = "Proposed data is required")
        Map<String, Object> proposedData,
        @NotNull(message = "Privileged access request ID is required")
        UUID privilegedAccessRequestId,
        String stepUpPassword,
        UUID stepUpAuthenticationId
) {
}
