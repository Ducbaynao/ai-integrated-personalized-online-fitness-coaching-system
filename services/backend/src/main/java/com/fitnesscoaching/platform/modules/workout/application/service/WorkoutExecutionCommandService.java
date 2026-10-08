package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionSnapshotUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutOccurrenceExecutionStateUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionPersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionPersistencePort.Receipt;
import com.fitnesscoaching.platform.modules.workout.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

@Service
@Transactional(isolation = Isolation.REPEATABLE_READ)
public class WorkoutExecutionCommandService implements WorkoutExecutionCommandUseCase {
    private final WorkoutExecutionPersistencePort store;
    private final WorkoutExecutionSnapshotUseCase snapshots;
    private final WorkoutOccurrenceExecutionStateUseCase occurrences;
    private final WorkoutExecutionAccessPolicy access;
    private final ExerciseReferenceQuery exercises;
    private final AuditService audit;

    public WorkoutExecutionCommandService(WorkoutExecutionPersistencePort store,
                                          WorkoutExecutionSnapshotUseCase snapshots,
                                          WorkoutOccurrenceExecutionStateUseCase occurrences,
                                          WorkoutExecutionAccessPolicy access,
                                          ExerciseReferenceQuery exercises,
                                          AuditService audit) {
        this.store = store;
        this.snapshots = snapshots;
        this.occurrences = occurrences;
        this.access = access;
        this.exercises = exercises;
        this.audit = audit;
    }

    @Override
    public WorkoutExecution start(StartCommand command) {
        if (command == null || command.occurrenceId() == null || command.actorId() == null
                || command.expectedOccurrenceVersion() < 0) throw invalid();
        String key = key(command.commandKey());
        UUID studentId = occurrences.studentForOccurrence(command.occurrenceId());
        store.lockStudent(studentId);
        access.requireStudentMutation(command.actorId(), studentId);
        String payload = hash("START", command.occurrenceId(), command.expectedOccurrenceVersion());
        var replay = replay(key, command.actorId(), "START", payload);
        if (replay != null) return requiredExecution(replay.executionId());
        if (store.hasInProgress(studentId)) throw failure(WorkoutExecutionError.ACTIVE_WORKOUT_EXECUTION_EXISTS);
        var frozen = snapshots.freezeForExecution(new WorkoutExecutionSnapshotUseCase.FreezeExecutionCommand(
                command.occurrenceId(), command.actorId(), command.expectedOccurrenceVersion()));
        WorkoutExecution execution = store.create(frozen, command.actorId());
        saveReceipt(command.actorId(), key, "START", payload, command.occurrenceId(), execution,
                "SCHEDULED", frozen.frozenAt());
        record(command.actorId(), "WORKOUT_EXECUTION_STARTED", execution.id(), frozen.frozenAt());
        return execution;
    }

    @Override
    public PlannedResult skip(SkipCommand command) {
        if (command == null || command.occurrenceId() == null || command.actorId() == null
                || command.expectedOccurrenceVersion() < 0) throw invalid();
        String key = key(command.commandKey());
        UUID studentId = occurrences.studentForOccurrence(command.occurrenceId());
        store.lockStudent(studentId);
        access.requireStudentMutation(command.actorId(), studentId);
        String payload = hash("SKIP", command.occurrenceId(), command.expectedOccurrenceVersion(), command.reason());
        Receipt replay = replay(key, command.actorId(), "SKIP", payload);
        if (replay != null) return new PlannedResult(replay.plannedWorkoutId(), replay.plannedStatus(),
                replay.resultingVersion(), true);
        var result = occurrences.skip(command.occurrenceId(), command.actorId(),
                command.expectedOccurrenceVersion(), command.reason());
        Instant at = result.effectiveAt();
        store.saveReceipt(new Receipt(command.actorId(), key, "SKIP", payload, command.occurrenceId(), null,
                null, result.status(), result.version(), at, "{}", at));
        record(command.actorId(), "PLANNED_WORKOUT_SKIPPED", command.occurrenceId(), at);
        return new PlannedResult(result.occurrenceId(), result.status(), result.version(), false);
    }

    @Override
    public WorkoutExecution upsertSet(UpsertSetCommand command) {
        if (command == null || command.executionId() == null || command.exerciseExecutionId() == null
                || command.actorId() == null || command.expectedVersion() < 0 || command.set() == null) throw invalid();
        WorkoutExecution observed = requiredExecution(command.executionId());
        store.lockStudent(observed.studentId());
        access.requireStudentMutation(command.actorId(), observed.studentId());
        WorkoutExecution locked = store.lockExecution(command.executionId());
        locked.requireInProgress();
        var existing = store.findSet(command.set().clientSetId());
        if (existing.isPresent()) {
            var identity = existing.get();
            if (!identity.executionId().equals(command.executionId())
                    || !identity.exerciseId().equals(command.exerciseExecutionId()))
                throw failure(WorkoutExecutionError.WORKOUT_SET_IDENTITY_CONFLICT);
            if (sameSet(identity.set(), command.set())) return locked;
        }
        requireVersion(locked, command.expectedVersion());
        Instant at = store.databaseNow();
        if (existing.isPresent()) store.updateSet(command.executionId(), command.exerciseExecutionId(), command.set(),
                command.expectedVersion(), at);
        else store.insertSet(command.executionId(), command.exerciseExecutionId(), command.set(),
                command.expectedVersion(), at);
        record(command.actorId(), existing.isPresent() ? "WORKOUT_SET_UPDATED" : "WORKOUT_SET_CREATED",
                command.executionId(), at);
        return store.lockExecution(command.executionId());
    }

    @Override
    public WorkoutExecution substitute(SubstituteExerciseCommand command) {
        if (command == null || command.executionId() == null || command.exerciseExecutionId() == null
                || command.actorId() == null || command.actualVariationId() == null
                || command.expectedVersion() < 0) throw invalid();
        WorkoutExecution observed = requiredExecution(command.executionId());
        store.lockStudent(observed.studentId());
        access.requireStudentMutation(command.actorId(), observed.studentId());
        WorkoutExecution locked = store.lockExecution(command.executionId());
        locked.requireInProgress();
        requireVersion(locked, command.expectedVersion());
        boolean known = exercises.lockAuthoringReferences(java.util.List.of(command.actualVariationId())).stream()
                .anyMatch(reference -> reference.variationId().equals(command.actualVariationId()));
        if (!known) throw invalid();
        Instant at = store.databaseNow();
        store.substitute(command.executionId(), command.exerciseExecutionId(), command.actualVariationId(),
                command.reason(), command.expectedVersion(), at);
        record(command.actorId(), "WORKOUT_EXERCISE_SUBSTITUTED", command.executionId(), at);
        return store.lockExecution(command.executionId());
    }

    @Override public WorkoutExecution complete(TerminalCommand command) { return terminal(command, false); }
    @Override public WorkoutExecution abort(TerminalCommand command) { return terminal(command, true); }

    private WorkoutExecution terminal(TerminalCommand command, boolean abort) {
        if (command == null || command.executionId() == null || command.actorId() == null
                || command.expectedVersion() < 0) throw invalid();
        if (command.overallRpe() != null && (command.overallRpe().compareTo(BigDecimal.ONE) < 0
                || command.overallRpe().compareTo(BigDecimal.TEN) > 0)) throw invalid();
        String name = abort ? "ABORT" : "COMPLETE";
        String key = key(command.commandKey());
        WorkoutExecution observed = requiredExecution(command.executionId());
        store.lockStudent(observed.studentId());
        access.requireStudentMutation(command.actorId(), observed.studentId());
        String payload = hash(name, command.executionId(), command.expectedVersion(), command.overallRpe(),
                command.sessionNote());
        Receipt replay = replay(key, command.actorId(), name, payload);
        if (replay != null) return requiredExecution(replay.executionId());
        long occurrenceVersion = occurrences.lockForExecutionMutation(observed.plannedWorkoutId(), command.actorId());
        WorkoutExecution locked = store.lockExecution(command.executionId());
        requireVersion(locked, command.expectedVersion());
        WorkoutExecutionStatus target;
        if (abort) {
            locked.requireAbortable();
            target = WorkoutExecutionStatus.ABORTED;
        } else target = locked.deriveCompletion();
        Instant at = store.databaseNow();
        store.terminal(locked.id(), locked.status(), target, command.actorId(), command.expectedVersion(),
                command.overallRpe(), command.sessionNote(), at);
        var planned = occurrences.synchronizeTerminal(locked.plannedWorkoutId(), command.actorId(), occurrenceVersion,
                target, at);
        WorkoutExecution result = requiredExecution(locked.id());
        saveReceipt(command.actorId(), key, name, payload, locked.plannedWorkoutId(), result, planned.status(), at);
        record(command.actorId(), "WORKOUT_EXECUTION_" + target.name(), result.id(), at);
        return result;
    }

    private Receipt replay(String key, UUID actor, String name, String payload) {
        var receipt = store.lockReceipt(actor, key);
        if (receipt.isEmpty()) return null;
        Receipt value = receipt.get();
        if (!name.equals(value.commandName()) || !payload.equals(value.payloadHash()))
            throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_IDEMPOTENCY_CONFLICT);
        return value;
    }

    private void saveReceipt(UUID actor, String key, String name, String payload, UUID occurrence,
                             WorkoutExecution execution, String plannedStatus, Instant at) {
        store.saveReceipt(new Receipt(actor, key, name, payload, occurrence, execution.id(),
                execution.status().name(), plannedStatus, execution.version(), at, "{}", at));
    }

    private WorkoutExecution requiredExecution(UUID id) {
        return store.find(id).orElseThrow(() -> failure(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND));
    }

    private static void requireVersion(WorkoutExecution execution, long expected) {
        if (execution.version() != expected)
            throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_VERSION_CONFLICT);
    }

    private static boolean sameSet(SetExecution left, SetExecution right) {
        return Objects.equals(left.clientSetId(), right.clientSetId())
                && Objects.equals(left.baselineSetNumber(), right.baselineSetNumber())
                && left.setNumber() == right.setNumber()
                && Objects.equals(left.setType(), right.setType())
                && Objects.equals(left.completionStatus(), right.completionStatus())
                && Objects.equals(left.repetitions(), right.repetitions())
                && decimal(left.loadValue(), right.loadValue()) && Objects.equals(left.loadUnitId(), right.loadUnitId())
                && Objects.equals(left.durationSeconds(), right.durationSeconds())
                && decimal(left.distanceValue(), right.distanceValue())
                && Objects.equals(left.distanceUnitId(), right.distanceUnitId())
                && decimal(left.rpe(), right.rpe()) && decimal(left.rir(), right.rir())
                && Objects.equals(left.tempo(), right.tempo())
                && Objects.equals(left.restAfterSeconds(), right.restAfterSeconds())
                && Objects.equals(left.note(), right.note());
    }

    private static boolean decimal(BigDecimal left, BigDecimal right) {
        return left == null ? right == null : right != null && left.compareTo(right) == 0;
    }

    private void record(UUID actor, String action, UUID target, Instant at) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole("STUDENT").action(action)
                .targetType("WORKOUT_EXECUTION").targetId(target).occurredAt(at).build());
    }

    private static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 200) throw invalid();
        return value.trim();
    }

    private static String hash(Object... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (Object value : values) {
                String normalized = value instanceof BigDecimal decimal ? decimal.stripTrailingZeros().toPlainString()
                        : Objects.toString(value, "<null>");
                digest.update(normalized.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static WorkoutExecutionFailure invalid() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.VALIDATION_FAILED, "Invalid workout execution command");
    }
    private static WorkoutExecutionFailure failure(WorkoutExecutionError error) {
        return new WorkoutExecutionFailure(error, error.name());
    }
}
