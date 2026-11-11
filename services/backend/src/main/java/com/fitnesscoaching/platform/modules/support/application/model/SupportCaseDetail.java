package com.fitnesscoaching.platform.modules.support.application.model;

import java.util.List;

public record SupportCaseDetail(
        SupportCaseSummary summary,
        List<SupportCaseEvent> events
) {
}
