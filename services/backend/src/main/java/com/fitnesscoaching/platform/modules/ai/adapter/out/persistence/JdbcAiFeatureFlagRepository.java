package com.fitnesscoaching.platform.modules.ai.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.ai.application.port.out.AiFeatureFlagRepository;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAiFeatureFlagRepository implements AiFeatureFlagRepository {

    private final JdbcTemplate jdbcTemplate;

    public JdbcAiFeatureFlagRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<FeatureFlagRecord> ROW_MAPPER = (rs, rowNum) -> new FeatureFlagRecord(
            (UUID) rs.getObject("id"),
            rs.getString("code"),
            rs.getString("description"),
            rs.getBoolean("enabled"),
            (UUID) rs.getObject("updated_by"),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts != null ? ts.toInstant() : null;
    }

    @Override
    public Optional<FeatureFlagRecord> findByCode(String code) {
        String sql = "SELECT id, code, description, enabled, updated_by, updated_at FROM fitness.feature_flags WHERE code = ?";
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(sql, ROW_MAPPER, code));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Override
    public FeatureFlagRecord setEnabled(String code, String description, boolean enabled, UUID updatedBy) {
        String sql = """
                INSERT INTO fitness.feature_flags (
                    id, code, description, enabled, criticality, updated_by, created_at, updated_at
                ) VALUES (
                    gen_random_uuid(), ?, ?, ?, 'MEDIUM'::fitness.severity_level, ?, now(), now()
                )
                ON CONFLICT (code) DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    description = COALESCE(EXCLUDED.description, fitness.feature_flags.description),
                    updated_by = EXCLUDED.updated_by,
                    updated_at = now()
                RETURNING id, code, description, enabled, updated_by, updated_at
                """;
        return jdbcTemplate.queryForObject(sql, ROW_MAPPER, code, description, enabled, updatedBy);
    }
}
