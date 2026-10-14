package com.fitnesscoaching.platform.modules.ai.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunInputReference;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAiProvenanceRepository implements AiProvenanceRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiProvenanceRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AiContextSnapshot> SNAPSHOT_ROW_MAPPER = (rs, rowNum) -> new AiContextSnapshot(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("ai_run_id"),
            rs.getString("context_version"),
            rs.getString("context_payload"),
            rs.getString("data_availability"),
            rs.getString("data_quality_summary"),
            rs.getString("training_continuity_summary"),
            rs.getString("context_hash"),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static final RowMapper<AiRunInputReference> INPUT_REF_ROW_MAPPER = (rs, rowNum) -> new AiRunInputReference(
            (UUID) rs.getObject("ai_run_id"),
            rs.getString("entity_type"),
            (UUID) rs.getObject("entity_id"),
            rs.getString("reference_role")
    );

    private static final RowMapper<AiRunKnowledgeChunk> CHUNK_REF_ROW_MAPPER = (rs, rowNum) -> new AiRunKnowledgeChunk(
            (UUID) rs.getObject("ai_run_id"),
            (UUID) rs.getObject("knowledge_chunk_id"),
            rs.getInt("rank"),
            rs.getBigDecimal("similarity_score")
    );

    private static final RowMapper<AiValidationResult> VALIDATION_ROW_MAPPER = (rs, rowNum) -> new AiValidationResult(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("ai_run_id"),
            rs.getString("validator_code"),
            rs.getBoolean("passed"),
            rs.getString("severity"),
            rs.getString("details"),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public AiContextSnapshot saveSnapshot(AiContextSnapshot snapshot) {
        UUID id = snapshot.id() != null ? snapshot.id() : UUID.randomUUID();
        Timestamp now = Timestamp.from(snapshot.createdAt() != null ? snapshot.createdAt() : Instant.now());

        String sql = """
                INSERT INTO fitness.ai_context_snapshots (
                    id, ai_run_id, context_version, context_payload, data_availability,
                    data_quality_summary, training_continuity_summary, context_hash, created_at
                ) VALUES (
                    ?, ?, ?, ?::jsonb, ?::jsonb,
                    ?::jsonb, ?::jsonb, ?, ?
                )
                ON CONFLICT (ai_run_id) DO UPDATE SET
                    context_version = EXCLUDED.context_version,
                    context_payload = EXCLUDED.context_payload,
                    data_availability = EXCLUDED.data_availability,
                    data_quality_summary = EXCLUDED.data_quality_summary,
                    training_continuity_summary = EXCLUDED.training_continuity_summary,
                    context_hash = EXCLUDED.context_hash
                RETURNING *
                """;

        return jdbcTemplate.queryForObject(
                sql,
                SNAPSHOT_ROW_MAPPER,
                id,
                snapshot.aiRunId(),
                snapshot.contextVersion(),
                snapshot.contextPayload(),
                snapshot.dataAvailability() != null ? snapshot.dataAvailability() : "{}",
                snapshot.dataQualitySummary() != null ? snapshot.dataQualitySummary() : "{}",
                snapshot.trainingContinuitySummary() != null ? snapshot.trainingContinuitySummary() : "{}",
                snapshot.contextHash(),
                now
        );
    }

    @Override
    public Optional<AiContextSnapshot> findSnapshotByRunId(UUID aiRunId) {
        String sql = "SELECT * FROM fitness.ai_context_snapshots WHERE ai_run_id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, SNAPSHOT_ROW_MAPPER, aiRunId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public void saveInputReferences(UUID aiRunId, List<AiRunInputReference> references) {
        if (references == null || references.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO fitness.ai_run_input_references (
                    ai_run_id, entity_type, entity_id, reference_role
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (ai_run_id, entity_type, entity_id, reference_role) DO NOTHING
                """;
        List<Object[]> batchArgs = references.stream()
                .map(ref -> new Object[]{aiRunId, ref.entityType(), ref.entityId(), ref.referenceRole()})
                .toList();
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Override
    public List<AiRunInputReference> findInputReferencesByRunId(UUID aiRunId) {
        String sql = "SELECT * FROM fitness.ai_run_input_references WHERE ai_run_id = ? ORDER BY entity_type, reference_role";
        return jdbcTemplate.query(sql, INPUT_REF_ROW_MAPPER, aiRunId);
    }

    @Override
    public void saveKnowledgeChunkReferences(UUID aiRunId, List<AiRunKnowledgeChunk> chunkReferences) {
        if (chunkReferences == null || chunkReferences.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO fitness.ai_run_knowledge_chunks (
                    ai_run_id, knowledge_chunk_id, rank, similarity_score
                ) VALUES (?, ?, ?, ?)
                ON CONFLICT (ai_run_id, knowledge_chunk_id) DO UPDATE SET
                    rank = EXCLUDED.rank,
                    similarity_score = EXCLUDED.similarity_score
                """;
        List<Object[]> batchArgs = chunkReferences.stream()
                .map(ref -> new Object[]{aiRunId, ref.knowledgeChunkId(), ref.rank(), ref.similarityScore()})
                .toList();
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Override
    public List<AiRunKnowledgeChunk> findKnowledgeChunkReferencesByRunId(UUID aiRunId) {
        String sql = "SELECT * FROM fitness.ai_run_knowledge_chunks WHERE ai_run_id = ? ORDER BY rank ASC";
        return jdbcTemplate.query(sql, CHUNK_REF_ROW_MAPPER, aiRunId);
    }

    @Override
    public void saveValidationResults(UUID aiRunId, List<AiValidationResult> validationResults) {
        if (validationResults == null || validationResults.isEmpty()) {
            return;
        }
        String sql = """
                INSERT INTO fitness.ai_validation_results (
                    id, ai_run_id, validator_code, passed, severity, details, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?::fitness.severity_level, ?::jsonb, ?
                )
                ON CONFLICT (ai_run_id, validator_code) DO UPDATE SET
                    passed = EXCLUDED.passed,
                    severity = EXCLUDED.severity,
                    details = EXCLUDED.details
                """;
        List<Object[]> batchArgs = validationResults.stream()
                .map(v -> new Object[]{
                        v.id() != null ? v.id() : UUID.randomUUID(),
                        aiRunId,
                        v.validatorCode(),
                        v.passed(),
                        v.severity() != null ? v.severity() : "INFO",
                        v.details() != null ? v.details() : "{}",
                        Timestamp.from(v.createdAt() != null ? v.createdAt() : Instant.now())
                })
                .toList();
        jdbcTemplate.batchUpdate(sql, batchArgs);
    }

    @Override
    public List<AiValidationResult> findValidationResultsByRunId(UUID aiRunId) {
        String sql = "SELECT * FROM fitness.ai_validation_results WHERE ai_run_id = ? ORDER BY created_at ASC";
        return jdbcTemplate.query(sql, VALIDATION_ROW_MAPPER, aiRunId);
    }
}
