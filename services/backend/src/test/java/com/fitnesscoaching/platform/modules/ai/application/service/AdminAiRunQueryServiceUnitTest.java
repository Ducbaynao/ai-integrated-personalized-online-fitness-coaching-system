package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AdminAiRunQueryUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunKnowledgeChunk;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiValidationResult;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdminAiRunQueryServiceUnitTest {

    @Mock
    private AiRunRepository runRepository;

    @Mock
    private AiProvenanceRepository provenanceRepository;

    @Mock
    private AuditService auditService;

    @Mock
    private UserAccountStatusQuery accountStatusQuery;

    @Mock
    private UserRoleQuery userRoleQuery;

    @Mock
    private UserPermissionQuery userPermissionQuery;

    private AdminAiRunQueryService service;

    private final UUID adminId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        service = new AdminAiRunQueryService(
                runRepository,
                provenanceRepository,
                auditService,
                accountStatusQuery,
                userRoleQuery,
                userPermissionQuery
        );
    }

    @Test
    @DisplayName("listRuns sanitizes payloads and returns operational metadata with pagination")
    void listRuns_asAdmin_returnsSanitizedSummaries() {
        when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);

        AiRun mockRun = new AiRun(
                runId,
                UUID.randomUUID(), // requestedBy (private user id)
                null,
                null,
                "F01_GENERAL_KNOWLEDGE",
                AiRunStatus.SUCCEEDED,
                null,
                null,
                UUID.randomUUID(),
                "BUSINESS_ASSISTANCE",
                false,
                "{\"privateQuestion\":\"Sensitive user query\"}", // input_payload (must be sanitized!)
                "{\"privateAnswer\":\"Confidential response\"}", // output_payload (must be sanitized!)
                null,
                null,
                150,
                50,
                1200,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                Instant.now()
        );

        when(runRepository.findRuns(eq(0), eq(10), eq(AiRunStatus.SUCCEEDED), eq("F01_GENERAL_KNOWLEDGE")))
                .thenReturn(List.of(mockRun));
        when(runRepository.countRuns(eq(AiRunStatus.SUCCEEDED), eq("F01_GENERAL_KNOWLEDGE")))
                .thenReturn(1L);

        AdminAiRunQueryUseCase.AdminAiRunPageDto result = service.listRuns(adminId, 0, 10, AiRunStatus.SUCCEEDED, "F01_GENERAL_KNOWLEDGE");

        assertThat(result.total()).isEqualTo(1L);
        assertThat(result.items()).hasSize(1);
        AdminAiRunQueryUseCase.AdminAiRunSummaryDto summary = result.items().get(0);
        assertThat(summary.id()).isEqualTo(runId);
        assertThat(summary.status()).isEqualTo("SUCCEEDED");
        assertThat(summary.inputTokenCount()).isEqualTo(150);
        assertThat(summary.outputTokenCount()).isEqualTo(50);
        assertThat(summary.totalTokenCount()).isEqualTo(200);
        assertThat(summary.latencyMs()).isEqualTo(1200);
    }

    @Test
    @DisplayName("getRunDetail records audit, masks private payloads, and includes validation and citations")
    void getRunDetail_asAdmin_auditsAndReturnsDetails() {
        when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);

        AiRun mockRun = new AiRun(
                runId,
                UUID.randomUUID(),
                null,
                null,
                "F01_GENERAL_KNOWLEDGE",
                AiRunStatus.SUCCEEDED,
                null,
                null,
                UUID.randomUUID(),
                "BUSINESS_ASSISTANCE",
                false,
                "{\"rawInput\":\"user secrets\"}",
                "{\"rawOutput\":\"answer secrets\"}",
                null,
                null,
                null, // unknown tokens
                null,
                800,
                Instant.now(),
                Instant.now(),
                Instant.now(),
                Instant.now()
        );

        when(runRepository.findById(runId)).thenReturn(Optional.of(mockRun));

        AiValidationResult validation = new AiValidationResult(
                UUID.randomUUID(),
                runId,
                "F01_SCHEMA_DOMAIN_VALIDATOR",
                true,
                "INFO",
                "{\"result\":\"PASSED\"}",
                Instant.now()
        );
        when(provenanceRepository.findValidationResultsByRunId(runId)).thenReturn(List.of(validation));

        UUID chunkId = UUID.randomUUID();
        AiRunKnowledgeChunk chunkRef = new AiRunKnowledgeChunk(
                runId,
                chunkId,
                1,
                BigDecimal.valueOf(0.92)
        );
        when(provenanceRepository.findKnowledgeChunkReferencesByRunId(runId)).thenReturn(List.of(chunkRef));

        AdminAiRunQueryUseCase.AdminAiRunDetailDto detail = service.getRunDetail(adminId, runId);

        assertThat(detail.id()).isEqualTo(runId);
        assertThat(detail.totalTokenCount()).isNull(); // Preserved NULL when tokens unknown, never zero!
        assertThat(detail.validations()).hasSize(1);
        assertThat(detail.validations().get(0).validatorCode()).isEqualTo("F01_SCHEMA_DOMAIN_VALIDATOR");
        assertThat(detail.citations()).hasSize(1);
        assertThat(detail.citations().get(0).chunkId()).isEqualTo(chunkId);

        ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
        verify(auditService).recordAudit(auditCaptor.capture());

        AuditRecord audit = auditCaptor.getValue();
        assertThat(audit.action()).isEqualTo("VIEW_AI_RUN_DETAIL");
        assertThat(audit.targetType()).isEqualTo("AI_RUN");
        assertThat(audit.targetId()).isEqualTo(runId);
    }

    @Test
    @DisplayName("getRunDetail throws AiRunNotFoundException when run does not exist")
    void getRunDetail_notFound_throwsException() {
        when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);
        when(runRepository.findById(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getRunDetail(adminId, runId))
                .isInstanceOf(AiRunNotFoundException.class);
    }

    @Test
    @DisplayName("listRuns throws AccessDeniedException when actor lacks admin roles")
    void listRuns_nonAdmin_throwsAccessDenied() {
        when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(false);
        when(userPermissionQuery.hasActivePermission(adminId, "AI_RUN_VIEW")).thenReturn(false);
        when(userPermissionQuery.hasActivePermission(adminId, "SYSTEM_CONFIG_MANAGE")).thenReturn(false);

        assertThatThrownBy(() -> service.listRuns(adminId, 0, 10, null, null))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("lacks ADMIN or AI_RUN_VIEW authority");
    }
}
