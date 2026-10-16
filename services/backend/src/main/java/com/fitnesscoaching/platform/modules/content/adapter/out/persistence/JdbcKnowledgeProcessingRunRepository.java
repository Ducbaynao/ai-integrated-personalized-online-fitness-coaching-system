package com.fitnesscoaching.platform.modules.content.adapter.out.persistence;

import com.fitnesscoaching.platform.common.exception.ContentConflictException;
import com.fitnesscoaching.platform.common.exception.ContentValidationException;
import com.fitnesscoaching.platform.modules.content.application.port.out.KnowledgeProcessingRunRepository;
import com.fitnesscoaching.platform.modules.content.domain.KnowledgeProcessingRun;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcKnowledgeProcessingRunRepository implements KnowledgeProcessingRunRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcKnowledgeProcessingRunRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<KnowledgeProcessingRun> ROW_MAPPER = (rs, rowNum) -> new KnowledgeProcessingRun(
            (UUID) rs.getObject("id"),
            (UUID) rs.getObject("knowledge_version_id"),
            rs.getString("run_type"),
            rs.getString("status"),
            (UUID) rs.getObject("model_version_id"),
            rs.getString("statistics"),
            rs.getString("error_message"),
            rs.getString("operation_key"),
            (UUID) rs.getObject("claim_token"),
            toInstant(rs.getTimestamp("locked_at")),
            rs.getString("locked_by"),
            rs.getInt("attempt_count"),
            rs.getInt("max_attempts"),
            toInstant(rs.getTimestamp("next_attempt_at")),
            toInstant(rs.getTimestamp("lease_expires_at")),
            toInstant(rs.getTimestamp("started_at")),
            toInstant(rs.getTimestamp("completed_at")),
            toInstant(rs.getTimestamp("created_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public KnowledgeProcessingRun enqueue(UUID versionId, String runType, String operationKey, String statisticsJson, Instant createdAt) {
        if (operationKey == null || operationKey.isBlank()) {
            throw new ContentValidationException("Operation key must not be null or blank");
        }

        UUID id = UUID.randomUUID();
        Timestamp now = Timestamp.from(createdAt != null ? createdAt : Instant.now());
        String insertSql = """
                INSERT INTO fitness.knowledge_processing_runs (
                    id, knowledge_version_id, run_type, status, operation_key, statistics, created_at
                ) VALUES (?, ?, ?, 'QUEUED', ?, ?::jsonb, ?)
                ON CONFLICT (operation_key) DO NOTHING
                RETURNING *
                """;
        List<KnowledgeProcessingRun> inserted = jdbcTemplate.query(
                insertSql,
                ROW_MAPPER,
                id,
                versionId,
                runType,
                operationKey,
                statisticsJson != null ? statisticsJson : "{}",
                now
        );

        if (!inserted.isEmpty()) {
            return inserted.get(0);
        }

        // On conflict across full lifecycle (QUEUED, RUNNING, SUCCEEDED, FAILED), retrieve existing run
        KnowledgeProcessingRun existing = findByOperationKey(operationKey)
                .orElseThrow(() -> new IllegalStateException("Failed to retrieve existing run for operation_key: " + operationKey));

        // Enforce intent match across lifecycle: conflict on mismatched version or runType
        if (!existing.knowledgeVersionId().equals(versionId) || !existing.runType().equalsIgnoreCase(runType)) {
            throw new ContentConflictException("Operation key '" + operationKey + "' already exists with mismatched intent: versionId=" + existing.knowledgeVersionId() + ", runType=" + existing.runType());
        }

        return existing;
    }

    @Override
    public Optional<KnowledgeProcessingRun> findById(UUID id) {
        String sql = "SELECT * FROM fitness.knowledge_processing_runs WHERE id = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, id));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<KnowledgeProcessingRun> findByOperationKey(String operationKey) {
        String sql = """
                SELECT * FROM fitness.knowledge_processing_runs
                WHERE operation_key = ?
                ORDER BY created_at DESC
                LIMIT 1
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, operationKey));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public List<KnowledgeProcessingRun> findQueuedRuns(int limit) {
        String sql = """
                SELECT * FROM fitness.knowledge_processing_runs
                WHERE status = 'QUEUED'
                ORDER BY created_at ASC
                LIMIT ?
                """;
        return jdbcTemplate.query(sql, ROW_MAPPER, limit);
    }

    @Override
    public int reapExpiredRuns() {
        String sql = """
                UPDATE fitness.knowledge_processing_runs
                SET status = 'FAILED',
                    error_message = CASE
                        WHEN status = 'RUNNING' THEN COALESCE(error_message, 'Exceeded maximum attempts during lease recovery')
                        ELSE COALESCE(error_message, 'Exceeded maximum attempts while queued for retry')
                    END,
                    completed_at = clock_timestamp(),
                    locked_at = null,
                    locked_by = null,
                    lease_expires_at = null
                WHERE (status = 'RUNNING' AND lease_expires_at IS NOT NULL AND lease_expires_at < clock_timestamp() AND attempt_count >= max_attempts)
                   OR (status = 'QUEUED' AND attempt_count >= max_attempts)
                """;
        return jdbcTemplate.update(sql);
    }

    @Override
    public Optional<KnowledgeProcessingRun> claimNextEligibleRun(String workerId, Duration leaseDuration) {
        if (leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
            throw new IllegalArgumentException("Lease duration must be positive");
        }

        reapExpiredRuns();

        long leaseMillis = leaseDuration.toMillis();
        UUID claimToken = UUID.randomUUID();
        String effectiveWorkerId = (workerId != null && !workerId.isBlank()) ? workerId : "worker-" + UUID.randomUUID();

        String sql = """
                UPDATE fitness.knowledge_processing_runs
                SET status = 'RUNNING',
                    claim_token = ?,
                    locked_at = clock_timestamp(),
                    locked_by = ?,
                    lease_expires_at = clock_timestamp() + (? || ' milliseconds')::interval,
                    attempt_count = attempt_count + 1,
                    started_at = COALESCE(started_at, clock_timestamp())
                WHERE id = (
                    SELECT id FROM fitness.knowledge_processing_runs
                    WHERE (status = 'QUEUED' AND attempt_count < max_attempts AND (next_attempt_at IS NULL OR next_attempt_at <= clock_timestamp()))
                       OR (status = 'RUNNING' AND attempt_count < max_attempts AND lease_expires_at IS NOT NULL AND lease_expires_at < clock_timestamp())
                    ORDER BY created_at ASC
                    FOR UPDATE SKIP LOCKED
                    LIMIT 1
                )
                RETURNING *
                """;
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, claimToken, effectiveWorkerId, String.valueOf(leaseMillis)));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public boolean markSuccess(UUID id, UUID claimToken, String statisticsJson, Instant completedAt) {
        if (claimToken == null) {
            return false;
        }

        String sql = """
                UPDATE fitness.knowledge_processing_runs
                SET status = 'SUCCEEDED',
                    statistics = ?::jsonb,
                    model_version_id = COALESCE(NULLIF(?::jsonb->>'embedding_model_version_id', '')::uuid, model_version_id),
                    completed_at = clock_timestamp(),
                    locked_at = null,
                    locked_by = null,
                    lease_expires_at = null
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND claim_token = ?
                  AND (lease_expires_at IS NULL OR lease_expires_at >= clock_timestamp())
                """;
        String stats = statisticsJson != null ? statisticsJson : "{}";
        int updated = jdbcTemplate.update(sql,
                stats,
                stats,
                id,
                claimToken
        );
        return updated > 0;
    }

    @Override
    public boolean markFailed(UUID id, UUID claimToken, String errorMessage, boolean retryable, Duration retryDelay, Instant now) {
        if (claimToken == null) {
            return false;
        }
        if (retryable && retryDelay != null && retryDelay.isNegative()) {
            throw new IllegalArgumentException("Retry delay must not be negative");
        }

        long retryDelayMillis = (retryable && retryDelay != null) ? retryDelay.toMillis() : 0L;
        String sql = """
                UPDATE fitness.knowledge_processing_runs
                SET status = CASE WHEN (?)::boolean = true AND attempt_count < max_attempts THEN 'QUEUED' ELSE 'FAILED' END,
                    next_attempt_at = CASE WHEN (?)::boolean = true AND attempt_count < max_attempts THEN clock_timestamp() + (? || ' milliseconds')::interval ELSE null END,
                    error_message = ?,
                    completed_at = CASE WHEN (?)::boolean = false OR attempt_count >= max_attempts THEN clock_timestamp() ELSE null END,
                    locked_at = null,
                    locked_by = null,
                    lease_expires_at = null
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND claim_token = ?
                  AND (lease_expires_at IS NULL OR lease_expires_at >= clock_timestamp())
                """;
        int updated = jdbcTemplate.update(sql,
                retryable,
                retryable,
                String.valueOf(retryDelayMillis),
                errorMessage,
                retryable,
                id,
                claimToken
        );
        return updated > 0;
    }

    @Override
    public boolean renewLease(UUID id, UUID claimToken, Duration additionalDuration) {
        if (claimToken == null) {
            return false;
        }
        if (additionalDuration == null || additionalDuration.isNegative() || additionalDuration.isZero()) {
            throw new IllegalArgumentException("Additional lease duration must be positive");
        }

        long addMillis = additionalDuration.toMillis();
        String sql = """
                UPDATE fitness.knowledge_processing_runs
                SET lease_expires_at = lease_expires_at + (? || ' milliseconds')::interval
                WHERE id = ?
                  AND status = 'RUNNING'
                  AND claim_token = ?
                  AND lease_expires_at >= clock_timestamp()
                """;
        int updated = jdbcTemplate.update(sql, String.valueOf(addMillis), id, claimToken);
        return updated > 0;
    }
}
