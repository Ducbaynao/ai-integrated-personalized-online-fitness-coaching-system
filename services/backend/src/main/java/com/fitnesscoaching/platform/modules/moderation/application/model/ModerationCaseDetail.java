package com.fitnesscoaching.platform.modules.moderation.application.model;

import java.util.List;

public record ModerationCaseDetail(
        ModerationCaseSummary summary,
        List<ModerationActionModel> actions
) {
}
