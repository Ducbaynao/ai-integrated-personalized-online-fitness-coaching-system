package com.fitnesscoaching.platform.modules.support.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseDetail;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseEvent;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCasePage;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseSummary;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class SupportCasePersistenceAdapter {

    private final NamedParameterJdbcTemplate jdbc;

    public SupportCasePersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<SupportCaseSummary> searchSupportCases(String search, String status, String priority, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT sc.id, sc.case_number, sc.requester_id,
                       u.display_name AS requester_name, u.email AS requester_email,
                       sc.subject, sc.description, sc.priority::text, sc.status,
                       sc.assigned_to, a.display_name AS assigned_to_name,
                       sc.opened_at, sc.resolved_at, sc.updated_at
                FROM fitness.support_cases sc
                LEFT JOIN fitness.users u ON u.id = sc.requester_id
                LEFT JOIN fitness.users a ON a.id = sc.assigned_to
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, search, status, priority);

        sql.append(" ORDER BY sc.updated_at DESC, sc.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> new SupportCaseSummary(
                rs.getObject("id", UUID.class),
                rs.getString("case_number"),
                rs.getObject("requester_id", UUID.class),
                rs.getString("requester_name"),
                rs.getString("requester_email"),
                rs.getString("subject"),
                rs.getString("description"),
                rs.getString("priority"),
                rs.getString("status"),
                rs.getObject("assigned_to", UUID.class),
                rs.getString("assigned_to_name"),
                rs.getTimestamp("opened_at") != null ? rs.getTimestamp("opened_at").toInstant() : null,
                rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null,
                rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
        ));
    }

    public long countSupportCases(String search, String status, String priority) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(1)
                FROM fitness.support_cases sc
                LEFT JOIN fitness.users u ON u.id = sc.requester_id
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, search, status, priority);

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    public Optional<SupportCaseSummary> findSupportCaseSummaryById(UUID caseId) {
        String sql = """
                SELECT sc.id, sc.case_number, sc.requester_id,
                       u.display_name AS requester_name, u.email AS requester_email,
                       sc.subject, sc.description, sc.priority::text, sc.status,
                       sc.assigned_to, a.display_name AS assigned_to_name,
                       sc.opened_at, sc.resolved_at, sc.updated_at
                FROM fitness.support_cases sc
                LEFT JOIN fitness.users u ON u.id = sc.requester_id
                LEFT JOIN fitness.users a ON a.id = sc.assigned_to
                WHERE sc.id = :id
                """;
        List<SupportCaseSummary> list = jdbc.query(sql, new MapSqlParameterSource("id", caseId), (rs, rowNum) ->
                new SupportCaseSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("case_number"),
                        rs.getObject("requester_id", UUID.class),
                        rs.getString("requester_name"),
                        rs.getString("requester_email"),
                        rs.getString("subject"),
                        rs.getString("description"),
                        rs.getString("priority"),
                        rs.getString("status"),
                        rs.getObject("assigned_to", UUID.class),
                        rs.getString("assigned_to_name"),
                        rs.getTimestamp("opened_at") != null ? rs.getTimestamp("opened_at").toInstant() : null,
                        rs.getTimestamp("resolved_at") != null ? rs.getTimestamp("resolved_at").toInstant() : null,
                        rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toInstant() : null
                )
        );
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<SupportCaseEvent> findEventsByCaseId(UUID caseId) {
        String sql = """
                SELECT sce.id, sce.support_case_id, sce.event_type, sce.actor_id,
                       u.display_name AS actor_name, sce.note, sce.metadata::text, sce.occurred_at
                FROM fitness.support_case_events sce
                LEFT JOIN fitness.users u ON u.id = sce.actor_id
                WHERE sce.support_case_id = :caseId
                ORDER BY sce.occurred_at ASC, sce.id ASC
                """;
        return jdbc.query(sql, new MapSqlParameterSource("caseId", caseId), (rs, rowNum) ->
                new SupportCaseEvent(
                        rs.getObject("id", UUID.class),
                        rs.getObject("support_case_id", UUID.class),
                        rs.getString("event_type"),
                        rs.getObject("actor_id", UUID.class),
                        rs.getString("actor_name"),
                        rs.getString("note"),
                        rs.getString("metadata"),
                        rs.getTimestamp("occurred_at") != null ? rs.getTimestamp("occurred_at").toInstant() : null
                )
        );
    }

    public String nextCaseNumber() {
        try {
            Long nextVal = jdbc.getJdbcTemplate().queryForObject(
                    "SELECT nextval('fitness.support_case_number_seq')", Long.class);
            return String.format("SUP-%06d", nextVal != null ? nextVal : System.currentTimeMillis() % 1000000);
        } catch (Exception ex) {
            return "SUP-" + (System.currentTimeMillis() % 10000000);
        }
    }

    public UUID insertSupportCase(UUID id, String caseNumber, UUID requesterId, String subject,
                                  String description, String priority, String status, Instant now) {
        String sql = """
                INSERT INTO fitness.support_cases (
                    id, case_number, requester_id, subject, description,
                    priority, status, opened_at, updated_at
                ) VALUES (
                    :id, :caseNumber, :requesterId, :subject, :description,
                    :priority::fitness.severity_level, :status, :now, :now
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("caseNumber", caseNumber)
                .addValue("requesterId", requesterId)
                .addValue("subject", subject)
                .addValue("description", description)
                .addValue("priority", priority != null ? priority : "MEDIUM")
                .addValue("status", status != null ? status : "OPEN")
                .addValue("now", Timestamp.from(now))
        );
        return id;
    }

    public void updateStatus(UUID caseId, String newStatus, Instant resolvedAt, Instant now) {
        String sql = """
                UPDATE fitness.support_cases
                SET status = :status,
                    resolved_at = :resolvedAt,
                    updated_at = :now
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", caseId)
                .addValue("status", newStatus)
                .addValue("resolvedAt", resolvedAt != null ? Timestamp.from(resolvedAt) : null)
                .addValue("now", Timestamp.from(now))
        );
    }

    public void updateAssignee(UUID caseId, UUID assignedTo, Instant now) {
        String sql = """
                UPDATE fitness.support_cases
                SET assigned_to = :assignedTo,
                    updated_at = :now
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", caseId)
                .addValue("assignedTo", assignedTo)
                .addValue("now", Timestamp.from(now))
        );
    }

    public void touchUpdatedAt(UUID caseId, Instant now) {
        String sql = """
                UPDATE fitness.support_cases
                SET updated_at = :now
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", caseId)
                .addValue("now", Timestamp.from(now))
        );
    }

    public void insertEvent(UUID id, UUID supportCaseId, String eventType, UUID actorId, String note, String metadataJson, Instant now) {
        String sql = """
                INSERT INTO fitness.support_case_events (
                    id, support_case_id, event_type, actor_id, note, metadata, occurred_at
                ) VALUES (
                    :id, :supportCaseId, :eventType, :actorId, :note, coalesce(:metadata::jsonb, '{}'::jsonb), :now
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("supportCaseId", supportCaseId)
                .addValue("eventType", eventType)
                .addValue("actorId", actorId)
                .addValue("note", note)
                .addValue("metadata", metadataJson)
                .addValue("now", Timestamp.from(now))
        );
    }

    private void appendFilters(StringBuilder sql, MapSqlParameterSource params, String search, String status, String priority) {
        if (search != null && !search.isBlank()) {
            sql.append(" AND (lower(sc.case_number) LIKE :search OR lower(sc.subject) LIKE :search OR lower(u.email) LIKE :search OR lower(u.display_name) LIKE :search)");
            params.addValue("search", "%" + search.trim().toLowerCase() + "%");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND sc.status = :status");
            params.addValue("status", status.trim().toUpperCase());
        }
        if (priority != null && !priority.isBlank()) {
            sql.append(" AND sc.priority = :priority::fitness.severity_level");
            params.addValue("priority", priority.trim().toUpperCase());
        }
    }
}
