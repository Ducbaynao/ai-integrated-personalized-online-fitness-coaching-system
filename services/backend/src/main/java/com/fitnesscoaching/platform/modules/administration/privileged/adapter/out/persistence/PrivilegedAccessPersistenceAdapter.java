package com.fitnesscoaching.platform.modules.administration.privileged.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessModel;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class PrivilegedAccessPersistenceAdapter {

    private final NamedParameterJdbcTemplate jdbc;

    public PrivilegedAccessPersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<PrivilegedAccessModel> searchRequests(String status, String purpose, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.requested_by_admin_id, ra.display_name AS requested_by_name,
                       p.approved_by_admin_id, aa.display_name AS approved_by_name,
                       p.target_user_id, tu.display_name AS target_user_name,
                       p.target_resource_type, p.target_resource_id,
                       p.purpose, p.access_scope::text AS access_scope_json,
                       p.reason, p.related_support_case_id, p.related_moderation_case_id,
                       p.status::text AS status, p.requested_at, p.approved_at,
                       p.opened_at, p.expires_at, p.revoked_at
                FROM fitness.privileged_data_access_requests p
                LEFT JOIN fitness.users ra ON ra.id = p.requested_by_admin_id
                LEFT JOIN fitness.users aa ON aa.id = p.approved_by_admin_id
                LEFT JOIN fitness.users tu ON tu.id = p.target_user_id
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, status, purpose);

        sql.append(" ORDER BY p.requested_at DESC, p.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> mapRow(rs));
    }

    public long countRequests(String status, String purpose) {
        StringBuilder sql = new StringBuilder("""
                SELECT count(1)
                FROM fitness.privileged_data_access_requests p
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, status, purpose);

        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    public Optional<PrivilegedAccessModel> findById(UUID id) {
        String sql = """
                SELECT p.id, p.requested_by_admin_id, ra.display_name AS requested_by_name,
                       p.approved_by_admin_id, aa.display_name AS approved_by_name,
                       p.target_user_id, tu.display_name AS target_user_name,
                       p.target_resource_type, p.target_resource_id,
                       p.purpose, p.access_scope::text AS access_scope_json,
                       p.reason, p.related_support_case_id, p.related_moderation_case_id,
                       p.status::text AS status, p.requested_at, p.approved_at,
                       p.opened_at, p.expires_at, p.revoked_at
                FROM fitness.privileged_data_access_requests p
                LEFT JOIN fitness.users ra ON ra.id = p.requested_by_admin_id
                LEFT JOIN fitness.users aa ON aa.id = p.approved_by_admin_id
                LEFT JOIN fitness.users tu ON tu.id = p.target_user_id
                WHERE p.id = :id
                """;
        List<PrivilegedAccessModel> list = jdbc.query(sql, new MapSqlParameterSource("id", id), (rs, rowNum) -> mapRow(rs));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }

    public List<PrivilegedAccessModel> findActiveForAdmin(UUID adminUserId, Instant now) {
        String sql = """
                SELECT p.id, p.requested_by_admin_id, ra.display_name AS requested_by_name,
                       p.approved_by_admin_id, aa.display_name AS approved_by_name,
                       p.target_user_id, tu.display_name AS target_user_name,
                       p.target_resource_type, p.target_resource_id,
                       p.purpose, p.access_scope::text AS access_scope_json,
                       p.reason, p.related_support_case_id, p.related_moderation_case_id,
                       p.status::text AS status, p.requested_at, p.approved_at,
                       p.opened_at, p.expires_at, p.revoked_at
                FROM fitness.privileged_data_access_requests p
                LEFT JOIN fitness.users ra ON ra.id = p.requested_by_admin_id
                LEFT JOIN fitness.users aa ON aa.id = p.approved_by_admin_id
                LEFT JOIN fitness.users tu ON tu.id = p.target_user_id
                WHERE p.requested_by_admin_id = :adminUserId
                  AND p.status = 'ACTIVE'::fitness.privileged_access_status
                  AND p.opened_at IS NOT NULL
                  AND (p.expires_at IS NULL OR p.expires_at > :now)
                  AND p.revoked_at IS NULL
                ORDER BY p.expires_at ASC
                """;
        return jdbc.query(sql, new MapSqlParameterSource()
                .addValue("adminUserId", adminUserId)
                .addValue("now", Timestamp.from(now)), (rs, rowNum) -> mapRow(rs));
    }

    public void insertRequest(UUID id, UUID requestedByAdminId, UUID approvedByAdminId, UUID targetUserId,
                              String targetResourceType, UUID targetResourceId, String purpose,
                              String accessScopeJson, String reason, UUID relatedSupportCaseId,
                              UUID relatedModerationCaseId, String status, Instant requestedAt,
                              Instant approvedAt, Instant openedAt, Instant expiresAt) {
        String sql = """
                INSERT INTO fitness.privileged_data_access_requests (
                    id, requested_by_admin_id, approved_by_admin_id, target_user_id,
                    target_resource_type, target_resource_id, purpose, access_scope,
                    reason, related_support_case_id, related_moderation_case_id,
                    status, requested_at, approved_at, opened_at, expires_at
                ) VALUES (
                    :id, :requestedByAdminId, :approvedByAdminId, :targetUserId,
                    :targetResourceType, :targetResourceId, :purpose, coalesce(:accessScope::jsonb, '{}'::jsonb),
                    :reason, :relatedSupportCaseId, :relatedModerationCaseId,
                    :status::fitness.privileged_access_status, :requestedAt, :approvedAt, :openedAt, :expiresAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("requestedByAdminId", requestedByAdminId)
                .addValue("approvedByAdminId", approvedByAdminId)
                .addValue("targetUserId", targetUserId)
                .addValue("targetResourceType", targetResourceType)
                .addValue("targetResourceId", targetResourceId)
                .addValue("purpose", purpose)
                .addValue("accessScope", accessScopeJson)
                .addValue("reason", reason)
                .addValue("relatedSupportCaseId", relatedSupportCaseId)
                .addValue("relatedModerationCaseId", relatedModerationCaseId)
                .addValue("status", status)
                .addValue("requestedAt", Timestamp.from(requestedAt))
                .addValue("approvedAt", approvedAt != null ? Timestamp.from(approvedAt) : null)
                .addValue("openedAt", openedAt != null ? Timestamp.from(openedAt) : null)
                .addValue("expiresAt", expiresAt != null ? Timestamp.from(expiresAt) : null)
        );
    }

    public void revokeRequest(UUID id, Instant revokedAt) {
        String sql = """
                UPDATE fitness.privileged_data_access_requests
                SET status = 'REVOKED'::fitness.privileged_access_status,
                    revoked_at = :revokedAt
                WHERE id = :id
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", id)
                .addValue("revokedAt", Timestamp.from(revokedAt))
        );
    }

    public void insertEvent(UUID eventId, UUID requestId, UUID adminUserId, String eventType,
                            String resourceType, UUID resourceId, String metadataJson, Instant occurredAt) {
        String sql = """
                INSERT INTO fitness.privileged_data_access_events (
                    id, privileged_access_request_id, admin_user_id, event_type,
                    resource_type, resource_id, metadata, occurred_at
                ) VALUES (
                    :id, :requestId, :adminUserId, :eventType,
                    :resourceType, :resourceId, coalesce(:metadata::jsonb, '{}'::jsonb), :occurredAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", eventId)
                .addValue("requestId", requestId)
                .addValue("adminUserId", adminUserId)
                .addValue("eventType", eventType)
                .addValue("resourceType", resourceType)
                .addValue("resourceId", resourceId)
                .addValue("metadata", metadataJson)
                .addValue("occurredAt", Timestamp.from(occurredAt))
        );
    }

    private PrivilegedAccessModel mapRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new PrivilegedAccessModel(
                rs.getObject("id", UUID.class),
                rs.getObject("requested_by_admin_id", UUID.class),
                rs.getString("requested_by_name"),
                rs.getObject("approved_by_admin_id", UUID.class),
                rs.getString("approved_by_name"),
                rs.getObject("target_user_id", UUID.class),
                rs.getString("target_user_name"),
                rs.getString("target_resource_type"),
                rs.getObject("target_resource_id", UUID.class),
                rs.getString("purpose"),
                rs.getString("access_scope_json"),
                rs.getString("reason"),
                rs.getObject("related_support_case_id", UUID.class),
                rs.getObject("related_moderation_case_id", UUID.class),
                rs.getString("status"),
                rs.getTimestamp("requested_at") != null ? rs.getTimestamp("requested_at").toInstant() : null,
                rs.getTimestamp("approved_at") != null ? rs.getTimestamp("approved_at").toInstant() : null,
                rs.getTimestamp("opened_at") != null ? rs.getTimestamp("opened_at").toInstant() : null,
                rs.getTimestamp("expires_at") != null ? rs.getTimestamp("expires_at").toInstant() : null,
                rs.getTimestamp("revoked_at") != null ? rs.getTimestamp("revoked_at").toInstant() : null
        );
    }

    private void appendFilters(StringBuilder sql, MapSqlParameterSource params, String status, String purpose) {
        if (status != null && !status.isBlank()) {
            sql.append(" AND p.status = :status::fitness.privileged_access_status");
            params.addValue("status", status.trim().toUpperCase());
        }
        if (purpose != null && !purpose.isBlank()) {
            sql.append(" AND p.purpose = :purpose");
            params.addValue("purpose", purpose.trim().toUpperCase());
        }
    }
}
