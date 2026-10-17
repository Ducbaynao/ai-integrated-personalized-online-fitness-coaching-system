package com.fitnesscoaching.platform.modules.content.adapter.out.persistence;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ContentConflictException;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeChunkRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    private static final RowMapper<KnowledgeChunk> CHUNK_ROW_MAPPER = (rs, rowNum) -> new KnowledgeChunk(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("knowledge_version_id"),
            rs.getInt("chunk_index"),
            rs.getString("heading_path"),
            rs.getString("content"),
            rs.getObject("token_count") != null ? rs.getInt("token_count") : null,
            rs.getString("metadata"),
            toInstant(rs.getTimestamp("created_at"))
    );

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void saveBatch(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }

        validateNoMutationsToCitedChunks(chunks);

        String sql = """
                INSERT INTO fitness.knowledge_chunks (
                    id, knowledge_version_id, chunk_index, heading_path, content, token_count, metadata, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?::jsonb, ?
                )
                ON CONFLICT (knowledge_version_id, chunk_index) DO UPDATE SET
                    heading_path = EXCLUDED.heading_path,
                    content = EXCLUDED.content,
                    token_count = EXCLUDED.token_count,
                    metadata = EXCLUDED.metadata,
                    created_at = EXCLUDED.created_at
                WHERE NOT EXISTS (
                    SELECT 1 FROM fitness.ai_run_knowledge_chunks rc
                    WHERE rc.knowledge_chunk_id = fitness.knowledge_chunks.id
                      AND (
                          fitness.knowledge_chunks.content <> EXCLUDED.content
                          OR fitness.knowledge_chunks.heading_path IS DISTINCT FROM EXCLUDED.heading_path
                          OR (fitness.knowledge_chunks.metadata->>'chunkHash') IS DISTINCT FROM (EXCLUDED.metadata->>'chunkHash')
                          OR (fitness.knowledge_chunks.metadata->>'chunkHash') IS NULL
                          OR (EXCLUDED.metadata->>'chunkHash') IS NULL
                          OR (fitness.knowledge_chunks.metadata->>'sourceId') IS DISTINCT FROM (EXCLUDED.metadata->>'sourceId')
                          OR (fitness.knowledge_chunks.metadata->>'sourcePath') IS DISTINCT FROM (EXCLUDED.metadata->>'sourcePath')
                          OR (fitness.knowledge_chunks.metadata->>'heading') IS DISTINCT FROM (EXCLUDED.metadata->>'heading')
                          OR (fitness.knowledge_chunks.metadata->>'documentCode') IS DISTINCT FROM (EXCLUDED.metadata->>'documentCode')
                          OR (fitness.knowledge_chunks.metadata->>'versionLabel') IS DISTINCT FROM (EXCLUDED.metadata->>'versionLabel')
                          OR (fitness.knowledge_chunks.metadata->>'contentHash') IS DISTINCT FROM (EXCLUDED.metadata->>'contentHash')
                          OR (fitness.knowledge_chunks.metadata->>'lineageBaseHash') IS DISTINCT FROM (EXCLUDED.metadata->>'lineageBaseHash')
                          OR (fitness.knowledge_chunks.metadata->>'lineageBaseDocumentId') IS DISTINCT FROM (EXCLUDED.metadata->>'lineageBaseDocumentId')
                          OR (fitness.knowledge_chunks.metadata->>'lineageBaseVersion') IS DISTINCT FROM (EXCLUDED.metadata->>'lineageBaseVersion')
                          OR (fitness.knowledge_chunks.metadata->>'sectionIndex') IS DISTINCT FROM (EXCLUDED.metadata->>'sectionIndex')
                      )
                )
                """;

        List<Object[]> batchArgs = chunks.stream()
                .map(c -> new Object[]{
                        c.id(),
                        c.knowledgeVersionId(),
                        c.chunkIndex(),
                        c.headingPath(),
                        c.content(),
                        c.tokenCount(),
                        c.metadataJson() != null ? c.metadataJson() : "{}",
                        Timestamp.from(c.createdAt())
                })
                .toList();

        int[] updateCounts = jdbcTemplate.batchUpdate(sql, batchArgs);
        for (int count : updateCounts) {
            if (count == 0) {
                throw new ContentConflictException("Knowledge chunk update rejected: cited chunk content, locator or provenance cannot be mutated");
            }
        }
    }

    public static final List<String> IMMUTABLE_PROVENANCE_FIELDS = List.of(
            "chunkHash",
            "documentCode",
            "versionLabel",
            "sourceId",
            "sourcePath",
            "contentHash",
            "lineageBaseDocumentId",
            "lineageBaseVersion",
            "lineageBaseHash",
            "heading",
            "sectionIndex"
    );

    private void validateNoMutationsToCitedChunks(List<KnowledgeChunk> chunks) {
        UUID versionId = chunks.get(0).knowledgeVersionId();
        String sql = """
                SELECT DISTINCT c.id, c.chunk_index, c.heading_path, c.content, c.metadata::text AS metadata_json
                FROM fitness.knowledge_chunks c
                JOIN fitness.ai_run_knowledge_chunks rc ON rc.knowledge_chunk_id = c.id
                WHERE c.knowledge_version_id = ?
                """;

        List<CitedChunkInfo> citedChunks = jdbcTemplate.query(sql, (rs, rowNum) -> new CitedChunkInfo(
                (UUID) rs.getObject("id"),
                rs.getInt("chunk_index"),
                rs.getString("heading_path"),
                rs.getString("content"),
                rs.getString("metadata_json")
        ), versionId);

        if (citedChunks.isEmpty()) {
            return;
        }

        Map<Integer, KnowledgeChunk> incomingByIndex = chunks.stream()
                .collect(Collectors.toMap(KnowledgeChunk::chunkIndex, c -> c, (c1, c2) -> c1));

        for (CitedChunkInfo cited : citedChunks) {
            KnowledgeChunk incoming = incomingByIndex.get(cited.chunkIndex);
            if (incoming == null) {
                // Incoming chunks count is smaller; cited chunk is not being overwritten,
                // will be safely preserved by deleteUnreferencedChunksBeyondIndex without embeddings.
                continue;
            }

            // 1. Content equality check
            if (!Objects.equals(incoming.content(), cited.content)) {
                throw new ContentConflictException("Cannot mutate content of cited chunk at index "
                        + cited.chunkIndex + " for version " + versionId);
            }

            // 2. Heading path (locator) equality check
            if (!Objects.equals(incoming.headingPath(), cited.headingPath)) {
                throw new ContentConflictException("Cannot mutate heading_path locator of cited chunk at index "
                        + cited.chunkIndex + " for version " + versionId);
            }

            // 3. Provenance and locator metadata check (fail-closed if metadata or any immutable field is missing/null/mutated)
            if (incoming.metadataJson() == null || incoming.metadataJson().isBlank()) {
                throw new ContentConflictException("Missing or unverified chunkHash for cited chunk at index "
                        + cited.chunkIndex + " for version " + versionId);
            }

            JsonNode incomingNode;
            JsonNode citedNode;
            try {
                incomingNode = objectMapper.readTree(incoming.metadataJson());
                citedNode = (cited.metadataJson != null && !cited.metadataJson.isBlank())
                        ? objectMapper.readTree(cited.metadataJson)
                        : null;
            } catch (Exception e) {
                throw new ContentConflictException("Invalid metadata JSON for cited chunk at index "
                        + cited.chunkIndex + " for version " + versionId, e);
            }

            if (incomingNode == null || !incomingNode.isObject()) {
                throw new ContentConflictException("Missing or unverified chunkHash for cited chunk at index "
                        + cited.chunkIndex + " for version " + versionId);
            }

            for (String field : IMMUTABLE_PROVENANCE_FIELDS) {
                JsonNode citedVal = (citedNode != null) ? citedNode.get(field) : null;
                JsonNode incomingVal = incomingNode.get(field);

                if ("chunkHash".equals(field)) {
                    if (incomingVal == null || incomingVal.isNull() || incomingVal.asText().isBlank()) {
                        throw new ContentConflictException("Missing or unverified chunkHash for cited chunk at index "
                                + cited.chunkIndex + " for version " + versionId);
                    }
                }

                if (citedVal != null && !citedVal.isNull() && !citedVal.asText().isBlank()) {
                    if (incomingVal == null || incomingVal.isNull() || !Objects.equals(incomingVal.asText(), citedVal.asText())) {
                        throw new ContentConflictException("Cannot mutate provenance field '" + field + "' of cited chunk at index "
                                + cited.chunkIndex + " for version " + versionId);
                    }
                }
            }
        }
    }

    private record CitedChunkInfo(UUID id, int chunkIndex, String headingPath, String content, String metadataJson) {}

    @Override
    public void deleteUnreferencedChunksBeyondIndex(UUID versionId, int activeChunkCount) {
        String sql = """
                DELETE FROM fitness.knowledge_chunks
                WHERE knowledge_version_id = ?
                  AND chunk_index >= ?
                  AND id NOT IN (SELECT knowledge_chunk_id FROM fitness.ai_run_knowledge_chunks)
                """;
        jdbcTemplate.update(sql, versionId, activeChunkCount);
    }

    @Override
    public void deleteByKnowledgeVersionId(UUID versionId) {
        jdbcTemplate.update("DELETE FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?", versionId);
    }

    @Override
    public List<KnowledgeChunk> findByKnowledgeVersionId(UUID versionId) {
        String sql = "SELECT * FROM fitness.knowledge_chunks WHERE knowledge_version_id = ? ORDER BY chunk_index ASC";
        return jdbcTemplate.query(sql, CHUNK_ROW_MAPPER, versionId);
    }

    @Override
    public Optional<KnowledgeChunk> findByDocumentCodeAndVersionAndIndex(String documentCode, String versionLabel, int chunkIndex) {
        String sql = """
                SELECT c.*
                FROM fitness.knowledge_chunks c
                JOIN fitness.knowledge_versions v ON v.id = c.knowledge_version_id
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = ?
                  AND v.version_label = ?
                  AND c.chunk_index = ?
                  AND v.status = 'ACTIVE'
                  AND v.technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'
                  AND d.deleted_at IS NULL
                """;
        List<KnowledgeChunk> results = jdbcTemplate.query(sql, CHUNK_ROW_MAPPER, documentCode, versionLabel, chunkIndex);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    @Override
    public Optional<KnowledgeChunk> findById(UUID chunkId) {
        String sql = """
                SELECT c.*
                FROM fitness.knowledge_chunks c
                JOIN fitness.knowledge_versions v ON v.id = c.knowledge_version_id
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE c.id = ?
                  AND v.status = 'ACTIVE'
                  AND v.technical_readiness = 'TECHNICAL_VERIFICATION_PASSED'
                  AND d.deleted_at IS NULL
                """;
        List<KnowledgeChunk> results = jdbcTemplate.query(sql, CHUNK_ROW_MAPPER, chunkId);
        return results.isEmpty() ? Optional.empty() : Optional.of(results.get(0));
    }

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }
}
