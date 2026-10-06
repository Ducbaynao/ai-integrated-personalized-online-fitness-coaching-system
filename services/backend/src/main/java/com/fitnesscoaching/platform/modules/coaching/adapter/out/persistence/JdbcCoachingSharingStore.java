package com.fitnesscoaching.platform.modules.coaching.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingSharingStore;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityContext;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.Permission;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Receipt;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Relationship;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCoachingSharingStore implements CoachingSharingStore {
    private final JdbcTemplate jdbc;

    public JdbcCoachingSharingStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<Relationship> RELATIONSHIP = (rs, ignored) -> new Relationship(
            rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
            rs.getObject("trainer_id", UUID.class), rs.getString("status"),
            rs.getObject("requested_by", UUID.class), instant(rs.getTimestamp("requested_at")),
            instant(rs.getTimestamp("accepted_at")), instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("ended_at")), rs.getLong("version"));

    private static final RowMapper<Permission> PERMISSION = (rs, ignored) -> new Permission(
            rs.getObject("id", UUID.class), rs.getObject("relationship_id", UUID.class),
            rs.getObject("student_id", UUID.class), rs.getObject("trainer_id", UUID.class),
            DataScope.valueOf(rs.getString("data_scope")), SharingDecision.valueOf(rs.getString("decision")),
            DataAccessLevel.valueOf(rs.getString("access_level")),
            instant(rs.getTimestamp("history_from")), instant(rs.getTimestamp("history_until")),
            instant(rs.getTimestamp("valid_from")), instant(rs.getTimestamp("valid_until")),
            rs.getObject("granted_by", UUID.class), instant(rs.getTimestamp("revoked_at")),
            rs.getString("revoke_reason"), rs.getLong("version"), instant(rs.getTimestamp("created_at")));

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static <T> Optional<T> first(List<T> rows) {
        return rows.stream().findFirst();
    }

    @Override
    public Instant transitionTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override
    public void lockStudent(UUID studentId) {
        jdbc.query("SELECT user_id FROM fitness.student_profiles WHERE user_id = ? FOR NO KEY UPDATE",
                rs -> { while (rs.next()) { /* lock held until transaction completion */ } }, studentId);
    }

    @Override
    public void lockAuthorityBoundary(UUID studentId, UUID trainerId) {
        jdbc.query("SELECT user_id FROM fitness.student_profiles WHERE user_id = ? FOR SHARE",
                rs -> { while (rs.next()) { /* lock held until consumer transaction completion */ } }, studentId);
        jdbc.query("SELECT id FROM fitness.users WHERE id = ? FOR SHARE",
                rs -> { while (rs.next()) { /* fixed lock order */ } }, trainerId);
        jdbc.query("""
                SELECT ur.user_id
                FROM fitness.user_roles ur
                JOIN fitness.roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.code = 'TRAINER'
                FOR SHARE OF ur
                """, rs -> { while (rs.next()) { /* fixed lock order */ } }, trainerId);
        jdbc.query("SELECT user_id FROM fitness.trainer_profiles WHERE user_id = ? FOR SHARE",
                rs -> { while (rs.next()) { /* fixed lock order */ } }, trainerId);
    }

    @Override
    public Optional<Relationship> relationship(UUID relationshipId) {
        return first(jdbc.query("SELECT * FROM fitness.coaching_relationships WHERE id = ?",
                RELATIONSHIP, relationshipId));
    }

    @Override
    public List<Permission> permissions(UUID relationshipId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM fitness.data_sharing_permissions
                WHERE relationship_id = ?
                ORDER BY valid_from DESC, created_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, PERMISSION, relationshipId, limit, offset);
    }

    @Override
    public List<Permission> latestPermissionsAt(UUID relationshipId, Instant at) {
        return jdbc.query("""
                SELECT DISTINCT ON (data_scope) *
                FROM fitness.data_sharing_permissions
                WHERE relationship_id = ? AND valid_from <= ?
                ORDER BY data_scope, valid_from DESC, created_at DESC, id DESC
                """, PERMISSION, relationshipId, timestamp(at));
    }

    @Override
    public Optional<Permission> permission(UUID permissionId) {
        return first(jdbc.query("SELECT * FROM fitness.data_sharing_permissions WHERE id = ?",
                PERMISSION, permissionId));
    }

    @Override
    public Optional<Permission> effectivePermission(UUID relationshipId, DataScope scope, Instant at) {
        return first(jdbc.query("""
                SELECT * FROM fitness.data_sharing_permissions
                WHERE relationship_id = ?
                  AND data_scope = ?::fitness.data_scope_code
                  AND valid_from <= ?
                  AND (valid_until IS NULL OR valid_until > ?)
                  AND revoked_at IS NULL
                ORDER BY valid_from DESC, id DESC
                LIMIT 1
                """, PERMISSION, relationshipId, scope.name(), timestamp(at), timestamp(at)));
    }

    @Override
    public Optional<AuthorityContext> authorityContext(UUID trainerId, UUID studentId,
                                                       DataScope scope, Instant at) {
        return first(jdbc.query("""
                SELECT r.id AS relationship_id, r.version AS relationship_version,
                       period.id AS period_id,
                       permission.id AS permission_id,
                       permission.student_id AS permission_student_id,
                       permission.trainer_id AS permission_trainer_id,
                       permission.data_scope AS permission_data_scope,
                       permission.decision AS permission_decision,
                       permission.access_level AS permission_access_level,
                       permission.history_from AS permission_history_from,
                       permission.history_until AS permission_history_until,
                       permission.valid_from AS permission_valid_from,
                       permission.valid_until AS permission_valid_until,
                       permission.granted_by AS permission_granted_by,
                       permission.revoked_at AS permission_revoked_at,
                       permission.revoke_reason AS permission_revoke_reason,
                       permission.version AS permission_version,
                       permission.created_at AS permission_created_at
                FROM fitness.coaching_relationships r
                LEFT JOIN LATERAL (
                    SELECT p.id
                    FROM fitness.coaching_periods p
                    WHERE p.student_id = r.student_id
                      AND p.trainer_id = r.trainer_id
                      AND p.coaching_relationship_id = r.id
                      AND p.mode = 'HUMAN_COACH'::fitness.coaching_mode
                      AND p.started_at <= ?
                      AND (p.ended_at IS NULL OR p.ended_at > ?)
                    ORDER BY p.started_at DESC, p.id DESC
                    LIMIT 1
                ) period ON true
                LEFT JOIN LATERAL (
                    SELECT dsp.*
                    FROM fitness.data_sharing_permissions dsp
                    WHERE dsp.relationship_id = r.id
                      AND dsp.student_id = r.student_id
                      AND dsp.trainer_id = r.trainer_id
                      AND dsp.data_scope = ?::fitness.data_scope_code
                      AND dsp.valid_from <= ?
                      AND (dsp.valid_until IS NULL OR dsp.valid_until > ?)
                      AND dsp.revoked_at IS NULL
                    ORDER BY dsp.valid_from DESC, dsp.id DESC
                    LIMIT 1
                ) permission ON true
                WHERE r.trainer_id = ?
                  AND r.student_id = ?
                  AND r.status = 'ACTIVE'::fitness.coaching_relationship_status
                LIMIT 1
                """, (rs, ignored) -> {
            Permission permission = null;
            UUID permissionId = rs.getObject("permission_id", UUID.class);
            if (permissionId != null) {
                permission = new Permission(permissionId, rs.getObject("relationship_id", UUID.class),
                        rs.getObject("permission_student_id", UUID.class),
                        rs.getObject("permission_trainer_id", UUID.class),
                        DataScope.valueOf(rs.getString("permission_data_scope")),
                        SharingDecision.valueOf(rs.getString("permission_decision")),
                        DataAccessLevel.valueOf(rs.getString("permission_access_level")),
                        instant(rs.getTimestamp("permission_history_from")),
                        instant(rs.getTimestamp("permission_history_until")),
                        instant(rs.getTimestamp("permission_valid_from")),
                        instant(rs.getTimestamp("permission_valid_until")),
                        rs.getObject("permission_granted_by", UUID.class),
                        instant(rs.getTimestamp("permission_revoked_at")),
                        rs.getString("permission_revoke_reason"), rs.getLong("permission_version"),
                        instant(rs.getTimestamp("permission_created_at")));
            }
            return new AuthorityContext(rs.getObject("relationship_id", UUID.class),
                    rs.getLong("relationship_version"), rs.getObject("period_id", UUID.class), permission);
        }, timestamp(at), timestamp(at), scope.name(), timestamp(at), timestamp(at), trainerId, studentId));
    }

    @Override
    public Permission createPermission(Relationship relationship, DataScope scope, SharingDecision decision,
                                       DataAccessLevel accessLevel, Instant historyFrom, Instant historyUntil,
                                       Instant validFrom, Instant validUntil, UUID grantedBy, long version) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id, relationship_id, student_id, trainer_id, data_scope, decision, access_level,
                 history_from, history_until, valid_from, valid_until, granted_by, version)
                VALUES (?, ?, ?, ?, ?::fitness.data_scope_code, ?::fitness.permission_decision,
                        ?::fitness.data_access_level, ?, ?, ?, ?, ?, ?)
                """, id, relationship.id(), relationship.studentId(), relationship.trainerId(),
                scope.name(), decision.name(), accessLevel.name(), timestamp(historyFrom), timestamp(historyUntil),
                timestamp(validFrom), timestamp(validUntil), grantedBy, version);
        return permission(id).orElseThrow();
    }

    @Override
    public Permission revokePermission(Permission permission, UUID actorId, Instant at, String reason) {
        int updated = jdbc.update("""
                UPDATE fitness.data_sharing_permissions
                SET revoked_at = ?, revoke_reason = ?, version = version + 1
                WHERE id = ? AND version = ? AND revoked_at IS NULL
                  AND (valid_until IS NULL OR valid_until > clock_timestamp())
                """, timestamp(at), reason, permission.id(), permission.version());
        if (updated != 1) {
            throw new CoachingFailure(HttpStatus.CONFLICT, "DATA_SHARING_PERMISSION_CONFLICT");
        }
        return permission(permission.id()).orElseThrow();
    }

    @Override
    public Optional<Receipt> receipt(UUID actorId, UUID commandKey) {
        return first(jdbc.query("""
                SELECT command_name, payload_hash, response_json::text
                FROM fitness.coaching_command_receipts
                WHERE actor_id = ? AND command_key = ?
                """, (rs, ignored) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                actorId, commandKey));
    }

    @Override
    public void saveReceipt(UUID actorId, UUID commandKey, String commandName,
                            String payloadHash, String responseJson) {
        jdbc.update("""
                INSERT INTO fitness.coaching_command_receipts
                (actor_id, command_key, command_name, payload_hash, response_json)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, actorId, commandKey, commandName, payloadHash, responseJson);
    }
}
