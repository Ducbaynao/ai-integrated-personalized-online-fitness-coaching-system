package com.fitnesscoaching.platform.modules.content.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeReviewRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeReviewRecord;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeReviewRepository implements KnowledgeReviewRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeReviewRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<KnowledgeReviewRecord> ROW_MAPPER = (rs, rowNum) -> new KnowledgeReviewRecord(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("knowledge_version_id"),
            (UUID) rs.getObject("reviewer_id"),
            rs.getString("reviewer_identity"),
            rs.getString("reviewer_role"),
            ReviewDecision.valueOf(rs.getString("decision")),
            rs.getString("content_hash"),
            rs.getString("notes"),
            toInstant(rs.getTimestamp("reviewed_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public KnowledgeReviewRecord save(KnowledgeReviewRecord record) {
        UUID id = record.id() != null ? record.id() : UUID.randomUUID();
        String sql = """
                INSERT INTO fitness.knowledge_version_reviews (
                    id, knowledge_version_id, reviewer_id, reviewer_identity, reviewer_role, decision, content_hash, notes, reviewed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                RETURNING *
                """;
        Timestamp now = Timestamp.from(record.reviewedAt() != null ? record.reviewedAt() : Instant.now());
        return jdbcTemplate.queryForObject(sql, ROW_MAPPER,
                id,
                record.knowledgeVersionId(),
                record.reviewerId(),
                record.reviewerIdentity(),
                record.reviewerRole(),
                record.decision().name(),
                record.contentHash(),
                record.notes(),
                now
        );
    }

    @Override
    public List<KnowledgeReviewRecord> findByVersionId(UUID versionId) {
        String sql = "SELECT * FROM fitness.knowledge_version_reviews WHERE knowledge_version_id = ? ORDER BY reviewed_at DESC, review_sequence DESC";
        return jdbcTemplate.query(sql, ROW_MAPPER, versionId);
    }

    @Override
    public Optional<KnowledgeReviewRecord> findLatestReview(UUID versionId) {
        String sql = """
                SELECT * FROM fitness.knowledge_version_reviews
                WHERE knowledge_version_id = ?
                ORDER BY reviewed_at DESC, review_sequence DESC
                LIMIT 1
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, versionId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean hasEffectiveApprovedReview(UUID versionId, String expectedContentHash) {
        Optional<KnowledgeReviewRecord> latestOpt = findLatestReview(versionId);
        if (latestOpt.isEmpty()) {
            return false;
        }
        KnowledgeReviewRecord latest = latestOpt.get();
        if (latest.decision() != ReviewDecision.APPROVED) {
            return false;
        }
        // Strict fail-closed: content_hash in review must be non-empty and match expectedContentHash exactly
        if (latest.contentHash() == null || latest.contentHash().isBlank() ||
                expectedContentHash == null || expectedContentHash.isBlank() ||
                !latest.contentHash().equalsIgnoreCase(expectedContentHash.trim())) {
            return false;
        }
        return true;
    }

    @Override
    public boolean hasApprovedReview(UUID versionId) {
        Optional<KnowledgeReviewRecord> latestOpt = findLatestReview(versionId);
        return latestOpt.isPresent() && latestOpt.get().decision() == ReviewDecision.APPROVED
                && latestOpt.get().contentHash() != null && !latestOpt.get().contentHash().isBlank();
    }
}
