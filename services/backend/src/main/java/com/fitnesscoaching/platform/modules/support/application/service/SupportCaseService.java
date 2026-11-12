package com.fitnesscoaching.platform.modules.support.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.SupportCaseNotFoundException;
import com.fitnesscoaching.platform.common.exception.TargetUserNotFoundException;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.AddSupportCaseNoteRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.AssignSupportCaseRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.CreateSupportCaseRequest;
import com.fitnesscoaching.platform.modules.support.adapter.in.web.dto.UpdateSupportCaseStatusRequest;
import com.fitnesscoaching.platform.modules.support.adapter.out.persistence.SupportCasePersistenceAdapter;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseDetail;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseEvent;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCasePage;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseSummary;
import com.fitnesscoaching.platform.modules.support.application.port.in.SupportCaseUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class SupportCaseService implements SupportCaseUseCase {

    private static final Set<String> VALID_STATUSES = Set.of("OPEN", "IN_PROGRESS", "WAITING_USER", "RESOLVED", "CLOSED");
    private static final Set<String> VALID_PRIORITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final SupportCasePersistenceAdapter persistence;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public SupportCaseService(
            SupportCasePersistenceAdapter persistence,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.persistence = persistence;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public SupportCasePage searchSupportCases(UUID adminUserId, String search, String status, String priority, int page, int size) {
        verifyAdminAccess(adminUserId, "SUPPORT_MANAGE");
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        long offset = (long) boundedPage * boundedSize;

        List<SupportCaseSummary> content = persistence.searchSupportCases(search, status, priority, boundedSize, offset);
        long total = persistence.countSupportCases(search, status, priority);
        int totalPages = (int) Math.ceil((double) total / boundedSize);

        return new SupportCasePage(content, total, totalPages, boundedPage, boundedSize);
    }

    @Override
    @Transactional(readOnly = true)
    public SupportCaseDetail getSupportCaseDetail(UUID adminUserId, UUID caseId) {
        verifyAdminAccess(adminUserId, "SUPPORT_MANAGE");
        SupportCaseSummary summary = persistence.findSupportCaseSummaryById(caseId)
                .orElseThrow(() -> new SupportCaseNotFoundException("Support case not found: " + caseId));
        List<SupportCaseEvent> events = persistence.findEventsByCaseId(caseId);
        return new SupportCaseDetail(summary, events);
    }

    @Override
    public SupportCaseSummary createSupportCase(UUID adminUserId, CreateSupportCaseRequest request) {
        verifyAdminAccess(adminUserId, "SUPPORT_MANAGE");

        if (request.subject() == null || request.subject().isBlank()) {
            throw new ApplicationValidationException("Subject is required");
        }
        if (request.description() == null || request.description().isBlank()) {
            throw new ApplicationValidationException("Description is required");
        }

        String priority = request.priority() != null ? request.priority().trim().toUpperCase() : "MEDIUM";
        if (!VALID_PRIORITIES.contains(priority)) {
            throw new ApplicationValidationException("Invalid priority: " + priority);
        }

        if (request.requesterId() != null) {
            userAccountStatusQuery.getAccountStatus(request.requesterId())
                    .orElseThrow(() -> new TargetUserNotFoundException("Requester user not found: " + request.requesterId()));
        }

        UUID caseId = UUID.randomUUID();
        String caseNumber = persistence.nextCaseNumber();
        Instant now = Instant.now(clock);

        persistence.insertSupportCase(caseId, caseNumber, request.requesterId(), request.subject().trim(),
                request.description().trim(), priority, "OPEN", now);

        persistence.insertEvent(UUID.randomUUID(), caseId, "CASE_CREATED", adminUserId,
                "Support case created: " + request.subject().trim(), "{}", now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("SUPPORT_CASE_CREATED")
                .targetType("SUPPORT_CASE")
                .targetId(caseId)
                .afterDataJson(toJson(Map.of("caseNumber", caseNumber, "priority", priority)))
                .metadataJson(toJson(Map.of("caseNumber", caseNumber)))
                .occurredAt(now)
                .build());

        return persistence.findSupportCaseSummaryById(caseId)
                .orElseThrow(() -> new SupportCaseNotFoundException("Support case not found after creation: " + caseId));
    }

    @Override
    public SupportCaseDetail updateStatus(UUID adminUserId, UUID caseId, UpdateSupportCaseStatusRequest request) {
        verifyAdminAccess(adminUserId, "SUPPORT_MANAGE");

        SupportCaseSummary current = persistence.findSupportCaseSummaryById(caseId)
                .orElseThrow(() -> new SupportCaseNotFoundException("Support case not found: " + caseId));

        String newStatus = request.status().trim().toUpperCase();
        if (!VALID_STATUSES.contains(newStatus)) {
            throw new ApplicationValidationException("Invalid status: " + newStatus);
        }

        if (current.status().equals(newStatus)) {
            throw new InvalidLifecycleTransitionException("Support case is already in status: " + newStatus);
        }

        Instant now = Instant.now(clock);
        Instant resolvedAt = ("RESOLVED".equals(newStatus) || "CLOSED".equals(newStatus)) ? now : null;

        persistence.updateStatus(caseId, newStatus, resolvedAt, now);

        String note = request.note() != null && !request.note().isBlank()
                ? request.note().trim()
                : "Status changed from " + current.status() + " to " + newStatus;

        persistence.insertEvent(UUID.randomUUID(), caseId, "STATUS_CHANGED", adminUserId, note,
                toJson(Map.of("fromStatus", current.status(), "toStatus", newStatus)), now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("SUPPORT_CASE_STATUS_CHANGED")
                .targetType("SUPPORT_CASE")
                .targetId(caseId)
                .beforeDataJson(toJson(Map.of("status", current.status())))
                .afterDataJson(toJson(Map.of("status", newStatus)))
                .metadataJson(toJson(Map.of("note", note)))
                .occurredAt(now)
                .build());

        return getSupportCaseDetail(adminUserId, caseId);
    }

    @Override
    public SupportCaseDetail assignCase(UUID adminUserId, UUID caseId, AssignSupportCaseRequest request) {
        verifyAdminAccess(adminUserId, "SUPPORT_MANAGE");

        SupportCaseSummary current = persistence.findSupportCaseSummaryById(caseId)
                .orElseThrow(() -> new SupportCaseNotFoundException("Support case not found: " + caseId));

        UUID assignedTo = request.getEffectiveAssignedTo();
        if (assignedTo == null) {
            throw new ApplicationValidationException("Assigned user ID is required");
        }

        AccountStatus assigneeStatus = userAccountStatusQuery.getAccountStatus(assignedTo)
                .orElseThrow(() -> new TargetUserNotFoundException("Assigned user not found: " + assignedTo));

        if (assigneeStatus != AccountStatus.ACTIVE) {
            throw new ApplicationValidationException("Assigned user account must be active");
        }

        if (!userRoleQuery.hasActiveRole(assignedTo, "ADMIN")) {
            throw new ApplicationValidationException("Assigned user must possess an active ADMIN role");
        }

        Instant now = Instant.now(clock);
        persistence.updateAssignee(caseId, assignedTo, now);

        persistence.insertEvent(UUID.randomUUID(), caseId, "CASE_ASSIGNED", adminUserId,
                "Case assigned to user " + assignedTo,
                toJson(Map.of("assignedTo", assignedTo.toString())), now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("SUPPORT_CASE_ASSIGNED")
                .targetType("SUPPORT_CASE")
                .targetId(caseId)
                .beforeDataJson(toJson(Collections.singletonMap("assignedTo", current.assignedTo() != null ? current.assignedTo().toString() : null)))
                .afterDataJson(toJson(Map.of("assignedTo", assignedTo.toString())))
                .occurredAt(now)
                .build());

        return getSupportCaseDetail(adminUserId, caseId);
    }

    @Override
    public SupportCaseDetail addNote(UUID adminUserId, UUID caseId, AddSupportCaseNoteRequest request) {
        verifyAdminAccess(adminUserId, "SUPPORT_MANAGE");

        persistence.findSupportCaseSummaryById(caseId)
                .orElseThrow(() -> new SupportCaseNotFoundException("Support case not found: " + caseId));

        if (request.note() == null || request.note().isBlank()) {
            throw new ApplicationValidationException("Note cannot be empty");
        }

        Instant now = Instant.now(clock);
        persistence.insertEvent(UUID.randomUUID(), caseId, "NOTE_ADDED", adminUserId, request.note().trim(), "{}", now);
        persistence.touchUpdatedAt(caseId, now);

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("SUPPORT_CASE_NOTE_ADDED")
                .targetType("SUPPORT_CASE")
                .targetId(caseId)
                .metadataJson(toJson(Map.of("note", request.note().trim())))
                .occurredAt(now)
                .build());

        return getSupportCaseDetail(adminUserId, caseId);
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
