package com.fitnesscoaching.platform.modules.ai.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAiRunRepository implements AiRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<AiRun> ROW_MAPPER = (rs, rowNum) -> new AiRun(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("requested_by"),
            (UUID) rs.getObject("student_id"),
            (UUID) rs.getObject("coaching_period_id"),
            rs.getString("request_type"),
            AiRunStatus.valueOf(rs.getString("status")),
            (UUID) rs.getObject("model_version_id"),
            (UUID) rs.getObject("prompt_version_id"),
            (UUID) rs.getObject("correlation_id"),
            rs.getString("purpose"),
            rs.getBoolean("evaluation_only"),
            rs.getString("input_payload"),
            rs.getString("output_payload"),
            rs.getString("error_code"),
            rs.getString("error_message"),
            (Integer) rs.getObject("input_token_count"),
            (Integer) rs.getObject("output_token_count"),
            (Integer) rs.getObject("latency_ms"),
            toInstant(rs.getTimestamp("queued_at")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public AiRun create(AiRun run) {
        UUID id = run.id() != null ? run.id() : UUID.randomUUID();
        Timestamp now = Timestamp.from(run.createdAt() != null ? run.createdAt() : Instant.now());
        Timestamp queued = Timestamp.from(run.queuedAt() != null ? run.queuedAt() : now.toInstant());

        String sql = """
                INSERT INTO fitness.ai_runs (
                    id, requested_by, student_id, coaching_period_id, request_type, status,
                    model_version_id, prompt_version_id, correlation_id, purpose, evaluation_only,
                    input_payload, output_payload, error_code, error_message,
                    input_token_count, output_token_count, latency_ms,
                    queued_at, started_at, completed_at, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?::fitness.ai_run_status,
                    ?, ?, ?, ?, ?,
                    ?::jsonb, ?::jsonb, ?, ?,
                    ?, ?, ?,
                    ?, ?, ?, ?
                ) RETURNING *
                """;

        return jdbcTemplate.queryForObject(
                sql,
                ROW_MAPPER,
                id,
                run.requestedBy(),
                run.studentId(),
                run.coachingPeriodId(),
                run.requestType(),
                run.status().name(),
                run.modelVersionId(),
                run.promptVersionId(),
                run.correlationId(),
                run.purpose(),
                run.evaluationOnly(),
                run.inputPayload() != null ? run.inputPayload() : "{}",
                run.outputPayload(),
                run.errorCode(),
                run.errorMessage(),
                run.inputTokenCount(),
                run.outputTokenCount(),
                run.latencyMs(),
                queued,
                run.startedAt() != null ? Timestamp.from(run.startedAt()) : null,
                run.completedAt() != null ? Timestamp.from(run.completedAt()) : null,
                now
        );
    }

    @Override
    public Optional<AiRun> findById(UUID id) {
        String sql = "SELECT * FROM fitness.ai_runs WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<AiRun> findByIdForUpdate(UUID id) {
        String sql = "SELECT * FROM fitness.ai_runs WHERE id = ? FOR UPDATE";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean transitionStatus(
            UUID runId,
            AiRunStatus expectedStatus,
            AiRunStatus newStatus,
            Instant startedAt,
            Instant completedAt
    ) {
        // Enforce that status must be currently expectedStatus, and run is not already terminal
        String sql = """
                UPDATE fitness.ai_runs
                SET status = ?::fitness.ai_run_status,
                    started_at = COALESCE(?, started_at),
                    completed_at = COALESCE(?, completed_at)
                WHERE id = ?
                  AND status = ?::fitness.ai_run_status
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'REJECTED_BY_VALIDATOR', 'CANCELLED')
                """;

        int rows = jdbcTemplate.update(
                sql,
                newStatus.name(),
                startedAt != null ? Timestamp.from(startedAt) : null,
                completedAt != null ? Timestamp.from(completedAt) : null,
                runId,
                expectedStatus.name()
        );
        return rows > 0;
    }

    @Override
    public boolean completeRun(
            UUID runId,
            AiRunStatus terminalStatus,
            String outputPayload,
            String errorCode,
            String errorMessage,
            Integer inputTokens,
            Integer outputTokens,
            Integer latencyMs,
            Instant completedAt
    ) {
        if (!terminalStatus.isTerminal()) {
            throw new IllegalArgumentException("terminalStatus must be terminal, got: " + terminalStatus);
        }

        // Only update if current status is NOT yet terminal (cannot overwrite a terminal status)
        String sql = """
                UPDATE fitness.ai_runs
                SET status = ?::fitness.ai_run_status,
                    output_payload = ?::jsonb,
                    error_code = ?,
                    error_message = ?,
                    input_token_count = COALESCE(?, input_token_count),
                    output_token_count = COALESCE(?, output_token_count),
                    latency_ms = COALESCE(?, latency_ms),
                    completed_at = COALESCE(?, clock_timestamp())
                WHERE id = ?
                  AND status NOT IN ('SUCCEEDED', 'FAILED', 'REJECTED_BY_VALIDATOR', 'CANCELLED')
                """;

        int rows = jdbcTemplate.update(
                sql,
                terminalStatus.name(),
                outputPayload,
                errorCode,
                errorMessage,
                inputTokens,
                outputTokens,
                latencyMs,
                completedAt != null ? Timestamp.from(completedAt) : null,
                runId
        );
        return rows > 0;
    }
}
