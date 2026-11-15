package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record UpdateSupportCaseStatusRequest(
        @NotBlank(message = "Status is required")
        String status,
        String note
) {
}
