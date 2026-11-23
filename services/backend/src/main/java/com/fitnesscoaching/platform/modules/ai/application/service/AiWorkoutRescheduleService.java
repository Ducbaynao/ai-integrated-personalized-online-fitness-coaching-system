package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F05WorkoutReschedulePayload;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutRescheduleUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery.Occurrence;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F05AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F05SourceSnapshot;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.ChangeResult;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Reschedule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class AiWorkoutRescheduleService implements AiWorkoutRescheduleUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiWorkoutRescheduleService.class);

    private final AiRunRepository aiRunRepository;
    private final AiRecommendationRepository recommendationRepository;
    private final F05AiServiceClient aiServiceClient;
    private final F05ContextBuilder contextBuilder;
    private final F05RuntimeOutputValidator outputValidator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CurrentCoachingContextQuery coachingContextQuery;
    private final WorkoutScheduleChangeUseCase workoutScheduleChangeUseCase;
    private final ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;
    private final PlatformTransactionManager transactionManager;
    private final UserAccountStatusQuery accountStatusQuery;
    private final CoachingAuthorityQuery coachingAuthorityQuery;
    private final Clock clock;

    public AiWorkoutRescheduleService(
            AiRunRepository aiRunRepository,
            AiRecommendationRepository recommendationRepository,
            F05AiServiceClient aiServiceClient,
            F05ContextBuilder contextBuilder,
            F05RuntimeOutputValidator outputValidator,
            AuditService auditService,
            ObjectMapper objectMapper,
            CurrentCoachingContextQuery coachingContextQuery,
            WorkoutScheduleChangeUseCase workoutScheduleChangeUseCase,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            Clock clock
    ) {
        this(aiRunRepository, recommendationRepository, aiServiceClient, contextBuilder, outputValidator,
                auditService, objectMapper, coachingContextQuery, workoutScheduleChangeUseCase,
                scheduleCalendarProvider, clock, null, null, null);
    }

    @Autowired
    public AiWorkoutRescheduleService(
            AiRunRepository aiRunRepository,
            AiRecommendationRepository recommendationRepository,
            F05AiServiceClient aiServiceClient,
            F05ContextBuilder contextBuilder,
            F05RuntimeOutputValidator outputValidator,
            AuditService auditService,
            ObjectMapper objectMapper,
            CurrentCoachingContextQuery coachingContextQuery,
            WorkoutScheduleChangeUseCase workoutScheduleChangeUseCase,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            Clock clock,
            @Autowired(required = false) PlatformTransactionManager transactionManager,
            @Autowired(required = false) UserAccountStatusQuery accountStatusQuery,
            @Autowired(required = false) CoachingAuthorityQuery coachingAuthorityQuery
    ) {
        this.aiRunRepository = aiRunRepository;
        this.recommendationRepository = recommendationRepository;
        this.aiServiceClient = aiServiceClient;
        this.contextBuilder = contextBuilder;
        this.outputValidator = outputValidator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.coachingContextQuery = coachingContextQuery;
        this.workoutScheduleChangeUseCase = workoutScheduleChangeUseCase;
        this.scheduleCalendarProvider = scheduleCalendarProvider;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.transactionManager = transactionManager;
        this.accountStatusQuery = accountStatusQuery;
        this.coachingAuthorityQuery = coachingAuthorityQuery;
    }

    private record AcceptExecutionResult(AcceptRescheduleApiResponse response, boolean expired, String expiredMessage) {
        static AcceptExecutionResult success(AcceptRescheduleApiResponse res) {
            return new AcceptExecutionResult(res, false, null);
        }
        static AcceptExecutionResult expired(String msg) {
            return new AcceptExecutionResult(null, true, msg);
        }
    }

    private record RejectExecutionResult(RejectRescheduleApiResponse response, boolean expired, String expiredMessage) {
        static RejectExecutionResult success(RejectRescheduleApiResponse res) {
            return new RejectExecutionResult(res, false, null);
        }
        static RejectExecutionResult expired(String msg) {
            return new RejectExecutionResult(null, true, msg);
        }
    }

    @Override
    public WorkoutRescheduleApiResponse generateReschedule(UUID actorId, WorkoutRescheduleApiRequest request) {
        if (request == null || request.commandKey() == null || request.commandKey().isBlank()) {
            throw new AiRunValidationException("commandKey is required for workout rescheduling proposal generation.");
        }
        if (request.occurrenceId() == null) {
            throw new AiRunValidationException("occurrenceId is required.");
        }

        // 1. Build Context and Source Snapshot
        F05ContextBuilder.ContextEnvelopeResult ctx = contextBuilder.buildContextEnvelope(actorId, request);
        Instant now = Instant.now(clock);

        // 2. Create and Persist AI Run (Requires new transaction to ensure RUNNING state is committed immediately)
        AiRun run = AiRun.builder()
                .id(UUID.randomUUID())
                .requestedBy(actorId)
                .studentId(ctx.studentId())
                .coachingPeriodId(ctx.coachingPeriodId())
                .requestType("F05_WORKOUT_RESCHEDULE_PROPOSAL")
                .status(AiRunStatus.RUNNING)
                .inputPayload(request.reason() != null ? "{\"reason\":\"" + request.reason() + "\"}" : "{}")
                .purpose("BUSINESS_ASSISTANCE")
                .startedAt(now)
                .build();
        if (transactionManager != null) {
            TransactionTemplate txRequiresNew = new TransactionTemplate(transactionManager);
            txRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txRequiresNew.executeWithoutResult(status -> aiRunRepository.create(run));
        } else {
            aiRunRepository.create(run);
        }

        // 3. Invoke AI Service
        String rawPayload;
        try {
            rawPayload = aiServiceClient.generateRescheduleJson(ctx.serviceRequest());
        } catch (Exception e) {
            completeFailedRun(run.id(), "PROVIDER_ERROR", e.getMessage(), now);
            throw e;
        }

        // 4. Validate AI Output
        F05RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rawPayload, ctx.sourceSnapshot());
        if (!validation.isValid()) {
            completeRejectedRun(run.id(), validation.failureReason(), now);
            throw new AiOutputValidationException("F05 output validation failed: " + validation.failureReason());
        }

        // 4.5. Revalidate Actor Authorization Post-Provider Call (T04: In-flight revocation cutoff check)
        try {
            revalidateDeliveryAuthority(actorId, ctx.studentId(), ctx.coachingPeriodId(), ctx.decisionAuthority());
        } catch (AiRunAccessDeniedException e) {
            completeFailedRun(run.id(), "AUTHORITY_REVOKED", e.getMessage(), now);
            throw e;
        }

        // 5. Parse JSON Payload
        JsonNode payloadNode;
        try {
            payloadNode = objectMapper.readTree(rawPayload);
        } catch (Exception e) {
            completeFailedRun(run.id(), "JSON_PARSE_ERROR", e.getMessage(), now);
            throw new AiOutputValidationException("Invalid JSON response from AI provider: " + e.getMessage());
        }

        // 6, 7, 8. Persist AiRecommendation, Complete AI Run, and Record Audit Atomically
        Instant expiresAt = now.plus(30, ChronoUnit.MINUTES);
        String summary = payloadNode.path("summary").asText("Reschedule planned workout occurrence");
        String reasoning = payloadNode.path("reasoning").toString();

        String snapshotJson;
        try {
            snapshotJson = objectMapper.writeValueAsString(ctx.sourceSnapshot());
        } catch (Exception e) {
            snapshotJson = "{}";
        }

        AiRecommendation rec = AiRecommendation.builder()
                .id(UUID.randomUUID())
                .aiRunId(run.id())
                .studentId(ctx.studentId())
                .coachingPeriodId(ctx.coachingPeriodId())
                .recommendationType("WORKOUT_RESCHEDULE_PROPOSAL")
                .title(summary)
                .explanation(reasoning)
                .structuredPayload(rawPayload)
                .originalPayload(rawPayload)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(ctx.decisionAuthority())
                .targetEntityType("WORKOUT_OCCURRENCE")
                .targetEntityId(ctx.sourceSnapshot().occurrenceId())
                .expiresAt(expiresAt)
                .revision(1)
                .isCustomized(false)
                .lastModifiedBy(actorId)
                .sourceSnapshot(snapshotJson)
                .createdAt(now)
                .updatedAt(now)
                .build();

        final AiRecommendation recToSave = rec;
        try {
            if (transactionManager != null) {
                TransactionTemplate tx = new TransactionTemplate(transactionManager);
                tx.executeWithoutResult(status -> {
                    aiRunRepository.completeRun(run.id(), AiRunStatus.SUCCEEDED, rawPayload, null, null, null, null, null, now);
                    recommendationRepository.save(recToSave);
                    auditService.recordAudit(AuditRecord.builder()
                            .actorUserId(actorId)
                            .actorRole(ctx.decisionAuthority() == AiDecisionAuthority.TRAINER ? "TRAINER" : "STUDENT")
                            .action("AI_WORKOUT_RESCHEDULE_PROPOSED")
                            .targetType("AI_RECOMMENDATION")
                            .targetId(recToSave.id())
                            .beforeDataJson("{}")
                            .afterDataJson(rawPayload)
                            .occurredAt(now)
                            .build());
                });
            } else {
                aiRunRepository.completeRun(run.id(), AiRunStatus.SUCCEEDED, rawPayload, null, null, null, null, null, now);
                recommendationRepository.save(recToSave);
                auditService.recordAudit(AuditRecord.builder()
                        .actorUserId(actorId)
                        .actorRole(ctx.decisionAuthority() == AiDecisionAuthority.TRAINER ? "TRAINER" : "STUDENT")
                        .action("AI_WORKOUT_RESCHEDULE_PROPOSED")
                        .targetType("AI_RECOMMENDATION")
                        .targetId(recToSave.id())
                        .beforeDataJson("{}")
                        .afterDataJson(rawPayload)
                        .occurredAt(now)
                        .build());
            }
        } catch (Exception e) {
            completeFailedRun(run.id(), "PERSISTENCE_FAILED", "Atomic proposal persistence failed: " + e.getMessage(), now);
            throw e;
        }

        return new WorkoutRescheduleApiResponse(
                recToSave.id(),
                run.id(),
                recToSave.status(),
                recToSave.decisionAuthority(),
                recToSave.title(),
                recToSave.explanation(),
                recToSave.expiresAt(),
                recToSave.createdAt(),
                payloadNode
        );
    }

    private void completeFailedRun(UUID runId, String errorCode, String errorMessage, Instant now) {
        if (transactionManager != null) {
            TransactionTemplate txRequiresNew = new TransactionTemplate(transactionManager);
            txRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txRequiresNew.executeWithoutResult(status -> {
                aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, errorCode, errorMessage, null, null, null, now);
            });
        } else {
            aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, errorCode, errorMessage, null, null, null, now);
        }
    }

    private void completeRejectedRun(UUID runId, String failureReason, Instant now) {
        if (transactionManager != null) {
            TransactionTemplate txRequiresNew = new TransactionTemplate(transactionManager);
            txRequiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            txRequiresNew.executeWithoutResult(status -> {
                aiRunRepository.completeRun(runId, AiRunStatus.REJECTED_BY_VALIDATOR, null, "VALIDATION_FAILED", failureReason, null, null, null, now);
            });
        } else {
            aiRunRepository.completeRun(runId, AiRunStatus.REJECTED_BY_VALIDATOR, null, "VALIDATION_FAILED", failureReason, null, null, null, now);
        }
    }

    @Override
    public AiRecommendation getReschedule(UUID actorId, UUID recommendationId) {
        AiRecommendation rec = getValidRecommendation(actorId, recommendationId);
        if (rec.status() == AiRecommendationStatus.PENDING && rec.isExpired(Instant.now(clock))) {
            recommendationRepository.updateDecisionIfStatus(
                    rec.id(),
                    AiRecommendationStatus.PENDING,
                    AiRecommendationStatus.EXPIRED,
                    null,
                    Instant.now(clock),
                    "Recommendation expired",
                    rec.targetEntityType(),
                    rec.targetEntityId()
            );
            return recommendationRepository.findById(recommendationId).orElse(rec);
        }
        return rec;
    }

    private void revalidateDeliveryAuthority(
            UUID actorId,
            UUID studentId,
            UUID expectedPeriodId,
            AiDecisionAuthority decisionAuthority
    ) {
        // 1. Recheck Actor Account Status (T04)
        if (accountStatusQuery != null) {
            AccountStatus actorStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
            if (actorStatus != AccountStatus.ACTIVE) {
                throw new AiRunAccessDeniedException("Actor account is no longer active; output cannot be delivered.");
            }
        }

        // 2. Recheck Coaching Context (T04)
        var contextOpt = coachingContextQuery.findEffectiveContext(studentId, false);

        if (decisionAuthority == AiDecisionAuthority.TRAINER) {
            // Started in HUMAN_COACH mode
            if (contextOpt.isEmpty()
                    || !"HUMAN_COACH".equals(contextOpt.get().mode() != null ? contextOpt.get().mode().name() : null)
                    || (expectedPeriodId != null && !expectedPeriodId.equals(contextOpt.get().periodId()))) {
                throw new AiRunAccessDeniedException("Trainer coaching relationship or period was revoked or changed during generation.");
            }

            if (actorId.equals(studentId)) {
                // Student requested generation in HUMAN_COACH mode for coach review
                return;
            }

            if (!actorId.equals(contextOpt.get().trainerId())) {
                throw new AiRunAccessDeniedException("Trainer coaching relationship or period was revoked or changed during generation.");
            }

            // Recheck Granular Data Sharing Permissions via CoachingAuthorityQuery
            if (coachingAuthorityQuery != null) {
                // Must retain MANAGE authority to create/propose workout plan rescheduling
                var manageDecision = coachingAuthorityQuery.evaluateCurrent(new AuthorityRequest(
                        actorId, studentId, DataScope.WORKOUT_PLAN, DataAccessLevel.MANAGE
                ));
                if (!manageDecision.allowed()) {
                    throw new AiRunAccessDeniedException("Trainer MANAGE permission for WORKOUT_PLAN was revoked during generation: " + manageDecision.reason());
                }

                // Must retain VIEW authority to receive proposal output payload
                var viewDecision = coachingAuthorityQuery.evaluateCurrent(new AuthorityRequest(
                        actorId, studentId, DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW
                ));
                if (!viewDecision.allowed()) {
                    throw new AiRunAccessDeniedException("Trainer VIEW permission for WORKOUT_PLAN was revoked during generation: " + viewDecision.reason());
                }
            }
        } else {
            // Started in SELF_DIRECTED mode
            if (!actorId.equals(studentId)) {
                throw new AiRunAccessDeniedException("Only the student may receive rescheduling proposals in SELF_DIRECTED mode.");
            }
            if (contextOpt.isPresent()
                    && "HUMAN_COACH".equals(contextOpt.get().mode() != null ? contextOpt.get().mode().name() : null)) {
                throw new AiRunAccessDeniedException("Coaching mode transitioned to HUMAN_COACH during generation; unilateral reschedule output cannot be delivered.");
            }
        }
    }

    private AiRecommendation getValidRecommendation(UUID actorId, UUID recommendationId) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authentication required.");
        }
        if (accountStatusQuery != null) {
            AccountStatus actorStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
            if (actorStatus != AccountStatus.ACTIVE) {
                throw new AiRunAccessDeniedException("Actor account is not active.");
            }
        }

        AiRecommendation rec = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new AiRunNotFoundException("AI recommendation not found: " + recommendationId));

        if (!"WORKOUT_RESCHEDULE_PROPOSAL".equals(rec.recommendationType())) {
            throw new AiRunNotFoundException("Recommendation is not a workout reschedule proposal: " + recommendationId);
        }

        // Verify Actor Access
        if (actorId.equals(rec.studentId())) {
            return rec;
        }

        var coaching = coachingContextQuery.findEffectiveContext(rec.studentId(), false);
        if (coaching.isPresent() && "HUMAN_COACH".equals(coaching.get().mode() != null ? coaching.get().mode().name() : null)) {
            if (actorId.equals(coaching.get().trainerId())) {
                if (rec.coachingPeriodId() != null && !rec.coachingPeriodId().equals(coaching.get().periodId())) {
                    throw new AiRunAccessDeniedException("Coaching period has changed since proposal was generated.");
                }
                if (coachingAuthorityQuery == null || !coachingAuthorityQuery.evaluateCurrent(new AuthorityRequest(
                        actorId, rec.studentId(), DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW
                )).allowed()) {
                    throw new AiRunAccessDeniedException("Trainer VIEW permission for WORKOUT_PLAN is revoked.");
                }
                return rec;
            }
        }

        throw new AiRunAccessDeniedException("Access denied to workout reschedule recommendation: " + recommendationId);
    }

    @Override
    public AcceptRescheduleApiResponse acceptReschedule(UUID actorId, UUID recommendationId, AcceptRescheduleApiRequest req) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authentication required.");
        }
        if (req == null || req.commandKey() == null || req.commandKey().isBlank()) {
            throw new AiRunValidationException("commandKey is required to accept recommendation.");
        }

        // 1.5 Verify Actor Account is Active
        if (accountStatusQuery != null) {
            AccountStatus actorStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
            if (actorStatus != AccountStatus.ACTIVE) {
                throw new AiRunAccessDeniedException("Actor account is not active.");
            }
        }

        AcceptExecutionResult result;
        if (transactionManager != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            result = tx.execute(status -> doAcceptReschedule(actorId, recommendationId, req));
        } else {
            result = doAcceptReschedule(actorId, recommendationId, req);
        }

        if (result != null && result.expired()) {
            throw new AiRunLifecycleConflictException(result.expiredMessage());
        }

        return result != null ? result.response() : null;
    }

    private AcceptExecutionResult doAcceptReschedule(UUID actorId, UUID recommendationId, AcceptRescheduleApiRequest req) {
        // 1. Lock Recommendation
        AiRecommendation rec = recommendationRepository.findByIdForUpdate(recommendationId)
                .orElseThrow(() -> new AiRunNotFoundException("AI recommendation not found: " + recommendationId));

        if (!"WORKOUT_RESCHEDULE_PROPOSAL".equals(rec.recommendationType())) {
            throw new AiRunNotFoundException("Recommendation is not a workout reschedule proposal: " + recommendationId);
        }

        // 2. Check Decision Authority based on Current Coaching Mode (T02, T03)
        var contextOpt = coachingContextQuery.findEffectiveContext(rec.studentId(), false);
        UUID currentPeriodId = contextOpt.map(CurrentCoachingContext::periodId).orElse(null);
        if (rec.coachingPeriodId() != null && !Objects.equals(rec.coachingPeriodId(), currentPeriodId)) {
            throw new AiRunLifecycleConflictException("Coaching period has changed since proposal was generated.");
        }

        String currentMode = contextOpt.isPresent() && contextOpt.get().mode() != null
                ? contextOpt.get().mode().name()
                : "SELF_DIRECTED";

        if ("HUMAN_COACH".equals(currentMode)) {
            // T02: In HUMAN_COACH mode, Student cannot unilaterally accept AI rescheduling.
            if (actorId.equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Student in HUMAN_COACH mode cannot unilaterally accept AI workout rescheduling. Trainer approval required.");
            }
            // T03: Unrelated trainer cannot accept
            if (contextOpt.isEmpty() || contextOpt.get().trainerId() == null || !actorId.equals(contextOpt.get().trainerId())) {
                throw new AiRunAccessDeniedException("Only the currently assigned coach has decision authority to accept this recommendation.");
            }
            if (coachingAuthorityQuery != null) {
                var decision = coachingAuthorityQuery.evaluateCurrent(new AuthorityRequest(
                        actorId, rec.studentId(), DataScope.WORKOUT_PLAN, DataAccessLevel.MANAGE
                ));
                if (!decision.allowed()) {
                    throw new AiRunAccessDeniedException("Trainer coaching or data sharing authority is no longer valid: " + decision.reason());
                }
            }
        } else {
            // SELF_DIRECTED: Student must be the actor
            if (!actorId.equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Only the student has decision authority to accept this recommendation in SELF_DIRECTED mode.");
            }
        }

        // 3. Idempotent Replay Check (T06)
        Optional<AiRecommendationApplication> existingApp = recommendationRepository.findApplicationByRecommendationId(rec.id());
        if (existingApp.isPresent()) {
            AiRecommendationApplication app = existingApp.get();
            try {
                JsonNode appPayloadNode = objectMapper.readTree(app.applicationPayload());
                String savedKey = appPayloadNode.path("commandKey").asText();
                String savedActor = appPayloadNode.path("actorId").asText();

                if (req.commandKey().equals(savedKey)) {
                    boolean actorMatches = actorId.toString().equals(savedActor);
                    int savedRev = appPayloadNode.has("appliedRevision") ? appPayloadNode.path("appliedRevision").asInt() : 1;
                    boolean revMatches = (req.expectedRevision() != null)
                            ? (req.expectedRevision() == savedRev)
                            : (savedRev == 1);
                    Long savedOccurrenceVer = appPayloadNode.hasNonNull("expectedOccurrenceVersion")
                            ? appPayloadNode.path("expectedOccurrenceVersion").asLong()
                            : null;
                    boolean occurrenceVerMatches = Objects.equals(req.expectedOccurrenceVersion(), savedOccurrenceVer);
                    String savedNote = appPayloadNode.path("decisionNote").asText("");
                    String reqNote = req.decisionNote() != null ? req.decisionNote() : "";
                    boolean noteMatches = reqNote.equals(savedNote);

                    String currentFingerprint = computePayloadFingerprint(
                            rec.id(),
                            rec.revision(),
                            actorId,
                            rec.structuredPayload(),
                            req.expectedOccurrenceVersion(),
                            req.decisionNote()
                    );
                    String savedFingerprint = appPayloadNode.path("payloadFingerprint").asText("");
                    boolean fingerprintMatches = savedFingerprint.isEmpty() || savedFingerprint.equals(currentFingerprint);

                    if (actorMatches && revMatches && occurrenceVerMatches && noteMatches && fingerprintMatches) {
                        log.info("Replaying identical AcceptRescheduleCommand for key: {}", req.commandKey());
                        ChangeResult changeResult = appPayloadNode.has("changeResult")
                                ? objectMapper.treeToValue(appPayloadNode.path("changeResult"), ChangeResult.class)
                                : null;
                        return AcceptExecutionResult.success(new AcceptRescheduleApiResponse(
                                rec.id(),
                                rec.status(),
                                app.appliedBy(),
                                app.resultingEntityType(),
                                app.resultingEntityId(),
                                app.appliedAt(),
                                true,
                                changeResult
                        ));
                    } else {
                        throw new AiRunLifecycleConflictException("Idempotency conflict: commandKey matches a previous command with differing parameters.");
                    }
                }
            } catch (AiRunLifecycleConflictException e) {
                throw e;
            } catch (Exception ignored) {}
            throw new AiRunLifecycleConflictException("Recommendation has already been applied.");
        }

        // 4. Status, Expiry, and Revision Guards (T23)
        if (rec.status() != AiRecommendationStatus.PENDING) {
            throw new AiRunLifecycleConflictException("Recommendation is in status " + rec.status() + " and cannot be accepted.");
        }

        Instant now = Instant.now(clock);
        if (rec.isExpired(now)) {
            recommendationRepository.updateDecision(
                    rec.id(),
                    AiRecommendationStatus.EXPIRED,
                    null,
                    now,
                    "Recommendation expired",
                    rec.targetEntityType(),
                    rec.targetEntityId()
            );
            return AcceptExecutionResult.expired("Recommendation has expired and cannot be accepted.");
        }

        if (req.expectedRevision() != null && rec.revision() != req.expectedRevision()) {
            throw new AiRunLifecycleConflictException("Revision conflict: expected revision "
                    + req.expectedRevision() + " but current is " + rec.revision());
        }

        // 5. Source Guard Check (T05)
        F05SourceSnapshot sourceSnapshot;
        try {
            sourceSnapshot = objectMapper.readValue(rec.sourceSnapshot(), F05SourceSnapshot.class);
        } catch (Exception e) {
            throw new AiRunLifecycleConflictException("Source snapshot is malformed and cannot be verified: " + e.getMessage());
        }

        // Coaching Period Guard: hard conflict if period changed or ended since proposal generation
        if (sourceSnapshot.coachingPeriodId() != null && !Objects.equals(currentPeriodId, sourceSnapshot.coachingPeriodId())) {
            throw new AiRunLifecycleConflictException("Coaching period has changed since proposal was generated.");
        }

        F05RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rec.structuredPayload(), sourceSnapshot);
        if (!validation.isValid()) {
            throw new AiOutputValidationException("Recommendation cannot be applied because payload is no longer valid: " + validation.failureReason());
        }

        WorkoutScheduleCalendarQuery calendarQuery = scheduleCalendarProvider.getIfAvailable();
        if (calendarQuery != null) {
            Occurrence liveOccurrence;
            try {
                liveOccurrence = calendarQuery.detail(actorId, sourceSnapshot.occurrenceId());
            } catch (Exception e) {
                throw new AiRunLifecycleConflictException("Planned workout occurrence could not be revalidated: " + e.getMessage());
            }
            if (liveOccurrence == null || !"SCHEDULED".equals(liveOccurrence.status())) {
                throw new AiRunLifecycleConflictException("Workout occurrence is no longer in SCHEDULED status.");
            }
            if (liveOccurrence.occurrenceVersion() != sourceSnapshot.occurrenceVersion()) {
                throw new AiRunLifecycleConflictException("Workout occurrence version conflict: expected version "
                        + sourceSnapshot.occurrenceVersion() + " but current is " + liveOccurrence.occurrenceVersion());
            }
            if (req.expectedOccurrenceVersion() != null && liveOccurrence.occurrenceVersion() != req.expectedOccurrenceVersion()) {
                throw new AiRunLifecycleConflictException("Workout occurrence version conflict: expected version "
                        + req.expectedOccurrenceVersion() + " but current is " + liveOccurrence.occurrenceVersion());
            }
            if (!Objects.equals(liveOccurrence.linkedAppointmentId(), sourceSnapshot.linkedAppointmentId())
                    || !Objects.equals(liveOccurrence.linkedAppointmentVersion(), sourceSnapshot.linkedAppointmentVersion())) {
                throw new AiRunLifecycleConflictException("Linked appointment has changed since proposal was generated.");
            }
        }

        // 6. Parse Reschedule Destination
        F05WorkoutReschedulePayload payload;
        try {
            payload = objectMapper.readValue(rec.structuredPayload(), F05WorkoutReschedulePayload.class);
        } catch (Exception e) {
            throw new AiOutputValidationException("Failed to parse structured reschedule payload: " + e.getMessage());
        }

        var destDto = payload.reschedule().destination();
        Destination destination = new Destination(
                LocalDateTime.parse(destDto.localStart()),
                LocalDateTime.parse(destDto.localEnd()),
                destDto.timezone(),
                destDto.startUtcOffset(),
                destDto.endUtcOffset()
        );

        // 7. Atomic Accept and Apply
        // Internal command key namespaced to ensure uniqueness
        String internalWorkoutKey = "ai-reschedule-" + rec.id() + "-" + req.commandKey();

        UUID expectedAppId = req.expectedAppointmentId() != null
                ? req.expectedAppointmentId()
                : sourceSnapshot.linkedAppointmentId();
        Long expectedAppVersion = req.expectedAppointmentVersion() != null
                ? req.expectedAppointmentVersion()
                : sourceSnapshot.linkedAppointmentVersion();

        ChangeResult changeResult = workoutScheduleChangeUseCase.reschedule(new Reschedule(
                sourceSnapshot.occurrenceId(),
                rec.studentId(),
                sourceSnapshot.occurrenceVersion(),
                destination,
                req.decisionNote() != null ? req.decisionNote() : rec.title(),
                internalWorkoutKey,
                expectedAppVersion,
                expectedAppId
        ));

        // 8. Update Recommendation Status to APPLIED
        recommendationRepository.updateDecision(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                actorId,
                now,
                req.decisionNote(),
                "WORKOUT_OCCURRENCE",
                changeResult.replacementOccurrenceId()
        );

        // 9. Save Recommendation Application
        String payloadFingerprint = computePayloadFingerprint(
                rec.id(),
                rec.revision(),
                actorId,
                rec.structuredPayload(),
                req.expectedOccurrenceVersion(),
                req.decisionNote()
        );

        Map<String, Object> appPayload = new LinkedHashMap<>();
        appPayload.put("commandKey", req.commandKey());
        appPayload.put("actorId", actorId.toString());
        appPayload.put("appliedRevision", rec.revision());
        appPayload.put("expectedOccurrenceVersion", req.expectedOccurrenceVersion());
        appPayload.put("decisionNote", req.decisionNote() != null ? req.decisionNote() : "");
        appPayload.put("payloadFingerprint", payloadFingerprint);
        appPayload.put("changeResult", changeResult);

        String appPayloadJson;
        try {
            appPayloadJson = objectMapper.writeValueAsString(appPayload);
        } catch (Exception e) {
            appPayloadJson = "{}";
        }

        AiRecommendationApplication application = new AiRecommendationApplication(
                UUID.randomUUID(),
                rec.id(),
                actorId,
                "WORKOUT_OCCURRENCE",
                changeResult.replacementOccurrenceId(),
                appPayloadJson,
                now
        );
        recommendationRepository.saveApplication(application);

        // 10. Record Audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(actorId.equals(rec.studentId()) ? "STUDENT" : "TRAINER")
                .action("AI_WORKOUT_RESCHEDULE_APPLIED")
                .targetType("WORKOUT_OCCURRENCE")
                .targetId(changeResult.replacementOccurrenceId())
                .beforeDataJson("{\"occurrenceId\":\"" + sourceSnapshot.occurrenceId() + "\"}")
                .afterDataJson(appPayloadJson)
                .occurredAt(now)
                .build());

        return AcceptExecutionResult.success(new AcceptRescheduleApiResponse(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                actorId,
                "WORKOUT_OCCURRENCE",
                changeResult.replacementOccurrenceId(),
                now,
                false,
                changeResult
        ));
    }

    @Override
    public RejectRescheduleApiResponse rejectReschedule(UUID actorId, UUID recommendationId, RejectRescheduleApiRequest req) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authentication required.");
        }
        if (accountStatusQuery != null) {
            AccountStatus actorStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
            if (actorStatus != AccountStatus.ACTIVE) {
                throw new AiRunAccessDeniedException("Actor account is not active.");
            }
        }

        RejectExecutionResult result;
        if (transactionManager != null) {
            TransactionTemplate tx = new TransactionTemplate(transactionManager);
            result = tx.execute(status -> doRejectReschedule(actorId, recommendationId, req));
        } else {
            result = doRejectReschedule(actorId, recommendationId, req);
        }

        if (result != null && result.expired()) {
            throw new AiRunLifecycleConflictException(result.expiredMessage());
        }

        return result != null ? result.response() : null;
    }

    private RejectExecutionResult doRejectReschedule(UUID actorId, UUID recommendationId, RejectRescheduleApiRequest req) {
        AiRecommendation rec = recommendationRepository.findByIdForUpdate(recommendationId)
                .orElseThrow(() -> new AiRunNotFoundException("AI recommendation not found: " + recommendationId));

        if (!"WORKOUT_RESCHEDULE_PROPOSAL".equals(rec.recommendationType())) {
            throw new AiRunNotFoundException("Recommendation is not a workout reschedule proposal: " + recommendationId);
        }

        // Verify Access & Authority
        var contextOpt = coachingContextQuery.findEffectiveContext(rec.studentId(), false);
        String currentMode = contextOpt.isPresent() && contextOpt.get().mode() != null
                ? contextOpt.get().mode().name()
                : "SELF_DIRECTED";

        if ("HUMAN_COACH".equals(currentMode)) {
            if (!actorId.equals(rec.studentId())) {
                if (contextOpt.isEmpty() || contextOpt.get().trainerId() == null || !actorId.equals(contextOpt.get().trainerId())) {
                    throw new AiRunAccessDeniedException("Only the student or their coach may reject this recommendation.");
                }
                if (coachingAuthorityQuery != null) {
                    var decision = coachingAuthorityQuery.evaluateCurrent(new AuthorityRequest(
                            actorId, rec.studentId(), DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW
                    ));
                    if (!decision.allowed()) {
                        throw new AiRunAccessDeniedException("Trainer coaching authority is no longer valid: " + decision.reason());
                    }
                }
            }
        } else {
            if (!actorId.equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Only the student may reject this recommendation in SELF_DIRECTED mode.");
            }
        }

        Instant now = Instant.now(clock);
        if (rec.isExpired(now)) {
            recommendationRepository.updateDecision(
                    rec.id(),
                    AiRecommendationStatus.EXPIRED,
                    null,
                    now,
                    "Recommendation expired",
                    rec.targetEntityType(),
                    rec.targetEntityId()
            );
            return RejectExecutionResult.expired("Recommendation has expired and cannot be rejected.");
        }

        if (rec.status() != AiRecommendationStatus.PENDING) {
            throw new AiRunLifecycleConflictException("Recommendation is in status " + rec.status() + " and cannot be rejected.");
        }

        String note = req != null ? req.decisionNote() : null;
        recommendationRepository.updateDecision(rec.id(), AiRecommendationStatus.REJECTED, actorId, now, note, rec.targetEntityType(), rec.targetEntityId());

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(actorId.equals(rec.studentId()) ? "STUDENT" : "TRAINER")
                .action("AI_WORKOUT_RESCHEDULE_REJECTED")
                .targetType("AI_RECOMMENDATION")
                .targetId(rec.id())
                .beforeDataJson("{}")
                .afterDataJson(note != null ? "{\"decisionNote\":\"" + note + "\"}" : "{}")
                .occurredAt(now)
                .build());

        return RejectExecutionResult.success(new RejectRescheduleApiResponse(rec.id(), AiRecommendationStatus.REJECTED, now));
    }

    private String computePayloadFingerprint(
            UUID recommendationId,
            int revision,
            UUID actorId,
            String structuredPayload,
            Long expectedOccurrenceVersion,
            String decisionNote
    ) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String combined = recommendationId + ":" + revision + ":" + actorId + ":"
                    + (expectedOccurrenceVersion != null ? expectedOccurrenceVersion : "") + ":"
                    + (decisionNote != null ? decisionNote : "") + ":"
                    + (structuredPayload != null ? structuredPayload : "");
            return HexFormat.of().formatHex(digest.digest(combined.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }
}
