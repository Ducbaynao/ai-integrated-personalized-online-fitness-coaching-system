package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiFeatureDisabledException;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.AiTimeoutException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F04ProgressSummaryResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.AiServiceProperties;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiFeatureControlUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiProgressSummarizationUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F04AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F04InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Application service coordinating F04 Progress Summarization and readback.
 * <p>
 * Implements:
 * 1. Minimal authorized context building via ProgressEngineQuery.
 * 2. Immutable AI Run creation with context snapshot.
 * 3. Non-transactional external AI service dispatch with timeout/provider isolation.
 * 4. Strict runtime output validation against contract invariants.
 * 5. Terminal AI Run audit recording.
 * 6. Readback with real-time authority revalidation and source watermark freshness detection.
 */
@Service
public class AiProgressSummarizationService implements AiProgressSummarizationUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiProgressSummarizationService.class);
    public static final String F04_REQUEST_TYPE = "F04_PROGRESS_SUMMARY";

    private final F04ContextBuilder contextBuilder;
    private final F04AiServiceClient aiServiceClient;
    private final F04RuntimeOutputValidator runtimeValidator;
    private final AiRunRepository runRepository;
    private final AiProvenanceRepository provenanceRepository;
    private final UserAccountStatusQuery accountStatusQuery;
    private final CurrentCoachingContextQuery coachingContextQuery;
    private final ProgressEngineQuery progressEngineQuery;
    private final AiFeatureControlUseCase featureControlUseCase;
    private final AiServiceProperties aiProperties;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public AiProgressSummarizationService(
            F04ContextBuilder contextBuilder,
            F04AiServiceClient aiServiceClient,
            F04RuntimeOutputValidator runtimeValidator,
            AiRunRepository runRepository,
            AiProvenanceRepository provenanceRepository,
            UserAccountStatusQuery accountStatusQuery,
            CurrentCoachingContextQuery coachingContextQuery,
            ProgressEngineQuery progressEngineQuery,
            AiFeatureControlUseCase featureControlUseCase,
            AiServiceProperties aiProperties,
            ObjectMapper objectMapper,
            Clock clock
    ) {
        this.contextBuilder = contextBuilder;
        this.aiServiceClient = aiServiceClient;
        this.runtimeValidator = runtimeValidator;
        this.runRepository = runRepository;
        this.provenanceRepository = provenanceRepository;
        this.accountStatusQuery = accountStatusQuery;
        this.coachingContextQuery = coachingContextQuery;
        this.progressEngineQuery = progressEngineQuery;
        this.featureControlUseCase = featureControlUseCase;
        this.aiProperties = aiProperties;
        this.objectMapper = objectMapper;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    @Override
    public F04ProgressSummaryResponse summarizeProgress(UUID actorId, F04ProgressSummaryApiRequest request) {
        // 0. Feature enable check
        if (!Boolean.TRUE.equals(aiProperties.enabled()) || (featureControlUseCase != null && !featureControlUseCase.isF01Enabled())) {
            throw new AiFeatureDisabledException("AI progress summarization is currently disabled by administrator configuration.");
        }

        UUID requestId = UUID.randomUUID();
        UUID correlationId = request.correlationId() != null ? request.correlationId() : UUID.randomUUID();

        // 1. Build minimal authorized context from ProgressEngineQuery
        F04ContextBuilder.BuildContextResult contextResult = contextBuilder.buildContext(
                actorId,
                request,
                requestId,
                correlationId
        );
        F04InternalContextEnvelope envelope = contextResult.envelope();

        // 2. Transaction 1: Create initial AI Run and persist Context Snapshot
        AiRun run = createInitialAiRun(actorId, envelope);

        // 3. Transaction 2: Transition from QUEUED to RUNNING
        Instant startedAt = Instant.now(clock);
        runRepository.transitionStatus(run.id(), AiRunStatus.QUEUED, AiRunStatus.RUNNING, startedAt, null);

        // 4. Dispatch external AI Service (OUTSIDE ANY DATABASE TRANSACTION)
        long startMs = System.currentTimeMillis();
        F04ProgressSummaryResponse serviceResponse = null;
        AiRunStatus terminalStatus = AiRunStatus.FAILED;
        String errorCode = null;
        String errorMessage = null;

        try {
            serviceResponse = aiServiceClient.summarizeProgress(envelope);
        } catch (AiTimeoutException ex) {
            log.warn("FastAPI AI timeout for F04 run {}: {}", run.id(), ex.getMessage());
            terminalStatus = AiRunStatus.FAILED;
            errorCode = "TIMEOUT";
            errorMessage = ex.getMessage();
        } catch (AiProviderException ex) {
            log.warn("FastAPI AI provider failure for F04 run {}: {}", run.id(), ex.getMessage());
            terminalStatus = AiRunStatus.FAILED;
            errorCode = "MODEL_ERROR";
            errorMessage = ex.getMessage();
        } catch (AiOutputValidationException ex) {
            log.warn("FastAPI AI output validation failure for F04 run {}: {}", run.id(), ex.getMessage());
            terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
            errorCode = "VALIDATION_FAILED";
            errorMessage = ex.getMessage();
        } catch (Exception ex) {
            log.error("Unexpected failure during F04 AI orchestration for run {}", run.id(), ex);
            terminalStatus = AiRunStatus.FAILED;
            errorCode = "PROCESSING_ERROR";
            errorMessage = "An unexpected processing error occurred.";
        }

        long latencyMs = System.currentTimeMillis() - startMs;

        // 5. Post-provider revalidation before publishing or committing
        if (serviceResponse != null) {
            // 5a. Re-verify Actor Account Status
            AccountStatus postActorStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
            if (postActorStatus != AccountStatus.ACTIVE) {
                log.warn("Actor account was deactivated during F04 AI summarization for run {}", run.id());
                terminalStatus = AiRunStatus.FAILED;
                errorCode = "ACCESS_REVOKED";
                errorMessage = "Actor account was deactivated during summarization.";
                runRepository.completeRun(run.id(), terminalStatus, null, errorCode, errorMessage, null, null, (int) latencyMs, Instant.now(clock));
                throw new AiRunAccessDeniedException("Actor account is no longer active.");
            }

            // 5b. Re-verify Target Student Account Status
            AccountStatus postStudentStatus = accountStatusQuery.getAccountStatus(envelope.studentId()).orElse(null);
            if (postStudentStatus != AccountStatus.ACTIVE) {
                log.warn("Target student account was deactivated during F04 AI summarization for run {}", run.id());
                terminalStatus = AiRunStatus.FAILED;
                errorCode = "ACCESS_REVOKED";
                errorMessage = "Target student account was deactivated during summarization.";
                runRepository.completeRun(run.id(), terminalStatus, null, errorCode, errorMessage, null, null, (int) latencyMs, Instant.now(clock));
                throw new AiRunAccessDeniedException("Target student account is no longer active.");
            }

            // 5c. Re-verify Coaching Authority if actor is Trainer
            if (!actorId.equals(envelope.studentId())) {
                var coachingOpt = coachingContextQuery.findEffectiveContext(envelope.studentId(), false);
                if (coachingOpt.isEmpty() || coachingOpt.get().trainerId() == null || !actorId.equals(coachingOpt.get().trainerId())) {
                    log.warn("Trainer coaching authority was revoked during F04 AI summarization for run {}", run.id());
                    terminalStatus = AiRunStatus.FAILED;
                    errorCode = "COACHING_REVOKED";
                    errorMessage = "Coaching authority was revoked during summarization.";
                    runRepository.completeRun(run.id(), terminalStatus, null, errorCode, errorMessage, null, null, (int) latencyMs, Instant.now(clock));
                    throw new AiRunAccessDeniedException("Actor no longer has coaching authority for this student.");
                }
            }

            // 5d. Strict runtime validation of AI service output
            F04RuntimeOutputValidator.ValidationResult valResult = runtimeValidator.validate(serviceResponse, envelope);
            if (!valResult.isValid()) {
                log.warn("F04 AI service output violated contract invariants for run {}: {}", run.id(), valResult.failureReason());
                terminalStatus = AiRunStatus.REJECTED_BY_VALIDATOR;
                errorCode = "VALIDATION_FAILED";
                errorMessage = "AI service output violated contract invariants: " + valResult.failureReason();
                serviceResponse = null;
            } else {
                terminalStatus = AiRunStatus.SUCCEEDED;

                // 5e. Check if source changed while generating summary
                boolean isStale = false;
                boolean needsRefresh = false;
                String currentWatermark = envelope.context().compositeWatermark();
                try {
                    var latestSnapshot = progressEngineQuery.query(
                            actorId,
                            envelope.studentId(),
                            request.metricCodes(),
                            request.fromDate(),
                            request.toDate(),
                            ZoneId.of(request.timezone() != null ? request.timezone() : "Asia/Ho_Chi_Minh"),
                            Instant.now(clock)
                    );
                    currentWatermark = latestSnapshot.compositeWatermark();
                    if (!Objects.equals(envelope.context().compositeWatermark(), currentWatermark)) {
                        isStale = true;
                        needsRefresh = true;
                    }
                } catch (Exception ex) {
                    log.debug("Could not verify immediate watermark change: {}", ex.getMessage());
                }

                if (isStale) {
                    serviceResponse = F04ProgressSummaryResponse.withFreshness(serviceResponse, true, true, currentWatermark);
                }
            }
        }

        // 6. Complete AI Run in database
        String outputPayloadJson = null;
        Integer inputTokens = null;
        Integer outputTokens = null;

        if (serviceResponse != null) {
            try {
                outputPayloadJson = objectMapper.writeValueAsString(serviceResponse);
            } catch (Exception ex) {
                log.error("Failed to serialize F04 response payload for run {}", run.id(), ex);
            }
            if (serviceResponse.usage() != null) {
                inputTokens = serviceResponse.usage().promptTokens();
                outputTokens = serviceResponse.usage().completionTokens();
            }
        }

        runRepository.completeRun(
                run.id(),
                terminalStatus,
                outputPayloadJson,
                errorCode,
                errorMessage,
                inputTokens,
                outputTokens,
                (int) latencyMs,
                Instant.now(clock)
        );

        if (serviceResponse == null) {
            // Build sanitised diagnostic response when invocation failed
            return new F04ProgressSummaryResponse(
                    run.id(),
                    correlationId,
                    envelope.studentId(),
                    actorId,
                    terminalStatus.name(),
                    "BLOCKED",
                    errorCode != null ? errorCode : "PROCESSING_ERROR",
                    errorMessage != null ? errorMessage : "Không thể tạo bản tóm tắt tiến độ.",
                    null,
                    null,
                    null,
                    List.of("Lỗi xử lý hệ thống khi tạo bản tóm tắt."),
                    List.of(),
                    List.of(),
                    envelope.context().effectiveAsOf(),
                    envelope.context().compositeWatermark(),
                    null,
                    false,
                    false,
                    null
            );
        }

        return serviceResponse;
    }

    @Override
    @Transactional(readOnly = true)
    public F04ProgressSummaryResponse getProgressSummary(UUID actorId, UUID runId) {
        if (actorId == null || runId == null) {
            throw new AiRunAccessDeniedException("actorId and runId are required.");
        }

        // 1. Check Actor Account Status
        AccountStatus actorStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (actorStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        // 2. Fetch AI Run
        AiRun run = runRepository.findById(runId)
                .orElseThrow(() -> new AiRunNotFoundException("AI Run not found: " + runId));

        if (!F04_REQUEST_TYPE.equals(run.requestType())) {
            throw new AiRunNotFoundException("AI Run is not an F04 Progress Summary: " + runId);
        }

        // 3. Real-time Authority Revalidation
        UUID studentId = run.studentId();

        // 3a. Target student account must be active
        AccountStatus studentStatus = accountStatusQuery.getAccountStatus(studentId).orElse(null);
        if (studentStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Target student account is not active.");
        }

        // 3b. Coaching authority if actor is Trainer
        if (!actorId.equals(studentId)) {
            // Actor is a Trainer -> must have an active coaching context with this student
            var coachingOpt = coachingContextQuery.findEffectiveContext(studentId, false);
            if (coachingOpt.isEmpty() || coachingOpt.get().trainerId() == null || !actorId.equals(coachingOpt.get().trainerId())) {
                throw new AiRunAccessDeniedException("Actor no longer has coaching authority to access progress summary for student: " + studentId);
            }
        }

        if (run.outputPayload() == null || run.outputPayload().isBlank()) {
            throw new AiRunNotFoundException("AI Run has no completed output payload: " + runId);
        }

        F04ProgressSummaryResponse baseResponse;
        try {
            baseResponse = objectMapper.readValue(run.outputPayload(), F04ProgressSummaryResponse.class);
        } catch (Exception ex) {
            log.error("Failed to deserialize F04 output payload for run {}", runId, ex);
            throw new RuntimeException("Corrupted AI Run payload for run " + runId);
        }

        // 4. Source Permission & Freshness Verification at Readback
        boolean isStale = false;
        boolean needsRefresh = false;
        String currentWatermark = null;

        var snapshotOpt = provenanceRepository.findSnapshotByRunId(runId);
        if (snapshotOpt.isPresent()) {
            try {
                JsonNode ctxNode = objectMapper.readTree(snapshotOpt.get().contextPayload());
                JsonNode dwNode = ctxNode.path("dateWindow");
                if (!dwNode.isMissingNode()) {
                    LocalDate fromDate = LocalDate.parse(dwNode.path("fromDate").asText());
                    LocalDate toDate = LocalDate.parse(dwNode.path("toDate").asText());
                    ZoneId timezone = ZoneId.of(dwNode.path("timezone").asText("Asia/Ho_Chi_Minh"));

                    // Re-query progress engine to get current watermark and current source permissions
                    var currentSnapshot = progressEngineQuery.query(
                            actorId,
                            studentId,
                            (Set<String>) null,
                            fromDate,
                            toDate,
                            timezone,
                            Instant.now(clock)
                    );
                    currentWatermark = currentSnapshot.compositeWatermark();

                    if (!Objects.equals(baseResponse.sourceWatermark(), currentWatermark)) {
                        isStale = true;
                        needsRefresh = true;
                    }

                    boolean workoutRestricted = currentSnapshot.workout().availability() != ProgressEngineQuery.SourceAvailability.AVAILABLE;
                    boolean measurementRestricted = currentSnapshot.measurement().availability() != ProgressEngineQuery.SourceAvailability.AVAILABLE;

                    if (workoutRestricted && measurementRestricted) {
                        throw new AiRunAccessDeniedException("Actor no longer has authority to access workout or measurement data for student: " + studentId);
                    }

                    if (workoutRestricted && baseResponse.workoutProgressSummary() != null) {
                        baseResponse = baseResponse.withMaskedWorkout();
                    }

                    if (measurementRestricted && baseResponse.measurementProgressSummary() != null) {
                        baseResponse = baseResponse.withMaskedMeasurement();
                    }
                }
            } catch (AiRunAccessDeniedException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Could not verify freshness or source permissions for run {}: {}", runId, ex.getMessage());
            }
        }

        return F04ProgressSummaryResponse.withFreshness(baseResponse, isStale, needsRefresh, currentWatermark);
    }

    @Transactional
    protected AiRun createInitialAiRun(UUID actorId, F04InternalContextEnvelope envelope) {
        String inputPayloadJson;
        try {
            inputPayloadJson = objectMapper.writeValueAsString(envelope);
        } catch (Exception ex) {
            log.error("Failed to serialize F04 input envelope", ex);
            inputPayloadJson = "{}";
        }

        AiRun run = AiRun.builder()
                .id(envelope.requestId())
                .requestedBy(actorId)
                .studentId(envelope.studentId())
                .requestType(F04_REQUEST_TYPE)
                .status(AiRunStatus.QUEUED)
                .correlationId(envelope.correlationId())
                .purpose("BUSINESS_ASSISTANCE")
                .inputPayload(inputPayloadJson)
                .queuedAt(Instant.now(clock))
                .build();

        AiRun savedRun = runRepository.create(run);

        // Save context snapshot in ai_context_snapshots
        String watermark = envelope.context().compositeWatermark();
        String contextHash = computeSha256(watermark != null ? watermark : envelope.requestId().toString());

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(),
                savedRun.id(),
                "v1.0",
                inputPayloadJson,
                "{}",
                "{}",
                "{}",
                contextHash,
                Instant.now(clock)
        );
        provenanceRepository.saveSnapshot(snapshot);

        return savedRun;
    }

    private String computeSha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return UUID.randomUUID().toString().replace("-", "");
        }
    }
}
