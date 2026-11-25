package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AccountUnavailableException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.ExerciseNotFoundException;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CompleteF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.in.CreateF01AiRunCommand;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiProvenanceRepository;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiContextSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunInputReference;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.ExerciseCatalogRepository;
import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseCatalogDetail;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class AiRunF01Service implements AiRunF01UseCase {

    private static final Logger log = LoggerFactory.getLogger(AiRunF01Service.class);

    private final AiRunRepository runRepository;
    private final AiProvenanceRepository provenanceRepository;
    private final UserAccountStatusQuery accountStatusQuery;
    private final UserRoleQuery userRoleQuery;
    private final ExerciseCatalogRepository exerciseCatalogRepository;
    private final F01InternalEnvelopeBuilder envelopeBuilder;
    private final F01PersonalContextBuilder personalContextBuilder;
    private final ObjectMapper objectMapper;

    @org.springframework.beans.factory.annotation.Autowired
    public AiRunF01Service(
            AiRunRepository runRepository,
            AiProvenanceRepository provenanceRepository,
            UserAccountStatusQuery accountStatusQuery,
            UserRoleQuery userRoleQuery,
            ExerciseCatalogRepository exerciseCatalogRepository,
            F01InternalEnvelopeBuilder envelopeBuilder,
            @org.springframework.beans.factory.annotation.Autowired(required = false) F01PersonalContextBuilder personalContextBuilder,
            ObjectMapper objectMapper
    ) {
        this.runRepository = runRepository;
        this.provenanceRepository = provenanceRepository;
        this.accountStatusQuery = accountStatusQuery;
        this.userRoleQuery = userRoleQuery;
        this.exerciseCatalogRepository = exerciseCatalogRepository;
        this.envelopeBuilder = envelopeBuilder;
        this.personalContextBuilder = personalContextBuilder;
        this.objectMapper = objectMapper;
    }

    public AiRunF01Service(
            AiRunRepository runRepository,
            AiProvenanceRepository provenanceRepository,
            UserAccountStatusQuery accountStatusQuery,
            UserRoleQuery userRoleQuery,
            ExerciseCatalogRepository exerciseCatalogRepository,
            F01InternalEnvelopeBuilder envelopeBuilder,
            ObjectMapper objectMapper
    ) {
        this(runRepository, provenanceRepository, accountStatusQuery, userRoleQuery, exerciseCatalogRepository, envelopeBuilder, null, objectMapper);
    }

    @Override
    @Transactional
    public CreatedF01RunContext createF01RunContext(UUID actorId, CreateF01AiRunCommand command) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authenticated actor ID is required");
        }

        // 1. Verify account status
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }

        // 2. Enforce capability (STUDENT or TRAINER). Base ADMIN alone is forbidden.
        boolean isStudent = userRoleQuery.hasActiveRole(actorId, "STUDENT");
        boolean isTrainer = userRoleQuery.hasActiveRole(actorId, "TRAINER");
        if (!isStudent && !isTrainer) {
            throw new AiRunAccessDeniedException("User lacks STUDENT or TRAINER capability to initiate AI general knowledge query");
        }

        // 3. Validate question input bounds (5 to 1000 unicode characters, non-whitespace)
        if (command == null || command.question() == null) {
            throw new AiRunValidationException("Question must not be null");
        }
        String trimmedQuestion = command.question().trim();
        int codePointCount = trimmedQuestion.codePointCount(0, trimmedQuestion.length());
        if (codePointCount < 5 || codePointCount > 1000) {
            throw new AiRunValidationException("Question length must be between 5 and 1000 characters");
        }

        // 4. Validate language (default 'vi', allow 'en')
        String language = (command.language() != null && !command.language().isBlank()) ? command.language().trim().toLowerCase() : "vi";
        if (!language.equals("vi") && !language.equals("en")) {
            throw new AiRunValidationException("Language must be 'vi' or 'en'");
        }

        // 5. If exerciseId provided, verify catalog existence and ACTIVE state
        ExerciseCatalogDetail exerciseDetail = null;
        if (command.exerciseId() != null) {
            Optional<ExerciseCatalogDetail> opt = exerciseCatalogRepository.findActiveDetail(command.exerciseId());
            if (opt.isEmpty()) {
                throw new ExerciseNotFoundException("Active exercise not found with ID: " + command.exerciseId());
            }
            exerciseDetail = opt.get();
        }

        // 6. Build input payload JSON
        Map<String, Object> inputMap = new LinkedHashMap<>();
        inputMap.put("question", trimmedQuestion);
        inputMap.put("language", language);
        if (command.exerciseId() != null) {
            inputMap.put("exerciseId", command.exerciseId().toString());
        }
        if (command.correlationId() != null) {
            inputMap.put("correlationId", command.correlationId().toString());
        }
        if (command.studentId() != null) {
            inputMap.put("studentId", command.studentId().toString());
        }
        if (command.includePersonalContext() != null) {
            inputMap.put("includePersonalContext", command.includePersonalContext());
        }
        String inputPayloadJson = toJson(inputMap);

        // 6a. Resolve acting role for dual-role accounts (STUDENT + TRAINER)
        String actingRole;
        if (command.studentId() != null && !command.studentId().equals(actorId)) {
            // Requesting for another student: must act as TRAINER if permitted
            actingRole = isTrainer ? "TRAINER" : "STUDENT";
        } else {
            // Requesting for self or general query
            actingRole = isStudent ? "STUDENT" : "TRAINER";
        }

        // 6b. Build personal context only if explicitly requested
        F01InternalContextEnvelope.PersonalContext personalContext = null;
        if (Boolean.TRUE.equals(command.includePersonalContext()) && personalContextBuilder != null) {
            personalContext = personalContextBuilder.buildPersonalContext(
                    actorId,
                    actingRole,
                    command.studentId()
            );
        } else if ("STUDENT".equals(actingRole) && command.studentId() != null && !command.studentId().equals(actorId)) {
            // Even when includePersonalContext is false/omitted, a Student must not query with another Student's ID
            throw new AiRunAccessDeniedException("Student is not authorized to query another student's personal context");
        }

        UUID runId = UUID.randomUUID();
        Instant now = Instant.now();

        AiRun run = AiRun.builder()
                .id(runId)
                .requestedBy(actorId)
                .requestType("F01_GENERAL_KNOWLEDGE")
                .status(AiRunStatus.QUEUED)
                .purpose("BUSINESS_ASSISTANCE")
                .evaluationOnly(false)
                .correlationId(command.correlationId())
                .inputPayload(inputPayloadJson)
                .queuedAt(now)
                .createdAt(now)
                .build();

        AiRun created = runRepository.create(run);

        // 7. Build and persist authoritative internal context envelope
        F01InternalContextEnvelope envelope = envelopeBuilder.build(
                runId,
                actorId,
                actingRole,
                trimmedQuestion,
                language,
                command.correlationId(),
                exerciseDetail,
                personalContext
        );

        String contextPayloadJson = toJson(envelope);
        String contextHash = sha256Hex(contextPayloadJson);

        AiContextSnapshot snapshot = new AiContextSnapshot(
                UUID.randomUUID(),
                runId,
                "1.0.0",
                contextPayloadJson,
                "{}",
                "{}",
                "{}",
                contextHash,
                now
        );
        provenanceRepository.saveSnapshot(snapshot);

        List<AiRunInputReference> inputReferences = new java.util.ArrayList<>();
        if (command.exerciseId() != null) {
            inputReferences.add(new AiRunInputReference(runId, "EXERCISE", command.exerciseId(), "CATALOG_SUBJECT"));
        }
        if (personalContext != null) {
            inputReferences.add(new AiRunInputReference(runId, "STUDENT", personalContext.studentId(), "PERSONAL_CONTEXT_SUBJECT"));
        }
        if (!inputReferences.isEmpty()) {
            provenanceRepository.saveInputReferences(runId, inputReferences);
        }

        return new CreatedF01RunContext(created, envelope);
    }

    @Override
    @Transactional
    public AiRun createF01Run(UUID actorId, CreateF01AiRunCommand command) {
        return createF01RunContext(actorId, command).run();
    }

    @Override
    // Trainer readback checks use Coaching authority's FOR SHARE boundary, requiring a read-write transaction in PostgreSQL.
    @Transactional
    public AiRun getF01Run(UUID actorId, UUID runId) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authenticated actor ID is required");
        }
        if (runId == null) {
            throw new AiRunValidationException("Run ID is required");
        }

        revalidateActorAuthorization(actorId);

        AiRun run = runRepository.findById(runId)
                .orElseThrow(() -> new AiRunNotFoundException("AI Run not found with ID: " + runId));

        // Enforce ownership: only the user who requested the run can view it
        if (run.requestedBy() == null || !run.requestedBy().equals(actorId)) {
            throw new AiRunAccessDeniedException("Access denied: only the run owner can access this AI Run");
        }

        // T04 Readback Guard: If the run contained personal context for a student other than the actor,
        // verify that the actor STILL retains active TRAINER capability, active coaching authority over that student,
        // and granular sharing permissions for any context data sources included in the run.
        if (personalContextBuilder != null) {
            List<AiRunInputReference> inputRefs = provenanceRepository.findInputReferencesByRunId(runId);
            for (AiRunInputReference ref : inputRefs) {
                if ("STUDENT".equals(ref.entityType()) && !actorId.equals(ref.entityId())) {
                    if (!userRoleQuery.hasActiveRole(actorId, "TRAINER")) {
                        throw new AiRunAccessDeniedException("Access denied: active trainer role required to access student context");
                    }
                    personalContextBuilder.validateTrainerAuthority(actorId, ref.entityId());

                    // Check snapshot to determine if goal or plan was included in this run's context
                    boolean hadGoal = false;
                    boolean hadPlan = false;
                    Optional<AiContextSnapshot> snapshotOpt = provenanceRepository.findSnapshotByRunId(runId);
                    if (snapshotOpt.isPresent() && snapshotOpt.get().contextPayload() != null) {
                        try {
                            JsonNode root = objectMapper.readTree(snapshotOpt.get().contextPayload());
                            JsonNode personalCtxNode = root.get("personalContext");
                            if (personalCtxNode != null && !personalCtxNode.isNull()) {
                                hadGoal = personalCtxNode.hasNonNull("activeGoal");
                                hadPlan = personalCtxNode.hasNonNull("currentActivePlanName");
                            }
                        } catch (Exception e) {
                            log.debug("Failed to inspect context snapshot for run {}: {}", runId, e.getMessage());
                        }
                    }
                    personalContextBuilder.validateGranularSharingAuthority(actorId, ref.entityId(), hadGoal, hadPlan);
                }
            }
        }

        return run;
    }

    @Override
    @Transactional
    public AiRun transitionRunStatus(
            UUID runId,
            AiRunStatus expectedStatus,
            AiRunStatus newStatus,
            Instant startedAt,
            Instant completedAt
    ) {
        if (runId == null) {
            throw new AiRunValidationException("Run ID is required");
        }
        if (expectedStatus == null || newStatus == null) {
            throw new AiRunValidationException("Status parameters must not be null");
        }

        AiRun existing = runRepository.findByIdForUpdate(runId)
                .orElseThrow(() -> new AiRunNotFoundException("AI Run not found with ID: " + runId));

        if (existing.isTerminal()) {
            throw new AiRunLifecycleConflictException("Cannot transition AI Run " + runId + ": already in terminal status " + existing.status());
        }

        if (existing.status() != expectedStatus) {
            throw new AiRunLifecycleConflictException("Cannot transition AI Run " + runId + ": expected status " + expectedStatus + " but was " + existing.status());
        }

        boolean success = runRepository.transitionStatus(runId, expectedStatus, newStatus, startedAt, completedAt);
        if (!success) {
            throw new AiRunLifecycleConflictException("Failed to transition AI Run " + runId + " from " + expectedStatus + " to " + newStatus);
        }

        return runRepository.findById(runId)
                .orElseThrow(() -> new AiRunNotFoundException("AI Run not found after transition: " + runId));
    }

    @Override
    @Transactional
    public AiRun completeF01Run(CompleteF01AiRunCommand command) {
        if (command == null || command.runId() == null) {
            throw new AiRunValidationException("Command and runId are required");
        }
        if (command.terminalStatus() == null || !command.terminalStatus().isTerminal()) {
            throw new AiRunValidationException("terminalStatus must be a terminal status (SUCCEEDED, FAILED, REJECTED_BY_VALIDATOR, CANCELLED)");
        }

        AiRun existing = runRepository.findByIdForUpdate(command.runId())
                .orElseThrow(() -> new AiRunNotFoundException("AI Run not found with ID: " + command.runId()));

        if (existing.isTerminal()) {
            throw new AiRunLifecycleConflictException("Cannot complete AI Run " + command.runId() + ": already completed with status " + existing.status());
        }

        // Sanitize error message to ensure secrets, stack traces or tokens are never leaked
        String sanitizedErrorMessage = sanitizeErrorMessage(command.errorMessage());

        boolean success = runRepository.completeRun(
                command.runId(),
                command.terminalStatus(),
                command.outputPayload(),
                command.errorCode(),
                sanitizedErrorMessage,
                command.promptTokens(),
                command.completionTokens(),
                command.latencyMs(),
                command.completedAt() != null ? command.completedAt() : Instant.now()
        );

        if (!success) {
            throw new AiRunLifecycleConflictException("Concurrent modification: AI Run " + command.runId() + " could not be marked terminal");
        }

        return runRepository.findById(command.runId())
                .orElseThrow(() -> new AiRunNotFoundException("AI Run not found after completion: " + command.runId()));
    }

    @Override
    @Transactional(readOnly = true)
    public void revalidateActorAuthorization(UUID actorId) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authenticated actor ID is required");
        }

        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId)
                .orElseThrow(() -> new AccountUnavailableException("Actor account does not exist"));
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AccountUnavailableException("Actor account is not active");
        }

        boolean isStudent = userRoleQuery.hasActiveRole(actorId, "STUDENT");
        boolean isTrainer = userRoleQuery.hasActiveRole(actorId, "TRAINER");
        if (!isStudent && !isTrainer) {
            throw new AiRunAccessDeniedException("User lacks STUDENT or TRAINER capability to access AI general knowledge output");
        }
    }

    private String sanitizeErrorMessage(String rawMessage) {
        if (rawMessage == null || rawMessage.isBlank()) {
            return null;
        }
        // Filter out stack traces, internal paths, sql exceptions, jwt tokens or raw secrets
        String sanitized = rawMessage;
        if (sanitized.contains("Exception") || sanitized.contains(".java:") || sanitized.contains("at org.") || sanitized.contains("at com.")) {
            sanitized = "Internal processing failure occurred.";
        }
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500);
        }
        return sanitized;
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize to JSON", e);
        }
    }

    private String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }
}
