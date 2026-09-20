package com.fitnesscoaching.platform.modules.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;

@Service
public class AuditServiceImpl implements AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditServiceImpl.class);

    private final JdbcTemplate jdbcTemplate;

    public AuditServiceImpl(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void recordAudit(AuditRecord record) {
        String sql = """
            INSERT INTO fitness.audit_logs (
                actor_user_id, actor_role, action, target_type, target_id,
                request_id, ip_address, user_agent, before_data, after_data, metadata, occurred_at
            ) VALUES (
                ?, ?, ?, ?, ?,
                ?, ?::inet, ?, ?::jsonb, ?::jsonb, coalesce(?::jsonb, '{}'::jsonb), ?
            )
        """;
        jdbcTemplate.update(
                sql,
                record.actorUserId(),
                record.actorRole(),
                record.action(),
                record.targetType(),
                record.targetId(),
                record.requestId(),
                record.ipAddress(),
                record.userAgent(),
                record.beforeDataJson(),
                record.afterDataJson(),
                record.metadataJson(),
                Timestamp.from(record.occurredAt() != null ? record.occurredAt() : Instant.now())
        );
    }

    @Override
    public void recordSecurityEvent(SecurityEventRecord record) {
        String sql = """
            INSERT INTO fitness.security_events (
                user_id, event_type, severity, ip_address, user_agent,
                device_id, details, occurred_at
            ) VALUES (
                ?, ?, ?::fitness.severity_level, ?::inet, ?,
                ?, coalesce(?::jsonb, '{}'::jsonb), ?
            )
        """;
        jdbcTemplate.update(
                sql,
                record.userId(),
                record.eventType(),
                record.severity() != null ? record.severity() : "INFO",
                record.ipAddress(),
                record.userAgent(),
                record.deviceId(),
                record.detailsJson(),
                Timestamp.from(record.occurredAt() != null ? record.occurredAt() : Instant.now())
        );
    }
}
