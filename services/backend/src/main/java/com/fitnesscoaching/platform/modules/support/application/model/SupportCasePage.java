package com.fitnesscoaching.platform.modules.support.application.model;

import java.util.List;

public record SupportCasePage(
        List<SupportCaseSummary> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
}
