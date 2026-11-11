package com.fitnesscoaching.platform.modules.administration.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.administration.application.model.AccountStatusHistoryEntry;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminDashboardSummary;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserDetail;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserSummary;
import com.fitnesscoaching.platform.modules.administration.application.model.StudentProfileInfo;
import com.fitnesscoaching.platform.modules.administration.application.model.TrainerProfileInfo;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AdminUserPersistenceAdapter {

    private final NamedParameterJdbcTemplate jdbc;

    public AdminUserPersistenceAdapter(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public List<AdminUserSummary> searchUsers(String search, String status, String role, int limit, long offset) {
        StringBuilder sql = new StringBuilder("""
                SELECT u.id, u.email, u.display_name, u.phone_number, u.status, u.created_at, u.last_login_at,
                       COALESCE(
                           (SELECT array_agg(r.code ORDER BY r.code)
                            FROM fitness.user_roles ur
                            JOIN fitness.roles r ON r.id = ur.role_id
                            WHERE ur.user_id = u.id AND ur.revoked_at IS NULL),
                           ARRAY[]::varchar[]
                       ) AS roles,
                       EXISTS(SELECT 1 FROM fitness.student_profiles sp WHERE sp.user_id = u.id) AS has_student_profile,
                       EXISTS(SELECT 1 FROM fitness.trainer_profiles tp WHERE tp.user_id = u.id) AS has_trainer_profile,
                       (SELECT tp.verification_status::text FROM fitness.trainer_profiles tp WHERE tp.user_id = u.id) AS trainer_verification_status
                FROM fitness.users u
                WHERE 1=1
                """);

        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, search, status, role);

        sql.append(" ORDER BY u.created_at DESC, u.id DESC LIMIT :limit OFFSET :offset");
        params.addValue("limit", limit);
        params.addValue("offset", offset);

        return jdbc.query(sql.toString(), params, (rs, rowNum) -> {
            java.sql.Array rolesArray = rs.getArray("roles");
            List<String> rolesList = rolesArray != null ? Arrays.asList((String[]) rolesArray.getArray()) : List.of();

            Timestamp createdAtTs = rs.getTimestamp("created_at");
            Timestamp lastLoginTs = rs.getTimestamp("last_login_at");

            return new AdminUserSummary(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("phone_number"),
                    AccountStatus.valueOf(rs.getString("status")),
                    rolesList,
                    rs.getBoolean("has_student_profile"),
                    rs.getBoolean("has_trainer_profile"),
                    rs.getString("trainer_verification_status"),
                    createdAtTs != null ? createdAtTs.toInstant() : null,
                    lastLoginTs != null ? lastLoginTs.toInstant() : null
            );
        });
    }

    public long countUsers(String search, String status, String role) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(DISTINCT u.id) FROM fitness.users u WHERE 1=1 ");
        MapSqlParameterSource params = new MapSqlParameterSource();
        appendFilters(sql, params, search, status, role);
        Long count = jdbc.queryForObject(sql.toString(), params, Long.class);
        return count != null ? count : 0L;
    }

    private void appendFilters(StringBuilder sql, MapSqlParameterSource params, String search, String status, String role) {
        if (search != null && !search.isBlank()) {
            String trimmed = search.trim().toLowerCase(Locale.ROOT);
            sql.append("""
                     AND (lower(u.email) LIKE :pattern ESCAPE '\\'
                          OR lower(u.display_name) LIKE :pattern ESCAPE '\\'
                          OR (u.phone_number IS NOT NULL AND lower(u.phone_number) LIKE :pattern ESCAPE '\\'))
                    """);
            params.addValue("pattern", "%" + escapeLike(trimmed) + "%");
        }
        if (status != null && !status.isBlank()) {
            sql.append(" AND u.status = :status::fitness.account_status");
            params.addValue("status", status.trim());
        }
        if (role != null && !role.isBlank()) {
            sql.append("""
                     AND EXISTS (
                         SELECT 1 FROM fitness.user_roles ur
                         JOIN fitness.roles r ON r.id = ur.role_id
                         WHERE ur.user_id = u.id AND ur.revoked_at IS NULL AND r.code = :role
                     )
                    """);
            params.addValue("role", role.trim());
        }
    }

    public Optional<AdminUserDetail> findUserDetail(UUID userId) {
        String userSql = """
                SELECT u.id, u.email, u.display_name, u.phone_number, u.status, u.preferred_locale, u.timezone,
                       u.email_verified_at, u.created_at, u.updated_at, u.last_login_at
                FROM fitness.users u
                WHERE u.id = :userId
                """;

        List<AdminUserDetail> users = jdbc.query(userSql, new MapSqlParameterSource("userId", userId), (rs, rowNum) -> {
            Timestamp emailVerifiedTs = rs.getTimestamp("email_verified_at");
            Timestamp createdAtTs = rs.getTimestamp("created_at");
            Timestamp updatedAtTs = rs.getTimestamp("updated_at");
            Timestamp lastLoginTs = rs.getTimestamp("last_login_at");

            return new AdminUserDetail(
                    rs.getObject("id", UUID.class),
                    rs.getString("email"),
                    rs.getString("display_name"),
                    rs.getString("phone_number"),
                    AccountStatus.valueOf(rs.getString("status")),
                    rs.getString("preferred_locale"),
                    rs.getString("timezone"),
                    emailVerifiedTs != null ? emailVerifiedTs.toInstant() : null,
                    createdAtTs != null ? createdAtTs.toInstant() : null,
                    updatedAtTs != null ? updatedAtTs.toInstant() : null,
                    lastLoginTs != null ? lastLoginTs.toInstant() : null,
                    new ArrayList<>(),
                    new ArrayList<>(),
                    null,
                    null,
                    new ArrayList<>()
            );
        });

        if (users.isEmpty()) {
            return Optional.empty();
        }

        AdminUserDetail base = users.get(0);

        // Roles
        List<String> roles = jdbc.query("""
                SELECT r.code
                FROM fitness.user_roles ur
                JOIN fitness.roles r ON r.id = ur.role_id
                WHERE ur.user_id = :userId AND ur.revoked_at IS NULL
                ORDER BY r.code
                """, new MapSqlParameterSource("userId", userId), (rs, i) -> rs.getString("code"));

        // Permissions
        List<String> permissions = jdbc.query("""
                SELECT DISTINCT p.code
                FROM fitness.user_roles ur
                JOIN fitness.role_permissions rp ON rp.role_id = ur.role_id
                JOIN fitness.permissions p ON p.id = rp.permission_id
                WHERE ur.user_id = :userId AND ur.revoked_at IS NULL
                ORDER BY p.code
                """, new MapSqlParameterSource("userId", userId), (rs, i) -> rs.getString("code"));

        // Student Profile
        List<StudentProfileInfo> studentProfiles = jdbc.query("""
                SELECT sp.date_of_birth, sp.gender, sp.training_experience_level, sp.training_experience_months,
                       sp.available_days_per_week, sp.preferred_session_minutes, sp.onboarding_completed_at
                FROM fitness.student_profiles sp
                WHERE sp.user_id = :userId
                """, new MapSqlParameterSource("userId", userId), (rs, i) -> {
            java.sql.Date dob = rs.getDate("date_of_birth");
            return new StudentProfileInfo(
                    dob != null ? dob.toLocalDate() : null,
                    rs.getString("gender"),
                    rs.getString("training_experience_level"),
                    rs.getBigDecimal("training_experience_months"),
                    rs.getObject("available_days_per_week", Integer.class),
                    rs.getObject("preferred_session_minutes", Integer.class),
                    rs.getTimestamp("onboarding_completed_at") != null
            );
        });
        StudentProfileInfo studentProfile = studentProfiles.isEmpty() ? null : studentProfiles.get(0);

        // Trainer Profile
        List<TrainerProfileInfo> trainerProfiles = jdbc.query("""
                SELECT tp.public_slug, tp.bio, tp.years_experience, tp.verification_status::text,
                       tp.activity_status::text, tp.verified_at, tp.verified_by, tp.is_accepting_students, tp.is_active
                FROM fitness.trainer_profiles tp
                WHERE tp.user_id = :userId
                """, new MapSqlParameterSource("userId", userId), (rs, i) -> {
            Timestamp vTs = rs.getTimestamp("verified_at");
            return new TrainerProfileInfo(
                    rs.getString("public_slug"),
                    rs.getString("bio"),
                    rs.getBigDecimal("years_experience"),
                    rs.getString("verification_status"),
                    rs.getString("activity_status"),
                    vTs != null ? vTs.toInstant() : null,
                    rs.getObject("verified_by", UUID.class),
                    rs.getBoolean("is_accepting_students"),
                    rs.getBoolean("is_active")
            );
        });
        TrainerProfileInfo trainerProfile = trainerProfiles.isEmpty() ? null : trainerProfiles.get(0);

        // Status history
        List<AccountStatusHistoryEntry> history = jdbc.query("""
                SELECT h.id, h.from_status, h.to_status, h.changed_by, u.display_name AS changed_by_name,
                       h.reason, h.changed_at
                FROM fitness.user_account_status_history h
                LEFT JOIN fitness.users u ON u.id = h.changed_by
                WHERE h.user_id = :userId
                ORDER BY h.changed_at DESC
                """, new MapSqlParameterSource("userId", userId), (rs, i) -> {
            String fromStr = rs.getString("from_status");
            String toStr = rs.getString("to_status");
            Timestamp changedAtTs = rs.getTimestamp("changed_at");

            return new AccountStatusHistoryEntry(
                    rs.getObject("id", UUID.class),
                    fromStr != null ? AccountStatus.valueOf(fromStr) : null,
                    AccountStatus.valueOf(toStr),
                    rs.getObject("changed_by", UUID.class),
                    rs.getString("changed_by_name"),
                    rs.getString("reason"),
                    changedAtTs != null ? changedAtTs.toInstant() : null
            );
        });

        return Optional.of(new AdminUserDetail(
                base.id(),
                base.email(),
                base.displayName(),
                base.phoneNumber(),
                base.status(),
                base.preferredLocale(),
                base.timezone(),
                base.emailVerifiedAt(),
                base.createdAt(),
                base.updatedAt(),
                base.lastLoginAt(),
                roles,
                permissions,
                studentProfile,
                trainerProfile,
                history
        ));
    }

    public int updateUserStatus(UUID userId, AccountStatus newStatus, Instant updatedAt) {
        String sql = """
                UPDATE fitness.users
                SET status = :status::fitness.account_status, updated_at = :updatedAt
                WHERE id = :userId
                """;
        return jdbc.update(sql, new MapSqlParameterSource()
                .addValue("status", newStatus.name())
                .addValue("updatedAt", Timestamp.from(updatedAt))
                .addValue("userId", userId));
    }

    public void recordAccountStatusHistory(UUID userId, AccountStatus fromStatus, AccountStatus toStatus,
                                           UUID changedBy, String reason, Instant changedAt) {
        String sql = """
                INSERT INTO fitness.user_account_status_history (
                    id, user_id, from_status, to_status, changed_by, reason, changed_at
                ) VALUES (
                    :id, :userId, :fromStatus::fitness.account_status, :toStatus::fitness.account_status,
                    :changedBy, :reason, :changedAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("userId", userId)
                .addValue("fromStatus", fromStatus != null ? fromStatus.name() : null)
                .addValue("toStatus", toStatus.name())
                .addValue("changedBy", changedBy)
                .addValue("reason", reason)
                .addValue("changedAt", Timestamp.from(changedAt)));
    }

    public void recordSessionRevocationEvent(UUID targetUserId, UUID requestedBy, String reason,
                                             UUID adminActionId, int revokedTokenCount, Instant occurredAt) {
        String sql = """
                INSERT INTO fitness.session_revocation_events (
                    id, target_user_id, requested_by, revocation_scope, reason,
                    admin_action_id, revoked_token_count, occurred_at
                ) VALUES (
                    :id, :targetUserId, :requestedBy, 'ALL_SESSIONS', :reason,
                    :adminActionId, :revokedTokenCount, :occurredAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", UUID.randomUUID())
                .addValue("targetUserId", targetUserId)
                .addValue("requestedBy", requestedBy)
                .addValue("reason", reason)
                .addValue("adminActionId", adminActionId)
                .addValue("revokedTokenCount", Math.max(revokedTokenCount, 0))
                .addValue("occurredAt", Timestamp.from(occurredAt)));
    }

    public UUID recordAdminAction(UUID adminUserId, String actionType, UUID targetUserId, String reason,
                                  String permissionCode, UUID stepUpAuthenticationId, Instant completedAt) {
        UUID actionId = UUID.randomUUID();
        String sql = """
                INSERT INTO fitness.admin_actions (
                    id, admin_user_id, action_type, target_type, target_id, reason, metadata,
                    permission_id, step_up_authentication_id, completed_at, created_at
                ) VALUES (
                    :id, :adminUserId, :actionType, 'USER', :targetUserId, :reason, '{}'::jsonb,
                    (SELECT id FROM fitness.permissions WHERE code = :permissionCode),
                    :stepUpAuthId, :completedAt, :completedAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", actionId)
                .addValue("adminUserId", adminUserId)
                .addValue("actionType", actionType)
                .addValue("targetUserId", targetUserId)
                .addValue("reason", reason)
                .addValue("permissionCode", permissionCode)
                .addValue("stepUpAuthId", stepUpAuthenticationId)
                .addValue("completedAt", Timestamp.from(completedAt)));
        return actionId;
    }

    public UUID createVerifiedStepUp(UUID userId, String actionCode, Instant verifiedAt, Instant expiresAt) {
        UUID stepUpId = UUID.randomUUID();
        String sql = """
                INSERT INTO fitness.step_up_authentications (
                    id, user_id, action_code, status, initiated_at, verified_at, expires_at
                ) VALUES (
                    :id, :userId, :actionCode, 'VERIFIED'::fitness.step_up_status,
                    :verifiedAt, :verifiedAt, :expiresAt
                )
                """;
        jdbc.update(sql, new MapSqlParameterSource()
                .addValue("id", stepUpId)
                .addValue("userId", userId)
                .addValue("actionCode", actionCode)
                .addValue("verifiedAt", Timestamp.from(verifiedAt))
                .addValue("expiresAt", Timestamp.from(expiresAt)));
        return stepUpId;
    }

    public boolean isStepUpValid(UUID stepUpId, UUID userId, String actionCode, Instant at) {
        String sql = """
                SELECT 1
                FROM fitness.step_up_authentications
                WHERE id = :id
                  AND user_id = :userId
                  AND action_code = :actionCode
                  AND status = 'VERIFIED'::fitness.step_up_status
                  AND expires_at > :at
                """;
        List<Integer> list = jdbc.query(sql, new MapSqlParameterSource()
                .addValue("id", stepUpId)
                .addValue("userId", userId)
                .addValue("actionCode", actionCode)
                .addValue("at", Timestamp.from(at)), (rs, i) -> rs.getInt(1));
        return !list.isEmpty();
    }

    public Optional<String> getUserPasswordHash(UUID userId) {
        String sql = "SELECT password_hash FROM fitness.users WHERE id = :userId";
        List<String> list = jdbc.query(sql, new MapSqlParameterSource("userId", userId),
                (rs, i) -> rs.getString("password_hash"));
        return list.stream().findFirst();
    }

    public void lockUser(UUID userId) {
        jdbc.query("SELECT id FROM fitness.users WHERE id = :userId FOR NO KEY UPDATE",
                new MapSqlParameterSource("userId", userId),
                rs -> {});
    }

    public long countActiveAdmins() {
        jdbc.query("SELECT id FROM fitness.roles WHERE code = 'ADMIN' FOR UPDATE", rs -> {});
        String sql = """
                SELECT COUNT(DISTINCT ur.user_id)
                FROM fitness.user_roles ur
                JOIN fitness.roles r ON r.id = ur.role_id
                JOIN fitness.users u ON u.id = ur.user_id
                WHERE r.code = 'ADMIN'
                  AND ur.revoked_at IS NULL
                  AND u.status = 'ACTIVE'::fitness.account_status
                """;
        Long count = jdbc.queryForObject(sql, new MapSqlParameterSource(), Long.class);
        return count != null ? count : 0L;
    }

    public boolean assignRole(UUID userId, String roleCode, UUID assignedBy, Instant at) {
        String sql = """
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at, revoked_at)
                SELECT :userId, r.id, :assignedBy, :at, NULL
                FROM fitness.roles r
                WHERE r.code = :roleCode
                ON CONFLICT (user_id, role_id)
                DO UPDATE SET revoked_at = NULL, assigned_by = :assignedBy, assigned_at = :at
                WHERE user_roles.revoked_at IS NOT NULL
                """;
        int rows = jdbc.update(sql, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("roleCode", roleCode)
                .addValue("assignedBy", assignedBy)
                .addValue("at", Timestamp.from(at)));
        return rows > 0;
    }

    public boolean revokeRole(UUID userId, String roleCode, Instant at) {
        String sql = """
                UPDATE fitness.user_roles ur
                SET revoked_at = :at
                FROM fitness.roles r
                WHERE ur.role_id = r.id
                  AND ur.user_id = :userId
                  AND r.code = :roleCode
                  AND ur.revoked_at IS NULL
                """;
        int rows = jdbc.update(sql, new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("roleCode", roleCode)
                .addValue("at", Timestamp.from(at)));
        return rows > 0;
    }

    public AdminDashboardSummary getDashboardSummary() {
        String appSql = "SELECT COUNT(*) FROM fitness.trainer_applications WHERE status = 'PENDING'";
        Long pendingApps = jdbc.queryForObject(appSql, new MapSqlParameterSource(), Long.class);

        String userCountSql = """
                SELECT
                    COUNT(*) AS total_count,
                    COUNT(*) FILTER (WHERE status = 'ACTIVE'::fitness.account_status) AS active_count,
                    COUNT(*) FILTER (WHERE status = 'SUSPENDED'::fitness.account_status) AS suspended_count,
                    COUNT(*) FILTER (WHERE status = 'DISABLED'::fitness.account_status) AS disabled_count,
                    COUNT(*) FILTER (WHERE status = 'PENDING_VERIFICATION'::fitness.account_status) AS pending_verification_count
                FROM fitness.users
                """;

        return jdbc.queryForObject(userCountSql, new MapSqlParameterSource(), (rs, i) -> new AdminDashboardSummary(
                pendingApps != null ? pendingApps : 0L,
                rs.getLong("total_count"),
                rs.getLong("active_count"),
                rs.getLong("suspended_count"),
                rs.getLong("disabled_count"),
                rs.getLong("pending_verification_count")
        ));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
