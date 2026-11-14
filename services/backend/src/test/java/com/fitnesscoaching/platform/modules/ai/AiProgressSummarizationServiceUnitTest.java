package com.fitnesscoaching.platform.modules.ai;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiFeatureDisabledException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse.ContinuityEvaluationDto;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiFeatureControlUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F04AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.service.AiProgressSummarizationService;
import com.fitnesscoaching.platform.modules.ai.application.service.F04ContextBuilder;
import com.fitnesscoaching.platform.modules.ai.application.service.F04RuntimeOutputValidator;
import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.ContinuitySignalDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.DateWindowDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.F04ProgressContextDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.HistoricalLimitsSummaryDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.MeasurementSectionDto;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope.WorkoutSectionDto;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ProgressSnapshot;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiProgressSummarizationServiceUnitTest {

    private F04ContextBuilder contextBuilder;
    private F04AiServiceClient aiServiceClient;
    private F04RuntimeOutputValidator runtimeValidator;
    private AiRunRepository runRepository;
    private AiProvenanceRepository provenanceRepository;
    private UserAccountStatusQuery accountStatusQuery;
    private CurrentCoachingContextQuery coachingContextQuery;
    private ProgressEngineQuery progressEngineQuery;
    private AiFeatureControlUseCase featureControlUseCase;
    private AiServiceProperties aiProperties;
    private ObjectMapper objectMapper;
    private Clock clock;

    private AiProgressSummarizationService service;

    private final UUID studentId = UUID.randomUUID();
    private final UUID trainerId = UUID.randomUUID();
    private final UUID strangerId = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final UUID correlationId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-10-28T12:00:00Z");

    @BeforeEach
    void setUp() {
        contextBuilder = Mockito.mock(F04ContextBuilder.class);
        aiServiceClient = Mockito.mock(F04AiServiceClient.class);
        runtimeValidator = Mockito.mock(F04RuntimeOutputValidator.class);
        runRepository = Mockito.mock(AiRunRepository.class);
        provenanceRepository = Mockito.mock(AiProvenanceRepository.class);
        accountStatusQuery = Mockito.mock(UserAccountStatusQuery.class);
        coachingContextQuery = Mockito.mock(CurrentCoachingContextQuery.class);
        progressEngineQuery = Mockito.mock(ProgressEngineQuery.class);
        featureControlUseCase = Mockito.mock(AiFeatureControlUseCase.class);
        aiProperties = new AiServiceProperties(true, "simulated", "http://localhost:8000", "key", 5000, 25000);
        objectMapper = new ObjectMapper();
        clock = Clock.fixed(now, ZoneId.of("UTC"));

        when(featureControlUseCase.isF01Enabled()).thenReturn(true);
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        service = new AiProgressSummarizationService(
                contextBuilder,
                aiServiceClient,
                runtimeValidator,
                runRepository,
                provenanceRepository,
                accountStatusQuery,
                coachingContextQuery,
                progressEngineQuery,
                featureControlUseCase,
                aiProperties,
                objectMapper,
                clock
        );
    }

    private F04InternalContextEnvelope createEnvelope() {
        return new F04InternalContextEnvelope(
                runId,
                correlationId,
                studentId,
                studentId,
                "STUDENT",
                new F04ProgressContextDto(
                        new DateWindowDto(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh"),
                        now,
                        new WorkoutSectionDto("AVAILABLE", null, null, null, "wm-workout"),
                        new MeasurementSectionDto("AVAILABLE", null, null, List.of(), "wm-meas"),
                        new ContinuitySignalDto("RECENT_ACTIVITY_OBSERVED", 2, LocalDate.of(2026, 10, 26), List.of(), null),
                        new HistoricalLimitsSummaryDto(false, false, false, null),
                        "ENGINE:composite-wm-v1"
                )
        );
    }

    private F04ProgressSummaryResponse createSuccessResponse() {
        return new F04ProgressSummaryResponse(
                runId,
                correlationId,
                studentId,
                studentId,
                "SUCCEEDED",
                "INFORMATION",
                "NONE",
                "Tóm tắt tiến độ thành công.",
                null,
                null,
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Recent activity", false),
                List.of(),
                List.of(),
                List.of("workout.adherence"),
                now,
                "ENGINE:composite-wm-v1",
                null,
                false,
                false,
                null
        );
    }

    @Test
    @DisplayName("Feature disabled: throws AiFeatureDisabledException")
    void featureDisabled_throwsException() {
        when(featureControlUseCase.isF01Enabled()).thenReturn(false);

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, null
        );

        assertThatThrownBy(() -> service.summarizeProgress(studentId, req))
                .isInstanceOf(AiFeatureDisabledException.class);
    }

    @Test
    @DisplayName("Successful summarization: orchestrates context, AI Run transitions, validation, and completion")
    void successfulSummarization_orchestratesFlow() {
        F04InternalContextEnvelope envelope = createEnvelope();
        ProgressSnapshot snapshot = Mockito.mock(ProgressSnapshot.class);
        when(snapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(studentId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, snapshot));

        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        F04ProgressSummaryResponse rawResponse = createSuccessResponse();
        when(aiServiceClient.summarizeProgress(envelope)).thenReturn(rawResponse);
        when(runtimeValidator.validate(rawResponse, envelope)).thenReturn(F04RuntimeOutputValidator.ValidationResult.valid());

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        F04ProgressSummaryResponse response = service.summarizeProgress(studentId, req);

        assertThat(response).isNotNull();
        assertThat(response.runStatus()).isEqualTo("SUCCEEDED");
        assertThat(response.resultType()).isEqualTo("INFORMATION");

        // Verify status transitions: QUEUED -> RUNNING -> completeRun(SUCCEEDED)
        verify(runRepository).transitionStatus(eq(envelope.requestId()), eq(AiRunStatus.QUEUED), eq(AiRunStatus.RUNNING), any(), any());
        verify(runRepository).completeRun(eq(envelope.requestId()), eq(AiRunStatus.SUCCEEDED), any(), any(), any(), any(), any(), any(), any());
        verify(provenanceRepository).saveSnapshot(any());
    }

    @Test
    @DisplayName("AI timeout: records FAILED status with TIMEOUT error and returns BLOCKED response")
    void aiTimeout_recordsFailedRun_returnsDiagnosticResponse() {
        F04InternalContextEnvelope envelope = createEnvelope();
        ProgressSnapshot snapshot = Mockito.mock(ProgressSnapshot.class);
        when(snapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(studentId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, snapshot));
        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        when(aiServiceClient.summarizeProgress(envelope))
                .thenThrow(new AiTimeoutException("AI service timed out"));

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        F04ProgressSummaryResponse response = service.summarizeProgress(studentId, req);

        assertThat(response.runStatus()).isEqualTo("FAILED");
        assertThat(response.resultType()).isEqualTo("BLOCKED");
        assertThat(response.reasonCode()).isEqualTo("TIMEOUT");

        verify(runRepository).completeRun(eq(envelope.requestId()), eq(AiRunStatus.FAILED), any(), eq("TIMEOUT"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Output validator rejection: records REJECTED_BY_VALIDATOR status")
    void validatorRejection_recordsRejectedStatus() {
        F04InternalContextEnvelope envelope = createEnvelope();
        ProgressSnapshot snapshot = Mockito.mock(ProgressSnapshot.class);
        when(snapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(studentId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, snapshot));
        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        F04ProgressSummaryResponse rawResponse = createSuccessResponse();
        when(aiServiceClient.summarizeProgress(envelope)).thenReturn(rawResponse);
        when(runtimeValidator.validate(rawResponse, envelope))
                .thenReturn(F04RuntimeOutputValidator.ValidationResult.invalid("Side channel leak detected"));

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        F04ProgressSummaryResponse response = service.summarizeProgress(studentId, req);

        assertThat(response.runStatus()).isEqualTo("REJECTED_BY_VALIDATOR");
        assertThat(response.reasonCode()).isEqualTo("VALIDATION_FAILED");

        verify(runRepository).completeRun(eq(envelope.requestId()), eq(AiRunStatus.REJECTED_BY_VALIDATOR), any(), eq("VALIDATION_FAILED"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Readback: student self-read retrieves stored summary")
    void readback_studentSelf_retrievesSummary() throws Exception {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);
        assertThat(result.runId()).isEqualTo(runId);
        assertThat(result.summary()).isEqualTo(storedResp.summary());
        assertThat(result.isStale()).isFalse();
    }

    @Test
    @DisplayName("Readback: trainer with revoked coaching relationship is denied (403)")
    void readback_trainerRevoked_deniesAccess() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        // Coaching relationship is no longer active with trainerId!
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), strangerId, null, null)));

        assertThatThrownBy(() -> service.getProgressSummary(trainerId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor no longer has coaching authority");
    }

    @Test
    @DisplayName("Readback freshness check: detects source changes via watermark mismatch and flags isStale=true")
    void readback_detectsWatermarkMismatch_flagsStale() throws Exception {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse(); // watermark is "ENGINE:composite-wm-v1"
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        // Re-query returns a new watermark because actual session was edited!
        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2-MODIFIED");
        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.isStale()).isTrue();
        assertThat(result.needsRefresh()).isTrue();
        assertThat(result.currentWatermark()).isEqualTo("ENGINE:composite-wm-v2-MODIFIED");
        // Historical summary content is preserved at original asOf!
        assertThat(result.sourceWatermark()).isEqualTo("ENGINE:composite-wm-v1");
        assertThat(result.summary()).isEqualTo(storedResp.summary());
    }

    @Test
    @DisplayName("Post-provider revalidation: actor account deactivated during AI call throws AiRunAccessDeniedException")
    void postProvider_actorDeactivated_throwsAccessDenied() {
        F04InternalContextEnvelope envelope = createEnvelope();
        ProgressSnapshot snapshot = Mockito.mock(ProgressSnapshot.class);
        when(snapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(studentId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, snapshot));
        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        F04ProgressSummaryResponse rawResponse = createSuccessResponse();
        when(aiServiceClient.summarizeProgress(envelope)).thenAnswer(inv -> {
            // Actor account deactivated while provider was executing!
            when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.SUSPENDED));
            return rawResponse;
        });

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        assertThatThrownBy(() -> service.summarizeProgress(studentId, req))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Actor account is no longer active");

        verify(runRepository).completeRun(eq(envelope.requestId()), eq(AiRunStatus.FAILED), any(), eq("ACCESS_REVOKED"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Post-provider revalidation: trainer coaching relationship revoked during AI call throws AiRunAccessDeniedException")
    void postProvider_coachingRevoked_throwsAccessDenied() {
        F04InternalContextEnvelope envelope = new F04InternalContextEnvelope(
                runId, correlationId, studentId, trainerId, "TRAINER",
                createEnvelope().context()
        );
        ProgressSnapshot snapshot = Mockito.mock(ProgressSnapshot.class);
        when(snapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(trainerId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, snapshot));
        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        F04ProgressSummaryResponse rawResponse = createSuccessResponse();
        when(aiServiceClient.summarizeProgress(envelope)).thenAnswer(inv -> {
            // Coaching relationship terminated while AI call was in flight!
            when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.empty());
            return rawResponse;
        });

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        assertThatThrownBy(() -> service.summarizeProgress(trainerId, req))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("no longer has coaching authority");

        verify(runRepository).completeRun(eq(envelope.requestId()), eq(AiRunStatus.FAILED), any(), eq("COACHING_REVOKED"), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Readback: measurement authority revoked masks measurement data without leaking metrics")
    void readback_measurementRevoked_masksMeasurementData() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, null, null)));

        F04ProgressSummaryResponse storedResp = new F04ProgressSummaryResponse(
                runId, correlationId, studentId, trainerId, "SUCCEEDED", "INFORMATION", "NONE",
                "Tiến độ tập luyện tốt.",
                new F04ProgressSummaryResponse.WorkoutProgressSummaryDto("Hoàn thành tốt", "80%", "1000kg"),
                new F04ProgressSummaryResponse.MeasurementProgressSummaryDto(List.of(
                        new F04ProgressSummaryResponse.MetricEvaluationDto("WEIGHT", "STABLE", "Cân nặng ổn định")
                )),
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Recent activity", false),
                List.of(),
                List.of(),
                List.of("workout.adherence", "measurement.weight"),
                now,
                "ENGINE:composite-wm-v1",
                null, false, false, null
        );
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        // Re-query: Workout is AVAILABLE, but Measurement is now RESTRICTED!
        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.RESTRICTED);

        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(progressEngineQuery.query(eq(trainerId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(trainerId, runId);

        // Workout data preserved
        assertThat(result.workoutProgressSummary()).isNotNull();
        assertThat(result.workoutProgressSummary().completionEvaluation()).isEqualTo("Hoàn thành tốt");

        // Measurement data strictly masked!
        assertThat(result.measurementProgressSummary()).isNull();
        assertThat(result.factsReferenced()).doesNotContain("measurement.weight");
        assertThat(result.factsReferenced()).contains("workout.adherence");
        assertThat(result.limitations()).anyMatch(l -> l.contains("số đo"));
    }

    @Test
    @DisplayName("Readback: both sources revoked throws AiRunAccessDeniedException")
    void readback_bothSourcesRevoked_throwsAccessDenied() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, null, null)));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        // Re-query: Both Workout and Measurement are RESTRICTED!
        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.RESTRICTED);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.RESTRICTED);

        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);

        when(progressEngineQuery.query(eq(trainerId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        assertThatThrownBy(() -> service.getProgressSummary(trainerId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("no longer has authority to access workout or measurement data");
    }

    @Test
    @DisplayName("Readback negative control: when domain data is unchanged and only composite watermark changes, summary remains fresh")
    void readback_whenDataUnchanged_onlyCompositeWatermarkChanged_remainsFresh() throws Exception {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse(); // sourceWatermark: "ENGINE:composite-wm-v1"
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  },
                  "workout": { "availability": "AVAILABLE", "sourceWatermark": "wm-workout" },
                  "measurement": { "availability": "AVAILABLE", "sourceWatermark": "wm-meas" },
                  "continuity": {
                    "state": "RECENT_ACTIVITY_OBSERVED",
                    "daysSinceLastWorkout": 2,
                    "lastWorkoutDate": "2026-10-26",
                    "safetyFlags": [],
                    "rationale": "Consistent training"
                  },
                  "historicalLimits": {
                    "hasHistoricalLimits": true,
                    "workoutLimits": true,
                    "measurementLimits": false,
                    "explanation": "Initial explanation"
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                2,
                LocalDate.of(2026, 10, 26),
                null, List.of(), "Consistent training"
        );
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(true, true, false, "Initial explanation");

        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(continuityMock);
        when(newSnapshot.historicalLimits()).thenReturn(limitsMock);
        // Only composite watermark changed due to asOf timestamp shift
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2-ASOF_SHIFT");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.isStale()).isFalse();
        assertThat(result.needsRefresh()).isFalse();
        assertThat(result.sourceWatermark()).isEqualTo("ENGINE:composite-wm-v1");
        assertThat(result.currentWatermark()).isEqualTo("ENGINE:composite-wm-v1");
    }

    @Test
    @DisplayName("Readback freshness check: continuity days changed flags isStale=true")
    void readback_continuityDaysChanged_flagsStale() throws Exception {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  },
                  "workout": { "availability": "AVAILABLE", "sourceWatermark": "wm-workout" },
                  "measurement": { "availability": "AVAILABLE", "sourceWatermark": "wm-meas" },
                  "continuity": {
                    "state": "RECENT_ACTIVITY_OBSERVED",
                    "daysSinceLastWorkout": 2,
                    "lastWorkoutDate": "2026-10-26",
                    "safetyFlags": [],
                    "rationale": "Consistent training"
                  },
                  "historicalLimits": {
                    "hasHistoricalLimits": false,
                    "workoutLimits": false,
                    "measurementLimits": false,
                    "explanation": null
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        // ONLY daysSinceLastWorkout changes: 2 -> 3; rationale and all others identical
        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                3,
                LocalDate.of(2026, 10, 26),
                null, List.of(), "Consistent training"
        );
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, null);

        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(continuityMock);
        when(newSnapshot.historicalLimits()).thenReturn(limitsMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.isStale()).isTrue();
        assertThat(result.needsRefresh()).isTrue();
        assertThat(result.currentWatermark()).isEqualTo("ENGINE:composite-wm-v2");
    }

    @Test
    @DisplayName("Readback freshness check: historical limits mismatch flags isStale=true")
    void readback_historicalLimitsChanged_flagsStale() throws Exception {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  },
                  "workout": { "availability": "AVAILABLE", "sourceWatermark": "wm-workout" },
                  "measurement": { "availability": "AVAILABLE", "sourceWatermark": "wm-meas" },
                  "continuity": {
                    "state": "RECENT_ACTIVITY_OBSERVED",
                    "daysSinceLastWorkout": 2,
                    "lastWorkoutDate": "2026-10-26",
                    "safetyFlags": [],
                    "rationale": "Consistent training"
                  },
                  "historicalLimits": {
                    "hasHistoricalLimits": false,
                    "workoutLimits": false,
                    "measurementLimits": false,
                    "explanation": null
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        // Continuity is completely unchanged
        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                2,
                LocalDate.of(2026, 10, 26),
                null, List.of(), "Consistent training"
        );
        // historicalLimits changes from false to true
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(true, true, false, "Historical limit reached");

        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(continuityMock);
        when(newSnapshot.historicalLimits()).thenReturn(limitsMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.isStale()).isTrue();
        assertThat(result.needsRefresh()).isTrue();
        assertThat(result.currentWatermark()).isEqualTo("ENGINE:composite-wm-v2");
    }

    @Test
    @DisplayName("Readback freshness check: explanation changed when hasHistoricalLimits remains true flags isStale=true")
    void readback_historicalExplanationChanged_whenHasLimitsRemainsTrue_flagsStale() throws Exception {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  },
                  "workout": { "availability": "AVAILABLE", "sourceWatermark": "wm-workout" },
                  "measurement": { "availability": "AVAILABLE", "sourceWatermark": "wm-meas" },
                  "continuity": {
                    "state": "RECENT_ACTIVITY_OBSERVED",
                    "daysSinceLastWorkout": 2,
                    "lastWorkoutDate": "2026-10-26",
                    "safetyFlags": [],
                    "rationale": "Consistent training"
                  },
                  "historicalLimits": {
                    "hasHistoricalLimits": true,
                    "workoutLimits": true,
                    "measurementLimits": false,
                    "explanation": "Initial explanation"
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        // Continuity is completely identical, including rationale
        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                2,
                LocalDate.of(2026, 10, 26),
                null, List.of(), "Consistent training"
        );
        // hasHistoricalLimits is still true, workoutLimits and measurementLimits identical; ONLY explanation changed!
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(true, true, false, "Modified explanation");

        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(continuityMock);
        when(newSnapshot.historicalLimits()).thenReturn(limitsMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.isStale()).isTrue();
        assertThat(result.needsRefresh()).isTrue();
        assertThat(result.currentWatermark()).isEqualTo("ENGINE:composite-wm-v2");
    }

    @Test
    @DisplayName("Readback freshness check: continuity safetyFlags changed flags isStale=true")
    void readback_continuitySafetyFlagsChanged_flagsStale() throws Exception {
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String json = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .studentId(studentId)
                .requestType(AiProgressSummarizationService.F04_REQUEST_TYPE)
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(json)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        String contextPayload = """
                {
                  "dateWindow": {
                    "fromDate": "2026-10-01",
                    "toDate": "2026-10-28",
                    "timezone": "Asia/Ho_Chi_Minh"
                  },
                  "workout": { "availability": "AVAILABLE", "sourceWatermark": "wm-workout" },
                  "measurement": { "availability": "AVAILABLE", "sourceWatermark": "wm-meas" },
                  "continuity": {
                    "state": "RECENT_ACTIVITY_OBSERVED",
                    "daysSinceLastWorkout": 2,
                    "lastWorkoutDate": "2026-10-26",
                    "safetyFlags": [],
                    "rationale": "Consistent training"
                  },
                  "historicalLimits": {
                    "hasHistoricalLimits": false,
                    "workoutLimits": false,
                    "measurementLimits": false,
                    "explanation": null
                  }
                }
                """;
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0", contextPayload, "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        // ONLY safetyFlags changed from [] to having "RETURN_AFTER_100_DAYS_V1"; rationale and all other fields identical
        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                2,
                LocalDate.of(2026, 10, 26),
                null, List.of("RETURN_AFTER_100_DAYS_V1"), "Consistent training"
        );
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, null);

        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(continuityMock);
        when(newSnapshot.historicalLimits()).thenReturn(limitsMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.isStale()).isTrue();
        assertThat(result.needsRefresh()).isTrue();
        assertThat(result.currentWatermark()).isEqualTo("ENGINE:composite-wm-v2");
    }

    @Test
    @DisplayName("Post-provider negative control: when domain data is unchanged and only composite watermark changes, summary remains fresh")
    void postProvider_whenDataUnchanged_onlyCompositeWatermarkChanged_remainsFresh() {
        F04InternalContextEnvelope envelope = new F04InternalContextEnvelope(
                runId, correlationId, studentId, studentId, "STUDENT",
                new F04ProgressContextDto(
                        new DateWindowDto(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh"),
                        now,
                        new WorkoutSectionDto("AVAILABLE", null, null, null, "wm-workout"),
                        new MeasurementSectionDto("AVAILABLE", null, null, List.of(), "wm-meas"),
                        new ContinuitySignalDto("RECENT_ACTIVITY_OBSERVED", 2, LocalDate.of(2026, 10, 26), List.of(), "Consistent training"),
                        new HistoricalLimitsSummaryDto(true, true, false, "Initial limitation"),
                        "ENGINE:composite-wm-v1"
                )
        );
        ProgressSnapshot initialSnapshot = Mockito.mock(ProgressSnapshot.class);
        when(initialSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(studentId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, initialSnapshot));
        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        F04ProgressSummaryResponse rawResponse = createSuccessResponse();
        when(aiServiceClient.summarizeProgress(envelope)).thenReturn(rawResponse);
        when(runtimeValidator.validate(rawResponse, envelope)).thenReturn(F04RuntimeOutputValidator.ValidationResult.valid());

        // Step 5e post-provider re-query returns completely unchanged domain data, only composite watermark changed
        ProgressSnapshot latestSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                2, LocalDate.of(2026, 10, 26), null, List.of(), "Consistent training"
        );
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(true, true, false, "Initial limitation");

        when(latestSnapshot.workout()).thenReturn(workoutMock);
        when(latestSnapshot.measurement()).thenReturn(measMock);
        when(latestSnapshot.continuity()).thenReturn(continuityMock);
        when(latestSnapshot.historicalLimits()).thenReturn(limitsMock);
        when(latestSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2-ASOF_SHIFT");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(latestSnapshot);

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        F04ProgressSummaryResponse response = service.summarizeProgress(studentId, req);

        assertThat(response.isStale()).isFalse();
        assertThat(response.needsRefresh()).isFalse();
        assertThat(response.sourceWatermark()).isEqualTo("ENGINE:composite-wm-v1");
        assertThat(response.currentWatermark()).isEqualTo("ENGINE:composite-wm-v1");
    }

    @Test
    @DisplayName("Post-provider freshness check: explanation changed when hasHistoricalLimits remains true flags isStale=true")
    void postProvider_historicalExplanationChanged_whenHasLimitsRemainsTrue_flagsStale() {
        F04InternalContextEnvelope envelope = new F04InternalContextEnvelope(
                runId, correlationId, studentId, studentId, "STUDENT",
                new F04ProgressContextDto(
                        new DateWindowDto(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh"),
                        now,
                        new WorkoutSectionDto("AVAILABLE", null, null, null, "wm-workout"),
                        new MeasurementSectionDto("AVAILABLE", null, null, List.of(), "wm-meas"),
                        new ContinuitySignalDto("RECENT_ACTIVITY_OBSERVED", 2, LocalDate.of(2026, 10, 26), List.of(), "Consistent training"),
                        new HistoricalLimitsSummaryDto(true, true, false, "Initial limitation"),
                        "ENGINE:composite-wm-v1"
                )
        );
        ProgressSnapshot initialSnapshot = Mockito.mock(ProgressSnapshot.class);
        when(initialSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(studentId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, initialSnapshot));
        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        F04ProgressSummaryResponse rawResponse = createSuccessResponse();
        when(aiServiceClient.summarizeProgress(envelope)).thenReturn(rawResponse);
        when(runtimeValidator.validate(rawResponse, envelope)).thenReturn(F04RuntimeOutputValidator.ValidationResult.valid());

        // Step 5e post-provider re-query returns changed explanation; all continuity fields identical
        ProgressSnapshot latestSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                2, LocalDate.of(2026, 10, 26), null, List.of(), "Consistent training"
        );
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(true, true, false, "Updated limitation");

        when(latestSnapshot.workout()).thenReturn(workoutMock);
        when(latestSnapshot.measurement()).thenReturn(measMock);
        when(latestSnapshot.continuity()).thenReturn(continuityMock);
        when(latestSnapshot.historicalLimits()).thenReturn(limitsMock);
        when(latestSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(latestSnapshot);

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        F04ProgressSummaryResponse response = service.summarizeProgress(studentId, req);

        assertThat(response.isStale()).isTrue();
        assertThat(response.needsRefresh()).isTrue();
        assertThat(response.currentWatermark()).isEqualTo("ENGINE:composite-wm-v2");
    }

    @Test
    @DisplayName("Post-provider freshness check: continuity safetyFlags changed flags isStale=true")
    void postProvider_continuitySafetyFlagsChanged_flagsStale() {
        F04InternalContextEnvelope envelope = new F04InternalContextEnvelope(
                runId, correlationId, studentId, studentId, "STUDENT",
                new F04ProgressContextDto(
                        new DateWindowDto(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh"),
                        now,
                        new WorkoutSectionDto("AVAILABLE", null, null, null, "wm-workout"),
                        new MeasurementSectionDto("AVAILABLE", null, null, List.of(), "wm-meas"),
                        new ContinuitySignalDto("RECENT_ACTIVITY_OBSERVED", 2, LocalDate.of(2026, 10, 26), List.of(), "Consistent training"),
                        new HistoricalLimitsSummaryDto(false, false, false, null),
                        "ENGINE:composite-wm-v1"
                )
        );
        ProgressSnapshot initialSnapshot = Mockito.mock(ProgressSnapshot.class);
        when(initialSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(contextBuilder.buildContext(eq(studentId), any(), any(), any()))
                .thenReturn(new F04ContextBuilder.BuildContextResult(envelope, initialSnapshot));
        when(runRepository.create(any())).thenAnswer(inv -> inv.getArgument(0));

        F04ProgressSummaryResponse rawResponse = createSuccessResponse();
        when(aiServiceClient.summarizeProgress(envelope)).thenReturn(rawResponse);
        when(runtimeValidator.validate(rawResponse, envelope)).thenReturn(F04RuntimeOutputValidator.ValidationResult.valid());

        // Step 5e post-provider re-query returns changed safetyFlags; rationale and all others identical
        ProgressSnapshot latestSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-workout");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-meas");

        var continuityMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.RECENT_ACTIVITY_OBSERVED,
                2, LocalDate.of(2026, 10, 26), null, List.of("RETURN_AFTER_100_DAYS_V1"), "Consistent training"
        );
        var limitsMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, null);

        when(latestSnapshot.workout()).thenReturn(workoutMock);
        when(latestSnapshot.measurement()).thenReturn(measMock);
        when(latestSnapshot.continuity()).thenReturn(continuityMock);
        when(latestSnapshot.historicalLimits()).thenReturn(limitsMock);
        when(latestSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v2");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(latestSnapshot);

        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 28), "Asia/Ho_Chi_Minh", null, correlationId
        );

        F04ProgressSummaryResponse response = service.summarizeProgress(studentId, req);

        assertThat(response.isStale()).isTrue();
        assertThat(response.needsRefresh()).isTrue();
        assertThat(response.currentWatermark()).isEqualTo("ENGINE:composite-wm-v2");
    }

    @Test
    @DisplayName("Scope preservation: readback returns explicit requested metric codes and scope, querying ProgressEngine with full requested metrics")
    void readback_preservesExplicitRequestedMetricCodes_evenWhenNoObservationsExist() throws Exception {
        Set<String> requestedMetrics = Set.of("WEIGHT", "BODY_FAT_PERCENTAGE", "WAIST_CIRCUMFERENCE");
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 31);
        String tz = "UTC";

        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, requestedMetrics);
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-31\",\"timezone\":\"UTC\"},\"measurement\":{\"metricDeltas\":[]}}}",
                "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-w");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-m");
        var contMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.UNKNOWN_HISTORY, null, null, null, List.of(), "rationale"
        );
        var limMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, "exact");
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(contMock);
        when(newSnapshot.historicalLimits()).thenReturn(limMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), eq(requestedMetrics), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result).isNotNull();
        assertThat(result.fromDate()).isEqualTo(from);
        assertThat(result.toDate()).isEqualTo(to);
        assertThat(result.timezone()).isEqualTo(tz);
        assertThat(result.requestedMetricCodes()).containsExactlyInAnyOrder("WEIGHT", "BODY_FAT_PERCENTAGE", "WAIST_CIRCUMFERENCE");

        // Verify that ProgressEngineQuery was called with all requestedMetrics, NOT an empty/inferred set
        verify(progressEngineQuery).query(eq(studentId), eq(studentId), eq(requestedMetrics), eq(from), eq(to), eq(ZoneId.of(tz)), any());
    }

    @Test
    @DisplayName("Scope preservation: legacy run without scope in outputPayload recovers dateWindow from snapshot")
    void readback_legacyRunWithoutScopeInOutput_fallsBackToDateWindowInSnapshot() throws Exception {
        // Legacy run has null fromDate, toDate, timezone in outputPayload
        F04ProgressSummaryResponse legacyStoredResp = createSuccessResponse();
        assertThat(legacyStoredResp.fromDate()).isNull();
        String storedJson = objectMapper.writeValueAsString(legacyStoredResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();

        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-09-01\",\"toDate\":\"2026-09-30\",\"timezone\":\"Asia/Ho_Chi_Minh\"},\"measurement\":{\"metricDeltas\":[{\"metricCode\":\"WEIGHT\"}]}}}",
                "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-w");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-m");
        var contMock = new ProgressEngineQuery.ContinuitySignal(
                ProgressEngineQuery.ContinuityState.UNKNOWN_HISTORY, null, null, null, List.of(), "rationale"
        );
        var limMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, "exact");
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(contMock);
        when(newSnapshot.historicalLimits()).thenReturn(limMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:composite-wm-v1");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result).isNotNull();
        assertThat(result.fromDate()).isEqualTo(LocalDate.of(2026, 9, 1));
        assertThat(result.toDate()).isEqualTo(LocalDate.of(2026, 9, 30));
        assertThat(result.timezone()).isEqualTo("Asia/Ho_Chi_Minh");
        assertThat(result.metricScope()).isEqualTo("UNKNOWN_LEGACY");
        assertThat(result.requestedMetricCodes()).isNull();
        verify(progressEngineQuery).query(eq(studentId), eq(studentId), (Set<String>) isNull(), eq(LocalDate.of(2026, 9, 1)), eq(LocalDate.of(2026, 9, 30)), any(), any());
    }

    @Test
    @DisplayName("Scope preservation: ALL metric scope preserves ALL and passes null to ProgressEngineQuery")
    void readback_allMetricScope_preservesAllScope_andPassesNullToProgressEngineQuery() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, null, "ALL");
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}}}",
                "{}", "{\"metricScope\":\"ALL\",\"requestedMetricCodes\":null,\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(workoutMock.sourceWatermark()).thenReturn("wm-w");
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(measMock.sourceWatermark()).thenReturn("wm-m");
        var contMock = new ProgressEngineQuery.ContinuitySignal(ProgressEngineQuery.ContinuityState.UNKNOWN_HISTORY, null, null, null, List.of(), "rationale");
        var limMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, "exact");
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(contMock);
        when(newSnapshot.historicalLimits()).thenReturn(limMock);
        when(newSnapshot.compositeWatermark()).thenReturn("wm-comp");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) isNull(), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result).isNotNull();
        assertThat(result.metricScope()).isEqualTo("ALL");
        assertThat(result.requestedMetricCodes()).isNull();
        verify(progressEngineQuery).query(eq(studentId), eq(studentId), (Set<String>) isNull(), eq(from), eq(to), eq(ZoneId.of(tz)), any());
    }

    @Test
    @DisplayName("Scope preservation: ALL metric scope detects new metric observation and marks summary stale")
    void readback_allMetricScope_detectsNewMetricObservation_flagsStale() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, null, "ALL");
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"},\"measurement\":{\"availability\":\"AVAILABLE\",\"sourceWatermark\":\"wm-old-meas\"}}}",
                "{}", "{\"metricScope\":\"ALL\"}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        // New metric observation altered the measurement watermark!
        when(measMock.sourceWatermark()).thenReturn("wm-new-meas-added");
        var contMock = new ProgressEngineQuery.ContinuitySignal(ProgressEngineQuery.ContinuityState.UNKNOWN_HISTORY, null, null, null, List.of(), "rationale");
        var limMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, "exact");
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(contMock);
        when(newSnapshot.historicalLimits()).thenReturn(limMock);
        when(newSnapshot.compositeWatermark()).thenReturn("wm-composite-new");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) isNull(), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.isStale()).isTrue();
        assertThat(result.needsRefresh()).isTrue();
        assertThat(result.currentWatermark()).isEqualTo("wm-composite-new");
    }

    @Test
    @DisplayName("Scope preservation: SELECTED metric scope preserves requested metric even with zero initial observations")
    void readback_selectedMetricScope_preservesRequestedCodes_evenWithZeroObservations() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";
        Set<String> requested = Set.of("CHEST_CIRCUMFERENCE");

        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, requested, "SELECTED");
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        // Snapshot has empty metricDeltas (0 observations recorded for CHEST_CIRCUMFERENCE)
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"},\"measurement\":{\"metricDeltas\":[]}}}",
                "{}", "{\"metricScope\":\"SELECTED\",\"requestedMetricCodes\":[\"CHEST_CIRCUMFERENCE\"]}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var contMock = new ProgressEngineQuery.ContinuitySignal(ProgressEngineQuery.ContinuityState.UNKNOWN_HISTORY, null, null, null, List.of(), "rationale");
        var limMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, "exact");
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(contMock);
        when(newSnapshot.historicalLimits()).thenReturn(limMock);
        when(newSnapshot.compositeWatermark()).thenReturn("wm-comp");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), eq(requested), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.metricScope()).isEqualTo("SELECTED");
        assertThat(result.requestedMetricCodes()).containsExactly("CHEST_CIRCUMFERENCE");
        verify(progressEngineQuery).query(eq(studentId), eq(studentId), eq(requested), eq(from), eq(to), eq(ZoneId.of(tz)), any());
    }

    @Test
    @DisplayName("Scope preservation: legacy run without metadata restores scope from saved dataQualitySummary")
    void readback_legacyRunWithSavedMetadata_restoresScope() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        // Output payload has null scope (older format)
        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}}}",
                "{}",
                "{\"metricScope\":\"SELECTED\",\"requestedMetricCodes\":[\"WEIGHT\",\"WAIST_CIRCUMFERENCE\"],\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
                "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var contMock = new ProgressEngineQuery.ContinuitySignal(ProgressEngineQuery.ContinuityState.UNKNOWN_HISTORY, null, null, null, List.of(), "rationale");
        var limMock = new ProgressEngineQuery.HistoricalLimitsSummary(false, false, false, "exact");
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.continuity()).thenReturn(contMock);
        when(newSnapshot.historicalLimits()).thenReturn(limMock);
        when(newSnapshot.compositeWatermark()).thenReturn("wm-comp");

        when(progressEngineQuery.query(eq(studentId), eq(studentId), (Set<String>) any(), any(), any(), any(), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.metricScope()).isEqualTo("SELECTED");
        assertThat(result.requestedMetricCodes()).containsExactlyInAnyOrder("WEIGHT", "WAIST_CIRCUMFERENCE");
        assertThat(result.fromDate()).isEqualTo(from);
        assertThat(result.toDate()).isEqualTo(to);
        verify(progressEngineQuery).query(eq(studentId), eq(studentId), eq(Set.of("WEIGHT", "WAIST_CIRCUMFERENCE")), eq(from), eq(to), eq(ZoneId.of(tz)), any());
    }

    @Test
    @DisplayName("Scope validation: summarizeProgress with empty metricCodes list rejects with IllegalArgumentException")
    void summarizeProgress_emptyMetricCodesList_rejectsWithIllegalArgumentException() {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        F04ProgressSummaryApiRequest req = new F04ProgressSummaryApiRequest(
                studentId, from, to, "Asia/Ho_Chi_Minh", Set.of(), correlationId
        );

        assertThatThrownBy(() -> service.summarizeProgress(studentId, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metricCodes must not be empty when provided");
    }

    @Test
    @DisplayName("Scope validation: SELECTED scope with missing/empty codes normalizes to UNKNOWN_LEGACY and never queries as ALL or Set.of()")
    void readback_selectedScope_missingOrEmptyCodes_normalizesToUnknownLegacy_neverQueriesWithNullOrEmptySet() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        // Stored response says SELECTED but has null requestedMetricCodes
        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, null, "SELECTED");
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        // Snapshot metadata ALSO lacks codes (empty array)
        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}}}",
                "{}",
                "{\"metricScope\":\"SELECTED\",\"requestedMetricCodes\":[],\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
                "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        // Strict normalization: SELECTED without codes cannot be executed as SELECTED or converted to ALL
        assertThat(result.metricScope()).isEqualTo("UNKNOWN_LEGACY");
        assertThat(result.requestedMetricCodes()).isNull();

        // Must NEVER convert SELECTED thiếu codes into an ALL query with null or empty set!
        verify(progressEngineQuery, never()).query(any(), any(), (Set<String>) isNull(), any(), any(), any(), any());
        verify(progressEngineQuery, never()).query(any(), any(), eq(Set.of()), any(), any(), any(), any());
    }

    @Test
    @DisplayName("Scope validation: invalid/unsupported scope value normalizes to UNKNOWN_LEGACY and does not treat as ALL")
    void readback_invalidScopeValue_normalizesToUnknownLegacy_andDoesNotTreatAsAll() throws Exception {
        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, null, "UNSUPPORTED_SCOPE_VALUE");
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(studentId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}}}",
                "{}",
                "{}",
                "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        F04ProgressSummaryResponse result = service.getProgressSummary(studentId, runId);

        assertThat(result.metricScope()).isEqualTo("UNKNOWN_LEGACY");
        assertThat(result.requestedMetricCodes()).isNull();
    }

    @Test
    @DisplayName("Readback authority: SELECTED missing codes, trainer lacks BODY_METRICS masks measurement and does not leak data")
    void readback_selectedScope_missingCodes_trainerLacksBodyMetrics_masksMeasurementAndDoesNotLeakData() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, null, null)));

        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        F04ProgressSummaryResponse storedResp = new F04ProgressSummaryResponse(
                runId, correlationId, studentId, trainerId,
                from, to, tz, null, "SELECTED",
                "SUCCEEDED", "INFORMATION", "NONE",
                "Tiến độ tập luyện và số đo thể chất.",
                new F04ProgressSummaryResponse.WorkoutProgressSummaryDto("Hoàn thành tốt", "85%", "1200kg"),
                new F04ProgressSummaryResponse.MeasurementProgressSummaryDto(List.of(
                        new F04ProgressSummaryResponse.MetricEvaluationDto("WEIGHT", "DECREASED", "Cân nặng giảm 1.5kg"),
                        new F04ProgressSummaryResponse.MetricEvaluationDto("BODY_FAT_PERCENTAGE", "DECREASED", "Mỡ giảm 0.8%")
                )),
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Recent activity", false),
                List.of(),
                List.of(),
                List.of("workout.adherence", "measurement.weight.latest", "measurement.body_fat.latest"),
                now,
                "ENGINE:composite-wm-v1",
                null, false, false, null
        );
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(trainerId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"},\"workout\":{\"availability\":\"AVAILABLE\"},\"measurement\":{\"availability\":\"AVAILABLE\"}}}",
                "{}",
                "{\"metricScope\":\"SELECTED\",\"requestedMetricCodes\":[],\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
                "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        // ProgressEngine check: Workout is AVAILABLE, but Measurement is RESTRICTED (BODY_METRICS revoked)
        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.RESTRICTED);
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:wm-post-recheck");

        when(progressEngineQuery.query(eq(trainerId), eq(studentId), (Set<String>) any(), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(trainerId, runId);

        // Scope normalized
        assertThat(result.metricScope()).isEqualTo("UNKNOWN_LEGACY");
        assertThat(result.requestedMetricCodes()).isNull();
        assertThat(result.isStale()).isTrue();
        assertThat(result.needsRefresh()).isTrue();

        // Workout data preserved
        assertThat(result.workoutProgressSummary()).isNotNull();
        assertThat(result.workoutProgressSummary().completionEvaluation()).isEqualTo("Hoàn thành tốt");

        // Measurement strictly masked, no sensitive data leaked!
        assertThat(result.measurementProgressSummary()).isNull();
        assertThat(result.factsReferenced()).contains("workout.adherence");
        assertThat(result.factsReferenced()).doesNotContain("measurement.weight.latest");
        assertThat(result.factsReferenced()).doesNotContain("measurement.body_fat.latest");
        assertThat(result.limitations()).anyMatch(l -> l.contains("số đo"));
    }

    @Test
    @DisplayName("Readback authority: SELECTED missing codes, trainer lacks Workout masks workout section")
    void readback_selectedScope_missingCodes_trainerLacksWorkout_masksWorkout() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, null, null)));

        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        F04ProgressSummaryResponse storedResp = new F04ProgressSummaryResponse(
                runId, correlationId, studentId, trainerId,
                from, to, tz, null, "SELECTED",
                "SUCCEEDED", "INFORMATION", "NONE",
                "Tiến độ tập luyện và số đo thể chất.",
                new F04ProgressSummaryResponse.WorkoutProgressSummaryDto("Hoàn thành tốt", "85%", "1200kg"),
                new F04ProgressSummaryResponse.MeasurementProgressSummaryDto(List.of(
                        new F04ProgressSummaryResponse.MetricEvaluationDto("WEIGHT", "DECREASED", "Cân nặng giảm 1.5kg")
                )),
                new ContinuityEvaluationDto("RECENT_ACTIVITY_OBSERVED", "Recent activity", false),
                List.of(),
                List.of(),
                List.of("workout.adherence", "measurement.weight.latest"),
                now,
                "ENGINE:composite-wm-v1",
                null, false, false, null
        );
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(trainerId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"},\"workout\":{\"availability\":\"AVAILABLE\"},\"measurement\":{\"availability\":\"AVAILABLE\"}}}",
                "{}",
                "{\"metricScope\":\"SELECTED\",\"requestedMetricCodes\":[],\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
                "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        // ProgressEngine check: Workout is RESTRICTED, Measurement is AVAILABLE
        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.RESTRICTED);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.AVAILABLE);
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);
        when(newSnapshot.compositeWatermark()).thenReturn("ENGINE:wm-post-recheck");

        when(progressEngineQuery.query(eq(trainerId), eq(studentId), (Set<String>) any(), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenReturn(newSnapshot);

        F04ProgressSummaryResponse result = service.getProgressSummary(trainerId, runId);

        // Workout strictly masked
        assertThat(result.workoutProgressSummary()).isNull();
        assertThat(result.factsReferenced()).doesNotContain("workout.adherence");
        assertThat(result.limitations()).anyMatch(l -> l.contains("buổi tập"));

        // Measurement preserved
        assertThat(result.measurementProgressSummary()).isNotNull();
        assertThat(result.factsReferenced()).contains("measurement.weight.latest");
    }

    @Test
    @DisplayName("Readback authority: SELECTED missing codes, trainer lacks both sources throws AiRunAccessDeniedException")
    void readback_selectedScope_missingCodes_trainerLacksBothSources_throwsAccessDenied() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, null, null)));

        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, null, "SELECTED");
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(trainerId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}}}",
                "{}",
                "{\"metricScope\":\"SELECTED\",\"requestedMetricCodes\":[],\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}",
                "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        ProgressSnapshot newSnapshot = Mockito.mock(ProgressSnapshot.class);
        var workoutMock = Mockito.mock(ProgressEngineQuery.WorkoutProgressSection.class);
        when(workoutMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.RESTRICTED);
        var measMock = Mockito.mock(ProgressEngineQuery.MeasurementProgressSection.class);
        when(measMock.availability()).thenReturn(ProgressEngineQuery.SourceAvailability.RESTRICTED);
        when(newSnapshot.workout()).thenReturn(workoutMock);
        when(newSnapshot.measurement()).thenReturn(measMock);

        when(progressEngineQuery.query(eq(trainerId), eq(studentId), (Set<String>) any(), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenReturn(newSnapshot);

        assertThatThrownBy(() -> service.getProgressSummary(trainerId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("no longer has authority to access workout or measurement data");
    }

    @Test
    @DisplayName("Readback authority fail-closed: trainer readback with missing snapshot fails closed")
    void readback_trainer_missingSnapshotOrCorruptedMetadata_failsClosed() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, null, null)));

        F04ProgressSummaryResponse storedResp = createSuccessResponse();
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(trainerId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        // Snapshot is missing
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getProgressSummary(trainerId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Cannot verify trainer source permissions due to missing or corrupted metadata");
    }

    @Test
    @DisplayName("Readback authority fail-closed: technical failure during trainer source permission check fails closed")
    void readback_trainer_technicalFailureDuringAuthorityCheck_failsClosed() throws Exception {
        when(accountStatusQuery.getAccountStatus(trainerId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(accountStatusQuery.getAccountStatus(studentId)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId, null, null)));

        LocalDate from = LocalDate.of(2026, 10, 1);
        LocalDate to = LocalDate.of(2026, 10, 25);
        String tz = "Asia/Ho_Chi_Minh";

        F04ProgressSummaryResponse storedResp = createSuccessResponse().withScope(from, to, tz, null, "ALL");
        String storedJson = objectMapper.writeValueAsString(storedResp);

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(trainerId)
                .studentId(studentId)
                .requestType("F04_PROGRESS_SUMMARY")
                .status(AiRunStatus.SUCCEEDED)
                .outputPayload(storedJson)
                .build();
        when(runRepository.findById(runId)).thenReturn(Optional.of(run));

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(), runId, "v1.0",
                "{\"context\":{\"dateWindow\":{\"fromDate\":\"2026-10-01\",\"toDate\":\"2026-10-25\",\"timezone\":\"Asia/Ho_Chi_Minh\"}}}",
                "{}", "{}", "{}", "hash", now
        );
        when(provenanceRepository.findSnapshotByRunId(runId)).thenReturn(Optional.of(snapshot));

        when(progressEngineQuery.query(eq(trainerId), eq(studentId), (Set<String>) any(), eq(from), eq(to), eq(ZoneId.of(tz)), any()))
                .thenThrow(new RuntimeException("Data store connection timeout"));

        assertThatThrownBy(() -> service.getProgressSummary(trainerId, runId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Failed to verify source permissions for run");
    }
}
