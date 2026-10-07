package com.fitnesscoaching.platform.modules.workout.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort.*;
import com.fitnesscoaching.platform.modules.workout.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class WorkoutPlanCommandService implements WorkoutPlanCommandUseCase {
    private final WorkoutPlanPersistencePort store;
    private final CurrentCoachingContextQuery contexts;
    private final WorkoutPlanAccessPolicy accessPolicy;
    private final ExerciseReferenceQuery exercises;
    private final AuditService audit;
    private final ObjectMapper objectMapper;

    public WorkoutPlanCommandService(WorkoutPlanPersistencePort store, CurrentCoachingContextQuery contexts,
                                     WorkoutPlanAccessPolicy accessPolicy, ExerciseReferenceQuery exercises,
                                     AuditService audit, ObjectMapper objectMapper) {
        this.store = store; this.contexts = contexts; this.accessPolicy = accessPolicy;
        this.exercises = exercises; this.audit = audit; this.objectMapper = objectMapper;
    }

    @Override public CommandResult createDraft(CreateDraftCommand c) {
        requireBase(c.actorId(), c.commandKey());
        if (c.studentId() == null) throw failure(WorkoutPlanError.VALIDATION_FAILED);
        List<WorkoutSessionTemplate> sessions = c.sessions() == null ? List.of() : List.copyOf(c.sessions());
        validateDraft(c.name(), sessions);
        String hash = hash("CREATE_DRAFT", c.studentId(), c.name(), c.description(), sessions);
        store.lockStudentAndActor(c.studentId(), c.actorId());
        Optional<Receipt> prior = receipt(c.actorId(), c.commandKey(), "CREATE_DRAFT", hash);
        if (prior.isPresent()) return replay(prior.get(), c.actorId(), c.studentId());
        var context = requireContext(c.studentId());
        DecisionOwnerType owner = authorizeDraftOwner(c.studentId(), c.actorId(), context);
        validateExercises(sessions.stream().flatMap(s -> s.prescriptions().stream())
                .map(ExercisePrescription::exerciseVariationId).toList());
        Instant at = store.databaseNow();
        Successor created = store.createDraft(c.studentId(), context.periodId(), c.actorId(), owner,
                c.name().trim(), c.description(), at, sessions);
        recordAudit(c.actorId(), "WORKOUT_PLAN_DRAFT_CREATED", created.planId(), at);
        store.saveReceipt(new Receipt(c.actorId(), c.commandKey(), "CREATE_DRAFT", hash, created.planId(),
                created.versionId(), null, "DRAFT", 0L, at, "{}", at));
        return new CommandResult(created.planId(), created.versionId(), 1, WorkoutPlanStatus.DRAFT, 0, at, false);
    }

    @Override public CommandResult updateDraft(UpdateDraftCommand c) {
        requireBase(c.actorId(), c.commandKey());
        if (c.planId() == null) throw failure(WorkoutPlanError.VALIDATION_FAILED);
        List<WorkoutSessionTemplate> sessions = c.sessions() == null ? List.of() : List.copyOf(c.sessions());
        validateDraft(c.name(), sessions);
        String hash = hash("UPDATE_DRAFT", c.planId(), c.expectedVersion(), c.name(), c.description(), sessions);
        UUID student = store.findPlanStudent(c.planId());
        store.lockStudentAndActor(student, c.actorId());
        Optional<Receipt> prior = receipt(c.actorId(), c.commandKey(), "UPDATE_DRAFT", hash);
        if (prior.isPresent()) return replay(prior.get(), c.actorId(), student);
        var context = requireContext(student);
        WorkoutPlan plan = store.lockPlan(c.planId());
        authorizeStrategic(plan, c.actorId(), context);
        requireVersion(plan.version(), c.expectedVersion());
        if (plan.status() != WorkoutPlanStatus.DRAFT) throw failure(WorkoutPlanError.WORKOUT_PLAN_IMMUTABLE);
        OpenVersion version = store.lockOpenVersion(plan.id());
        validateExercises(sessions.stream().flatMap(s -> s.prescriptions().stream())
                .map(ExercisePrescription::exerciseVariationId).toList());
        Instant at = store.databaseNow();
        store.updateDraft(plan.id(), version.id(), c.actorId(), c.expectedVersion(), c.name().trim(),
                c.description(), at, sessions);
        recordAudit(c.actorId(), "WORKOUT_PLAN_DRAFT_UPDATED", plan.id(), at);
        store.saveReceipt(new Receipt(c.actorId(), c.commandKey(), "UPDATE_DRAFT", hash, plan.id(), version.id(),
                null, "DRAFT", c.expectedVersion() + 1, at, "{}", at));
        return new CommandResult(plan.id(), version.id(), version.versionNumber(), WorkoutPlanStatus.DRAFT,
                c.expectedVersion() + 1, at, false);
    }

    @Override public CommandResult activate(ActivateCommand c) {
        requireBase(c.actorId(), c.commandKey());
        String hash = hash("ACTIVATE", c.planId(), c.expectedVersion());
        UUID student = store.findPlanStudent(c.planId());
        store.lockStudentAndActor(student, c.actorId());
        Optional<Receipt> prior = receipt(c.actorId(), c.commandKey(), "ACTIVATE", hash);
        if (prior.isPresent()) return replay(prior.get(), c.actorId(), student);
        var context = requireContext(student);
        WorkoutPlan plan = store.lockPlan(c.planId());
        authorizeStrategic(plan, c.actorId(), context);
        requireVersion(plan.version(), c.expectedVersion());
        if (plan.status() != WorkoutPlanStatus.DRAFT) lifecycle();
        if (store.hasAnotherActivePlan(student, plan.id()))
            throw failure(WorkoutPlanError.ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS);
        OpenVersion version = store.lockOpenVersion(plan.id());
        validateExercises(store.exerciseVariationIds(version.id()));
        Instant at = store.databaseNow();
        store.activate(plan.id(), version.id(), c.actorId(), c.expectedVersion(), at);
        recordAudit(c.actorId(), "WORKOUT_PLAN_ACTIVATED", plan.id(), at);
        store.saveReceipt(new Receipt(c.actorId(), c.commandKey(), "ACTIVATE", hash, plan.id(), version.id(), null,
                "ACTIVE", c.expectedVersion() + 1, at, "{}", at));
        return new CommandResult(plan.id(), version.id(), version.versionNumber(), WorkoutPlanStatus.ACTIVE,
                c.expectedVersion() + 1, at, false);
    }

    @Override public CommandResult transition(TransitionCommand c) {
        requireBase(c.actorId(), c.commandKey());
        if (c.target() == null) throw failure(WorkoutPlanError.VALIDATION_FAILED);
        String hash = hash("TRANSITION", c.planId(), c.expectedVersion(), c.target(), c.reason());
        UUID student = store.findPlanStudent(c.planId());
        store.lockStudentAndActor(student, c.actorId());
        Optional<Receipt> prior = receipt(c.actorId(), c.commandKey(), "TRANSITION", hash);
        if (prior.isPresent()) return replay(prior.get(), c.actorId(), student);
        var context = requireContext(student);
        WorkoutPlan plan = store.lockPlan(c.planId());
        authorizeLifecycle(plan, c.actorId(), context, c.target());
        requireVersion(plan.version(), c.expectedVersion());
        plan.transitionTo(c.target());
        if (c.target() == WorkoutPlanStatus.ACTIVE && store.hasAnotherActivePlan(student, plan.id()))
            throw failure(WorkoutPlanError.ACTIVE_WORKOUT_PLAN_ALREADY_EXISTS);
        Instant at = store.databaseNow();
        store.transition(plan.id(), c.actorId(), c.expectedVersion(), plan.status(), c.target(), c.reason(), at);
        recordAudit(c.actorId(), "WORKOUT_PLAN_" + c.target().name(), plan.id(), at);
        store.saveReceipt(new Receipt(c.actorId(), c.commandKey(), "TRANSITION", hash, plan.id(), null, null,
                c.target().name(), c.expectedVersion() + 1, at, "{}", at));
        return new CommandResult(plan.id(), null, null, c.target(), c.expectedVersion() + 1, at, false);
    }

    @Override public CommandResult publishVersion(PublishVersionCommand c) {
        requireBase(c.actorId(), c.commandKey());
        List<WorkoutSessionTemplate> sessions = c.sessions() == null ? List.of() : List.copyOf(c.sessions());
        validateBlueprint(c.reason(), sessions);
        String hash = hash("PUBLISH", c.planId(), c.expectedVersion(), c.reason(), c.summary(), sessions);
        UUID student = store.findPlanStudent(c.planId());
        store.lockStudentAndActor(student, c.actorId());
        Optional<Receipt> prior = receipt(c.actorId(), c.commandKey(), "PUBLISH", hash);
        if (prior.isPresent()) return replay(prior.get(), c.actorId(), student);
        var context = requireContext(student);
        WorkoutPlan plan = store.lockPlan(c.planId());
        authorizeStrategic(plan, c.actorId(), context);
        requireVersion(plan.version(), c.expectedVersion());
        if (plan.status() != WorkoutPlanStatus.ACTIVE && plan.status() != WorkoutPlanStatus.PAUSED) lifecycle();
        OpenVersion previous = store.lockOpenVersion(plan.id());
        List<UUID> variationIds = sessions.stream().flatMap(s -> s.prescriptions().stream())
                .map(ExercisePrescription::exerciseVariationId).toList();
        validateExercises(variationIds);
        Instant boundary = store.databaseNow();
        UUID versionId = store.publish(plan.id(), previous, c.actorId(), c.expectedVersion(), boundary,
                c.reason(), c.summary(), sessions);
        recordAudit(c.actorId(), "WORKOUT_PLAN_VERSION_PUBLISHED", plan.id(), boundary);
        store.saveReceipt(new Receipt(c.actorId(), c.commandKey(), "PUBLISH", hash, plan.id(), versionId, null,
                plan.status().name(), c.expectedVersion() + 1, boundary, "{}", boundary));
        return new CommandResult(plan.id(), versionId, previous.versionNumber() + 1, plan.status(),
                c.expectedVersion() + 1, boundary, false);
    }

    @Override public CommandResult createStudentSuccessor(CreateStudentSuccessorCommand c) {
        requireBase(c.actorId(), c.commandKey());
        if (c.sourcePlanId() == null || c.sourceVersionId() == null || c.name() == null
                || c.name().isBlank() || c.name().length() > 200)
            throw failure(WorkoutPlanError.VALIDATION_FAILED);
        String hash = hash("SUCCESSOR", c.sourcePlanId(), c.sourceVersionId(), c.name());
        UUID student = store.findPlanStudent(c.sourcePlanId());
        if (!student.equals(c.actorId())) throw failure(WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED);
        store.lockStudentAndActor(student, c.actorId());
        Optional<Receipt> prior = receipt(c.actorId(), c.commandKey(), "SUCCESSOR", hash);
        if (prior.isPresent()) return replay(prior.get(), c.actorId(), student);
        var context = requireContext(student);
        if (context.mode() != CurrentCoachingContextQuery.Mode.SELF_DIRECTED)
            throw failure(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED);
        WorkoutPlan source = store.lockPlan(c.sourcePlanId());
        if (source.decisionOwnerType() != DecisionOwnerType.TRAINER)
            throw failure(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED);
        OpenVersion current = store.lockOpenVersion(source.id());
        if (!current.id().equals(c.sourceVersionId())) {
            // A historical locked version is still valid lineage; persistence validates it during copy.
        }
        Instant at = store.databaseNow();
        Successor successor = store.createStudentSuccessor(source.id(), c.sourceVersionId(), student,
                context.periodId(), c.name(), at);
        recordAudit(c.actorId(), "WORKOUT_PLAN_SUCCESSOR_CREATED", successor.planId(), at);
        store.saveReceipt(new Receipt(c.actorId(), c.commandKey(), "SUCCESSOR", hash, successor.planId(),
                successor.versionId(), null, "DRAFT", 0L, at, "{}", at));
        return new CommandResult(successor.planId(), successor.versionId(), 1,
                WorkoutPlanStatus.DRAFT, 0, at, false);
    }

    @Override public OccurrenceResult adjustOccurrence(AdjustOccurrenceCommand c) {
        requireBase(c.actorId(), c.commandKey());
        if (c.type() == null || c.afterJson() == null || c.reason() == null || c.reason().isBlank())
            throw failure(WorkoutPlanError.VALIDATION_FAILED);
        validateJson(c.beforeJson());
        validateJson(c.afterJson());
        String hash = hash("ADJUST", c.plannedWorkoutId(), c.expectedVersion(), c.type(),
                c.plannedSessionExerciseId(), c.replacementVariationId(), c.beforeJson(), c.afterJson(), c.reason());
        UUID student = store.findOccurrenceStudent(c.plannedWorkoutId());
        store.lockStudentAndActor(student, c.actorId());
        Optional<Receipt> prior = receipt(c.actorId(), c.commandKey(), "ADJUST", hash);
        if (prior.isPresent()) {
            Receipt r = prior.get(); authorizeRead(c.actorId(), student);
            return new OccurrenceResult(r.plannedWorkoutId(), null, Objects.requireNonNullElse(r.resultingVersion(), 0L), true);
        }
        requireContext(student);
        Occurrence occurrence = store.lockOccurrence(c.plannedWorkoutId());
        authorizeOccurrenceMutation(c.actorId(), student);
        if (occurrence.version() != c.expectedVersion()) throw failure(WorkoutPlanError.PLANNED_WORKOUT_VERSION_CONFLICT);
        if (c.type() == WorkoutSessionAdjustment.Type.EXERCISE_SWAP) {
            if (c.plannedSessionExerciseId() == null || c.replacementVariationId() == null)
                throw failure(WorkoutPlanError.VALIDATION_FAILED);
            validateExercises(List.of(c.replacementVariationId()));
        } else if (c.replacementVariationId() != null) throw failure(WorkoutPlanError.VALIDATION_FAILED);
        Instant at = store.databaseNow();
        UUID adjustment = store.appendAdjustment(occurrence, c.actorId(), c.type(), c.plannedSessionExerciseId(),
                c.replacementVariationId(), c.beforeJson(), c.afterJson(), c.reason(), c.expectedVersion(), at);
        recordAudit(c.actorId(), "PLANNED_WORKOUT_ADJUSTED", occurrence.id(), at);
        store.saveReceipt(new Receipt(c.actorId(), c.commandKey(), "ADJUST", hash, occurrence.planId(),
                occurrence.planVersionId(), occurrence.id(), null, c.expectedVersion() + 1, at, "{}", at));
        return new OccurrenceResult(occurrence.id(), adjustment, c.expectedVersion() + 1, false);
    }

    private CurrentCoachingContextQuery.CurrentCoachingContext requireContext(UUID student) {
        return contexts.findEffectiveContext(student, true).orElseThrow(() ->
                failure(WorkoutPlanError.COACHING_PERIOD_REQUIRED));
    }

    private void authorizeStrategic(WorkoutPlan plan, UUID actor,
                                    CurrentCoachingContextQuery.CurrentCoachingContext context) {
        if (actor.equals(plan.studentId())) {
            if (plan.requiresStudentSuccessor(actor)) throw failure(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED);
            if (plan.decisionOwnerType() != DecisionOwnerType.STUDENT
                    || context.mode() != CurrentCoachingContextQuery.Mode.SELF_DIRECTED)
                throw failure(WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED);
            return;
        }
        trainerAuthority(actor, plan.studentId(), DataAccessLevel.MANAGE);
        if (plan.decisionOwnerType() != DecisionOwnerType.TRAINER || !actor.equals(plan.decisionOwnerId()))
            throw failure(WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED);
    }

    private DecisionOwnerType authorizeDraftOwner(UUID student, UUID actor,
                                                   CurrentCoachingContextQuery.CurrentCoachingContext context) {
        if (context.mode() == CurrentCoachingContextQuery.Mode.SELF_DIRECTED) {
            if (!actor.equals(student)) throw failure(WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED);
            return DecisionOwnerType.STUDENT;
        }
        if (context.trainerId() == null || !actor.equals(context.trainerId()))
            throw failure(WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED);
        trainerAuthority(actor, student, DataAccessLevel.MANAGE);
        return DecisionOwnerType.TRAINER;
    }

    private void authorizeLifecycle(WorkoutPlan plan, UUID actor,
                                    CurrentCoachingContextQuery.CurrentCoachingContext context,
                                    WorkoutPlanStatus target) {
        if (actor.equals(plan.studentId()) && plan.decisionOwnerType() == DecisionOwnerType.TRAINER) {
            if (context.mode() != CurrentCoachingContextQuery.Mode.SELF_DIRECTED)
                throw failure(WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED);
            if (target != WorkoutPlanStatus.COMPLETED && target != WorkoutPlanStatus.ARCHIVED)
                throw failure(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED);
            return;
        }
        authorizeStrategic(plan, actor, context);
    }

    private void authorizeOccurrenceMutation(UUID actor, UUID student) {
        if (!actor.equals(student)) trainerAuthority(actor, student, DataAccessLevel.MANAGE);
    }

    private void authorizeRead(UUID actor, UUID student) {
        if (!actor.equals(student)) trainerAuthority(actor, student, DataAccessLevel.VIEW);
    }

    private void trainerAuthority(UUID actor, UUID student, DataAccessLevel level) {
        accessPolicy.verifyCurrentTrainer(actor, student, level);
    }

    private void validateExercises(List<UUID> ids) {
        long requested = ids.stream().distinct().count();
        if (exercises.lockAuthoringReferences(ids).size() != requested)
            throw failure(WorkoutPlanError.WORKOUT_PLAN_EXERCISE_UNAVAILABLE);
    }

    private void validateBlueprint(String reason, List<WorkoutSessionTemplate> sessions) {
        if (sessions.isEmpty() || reason == null || reason.isBlank() || reason.length() > 100)
            throw failure(WorkoutPlanError.VALIDATION_FAILED);
        validateSessions(sessions);
    }

    private void validateDraft(String name, List<WorkoutSessionTemplate> sessions) {
        if (name == null || name.isBlank() || name.length() > 200)
            throw failure(WorkoutPlanError.VALIDATION_FAILED);
        validateSessions(sessions);
    }

    private void validateSessions(List<WorkoutSessionTemplate> sessions) {
        Set<String> sessionKeys = new HashSet<>();
        for (WorkoutSessionTemplate session : sessions) {
            if (session.name().length() > 180 || (session.focus() != null && session.focus().length() > 160)
                    || (session.estimatedDurationMinutes() != null && session.estimatedDurationMinutes() <= 0)
                    || !sessionKeys.add(session.weekNumber() + ":" + session.dayNumber() + ":" + session.sequenceNumber()))
                throw failure(WorkoutPlanError.VALIDATION_FAILED);
            Set<Integer> exerciseSequences = new HashSet<>();
            for (ExercisePrescription exercise : session.prescriptions()) {
                if (!exerciseSequences.add(exercise.sequenceNumber()))
                    throw failure(WorkoutPlanError.VALIDATION_FAILED);
            }
        }
    }

    private void validateJson(String value) {
        if (value == null) return;
        try { objectMapper.readTree(value); }
        catch (Exception invalid) { throw failure(WorkoutPlanError.VALIDATION_FAILED); }
    }

    private Optional<Receipt> receipt(UUID actor, String key, String name, String hash) {
        Optional<Receipt> receipt = store.lockReceipt(actor, key);
        if (receipt.isPresent() && (!name.equals(receipt.get().commandName()) || !hash.equals(receipt.get().payloadHash())))
            throw failure(WorkoutPlanError.WORKOUT_PLAN_IDEMPOTENCY_CONFLICT);
        return receipt;
    }

    private CommandResult replay(Receipt r, UUID actor, UUID student) {
        authorizeRead(actor, student);
        Integer versionNumber = r.planVersionId() == null ? null
                : store.findVersionNumber(r.planId(), r.planVersionId());
        return new CommandResult(r.planId(), r.planVersionId(), versionNumber, WorkoutPlanStatus.valueOf(r.status()),
                Objects.requireNonNullElse(r.resultingVersion(), 0L), r.effectiveAt(), true);
    }

    private static void requireBase(UUID actor, String key) {
        if (actor == null || key == null || key.isBlank() || key.length() > 120)
            throw failure(WorkoutPlanError.VALIDATION_FAILED);
    }
    private static void requireVersion(long actual, long expected) {
        if (expected < 0 || actual != expected) throw failure(WorkoutPlanError.WORKOUT_PLAN_VERSION_CONFLICT);
    }
    private static void lifecycle() { throw failure(WorkoutPlanError.WORKOUT_PLAN_LIFECYCLE_CONFLICT); }
    private static WorkoutPlanFailure failure(WorkoutPlanError error) { return new WorkoutPlanFailure(error, error.name()); }

    private void recordAudit(UUID actor, String action, UUID target, Instant at) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole("WORKOUT_PLAN_ACTOR")
                .action(action).targetType("WORKOUT_PLAN").targetId(target)
                .beforeDataJson("{}").afterDataJson("{}").occurredAt(at).build());
    }

    private static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String normalized = Arrays.stream(values).map(v -> Objects.toString(v, "<null>"))
                    .reduce((a, b) -> a + "\u001f" + b).orElse("");
            return HexFormat.of().formatHex(digest.digest(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
