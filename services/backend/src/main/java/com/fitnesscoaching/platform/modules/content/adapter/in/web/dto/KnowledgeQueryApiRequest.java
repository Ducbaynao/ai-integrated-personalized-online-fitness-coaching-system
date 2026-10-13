package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryRequest;

import java.util.List;

public record KnowledgeQueryApiRequest(
        String query,
        List<String> allowedSourceIds,
        Integer limit
) {
    public KnowledgeQueryRequest toDomain() {
        return new KnowledgeQueryRequest(
                query,
                allowedSourceIds,
                limit != null ? limit : 10
        );
    }
}
