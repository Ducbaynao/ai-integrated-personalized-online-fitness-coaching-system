package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import tools.jackson.databind.JsonNode;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.ExercisePrescription;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

final class WorkoutPlanDtos {
    private WorkoutPlanDtos() {}

    record CreateDraftRequest(
            @NotNull UUID studentId,
            @NotBlank @Size(max = 200) String name,
            String description,
            @NotNull List<@Valid SessionRequest> sessions,
            @NotBlank @Size(max = 120) String commandKey
    ) {
        WorkoutPlanCommandUseCase.CreateDraftCommand toCommand(UUID actorId) {
            return new WorkoutPlanCommandUseCase.CreateDraftCommand(studentId, actorId, name, description,
                    sessions.stream().map(SessionRequest::toDomain).toList(), commandKey);
        }
    }

    record UpdateDraftRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 200) String name,
            String description,
            @NotNull List<@Valid SessionRequest> sessions,
            @NotBlank @Size(max = 120) String commandKey
    ) {}

    record ActivateRequest(@NotNull @PositiveOrZero Long expectedVersion,
                           @NotBlank @Size(max = 120) String commandKey) {}

    record TransitionRequest(@NotNull @PositiveOrZero Long expectedVersion,
                             @NotNull WorkoutPlanStatus target,
                             String reason,
                             @NotBlank @Size(max = 120) String commandKey) {}

    record PublishVersionRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotBlank @Size(max = 100) String reason,
            String summary,
            @NotEmpty List<@Valid SessionRequest> sessions,
            @NotBlank @Size(max = 120) String commandKey
    ) {}

    record CreateSuccessorRequest(
            @NotNull UUID sourcePlanId,
            @NotNull UUID sourceVersionId,
            @NotBlank @Size(max = 200) String name,
            @NotBlank @Size(max = 120) String commandKey
    ) {}

    record AdjustmentRequest(
            @NotNull @PositiveOrZero Long expectedVersion,
            @NotNull WorkoutSessionAdjustment.Type type,
            UUID plannedSessionExerciseId,
            UUID replacementVariationId,
            JsonNode beforeValue,
            @NotNull JsonNode afterValue,
            @NotBlank String reason,
            @NotBlank @Size(max = 120) String commandKey
    ) {}

    record SessionRequest(
            @Min(1) int weekNumber,
            @Min(1) int dayNumber,
            @Min(1) int sequenceNumber,
            @NotBlank @Size(max = 180) String name,
            @Size(max = 160) String focus,
            @Positive Integer estimatedDurationMinutes,
            String notes,
            @NotNull List<@Valid PrescriptionRequest> prescriptions
    ) {
        WorkoutSessionTemplate toDomain() {
            return new WorkoutSessionTemplate(null, weekNumber, dayNumber, sequenceNumber, name, focus,
                    estimatedDurationMinutes, notes,
                    prescriptions.stream().map(PrescriptionRequest::toDomain).toList());
        }
    }

    record PrescriptionRequest(
            @NotNull UUID exerciseVariationId,
            @Min(1) int sequenceNumber,
            @Positive Integer targetSets,
            @PositiveOrZero Integer targetRepsMin,
            @PositiveOrZero Integer targetRepsMax,
            @PositiveOrZero BigDecimal targetLoad,
            @PositiveOrZero Integer restSeconds,
            @PositiveOrZero Integer durationSeconds,
            String instructions
    ) {
        ExercisePrescription toDomain() {
            return new ExercisePrescription(null, exerciseVariationId, sequenceNumber, targetSets,
                    targetRepsMin, targetRepsMax, targetLoad, restSeconds, durationSeconds, instructions);
        }
    }

    record CommandResponse(UUID planId, UUID planVersionId, Integer planVersionNumber,
                           WorkoutPlanStatus status, long aggregateVersion, Instant effectiveAt,
                           boolean replayed) {
        static CommandResponse from(WorkoutPlanCommandUseCase.CommandResult result) {
            return new CommandResponse(result.planId(), result.planVersionId(), result.planVersionNumber(),
                    result.status(), result.aggregateVersion(), result.effectiveAt(), result.replayed());
        }
    }

    record AdjustmentResponse(UUID plannedWorkoutId, UUID adjustmentId, long occurrenceVersion,
                              boolean replayed) {
        static AdjustmentResponse from(WorkoutPlanCommandUseCase.OccurrenceResult result) {
            return new AdjustmentResponse(result.plannedWorkoutId(), result.adjustmentId(),
                    result.occurrenceVersion(), result.replayed());
        }
    }
}
