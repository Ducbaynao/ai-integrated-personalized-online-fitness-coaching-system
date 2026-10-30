package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutProposalUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient.F02ServiceRequest;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F02SourceSnapshot;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.ActivateCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.CommandResult;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.CreateDraftCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.PublishVersionCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AiWorkoutProposalService implements AiWorkoutProposalUseCase {

    private static final Logger log = LoggerFactory.getLogger(AiWorkoutProposalService.class);

    private final AiRunRepository aiRunRepository;
    private final AiRecommendationRepository recommendationRepository;
    private final F02AiServiceClient aiServiceClient;
    private final F02ContextBuilder contextBuilder;
    private final F02RuntimeOutputValidator outputValidator;
    private final WorkoutPlanCommandUseCase workoutPlanCommandUseCase;
    private final WorkoutPlanQueryUseCase workoutPlanQueryUseCase;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CurrentCoachingContextQuery coachingContextQuery;
    private final UserAccountStatusQuery accountStatusQuery;
    private final ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider;

    public AiWorkoutProposalService(
            AiRunRepository aiRunRepository,
            AiRecommendationRepository recommendationRepository,
            F02AiServiceClient aiServiceClient,
            F02ContextBuilder contextBuilder,
            F02RuntimeOutputValidator outputValidator,
            WorkoutPlanCommandUseCase workoutPlanCommandUseCase,
            WorkoutPlanQueryUseCase workoutPlanQueryUseCase,
            AuditService auditService,
            ObjectMapper objectMapper,
            CurrentCoachingContextQuery coachingContextQuery,
            UserAccountStatusQuery accountStatusQuery,
            ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider
    ) {
        this.aiRunRepository = aiRunRepository;
        this.recommendationRepository = recommendationRepository;
        this.aiServiceClient = aiServiceClient;
        this.contextBuilder = contextBuilder;
        this.outputValidator = outputValidator;
        this.workoutPlanCommandUseCase = workoutPlanCommandUseCase;
        this.workoutPlanQueryUseCase = workoutPlanQueryUseCase;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.coachingContextQuery = coachingContextQuery;
        this.accountStatusQuery = accountStatusQuery;
        this.goalProvider = goalProvider;
    }

    @Override
    public WorkoutProposalResult generateProposal(GenerateProposalCommand command) {
        if (command == null || command.actorId() == null) {
            throw new IllegalArgumentException("Invalid proposal command: actorId is required.");
        }

        // 0. Verify Actor Account Status (B08: inactive/suspended accounts cannot request AI assistance)
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(command.actorId()).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        UUID studentId = command.studentId() != null ? command.studentId() : command.actorId();
        var coaching = contextBuilder.resolveCoachingContext(studentId);

        // Authority Check
        if ("SELF_DIRECTED".equals(coaching.mode())) {
            if (!command.actorId().equals(studentId)) {
                throw new AiRunAccessDeniedException("In SELF_DIRECTED mode, only the student may request a workout proposal.");
            }
        } else if ("HUMAN_COACH".equals(coaching.mode())) {
            if (coaching.trainerId() == null || !command.actorId().equals(coaching.trainerId())) {
                throw new AiRunAccessDeniedException("In HUMAN_COACH mode, only the assigned coach may request a workout proposal.");
            }
        }

        Instant now = Instant.now();
        UUID runId = UUID.randomUUID();

        // 1. Build Context Envelope & Capture Source Snapshot BEFORE calling provider
        F02ContextBuilder.ContextEnvelopeResult envelopeResult =
                contextBuilder.buildContextEnvelope(studentId, command.actorId(), command.preferences(), List.of());
        F02ServiceRequest request = envelopeResult.request();
        F02SourceSnapshot sourceSnapshot = envelopeResult.sourceSnapshot();

        String sourceSnapshotJson;
        try {
            sourceSnapshotJson = objectMapper.writeValueAsString(sourceSnapshot);
        } catch (Exception e) {
            log.error("Failed to serialize F02 source snapshot for student {}: {}", studentId, e.getMessage());
            throw new IllegalStateException("Failed to serialize source snapshot: " + e.getMessage(), e);
        }

        // Record Initial AI Run with source snapshot in input payload
        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(command.actorId())
                .studentId(studentId)
                .coachingPeriodId(coaching.periodId())
                .requestType("F02_WORKOUT_PLAN_PROPOSAL")
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
            rawJson = aiServiceClient.generateProposalJson(request);
        } catch (Exception e) {
            Instant failedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, failedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "PROVIDER_ERROR", e.getMessage(), null, null, latency, failedAt);
            throw e;
        }

        // 3. Strict Runtime Output Validation
        F02RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rawJson);
        if (!validation.isValid()) {
            Instant rejectedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, rejectedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.REJECTED_BY_VALIDATOR, null, "VALIDATION_FAILED",
                    validation.failureReason(), null, null, latency, rejectedAt);
            throw new AiOutputValidationException("F02 proposal output validation failed: " + validation.failureReason());
        }

        // 4. Revalidate Actor Authorization Post-Provider Call (T04: Cutoff check before delivery)
        AccountStatus postAccountStatus = accountStatusQuery.getAccountStatus(command.actorId()).orElse(null);
        if (postAccountStatus != AccountStatus.ACTIVE) {
            Instant revokedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, revokedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "AUTHORITY_REVOKED",
                    "Actor account is no longer active", null, null, latency, revokedAt);
            throw new AiRunAccessDeniedException("Actor account is no longer active; output cannot be delivered.");
        }

        var postCoachingOpt = coachingContextQuery.findEffectiveContext(studentId, false);
        if ("HUMAN_COACH".equals(coaching.mode())) {
            if (postCoachingOpt.isEmpty()
                    || !"HUMAN_COACH".equals(postCoachingOpt.get().mode() != null ? postCoachingOpt.get().mode().name() : null)
                    || !command.actorId().equals(postCoachingOpt.get().trainerId())
                    || (coaching.periodId() != null && !coaching.periodId().equals(postCoachingOpt.get().periodId()))) {
                Instant revokedAt = Instant.now();
                int latency = (int) ChronoUnit.MILLIS.between(startCall, revokedAt);
                aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "AUTHORITY_REVOKED",
                        "Trainer coaching authority revoked or coaching period changed during generation",
                        null, null, latency, revokedAt);
                throw new AiRunAccessDeniedException("Trainer coaching authority revoked or coaching period changed during generation.");
            }
        } else if ("SELF_DIRECTED".equals(coaching.mode())) {
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
        // Telemetry tokens are recorded as null/unknown when provider does not measure exact token count (no fake numbers)
        aiRunRepository.completeRun(runId, AiRunStatus.SUCCEEDED, rawJson, null, null, null, null, latency, completedAt);

        // 5. Create and Persist Recommendation in PENDING state
        String title = "1-Week Workout Plan Proposal";
        String explanation = "Personalized resistance training plan tailored to current goals and training schedule.";
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            if (root.has("plan") && root.get("plan").has("name")) {
                title = root.get("plan").get("name").asText(title);
            }
            if (root.has("summary")) {
                explanation = root.get("summary").asText(explanation);
            }
        } catch (Exception ignored) {}

        AiDecisionAuthority authority = "HUMAN_COACH".equals(coaching.mode())
                ? AiDecisionAuthority.TRAINER
                : AiDecisionAuthority.STUDENT;

        // Approved contract: F02 recommendation expires in 24 hours
        Instant expiresAt = completedAt.plus(24, ChronoUnit.HOURS);

        AiRecommendation recommendation = AiRecommendation.builder()
                .id(UUID.randomUUID())
                .aiRunId(runId)
                .studentId(studentId)
                .coachingPeriodId(coaching.periodId())
                .recommendationType("WORKOUT_PLAN_PROPOSAL")
                .title(title)
                .explanation(explanation)
                .structuredPayload(rawJson)
                .originalPayload(rawJson)
                .revision(1)
                .isCustomized(false)
                .sourceSnapshot(sourceSnapshotJson)
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(authority)
                .targetEntityType("WORKOUT_PLAN")
                .expiresAt(expiresAt)
                .createdAt(completedAt)
                .updatedAt(completedAt)
                .build();

        AiRecommendation saved = recommendationRepository.save(recommendation);

        // Record Audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.actorId())
                .actorRole(authority.name())
                .action("AI_RECOMMENDATION_CREATED")
                .targetType("AI_RECOMMENDATION")
                .targetId(saved.id())
                .beforeDataJson("{}")
                .afterDataJson(rawJson)
                .occurredAt(completedAt)
                .build());

        return new WorkoutProposalResult(
                saved.id(),
                runId,
                saved.status(),
                saved.decisionAuthority(),
                saved.title(),
                saved.explanation(),
                saved.expiresAt(),
                saved.createdAt(),
                saved.structuredPayload()
        );
    }

    @Override
    public AiRecommendation getRecommendation(UUID actorId, UUID recommendationId) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authenticated actor ID is required.");
        }
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        AiRecommendation rec = recommendationRepository.findById(recommendationId)
                .orElseThrow(() -> new AiRunNotFoundException("Recommendation not found: " + recommendationId));

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
    public UpdateDraftResult updateDraft(UpdateDraftCommand command) {
        if (command == null || command.actorId() == null || command.recommendationId() == null) {
            throw new IllegalArgumentException("Invalid update draft command parameters.");
        }
        if (command.structuredPayload() == null || command.structuredPayload().isBlank()) {
            throw new IllegalArgumentException("Structured payload is required for draft update.");
        }

        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(command.actorId()).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        AiRecommendation rec = recommendationRepository.findByIdForUpdate(command.recommendationId())
                .orElseThrow(() -> new AiRunNotFoundException("Recommendation not found: " + command.recommendationId()));

        // 1. Verify current coaching context and decision authority
        var currentContext = coachingContextQuery.findEffectiveContext(rec.studentId(), false).orElse(null);
        if (currentContext == null) {
            throw new AiRunAccessDeniedException("Active coaching context required to edit draft.");
        }

        if (rec.coachingPeriodId() != null && !rec.coachingPeriodId().equals(currentContext.periodId())) {
            throw new AiRunLifecycleConflictException("Coaching period has transitioned since recommendation was generated.");
        }

        if (currentContext.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH) {
            if (rec.decisionAuthority() != AiDecisionAuthority.TRAINER) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to HUMAN_COACH; student decision authority is invalid.");
            }
            if (currentContext.trainerId() == null || !command.actorId().equals(currentContext.trainerId())) {
                throw new AiRunAccessDeniedException("Only the currently assigned coach has authority to edit this draft.");
            }
        } else {
            // SELF_DIRECTED
            if (rec.decisionAuthority() != AiDecisionAuthority.STUDENT) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to SELF_DIRECTED; trainer decision authority is invalid.");
            }
            if (!command.actorId().equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Only the student has authority to edit this draft.");
            }
        }

        // 2. Status and Expiration Checks
        if (rec.status() != AiRecommendationStatus.PENDING) {
            throw new AiRunLifecycleConflictException("Recommendation is in status " + rec.status() + " and cannot be edited.");
        }

        Instant now = Instant.now();
        if (rec.isExpired(now)) {
            throw new AiRunLifecycleConflictException("Recommendation has expired and cannot be edited.");
        }

        // 3. Optimistic Locking / Revision Check
        if (rec.revision() != command.expectedRevision()) {
            throw new AiRunLifecycleConflictException("Revision conflict: expected revision "
                    + command.expectedRevision() + " but current is " + rec.revision());
        }

        // 4. Strict Output Validation on Modified Payload
        F02RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(command.structuredPayload());
        if (!validation.isValid()) {
            throw new AiOutputValidationException("Draft payload validation failed: " + validation.failureReason());
        }

        // 5. Determine updated Title and Explanation
        String updatedTitle = command.title() != null && !command.title().isBlank() ? command.title() : rec.title();
        String updatedExplanation = command.explanation() != null && !command.explanation().isBlank() ? command.explanation() : rec.explanation();
        try {
            JsonNode root = objectMapper.readTree(command.structuredPayload());
            if ((command.title() == null || command.title().isBlank()) && root.has("plan") && root.get("plan").has("name")) {
                updatedTitle = root.get("plan").get("name").asText(updatedTitle);
            }
            if ((command.explanation() == null || command.explanation().isBlank()) && root.has("summary")) {
                updatedExplanation = root.get("summary").asText(updatedExplanation);
            }
        } catch (Exception ignored) {}

        int newRevision = rec.revision() + 1;

        // 6. Persist Updated Draft
        recommendationRepository.updateDraft(
                rec.id(),
                newRevision,
                updatedTitle,
                updatedExplanation,
                command.structuredPayload(),
                command.actorId()
        );

        // 7. Record Immutable Audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.actorId())
                .actorRole(rec.decisionAuthority().name())
                .action("AI_RECOMMENDATION_DRAFT_UPDATED")
                .targetType("AI_RECOMMENDATION")
                .targetId(rec.id())
                .beforeDataJson(rec.structuredPayload())
                .afterDataJson(command.structuredPayload())
                .occurredAt(now)
                .build());

        String orig = rec.originalPayload() != null ? rec.originalPayload() : rec.structuredPayload();

        return new UpdateDraftResult(
                rec.id(),
                newRevision,
                true,
                rec.status(),
                updatedTitle,
                updatedExplanation,
                command.structuredPayload(),
                orig,
                now
        );
    }

    @Override
    @Transactional
    public AcceptRecommendationResult acceptRecommendation(AcceptRecommendationCommand command) {
        if (command == null || command.actorId() == null || command.recommendationId() == null) {
            throw new IllegalArgumentException("Invalid accept command parameters.");
        }

        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(command.actorId()).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        AiRecommendation rec = recommendationRepository.findByIdForUpdate(command.recommendationId())
                .orElseThrow(() -> new AiRunNotFoundException("Recommendation not found: " + command.recommendationId()));

        // 1. Verify current coaching context and decision authority FIRST before looking at replay
        var currentContext = coachingContextQuery.findEffectiveContext(rec.studentId(), false).orElse(null);
        if (currentContext == null) {
            throw new AiRunAccessDeniedException("Active coaching context required to process recommendation.");
        }

        // Verify coaching period continuity
        if (rec.coachingPeriodId() != null && !rec.coachingPeriodId().equals(currentContext.periodId())) {
            throw new AiRunLifecycleConflictException("Coaching period has transitioned since recommendation was generated.");
        }

        // Verify authority against current coaching mode
        if (currentContext.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH) {
            if (rec.decisionAuthority() != AiDecisionAuthority.TRAINER) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to HUMAN_COACH; student decision authority is invalid.");
            }
            if (currentContext.trainerId() == null || !command.actorId().equals(currentContext.trainerId())) {
                throw new AiRunAccessDeniedException("Only the currently assigned coach has decision authority to accept this recommendation.");
            }
        } else {
            // SELF_DIRECTED
            if (rec.decisionAuthority() != AiDecisionAuthority.STUDENT) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to SELF_DIRECTED; trainer decision authority is invalid.");
            }
            if (!command.actorId().equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Only the student has decision authority to accept this recommendation.");
            }
        }

        // 2. Check for Idempotent Replay
        Optional<AiRecommendationApplication> existingApp = recommendationRepository.findApplicationByRecommendationId(rec.id());
        if (existingApp.isPresent()) {
            AiRecommendationApplication app = existingApp.get();
            try {
                JsonNode payload = objectMapper.readTree(app.applicationPayload());
                String savedKey = payload.path("commandKey").asText();
                String savedActor = payload.path("actorId").asText();
                String savedNote = payload.has("decisionNote") && !payload.path("decisionNote").isNull()
                        ? payload.path("decisionNote").asText() : "";

                if (command.commandKey() != null && command.commandKey().equals(savedKey)) {
                    // Check payload identity and revision consistency
                    boolean actorMatches = command.actorId().toString().equals(savedActor);
                    String cmdNote = command.decisionNote() != null ? command.decisionNote() : "";
                    boolean noteMatches = cmdNote.equals(savedNote);
                    int savedRev = payload.has("appliedRevision") ? payload.path("appliedRevision").asInt() : 1;
                    boolean revMatches = (command.expectedRevision() != null)
                            ? command.expectedRevision() == savedRev
                            : savedRev == 1;

                    boolean planIdMatches = true;
                    if (command.expectedPlanId() != null) {
                        String savedPlanId = payload.has("expectedPlanId") ? payload.path("expectedPlanId").asText() : "";
                        planIdMatches = command.expectedPlanId().toString().equals(savedPlanId);
                    }

                    boolean planVerMatches = true;
                    if (command.expectedPlanAggregateVersion() != null) {
                        long savedPlanVer = payload.has("expectedPlanAggregateVersion") ? payload.path("expectedPlanAggregateVersion").asLong() : -1L;
                        planVerMatches = command.expectedPlanAggregateVersion() == savedPlanVer;
                    }

                    if (actorMatches && noteMatches && revMatches && planIdMatches && planVerMatches) {
                        log.info("Replaying identical AcceptRecommendationCommand for key: {}", command.commandKey());
                        return new AcceptRecommendationResult(
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

        if (rec.status() != AiRecommendationStatus.PENDING) {
            throw new AiRunLifecycleConflictException("Recommendation is in status " + rec.status() + " and cannot be accepted.");
        }

        Instant now = Instant.now();
        if (rec.isExpired(now)) {
            throw new AiRunLifecycleConflictException("Recommendation has expired and cannot be accepted.");
        }

        // Verify revision: if provided, it must match current revision.
        // If draft has been modified beyond v1, expectedRevision is mandatory to prevent
        // applying an altered draft when caller only saw earlier revisions.
        if (command.expectedRevision() != null) {
            if (rec.revision() != command.expectedRevision()) {
                throw new AiRunLifecycleConflictException("Revision conflict: expected revision "
                        + command.expectedRevision() + " but current is " + rec.revision());
            }
        } else if (rec.revision() > 1) {
            throw new AiRunLifecycleConflictException("Revision conflict: draft has been modified to revision "
                    + rec.revision() + "; expectedRevision is required when accepting.");
        }

        // Check if student already has an ACTIVE workout plan
        Optional<PlanDetail> activePlanOpt = workoutPlanQueryUseCase.findActive(command.actorId(), rec.studentId());

        TrainingContinuityState snapshotContinuity = null;

        // Source Snapshot Guards (T05: Goal and active plan state verification with fail-closed semantics)
        if (rec.sourceSnapshot() != null && !rec.sourceSnapshot().isBlank()) {
            JsonNode snapshotNode;
            try {
                snapshotNode = objectMapper.readTree(rec.sourceSnapshot());
            } catch (Exception e) {
                log.error("Failed to parse source snapshot for recommendation {}: {}", command.recommendationId(), e.getMessage());
                throw new AiRunLifecycleConflictException("Source snapshot is malformed and cannot be verified: " + e.getMessage());
            }

            if (snapshotNode.hasNonNull("continuityState")) {
                try {
                    snapshotContinuity = TrainingContinuityState.valueOf(snapshotNode.get("continuityState").asText());
                } catch (Exception ignored) {}
            }

            if (snapshotNode.hasNonNull("goalId")) {
                UUID snapshotGoalId = UUID.fromString(snapshotNode.get("goalId").asText());
                int snapshotGoalVer = snapshotNode.has("goalVersionNumber") ? snapshotNode.get("goalVersionNumber").asInt() : -1;

                GetCurrentFitnessGoalUseCase goalService = goalProvider.getIfAvailable();
                if (goalService == null) {
                    throw new AiProviderException("Fitness goal service is unavailable for source revalidation");
                }
                Optional<FitnessGoal> currentGoalOpt = goalService.lockAndVerifyActiveGoal(rec.studentId(), snapshotGoalId, snapshotGoalVer);
                if (currentGoalOpt.isEmpty() || currentGoalOpt.get().status() != GoalStatus.ACTIVE) {
                    throw new AiRunLifecycleConflictException("Active fitness goal is no longer active; cannot accept recommendation.");
                }
                FitnessGoal currentGoal = currentGoalOpt.get();
                if (!snapshotGoalId.equals(currentGoal.id())) {
                    throw new AiRunLifecycleConflictException("Fitness goal has changed since proposal was generated; cannot accept recommendation.");
                }
                if (currentGoal.currentVersion() == null || currentGoal.currentVersion().versionNumber() != snapshotGoalVer) {
                    throw new AiRunLifecycleConflictException("Fitness goal version has changed since proposal was generated (was v"
                            + snapshotGoalVer + ", now v" + (currentGoal.currentVersion() != null ? currentGoal.currentVersion().versionNumber() : "null")
                            + "); please reload proposal.");
                }
            }

            // Plan guards from snapshot
            if (snapshotNode.has("activePlanId")) {
                if (snapshotNode.get("activePlanId").isNull()) {
                    if (activePlanOpt.isPresent()) {
                        throw new AiRunLifecycleConflictException("Active workout plan appeared since proposal was generated; "
                                + "please reload the proposal to review changes before accepting.");
                    }
                } else {
                    UUID snapshotPlanId = UUID.fromString(snapshotNode.get("activePlanId").asText());
                    long snapshotPlanAggVer = snapshotNode.has("activePlanAggregateVersion")
                            ? snapshotNode.get("activePlanAggregateVersion").asLong() : -1L;

                    if (activePlanOpt.isEmpty()) {
                        throw new AiRunLifecycleConflictException("Expected active workout plan " + snapshotPlanId
                                + " is no longer active; please reload the proposal before accepting.");
                    }
                    if (!activePlanOpt.get().plan().id().equals(snapshotPlanId)) {
                        throw new AiRunLifecycleConflictException("Active workout plan has changed since proposal was generated (was "
                                + snapshotPlanId + ", now " + activePlanOpt.get().plan().id() + "); please reload proposal.");
                    }
                    if (snapshotPlanAggVer >= 0 && activePlanOpt.get().plan().aggregateVersion() != snapshotPlanAggVer) {
                        long currentAggVer = activePlanOpt.get().plan().aggregateVersion();
                        if (command.expectedPlanAggregateVersion() == null || command.expectedPlanAggregateVersion() != currentAggVer) {
                            throw new AiRunLifecycleConflictException("Workout plan version conflict: plan aggregate version has changed (was "
                                    + snapshotPlanAggVer + ", now " + currentAggVer
                                    + "); please reload to review changes before accepting.");
                        }
                        log.info("Accepting proposal {} after client reloaded preview: original snapshot aggregate version {}, current aggregate version {}",
                                command.recommendationId(), snapshotPlanAggVer, currentAggVer);
                    }
                }
            }
        } else {
            // Legacy proposal without snapshot: verify live active goal
            log.info("Proposal {} has no source snapshot (legacy record). Performing basic live goal verification.", command.recommendationId());
            GetCurrentFitnessGoalUseCase goalService = goalProvider.getIfAvailable();
            if (goalService != null) {
                Optional<FitnessGoal> currentGoalOpt = goalService.getCurrentFitnessGoal(rec.studentId(), false);
                if (currentGoalOpt.isEmpty() || currentGoalOpt.get().status() != GoalStatus.ACTIVE) {
                    throw new AiRunLifecycleConflictException("Active fitness goal is no longer active; cannot accept recommendation.");
                }
            }
        }

        // Re-validate payload with output validator and continuity rule gate inside transactional boundary before applying
        F02RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rec.structuredPayload(), snapshotContinuity);
        if (!validation.isValid()) {
            throw new AiOutputValidationException("Recommendation cannot be applied because payload is no longer valid: " + validation.failureReason());
        }

        // Parse structured payload into WorkoutSessionTemplates
        List<WorkoutSessionTemplate> sessionTemplates = parseTemplates(rec.structuredPayload());
        String planName = rec.title();
        String planDescription = rec.explanation();

        // Plan version and preview guards:
        if (Boolean.TRUE.equals(command.expectedNoActivePlan()) && activePlanOpt.isPresent()) {
            throw new AiRunLifecycleConflictException("Active workout plan appeared after proposal was previewed; "
                    + "please reload the proposal to review changes before accepting.");
        }

        if (command.expectedPlanId() != null) {
            if (activePlanOpt.isEmpty()) {
                throw new AiRunLifecycleConflictException("Expected active workout plan " + command.expectedPlanId()
                        + " is no longer active; please reload the proposal before accepting.");
            }
            if (!activePlanOpt.get().plan().id().equals(command.expectedPlanId())) {
                throw new AiRunLifecycleConflictException("Active workout plan has changed since preview (expected plan "
                        + command.expectedPlanId() + " but found " + activePlanOpt.get().plan().id()
                        + "); please reload the proposal before accepting.");
            }
        }

        if (command.expectedPlanAggregateVersion() != null) {
            if (activePlanOpt.isEmpty()) {
                throw new AiRunLifecycleConflictException("Expected active workout plan aggregate version "
                        + command.expectedPlanAggregateVersion() + " but no active plan is currently running.");
            }
            long currentAggregateVersion = activePlanOpt.get().plan().aggregateVersion();
            if (currentAggregateVersion != command.expectedPlanAggregateVersion()) {
                throw new AiRunLifecycleConflictException("Workout plan version conflict: expected plan aggregate version "
                        + command.expectedPlanAggregateVersion() + " but current is " + currentAggregateVersion
                        + "; please reload to review changes before accepting.");
            }
        }

        UUID resultingPlanId;
        UUID resultingPlanVersionId;
        String action;

        if (activePlanOpt.isPresent()) {
            PlanSummary activePlan = activePlanOpt.get().plan();

            // When an ACTIVE workout plan exists, execute significant version publication
            PublishVersionCommand publishCmd = new PublishVersionCommand(
                    activePlan.id(),
                    command.actorId(),
                    activePlan.aggregateVersion(),
                    command.commandKey() + ":PUBLISH",
                    "AI_WORKOUT_PROPOSAL_APPLIED",
                    planName != null && !planName.isBlank() ? planName : "AI Workout Plan Proposal Update",
                    sessionTemplates
            );
            CommandResult publishResult = workoutPlanCommandUseCase.publishVersion(publishCmd);
            resultingPlanId = publishResult.planId();
            resultingPlanVersionId = publishResult.planVersionId();
            action = "PUBLISHED_NEW_VERSION";
        } else {
            // Student has NO active plan: create draft and activate
            CreateDraftCommand draftCmd = new CreateDraftCommand(
                    rec.studentId(),
                    command.actorId(),
                    planName,
                    planDescription,
                    sessionTemplates,
                    command.commandKey() + ":DRAFT"
            );
            CommandResult draftResult = workoutPlanCommandUseCase.createDraft(draftCmd);

            ActivateCommand activateCmd = new ActivateCommand(
                    draftResult.planId(),
                    command.actorId(),
                    0L,
                    command.commandKey() + ":ACTIVATE"
            );
            workoutPlanCommandUseCase.activate(activateCmd);
            resultingPlanId = draftResult.planId();
            resultingPlanVersionId = draftResult.planVersionId();
            action = "CREATED_AND_ACTIVATED_NEW_PLAN";
        }

        // 3. Mark Recommendation APPLIED atomically
        recommendationRepository.updateDecision(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                command.actorId(),
                now,
                command.decisionNote(),
                "WORKOUT_PLAN",
                resultingPlanId
        );

        // 4. Save Application Audit Record (Receipt)
        String appPayload = "{}";
        try {
            appPayload = objectMapper.writeValueAsString(Map.of(
                    "commandKey", command.commandKey() != null ? command.commandKey() : "",
                    "actorId", command.actorId().toString(),
                    "decisionNote", command.decisionNote() != null ? command.decisionNote() : "",
                    "resultingPlanId", resultingPlanId.toString(),
                    "resultingPlanVersionId", resultingPlanVersionId != null ? resultingPlanVersionId.toString() : "",
                    "appliedRevision", rec.revision(),
                    "isCustomized", rec.isCustomized(),
                    "action", action,
                    "expectedPlanId", command.expectedPlanId() != null ? command.expectedPlanId().toString() : "",
                    "expectedPlanAggregateVersion", command.expectedPlanAggregateVersion() != null ? command.expectedPlanAggregateVersion() : -1L
            ));
        } catch (Exception ignored) {}

        AiRecommendationApplication application = new AiRecommendationApplication(
                UUID.randomUUID(),
                rec.id(),
                command.actorId(),
                "WORKOUT_PLAN",
                resultingPlanId,
                appPayload,
                now
        );
        recommendationRepository.saveApplication(application);

        // 5. Audit Logging
        String auditAfterJson = "{}";
        try {
            auditAfterJson = objectMapper.writeValueAsString(Map.of(
                    "status", "APPLIED",
                    "resultingPlanId", resultingPlanId.toString(),
                    "resultingPlanVersionId", resultingPlanVersionId != null ? resultingPlanVersionId.toString() : "",
                    "appliedRevision", rec.revision(),
                    "isCustomized", rec.isCustomized(),
                    "action", action
            ));
        } catch (Exception ignored) {}

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.actorId())
                .actorRole(rec.decisionAuthority().name())
                .action("AI_RECOMMENDATION_APPLIED")
                .targetType("AI_RECOMMENDATION")
                .targetId(rec.id())
                .beforeDataJson(rec.structuredPayload())
                .afterDataJson(auditAfterJson)
                .occurredAt(now)
                .build());

        return new AcceptRecommendationResult(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                command.actorId(),
                "WORKOUT_PLAN",
                resultingPlanId,
                now,
                false
        );
    }

    @Override
    @Transactional
    public RejectRecommendationResult rejectRecommendation(RejectRecommendationCommand command) {
        if (command == null || command.actorId() == null || command.recommendationId() == null) {
            throw new IllegalArgumentException("Invalid reject command parameters.");
        }

        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(command.actorId()).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        AiRecommendation rec = recommendationRepository.findByIdForUpdate(command.recommendationId())
                .orElseThrow(() -> new AiRunNotFoundException("Recommendation not found: " + command.recommendationId()));

        if (rec.status() != AiRecommendationStatus.PENDING) {
            throw new AiRunLifecycleConflictException("Recommendation is in status " + rec.status() + " and cannot be rejected.");
        }

        Instant now = Instant.now();
        if (rec.isExpired(now)) {
            throw new AiRunLifecycleConflictException("Recommendation has expired and cannot be rejected.");
        }

        // Decision Authority Verification against current coaching context
        var currentContext = coachingContextQuery.findEffectiveContext(rec.studentId(), false).orElse(null);
        if (currentContext == null) {
            throw new AiRunAccessDeniedException("Active coaching context required to reject recommendation.");
        }

        if (currentContext.mode() == CurrentCoachingContextQuery.Mode.HUMAN_COACH) {
            if (rec.decisionAuthority() != AiDecisionAuthority.TRAINER) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to HUMAN_COACH; student decision authority is invalid.");
            }
            if (currentContext.trainerId() == null || !command.actorId().equals(currentContext.trainerId())) {
                throw new AiRunAccessDeniedException("Only the assigned coach has decision authority to reject this recommendation.");
            }
        } else {
            // SELF_DIRECTED
            if (rec.decisionAuthority() != AiDecisionAuthority.STUDENT) {
                throw new AiRunAccessDeniedException("Coaching mode has transitioned to SELF_DIRECTED; trainer decision authority is invalid.");
            }
            if (!command.actorId().equals(rec.studentId())) {
                throw new AiRunAccessDeniedException("Only the student has decision authority to reject this recommendation.");
            }
        }

        recommendationRepository.updateDecision(
                rec.id(),
                AiRecommendationStatus.REJECTED,
                command.actorId(),
                now,
                command.reason(),
                rec.targetEntityType(),
                rec.targetEntityId()
        );

        String auditAfterJson;
        try {
            auditAfterJson = objectMapper.writeValueAsString(Map.of("reason", command.reason() != null ? command.reason() : ""));
        } catch (Exception e) {
            auditAfterJson = "{}";
        }

        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.actorId())
                .actorRole(rec.decisionAuthority().name())
                .action("AI_RECOMMENDATION_REJECTED")
                .targetType("AI_RECOMMENDATION")
                .targetId(rec.id())
                .beforeDataJson("{}")
                .afterDataJson(auditAfterJson)
                .occurredAt(now)
                .build());

        return new RejectRecommendationResult(
                rec.id(),
                AiRecommendationStatus.REJECTED,
                command.actorId(),
                now,
                command.reason()
        );
    }

    private List<WorkoutSessionTemplate> parseTemplates(String rawJson) {
        List<WorkoutSessionTemplate> sessionTemplates = new ArrayList<>();
        try {
            JsonNode root = objectMapper.readTree(rawJson);
            JsonNode sessionsNode = root.path("plan").path("sessions");
            for (int i = 0; i < sessionsNode.size(); i++) {
                JsonNode sNode = sessionsNode.get(i);
                int weekNumber = sNode.path("weekNumber").asInt(1);
                int dayNumber = sNode.path("dayNumber").asInt(1);
                int seqNumber = sNode.path("sequenceNumber").asInt(i + 1);
                String name = sNode.path("name").asText("Session " + (i + 1));
                String focus = sNode.has("focus") && !sNode.path("focus").isNull() ? sNode.path("focus").asText() : null;
                Integer estimatedMinutes = sNode.has("estimatedDurationMinutes") && !sNode.path("estimatedDurationMinutes").isNull()
                        ? sNode.path("estimatedDurationMinutes").asInt() : null;
                String notes = sNode.has("notes") && !sNode.path("notes").isNull() ? sNode.path("notes").asText() : null;

                JsonNode pNode = sNode.path("prescriptions");
                List<ExercisePrescription> prescriptions = new ArrayList<>();
                for (int j = 0; j < pNode.size(); j++) {
                    JsonNode exNode = pNode.get(j);
                    UUID varId = UUID.fromString(exNode.path("exerciseVariationId").asText());
                    int exSeq = exNode.path("sequenceNumber").asInt(j + 1);
                    Integer targetSets = exNode.has("targetSets") && !exNode.path("targetSets").isNull() ? exNode.path("targetSets").asInt() : 3;
                    Integer targetRepsMin = exNode.has("targetRepsMin") && !exNode.path("targetRepsMin").isNull() ? exNode.path("targetRepsMin").asInt() : null;
                    Integer targetRepsMax = exNode.has("targetRepsMax") && !exNode.path("targetRepsMax").isNull() ? exNode.path("targetRepsMax").asInt() : null;
                    BigDecimal targetLoad = exNode.has("targetLoad") && !exNode.path("targetLoad").isNull()
                            ? BigDecimal.valueOf(exNode.path("targetLoad").asDouble()) : null;
                    Integer restSeconds = exNode.has("restSeconds") && !exNode.path("restSeconds").isNull() ? exNode.path("restSeconds").asInt() : null;
                    Integer durationSeconds = exNode.has("durationSeconds") && !exNode.path("durationSeconds").isNull() ? exNode.path("durationSeconds").asInt() : null;
                    String instructions = exNode.has("instructions") && !exNode.path("instructions").isNull() ? exNode.path("instructions").asText() : null;

                    prescriptions.add(new ExercisePrescription(
                            null,
                            varId,
                            exSeq,
                            targetSets,
                            targetRepsMin,
                            targetRepsMax,
                            targetLoad,
                            restSeconds,
                            durationSeconds,
                            instructions
                    ));
                }

                sessionTemplates.add(new WorkoutSessionTemplate(
                        null,
                        weekNumber,
                        dayNumber,
                        seqNumber,
                        name,
                        focus,
                        estimatedMinutes,
                        notes,
                        prescriptions
                ));
            }
            return sessionTemplates;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse workout plan templates from structured proposal", e);
        }
    }
}
