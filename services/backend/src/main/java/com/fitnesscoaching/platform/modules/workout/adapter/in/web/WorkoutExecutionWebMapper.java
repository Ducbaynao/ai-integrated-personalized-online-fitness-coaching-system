package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.adapter.in.web.WorkoutExecutionDtos.*;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews;
import org.springframework.stereotype.Component;

@Component
class WorkoutExecutionWebMapper {
    WorkoutExecutionPage page(WorkoutExecutionViews.Page source) {
        return new WorkoutExecutionPage(source.items().stream().map(this::summary).toList(),
                source.page(), source.size(), source.totalItems(), source.totalPages());
    }

    WorkoutExecutionDetail detail(WorkoutExecutionViews.Detail source) {
        return new WorkoutExecutionDetail(summary(source), source.exercises().stream().map(this::exercise).toList());
    }

    private WorkoutExecutionSummary summary(WorkoutExecutionViews.Detail source) {
        return new WorkoutExecutionSummary(source.executionId(), source.studentId(), source.plannedWorkoutId(),
                source.planId(), source.planVersionId(), source.planSessionId(), source.coachingPeriodId(),
                source.snapshotMode(), source.plannedStartAt(), source.originalPlannedStartAt(),
                source.plannedEndAt(), source.supervisionRequirement(), source.frozenAt(),
                source.sourceOccurrenceVersion(), source.performedStartedAt(), source.performedEndedAt(),
                source.status(), source.overallRpe(), source.sessionNote(), source.version());
    }

    private WorkoutExerciseExecution exercise(WorkoutExecutionViews.Exercise source) {
        return new WorkoutExerciseExecution(source.exerciseExecutionId(), source.sourcePrescriptionId(),
                source.prescribedVariationId(), presentation(source.prescribedVariation()),
                source.actualVariationId(), presentation(source.actualVariation()), source.substitutionReason(),
                source.sequenceNumber(), source.baselineSetCount(), source.targetRepsMin(), source.targetRepsMax(),
                source.targetLoad(), source.loadUnitId(), unit(source.loadUnit()), source.targetRpe(),
                source.targetRir(), source.restSeconds(), source.tempo(), source.durationSeconds(),
                source.distanceValue(), source.distanceUnitId(), unit(source.distanceUnit()), source.instructions(),
                source.note(), source.sets().stream().map(this::set).toList());
    }

    private ExercisePresentation presentation(WorkoutExecutionViews.ExercisePresentation source) {
        return source == null ? null : new ExercisePresentation(source.variationId(), source.exerciseId(),
                source.exerciseName(), source.variationName(), source.state(), source.canonicalExerciseId(),
                source.canonicalExerciseName());
    }

    private MeasurementUnitPresentation unit(WorkoutExecutionViews.UnitPresentation source) {
        return source == null ? null : new MeasurementUnitPresentation(source.id(), source.code(), source.symbol(),
                source.dimension());
    }

    private WorkoutSetExecution set(WorkoutExecutionViews.SetView source) {
        return new WorkoutSetExecution(source.clientSetId(), source.baselineSetNumber(), source.setNumber(),
                source.setType(), source.completionStatus(), source.repetitions(), source.loadValue(),
                source.loadUnitId(), unit(source.loadUnit()), source.durationSeconds(), source.distanceValue(),
                source.distanceUnitId(), unit(source.distanceUnit()), source.rpe(), source.rir(), source.tempo(),
                source.restAfterSeconds(), source.note(),
                source.completedAt());
    }
}
