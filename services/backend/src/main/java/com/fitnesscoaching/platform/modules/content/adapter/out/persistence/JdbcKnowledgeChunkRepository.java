package com.fitnesscoaching.platform.modules.content.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeChunkRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeChunk;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeChunkRepository implements KnowledgeChunkRepository {

    private final JdbcTemplate jdbcTemplate;

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

    public JdbcKnowledgeChunkRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveBatch(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
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

        jdbcTemplate.batchUpdate(sql, batchArgs);
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
