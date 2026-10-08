package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionQueryUseCase;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews.*;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort.ExecutionRecord;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkoutExecutionQueryService implements WorkoutExecutionQueryUseCase {
    private final WorkoutExecutionReadPort store;
    private final WorkoutExecutionAccessPolicy access;
    private final ExerciseReferenceQuery exercises;

    public WorkoutExecutionQueryService(WorkoutExecutionReadPort store, WorkoutExecutionAccessPolicy access,
                                        ExerciseReferenceQuery exercises) {
        this.store = store;
        this.access = access;
        this.exercises = exercises;
    }

    @Override
    public Detail current(UUID actorId) {
        if (actorId == null) throw invalid();
        access.requireStudentMutation(actorId, actorId);
        return store.findCurrent(actorId).map(this::project).orElseThrow(WorkoutExecutionQueryService::concealed);
    }

    @Override
    public Detail detail(UUID actorId, UUID executionId) {
        if (actorId == null || executionId == null) throw invalid();
        ExecutionRecord execution = store.findDetail(executionId).orElseThrow(WorkoutExecutionQueryService::concealed);
        if (!access.canRead(actorId, execution.studentId(), execution.performedStartedAt())) throw concealed();
        return project(execution);
    }

    @Override
    public Page history(UUID actorId, UUID studentId, int page, int size) {
        if (actorId == null || studentId == null || page < 0 || size < 1 || size > 100) throw invalid();
        var window = access.historyWindow(actorId, studentId);
        if (window.isEmpty()) return Page.empty(page, size);
        var raw = store.findHistory(studentId, window.get().from(), window.get().until(), page, size);
        int pages = raw.totalItems() == 0 ? 0 : (int) ((raw.totalItems() + size - 1) / size);
        return new Page(raw.items().stream().map(this::project).toList(), page, size, raw.totalItems(), pages);
    }

    private Detail project(ExecutionRecord value) {
        return new Detail(value.executionId(), value.studentId(), value.plannedWorkoutId(), value.planId(),
                value.planVersionId(), value.planSessionId(), value.coachingPeriodId(), value.snapshotMode(),
                value.performedStartedAt(), value.performedEndedAt(), value.status(), value.overallRpe(),
                value.sessionNote(), value.version(), value.frozenAt(), value.sourceOccurrenceVersion(),
                value.plannedStartAt(), value.originalPlannedStartAt(), value.plannedEndAt(),
                value.supervisionRequirement(), value.exercises().stream().map(raw -> new Exercise(
                raw.exerciseExecutionId(), raw.sourcePrescriptionId(), raw.prescribedVariationId(),
                raw.actualVariationId(), presentation(raw.prescribedVariationId()),
                presentation(raw.actualVariationId()), raw.substitutionReason(), raw.sequenceNumber(),
                raw.baselineSetCount(), raw.targetRepsMin(), raw.targetRepsMax(), raw.targetLoad(), raw.loadUnitId(),
                raw.targetRpe(), raw.targetRir(), raw.restSeconds(), raw.tempo(), raw.durationSeconds(),
                raw.distanceValue(), raw.distanceUnitId(), raw.instructions(), raw.note(), raw.sets().stream()
                .map(set -> new SetView(set.clientSetId(), set.baselineSetNumber(), set.setNumber(), set.setType(),
                        set.completionStatus(), set.repetitions(), set.loadValue(), set.loadUnitId(),
                        set.durationSeconds(), set.distanceValue(), set.distanceUnitId(), set.rpe(), set.rir(),
                        set.tempo(), set.restAfterSeconds(), set.note(), set.completedAt()))
                .toList())).toList());
    }

    private ExercisePresentation presentation(UUID variationId) {
        if (variationId == null) return null;
        return exercises.resolveHistorical(variationId).map(ref -> new ExercisePresentation(ref.variationId(),
                ref.exerciseId(), ref.exerciseName(), ref.variationName(), ref.state().name(),
                ref.canonicalExerciseId(), ref.canonicalExerciseName())).orElseGet(() ->
                new ExercisePresentation(variationId, null, null, null,
                        ExerciseReferenceQuery.PresentationState.UNAVAILABLE.name(), null, null));
    }

    private static WorkoutExecutionFailure invalid() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.VALIDATION_FAILED, "Invalid workout execution query");
    }

    private static WorkoutExecutionFailure concealed() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND,
                "Workout execution was not found");
    }
}
