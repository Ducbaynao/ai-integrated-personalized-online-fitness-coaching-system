package com.fitnesscoaching.platform.modules.support.correction.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.InvalidLifecycleTransitionException;
import com.fitnesscoaching.platform.common.exception.PrivilegedAccessNotFoundException;
import com.fitnesscoaching.platform.common.exception.SupportCaseNotFoundException;
import com.fitnesscoaching.platform.modules.administration.application.service.AdminStepUpService;
import com.fitnesscoaching.platform.modules.administration.privileged.adapter.out.persistence.PrivilegedAccessPersistenceAdapter;
import com.fitnesscoaching.platform.modules.administration.privileged.application.model.PrivilegedAccessModel;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.support.adapter.out.persistence.SupportCasePersistenceAdapter;
import com.fitnesscoaching.platform.modules.support.application.model.SupportCaseSummary;
import com.fitnesscoaching.platform.modules.support.correction.adapter.in.web.dto.ExecuteDataCorrectionRequest;
import com.fitnesscoaching.platform.modules.support.correction.adapter.out.persistence.DataCorrectionPersistenceAdapter;
import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionModel;
import com.fitnesscoaching.platform.modules.support.correction.application.model.DataCorrectionPage;
import com.fitnesscoaching.platform.modules.support.correction.application.port.in.DataCorrectionUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class DataCorrectionService implements DataCorrectionUseCase {

    private final DataCorrectionPersistenceAdapter persistence;
    private final SupportCasePersistenceAdapter supportCasePersistence;
    private final PrivilegedAccessPersistenceAdapter privilegedAccessPersistence;
    private final AdminStepUpService adminStepUpService;
    private final UserAccountStatusQuery userAccountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DataCorrectionService(
            DataCorrectionPersistenceAdapter persistence,
            SupportCasePersistenceAdapter supportCasePersistence,
            PrivilegedAccessPersistenceAdapter privilegedAccessPersistence,
            AdminStepUpService adminStepUpService,
            UserAccountStatusQuery userAccountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery,
            AuditService auditService,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.persistence = persistence;
        this.supportCasePersistence = supportCasePersistence;
        this.privilegedAccessPersistence = privilegedAccessPersistence;
        this.adminStepUpService = adminStepUpService;
        this.userAccountStatusQuery = userAccountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Override
    @Transactional(readOnly = true)
    public DataCorrectionPage searchCorrections(UUID adminUserId, UUID supportCaseId, String targetType, int page, int size) {
        verifyAdminAccess(adminUserId, "DATA_CORRECTION_EXECUTE");
        int boundedPage = Math.max(0, page);
        int boundedSize = Math.max(1, Math.min(size, 100));
        long offset = (long) boundedPage * boundedSize;

        List<DataCorrectionModel> content = persistence.searchCorrections(supportCaseId, targetType, boundedSize, offset);
        long total = persistence.countCorrections(supportCaseId, targetType);
        int totalPages = (int) Math.ceil((double) total / boundedSize);

        return new DataCorrectionPage(content, total, totalPages, boundedPage, boundedSize);
    }

    @Override
    @Transactional(readOnly = true)
    public DataCorrectionModel getCorrection(UUID adminUserId, UUID correctionId) {
        verifyAdminAccess(adminUserId, "DATA_CORRECTION_EXECUTE");
        return persistence.findById(correctionId)
                .orElseThrow(() -> new ApplicationValidationException("Data correction not found: " + correctionId));
    }

    @Override
    public DataCorrectionModel executeCorrection(UUID adminUserId, ExecuteDataCorrectionRequest request) {
        verifyAdminAccess(adminUserId, "DATA_CORRECTION_EXECUTE");

        if (request.reason() == null || request.reason().isBlank()) {
            throw new ApplicationValidationException("Reason is mandatory for data correction");
        }

        SupportCaseSummary supportCase = supportCasePersistence.findSupportCaseSummaryById(request.supportCaseId())
                .orElseThrow(() -> new SupportCaseNotFoundException("Support case not found: " + request.supportCaseId()));

        if ("CLOSED".equalsIgnoreCase(supportCase.status())) {
            throw new InvalidLifecycleTransitionException("Cannot apply data correction to a CLOSED support case");
        }

        Instant now = Instant.now(clock);

        PrivilegedAccessModel privAccess = privilegedAccessPersistence.findById(request.privilegedAccessRequestId())
                .orElseThrow(() -> new PrivilegedAccessNotFoundException("Privileged access request not found: " + request.privilegedAccessRequestId()));

        if (!privAccess.isActive(now) || !adminUserId.equals(privAccess.requestedByAdminId())) {
            throw new AccessDeniedException("Active privileged data access belonging to current administrator is required");
        }

        if (privAccess.relatedSupportCaseId() != null && !privAccess.relatedSupportCaseId().equals(request.supportCaseId())) {
            throw new AccessDeniedException("Privileged access request is associated with a different support case");
        }

        UUID stepUpId = adminStepUpService.resolveOrVerifyStepUp(adminUserId, "DATA_CORRECTION", request.stepUpPassword(), request.stepUpAuthenticationId());

        String targetType = request.targetType().trim().toUpperCase();
        Map<String, Object> beforeDataMap = new HashMap<>();
        Map<String, Object> appliedDataMap = new HashMap<>();

        if ("MEASUREMENT".equals(targetType)) {
            Map<String, Object> existing = persistence.findMeasurementById(request.targetId())
                    .orElseThrow(() -> new ApplicationValidationException("Measurement record not found: " + request.targetId()));

            UUID studentId = (UUID) existing.get("student_id");

            if (privAccess.targetUserId() != null && !privAccess.targetUserId().equals(studentId)) {
                throw new AccessDeniedException("Privileged access session target user does not match the measurement student");
            }

            if (supportCase.requesterId() != null && !supportCase.requesterId().equals(studentId)) {
                throw new ApplicationValidationException("Support case requester does not match the measurement student");
            }

            if (existing.get("superseded_by_id") != null) {
                throw new InvalidLifecycleTransitionException("Measurement record has already been superseded");
            }

            beforeDataMap.putAll(existing);

            Object valObj = request.proposedData().get("value");
            if (valObj == null) {
                throw new ApplicationValidationException("Proposed value is required for measurement correction");
            }
            double newValue;
            try {
                newValue = Double.parseDouble(valObj.toString());
            } catch (NumberFormatException e) {
                throw new ApplicationValidationException("Proposed measurement value must be numeric");
            }
            if (newValue <= 0) {
                throw new ApplicationValidationException("Proposed measurement value must be greater than zero");
            }

            Short existingUnitId = existing.get("unit_id") != null ? ((Number) existing.get("unit_id")).shortValue() : null;
            Short targetUnitId = existingUnitId;

            if (request.proposedData().get("unit") != null) {
                String unitStr = request.proposedData().get("unit").toString();
                targetUnitId = persistence.findUnitIdByCode(unitStr)
                        .orElse(existingUnitId);
            }

            int metricDefId = ((Number) existing.get("metric_definition_id")).intValue();
            Short sourceId = existing.get("source_id") != null ? ((Number) existing.get("source_id")).shortValue() : (short) 1;
            Instant measuredAt = existing.get("measured_at") != null
                    ? ((Timestamp) existing.get("measured_at")).toInstant()
                    : now;

            UUID newMeasurementId = UUID.randomUUID();
            persistence.insertCorrectedMeasurement(newMeasurementId, studentId, metricDefId, newValue, targetUnitId, sourceId, measuredAt, request.reason().trim(), now);

            int updated = persistence.markMeasurementSuperseded(request.targetId(), newMeasurementId);
            if (updated == 0) {
                throw new InvalidLifecycleTransitionException("Concurrent conflict: measurement already superseded");
            }

            appliedDataMap.put("newMeasurementId", newMeasurementId);
            appliedDataMap.put("value", newValue);
            appliedDataMap.put("unitId", targetUnitId);
            appliedDataMap.put("measuredAt", measuredAt);
            appliedDataMap.put("supersededOldId", request.targetId());

        } else if ("USER_PROFILE".equals(targetType)) {
            UUID targetUserId = request.targetId();

            if (privAccess.targetUserId() != null && !privAccess.targetUserId().equals(targetUserId)) {
                throw new AccessDeniedException("Privileged access session target user does not match the target user profile");
            }

            if (supportCase.requesterId() != null && !supportCase.requesterId().equals(targetUserId)) {
                throw new ApplicationValidationException("Support case requester does not match the target user profile");
            }

            Map<String, Object> existing = persistence.findUserById(request.targetId())
                    .orElseThrow(() -> new ApplicationValidationException("User record not found: " + request.targetId()));

            beforeDataMap.putAll(existing);

            Object displayNameObj = request.proposedData().get("displayName");
            if (displayNameObj == null || displayNameObj.toString().isBlank()) {
                throw new ApplicationValidationException("Proposed displayName is required for user profile correction");
            }
            String newDisplayName = displayNameObj.toString().trim();
            if (newDisplayName.length() > 100) {
                throw new ApplicationValidationException("Display name cannot exceed 100 characters");
            }

            String newPhone = request.proposedData().get("phoneNumber") != null
                    ? request.proposedData().get("phoneNumber").toString().trim()
                    : (existing.get("phone_number") != null ? existing.get("phone_number").toString() : null);

            persistence.updateUserProfile(request.targetId(), newDisplayName, newPhone, now);

            appliedDataMap.put("displayName", newDisplayName);
            appliedDataMap.put("phoneNumber", newPhone);

        } else {
            throw new ApplicationValidationException("Unsupported data correction target type: " + targetType);
        }

        String beforeDataJson = toJson(beforeDataMap);
        String proposedDataJson = toJson(request.proposedData());
        String appliedDataJson = toJson(appliedDataMap);
        String validationResultJson = "{\"status\":\"VALID\",\"validatedAt\":\"" + now + "\"}";

        UUID correctionId = UUID.randomUUID();
        persistence.insertCorrectionAction(
                correctionId, request.supportCaseId(), targetType, request.targetId(),
                beforeDataJson, proposedDataJson, appliedDataJson, validationResultJson,
                adminUserId, adminUserId, now, now
        );

        UUID adminActionId = UUID.randomUUID();
        persistence.insertAdminAction(
                adminActionId, adminUserId, "DATA_CORRECTION", targetType, request.targetId(),
                request.reason().trim(), "DATA_CORRECTION_EXECUTE", stepUpId,
                privAccess.id(), request.supportCaseId(), now
        );

        supportCasePersistence.insertEvent(
                UUID.randomUUID(), request.supportCaseId(), "DATA_CORRECTION_APPLIED", adminUserId,
                "Data correction executed on " + targetType + " ID: " + request.targetId() + ". Reason: " + request.reason().trim(),
                "{\"correctionId\":\"" + correctionId + "\",\"targetType\":\"" + targetType + "\"}", now
        );

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(adminUserId)
                .actorRole("ADMIN")
                .action("ADMIN_DATA_CORRECTION")
                .targetType(targetType)
                .targetId(request.targetId())
                .beforeDataJson(beforeDataJson)
                .afterDataJson(appliedDataJson)
                .metadataJson("{\"supportCaseId\":\"" + request.supportCaseId() + "\",\"correctionId\":\"" + correctionId + "\"}")
                .occurredAt(now)
                .build());

        return persistence.findById(correctionId)
                .orElseThrow(() -> new ApplicationValidationException("Data correction not found after insert: " + correctionId));
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
