package com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionPage;

import java.util.Collections;
import java.util.List;

public record DataCorrectionPageResponse(
        List<DataCorrectionResponse> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public static DataCorrectionPageResponse fromDomain(DataCorrectionPage pageModel) {
        if (pageModel == null) return new DataCorrectionPageResponse(Collections.emptyList(), 0, 0, 0, 0);
        List<DataCorrectionResponse> items = pageModel.content() != null
                ? pageModel.content().stream().map(DataCorrectionResponse::fromDomain).toList()
                : Collections.emptyList();
        return new DataCorrectionPageResponse(
                items,
                pageModel.totalElements(),
                pageModel.totalPages(),
                pageModel.page(),
                pageModel.size()
        );
    }
}
