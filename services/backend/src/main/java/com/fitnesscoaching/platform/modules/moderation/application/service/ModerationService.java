package com.fitnesscoaching.platform.modules.moderation.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.ModerationCaseNotFoundException;
import com.fitnesscoaching.platform.common.exception.SelfActionNotAllowedException;
import com.fitnesscoaching.platform.common.exception.TargetUserNotFoundException;
import com.fitnesscoaching.platform.modules.administration.application.port.in.AdminUserLifecycleUseCase;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.CreateModerationCaseRequest;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.PerformModerationActionRequest;
import com.fitnesscoaching.platform.modules.moderation.adapter.in.web.dto.UpdateModerationStatusRequest;
import com.fitnesscoaching.platform.modules.moderation.adapter.out.persistence.ModerationPersistenceAdapter;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationActionModel;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseDetail;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCasePage;
import com.fitnesscoaching.platform.modules.moderation.application.model.ModerationCaseSummary;
import com.fitnesscoaching.platform.modules.moderation.application.port.in.ModerationUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class ModerationService implements ModerationUseCase {

    private static final Set<String> VALID_STATUSES = Set.of("OPEN", "IN_REVIEW", "RESOLVED", "CLOSED");
    private static final Set<String> VALID_ACTIONS = Set.of(
            "WARNING", "CONTENT_RESTRICTION", "ACCOUNT_RESTRICTION", "SUSPENSION", "DISABLE", "ESCALATION", "NO_ACTION"
    );
    private static final Set<String> VALID_PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final ModerationPersistenceAdapter persistence;
    private final AdminUserLifecycleUseCase adminUserLifecycleUseCase;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ModerationService(
            ModerationPersistenceAdapter persistence,
            AdminUserLifecycleUseCase adminUserLifecycleUseCase,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.persistence = persistence;
        this.adminUserLifecycleUseCase = adminUserLifecycleUseCase;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationCasePage searchModerationCases(UUID adminUserId, String status, String priority, int page, int size) {
        verifyAdminAccess(adminUserId, "MODERATION_MANAGE");
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        long offset = (long) boundedPage * boundedSize;

        List<ModerationCaseSummary> content = persistence.searchModerationCases(status, priority, boundedSize, offset);
        long total = persistence.countModerationCases(status, priority);
        int totalPages = (int) Math.ceil((double) total / boundedSize);

        return new ModerationCasePage(content, total, totalPages, boundedPage, boundedSize);
    }

    @Override
    @Transactional(readOnly = true)
    public ModerationCaseDetail getModerationCaseDetail(UUID adminUserId, UUID caseId) {
        verifyAdminAccess(adminUserId, "MODERATION_MANAGE");
        ModerationCaseSummary summary = persistence.findModerationCaseSummaryById(caseId)
                .orElseThrow(() -> new ModerationCaseNotFoundException("Moderation case not found: " + caseId));
        List<ModerationActionModel> actions = persistence.findActionsByCaseId(caseId);
        return new ModerationCaseDetail(summary, actions);
    }

    @Override
    public ModerationCaseSummary createModerationCase(UUID adminUserId, CreateModerationCaseRequest request) {
        verifyAdminAccess(adminUserId, "MODERATION_MANAGE");

        if (request.subjectUserId() != null) {
            userAccountStatusQuery.getAccountStatus(request.subjectUserId())
                    .orElseThrow(() -> new TargetUserNotFoundException("Subject user not found: " + request.subjectUserId()));
        }

        String priority = request.priority() != null ? request.priority().trim().toUpperCase() : "MEDIUM";
        if (!VALID_PRIORITIES.contains(priority)) {
            throw new ApplicationValidationException("Invalid priority: " + priority);
        }

        UUID caseId = UUID.randomUUID();
        Instant now = Instant.now(clock);

        persistence.insertModerationCase(caseId, request.reportId(), request.subjectUserId(), priority, "OPEN", now);

        if (request.notes() != null && !request.notes().isBlank()) {
            persistence.insertAction(UUID.randomUUID(), caseId, "NO_ACTION", request.subjectUserId(),
                    adminUserId, "Case opened with notes: " + request.notes().trim(), "{}", now);
        } else if (request.reason() != null && !request.reason().isBlank()) {
            persistence.insertAction(UUID.randomUUID(), caseId, "NO_ACTION", request.subjectUserId(),
                    adminUserId, "Case opened: " + request.reason().trim(), "{}", now);
        }

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("MODERATION_CASE_CREATED")
                .targetType("MODERATION_CASE")
                .targetId(caseId)
                .afterDataJson("{\"priority\":\"" + priority + "\"}")
                .occurredAt(now)
                .build());

        return persistence.findModerationCaseSummaryById(caseId)
                .orElseThrow(() -> new ModerationCaseNotFoundException("Moderation case not found after creation: " + caseId));
    }

    @Override
    public ModerationCaseDetail updateStatus(UUID adminUserId, UUID caseId, UpdateModerationStatusRequest request) {
        verifyAdminAccess(adminUserId, "MODERATION_MANAGE");

        ModerationCaseSummary current = persistence.findModerationCaseSummaryById(caseId)
                .orElseThrow(() -> new ModerationCaseNotFoundException("Moderation case not found: " + caseId));

        String newStatus = request.status().trim().toUpperCase();
        if (!VALID_STATUSES.contains(newStatus)) {
            throw new ApplicationValidationException("Invalid status: " + newStatus);
        }

        if (current.status().equals(newStatus)) {
            throw new InvalidLifecycleTransitionException("Moderation case is already in status: " + newStatus);
        }

        Instant now = Instant.now(clock);
        Instant resolvedAt = ("RESOLVED".equals(newStatus) || "CLOSED".equals(newStatus)) ? now : null;
        String resolution = request.resolution() != null ? request.resolution().trim() : current.resolution();

        persistence.updateStatus(caseId, newStatus, resolution, resolvedAt, now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("MODERATION_CASE_STATUS_CHANGED")
                .targetType("MODERATION_CASE")
                .targetId(caseId)
                .beforeDataJson(toJson(Map.of("status", current.status())))
                .afterDataJson(toJson(Map.of("status", newStatus)))
                .metadataJson(resolution != null && !resolution.isBlank()
                        ? toJson(Map.of("resolution", resolution))
                        : "{}")
                .occurredAt(now)
                .build());

        return getModerationCaseDetail(adminUserId, caseId);
    }

    @Override
    public ModerationActionModel performAction(UUID adminUserId, UUID caseId, PerformModerationActionRequest request) {
        verifyAdminAccess(adminUserId, "MODERATION_MANAGE");

        ModerationCaseSummary current = persistence.findModerationCaseSummaryById(caseId)
                .orElseThrow(() -> new ModerationCaseNotFoundException("Moderation case not found: " + caseId));

        String actionType = request.actionType().trim().toUpperCase();
        if (!VALID_ACTIONS.contains(actionType)) {
            throw new ApplicationValidationException("Invalid moderation action type: " + actionType);
        }

        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApplicationValidationException("Reason is mandatory for moderation actions");
        }

        UUID targetUserId = request.targetUserId() != null ? request.targetUserId() : current.subjectUserId();
        if (targetUserId != null) {
            userAccountStatusQuery.getAccountStatus(targetUserId)
                    .orElseThrow(() -> new TargetUserNotFoundException("Target user not found: " + targetUserId));

            if (adminUserId.equals(targetUserId)) {
                throw new SelfActionNotAllowedException("Administrator cannot perform moderation action against their own account");
            }
        }

        Instant now = Instant.now(clock);

        // Enforce concrete business lifecycle impact for SUSPENSION and DISABLE actions via A05/M8 account administration
        if ("SUSPENSION".equals(actionType)) {
            if (targetUserId == null) {
                throw new ApplicationValidationException("Target user ID is required for account suspension");
            }
            adminUserLifecycleUseCase.suspendUser(
                    adminUserId, targetUserId, request.reason().trim(),
                    request.stepUpPassword(), request.stepUpAuthenticationId()
            );
        } else if ("DISABLE".equals(actionType)) {
            if (targetUserId == null) {
                throw new ApplicationValidationException("Target user ID is required for account disablement");
            }
            adminUserLifecycleUseCase.disableUser(
                    adminUserId, targetUserId, request.reason().trim(),
                    request.stepUpPassword(), request.stepUpAuthenticationId()
            );
        } else if ("WARNING".equals(actionType)) {
            if (targetUserId != null) {
                persistence.insertSecurityEvent(
                        targetUserId, "USER_MODERATION_WARNING", "MEDIUM",
                        toJson(Map.of("caseId", caseId.toString(), "reason", request.reason().trim())),
                        now
                );
            }
        }

        UUID actionId = UUID.randomUUID();

        String metadataJson = "{}";
        if (request.metadata() != null && !request.metadata().isEmpty()) {
            try {
                metadataJson = objectMapper.writeValueAsString(request.metadata());
            } catch (Exception ignored) {
            }
        }

        persistence.insertAction(actionId, caseId, actionType, targetUserId, adminUserId, request.reason().trim(), metadataJson, now);
        persistence.touchUpdatedAt(caseId, now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("MODERATION_ACTION_PERFORMED")
                .targetType("MODERATION_CASE")
                .targetId(caseId)
                .afterDataJson(toJson(Map.of("actionType", actionType, "actionId", actionId.toString())))
                .metadataJson(toJson(Map.of("reason", request.reason().trim())))
                .occurredAt(now)
                .build());

        return persistence.findActionsByCaseId(caseId).stream()
                .filter(a -> a.id().equals(actionId))
                .findFirst()
                .orElse(new ModerationActionModel(actionId, caseId, actionType, targetUserId, null, adminUserId, null, request.reason().trim(), metadataJson, now));
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

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            return "{}";
        }
    }
}
