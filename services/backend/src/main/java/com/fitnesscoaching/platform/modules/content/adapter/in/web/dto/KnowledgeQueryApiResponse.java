package com.fitnesscoaching.platform.modules.content.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryResult;

import java.util.List;
import java.util.UUID;

public record KnowledgeQueryApiResponse(
        List<CitationDto> citations
) {
    public record CitationDto(
            UUID documentId,
            String documentCode,
            String version,
            String sourceId,
            String title,
            String contentPath,
            String snippet,
            String content
    ) {
    }

    public static KnowledgeQueryApiResponse fromDomain(KnowledgeQueryResult result) {
        List<CitationDto> dtos = result.citations().stream()
                .map(c -> new CitationDto(
                        c.documentId(),
                        c.documentCode(),
                        c.versionLabel(),
                        c.sourceId(),
                        c.title(),
                        c.contentPath(),
                        c.contentSnippet(),
                        c.fullContent()
                ))
                .toList();
        return new KnowledgeQueryApiResponse(dtos);
    }
}
