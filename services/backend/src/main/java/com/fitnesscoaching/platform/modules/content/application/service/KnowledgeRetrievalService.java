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
public class KnowledgeRetrievalService implements KnowledgeRetrievalUseCase {

    // Server-owned canonical allowlist of sources approved for F01 general fitness knowledge
    public static final Set<String> APPROVED_SOURCES =
            com.fitnesscoaching.platform.modules.content.domain.KnowledgeSourceIdentity.APPROVED_SOURCES;

    public static final String ODS_SOURCE_CANONICAL =
            com.fitnesscoaching.platform.modules.content.domain.KnowledgeSourceIdentity.ODS_CANONICAL;
    public static final String ODS_SOURCE_ALIAS =
            com.fitnesscoaching.platform.modules.content.domain.KnowledgeSourceIdentity.ODS_LEGACY_ALIAS;

    public static String canonicalizeSourceId(String sourceId) {
        return com.fitnesscoaching.platform.modules.content.domain.KnowledgeSourceIdentity.canonicalize(sourceId);
    }

    // Deterministic cosine similarity relevance cutoff: non-positively aligned neighbors (similarity <= 0.0) are discarded
    public static final double DEFAULT_MIN_SIMILARITY_THRESHOLD = 0.0;

    private final JdbcTemplate jdbcTemplate;
    private final EmbeddingModelPort embeddingModelPort;
    private final org.springframework.transaction.support.TransactionTemplate notSupportedTxTemplate;
    private final org.springframework.transaction.support.TransactionTemplate readOnlyTxTemplate;

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeRetrievalService(
            JdbcTemplate jdbcTemplate,
            EmbeddingModelPort embeddingModelPort,
            org.springframework.transaction.PlatformTransactionManager transactionManager
    ) {
        if (transactionManager == null) {
            throw new IllegalArgumentException("transactionManager must not be null");
        }
        this.jdbcTemplate = jdbcTemplate;
        this.embeddingModelPort = embeddingModelPort;
        this.notSupportedTxTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.notSupportedTxTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        this.readOnlyTxTemplate = new org.springframework.transaction.support.TransactionTemplate(transactionManager);
        this.readOnlyTxTemplate.setReadOnly(true);
        this.readOnlyTxTemplate.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public List<RetrievedKnowledgeChunk> retrieveActiveKnowledge(String query, List<String> allowedSourceIds, int limit) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String trimmed = query.trim();
        final String effectiveQuery = trimmed.length() > 2000 ? trimmed.substring(0, 2000) : trimmed;

        if (allowedSourceIds == null || allowedSourceIds.isEmpty()) {
            return List.of();
        }

        // Intersect client request with server-owned approved sources allowlist.
        // Client CANNOT expand scope beyond server-approved sources.
        // Canonicalize source IDs prior to scope checking so aliases are uniformly validated.
        List<String> authorizedSourceIds = allowedSourceIds.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(KnowledgeRetrievalService::canonicalizeSourceId)
                .filter(APPROVED_SOURCES::contains)
                .distinct()
                .toList();

        if (authorizedSourceIds.isEmpty()) {
            return List.of();
        }

        int effectiveLimit = Math.min(Math.max(limit, 1), 50);

        // Boundary: Remote embedding generation executed OUTSIDE database transaction.
        // Suspends any caller transaction using PROPAGATION_NOT_SUPPORTED.
        // Note on resource semantics: in standard single DataSource Spring JDBC,
        // if a caller opened a transaction prior to calling retrieval, the underlying connection
        // remains bound to the thread in suspended state. True zero-connection holding
        // is guaranteed by initiating retrieval outside any caller transaction boundaries
        // (as done in AiKnowledgeOrchestrationService and KnowledgeQueryService).
        float[] queryEmbedding = notSupportedTxTemplate.execute(status ->
                embeddingModelPort.generateEmbedding(effectiveQuery)
        );
        String vectorParam = JdbcKnowledgeEmbeddingRepository.vectorToString(queryEmbedding);

        // Query both canonical and legacy alias in database to match both current and historical documents
        List<String> querySourceIds = new ArrayList<>(authorizedSourceIds);
        if (querySourceIds.contains(ODS_SOURCE_CANONICAL) && !querySourceIds.contains(ODS_SOURCE_ALIAS)) {
            querySourceIds.add(ODS_SOURCE_ALIAS);
        }

        String inSql = String.join(",", Collections.nCopies(querySourceIds.size(), "?"));

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
                  AND (1.0 - (e.embedding <=> ?::vector)) > ?
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
        params.addAll(querySourceIds);
        params.add(embeddingModelPort.modelVersionId());
        params.add(vectorParam);
        params.add(DEFAULT_MIN_SIMILARITY_THRESHOLD);
        params.add(embeddingModelPort.modelVersionId());
        params.add(embeddingModelPort.modelVersionId());
        params.add(vectorParam);
        params.add(effectiveLimit);

        return readOnlyTxTemplate.execute(status -> executeRetrievalQuery(sql, params));
    }

    private List<RetrievedKnowledgeChunk> executeRetrievalQuery(String sql, List<Object> params) {
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            String content = rs.getString("content");
            String snippet = extractSnippet(content, 200);
            return new RetrievedKnowledgeChunk(
                    (UUID) rs.getObject("chunk_id"),
                    (UUID) rs.getObject("document_id"),
                    rs.getString("document_code"),
                    rs.getString("version_label"),
                    canonicalizeSourceId(rs.getString("source_id")),
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

    @Override
    @Transactional(readOnly = true)
    public boolean isChunkEligible(String documentCode, String versionLabel, int sectionIndex) {
        if (documentCode == null || documentCode.isBlank() || versionLabel == null || versionLabel.isBlank()) {
            return false;
        }
        String sql = """
                SELECT COUNT(*)
                FROM fitness.knowledge_chunks c
                JOIN fitness.knowledge_embeddings e ON e.knowledge_chunk_id = c.id
                JOIN fitness.knowledge_versions v ON v.id = c.knowledge_version_id
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = ?
                  AND v.version_label = ?
                  AND c.chunk_index = ?
                  AND v.status = 'ACTIVE'
                  AND v.technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'
                  AND d.deleted_at IS NULL
                  AND e.embedding_model_version_id = ?
                """;
        Integer count = jdbcTemplate.queryForObject(
                sql,
                Integer.class,
                documentCode,
                versionLabel,
                sectionIndex,
                embeddingModelPort.modelVersionId()
        );
        return count != null && count > 0;
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
