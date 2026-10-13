package com.fitnesscoaching.platform.modules.content.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeVersionRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeStatus;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeVersion;
import com.fitnesscoaching.platform.modules.content.domain.TechnicalReadiness;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeVersionRepository implements KnowledgeVersionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final NamedParameterJdbcTemplate namedParameterJdbcTemplate;

    public JdbcKnowledgeVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(jdbcTemplate);
    }

    private static final RowMapper<KnowledgeVersion> ROW_MAPPER = (rs, rowNum) -> new KnowledgeVersion(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("knowledge_document_id"),
            rs.getInt("version_number"),
            rs.getString("version_label"),
            KnowledgeStatus.valueOf(rs.getString("status")),
            parseTechnicalReadiness(rs.getString("technical_readiness")),
            rs.getString("hold_reason"),
            rs.getString("content_hash"),
            rs.getString("content_path"),
            rs.getObject("source_bytes") != null ? rs.getInt("source_bytes") : null,
            rs.getString("review_evidence_id"),
            rs.getString("lineage_base_document_id"),
            rs.getString("lineage_base_version"),
            rs.getString("lineage_base_hash"),
            rs.getString("content"),
            (UUID) rs.getObject("reviewed_by"),
            toInstant(rs.getTimestamp("reviewed_at")),
            (UUID) rs.getObject("published_by"),
            toInstant(rs.getTimestamp("published_at")),
            toInstant(rs.getTimestamp("source_published_at")),
            toInstant(rs.getTimestamp("activated_at")),
            toInstant(rs.getTimestamp("archived_at")),
            (UUID) rs.getObject("created_by"),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static TechnicalReadiness parseTechnicalReadiness(String value) {
        if (value == null) {
            return TechnicalReadiness.DRAFT_STAGED;
        }
        try {
            return TechnicalReadiness.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TechnicalReadiness.DRAFT_STAGED;
        }
    }

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public Optional<KnowledgeVersion> findById(UUID id) {
        String sql = "SELECT * FROM fitness.knowledge_versions WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<KnowledgeVersion> findByIdForUpdate(UUID id) {
        String sql = "SELECT * FROM fitness.knowledge_versions WHERE id = ? FOR UPDATE";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<KnowledgeVersion> findByDocumentIdAndVersionLabel(UUID documentId, String versionLabel) {
        String sql = "SELECT * FROM fitness.knowledge_versions WHERE knowledge_document_id = ? AND version_label = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, documentId, versionLabel));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<KnowledgeVersion> findByDocumentCodeAndVersionLabel(String documentCode, String versionLabel) {
        String sql = """
                SELECT v.* FROM fitness.knowledge_versions v
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE d.document_code = ? AND v.version_label = ?
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, documentCode, versionLabel));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public KnowledgeVersion save(KnowledgeVersion v) {
        UUID id = v.id() != null ? v.id() : UUID.randomUUID();
        String sql = """
                INSERT INTO fitness.knowledge_versions (
                    id, knowledge_document_id, version_number, version_label,
                    status, technical_readiness, hold_reason, content_hash,
                    content_path, source_bytes, review_evidence_id,
                    lineage_base_document_id, lineage_base_version, lineage_base_hash,
                    content, created_by, created_at
                ) VALUES (
                    ?, ?, ?, ?,
                    ?::fitness.knowledge_status, ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?
                ) RETURNING *
                """;

        Timestamp now = Timestamp.from(v.createdAt() != null ? v.createdAt() : Instant.now());
        return jdbcTemplate.queryForObject(sql, ROW_MAPPER,
                id,
                v.knowledgeDocumentId(),
                v.versionNumber(),
                v.versionLabel(),
                v.status().name(),
                v.technicalReadiness() != null ? v.technicalReadiness().name() : TechnicalReadiness.DRAFT_STAGED.name(),
                v.holdReason(),
                v.contentHash(),
                v.contentPath(),
                v.sourceBytes(),
                v.reviewEvidenceId(),
                v.lineageBaseDocumentId(),
                v.lineageBaseVersion(),
                v.lineageBaseHash(),
                v.content(),
                v.createdBy(),
                now
        );
    }

    @Override
    public int updateStatusAndPublishInfo(UUID versionId, KnowledgeStatus status, UUID publishedBy, Instant publishedAt, Instant activatedAt) {
        String sql = """
                UPDATE fitness.knowledge_versions
                SET status = ?::fitness.knowledge_status,
                    published_by = ?,
                    published_at = ?,
                    activated_at = ?
                WHERE id = ? AND status = 'DRAFT'::fitness.knowledge_status
                """;
        return jdbcTemplate.update(sql,
                status.name(),
                publishedBy,
                Timestamp.from(publishedAt),
                Timestamp.from(activatedAt),
                versionId
        );
    }

    @Override
    public int updateStatusAndArchiveInfo(UUID versionId, KnowledgeStatus status, Instant archivedAt) {
        String sql = """
                UPDATE fitness.knowledge_versions
                SET status = ?::fitness.knowledge_status,
                    archived_at = ?
                WHERE id = ? AND status = 'ACTIVE'::fitness.knowledge_status
                """;
        return jdbcTemplate.update(sql,
                status.name(),
                Timestamp.from(archivedAt),
                versionId
        );
    }

    @Override
    public void updateReviewInfo(UUID versionId, UUID reviewedBy, Instant reviewedAt) {
        String sql = """
                UPDATE fitness.knowledge_versions
                SET reviewed_by = ?,
                    reviewed_at = ?
                WHERE id = ?
                """;
        jdbcTemplate.update(sql,
                reviewedBy,
                Timestamp.from(reviewedAt),
                versionId
        );
    }

    @Override
    public List<KnowledgeVersion> findActiveBySourceIds(List<String> sourceIds, int limit) {
        if (sourceIds == null || sourceIds.isEmpty()) {
            return Collections.emptyList();
        }
        String sql = """
                SELECT v.* FROM fitness.knowledge_versions v
                JOIN fitness.knowledge_documents d ON d.id = v.knowledge_document_id
                WHERE v.status = 'ACTIVE'
                  AND d.source_id IN (:sourceIds)
                  AND d.deleted_at IS NULL
                ORDER BY d.title ASC, v.version_number DESC
                LIMIT :limit
                """;
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("sourceIds", sourceIds)
                .addValue("limit", Math.max(limit, 1));
        return namedParameterJdbcTemplate.query(sql, params, ROW_MAPPER);
    }

    @Override
    public List<KnowledgeVersion> findByDocumentId(UUID documentId) {
        String sql = "SELECT * FROM fitness.knowledge_versions WHERE knowledge_document_id = ? ORDER BY version_number DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, documentId);
    }
}
