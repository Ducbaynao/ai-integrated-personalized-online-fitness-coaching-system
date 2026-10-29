package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

public record AcceptRecommendationApiRequest(
        String commandKey,
        String decisionNote,
        Integer expectedRevision
) {
    public AcceptRecommendationApiRequest(String commandKey, String decisionNote) {
        this(commandKey, decisionNote, null);
    }
}
