package com.fitnesscoaching.platform.modules.support.correction.application.model;

import java.util.List;

public record DataCorrectionPage(
        List<DataCorrectionModel> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
}
