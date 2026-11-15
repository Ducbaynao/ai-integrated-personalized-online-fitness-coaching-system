package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import jakarta.validation.constraints.NotBlank;

public record AddSupportCaseNoteRequest(
        @NotBlank(message = "Note cannot be empty")
        String note
) {
}
