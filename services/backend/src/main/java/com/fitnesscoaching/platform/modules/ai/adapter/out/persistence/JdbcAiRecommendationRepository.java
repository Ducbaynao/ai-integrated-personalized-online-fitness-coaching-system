package com.fitnesscoaching.platform.modules.ai.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAiRecommendationRepository implements AiRecommendationRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiRecommendationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AiRecommendation> RECOMMENDATION_ROW_MAPPER = (rs, rowNum) -> new AiRecommendation(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("ai_run_id"),
            (UUID) rs.getObject("student_id"),
            (UUID) rs.getObject("coaching_period_id"),
            rs.getString("recommendation_type"),
            rs.getString("title"),
            rs.getString("explanation"),
            rs.getString("structured_payload"),
            AiRecommendationStatus.valueOf(rs.getString("status")),
            AiDecisionAuthority.valueOf(rs.getString("decision_authority")),
            rs.getString("target_entity_type"),
            (UUID) rs.getObject("target_entity_id"),
            (UUID) rs.getObject("decided_by"),
            toInstant(rs.getTimestamp("decided_at")),
            rs.getString("decision_note"),
            toInstant(rs.getTimestamp("expires_at")),
            rs.getInt("revision"),
            rs.getString("original_payload"),
            rs.getBoolean("is_customized"),
            (UUID) rs.getObject("last_modified_by"),
            rs.getString("source_snapshot"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private static final RowMapper<AiRecommendationApplication> APPLICATION_ROW_MAPPER = (rs, rowNum) -> new AiRecommendationApplication(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("ai_recommendation_id"),
            (UUID) rs.getObject("applied_by"),
            rs.getString("resulting_entity_type"),
            (UUID) rs.getObject("resulting_entity_id"),
            rs.getString("application_payload"),
            toInstant(rs.getTimestamp("applied_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public AiRecommendation save(AiRecommendation recommendation) {
        UUID id = recommendation.id() != null ? recommendation.id() : UUID.randomUUID();
        Timestamp now = Timestamp.from(recommendation.createdAt() != null ? recommendation.createdAt() : Instant.now());
        Timestamp expires = recommendation.expiresAt() != null ? Timestamp.from(recommendation.expiresAt()) : null;
        String origPayload = recommendation.originalPayload() != null ? recommendation.originalPayload() : recommendation.structuredPayload();

        String sql = """
            INSERT INTO fitness.ai_recommendations (
                id, ai_run_id, student_id, coaching_period_id, recommendation_type,
                title, explanation, structured_payload, status, decision_authority,
                target_entity_type, target_entity_id, decided_by, decided_at, decision_note,
                expires_at, revision, original_payload, is_customized, last_modified_by,
                source_snapshot, created_at, updated_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?, ?::jsonb, ?::fitness.recommendation_status, ?,
                ?, ?, ?, ?, ?,
                ?, ?, ?::jsonb, ?, ?,
                ?::jsonb, ?, ?
            )
            """;

        jdbcTemplate.update(
                sql,
                id,
                recommendation.aiRunId(),
                recommendation.studentId(),
                recommendation.coachingPeriodId(),
                recommendation.recommendationType(),
                recommendation.title(),
                recommendation.explanation(),
                recommendation.structuredPayload(),
                recommendation.status().name(),
                recommendation.decisionAuthority().name(),
                recommendation.targetEntityType(),
                recommendation.targetEntityId(),
                recommendation.decidedBy(),
                recommendation.decidedAt() != null ? Timestamp.from(recommendation.decidedAt()) : null,
                recommendation.decisionNote(),
                expires,
                recommendation.revision(),
                origPayload,
                recommendation.isCustomized(),
                recommendation.lastModifiedBy(),
                recommendation.sourceSnapshot(),
                now,
                now
        );

        return findById(id).orElseThrow();
    }

    @Override
    public Optional<AiRecommendation> findById(UUID id) {
        String sql = "SELECT * FROM fitness.ai_recommendations WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, RECOMMENDATION_ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AiRecommendation> findByIdForUpdate(UUID id) {
        String sql = "SELECT * FROM fitness.ai_recommendations WHERE id = ? FOR UPDATE";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, RECOMMENDATION_ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void updateDecision(
            UUID id,
            AiRecommendationStatus status,
            UUID decidedBy,
            Instant decidedAt,
            String decisionNote,
            String targetEntityType,
            UUID targetEntityId
    ) {
        String sql = """
            UPDATE fitness.ai_recommendations
            SET status = ?::fitness.recommendation_status,
                decided_by = ?,
                decided_at = ?,
                decision_note = ?,
                target_entity_type = ?,
                target_entity_id = ?,
                updated_at = ?
            WHERE id = ?
            """;

        jdbcTemplate.update(
                sql,
                status.name(),
                decidedBy,
                decidedAt != null ? Timestamp.from(decidedAt) : null,
                decisionNote,
                targetEntityType,
                targetEntityId,
                Timestamp.from(Instant.now()),
                id
        );
    }

    @Override
    public void updateDraft(
            UUID id,
            int newRevision,
            String title,
            String explanation,
            String structuredPayload,
            UUID lastModifiedBy
    ) {
        String sql = """
            UPDATE fitness.ai_recommendations
            SET revision = ?,
                title = ?,
                explanation = ?,
                structured_payload = ?::jsonb,
                is_customized = true,
                last_modified_by = ?,
                updated_at = ?
            WHERE id = ?
            """;

        jdbcTemplate.update(
                sql,
                newRevision,
                title,
                explanation,
                structuredPayload,
                lastModifiedBy,
                Timestamp.from(Instant.now()),
                id
        );
    }

    @Override
    public AiRecommendationApplication saveApplication(AiRecommendationApplication application) {
        UUID id = application.id() != null ? application.id() : UUID.randomUUID();
        Timestamp now = Timestamp.from(application.appliedAt() != null ? application.appliedAt() : Instant.now());

        String sql = """
            INSERT INTO fitness.ai_recommendation_applications (
                id, ai_recommendation_id, applied_by, resulting_entity_type, resulting_entity_id,
                application_payload, applied_at
            ) VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
            """;

        jdbcTemplate.update(
                sql,
                id,
                application.aiRecommendationId(),
                application.appliedBy(),
                application.resultingEntityType(),
                application.resultingEntityId(),
                application.applicationPayload() != null ? application.applicationPayload() : "{}",
                now
        );

        return findApplicationByRecommendationId(application.aiRecommendationId()).orElseThrow();
    }

    @Override
    public Optional<AiRecommendationApplication> findApplicationByRecommendationId(UUID recommendationId) {
        String sql = "SELECT * FROM fitness.ai_recommendation_applications WHERE ai_recommendation_id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, APPLICATION_ROW_MAPPER, recommendationId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }
}
