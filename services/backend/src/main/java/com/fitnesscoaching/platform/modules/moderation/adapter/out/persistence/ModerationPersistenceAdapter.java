package com.fitnesscoaching.platform.modules.moderation.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationActionModel;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class ModerationPersistenceAdapter {

    private final NamedParameterJdbcTemplate jdbc;

    public ModerationPersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<ModerationCaseSummary> searchModerationCases(String status, String priority, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT mc.id, mc.report_id, mc.subject_user_id,
                       u.display_name AS subject_user_name, u.email AS subject_user_email,
                       mc.priority::text, mc.status, mc.assigned_to, a.display_name AS assigned_to_name,
                       mc.opened_at, mc.resolved_at, mc.resolution, mc.updated_at
                FROM fitness.moderation_cases mc
                LEFT JOIN fitness.users u ON u.id = mc.subject_user_id
                LEFT JOIN fitness.users a ON a.id = mc.assigned_to
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, status, priority);

        sql.append(" ORDER BY mc.updated_at DESC, mc.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new ModerationCaseSummary(
                rs.getObject("id", UUID.class),
                rs.getObject("report_id", UUID.class),
                rs.getObject("subject_user_id", UUID.class),
                rs.getString("subject_user_name"),
                rs.getString("subject_user_email"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getObject("assigned_to", UUID.class),
                rs.getString("assigned_to_name"),
                rs.getTimestamp("opened_at") != null ? rs.getTimestamp("opened_at").toInstant() : null,
                rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null,
                rs.getString("resolution"),
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ));
    }

    public long countModerationCases(String status, String priority) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(1)
                FROM fitness.moderation_cases mc
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, status, priority);

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    public Optional<ModerationCaseSummary> findModerationCaseSummaryById(UUID caseId) {
        String sql = """
                SELECT mc.id, mc.report_id, mc.subject_user_id,
                       u.display_name AS subject_user_name, u.email AS subject_user_email,
                       mc.priority::text, mc.status, mc.assigned_to, a.display_name AS assigned_to_name,
                       mc.opened_at, mc.resolved_at, mc.resolution, mc.updated_at
                FROM fitness.moderation_cases mc
                LEFT JOIN fitness.users u ON u.id = mc.subject_user_id
                LEFT JOIN fitness.users a ON a.id = mc.assigned_to
                WHERE mc.id = :id
                """;
        List<ModerationCaseSummary> list = jdbc.query(sql, new MapSqlParameterSource("id", caseId), (rs, rowNum) ->
                new ModerationCaseSummary(
                        rs.getObject("id", UUID.class),
                        rs.getObject("report_id", UUID.class),
                        rs.getObject("subject_user_id", UUID.class),
                        rs.getString("subject_user_name"),
                        rs.getString("subject_user_email"),
                        rs.getString("priority"),
                        rs.getString("status"),
                        rs.getObject("assigned_to", UUID.class),
                        rs.getString("assigned_to_name"),
                        rs.getTimestamp("opened_at") != null ? rs.getTimestamp("opened_at").toInstant() : null,
                        rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null,
                        rs.getString("resolution"),
                        rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
                )
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<ModerationActionModel> findActionsByCaseId(UUID caseId) {
        String sql = """
                SELECT ma.id, ma.moderation_case_id, ma.action_type, ma.target_user_id,
                       tu.display_name AS target_user_name,
                       ma.performed_by, pb.display_name AS performed_by_name,
                       ma.reason, ma.metadata::text AS metadata_json, ma.occurred_at
                FROM fitness.moderation_actions ma
                LEFT JOIN fitness.users tu ON tu.id = ma.target_user_id
                LEFT JOIN fitness.users pb ON pb.id = ma.performed_by
                WHERE ma.moderation_case_id = :caseId
                ORDER BY ma.occurred_at ASC, ma.id ASC
                """;
        return jdbc.query(sql, new MapSqlParameterSource("caseId", caseId), (rs, rowNum) ->
                new ModerationActionModel(
                        rs.getObject("id", UUID.class),
                        rs.getObject("moderation_case_id", UUID.class),
                        rs.getString("action_type"),
                        rs.getObject("target_user_id", UUID.class),
                        rs.getString("target_user_name"),
                        rs.getObject("performed_by", UUID.class),
                        rs.getString("performed_by_name"),
                        rs.getString("reason"),
                        rs.getString("metadata_json"),
                        rs.getTimestamp("occurred_at") != null ? rs.getTimestamp("occurred_at").toInstant() : null
                )
        );
    }

    public UUID insertModerationCase(UUID id, UUID reportId, UUID subjectUserId, String priority, String status, Instant now) {
        String sql = """
                INSERT INTO fitness.moderation_cases (
                    id, report_id, subject_user_id, priority, status, opened_at, updated_at
                ) VALUES (
                    :id, :reportId, :subjectUserId, :priority::fitness.severity_level, :status, :now, :now
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("reportId", reportId)
                .addValue("subjectUserId", subjectUserId)
                .addValue("priority", priority != null ? priority : "MEDIUM")
                .addValue("status", status != null ? status : "OPEN")
                .addValue("now", Timestamp.from(now))
        );
        return id;
    }

    public void updateStatus(UUID caseId, String status, String resolution, Instant resolvedAt, Instant now) {
        String sql = """
                UPDATE fitness.moderation_cases
                SET status = :status,
                    resolution = :resolution,
                    resolved_at = :resolvedAt,
                    updated_at = :now
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", caseId)
                .addValue("status", status)
                .addValue("resolution", resolution)
                .addValue("resolvedAt", resolvedAt != null ? Timestamp.from(resolvedAt) : null)
                .addValue("now", Timestamp.from(now))
        );
    }

    public void insertAction(UUID actionId, UUID caseId, String actionType, UUID targetUserId,
                             UUID performedBy, String reason, String metadataJson, Instant now) {
        String sql = """
                INSERT INTO fitness.moderation_actions (
                    id, moderation_case_id, action_type, target_user_id, performed_by, reason, metadata, occurred_at
                ) VALUES (
                    :id, :caseId, :actionType, :targetUserId, :performedBy, :reason, coalesce(:metadata::jsonb, '{}'::jsonb), :now
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", actionId)
                .addValue("caseId", caseId)
                .addValue("actionType", actionType)
                .addValue("targetUserId", targetUserId)
                .addValue("performedBy", performedBy)
                .addValue("reason", reason)
                .addValue("metadata", metadataJson)
                .addValue("now", Timestamp.from(now))
        );
    }

    public void touchUpdatedAt(UUID caseId, Instant now) {
        String sql = """
                UPDATE fitness.moderation_cases
                SET updated_at = :now
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", caseId)
                .addValue("now", Timestamp.from(now))
        );
    }

    public void insertSecurityEvent(UUID userId, String eventType, String severity, String detailsJson, Instant now) {
        String sql = """
                INSERT INTO fitness.security_events (
                    user_id, event_type, severity, details, occurred_at
                ) VALUES (
                    :userId, :eventType, :severity::fitness.severity_level, coalesce(:details::jsonb, '{}'::jsonb), :now
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("eventType", eventType)
                .addValue("severity", severity)
                .addValue("details", detailsJson)
                .addValue("now", Timestamp.from(now))
        );
    }


    private void appendFilters(StringBuilder sql, MapSqlParameterSource params, String status, String priority) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND mc.status = :status");
            params.addValue("status", status.trim().toUpperCase());
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND mc.priority = :priority::fitness.severity_level");
            params.addValue("priority", priority.trim().toUpperCase());
        }
    }
}
