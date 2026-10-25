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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiFeatureControlServiceUnitTest {

    @Mock
    private AiFeatureFlagRepository featureFlagRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private UserAccountStatusQuery accountStatusQuery;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private UserPermissionQuery userPermissionQuery;

    private AiServiceProperties aiProperties;
    private AiFeatureControlService service;

    private final UUID adminId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        aiProperties = new AiServiceProperties(true, "simulated", "http://localhost:8000", "key", 5000, 25000);
        service = new AiFeatureControlService(
                featureFlagRepository,
                aiProperties,
                auditService,
                accountStatusQuery,
                userRoleQuery,
                userPermissionQuery
        );
    }

    @Test
    @DisplayName("isF01Enabled returns database value when record is present")
    void isF01Enabled_whenInDatabase_returnsDatabaseValue() {
        when(featureFlagRepository.findByCode(AiFeatureControlUseCase.F01_FEATURE_CODE))
                .thenReturn(Optional.of(new AiFeatureFlagRepository.FeatureFlagRecord(
                        UUID.randomUUID(),
                        AiFeatureControlUseCase.F01_FEATURE_CODE,
                        "desc",
                        false,
                        adminId,
                        Instant.now()
                )));

        assertThat(service.isF01Enabled()).isFalse();
    }

    @Test
    @DisplayName("isF01Enabled falls back to properties when record not in database")
    void isF01Enabled_whenNotInDatabase_returnsFallbackProperty() {
        when(featureFlagRepository.findByCode(AiFeatureControlUseCase.F01_FEATURE_CODE))
                .thenReturn(Optional.empty());

        assertThat(service.isF01Enabled()).isTrue();
    }

    @Test
    @DisplayName("setF01Enabled records audit and updates feature flag when actor is active ADMIN")
    void setF01Enabled_asAdmin_updatesAndAudits() {
        when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);

        when(featureFlagRepository.findByCode(AiFeatureControlUseCase.F01_FEATURE_CODE))
                .thenReturn(Optional.of(new AiFeatureFlagRepository.FeatureFlagRecord(
                        UUID.randomUUID(),
                        AiFeatureControlUseCase.F01_FEATURE_CODE,
                        "desc",
                        true,
                        adminId,
                        Instant.now()
                )));

        UUID flagId = UUID.randomUUID();
        Instant now = Instant.now();
        when(featureFlagRepository.setEnabled(
                eq(AiFeatureControlUseCase.F01_FEATURE_CODE),
                any(),
                eq(false),
                eq(adminId)
        )).thenReturn(new AiFeatureFlagRepository.FeatureFlagRecord(
                flagId,
                AiFeatureControlUseCase.F01_FEATURE_CODE,
                "F01 General Fitness Knowledge AI Assistance",
                false,
                adminId,
                now
        ));

        AiFeatureControlUseCase.AiFeatureStatusDto result = service.setF01Enabled(adminId, false, "Emergency killswitch for maintenance");

        assertThat(result.enabled()).isFalse();
        assertThat(result.featureCode()).isEqualTo(AiFeatureControlUseCase.F01_FEATURE_CODE);

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(auditCaptor.capture());

        AuditRecord audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo("TOGGLE_AI_FEATURE");
        assertThat(audit.targetType()).isEqualTo("FEATURE_FLAG");
        assertThat(audit.targetId()).isEqualTo(flagId);
        assertThat(audit.actorUserId()).isEqualTo(adminId);
        assertThat(audit.beforeDataJson()).contains("\"enabled\":true");
        assertThat(audit.afterDataJson()).contains("\"enabled\":false");
        assertThat(audit.afterDataJson()).contains("Emergency killswitch for maintenance");
    }

    @Test
    @DisplayName("setF01Enabled throws AccessDeniedException when actor lacks admin privileges")
    void setF01Enabled_unauthorizedActor_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(false);
        when(userPermissionQuery.hasActivePermission(adminId, "SYSTEM_CONFIG_MANAGE")).thenReturn(false);

        assertThatThrownBy(() -> service.setF01Enabled(adminId, false, "test"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("lacks ADMIN or SYSTEM_CONFIG_MANAGE authority");
    }

    @Test
    @DisplayName("setF01Enabled throws AccountUnavailableException when actor account is inactive")
    void setF01Enabled_suspendedActor_throwsAccountUnavailable() {
        when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

        assertThatThrownBy(() -> service.setF01Enabled(adminId, false, "test"))
                .isInstanceOf(AccountUnavailableException.class)
                .hasMessageContaining("not active");
    }

    @Test
    @DisplayName("isF01Enabled fails closed (returns false) when repository throws exception")
    void isF01Enabled_repositoryError_failsClosed() {
        when(featureFlagRepository.findByCode(AiFeatureControlUseCase.F01_FEATURE_CODE))
                .thenThrow(new RuntimeException("Database connection timeout"));

        boolean enabled = service.isF01Enabled();

        assertThat(enabled).isFalse();
    }
}
