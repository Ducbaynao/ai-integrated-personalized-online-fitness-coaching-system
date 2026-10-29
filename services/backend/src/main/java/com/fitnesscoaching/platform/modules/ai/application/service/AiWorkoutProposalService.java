package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.AiOutputValidationException;
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
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.ActivateCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.CommandResult;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.CreateDraftCommand;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final CurrentCoachingContextQuery coachingContextQuery;

    public AiWorkoutProposalService(
            AiRunRepository aiRunRepository,
            AiRecommendationRepository recommendationRepository,
            F02AiServiceClient aiServiceClient,
            F02ContextBuilder contextBuilder,
            F02RuntimeOutputValidator outputValidator,
            WorkoutPlanCommandUseCase workoutPlanCommandUseCase,
            AuditService auditService,
            ObjectMapper objectMapper,
            CurrentCoachingContextQuery coachingContextQuery
    ) {
        this.aiRunRepository = aiRunRepository;
        this.recommendationRepository = recommendationRepository;
        this.aiServiceClient = aiServiceClient;
        this.contextBuilder = contextBuilder;
        this.outputValidator = outputValidator;
        this.workoutPlanCommandUseCase = workoutPlanCommandUseCase;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.coachingContextQuery = coachingContextQuery;
    }

    @Override
    public WorkoutProposalResult generateProposal(GenerateProposalCommand command) {
        if (command == null || command.actorId() == null) {
            throw new IllegalArgumentException("Invalid proposal command: actorId is required.");
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

        // 1. Record Initial AI Run
        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(command.actorId())
                .studentId(studentId)
                .coachingPeriodId(coaching.periodId())
                .requestType("F02_WORKOUT_PLAN_PROPOSAL")
                .status(AiRunStatus.QUEUED)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .queuedAt(now)
                .createdAt(now)
                .build();
        aiRunRepository.create(run);

        // Transition to RUNNING
        aiRunRepository.transitionStatus(runId, AiRunStatus.QUEUED, AiRunStatus.RUNNING, now, null);

        // 2. Build Context Envelope & Invoke AI Service
        F02ServiceRequest request = contextBuilder.buildRequest(studentId, command.preferences(), List.of());
        String rawJson;
        Instant startCall = Instant.now();
        try {
            rawJson = aiServiceClient.generateProposalJson(request);
        } catch (Exception e) {
            Instant failedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, failedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.FAILED, null, "PROVIDER_ERROR", e.getMessage(), 0, 0, latency, failedAt);
            throw e;
        }

        // 3. Strict Runtime Output Validation
        F02RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rawJson);
        if (!validation.isValid()) {
            Instant rejectedAt = Instant.now();
            int latency = (int) ChronoUnit.MILLIS.between(startCall, rejectedAt);
            aiRunRepository.completeRun(runId, AiRunStatus.REJECTED_BY_VALIDATOR, null, "VALIDATION_FAILED",
                    validation.failureReason(), 0, 0, latency, rejectedAt);
            throw new AiOutputValidationException("F02 proposal output validation failed: " + validation.failureReason());
        }

        Instant completedAt = Instant.now();
        int latency = (int) ChronoUnit.MILLIS.between(startCall, completedAt);
        aiRunRepository.completeRun(runId, AiRunStatus.SUCCEEDED, rawJson, null, null, 150, 450, latency, completedAt);

        // 4. Create and Persist Recommendation in PENDING state
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

        Instant expiresAt = completedAt.plus(7, ChronoUnit.DAYS);

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

                    if (actorMatches && noteMatches && revMatches) {
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

        // Re-validate payload with output validator inside transactional boundary before applying
        F02RuntimeOutputValidator.ValidationResult validation = outputValidator.validate(rec.structuredPayload());
        if (!validation.isValid()) {
            throw new AiOutputValidationException("Recommendation cannot be applied because payload is no longer valid: " + validation.failureReason());
        }

        // Parse structured payload into WorkoutSessionTemplates
        List<WorkoutSessionTemplate> sessionTemplates = parseTemplates(rec.structuredPayload());
        String planName = rec.title();
        String planDescription = rec.explanation();

        // 1. Create Draft Workout Plan
        CreateDraftCommand draftCmd = new CreateDraftCommand(
                rec.studentId(),
                command.actorId(),
                planName,
                planDescription,
                sessionTemplates,
                command.commandKey() + ":DRAFT"
        );
        CommandResult draftResult = workoutPlanCommandUseCase.createDraft(draftCmd);

        // 2. Activate Workout Plan
        ActivateCommand activateCmd = new ActivateCommand(
                draftResult.planId(),
                command.actorId(),
                0L,
                command.commandKey() + ":ACTIVATE"
        );
        workoutPlanCommandUseCase.activate(activateCmd);

        // 3. Update Recommendation Status to APPLIED
        recommendationRepository.updateDecision(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                command.actorId(),
                now,
                command.decisionNote(),
                "WORKOUT_PLAN",
                draftResult.planId()
        );

        // 4. Save Application Record with full payload tracking including revision and provenance
        String appPayloadJson;
        try {
            appPayloadJson = objectMapper.writeValueAsString(Map.of(
                    "commandKey", command.commandKey() != null ? command.commandKey() : "",
                    "actorId", command.actorId().toString(),
                    "decisionNote", command.decisionNote() != null ? command.decisionNote() : "",
                    "createdPlanId", draftResult.planId().toString(),
                    "planVersionId", draftResult.planVersionId() != null ? draftResult.planVersionId().toString() : "",
                    "appliedRevision", rec.revision(),
                    "isCustomized", rec.isCustomized()
            ));
        } catch (Exception e) {
            appPayloadJson = "{}";
        }

        AiRecommendationApplication application = new AiRecommendationApplication(
                UUID.randomUUID(),
                rec.id(),
                command.actorId(),
                "WORKOUT_PLAN",
                draftResult.planId(),
                appPayloadJson,
                now
        );
        recommendationRepository.saveApplication(application);

        // 5. Record Immutable Audit
        auditService.recordAudit(AuditRecord.builder()
                .actorUserId(command.actorId())
                .actorRole(rec.decisionAuthority().name())
                .action("AI_RECOMMENDATION_APPLIED")
                .targetType("AI_RECOMMENDATION")
                .targetId(rec.id())
                .beforeDataJson("{}")
                .afterDataJson(appPayloadJson)
                .occurredAt(now)
                .build());

        return new AcceptRecommendationResult(
                rec.id(),
                AiRecommendationStatus.APPLIED,
                command.actorId(),
                "WORKOUT_PLAN",
                draftResult.planId(),
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
                String focus = sNode.has("focus") ? sNode.path("focus").asText() : null;
                Integer estimatedMinutes = sNode.has("estimatedDurationMinutes") ? sNode.path("estimatedDurationMinutes").asInt() : null;
                String notes = sNode.has("notes") ? sNode.path("notes").asText() : null;

                JsonNode pNode = sNode.path("prescriptions");
                List<ExercisePrescription> prescriptions = new ArrayList<>();
                for (int j = 0; j < pNode.size(); j++) {
                    JsonNode exNode = pNode.get(j);
                    UUID varId = UUID.fromString(exNode.path("exerciseVariationId").asText());
                    int exSeq = exNode.path("sequenceNumber").asInt(j + 1);
                    Integer targetSets = exNode.has("targetSets") ? exNode.path("targetSets").asInt() : 3;
                    Integer targetRepsMin = exNode.has("targetRepsMin") ? exNode.path("targetRepsMin").asInt() : null;
                    Integer targetRepsMax = exNode.has("targetRepsMax") ? exNode.path("targetRepsMax").asInt() : null;
                    BigDecimal targetLoad = exNode.has("targetLoad") ? BigDecimal.valueOf(exNode.path("targetLoad").asDouble()) : null;
                    Integer restSeconds = exNode.has("restSeconds") ? exNode.path("restSeconds").asInt() : null;
                    Integer durationSeconds = exNode.has("durationSeconds") ? exNode.path("durationSeconds").asInt() : null;
                    String instructions = exNode.has("instructions") ? exNode.path("instructions").asText() : null;

                    prescriptions.add(new ExercisePrescription(
                            UUID.randomUUID(), varId, exSeq, targetSets, targetRepsMin, targetRepsMax, targetLoad, restSeconds, durationSeconds, instructions
                    ));
                }

                sessionTemplates.add(new WorkoutSessionTemplate(
                        UUID.randomUUID(), weekNumber, dayNumber, seqNumber, name, focus, estimatedMinutes, notes, prescriptions
                ));
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse workout plan templates from structured proposal", e);
        }
        return sessionTemplates;
    }
}
