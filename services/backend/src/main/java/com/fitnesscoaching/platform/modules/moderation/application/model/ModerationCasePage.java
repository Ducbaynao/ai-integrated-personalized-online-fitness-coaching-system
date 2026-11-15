package com.fitnesscoaching.platform.modules.moderation.application.model;

import java.util.List;

public record ModerationCasePage(
        List<ModerationCaseSummary> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
}
