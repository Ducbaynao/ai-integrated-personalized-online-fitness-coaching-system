package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.AcceptAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TypedChange;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.RejectAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutAdjustmentUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F03AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F03AiServiceClient.F03ServiceRequest;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F03SourceSnapshot;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PrescriptionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.SessionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionDetail;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AiWorkoutAdjustmentService implements AiWorkoutAdjustmentUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiWorkoutAdjustmentService.class);

    private final AiRunRepository aiRunRepository;
    private final AiRecommendationRepository recommendationRepository;
    private final F03AiServiceClient aiServiceClient;
    private final F03ContextBuilder contextBuilder;
    private final F03RuntimeOutputValidator outputValidator;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CurrentCoachingContextQuery coachingContextQuery;
    private final UserAccountStatusQuery accountStatusQuery;
    private final WorkoutPlanCommandUseCase workoutPlanCommandUseCase;
    private final WorkoutPlanQueryUseCase workoutPlanQueryUseCase;
    private final ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;

    public AiWorkoutAdjustmentService(
            AiRunRepository aiRunRepository,
            AiRecommendationRepository recommendationRepository,
            F03AiServiceClient aiServiceClient,
            F03ContextBuilder contextBuilder,
            F03RuntimeOutputValidator outputValidator,
            AuditService auditService,
            ObjectMapper objectMapper,
            CurrentCoachingContextQuery coachingContextQuery,
            UserAccountStatusQuery accountStatusQuery,
            WorkoutPlanCommandUseCase workoutPlanCommandUseCase,
            WorkoutPlanQueryUseCase workoutPlanQueryUseCase,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider
    ) {
        this.aiRunRepository = aiRunRepository;
        this.recommendationRepository = recommendationRepository;
        this.aiServiceClient = aiServiceClient;
        this.contextBuilder = contextBuilder;
        this.outputValidator = outputValidator;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.coachingContextQuery = coachingContextQuery;
        this.accountStatusQuery = accountStatusQuery;
        this.workoutPlanCommandUseCase = workoutPlanCommandUseCase;
        this.workoutPlanQueryUseCase = workoutPlanQueryUseCase;
        this.scheduleCalendarProvider = scheduleCalendarProvider;
    }

    @Override
    public WorkoutAdjustmentApiResponse generateAdjustment(UUID actorId, WorkoutAdjustmentApiRequest request) {
        if (actorId == null) {
            throw new IllegalArgumentException("actorId is required.");
        }
        if (request == null) {
            throw new IllegalArgumentException("WorkoutAdjustmentApiRequest is required.");
        }

        // 0. Verify Actor Account Status
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        UUID studentId = request.studentId() != null ? request.studentId() : actorId;
        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();

        // 1. Build Context Envelope & Capture Source Snapshot BEFORE provider call
        F03ContextBuilder.ContextEnvelopeResult envelopeResult =
                contextBuilder.buildContextEnvelope(studentId, actorId, request);
        F03ServiceRequest serviceRequest = envelopeResult.request();
        F03SourceSnapshot sourceSnapshot = envelopeResult.sourceSnapshot();

        String sourceSnapshotJson;
        try {
            sourceSnapshotJson = objectMapper.writeValueAsString(sourceSnapshot);
        } catch (Exception e) {
            log.error("Failed to serialize F03 source snapshot for student {}: {}", studentId, e.getMessage());
            throw new IllegalStateException("Failed to serialize source snapshot: " + e.getMessage(), e);
        }

        // Record Initial AI Run
        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .studentId(studentId)
                .coachingPeriodId(sourceSnapshot.coachingPeriodId())
                .requestType("F03_WORKOUT_ADJUSTMENT_PROPOSAL")
                .inputPayload(sourceSnapshotJson)
                .status(AiRunStatus.QUEUED)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(now)
                .createdAt(now)
                .build();
        aiRunRepository.create(run);

        // Transition to RUNNING
        aiRunRepository.transitionStatus(runId, AiRunStatus.QUEUED, AiRunStatus.RUNNING, now, null);

        // 2. Invoke AI Service
        String rawJson;
        Instant startCall = Instant.now();
        try {
            rawJson = aiServiceClient.generateAdjustmentJson(serviceRequest);
        } catch (Exception e) {
            Instant failedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, failedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "PROVIDER_ERROR", e.getMessage(), null, null, latency, failedAt);
            throw e;
        }

        // 3. Strict Runtime Output Validation
        F03RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rawJson, sourceSnapshot);
        if (!validation.isValid()) {
            Instant rejectedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, rejectedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.REJECTED_BY_VALIDATOR, null, "VALIDATION_FAILED",
                    validation.failureReason(), null, null, latency, rejectedAt);
            throw new AiOutputValidationException("F03 adjustment proposal output validation failed: " + validation.failureReason());
        }

        // 4. Revalidate Actor Authorization Post-Provider Call (Cutoff check before delivery)
        AccountStatus postAccountStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (postAccountStatus != AccountStatus.ACTIVE) {
            Instant revokedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, revokedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "AUTHORITY_REVOKED",
                    "Actor account is no longer active", null, null, latency, revokedAt);
            throw new AiRunAccessDeniedException("Actor account is no longer active; output cannot be delivered.");
        }

        var postCoachingOpt = coachingContextQuery.findEffectiveContext(studentId, false);
        if ("HUMAN_COACH".equals(sourceSnapshot.coachingMode())) {
            if (postCoachingOpt.isEmpty()
                    || !"HUMAN_COACH".equals(postCoachingOpt.get().mode() != null ? postCoachingOpt.get().mode().name() : null)
                    || !actorId.equals(postCoachingOpt.get().trainerId())
                    || (sourceSnapshot.coachingPeriodId() != null && !sourceSnapshot.coachingPeriodId().equals(postCoachingOpt.get().periodId()))) {
                Instant revokedAt = Instant.now();
                int latency = (int) ChronoUnit.MILLIS.between(startCall, revokedAt);
                aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "AUTHORITY_REVOKED",
                        "Trainer coaching authority revoked or coaching period changed during generation",
                        null, null, latency, revokedAt);
                throw new AiRunAccessDeniedException("Trainer coaching authority revoked or coaching period changed during generation.");
            }
        } else if ("SELF_DIRECTED".equals(sourceSnapshot.coachingMode())) {
            if (postCoachingOpt.isPresent()
                    && "HUMAN_COACH".equals(postCoachingOpt.get().mode() != null ? postCoachingOpt.get().mode().name() : null)) {
                Instant revokedAt = Instant.now();
                int latency = (int) ChronoUnit.MILLIS.between(startCall, revokedAt);
                aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "AUTHORITY_REVOKED",
                        "Coaching mode transitioned to HUMAN_COACH during generation",
                        null, null, latency, revokedAt);
                throw new AiRunAccessDeniedException("Coaching mode transitioned to HUMAN_COACH during generation.");
            }
        }

        Instant completedAt = Instant.now();
        int latency = (int) ChronoUnit.MILLIS.between(startCall, completedAt);
        aiRunRepository.completeRun(runId, AiRunStatus.SUCCEEDED, rawJson, null, null, null, null, latency, completedAt);

        // 5. Parse Title and Explanation
        String title = "Workout Adjustment Proposal";
        String explanation = "Targeted workout adjustment proposal based on training feedback and schedule.";
        JsonNode parsedPayload = null;
        try {
            parsedPayload = objectMapper.readTree(rawJson);
            if (parsedPayload.has("summary") && !parsedPayload.get("summary").asText().isBlank()) {
                explanation = parsedPayload.get("summary").asText();
            }
            if (parsedPayload.has("changes") && parsedPayload.get("changes").isArray() && !parsedPayload.get("changes").isEmpty()) {
                JsonNode firstChange = parsedPayload.get("changes").get(0);
                String cType = firstChange.path("changeType").asText("");
                if (!cType.isBlank()) {
                    title = switch (cType) {
                        case "EXERCISE_SUBSTITUTION" -> "Exercise Substitution Proposal";
                        case "LOAD_ADJUSTMENT" -> "Load Adjustment Proposal";
                        case "VOLUME_ADJUSTMENT" -> "Volume Adjustment Proposal";
                        default -> title;
                    };
                }
            }
        } catch (Exception ignored) {}

        AiDecisionAuthority authority = "HUMAN_COACH".equals(sourceSnapshot.coachingMode())
                ? AiDecisionAuthority.TRAINER
                : AiDecisionAuthority.STUDENT;

        Instant expiresAt = completedAt.plus(24, ChronoUnit.HOURS);
        UUID targetEntityId = sourceSnapshot.scope() == TargetScope.ONE_MATERIALIZED_OCCURRENCE
                ? sourceSnapshot.plannedWorkoutId()
                : sourceSnapshot.planId();
        String targetEntityType = sourceSnapshot.scope() == TargetScope.ONE_MATERIALIZED_OCCURRENCE
                ? "WORKOUT_OCCURRENCE"
                : "WORKOUT_PLAN";

        AiRecommendation recommendation = AiRecommendation.builder()
                .id(UUID.randomUUID())
                .aiRunId(runId)
                .studentId(studentId)
                .coachingPeriodId(sourceSnapshot.coachingPeriodId())
                .recommendationType("WORKOUT_ADJUSTMENT_PROPOSAL")
                .title(title)
                .explanation(explanation)
                .structuredPayload(rawJson)
                .originalPayload(rawJson)
                .revision(1)
                .isCustomized(false)
                .sourceSnapshot(sourceSnapshotJson)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(authority)
                .targetEntityType(targetEntityType)
                .targetEntityId(targetEntityId)
                .expiresAt(expiresAt)
                .createdAt(completedAt)
                .updatedAt(completedAt)
                .build();

        AiRecommendation saved = recommendationRepository.save(recommendation);

        // Record Immutable Audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(authority.name())
                .action("AI_RECOMMENDATION_CREATED")
                .targetType("AI_RECOMMENDATION")
                .targetId(saved.id())
                .beforeDataJson("{}")
                .afterDataJson(rawJson)
                .occurredAt(completedAt)
                .build());

        return new WorkoutAdjustmentApiResponse(
                saved.id(),
                runId,
                saved.status(),
                saved.decisionAuthority(),
                saved.title(),
                saved.explanation(),
                saved.expiresAt(),
                saved.createdAt(),
                parsedPayload
        );
    }

    @Override
    public AiRecommendation getAdjustment(UUID actorId, UUID recommendationId) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authenticated actor ID is required.");
        }
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        AiRecommendation rec = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new AiRunNotFoundException("Recommendation not found: " + recommendationId));

        if (!"WORKOUT_ADJUSTMENT_PROPOSAL".equals(rec.recommendationType())) {
            throw new AiRunNotFoundException("Recommendation is not a workout adjustment proposal: " + recommendationId);
        }

        if (!actorId.equals(rec.studentId())) {
            var ctx = coachingContextQuery.findEffectiveContext(rec.studentId(), false).orElse(null);
            if (ctx == null || !actorId.equals(ctx.trainerId())) {
                throw new AiRunAccessDeniedException("Access denied to view this recommendation.");
            }
        }

        return rec;
    }

    @Override
    @Transactional
    public AcceptAdjustmentApiResponse acceptAdjustment(UUID actorId, UUID recommendationId, AcceptAdjustmentApiRequest request) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authenticated actor ID is required.");
        }
        if (recommendationId == null) {
            throw new IllegalArgumentException("recommendationId is required.");
        }
        AcceptAdjustmentApiRequest req = request != null ? request : new AcceptAdjustmentApiRequest(null, null, null, null);

        // 1. Verify Actor Account Status
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        // 2. Lock Recommendation row for update
        AiRecommendation rec = recommendationRepository.findByIdForUpdate(recommendationId)
                .orElseThrow(() -> new AiRunNotFoundException("Recommendation not found: " + recommendationId));

        if (!"WORKOUT_ADJUSTMENT_PROPOSAL".equals(rec.recommendationType())) {
            throw new AiRunLifecycleConflictException("Recommendation is not a workout adjustment proposal: " + rec.recommendationType());
        }

        // 3. Verify Coaching Context and Decision Authority
        var currentContext = coachingContextQuery.findEffectiveContext(rec.studentId(), false).orElse(null);
        if (currentContext == null) {
            throw new AiRunAccessDeniedException("Active coaching context required to process recommendation.");
        }

        if (rec.coachingPeriodId() != null && !rec.coachingPeriodId().equals(currentContext.periodId())) {
            throw new AiRunLifecycleConflictException("Coaching period has transitioned since recommendation was generated.");
        }

        if (currentContext.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH) {
            if (rec.decisionAuthority() != AiDecisionAuthority.TRAINER) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to HUMAN_COACH; student decision authority is invalid.");
            }
            if (currentContext.trainerId() == null || !actorId.equals(currentContext.trainerId())) {
                throw new AiRunAccessDeniedException("Only the currently assigned coach has decision authority to accept this recommendation.");
            }
        } else {
            // SELF_DIRECTED
            if (rec.decisionAuthority() != AiDecisionAuthority.STUDENT) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to SELF_DIRECTED; trainer decision authority is invalid.");
            }
            if (!actorId.equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Only the student has decision authority to accept this recommendation.");
            }
        }

        // 4. Check Idempotent Replay
        Optional<AiRecommendationApplication> existingApp = recommendationRepository.findApplicationByRecommendationId(rec.id());
        if (existingApp.isPresent()) {
            AiRecommendationApplication app = existingApp.get();
            try {
                JsonNode appPayloadNode = objectMapper.readTree(app.applicationPayload());
                String savedKey = appPayloadNode.path("commandKey").asText();
                String savedActor = appPayloadNode.path("actorId").asText();

                if (req.commandKey() != null && req.commandKey().equals(savedKey)) {
                    boolean actorMatches = actorId.toString().equals(savedActor);
                    int savedRev = appPayloadNode.has("appliedRevision") ? appPayloadNode.path("appliedRevision").asInt() : 1;
                    boolean revMatches = (req.expectedRevision() != null)
                            ? (req.expectedRevision() == savedRev)
                            : (savedRev == 1);
                    Long savedTargetVer = appPayloadNode.hasNonNull("expectedTargetVersion")
                            ? appPayloadNode.path("expectedTargetVersion").asLong()
                            : null;
                    boolean targetVerMatches = java.util.Objects.equals(req.expectedTargetVersion(), savedTargetVer);
                    String savedNote = appPayloadNode.path("decisionNote").asText("");
                    String reqNote = req.decisionNote() != null ? req.decisionNote() : "";
                    boolean noteMatches = reqNote.equals(savedNote);
                    if (actorMatches && revMatches && targetVerMatches && noteMatches) {
                        log.info("Replaying identical AcceptAdjustmentCommand for key: {}", req.commandKey());
                        return new AcceptAdjustmentApiResponse(
                                rec.id(),
                                rec.status(),
                                app.appliedBy(),
                                app.resultingEntityType(),
                                app.resultingEntityId(),
                                app.appliedAt(),
                                true
                        );
                    } else {
                        throw new AiRunLifecycleConflictException("Idempotency conflict: commandKey matches a previous command with differing parameters.");
                    }
                }
            } catch (AiRunLifecycleConflictException e) {
                throw e;
            } catch (Exception ignored) {}
            throw new AiRunLifecycleConflictException("Recommendation has already been applied.");
        }

        // 5. Status, Expiration, and Revision Guards
        if (rec.status() != AiRecommendationStatus.PENDING) {
            throw new AiRunLifecycleConflictException("Recommendation is in status " + rec.status() + " and cannot be accepted.");
        }

        Instant now = Instant.now();
        if (rec.isExpired(now)) {
            throw new AiRunLifecycleConflictException("Recommendation has expired and cannot be accepted.");
        }

        if (req.expectedRevision() != null) {
            if (rec.revision() != req.expectedRevision()) {
                throw new AiRunLifecycleConflictException("Revision conflict: expected revision "
                        + req.expectedRevision() + " but current is " + rec.revision());
            }
        } else if (rec.revision() > 1) {
            throw new AiRunLifecycleConflictException("Revision conflict: draft has been modified to revision "
                    + rec.revision() + "; expectedRevision is required when accepting.");
        }

        // 6. Parse and Revalidate Source Snapshot and Payload
        F03SourceSnapshot sourceSnapshot;
        try {
            sourceSnapshot = objectMapper.readValue(rec.sourceSnapshot(), F03SourceSnapshot.class);
        } catch (Exception e) {
            throw new AiRunLifecycleConflictException("Source snapshot is malformed and cannot be verified: " + e.getMessage());
        }

        F03RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rec.structuredPayload(), sourceSnapshot);
        if (!validation.isValid()) {
            throw new AiOutputValidationException("Recommendation cannot be applied because payload is no longer valid: " + validation.failureReason());
        }

        F03WorkoutAdjustmentPayload payload;
        try {
            payload = objectMapper.readValue(rec.structuredPayload(), F03WorkoutAdjustmentPayload.class);
        } catch (Exception e) {
            throw new AiOutputValidationException("Failed to parse structured payload: " + e.getMessage());
        }

        String commandKey = req.commandKey() != null && !req.commandKey().isBlank()
                ? req.commandKey()
                : "accept:adj:" + rec.id();

        String resultingEntityType;
        UUID resultingEntityId;
        String action;

        // 7. Route and Execute Domain Commands
        if (sourceSnapshot.scope() == TargetScope.ONE_MATERIALIZED_OCCURRENCE) {
            UUID plannedWorkoutId = sourceSnapshot.plannedWorkoutId();
            if (plannedWorkoutId == null) {
                throw new AiRunLifecycleConflictException("Missing plannedWorkoutId in source snapshot.");
            }

            WorkoutScheduleCalendarQuery calendarQuery = scheduleCalendarProvider.getIfAvailable();
            if (calendarQuery == null) {
                throw new AiProviderException("Workout schedule calendar query service is unavailable.");
            }

            WorkoutScheduleCalendarQuery.Occurrence occurrence;
            try {
                occurrence = calendarQuery.detail(actorId, plannedWorkoutId);
            } catch (Exception e) {
                throw new AiRunLifecycleConflictException("Failed to locate planned workout occurrence: " + plannedWorkoutId + " (" + e.getMessage() + ")");
            }
            if (occurrence == null) {
                throw new AiRunLifecycleConflictException("Planned workout occurrence no longer exists: " + plannedWorkoutId);
            }
            if (!"SCHEDULED".equalsIgnoreCase(occurrence.status())) {
                throw new AiRunLifecycleConflictException("Cannot adjust occurrence with status " + occurrence.status() + " (only SCHEDULED occurrences can be adjusted).");
            }

            if (req.expectedTargetVersion() != null && occurrence.occurrenceVersion() != req.expectedTargetVersion()) {
                throw new AiRunLifecycleConflictException("Planned workout occurrence version conflict: expected version "
                        + req.expectedTargetVersion() + " but current is " + occurrence.occurrenceVersion());
            } else if (req.expectedTargetVersion() == null && sourceSnapshot.occurrenceVersion() != null
                    && occurrence.occurrenceVersion() != sourceSnapshot.occurrenceVersion()) {
                throw new AiRunLifecycleConflictException("Planned workout occurrence version conflict: occurrence version changed from "
                        + sourceSnapshot.occurrenceVersion() + " to " + occurrence.occurrenceVersion());
            }

            long currentOccVersion = occurrence.occurrenceVersion();
            // Multi-action atomicity: A single adjustment proposal (such as VOLUME_ADJUSTMENT modifying both sets and reps)
            // may execute multiple sequential domain commands. All adjustments execute inside @Transactional;
            // any failure rolls back all adjustments atomically without partial persistence.
            record OccurrenceSubAction(
                    WorkoutSessionAdjustment.Type type,
                    UUID replacementVariationId,
                    String beforeJson,
                    String afterJson,
                    String subKeySuffix
            ) {}

            for (int i = 0; i < payload.changes().size(); i++) {
                TypedChange change = payload.changes().get(i);
                List<OccurrenceSubAction> subActions = new ArrayList<>();

                switch (change.changeType()) {
                    case EXERCISE_SUBSTITUTION -> {
                        UUID replacementVariationId = UUID.fromString(change.after().path("replacementVariationId").asText());
                        subActions.add(new OccurrenceSubAction(
                                WorkoutSessionAdjustment.Type.EXERCISE_SWAP,
                                replacementVariationId,
                                "{}",
                                "{}",
                                "swap"
                        ));
                    }
                    case LOAD_ADJUSTMENT -> {
                        Map<String, Object> beforeMap = new HashMap<>();
                        if (change.before() != null && change.before().hasNonNull("targetLoad")) {
                            beforeMap.put("targetLoad", new BigDecimal(change.before().path("targetLoad").asText()));
                        }
                        try {
                            String beforeJson = objectMapper.writeValueAsString(beforeMap);
                            Map<String, Object> afterMap = new HashMap<>();
                            afterMap.put("targetLoad", new BigDecimal(change.after().path("targetLoad").asText()));
                            if (change.after().hasNonNull("loadUnitId")) {
                                afterMap.put("loadUnitId", change.after().path("loadUnitId").intValue());
                            }
                            String afterJson = objectMapper.writeValueAsString(afterMap);
                            subActions.add(new OccurrenceSubAction(
                                    WorkoutSessionAdjustment.Type.LOAD,
                                    null,
                                    beforeJson,
                                    afterJson,
                                    "load"
                            ));
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to serialize load adjustment json", e);
                        }
                    }
                    case VOLUME_ADJUSTMENT -> {
                        JsonNode aNode = change.after();
                        JsonNode bNode = change.before();
                        try {
                            boolean hasSetsDelta = aNode.hasNonNull("deltaSets") && aNode.path("deltaSets").asInt() != 0;
                            boolean hasSetsChange = hasSetsDelta
                                    || (aNode.hasNonNull("targetSets") && bNode != null && bNode.hasNonNull("targetSets")
                                        && aNode.path("targetSets").asInt() != bNode.path("targetSets").asInt());

                            boolean hasRepsDelta = (aNode.hasNonNull("deltaRepsMin") && aNode.path("deltaRepsMin").asInt() != 0)
                                    || (aNode.hasNonNull("deltaRepsMax") && aNode.path("deltaRepsMax").asInt() != 0);
                            boolean hasRepsChange = hasRepsDelta
                                    || (aNode.hasNonNull("targetRepsMin") && bNode != null && aNode.path("targetRepsMin").asInt() != bNode.path("targetRepsMin").asInt(-1))
                                    || (aNode.hasNonNull("targetRepsMax") && bNode != null && aNode.path("targetRepsMax").asInt() != bNode.path("targetRepsMax").asInt(-1));

                            boolean hasDurationDelta = aNode.hasNonNull("deltaDurationSeconds") && aNode.path("deltaDurationSeconds").asInt() != 0;
                            boolean hasDurationChange = hasDurationDelta
                                    || (aNode.hasNonNull("durationSeconds") && bNode != null && aNode.path("durationSeconds").asInt() != bNode.path("durationSeconds").asInt(-1));

                            if (!hasSetsChange && !hasRepsChange && !hasDurationChange) {
                                if (aNode.hasNonNull("targetRepsMin") || aNode.hasNonNull("targetRepsMax")) {
                                    hasRepsChange = true;
                                } else if (aNode.hasNonNull("durationSeconds")) {
                                    hasDurationChange = true;
                                } else {
                                    hasSetsChange = true;
                                }
                            }

                            // 1. SETS sub-action (if sets changed or specified)
                            if (hasSetsChange && aNode.hasNonNull("targetSets")) {
                                int bSets = bNode != null && bNode.hasNonNull("targetSets") ? bNode.path("targetSets").intValue() : 1;
                                String bSetsJson = objectMapper.writeValueAsString(Map.of("targetSets", bSets));
                                String aSetsJson = objectMapper.writeValueAsString(Map.of("targetSets", aNode.path("targetSets").intValue()));
                                subActions.add(new OccurrenceSubAction(
                                        WorkoutSessionAdjustment.Type.SETS,
                                        null,
                                        bSetsJson,
                                        aSetsJson,
                                        "sets"
                                ));
                            }

                            // 2. REPS or DURATION sub-action
                            if (hasRepsChange && (aNode.hasNonNull("targetRepsMin") || aNode.hasNonNull("targetRepsMax"))) {
                                Map<String, Object> bMap = new HashMap<>();
                                if (bNode != null && bNode.hasNonNull("targetRepsMin")) bMap.put("targetRepsMin", bNode.path("targetRepsMin").intValue());
                                if (bNode != null && bNode.hasNonNull("targetRepsMax")) bMap.put("targetRepsMax", bNode.path("targetRepsMax").intValue());
                                String bRepsJson = objectMapper.writeValueAsString(bMap);

                                Map<String, Object> aMap = new HashMap<>();
                                if (aNode.hasNonNull("targetRepsMin")) aMap.put("targetRepsMin", aNode.path("targetRepsMin").intValue());
                                if (aNode.hasNonNull("targetRepsMax")) aMap.put("targetRepsMax", aNode.path("targetRepsMax").intValue());
                                String aRepsJson = objectMapper.writeValueAsString(aMap);

                                subActions.add(new OccurrenceSubAction(
                                        WorkoutSessionAdjustment.Type.REPS,
                                        null,
                                        bRepsJson,
                                        aRepsJson,
                                        "reps"
                                ));
                            } else if (hasDurationChange && aNode.hasNonNull("durationSeconds")) {
                                int bDur = bNode != null && bNode.hasNonNull("durationSeconds") ? bNode.path("durationSeconds").intValue() : 0;
                                String bDurJson = objectMapper.writeValueAsString(Map.of("durationSeconds", bDur));
                                String aDurJson = objectMapper.writeValueAsString(Map.of("durationSeconds", aNode.path("durationSeconds").intValue()));
                                subActions.add(new OccurrenceSubAction(
                                        WorkoutSessionAdjustment.Type.DURATION,
                                        null,
                                        bDurJson,
                                        aDurJson,
                                        "duration"
                                ));
                            }

                            if (subActions.isEmpty()) {
                                int bSets = bNode != null && bNode.hasNonNull("targetSets") ? bNode.path("targetSets").intValue() : 1;
                                int aSets = aNode.hasNonNull("targetSets") ? aNode.path("targetSets").intValue() : 1;
                                String bSetsJson = objectMapper.writeValueAsString(Map.of("targetSets", bSets));
                                String aSetsJson = objectMapper.writeValueAsString(Map.of("targetSets", aSets));
                                subActions.add(new OccurrenceSubAction(
                                        WorkoutSessionAdjustment.Type.SETS,
                                        null,
                                        bSetsJson,
                                        aSetsJson,
                                        "sets"
                                ));
                            }
                        } catch (Exception e) {
                            throw new IllegalStateException("Failed to serialize volume adjustment json", e);
                        }
                    }
                    default -> throw new IllegalStateException("Unexpected change type: " + change.changeType());
                }

                String reason = change.reason() != null && !change.reason().isBlank()
                        ? change.reason()
                        : (rec.explanation() != null ? rec.explanation() : "AI Workout Adjustment");

                for (OccurrenceSubAction subAction : subActions) {
                    String subCommandKey = commandKey + ":adj:" + change.changeId() + ":" + subAction.subKeySuffix();
                    var occResult = workoutPlanCommandUseCase.adjustOccurrence(
                            new WorkoutPlanCommandUseCase.AdjustOccurrenceCommand(
                                    plannedWorkoutId,
                                    actorId,
                                    currentOccVersion,
                                    subCommandKey,
                                    subAction.type(),
                                    sourceSnapshot.plannedSessionExerciseId(),
                                    subAction.replacementVariationId(),
                                    subAction.beforeJson(),
                                    subAction.afterJson(),
                                    reason
                            )
                    );
                    currentOccVersion = occResult.occurrenceVersion();
                }
            }

            resultingEntityType = "WORKOUT_OCCURRENCE";
            resultingEntityId = plannedWorkoutId;
            action = "ADJUSTED_OCCURRENCE";

        } else {
            // TEMPLATE_OR_FUTURE_OCCURRENCES
            UUID planId = sourceSnapshot.planId();
            if (planId == null) {
                throw new AiRunLifecycleConflictException("Missing planId in source snapshot.");
            }

            Optional<PlanDetail> activePlanOpt = workoutPlanQueryUseCase.findActive(actorId, rec.studentId());
            if (activePlanOpt.isEmpty()) {
                throw new AiRunLifecycleConflictException("Student has no active workout plan.");
            }
            PlanDetail activePlan = activePlanOpt.get();
            if (!activePlan.plan().id().equals(planId)) {
                throw new AiRunLifecycleConflictException("Active plan has changed since proposal was generated (was "
                        + planId + ", now " + activePlan.plan().id() + ")");
            }

            if (req.expectedTargetVersion() != null && activePlan.plan().aggregateVersion() != req.expectedTargetVersion()) {
                throw new AiRunLifecycleConflictException("Workout plan aggregate version conflict: expected version "
                        + req.expectedTargetVersion() + " but current is " + activePlan.plan().aggregateVersion());
            } else if (req.expectedTargetVersion() == null && sourceSnapshot.planAggregateVersion() != null
                    && activePlan.plan().aggregateVersion() != sourceSnapshot.planAggregateVersion()) {
                throw new AiRunLifecycleConflictException("Workout plan aggregate version conflict: aggregate version changed from "
                        + sourceSnapshot.planAggregateVersion() + " to " + activePlan.plan().aggregateVersion());
            }

            VersionDetail versionDetail = workoutPlanQueryUseCase.version(actorId, planId, activePlan.currentVersion().id());
            UUID targetRxId = sourceSnapshot.exercisePrescriptionId();

            List<WorkoutSessionTemplate> sessionTemplates = new ArrayList<>();
            for (SessionView session : versionDetail.sessions()) {
                List<ExercisePrescription> rxTemplates = new ArrayList<>();
                for (PrescriptionView rx : session.prescriptions()) {
                    if (rx.id().equals(targetRxId)) {
                        UUID newVariationId = rx.exerciseVariationId();
                        Integer newSets = rx.targetSets();
                        Integer newRepsMin = rx.targetRepsMin();
                        Integer newRepsMax = rx.targetRepsMax();
                        BigDecimal newLoad = rx.targetLoad();
                        Integer newDuration = rx.durationSeconds();

                        for (TypedChange change : payload.changes()) {
                            switch (change.changeType()) {
                                case EXERCISE_SUBSTITUTION -> {
                                    newVariationId = UUID.fromString(change.after().path("replacementVariationId").asText());
                                }
                                case LOAD_ADJUSTMENT -> {
                                    if (change.after().hasNonNull("targetLoad")) {
                                        newLoad = new BigDecimal(change.after().path("targetLoad").asText());
                                    }
                                }
                                case VOLUME_ADJUSTMENT -> {
                                    JsonNode aNode = change.after();
                                    if (aNode.hasNonNull("targetSets")) newSets = aNode.path("targetSets").intValue();
                                    if (aNode.hasNonNull("targetRepsMin")) newRepsMin = aNode.path("targetRepsMin").intValue();
                                    if (aNode.hasNonNull("targetRepsMax")) newRepsMax = aNode.path("targetRepsMax").intValue();
                                    if (aNode.hasNonNull("durationSeconds")) newDuration = aNode.path("durationSeconds").intValue();
                                }
                            }
                        }

                        rxTemplates.add(new ExercisePrescription(
                                rx.id(),
                                newVariationId,
                                rx.sequenceNumber(),
                                newSets,
                                newRepsMin,
                                newRepsMax,
                                newLoad,
                                rx.restSeconds(),
                                newDuration,
                                rx.instructions()
                        ));
                    } else {
                        rxTemplates.add(new ExercisePrescription(
                                rx.id(),
                                rx.exerciseVariationId(),
                                rx.sequenceNumber(),
                                rx.targetSets(),
                                rx.targetRepsMin(),
                                rx.targetRepsMax(),
                                rx.targetLoad(),
                                rx.restSeconds(),
                                rx.durationSeconds(),
                                rx.instructions()
                        ));
                    }
                }

                sessionTemplates.add(new WorkoutSessionTemplate(
                        session.id(),
                        session.weekNumber(),
                        session.dayNumber(),
                        session.sequenceNumber(),
                        session.name(),
                        session.focus(),
                        session.estimatedDurationMinutes(),
                        session.notes(),
                        rxTemplates
                ));
            }

            var publishCmd = new WorkoutPlanCommandUseCase.PublishVersionCommand(
                    planId,
                    actorId,
                    activePlan.plan().aggregateVersion(),
                    commandKey + ":PUBLISH",
                    "AI_WORKOUT_ADJUSTMENT_APPLIED",
                    rec.explanation() != null && !rec.explanation().isBlank() ? rec.explanation() : "AI Workout Adjustment Update",
                    sessionTemplates
            );
            var cmdResult = workoutPlanCommandUseCase.publishVersion(publishCmd);

            resultingEntityType = "WORKOUT_PLAN";
            resultingEntityId = cmdResult.planId();
            action = "PUBLISHED_NEW_PLAN_VERSION";
        }

        // 8. Update Recommendation Status
        recommendationRepository.updateDecision(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                actorId,
                now,
                req.decisionNote(),
                resultingEntityType,
                resultingEntityId
        );

        // 9. Persist Application Receipt
        String appPayload = "{}";
        try {
            Map<String, Object> pMap = new HashMap<>();
            pMap.put("commandKey", commandKey);
            pMap.put("actorId", actorId.toString());
            pMap.put("decisionNote", req.decisionNote() != null ? req.decisionNote() : "");
            pMap.put("resultingEntityType", resultingEntityType);
            pMap.put("resultingEntityId", resultingEntityId.toString());
            pMap.put("appliedRevision", rec.revision());
            pMap.put("action", action);
            pMap.put("scope", sourceSnapshot.scope().name());
            if (req.expectedTargetVersion() != null) {
                pMap.put("expectedTargetVersion", req.expectedTargetVersion());
            }
            appPayload = objectMapper.writeValueAsString(pMap);
        } catch (Exception ignored) {}

        AiRecommendationApplication application = new AiRecommendationApplication(
                UUID.randomUUID(),
                rec.id(),
                actorId,
                resultingEntityType,
                resultingEntityId,
                appPayload,
                now
        );
        recommendationRepository.saveApplication(application);

        // 10. Record Immutable Audit Event
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(rec.decisionAuthority().name())
                .action("AI_RECOMMENDATION_APPLIED")
                .targetType("AI_RECOMMENDATION")
                .targetId(rec.id())
                .beforeDataJson(rec.structuredPayload())
                .afterDataJson(appPayload)
                .occurredAt(now)
                .build());

        return new AcceptAdjustmentApiResponse(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                actorId,
                resultingEntityType,
                resultingEntityId,
                now,
                false
        );
    }

    @Override
    @Transactional
    public RejectAdjustmentApiResponse rejectAdjustment(UUID actorId, UUID recommendationId, RejectAdjustmentApiRequest request) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authenticated actor ID is required.");
        }
        if (recommendationId == null) {
            throw new IllegalArgumentException("recommendationId is required.");
        }
        RejectAdjustmentApiRequest req = request != null ? request : new RejectAdjustmentApiRequest(null);

        // 1. Verify Actor Account Status
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        // 2. Lock Recommendation row
        AiRecommendation rec = recommendationRepository.findByIdForUpdate(recommendationId)
                .orElseThrow(() -> new AiRunNotFoundException("Recommendation not found: " + recommendationId));

        if (!"WORKOUT_ADJUSTMENT_PROPOSAL".equals(rec.recommendationType())) {
            throw new AiRunLifecycleConflictException("Recommendation is not a workout adjustment proposal: " + rec.recommendationType());
        }

        if (rec.status() != AiRecommendationStatus.PENDING) {
            throw new AiRunLifecycleConflictException("Recommendation is in status " + rec.status() + " and cannot be rejected.");
        }

        Instant now = Instant.now();
        if (rec.isExpired(now)) {
            throw new AiRunLifecycleConflictException("Recommendation has expired and cannot be rejected.");
        }

        // 3. Verify Authority
        var currentContext = coachingContextQuery.findEffectiveContext(rec.studentId(), false).orElse(null);
        if (currentContext == null) {
            throw new AiRunAccessDeniedException("Active coaching context required to process recommendation.");
        }

        if (currentContext.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH) {
            if (rec.decisionAuthority() != AiDecisionAuthority.TRAINER) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to HUMAN_COACH; student decision authority is invalid.");
            }
            if (currentContext.trainerId() == null || !actorId.equals(currentContext.trainerId())) {
                throw new AiRunAccessDeniedException("Only the currently assigned coach has decision authority to reject this recommendation.");
            }
        } else {
            // SELF_DIRECTED
            if (rec.decisionAuthority() != AiDecisionAuthority.STUDENT) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to SELF_DIRECTED; trainer decision authority is invalid.");
            }
            if (!actorId.equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Only the student has decision authority to reject this recommendation.");
            }
        }

        // 4. Update Recommendation
        recommendationRepository.updateDecision(
                rec.id(),
                AiRecommendationStatus.REJECTED,
                actorId,
                now,
                req.reason(),
                rec.targetEntityType(),
                rec.targetEntityId()
        );

        // 5. Record Immutable Audit Event
        String auditAfterJson = "{}";
        try {
            auditAfterJson = objectMapper.writeValueAsString(Map.of(
                    "status", "REJECTED",
                    "reason", req.reason() != null ? req.reason() : ""
            ));
        } catch (Exception ignored) {}

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(actorId)
                .actorRole(rec.decisionAuthority().name())
                .action("AI_RECOMMENDATION_REJECTED")
                .targetType("AI_RECOMMENDATION")
                .targetId(rec.id())
                .beforeDataJson(rec.structuredPayload())
                .afterDataJson(auditAfterJson)
                .occurredAt(now)
                .build());

        return new RejectAdjustmentApiResponse(
                rec.id(),
                AiRecommendationStatus.REJECTED,
                actorId,
                req.reason(),
                now
        );
    }
}
