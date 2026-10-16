package com.fitnesscoaching.platform.modules.content.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeEmbeddingRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeEmbeddingRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcKnowledgeEmbeddingRepository implements KnowledgeEmbeddingRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeEmbeddingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void saveBatch(List<KnowledgeEmbeddingRecord> embeddings) {
        if (embeddings == null || embeddings.isEmpty()) {
            return;
        }

        String sql = """
                INSERT INTO fitness.knowledge_embeddings (
                    id, knowledge_chunk_id, embedding_model_version_id, embedding, created_at
                ) VALUES (
                    ?, ?, ?, ?::vector, ?
                )
                ON CONFLICT (knowledge_chunk_id, embedding_model_version_id) DO UPDATE SET
                    embedding = EXCLUDED.embedding,
                    created_at = EXCLUDED.created_at
                """;

        List<Object[]> batchArgs = embeddings.stream()
                .map(e -> new Object[]{
                        e.id(),
                        e.knowledgeChunkId(),
                        e.embeddingModelVersionId(),
                        vectorToString(e.embedding()),
                        Timestamp.from(e.createdAt())
                })
                .toList();

        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Override
    public void deleteByKnowledgeVersionId(UUID versionId) {
        String sql = """
                DELETE FROM fitness.knowledge_embeddings
                WHERE knowledge_chunk_id IN (
                    SELECT id FROM fitness.knowledge_chunks WHERE knowledge_version_id = ?
                )
                """;
        jdbcTemplate.update(sql, versionId);
    }

    public static String vectorToString(float[] vector) {
        if (vector == null || vector.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder(vector.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(vector[i]);
        }
        sb.append(']');
        return sb.toString();
    }
}
