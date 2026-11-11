package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.support.application.model.SupportCasePage;

import java.util.Collections;
import java.util.List;

public record SupportCasePageResponse(
        List<SupportCaseSummaryResponse> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public static SupportCasePageResponse fromDomain(SupportCasePage pageModel) {
        if (pageModel == null) return new SupportCasePageResponse(Collections.emptyList(), 0, 0, 0, 0);
        List<SupportCaseSummaryResponse> items = pageModel.content() != null
                ? pageModel.content().stream().map(SupportCaseSummaryResponse::fromDomain).toList()
                : Collections.emptyList();
        return new SupportCasePageResponse(
                items,
                pageModel.totalElements(),
                pageModel.totalPages(),
                pageModel.page(),
                pageModel.size()
        );
    }
}
