package com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCasePage;

import java.util.Collections;
import java.util.List;

public record ModerationCasePageResponse(
        List<ModerationCaseSummaryResponse> content,
        long totalElements,
        int totalPages,
        int page,
        int size
) {
    public static ModerationCasePageResponse fromDomain(ModerationCasePage pageModel) {
        if (pageModel == null) return new ModerationCasePageResponse(Collections.emptyList(), 0, 0, 0, 0);
        List<ModerationCaseSummaryResponse> items = pageModel.content() != null
                ? pageModel.content().stream().map(ModerationCaseSummaryResponse::fromDomain).toList()
                : Collections.emptyList();
        return new ModerationCasePageResponse(
                items,
                pageModel.totalElements(),
                pageModel.totalPages(),
                pageModel.page(),
                pageModel.size()
        );
    }
}
