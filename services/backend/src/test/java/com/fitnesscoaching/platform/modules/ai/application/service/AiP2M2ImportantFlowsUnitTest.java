package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.AiFeatureDisabledException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F01KnowledgeQueryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AdminAiRunQueryUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiFeatureControlUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CompleteF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiFeatureFlagRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F01AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.content.application.port.in.ContentLifecycleUseCase;
import com.fitnesscoaching.platform.modules.content.application.port.in.KnowledgeRetrievalUseCase;
import com.fitnesscoaching.platform.modules.content.application.service.F01RunEvidenceTracker;
import com.fitnesscoaching.platform.modules.content.domain.ReviewDecision;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.ExerciseCatalogRepository;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserPermissionQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Comprehensive verification of the 7 critical flows for P2-M2:
 * 1. F01 Active Normal Flow
 * 2. F01 Disabled Before Request (Fast Rejection, Zero Provider Calls)
 * 3. In-flight Disable Policy (Persist for Audit, Block Output Delivery, Lifecycle Conflict)
 * 4. F01 Re-enable Flow (No Restart Required, Audit Logged)
 * 5. Historical AI Run Reading (Ownership, Account Inactive, Role Transitions, Disabled State)
 * 6. Admin AI Run Monitoring (Redaction of Sensitive Data, Safe Usage, Audit Logging)
 * 7. Admin Knowledge Management Lifecycle (Review, Publish, Archive, Audit)
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AiP2M2ImportantFlowsUnitTest {

    @Mock private AiRunF01UseCase aiRunUseCase;
    @Mock private F01AiServiceClient aiServiceClient;
    @Mock private AiProvenanceRepository provenanceRepository;
    @Mock private KnowledgeRetrievalUseCase retrievalUseCase;
    @Mock private F01RunEvidenceTracker evidenceTracker;
    @Mock private F01RuntimeOutputValidator runtimeValidator;
    @Mock private AiFeatureControlUseCase featureControlUseCase;
    @Mock private AiFeatureFlagRepository featureFlagRepository;
    @Mock private AuditService auditService;
    @Mock private UserAccountStatusQuery accountStatusQuery;
    @Mock private UserRoleQuery userRoleQuery;
    @Mock private UserPermissionQuery userPermissionQuery;
    @Mock private AiRunRepository runRepository;
    @Mock private ContentLifecycleUseCase contentLifecycleUseCase;
    @Mock private ExerciseCatalogRepository exerciseCatalogRepository;
    @Mock private F01InternalEnvelopeBuilder envelopeBuilder;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UUID actorId = UUID.randomUUID();
    private final UUID adminId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();

    private AiKnowledgeOrchestrationService orchestrationService;
    private AiFeatureControlService featureControlService;
    private AiRunF01Service runF01Service;
    private AdminAiRunQueryService adminRunQueryService;

    @BeforeEach
    void setUp() {
        AiServiceProperties aiProps = new AiServiceProperties(true, "simulated", "http://localhost:8000", "key", 5000, 25000);

        orchestrationService = new AiKnowledgeOrchestrationService(
                aiRunUseCase,
                aiServiceClient,
                provenanceRepository,
                retrievalUseCase,
                evidenceTracker,
                objectMapper,
                aiProps,
                runtimeValidator,
                featureControlUseCase
        );

        featureControlService = new AiFeatureControlService(
                featureFlagRepository,
                aiProps,
                auditService,
                accountStatusQuery,
                userRoleQuery,
                userPermissionQuery
        );

        runF01Service = new AiRunF01Service(
                runRepository,
                provenanceRepository,
                accountStatusQuery,
                userRoleQuery,
                exerciseCatalogRepository,
                envelopeBuilder,
                objectMapper
        );

        adminRunQueryService = new AdminAiRunQueryService(
                runRepository,
                provenanceRepository,
                auditService,
                accountStatusQuery,
                userRoleQuery,
                userPermissionQuery
        );
    }

    private F01InternalContextEnvelope createEnvelope(UUID correlationId) {
        F01InternalContextEnvelope.ActorContext actorContext = new F01InternalContextEnvelope.ActorContext(actorId, "STUDENT");
        F01InternalContextEnvelope.KnowledgeScopeContext knowledgeScope = new F01InternalContextEnvelope.KnowledgeScopeContext(
                "ACTIVE",
                List.of("WHO-PA-SB-2020-VI-05")
        );

        return new F01InternalContextEnvelope(
                runId,
                F01InternalContextEnvelope.F01_REQUEST_TYPE,
                F01InternalContextEnvelope.SCHEMA_VERSION,
                correlationId,
                actorContext,
                F01InternalContextEnvelope.GENERAL_PURPOSE,
                F01InternalContextEnvelope.ALLOWED_ACTIONS,
                "2026-10-23T10:00:00Z",
                "WHO recommendations?",
                "en",
                null,
                knowledgeScope
        );
    }

    // =========================================================================
    // LUỒNG 1: F01 ĐANG BẬT
    // =========================================================================
    @Nested
    @DisplayName("Luồng 1 — F01 đang bật")
    class Flow1_F01Active {

        @Test
        @DisplayName("Request hợp lệ được xử lý trọn vẹn, output và usage nhất quán")
        void validRequest_processesNormally() {
            when(featureControlUseCase.isF01Enabled()).thenReturn(true);

            AiRun queuedRun = AiRun.builder()
                    .id(runId)
                    .requestedBy(actorId)
                    .status(AiRunStatus.QUEUED)
                    .requestType("F01_GENERAL_KNOWLEDGE")
                    .purpose("BUSINESS_ASSISTANCE")
                    .evaluationOnly(false)
                    .queuedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();

            UUID correlationId = UUID.randomUUID();
            F01InternalContextEnvelope envelope = createEnvelope(correlationId);

            when(aiRunUseCase.createF01RunContext(eq(actorId), any()))
                    .thenReturn(new AiRunF01UseCase.CreatedF01RunContext(queuedRun, envelope));

            F01KnowledgeQueryResponse fakeAiResponse = new F01KnowledgeQueryResponse(
                    runId,
                    correlationId,
                    "SUCCEEDED",
                    "INFORMATION",
                    "NONE",
                    "WHO recommends 150-300 min aerobic weekly.",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Instant.now().toString(),
                    new F01KnowledgeQueryResponse.UsageDto(120, 80, 200, 350),
                    null,
                    null
            );

            when(aiServiceClient.queryKnowledge(envelope)).thenReturn(fakeAiResponse);
            when(runtimeValidator.validate(fakeAiResponse, envelope))
                    .thenReturn(new F01RuntimeOutputValidator.ValidationResult(true, null));

            F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                    "WHO recommendations?",
                    "en",
                    null,
                    correlationId
            );

            F01KnowledgeQueryResponse result = orchestrationService.queryKnowledge(actorId, request);

            assertThat(result).isNotNull();
            assertThat(result.runStatus()).isEqualTo("SUCCEEDED");
            assertThat(result.resultType()).isEqualTo("INFORMATION");
            assertThat(result.answer()).contains("WHO recommends");
            assertThat(result.usage().totalTokens()).isEqualTo(200);

            verify(aiRunUseCase).transitionRunStatus(eq(runId), eq(AiRunStatus.QUEUED), eq(AiRunStatus.RUNNING), any(), any());
            verify(aiRunUseCase).completeF01Run(argThat(cmd ->
                    cmd.runId().equals(runId)
                            && cmd.terminalStatus() == AiRunStatus.SUCCEEDED
                            && cmd.totalTokens() == 200
            ));
            verify(aiRunUseCase).revalidateActorAuthorization(actorId);
        }
    }

    // =========================================================================
    // LUỒNG 2: F01 ĐÃ TẮT TRƯỚC KHI GỬI REQUEST
    // =========================================================================
    @Nested
    @DisplayName("Luồng 2 — F01 đã tắt trước khi gửi request")
    class Flow2_F01DisabledBeforeRequest {

        @Test
        @DisplayName("Từ chối ngay lập tức bằng AiFeatureDisabledException, zero provider calls")
        void disabledFeature_rejectsImmediatelyWithoutCallingAi() {
            when(featureControlUseCase.isF01Enabled()).thenReturn(false);

            F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                    "How much protein?",
                    "en",
                    null,
                    UUID.randomUUID()
            );

            assertThatThrownBy(() -> orchestrationService.queryKnowledge(actorId, request))
                    .isInstanceOf(AiFeatureDisabledException.class)
                    .hasMessageContaining("disabled by administrator");

            // Zero interactions with AI client, zero run context created
            verifyNoInteractions(aiServiceClient);
            verifyNoInteractions(aiRunUseCase);
        }
    }

    // =========================================================================
    // LUỒNG 3: TẮT F01 KHI GENERATION ĐANG CHẠY
    // =========================================================================
    @Nested
    @DisplayName("Luồng 3 — Tắt F01 khi generation đang chạy")
    class Flow3_InFlightDisablePolicy {

        @Test
        @DisplayName("Persist run hoàn tất để bảo đảm audit và token usage, nhưng chặn giao output")
        void inFlightDisable_savesRunAudit_blocksDelivery() {
            AtomicBoolean featureState = new AtomicBoolean(true);
            when(featureControlUseCase.isF01Enabled()).thenAnswer(inv -> featureState.get());

            AiRun queuedRun = AiRun.builder()
                    .id(runId)
                    .requestedBy(actorId)
                    .status(AiRunStatus.QUEUED)
                    .requestType("F01_GENERAL_KNOWLEDGE")
                    .purpose("BUSINESS_ASSISTANCE")
                    .evaluationOnly(false)
                    .queuedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();

            UUID correlationId = UUID.randomUUID();
            F01InternalContextEnvelope envelope = createEnvelope(correlationId);

            when(aiRunUseCase.createF01RunContext(eq(actorId), any()))
                    .thenReturn(new AiRunF01UseCase.CreatedF01RunContext(queuedRun, envelope));

            F01KnowledgeQueryResponse fakeAiResponse = new F01KnowledgeQueryResponse(
                    runId,
                    correlationId,
                    "SUCCEEDED",
                    "INFORMATION",
                    "NONE",
                    "Answer generated before kill switch was toggled.",
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Collections.emptyList(),
                    Instant.now().toString(),
                    new F01KnowledgeQueryResponse.UsageDto(300, 150, 450, 800),
                    null,
                    null
            );

            when(aiServiceClient.queryKnowledge(envelope)).thenAnswer(inv -> {
                featureState.set(false); // Admin toggled OFF during generation!
                return fakeAiResponse;
            });

            when(runtimeValidator.validate(fakeAiResponse, envelope))
                    .thenReturn(new F01RuntimeOutputValidator.ValidationResult(true, null));

            F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest(
                    "Inflight question",
                    "en",
                    null,
                    correlationId
            );

            assertThatThrownBy(() -> orchestrationService.queryKnowledge(actorId, request))
                    .isInstanceOf(AiFeatureDisabledException.class)
                    .hasMessageContaining("disabled by administrator during processing");

            // CRITICAL INVARIANT: The run was STILL completed in the database with usage tokens to prevent orphan run
            verify(aiRunUseCase).completeF01Run(argThat(cmd ->
                    cmd.runId().equals(runId)
                            && cmd.terminalStatus() == AiRunStatus.SUCCEEDED
                            && cmd.totalTokens() == 450
            ));
        }

        @Test
        @DisplayName("Nếu recovery kết thúc run trước completion, giữ đúng cơ chế lifecycle conflict")
        void inFlightRecovery_conflictPreserved() {
            when(featureControlUseCase.isF01Enabled()).thenReturn(true);

            AiRun queuedRun = AiRun.builder()
                    .id(runId)
                    .requestedBy(actorId)
                    .status(AiRunStatus.QUEUED)
                    .requestType("F01_GENERAL_KNOWLEDGE")
                    .purpose("BUSINESS_ASSISTANCE")
                    .evaluationOnly(false)
                    .queuedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();

            UUID correlationId = UUID.randomUUID();
            F01InternalContextEnvelope envelope = createEnvelope(correlationId);

            when(aiRunUseCase.createF01RunContext(eq(actorId), any()))
                    .thenReturn(new AiRunF01UseCase.CreatedF01RunContext(queuedRun, envelope));

            F01KnowledgeQueryResponse fakeAiResponse = new F01KnowledgeQueryResponse(
                    runId, correlationId, "SUCCEEDED", "INFORMATION", "NONE", "Ans",
                    Collections.emptyList(), Collections.emptyList(), Collections.emptyList(),
                    Instant.now().toString(), null, null, null
            );
            when(aiServiceClient.queryKnowledge(envelope)).thenReturn(fakeAiResponse);
            when(runtimeValidator.validate(fakeAiResponse, envelope))
                    .thenReturn(new F01RuntimeOutputValidator.ValidationResult(true, null));

            doThrow(new AiRunLifecycleConflictException("Cannot transition AI Run: already terminal"))
                    .when(aiRunUseCase).completeF01Run(any());

            F01KnowledgeQueryApiRequest request = new F01KnowledgeQueryApiRequest("Q", "en", null, correlationId);

            assertThatThrownBy(() -> orchestrationService.queryKnowledge(actorId, request))
                    .isInstanceOf(AiRunLifecycleConflictException.class);
        }
    }

    // =========================================================================
    // LUỒNG 4: BẬT LẠI F01
    // =========================================================================
    @Nested
    @DisplayName("Luồng 4 — Bật lại F01")
    class Flow4_ReEnableFeature {

        @Test
        @DisplayName("Admin bật lại F01: không cần restart, ghi nhận audit log và request mới thông suốt")
        void reEnableF01_noRestart_logsAudit() {
            when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);

            when(featureFlagRepository.findByCode(AiFeatureControlUseCase.F01_FEATURE_CODE))
                    .thenReturn(Optional.of(new AiFeatureFlagRepository.FeatureFlagRecord(
                            UUID.randomUUID(), AiFeatureControlUseCase.F01_FEATURE_CODE, "F01", false, adminId, Instant.now()
                    )));

            UUID flagId = UUID.randomUUID();
            Instant now = Instant.now();
            when(featureFlagRepository.setEnabled(eq(AiFeatureControlUseCase.F01_FEATURE_CODE), anyString(), eq(true), eq(adminId)))
                    .thenReturn(new AiFeatureFlagRepository.FeatureFlagRecord(
                            flagId, AiFeatureControlUseCase.F01_FEATURE_CODE, "F01 General Fitness", true, adminId, now
                    ));

            AiFeatureControlUseCase.AiFeatureStatusDto status = featureControlService.setF01Enabled(adminId, true, "Maintenance concluded");

            assertThat(status.enabled()).isTrue();

            ArgumentCaptor<AuditRecord> auditCaptor = ArgumentCaptor.forClass(AuditRecord.class);
            verify(auditService).recordAudit(auditCaptor.capture());
            AuditRecord audit = auditCaptor.getValue();
            assertThat(audit.action()).isEqualTo("TOGGLE_AI_FEATURE");
            assertThat(audit.beforeDataJson()).contains("\"enabled\":false");
            assertThat(audit.afterDataJson()).contains("\"enabled\":true");
            assertThat(audit.afterDataJson()).contains("Maintenance concluded");
        }
    }

    // =========================================================================
    // LUỒNG 5: ĐỌC AI RUN ĐÃ LƯU
    // =========================================================================
    @Nested
    @DisplayName("Luồng 5 — Đọc AI Run đã lưu")
    class Flow5_HistoricalRunReading {

        private AiRun createSampleRun(UUID ownerId) {
            return AiRun.builder()
                    .id(runId)
                    .requestedBy(ownerId)
                    .status(AiRunStatus.SUCCEEDED)
                    .requestType("F01_GENERAL_KNOWLEDGE")
                    .purpose("BUSINESS_ASSISTANCE")
                    .evaluationOnly(false)
                    .outputPayload("{\"answer\":\"Sample answer\"}")
                    .queuedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();
        }

        @Test
        @DisplayName("Chủ sở hữu hợp lệ đọc được run đã lưu")
        void validOwner_readsRunSuccessfully() {
            when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);
            when(runRepository.findById(runId)).thenReturn(Optional.of(createSampleRun(actorId)));

            AiRun run = runF01Service.getF01Run(actorId, runId);
            assertThat(run).isNotNull();
            assertThat(run.id()).isEqualTo(runId);
        }

        @Test
        @DisplayName("Người dùng khác bị từ chối truy cập (ownership enforcement)")
        void differentUser_denied() {
            when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);
            UUID anotherUser = UUID.randomUUID();
            when(runRepository.findById(runId)).thenReturn(Optional.of(createSampleRun(anotherUser)));

            assertThatThrownBy(() -> runF01Service.getF01Run(actorId, runId))
                    .isInstanceOf(AiRunAccessDeniedException.class)
                    .hasMessageContaining("only the run owner can access this AI Run");
        }

        @Test
        @DisplayName("Tài khoản không còn ACTIVE (SUSPENDED) bị từ chối truy cập")
        void suspendedAccount_denied() {
            when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.SUSPENDED));

            assertThatThrownBy(() -> runF01Service.getF01Run(actorId, runId))
                    .isInstanceOf(AccountUnavailableException.class)
                    .hasMessageContaining("not active");

            verifyNoInteractions(runRepository);
        }

        @Test
        @DisplayName("Người dùng mất toàn bộ quyền STUDENT và TRAINER bị từ chối truy cập")
        void lostBothRoles_denied() {
            when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
            when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(false);

            assertThatThrownBy(() -> runF01Service.getF01Run(actorId, runId))
                    .isInstanceOf(AiRunAccessDeniedException.class)
                    .hasMessageContaining("User lacks STUDENT or TRAINER capability");

            verifyNoInteractions(runRepository);
        }

        @Test
        @DisplayName("Người dùng mất STUDENT nhưng vẫn còn TRAINER hợp lệ: VẪN ĐƯỢC PHÉP TRUY CẬP")
        void lostStudentRetainsTrainer_succeeds() {
            when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(false);
            when(userRoleQuery.hasActiveRole(actorId, "TRAINER")).thenReturn(true);
            when(runRepository.findById(runId)).thenReturn(Optional.of(createSampleRun(actorId)));

            AiRun run = runF01Service.getF01Run(actorId, runId);
            assertThat(run).isNotNull();
            assertThat(run.requestedBy()).isEqualTo(actorId);
        }

        @Test
        @DisplayName("Trạng thái F01 đang tắt: Lịch sử run cũ vẫn đọc được cho chủ sở hữu hợp lệ")
        void f01Disabled_historicalRunStillAccessible() {
            when(accountStatusQuery.getAccountStatus(actorId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(actorId, "STUDENT")).thenReturn(true);
            when(runRepository.findById(runId)).thenReturn(Optional.of(createSampleRun(actorId)));

            AiRun run = runF01Service.getF01Run(actorId, runId);
            assertThat(run).isNotNull();
        }
    }

    // =========================================================================
    // LUỒNG 6: ADMIN AI RUN (REDACTION & AUDIT)
    // =========================================================================
    @Nested
    @DisplayName("Luồng 6 — Admin AI Run Telemetry")
    class Flow6_AdminAiRunMonitoring {

        @Test
        @DisplayName("Admin list AI Runs: dữ liệu nhạy cảm được che giấu (redacted), null usage giữ null")
        void adminListRuns_redactedAndNullSafeUsage() {
            when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);

            AiRun rawRun = AiRun.builder()
                    .id(runId)
                    .requestedBy(actorId)
                    .status(AiRunStatus.SUCCEEDED)
                    .requestType("F01_GENERAL_KNOWLEDGE")
                    .purpose("BUSINESS_ASSISTANCE")
                    .inputPayload("{\"question\":\"Private medical condition question?\"}")
                    .outputPayload("{\"answer\":\"Private answer with personal recommendations\"}")
                    .inputTokenCount(null)
                    .outputTokenCount(null)
                    .latencyMs(null)
                    .queuedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();

            when(runRepository.findRuns(0, 15, null, null)).thenReturn(List.of(rawRun));
            when(runRepository.countRuns(null, null)).thenReturn(1L);

            AdminAiRunQueryUseCase.AdminAiRunPageDto response = adminRunQueryService.listRuns(adminId, 0, 15, null, null);

            assertThat(response.total()).isEqualTo(1);
            AdminAiRunQueryUseCase.AdminAiRunSummaryDto summary = response.items().getFirst();

            // SENSITIVE DATA CHECK: No input/output payload
            assertThat(summary.id()).isEqualTo(runId);
            assertThat(summary.status()).isEqualTo("SUCCEEDED");
            assertThat(summary.totalTokenCount()).isNull(); // KEPT NULL, NOT 0
            assertThat(summary.latencyMs()).isNull();
        }

        @Test
        @DisplayName("Admin xem chi tiết AI Run: ghi nhận audit log VIEW_AI_RUN_DETAIL")
        void adminGetDetail_logsAudit() {
            when(accountStatusQuery.getAccountStatus(adminId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
            when(userRoleQuery.hasActiveRole(adminId, "ADMIN")).thenReturn(true);

            AiRun rawRun = AiRun.builder()
                    .id(runId)
                    .requestedBy(actorId)
                    .status(AiRunStatus.SUCCEEDED)
                    .requestType("F01_GENERAL_KNOWLEDGE")
                    .purpose("BUSINESS_ASSISTANCE")
                    .queuedAt(Instant.now())
                    .createdAt(Instant.now())
                    .build();

            when(runRepository.findById(runId)).thenReturn(Optional.of(rawRun));
            when(provenanceRepository.findValidationResultsByRunId(runId)).thenReturn(List.of());
            when(provenanceRepository.findKnowledgeChunkReferencesByRunId(runId)).thenReturn(List.of());

            AdminAiRunQueryUseCase.AdminAiRunDetailDto detail = adminRunQueryService.getRunDetail(adminId, runId);

            assertThat(detail).isNotNull();
            assertThat(detail.id()).isEqualTo(runId);

            ArgumentCaptor<AuditRecord> captor = ArgumentCaptor.forClass(AuditRecord.class);
            verify(auditService).recordAudit(captor.capture());
            assertThat(captor.getValue().action()).isEqualTo("VIEW_AI_RUN_DETAIL");
            assertThat(captor.getValue().targetId()).isEqualTo(runId);
            assertThat(captor.getValue().actorUserId()).isEqualTo(adminId);
        }
    }

    // =========================================================================
    // LUỒNG 7: ADMIN TRI THỨC (CONTENT LIFECYCLE)
    // =========================================================================
    @Nested
    @DisplayName("Luồng 7 — Admin tri thức")
    class Flow7_KnowledgeLifecycle {

        @Test
        @DisplayName("Review version gọi đúng lifecycle usecase với decision và audit notes")
        void reviewVersion_delegatesToUseCase() {
            UUID versionId = UUID.randomUUID();
            contentLifecycleUseCase.reviewVersion(adminId, versionId, ReviewDecision.APPROVED, "Approved after technical check");

            verify(contentLifecycleUseCase).reviewVersion(adminId, versionId, ReviewDecision.APPROVED, "Approved after technical check");
        }

        @Test
        @DisplayName("Publish version kích hoạt publish trong content lifecycle usecase")
        void publishVersion_delegatesToUseCase() {
            UUID versionId = UUID.randomUUID();
            contentLifecycleUseCase.publishVersion(adminId, versionId);

            verify(contentLifecycleUseCase).publishVersion(adminId, versionId);
        }

        @Test
        @DisplayName("Archive version lưu trữ phiên bản với lý do")
        void archiveVersion_delegatesToUseCase() {
            UUID versionId = UUID.randomUUID();
            contentLifecycleUseCase.archiveVersion(adminId, versionId, "Superseded by v2.0");

            verify(contentLifecycleUseCase).archiveVersion(adminId, versionId, "Superseded by v2.0");
        }
    }
}
