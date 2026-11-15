package com.fitnesscoaching.platform.modules.administration.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.LastAdminProtectionException;
import com.fitnesscoaching.platform.common.exception.SelfActionNotAllowedException;
import com.fitnesscoaching.platform.common.exception.TargetUserNotFoundException;
import com.fitnesscoaching.platform.common.web.RequestIdHolder;
import com.fitnesscoaching.platform.modules.administration.adapter.out.persistence.AdminUserPersistenceAdapter;
import com.fitnesscoaching.platform.modules.administration.application.model.AdminUserDetail;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminUserLifecycleUseCase;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.application.port.out.RefreshTokenPort;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class AdminUserLifecycleService implements AdminUserLifecycleUseCase {

    private final AdminUserPersistenceAdapter persistence;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;
    private final RefreshTokenPort refreshTokenPort;
    private final AuditService auditService;
    private final AdminStepUpService adminStepUpService;
    private final Clock clock;

    public AdminUserLifecycleService(
            AdminUserPersistenceAdapter persistence,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery,
            RefreshTokenPort refreshTokenPort,
            AuditService auditService,
            AdminStepUpService adminStepUpService,
            Clock clock
    ) {
        this.persistence = persistence;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
        this.refreshTokenPort = refreshTokenPort;
        this.auditService = auditService;
        this.adminStepUpService = adminStepUpService;
        this.clock = clock;
    }

    @Override
    public AdminUserDetail suspendUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId) {
        verifyActiveAdminWithPermission(adminUserId, "USER_SUSPEND");
        validateReason(reason);

        if (adminUserId.equals(targetUserId)) {
            throw new SelfActionNotAllowedException("Administrator cannot suspend their own account");
        }

        AdminUserDetail target = persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));

        if (target.status() == AccountStatus.SUSPENDED) {
            throw new InvalidLifecycleTransitionException("User account is already suspended");
        }
        if (target.status() != AccountStatus.ACTIVE) {
            throw new InvalidLifecycleTransitionException("Cannot suspend an account with status: " + target.status());
        }

        if (target.roles().contains("ADMIN") && persistence.countActiveAdmins() <= 1) {
            throw new LastAdminProtectionException("Cannot suspend the only remaining active platform administrator");
        }

        UUID stepUpId = adminStepUpService.resolveOrVerifyStepUp(adminUserId, "SUSPEND_ACCOUNT", stepUpPassword, stepUpAuthId);
        Instant now = Instant.now(clock);

        persistence.updateUserStatus(targetUserId, AccountStatus.SUSPENDED, now);
        persistence.recordAccountStatusHistory(targetUserId, target.status(), AccountStatus.SUSPENDED, adminUserId, reason.trim(), now);

        int revokedTokens = refreshTokenPort.revokeActiveForUser(targetUserId, now, "ACCOUNT_SUSPENDED");
        UUID actionId = persistence.recordAdminAction(adminUserId, "SUSPEND_ACCOUNT", targetUserId, reason.trim(), "USER_SUSPEND", stepUpId, now);
        persistence.recordSessionRevocationEvent(targetUserId, adminUserId, reason.trim(), actionId, revokedTokens, now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("USER_ACCOUNT_SUSPENDED")
                .targetType("USER")
                .targetId(targetUserId)
                .requestId(RequestIdHolder.getAsUuid())
                .beforeDataJson("{\"status\":\"" + target.status().name() + "\"}")
                .afterDataJson("{\"status\":\"SUSPENDED\"}")
                .metadataJson("{\"reason\":\"" + escapeJson(reason.trim()) + "\",\"revokedTokens\":" + revokedTokens + "}")
                .occurredAt(now)
                .build());

        return persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));
    }

    @Override
    public AdminUserDetail restoreUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId) {
        verifyActiveAdminWithPermission(adminUserId, "USER_SUSPEND");
        validateReason(reason);

        AdminUserDetail target = persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));

        if (target.status() == AccountStatus.ACTIVE) {
            throw new InvalidLifecycleTransitionException("User account is already active");
        }
        if (target.status() == AccountStatus.DISABLED) {
            throw new InvalidLifecycleTransitionException("Permanently disabled account cannot be restored under standard administrative workflow");
        }
        if (target.status() != AccountStatus.SUSPENDED) {
            throw new InvalidLifecycleTransitionException("Only suspended accounts can be restored. Current status: " + target.status());
        }

        UUID stepUpId = adminStepUpService.resolveOrVerifyStepUp(adminUserId, "RESTORE_ACCOUNT", stepUpPassword, stepUpAuthId);
        Instant now = Instant.now(clock);

        persistence.updateUserStatus(targetUserId, AccountStatus.ACTIVE, now);
        persistence.recordAccountStatusHistory(targetUserId, AccountStatus.SUSPENDED, AccountStatus.ACTIVE, adminUserId, reason.trim(), now);
        UUID actionId = persistence.recordAdminAction(adminUserId, "RESTORE_ACCOUNT", targetUserId, reason.trim(), "USER_SUSPEND", stepUpId, now);
        persistence.recordSessionRevocationEvent(targetUserId, adminUserId, "Account restored: " + reason.trim(), actionId, 0, now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("USER_ACCOUNT_RESTORED")
                .targetType("USER")
                .targetId(targetUserId)
                .requestId(RequestIdHolder.getAsUuid())
                .beforeDataJson("{\"status\":\"SUSPENDED\"}")
                .afterDataJson("{\"status\":\"ACTIVE\"}")
                .metadataJson("{\"reason\":\"" + escapeJson(reason.trim()) + "\"}")
                .occurredAt(now)
                .build());

        return persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));
    }

    @Override
    public AdminUserDetail disableUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId) {
        verifyActiveAdminWithPermission(adminUserId, "USER_DISABLE");
        validateReason(reason);

        if (adminUserId.equals(targetUserId)) {
            throw new SelfActionNotAllowedException("Administrator cannot disable their own account");
        }

        AdminUserDetail target = persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));

        if (target.status() == AccountStatus.DISABLED) {
            throw new InvalidLifecycleTransitionException("User account is already disabled");
        }
        if (target.status() != AccountStatus.ACTIVE && target.status() != AccountStatus.SUSPENDED) {
            throw new InvalidLifecycleTransitionException("Cannot disable an account with status: " + target.status());
        }

        if (target.roles().contains("ADMIN") && persistence.countActiveAdmins() <= 1) {
            throw new LastAdminProtectionException("Cannot disable the only remaining active platform administrator");
        }

        UUID stepUpId = adminStepUpService.resolveOrVerifyStepUp(adminUserId, "DISABLE_ACCOUNT", stepUpPassword, stepUpAuthId);
        Instant now = Instant.now(clock);

        persistence.updateUserStatus(targetUserId, AccountStatus.DISABLED, now);
        persistence.recordAccountStatusHistory(targetUserId, target.status(), AccountStatus.DISABLED, adminUserId, reason.trim(), now);

        int revokedTokens = refreshTokenPort.revokeActiveForUser(targetUserId, now, "ACCOUNT_DISABLED");
        UUID actionId = persistence.recordAdminAction(adminUserId, "DISABLE_ACCOUNT", targetUserId, reason.trim(), "USER_DISABLE", stepUpId, now);
        persistence.recordSessionRevocationEvent(targetUserId, adminUserId, reason.trim(), actionId, revokedTokens, now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("USER_ACCOUNT_DISABLED")
                .targetType("USER")
                .targetId(targetUserId)
                .requestId(RequestIdHolder.getAsUuid())
                .beforeDataJson("{\"status\":\"" + target.status().name() + "\"}")
                .afterDataJson("{\"status\":\"DISABLED\"}")
                .metadataJson("{\"reason\":\"" + escapeJson(reason.trim()) + "\",\"revokedTokens\":" + revokedTokens + "}")
                .occurredAt(now)
                .build());

        return persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));
    }

    @Override
    public int forceLogoutUser(UUID adminUserId, UUID targetUserId, String reason, String stepUpPassword, UUID stepUpAuthId) {
        verifyActiveAdminWithPermission(adminUserId, "SESSION_REVOKE");
        validateReason(reason);

        if (adminUserId.equals(targetUserId)) {
            throw new SelfActionNotAllowedException("Administrator cannot force-logout their own account via administrative endpoint");
        }

        persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));

        UUID stepUpId = adminStepUpService.resolveOrVerifyStepUp(adminUserId, "FORCE_LOGOUT", stepUpPassword, stepUpAuthId);
        Instant now = Instant.now(clock);

        // Serialize against concurrent login/refresh operations on target user
        persistence.lockUser(targetUserId);

        int revokedTokens = refreshTokenPort.revokeActiveForUser(targetUserId, now, "ADMIN_FORCE_LOGOUT");
        UUID actionId = persistence.recordAdminAction(adminUserId, "FORCE_LOGOUT", targetUserId, reason.trim(), "SESSION_REVOKE", stepUpId, now);
        persistence.recordSessionRevocationEvent(targetUserId, adminUserId, reason.trim(), actionId, revokedTokens, now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("USER_SESSIONS_REVOKED")
                .targetType("USER")
                .targetId(targetUserId)
                .requestId(RequestIdHolder.getAsUuid())
                .metadataJson("{\"reason\":\"" + escapeJson(reason.trim()) + "\",\"revokedTokens\":" + revokedTokens + "}")
                .occurredAt(now)
                .build());

        return revokedTokens;
    }

    @Override
    public AdminUserDetail changeUserRole(UUID adminUserId, UUID targetUserId, String roleCode, String action, String reason, String stepUpPassword, UUID stepUpAuthId) {
        verifyActiveAdminWithPermission(adminUserId, "USER_ROLE_MANAGE");
        validateReason(reason);

        if (roleCode == null || roleCode.isBlank()) {
            throw new ApplicationValidationException("Role code is required");
        }
        String normalizedRole = roleCode.trim().toUpperCase();

        if (action == null || (!action.equalsIgnoreCase("ASSIGN") && !action.equalsIgnoreCase("REVOKE"))) {
            throw new ApplicationValidationException("Action must be either ASSIGN or REVOKE");
        }
        boolean isAssign = action.equalsIgnoreCase("ASSIGN");

        AdminUserDetail target = persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));

        if (!isAssign && normalizedRole.equals("ADMIN")) {
            if (adminUserId.equals(targetUserId)) {
                throw new SelfActionNotAllowedException("Administrator cannot revoke their own ADMIN role");
            }
            if (persistence.countActiveAdmins() <= 1) {
                throw new LastAdminProtectionException("Cannot revoke the role of the only remaining active administrator");
            }
        }

        UUID stepUpId = adminStepUpService.resolveOrVerifyStepUp(adminUserId, "CHANGE_USER_ROLE", stepUpPassword, stepUpAuthId);
        Instant now = Instant.now(clock);

        UUID actionId = persistence.recordAdminAction(adminUserId, "CHANGE_USER_ROLE", targetUserId, reason.trim(), "USER_ROLE_MANAGE", stepUpId, now);

        int revokedTokens = 0;
        if (isAssign) {
            persistence.assignRole(targetUserId, normalizedRole, adminUserId, now);
        } else {
            persistence.revokeRole(targetUserId, normalizedRole, now);
            if (normalizedRole.equals("ADMIN")) {
                persistence.lockUser(targetUserId);
                revokedTokens = refreshTokenPort.revokeActiveForUser(targetUserId, now, "ADMIN_ROLE_REVOKED");
                persistence.recordSessionRevocationEvent(targetUserId, adminUserId, "ADMIN role revoked: " + reason.trim(), actionId, revokedTokens, now);
            }
        }

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action(isAssign ? "USER_ROLE_ASSIGNED" : "USER_ROLE_REVOKED")
                .targetType("USER")
                .targetId(targetUserId)
                .requestId(RequestIdHolder.getAsUuid())
                .metadataJson("{\"role\":\"" + normalizedRole + "\",\"action\":\"" + action.toUpperCase() + "\",\"reason\":\"" + escapeJson(reason.trim()) + "\"" + (revokedTokens > 0 ? ",\"revokedTokens\":" + revokedTokens : "") + "}")
                .occurredAt(now)
                .build());

        return persistence.findUserDetail(targetUserId)
                .orElseThrow(() -> new TargetUserNotFoundException("User not found with ID: " + targetUserId));
    }

    private void verifyActiveAdminWithPermission(UUID adminUserId, String requiredPermission) {
        if (adminUserId == null) {
            throw new AccessDeniedException("Admin user ID is required");
        }
        AccountStatus status = userAccountStatusQuery.getAccountStatus(adminUserId)
                .orElseThrow(() -> new AccessDeniedException("User does not exist"));

        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Administrator account is not active");
        }

        if (!userRoleQuery.hasActiveRole(adminUserId, "ADMIN")) {
            throw new AccessDeniedException("User does not possess an active ADMIN role");
        }

        List<String> permissions = userPermissionQuery.getEffectivePermissions(adminUserId);
        if (!permissions.contains(requiredPermission)) {
            throw new AccessDeniedException("Administrator lacks required permission: " + requiredPermission);
        }
    }

    private void validateReason(String reason) {
        if (reason == null || reason.trim().length() < 5) {
            throw new ApplicationValidationException("A valid non-blank reason of at least 5 characters is required",
                    List.of(new FieldErrorDto("reason", "MinLength", "Reason must be at least 5 characters")));
        }
        if (reason.trim().length() > 2000) {
            throw new ApplicationValidationException("Reason cannot exceed 2000 characters",
                    List.of(new FieldErrorDto("reason", "MaxLength", "Reason cannot exceed 2000 characters")));
        }
    }

    private static String escapeJson(String raw) {
        return raw.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }
}
