package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiFeatureControlUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiFeatureFlagRepository;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
public class AiFeatureControlService implements AiFeatureControlUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiFeatureControlService.class);

    private final AiFeatureFlagRepository featureFlagRepository;
    private final AiServiceProperties aiProperties;
    private final AuditService auditService;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final UserPermissionQuery userPermissionQuery;

    public AiFeatureControlService(
            AiFeatureFlagRepository featureFlagRepository,
            AiServiceProperties aiProperties,
            AuditService auditService,
            UserAccountStatusQuery accountStatusQuery,
            UserRoleQuery userRoleQuery,
            UserPermissionQuery userPermissionQuery
    ) {
        this.featureFlagRepository = featureFlagRepository;
        this.aiProperties = aiProperties;
        this.auditService = auditService;
        this.accountStatusQuery = accountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.userPermissionQuery = userPermissionQuery;
    }

    @Override
    public boolean isF01Enabled() {
        try {
            Optional<AiFeatureFlagRepository.FeatureFlagRecord> opt = featureFlagRepository.findByCode(F01_FEATURE_CODE);
            if (opt.isPresent()) {
                return opt.get().enabled();
            }
            return Boolean.TRUE.equals(aiProperties.enabled());
        } catch (Exception ex) {
            log.error("Failed to query feature flag {} from repository. Defaulting to disabled (fail-closed) to prevent unintended activation.", F01_FEATURE_CODE, ex);
            return false;
        }
    }

    @Override
    public AiFeatureStatusDto getF01Status() {
        Optional<AiFeatureFlagRepository.FeatureFlagRecord> opt = featureFlagRepository.findByCode(F01_FEATURE_CODE);
        if (opt.isPresent()) {
            AiFeatureFlagRepository.FeatureFlagRecord rec = opt.get();
            return new AiFeatureStatusDto(
                    rec.code(),
                    rec.enabled(),
                    rec.description(),
                    rec.updatedBy(),
                    rec.updatedAt()
            );
        }
        return new AiFeatureStatusDto(
                F01_FEATURE_CODE,
                Boolean.TRUE.equals(aiProperties.enabled()),
                "F01 General Fitness Knowledge AI Assistance (default fallback)",
                null,
                null
        );
    }

    @Override
    @Transactional
    public AiFeatureStatusDto setF01Enabled(UUID actorId, boolean enabled, String reason) {
        validateAdminAuthority(actorId);

        boolean beforeEnabled = isF01Enabled();
        String description = "F01 General Fitness Knowledge AI Assistance";

        AiFeatureFlagRepository.FeatureFlagRecord updated = featureFlagRepository.setEnabled(
                F01_FEATURE_CODE,
                description,
                enabled,
                actorId
        );

        String sanitizedReason = reason != null ? reason.trim() : "";
        String beforeJson = "{\"featureCode\":\"" + F01_FEATURE_CODE + "\",\"enabled\":" + beforeEnabled + "}";
        String afterJson = "{\"featureCode\":\"" + F01_FEATURE_CODE + "\",\"enabled\":" + enabled + ",\"reason\":\"" + sanitizedReason.replace("\"", "\\\"") + "\"}";

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole("ADMIN")
                .action("TOGGLE_AI_FEATURE")
                .targetType("FEATURE_FLAG")
                .targetId(updated.id())
                .beforeDataJson(beforeJson)
                .afterDataJson(afterJson)
                .occurredAt(Instant.now())
                .build());

        return new AiFeatureStatusDto(
                updated.code(),
                updated.enabled(),
                updated.description(),
                updated.updatedBy(),
                updated.updatedAt()
        );
    }

    private void validateAdminAuthority(UUID actorId) {
        if (actorId == null) {
            throw new AccessDeniedException("Authenticated actor ID is required");
        }
        AccountStatus status = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
        if (status != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }
        boolean isAdmin = userRoleQuery.hasActiveRole(actorId, "ADMIN");
        boolean hasConfigPerm = userPermissionQuery.hasActivePermission(actorId, "SYSTEM_CONFIG_MANAGE");
        if (!isAdmin && !hasConfigPerm) {
            throw new AccessDeniedException("User lacks ADMIN or SYSTEM_CONFIG_MANAGE authority to control AI features");
        }
    }
}
