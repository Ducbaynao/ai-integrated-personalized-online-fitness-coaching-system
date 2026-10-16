package com.fitnesscoaching.platform.modules.content.application.service;

import com.fitnesscoaching.platform.modules.content.adapter.out.persistence.JdbcKnowledgeEmbeddingRepository;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.out.EmbeddingModelPort;
import com.fitnesscoaching.platform.modules.content.domain.RetrievedKnowledgeChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class KnowledgeRetrievalService implements KnowledgeRetrievalUseCase {

    // Server-owned canonical allowlist of sources approved for F01 general fitness knowledge
    public static final Set<String> APPROVED_SOURCES = Set.of(
            "WHO-PA-SB-2020",
            "ISSN-PROTEIN-EXERCISE-2017",
            "ODS-EXERCISE-SUPPLEMENTS-2024",
            "NIDDK-WEIGHT-FACTORS-2023"
    );

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModelPort embeddingModelPort;

    public KnowledgeRetrievalService(JdbcTemplate jdbcTemplate, EmbeddingModelPort embeddingModelPort) {
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModelPort = embeddingModelPort;
    }

    @Override
    public List<RetrievedKnowledgeChunk> retrieveActiveKnowledge(String query, List<String> allowedSourceIds, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String sanitizedQuery = query.trim();
        if (sanitizedQuery.length() > 2000) {
            sanitizedQuery = sanitizedQuery.substring(0, 2000);
        }

        if (allowedSourceIds == null || allowedSourceIds.isEmpty()) {
            return List.of();
        }

        // Intersect client request with server-owned approved sources allowlist.
        // Client CANNOT expand scope beyond server-approved sources.
        List<String> authorizedSourceIds = allowedSourceIds.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .filter(APPROVED_SOURCES::contains)
                .distinct()
                .toList();

        if (authorizedSourceIds.isEmpty()) {
            return List.of();
        }

        int effectiveLimit = Math.min(Math.max(limit, 1), 50);

        // Generate query embedding in baseline embedding space
        float[] queryEmbedding = embeddingModelPort.generateEmbedding(sanitizedQuery);
        String vectorParam = JdbcKnowledgeEmbeddingRepository.vectorToString(queryEmbedding);

        String inSql = String.join(",", Collections.nCopies(authorizedSourceIds.size(), "?"));

        String sql = """
                SELECT
                    c.id AS chunk_id,
                    c.chunk_index,
                    c.heading_path,
                    c.content,
                    d.id AS document_id,
                    d.document_code,
                    d.source_id,
                    d.title AS document_title,
                    v.version_label,
                    v.content_path,
                    (1.0 - (e.embedding <=> ?::vector)) AS similarity
                FROM fitness.knowledge_chunks c
                JOIN fitness.knowledge_embeddings e ON e.knowledge_chunk_id = c.id
                JOIN fitness.knowledge_versions v ON v.id = c.knowledge_version_id
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE v.status = 'ACTIVE'
                  AND v.technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'
                  AND d.deleted_at IS NULL
                  AND d.source_id IN (%s)
                  AND e.embedding_model_version_id = ?
                  AND EXISTS (
                      SELECT 1 FROM fitness.knowledge_processing_runs pr
                      WHERE pr.knowledge_version_id = v.id
                        AND pr.status = 'SUCCEEDED'
                        AND (pr.model_version_id = ? OR (pr.statistics->>'embedding_model_version_id')::uuid = ?)
                        AND (pr.statistics->>'index_ready')::boolean = true
                  )
                ORDER BY e.embedding <=> ?::vector ASC
                LIMIT ?
                """.formatted(inSql);

        List<Object> params = new ArrayList<>();
        params.add(vectorParam);
        params.addAll(authorizedSourceIds);
        params.add(embeddingModelPort.modelVersionId());
        params.add(embeddingModelPort.modelVersionId());
        params.add(embeddingModelPort.modelVersionId());
        params.add(vectorParam);
        params.add(effectiveLimit);

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String content = rs.getString("content");
            String snippet = extractSnippet(content, 200);
            return new RetrievedKnowledgeChunk(
                    (UUID) rs.getObject("chunk_id"),
                    (UUID) rs.getObject("document_id"),
                    rs.getString("document_code"),
                    rs.getString("version_label"),
                    rs.getString("source_id"),
                    rs.getString("document_title"),
                    rs.getString("content_path") != null ? rs.getString("content_path") : "",
                    rs.getString("heading_path") != null ? rs.getString("heading_path") : "",
                    rs.getInt("chunk_index"),
                    content,
                    snippet,
                    rs.getDouble("similarity")
            );
        }, params.toArray());
    }

    private static String extractSnippet(String content, int maxLength) {
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
