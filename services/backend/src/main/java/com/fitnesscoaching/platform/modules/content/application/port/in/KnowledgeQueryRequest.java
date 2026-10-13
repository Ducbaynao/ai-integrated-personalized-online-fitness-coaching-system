package com.fitnesscoaching.platform.modules.content.application.port.in;

import java.util.List;

public record KnowledgeQueryRequest(
        String query,
        List<String> allowedSourceIds,
        int limit
) {
    public KnowledgeQueryRequest {
        if (limit <= 0) {
            limit = 10;
        }
    }
}
