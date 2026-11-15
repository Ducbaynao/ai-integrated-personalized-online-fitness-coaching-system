package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.UUID;

public record CreateSupportCaseRequest(
        UUID requesterId,
        @NotBlank(message = "Subject is required")
        @Size(max = 200, message = "Subject cannot exceed 200 characters")
        String subject,
        @NotBlank(message = "Description is required")
        String description,
        String priority,
        String category
) {
}
