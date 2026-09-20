package com.fitnesscoaching.platform.modules.user.application.service;

import com.fitnesscoaching.platform.common.exception.SystemRoleNotFoundException;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.domain.RoleActivationResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserRoleServiceUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private UserRoleService userRoleService;

    @BeforeEach
    void setUp() {
        userRoleService = new UserRoleService(jdbcTemplate);
    }

    @Test
    @DisplayName("UserRoleService does not declare or reference any Student-specific exceptions or types")
    void userRoleService_hasNoStudentSpecificCoupling() {
        for (Method method : UserRoleService.class.getDeclaredMethods()) {
            assertThat(method.getReturnType().getName())
                    .as("Method return type should not be Student-specific: " + method.getName())
                    .doesNotContain("Student");

            for (Class<?> paramType : method.getParameterTypes()) {
                assertThat(paramType.getName())
                        .as("Method param should not be Student-specific: " + method.getName())
                        .doesNotContain("Student");
            }

            for (Class<?> exceptionType : method.getExceptionTypes()) {
                assertThat(exceptionType.getName())
                        .as("Method throws should not be Student-specific: " + method.getName())
                        .doesNotContain("Student");
            }
        }
    }

    @Test
    @DisplayName("activateRole returns REVOKED when existing assignment has non-null revoked_at for any role")
    void activateRole_whenExistingAssignmentRevoked_returnsRevoked() {
        UUID userId = UUID.randomUUID();
        String roleCode = "STUDENT";
        Short roleId = (short) 1;

        when(jdbcTemplate.query(eq("SELECT id FROM fitness.roles WHERE code = ?"), any(RowMapper.class), eq(roleCode)))
                .thenReturn(List.of(roleId));

        when(jdbcTemplate.query(
                eq("SELECT revoked_at FROM fitness.user_roles WHERE user_id = ? AND role_id = ?"),
                any(RowMapper.class),
                eq(userId),
                eq(roleId)
        )).thenReturn(Collections.singletonList(Timestamp.from(Instant.now())));

        RoleActivationResult result = userRoleService.activateRole(userId, roleCode, userId);

        assertThat(result).isEqualTo(RoleActivationResult.REVOKED);
    }

    @Test
    @DisplayName("activateRole returns ALREADY_ACTIVE when existing assignment has null revoked_at")
    void activateRole_whenExistingAssignmentActive_returnsAlreadyActive() {
        UUID userId = UUID.randomUUID();
        String roleCode = "TRAINER";
        Short roleId = (short) 2;

        when(jdbcTemplate.query(eq("SELECT id FROM fitness.roles WHERE code = ?"), any(RowMapper.class), eq(roleCode)))
                .thenReturn(List.of(roleId));

        when(jdbcTemplate.query(
                eq("SELECT revoked_at FROM fitness.user_roles WHERE user_id = ? AND role_id = ?"),
                any(RowMapper.class),
                eq(userId),
                eq(roleId)
        )).thenReturn(Collections.singletonList(null));

        RoleActivationResult result = userRoleService.activateRole(userId, roleCode, userId);

        assertThat(result).isEqualTo(RoleActivationResult.ALREADY_ACTIVE);
    }

    @Test
    @DisplayName("activateRole returns ASSIGNED when role was absent and successfully inserted")
    void activateRole_whenAbsent_insertsAndReturnsAssigned() {
        UUID userId = UUID.randomUUID();
        String roleCode = "STUDENT";
        Short roleId = (short) 1;

        when(jdbcTemplate.query(eq("SELECT id FROM fitness.roles WHERE code = ?"), any(RowMapper.class), eq(roleCode)))
                .thenReturn(List.of(roleId));

        // First check: absent
        when(jdbcTemplate.query(
                eq("SELECT revoked_at FROM fitness.user_roles WHERE user_id = ? AND role_id = ?"),
                any(RowMapper.class),
                eq(userId),
                eq(roleId)
        )).thenReturn(Collections.emptyList())
          .thenReturn(Collections.singletonList(null)); // statusAfterInsert: active

        when(jdbcTemplate.update(anyString(), eq(userId), eq(roleId), eq(userId)))
                .thenReturn(1);

        RoleActivationResult result = userRoleService.activateRole(userId, roleCode, userId);

        assertThat(result).isEqualTo(RoleActivationResult.ASSIGNED);
    }

    @Test
    @DisplayName("activateRole throws SystemRoleNotFoundException when role does not exist in roles table")
    void activateRole_whenRoleNotFound_throwsSystemRoleNotFoundException() {
        UUID userId = UUID.randomUUID();
        String roleCode = "UNKNOWN_ROLE";

        when(jdbcTemplate.query(eq("SELECT id FROM fitness.roles WHERE code = ?"), any(RowMapper.class), eq(roleCode)))
                .thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> userRoleService.activateRole(userId, roleCode, userId))
                .isInstanceOf(SystemRoleNotFoundException.class)
                .hasMessageContaining("UNKNOWN_ROLE");
    }

    @Test
    @DisplayName("hasActiveRole returns true when role assignment exists and revoked_at is null")
    void hasActiveRole_whenActive_returnsTrue() {
        UUID userId = UUID.randomUUID();
        String roleCode = "STUDENT";

        when(jdbcTemplate.query(contains("FROM fitness.user_roles"), any(RowMapper.class), eq(userId), eq(roleCode)))
                .thenReturn(Collections.singletonList(null));

        boolean active = userRoleService.hasActiveRole(userId, roleCode);

        assertThat(active).isTrue();
    }

    @Test
    @DisplayName("hasActiveRole returns false when role assignment is absent")
    void hasActiveRole_whenAbsent_returnsFalse() {
        UUID userId = UUID.randomUUID();
        String roleCode = "STUDENT";

        when(jdbcTemplate.query(contains("FROM fitness.user_roles"), any(RowMapper.class), eq(userId), eq(roleCode)))
                .thenReturn(Collections.emptyList());

        boolean active = userRoleService.hasActiveRole(userId, roleCode);

        assertThat(active).isFalse();
    }

    @Test
    @DisplayName("hasActiveRole returns false when role assignment is revoked")
    void hasActiveRole_whenRevoked_returnsFalse() {
        UUID userId = UUID.randomUUID();
        String roleCode = "STUDENT";

        when(jdbcTemplate.query(contains("FROM fitness.user_roles"), any(RowMapper.class), eq(userId), eq(roleCode)))
                .thenReturn(Collections.singletonList(Timestamp.from(Instant.now())));

        boolean active = userRoleService.hasActiveRole(userId, roleCode);

        assertThat(active).isFalse();
    }

    @Test
    @DisplayName("getAccountStatus returns AccountStatus when user exists")
    void getAccountStatus_whenUserExists_returnsStatus() {
        UUID userId = UUID.randomUUID();

        when(jdbcTemplate.query(contains("FROM fitness.users"), any(RowMapper.class), eq(userId)))
                .thenReturn(List.of("ACTIVE"));

        Optional<AccountStatus> status = userRoleService.getAccountStatus(userId);

        assertThat(status).contains(AccountStatus.ACTIVE);
    }
}
