package com.fitnesscoaching.platform.modules.content.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeDocumentRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeDocument;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeDocumentRepository implements KnowledgeDocumentRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeDocumentRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<KnowledgeDocument> ROW_MAPPER = (rs, rowNum) -> new KnowledgeDocument(
            (UUID) rs.getObject("id"),
            rs.getString("document_code"),
            rs.getString("title"),
            rs.getString("source_id"),
            rs.getString("source_name"),
            rs.getString("source_url"),
            rs.getString("author"),
            rs.getString("document_type"),
            rs.getString("language"),
            (UUID) rs.getObject("created_by"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at")),
            toInstant(rs.getTimestamp("deleted_at"))
    );

    private static java.time.Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public Optional<KnowledgeDocument> findById(UUID id) {
        String sql = "SELECT * FROM fitness.knowledge_documents WHERE id = ? AND deleted_at IS NULL";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<KnowledgeDocument> findByDocumentCode(String documentCode) {
        String sql = "SELECT * FROM fitness.knowledge_documents WHERE document_code = ? AND deleted_at IS NULL";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, documentCode));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public KnowledgeDocument save(KnowledgeDocument doc) {
        UUID id = doc.id() != null ? doc.id() : UUID.randomUUID();
        String sql = """
                INSERT INTO fitness.knowledge_documents (
                    id, document_code, title, source_id, source_name, source_url,
                    author, document_type, language, created_by, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (document_code) DO UPDATE SET
                    title = EXCLUDED.title,
                    source_id = EXCLUDED.source_id,
                    source_name = EXCLUDED.source_name,
                    source_url = EXCLUDED.source_url,
                    author = EXCLUDED.author,
                    document_type = EXCLUDED.document_type,
                    language = EXCLUDED.language,
                    updated_at = EXCLUDED.updated_at
                RETURNING *
                """;

        Timestamp now = Timestamp.from(doc.createdAt() != null ? doc.createdAt() : java.time.Instant.now());
        return jdbcTemplate.queryForObject(sql, ROW_MAPPER,
                id,
                doc.documentCode(),
                doc.title(),
                doc.sourceId() != null ? doc.sourceId() : "SYSTEM",
                doc.sourceName(),
                doc.sourceUrl(),
                doc.author(),
                doc.documentType() != null ? doc.documentType() : "ARTICLE",
                doc.language() != null ? doc.language() : "vi",
                doc.createdBy(),
                now,
                now
        );
    }

    @Override
    public List<KnowledgeDocument> findAllActive() {
        String sql = """
                SELECT DISTINCT d.* FROM fitness.knowledge_documents d
                JOIN fitness.knowledge_versions v ON v.knowledge_document_id = d.id
                WHERE v.status = 'ACTIVE' AND d.deleted_at IS NULL
                ORDER BY d.title ASC
                """;
        return jdbcTemplate.query(sql, ROW_MAPPER);
    }

    @Override
    public List<KnowledgeDocument> findAll(int offset, int limit) {
        String sql = "SELECT * FROM fitness.knowledge_documents WHERE deleted_at IS NULL ORDER BY created_at DESC LIMIT ? OFFSET ?";
        return jdbcTemplate.query(sql, ROW_MAPPER, Math.max(limit, 1), Math.max(offset, 0));
    }
}
