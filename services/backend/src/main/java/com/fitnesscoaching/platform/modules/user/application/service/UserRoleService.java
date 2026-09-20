package com.fitnesscoaching.platform.modules.user.application.service;

import com.fitnesscoaching.platform.common.exception.SystemRoleNotFoundException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleUseCase;
import com.fitnesscoaching.platform.modules.user.domain.RoleActivationResult;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class UserRoleService implements UserRoleUseCase, UserRoleQuery, UserAccountStatusQuery {

    private final JdbcTemplate jdbcTemplate;

    public UserRoleService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountStatus> getAccountStatus(UUID userId) {
        List<String> results = jdbcTemplate.query(
                "SELECT status FROM fitness.users WHERE id = ?",
                (rs, rowNum) -> rs.getString("status"),
                userId
        );
        if (results.isEmpty()) {
            return Optional.empty();
        }
        String statusStr = results.get(0);
        return Optional.ofNullable(statusStr != null ? AccountStatus.valueOf(statusStr) : null);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasActiveRole(UUID userId, String roleCode) {
        List<Timestamp> results = jdbcTemplate.query(
                """
                SELECT ur.revoked_at
                FROM fitness.user_roles ur
                JOIN fitness.roles r ON r.id = ur.role_id
                WHERE ur.user_id = ? AND r.code = ?
                """,
                (rs, rowNum) -> rs.getTimestamp("revoked_at"),
                userId,
                roleCode
        );

        if (results.isEmpty()) {
            return false;
        }

        return results.get(0) == null;
    }

    @Override
    public RoleActivationResult activateRole(UUID userId, String roleCode, UUID assignedBy) {
        List<Short> roleIds = jdbcTemplate.query(
                "SELECT id FROM fitness.roles WHERE code = ?",
                (rs, rowNum) -> rs.getShort("id"),
                roleCode
        );

        if (roleIds.isEmpty()) {
            throw new SystemRoleNotFoundException("System role '" + roleCode + "' does not exist.");
        }
        Short roleId = roleIds.get(0);

        List<Timestamp> existingAssignments = jdbcTemplate.query(
                "SELECT revoked_at FROM fitness.user_roles WHERE user_id = ? AND role_id = ?",
                (rs, rowNum) -> rs.getTimestamp("revoked_at"),
                userId,
                roleId
        );

        if (!existingAssignments.isEmpty()) {
            Timestamp revokedAt = existingAssignments.get(0);
            if (revokedAt != null) {
                return RoleActivationResult.REVOKED;
            }
            return RoleActivationResult.ALREADY_ACTIVE;
        }

        int rowsAffected = jdbcTemplate.update(
                """
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                VALUES (?, ?, ?, now())
                ON CONFLICT (user_id, role_id) DO NOTHING
                """,
                userId,
                roleId,
                assignedBy
        );

        List<Timestamp> statusAfterInsert = jdbcTemplate.query(
                "SELECT revoked_at FROM fitness.user_roles WHERE user_id = ? AND role_id = ?",
                (rs, rowNum) -> rs.getTimestamp("revoked_at"),
                userId,
                roleId
        );

        if (statusAfterInsert.isEmpty()) {
            throw new IllegalStateException("Failed to activate role '" + roleCode + "' for user: " + userId);
        }

        if (statusAfterInsert.get(0) != null) {
            return RoleActivationResult.REVOKED;
        }

        return rowsAffected > 0 ? RoleActivationResult.ASSIGNED : RoleActivationResult.ALREADY_ACTIVE;
    }
}
