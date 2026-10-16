package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryRequest;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryResult;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeQueryUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class KnowledgeQueryService implements KnowledgeQueryUseCase {

    // Server-owned canonical allowlist of sources approved for F01 general fitness knowledge
    private static final Set<String> APPROVED_SOURCES = Set.of(
            "WHO-PA-SB-2020",
            "ISSN-PROTEIN-EXERCISE-2017",
            "ODS-EXERCISE-SUPPLEMENTS-2024",
            "NIDDK-WEIGHT-FACTORS-2023"
    );

    private final KnowledgeVersionRepository versionRepository;
    private final KnowledgeDocumentRepository documentRepository;
    private final KnowledgeRetrievalUseCase retrievalUseCase;

    public KnowledgeQueryService(
            KnowledgeVersionRepository versionRepository,
            KnowledgeDocumentRepository documentRepository,
            KnowledgeRetrievalUseCase retrievalUseCase
    ) {
        this.versionRepository = versionRepository;
        this.documentRepository = documentRepository;
        this.retrievalUseCase = retrievalUseCase;
    }

    @Override
    public KnowledgeQueryResult queryActiveKnowledge(KnowledgeQueryRequest request) {
        // Empty scope returns empty citations: [] -> [], never fall back to open retrieval
        if (request == null || request.allowedSourceIds() == null || request.allowedSourceIds().isEmpty()) {
            return new KnowledgeQueryResult(List.of());
        }

        // Intersect client request with server-owned approved sources allowlist.
        // Client CANNOT expand scope beyond server-approved sources.
        List<String> authorizedSourceIds = request.allowedSourceIds().stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .filter(APPROVED_SOURCES::contains)
                .distinct()
                .toList();

        if (authorizedSourceIds.isEmpty()) {
            return new KnowledgeQueryResult(List.of());
        }

        // 1. Try real vector retrieval first if a query string is present
        String query = request.query();
        if (query != null && !query.isBlank()) {
            List<RetrievedKnowledgeChunk> retrievedChunks = retrievalUseCase.retrieveActiveKnowledge(
                    query, authorizedSourceIds, request.limit()
            );

            if (!retrievedChunks.isEmpty()) {
                List<KnowledgeQueryResult.KnowledgeCitationItem> citations = retrievedChunks.stream()
                        .map(chunk -> new KnowledgeQueryResult.KnowledgeCitationItem(
                                chunk.documentId(),
                                chunk.documentCode(),
                                chunk.versionLabel(),
                                chunk.sourceId(),
                                chunk.title(),
                                chunk.contentPath(),
                                chunk.snippet(),
                                chunk.content()
                        ))
                        .toList();
                return new KnowledgeQueryResult(citations);
            }
        }

        // 2. Direct active versions query (strict fail-closed against non-ACTIVE / non-VERIFIED)
        List<KnowledgeVersion> activeVersions = versionRepository.findActiveBySourceIds(
                authorizedSourceIds, Math.min(Math.max(request.limit(), 1), 50)
        );

        if (activeVersions.isEmpty()) {
            return new KnowledgeQueryResult(List.of());
        }

        // Strict application-level filter:
        // Must be ACTIVE and must have TECHNICAL_VERIFICATION_PASSED (fail-closed against HELD_AS_DRAFT or DRAFT_STAGED)
        List<KnowledgeVersion> strictlyEligible = activeVersions.stream()
                .filter(v -> v.status() == KnowledgeStatus.ACTIVE)
                .filter(v -> v.technicalReadiness() == TechnicalReadiness.TECHNICAL_VERIFICATION_PASSED)
                .toList();

        if (strictlyEligible.isEmpty()) {
            return new KnowledgeQueryResult(List.of());
        }

        List<UUID> docIds = strictlyEligible.stream()
                .map(KnowledgeVersion::knowledgeDocumentId)
                .distinct()
                .toList();

        Map<UUID, KnowledgeDocument> docMap = docIds.stream()
                .map(documentRepository::findById)
                .flatMap(java.util.Optional::stream)
                .collect(Collectors.toMap(KnowledgeDocument::id, d -> d));

        List<KnowledgeQueryResult.KnowledgeCitationItem> citations = new ArrayList<>();
        for (KnowledgeVersion version : strictlyEligible) {
            KnowledgeDocument doc = docMap.get(version.knowledgeDocumentId());
            if (doc != null && doc.deletedAt() == null && APPROVED_SOURCES.contains(doc.sourceId())) {
                String snippet = extractSnippet(version.content(), 200);
                citations.add(new KnowledgeQueryResult.KnowledgeCitationItem(
                        doc.id(),
                        doc.documentCode(),
                        version.versionLabel(),
                        doc.sourceId(),
                        doc.title(),
                        version.contentPath() != null ? version.contentPath() : "",
                        snippet,
                        version.content()
                ));
            }
        }

        return new KnowledgeQueryResult(citations);
    }

    private String extractSnippet(String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return "";
        }
        String clean = content.replaceAll("[#*`>\\[\\]]", " ").replaceAll("\\s+", " ").trim();
        if (clean.length() <= maxLength) {
            return clean;
        }
        return clean.substring(0, maxLength) + "...";
    }
}
