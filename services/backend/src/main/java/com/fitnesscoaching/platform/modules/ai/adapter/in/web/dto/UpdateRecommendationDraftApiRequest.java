package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import tools.jackson.databind.JsonNode;

public record UpdateRecommendationDraftApiRequest(
        Integer expectedRevision,
        String title,
        String explanation,
        JsonNode structuredPayload
) {}
