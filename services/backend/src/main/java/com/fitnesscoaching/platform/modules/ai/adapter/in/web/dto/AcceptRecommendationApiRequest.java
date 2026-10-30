package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.util.UUID;

public record AcceptRecommendationApiRequest(
        String commandKey,
        String decisionNote,
        Integer expectedRevision,
        UUID expectedPlanId,
        Long expectedPlanAggregateVersion,
        Boolean expectedNoActivePlan
) {
    public AcceptRecommendationApiRequest(String commandKey, String decisionNote) {
        this(commandKey, decisionNote, null, null, null, null);
    }

    public AcceptRecommendationApiRequest(String commandKey, String decisionNote, Integer expectedRevision) {
        this(commandKey, decisionNote, expectedRevision, null, null, null);
    }
}
