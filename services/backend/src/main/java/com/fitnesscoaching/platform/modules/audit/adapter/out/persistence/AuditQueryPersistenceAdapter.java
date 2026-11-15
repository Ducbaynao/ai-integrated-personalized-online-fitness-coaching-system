package com.fitnesscoaching.platform.modules.audit.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.audit.application.model.AuditLogEntry;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuditQueryPersistenceAdapter {

    private final NamedParameterJdbcTemplate jdbc;

    public AuditQueryPersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AuditLogEntry> searchAuditLogs(UUID actorUserId, String action, String targetType,
                                              UUID targetId, Instant from, Instant to,
                                              int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT al.id, al.actor_user_id, u.display_name AS actor_user_name,
                       al.actor_role, al.action, al.target_type, al.target_id,
                       al.request_id, al.ip_address::text AS ip_address, al.user_agent,
                       al.before_data::text AS before_data_json,
                       al.after_data::text AS after_data_json,
                       al.metadata::text AS metadata_json,
                       al.occurred_at
                FROM fitness.audit_logs al
                LEFT JOIN fitness.users u ON u.id = al.actor_user_id
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, actorUserId, action, targetType, targetId, from, to);

        sql.append(" ORDER BY al.occurred_at DESC, al.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapRow(rs));
    }

    public long countAuditLogs(UUID actorUserId, String action, String targetType,
                               UUID targetId, Instant from, Instant to) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(1)
                FROM fitness.audit_logs al
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, actorUserId, action, targetType, targetId, from, to);

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    public Optional<AuditLogEntry> findById(long id) {
        String sql = """
                SELECT al.id, al.actor_user_id, u.display_name AS actor_user_name,
                       al.actor_role, al.action, al.target_type, al.target_id,
                       al.request_id, al.ip_address::text AS ip_address, al.user_agent,
                       al.before_data::text AS before_data_json,
                       al.after_data::text AS after_data_json,
                       al.metadata::text AS metadata_json,
                       al.occurred_at
                FROM fitness.audit_logs al
                LEFT JOIN fitness.users u ON u.id = al.actor_user_id
                WHERE al.id = :id
                """;
        List<AuditLogEntry> list = jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRow(rs));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    private AuditLogEntry mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new AuditLogEntry(
                rs.getLong("id"),
                rs.getObject("actor_user_id", UUID.class),
                rs.getString("actor_user_name"),
                rs.getString("actor_role"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getObject("target_id", UUID.class),
                rs.getObject("request_id", UUID.class),
                rs.getString("ip_address"),
                rs.getString("user_agent"),
                rs.getString("before_data_json"),
                rs.getString("after_data_json"),
                rs.getString("metadata_json"),
                rs.getTimestamp("occurred_at") != null ? rs.getTimestamp("occurred_at").toInstant() : null
        );
    }

    private void appendFilters(StringBuilder sql, MapSqlParameterSource params,
                               UUID actorUserId, String action, String targetType,
                               UUID targetId, Instant from, Instant to) {
        if (actorUserId != null) {
            sql.append(" AND al.actor_user_id = :actorUserId");
            params.addValue("actorUserId", actorUserId);
        }
        if (action != null && !action.isBlank()) {
            sql.append(" AND al.action = :action");
            params.addValue("action", action.trim());
        }
        if (targetType != null && !targetType.isBlank()) {
            sql.append(" AND al.target_type = :targetType");
            params.addValue("targetType", targetType.trim());
        }
        if (targetId != null) {
            sql.append(" AND al.target_id = :targetId");
            params.addValue("targetId", targetId);
        }
        if (from != null) {
            sql.append(" AND al.occurred_at >= :from");
            params.addValue("from", Timestamp.from(from));
        }
        if (to != null) {
            sql.append(" AND al.occurred_at <= :to");
            params.addValue("to", Timestamp.from(to));
        }
    }
}
