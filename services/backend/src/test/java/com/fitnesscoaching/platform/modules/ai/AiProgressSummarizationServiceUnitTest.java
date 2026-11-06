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
}
