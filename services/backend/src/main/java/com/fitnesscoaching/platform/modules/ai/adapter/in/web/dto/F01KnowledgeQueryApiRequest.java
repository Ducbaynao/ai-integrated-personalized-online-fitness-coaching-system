package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import java.util.UUID;

public record F01KnowledgeQueryApiRequest(
        String question,
        String language,
        UUID exerciseId,
        UUID correlationId,
        UUID studentId,
        Boolean includePersonalContext
) {
    public F01KnowledgeQueryApiRequest(String question, String language, UUID exerciseId, UUID correlationId) {
        this(question, language, exerciseId, correlationId, null, false);
    }
}
