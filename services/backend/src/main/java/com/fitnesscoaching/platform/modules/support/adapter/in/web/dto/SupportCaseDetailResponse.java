package com.fitnesscoaching.platform.modules.support.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseDetail;

import java.util.Collections;
import java.util.List;

public record SupportCaseDetailResponse(
        SupportCaseSummaryResponse summary,
        List<SupportCaseEventResponse> events
) {
    public static SupportCaseDetailResponse fromDomain(SupportCaseDetail model) {
        if (model == null) return null;
        List<SupportCaseEventResponse> eventResponses = model.events() != null
                ? model.events().stream().map(SupportCaseEventResponse::fromDomain).toList()
                : Collections.emptyList();
        return new SupportCaseDetailResponse(
                SupportCaseSummaryResponse.fromDomain(model.summary()),
                eventResponses
        );
    }
}
