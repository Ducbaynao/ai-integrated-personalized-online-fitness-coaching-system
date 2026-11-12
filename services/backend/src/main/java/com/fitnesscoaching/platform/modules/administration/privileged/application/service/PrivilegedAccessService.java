package com.fitnesscoaching.platform.modules.administration.privileged.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.PrivilegedAccessNotFoundException;
import com.fitnesscoaching.platform.common.exception.TargetUserNotFoundException;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminStepUpUseCase;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.RequestPrivilegedAccessDto;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.in.web.dto.RevokePrivilegedAccessDto;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.out.persistence.PrivilegedAccessPersistenceAdapter;
import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessModel;
import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessPage;
import com.fitnesscoaching.platform.modules.administration.privileged.application.port.in.PrivilegedAccessQuery;
import com.fitnesscoaching.platform.modules.administration.privileged.application.port.in.PrivilegedAccessUseCase;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class PrivilegedAccessService implements PrivilegedAccessUseCase, PrivilegedAccessQuery {

    private static final Set<String> VALID_PURPOSES = Set.of(
            "SUPPORT", "MODERATION", "SECURITY", "LEGAL_COMPLIANCE", "DATA_CORRECTION"
    );

    private final PrivilegedAccessPersistenceAdapter persistence;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;
    private final AuditService auditService;
    private final AdminStepUpUseCase adminStepUpService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public PrivilegedAccessService(
            PrivilegedAccessPersistenceAdapter persistence,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery,
            AuditService auditService,
            AdminStepUpUseCase adminStepUpService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.persistence = persistence;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
        this.auditService = auditService;
        this.adminStepUpService = adminStepUpService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public PrivilegedAccessPage searchRequests(UUID adminUserId, String status, String purpose, int page, int size) {
        verifyAdminAccess(adminUserId, "PRIVILEGED_ACCESS_MANAGE");
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        long offset = (long) boundedPage * boundedSize;

        List<PrivilegedAccessModel> content = persistence.searchRequests(status, purpose, boundedSize, offset);
        long total = persistence.countRequests(status, purpose);
        int totalPages = (int) Math.ceil((double) total / boundedSize);

        return new PrivilegedAccessPage(content, total, totalPages, boundedPage, boundedSize);
    }

    @Override
    @Transactional(readOnly = true)
    public PrivilegedAccessModel getRequest(UUID adminUserId, UUID requestId) {
        verifyAdminAccess(adminUserId, "PRIVILEGED_ACCESS_MANAGE");
        return persistence.findById(requestId)
                .orElseThrow(() -> new PrivilegedAccessNotFoundException("Privileged access request not found: " + requestId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PrivilegedAccessModel> getActiveRequestsForAdmin(UUID adminUserId) {
        verifyAdminAccess(adminUserId, "PRIVILEGED_ACCESS_MANAGE");
        Instant now = Instant.now(clock);
        return persistence.findActiveForAdmin(adminUserId, now);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PrivilegedAccessModel> findById(UUID requestId) {
        if (requestId == null) {
            return Optional.empty();
        }
        return persistence.findById(requestId);
    }

    @Override
    public PrivilegedAccessModel requestPrivilegedAccess(UUID adminUserId, RequestPrivilegedAccessDto request) {
        verifyAdminAccess(adminUserId, "PRIVILEGED_ACCESS_MANAGE");

        String purpose = request.purpose().trim().toUpperCase();
        if (!VALID_PURPOSES.contains(purpose)) {
            throw new ApplicationValidationException("Invalid privileged access purpose: " + purpose);
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApplicationValidationException("Reason is mandatory for privileged data access");
        }

        if (request.targetUserId() == null && (request.targetResourceType() == null || request.targetResourceId() == null)) {
            throw new ApplicationValidationException("Must specify target user ID or target resource type and ID");
        }

        if (request.targetUserId() != null) {
            userAccountStatusQuery.getAccountStatus(request.targetUserId())
                    .orElseThrow(() -> new TargetUserNotFoundException("Target user not found: " + request.targetUserId()));
        }

        if (request.durationMinutes() != null) {
            int durationMinutes = request.durationMinutes();
            if (durationMinutes < 1 || durationMinutes > 480) {
                throw new ApplicationValidationException("Duration must be between 1 and 480 minutes");
            }
        }
        int durationMinutes = request.durationMinutes() != null ? request.durationMinutes() : 30;

        UUID stepUpId;
        try {
            stepUpId = adminStepUpService.resolveOrVerifyStepUp(
                    adminUserId, "PRIVILEGED_ACCESS_REQUEST",
                    request.stepUpPassword(), request.stepUpAuthenticationId());
        } catch (AccessDeniedException ex) {
            if (request.stepUpAuthenticationId() != null && (request.stepUpPassword() == null || request.stepUpPassword().isBlank())) {
                stepUpId = adminStepUpService.resolveOrVerifyStepUp(
                        adminUserId, "PRIVILEGED_DATA_ACCESS",
                        null, request.stepUpAuthenticationId());
            } else {
                throw ex;
            }
        }

        UUID requestId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(Duration.ofMinutes(durationMinutes));

        String accessScopeJson = "{}";
        if (request.accessScope() != null) {
            if (request.accessScope() instanceof String str) {
                if (!str.isBlank()) {
                    accessScopeJson = str;
                }
            } else {
                try {
                    accessScopeJson = objectMapper.writeValueAsString(request.accessScope());
                } catch (Exception ignored) {
                }
            }
        }

        persistence.insertRequest(
                requestId, adminUserId, adminUserId, request.targetUserId(),
                request.targetResourceType(), request.targetResourceId(), purpose,
                accessScopeJson, request.reason().trim(), request.relatedSupportCaseId(),
                request.relatedModerationCaseId(), "ACTIVE", now, now, now, expiresAt
        );

        persistence.insertEvent(UUID.randomUUID(), requestId, adminUserId, "OPENED",
                request.targetResourceType(), request.targetResourceId(),
                toJson(Map.of("durationMinutes", durationMinutes, "stepUpAuthenticationId", stepUpId.toString())), now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("PRIVILEGED_DATA_ACCESS_OPENED")
                .targetType("PRIVILEGED_ACCESS")
                .targetId(requestId)
                .afterDataJson(toJson(Map.of("purpose", purpose, "durationMinutes", durationMinutes)))
                .metadataJson(toJson(Map.of("reason", request.reason().trim(), "stepUpAuthenticationId", stepUpId.toString())))
                .occurredAt(now)
                .build());

        return persistence.findById(requestId)
                .orElseThrow(() -> new PrivilegedAccessNotFoundException("Privileged access request not found after insert: " + requestId));
    }

    @Override
    public PrivilegedAccessModel revokePrivilegedAccess(UUID adminUserId, UUID requestId, RevokePrivilegedAccessDto request) {
        verifyAdminAccess(adminUserId, "PRIVILEGED_ACCESS_MANAGE");

        if (request == null || request.reason() == null || request.reason().isBlank()) {
            throw new ApplicationValidationException("Revocation reason is required");
        }

        PrivilegedAccessModel current = persistence.findById(requestId)
                .orElseThrow(() -> new PrivilegedAccessNotFoundException("Privileged access request not found: " + requestId));

        if (!"ACTIVE".equalsIgnoreCase(current.status())) {
            throw new ApplicationValidationException("Cannot revoke a non-active access request. Current status: " + current.status());
        }

        Instant now = Instant.now(clock);
        persistence.revokeRequest(requestId, now);

        persistence.insertEvent(UUID.randomUUID(), requestId, adminUserId, "REVOKED",
                current.targetResourceType(), current.targetResourceId(),
                toJson(Map.of("reason", request.reason().trim())), now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("PRIVILEGED_DATA_ACCESS_REVOKED")
                .targetType("PRIVILEGED_ACCESS")
                .targetId(requestId)
                .metadataJson(toJson(Map.of("reason", request.reason().trim())))
                .occurredAt(now)
                .build());

        return persistence.findById(requestId)
                .orElseThrow(() -> new PrivilegedAccessNotFoundException("Privileged access request not found: " + requestId));
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void verifyAdminAccess(UUID adminUserId, String permissionCode) {
        if (adminUserId == null) {
            throw new AccessDeniedException("Authentication required");
        }
        AccountStatus status = userAccountStatusQuery.getAccountStatus(adminUserId)
                .orElseThrow(() -> new AccessDeniedException("User not found"));
        if (status != AccountStatus.ACTIVE) {
            throw new AccessDeniedException("Account is not active");
        }
        if (!userRoleQuery.hasActiveRole(adminUserId, "ADMIN")) {
            throw new AccessDeniedException("Admin role required");
        }
        List<String> permissions = userPermissionQuery.getEffectivePermissions(adminUserId);
        if (!permissions.contains(permissionCode)) {
            throw new AccessDeniedException("Missing permission: " + permissionCode);
        }
    }
}
