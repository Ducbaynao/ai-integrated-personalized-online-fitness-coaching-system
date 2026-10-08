package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionSnapshotUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutOccurrenceExecutionStateUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionSnapshotPersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionSnapshotPersistencePort.*;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class WorkoutExecutionSnapshotService implements WorkoutExecutionSnapshotUseCase,
        WorkoutOccurrenceExecutionStateUseCase {
    private final WorkoutExecutionSnapshotPersistencePort store;
    private final WorkoutPlanPersistencePort planStore;

    public WorkoutExecutionSnapshotService(WorkoutExecutionSnapshotPersistencePort store,
                                           WorkoutPlanPersistencePort planStore) {
        this.store = store; this.planStore = planStore;
    }

    @Override
    public UUID studentForOccurrence(UUID occurrenceId) {
        if (occurrenceId == null) throw failure(WorkoutExecutionError.VALIDATION_FAILED);
        return planStore.findOccurrenceStudent(occurrenceId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public long lockForExecutionMutation(UUID occurrenceId, UUID studentActorId) {
        if (occurrenceId == null || studentActorId == null)
            throw failure(WorkoutExecutionError.VALIDATION_FAILED);
        var occurrence = planStore.lockOccurrence(occurrenceId);
        requireStudent(occurrence.studentId(), studentActorId);
        if (occurrence.executionStartedAt() == null)
            throw new WorkoutPlanFailure(WorkoutPlanError.PLANNED_WORKOUT_NOT_STARTABLE,
                    "Planned workout has not started");
        return occurrence.version();
    }

    @Override public FrozenExecutionSnapshot freezeForExecution(FreezeExecutionCommand command) {
        if (command == null || command.occurrenceId() == null || command.studentActorId() == null
                || command.expectedOccurrenceVersion() < 0) throw failure(WorkoutExecutionError.VALIDATION_FAILED);
        UUID student = planStore.findOccurrenceStudent(command.occurrenceId());
        planStore.lockStudentAndActor(student, command.studentActorId());
        SnapshotOccurrence occurrence = store.lockOccurrence(command.occurrenceId());
        requireStudent(occurrence.studentId(), command.studentActorId());
        if (!"SCHEDULED".equals(occurrence.status())) throw failure(WorkoutExecutionError.PLANNED_WORKOUT_NOT_STARTABLE);
        if (occurrence.executionStartedAt() != null) throw failure(WorkoutExecutionError.PLANNED_WORKOUT_ALREADY_STARTED);
        if (occurrence.version() != command.expectedOccurrenceVersion())
            throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_VERSION_CONFLICT);

        LinkedHashMap<UUID, Resolved> resolved = new LinkedHashMap<>();
        for (SnapshotPrescription p : store.prescriptions(occurrence.planSessionId())) resolved.put(p.id(), new Resolved(p));
        String sessionNote = null;
        List<UUID> adjustmentIds = new ArrayList<>();
        for (StoredAdjustment a : store.adjustments(occurrence.id())) {
            if (!"TYPED".equals(a.resolutionState())) throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID);
            Resolved target = a.prescriptionId() == null ? null : resolved.get(a.prescriptionId());
            if (a.prescriptionId() != null && target == null) throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID);
            switch (a.type()) {
                case LOAD -> { target.load = a.targetLoad(); target.loadUnitId = a.loadUnitId(); }
                case REPS -> { if (a.repsMin() != null) target.repsMin = a.repsMin(); if (a.repsMax() != null) target.repsMax = a.repsMax(); }
                case SETS -> target.targetSets = a.targetSets();
                case DURATION -> target.durationSeconds = a.durationSeconds();
                case ORDER -> target.sequence = a.sequence();
                case NOTE -> { if (target == null) sessionNote = a.note(); else target.note = a.note(); }
                case EXERCISE_SWAP -> target.variationId = a.replacementVariationId();
                case OTHER -> { }
            }
            adjustmentIds.add(a.id());
        }
        Set<Integer> sequences = new HashSet<>();
        List<FrozenPrescription> prescriptions = resolved.values().stream().map(value -> {
            if (value.targetSets == null || value.targetSets < 1 || !sequences.add(value.sequence))
                throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID);
            if (value.repsMin != null && value.repsMax != null && value.repsMax < value.repsMin)
                throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID);
            return value.freeze();
        }).sorted(Comparator.comparingInt(FrozenPrescription::sequenceNumber)
                .thenComparing(FrozenPrescription::prescriptionId)).toList();
        if (prescriptions.isEmpty()) throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_BASELINE_INVALID);
        Instant at = store.databaseNow();
        store.seal(occurrence.id(), occurrence.version(), at);
        return new FrozenExecutionSnapshot(occurrence.id(), occurrence.studentId(), occurrence.planId(),
                occurrence.planVersionId(), occurrence.planSessionId(), occurrence.coachingPeriodId(),
                occurrence.plannedStartAt(), occurrence.plannedEndAt(), occurrence.originalPlannedStartAt(),
                occurrence.supervisionRequirement(), at, occurrence.version() + 1, sessionNote,
                prescriptions, List.copyOf(adjustmentIds));
    }

    @Override public PlannedWorkoutOutcome skip(UUID occurrenceId, UUID actorId, long expected, String reason) {
        UUID student = planStore.findOccurrenceStudent(occurrenceId);
        planStore.lockStudentAndActor(student, actorId);
        SnapshotOccurrence occurrence = store.lockOccurrence(occurrenceId);
        requireStudent(student, actorId);
        if (occurrence.executionStartedAt() != null) throw failure(WorkoutExecutionError.PLANNED_WORKOUT_ALREADY_STARTED);
        if (occurrence.version() != expected) throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_VERSION_CONFLICT);
        Instant at = store.databaseNow();
        store.skip(occurrenceId, expected, reason, at);
        return new PlannedWorkoutOutcome(occurrenceId, "SKIPPED", expected + 1, at);
    }

    @Override public PlannedWorkoutOutcome synchronizeTerminal(UUID occurrenceId, UUID actorId, long expected,
                                                               WorkoutExecutionStatus status, Instant at) {
        SnapshotOccurrence occurrence = store.lockOccurrence(occurrenceId);
        requireStudent(occurrence.studentId(), actorId);
        if (occurrence.version() != expected || occurrence.executionStartedAt() == null)
            throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_VERSION_CONFLICT);
        String planned = status == WorkoutExecutionStatus.COMPLETED ? "COMPLETED" : "ATTEMPTED";
        store.synchronizeTerminal(occurrenceId, expected, planned, at);
        return new PlannedWorkoutOutcome(occurrenceId, planned, expected + 1, at);
    }

    private static void requireStudent(UUID student, UUID actor) {
        if (!student.equals(actor)) throw failure(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED);
    }
    private static WorkoutExecutionFailure failure(WorkoutExecutionError error) {
        return new WorkoutExecutionFailure(error, error.name());
    }

    private static final class Resolved {
        final UUID id; UUID variationId; int sequence; Integer targetSets; Integer repsMin; Integer repsMax;
        java.math.BigDecimal load; Short loadUnitId; final java.math.BigDecimal rpe; final java.math.BigDecimal rir;
        final Integer rest; final String tempo; Integer durationSeconds; final java.math.BigDecimal distance;
        final Short distanceUnit; final String instructions; String note;
        Resolved(SnapshotPrescription p) {
            id=p.id(); variationId=p.variationId(); sequence=p.sequence(); targetSets=p.targetSets();
            repsMin=p.repsMin(); repsMax=p.repsMax(); load=p.load(); loadUnitId=p.loadUnitId(); rpe=p.targetRpe();
            rir=p.targetRir(); rest=p.restSeconds(); tempo=p.tempo(); durationSeconds=p.durationSeconds();
            distance=p.distanceValue(); distanceUnit=p.distanceUnitId(); instructions=p.instructions();
        }
        FrozenPrescription freeze() { return new FrozenPrescription(id,variationId,sequence,targetSets,repsMin,repsMax,
                load,loadUnitId,rpe,rir,rest,tempo,durationSeconds,distance,distanceUnit,instructions,note); }
    }
}
