package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

public record AcceptRecommendationApiRequest(
        String commandKey,
        String decisionNote
) {}
