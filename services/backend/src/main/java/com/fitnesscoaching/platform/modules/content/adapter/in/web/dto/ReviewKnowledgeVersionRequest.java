package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import jakarta.validation.constraints.NotNull;

public record ReviewKnowledgeVersionRequest(
        @NotNull ReviewDecision decision,
        String notes
) {
}
