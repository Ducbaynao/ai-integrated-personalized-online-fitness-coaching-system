package com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseDetail;

import java.util.Collections;
import java.util.List;

public record ModerationCaseDetailResponse(
        ModerationCaseSummaryResponse summary,
        List<ModerationActionResponse> actions
) {
    public static ModerationCaseDetailResponse fromDomain(ModerationCaseDetail model) {
        if (model == null) return null;
        List<ModerationActionResponse> actionResponses = model.actions() != null
                ? model.actions().stream().map(ModerationActionResponse::fromDomain).toList()
                : Collections.emptyList();
        return new ModerationCaseDetailResponse(
                ModerationCaseSummaryResponse.fromDomain(model.summary()),
                actionResponses
        );
    }
}
