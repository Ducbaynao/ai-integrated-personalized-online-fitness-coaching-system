package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface WorkoutPlanCommandUseCase {
    CommandResult createDraft(CreateDraftCommand command);
    CommandResult updateDraft(UpdateDraftCommand command);
    CommandResult activate(ActivateCommand command);
    CommandResult transition(TransitionCommand command);
    CommandResult publishVersion(PublishVersionCommand command);
    CommandResult createStudentSuccessor(CreateStudentSuccessorCommand command);
    OccurrenceResult adjustOccurrence(AdjustOccurrenceCommand command);

    record CreateDraftCommand(UUID studentId, UUID actorId, String name, String description,
                              List<WorkoutSessionTemplate> sessions, String commandKey) {}
    record UpdateDraftCommand(UUID planId, UUID actorId, long expectedVersion, String name,
                              String description, List<WorkoutSessionTemplate> sessions, String commandKey) {}
    record ActivateCommand(UUID planId, UUID actorId, long expectedVersion, String commandKey) {}
    record TransitionCommand(UUID planId, UUID actorId, long expectedVersion, WorkoutPlanStatus target,
                             String reason, String commandKey) {}
    record PublishVersionCommand(UUID planId, UUID actorId, long expectedVersion, String commandKey,
                                 String reason, String summary, List<WorkoutSessionTemplate> sessions) {}
    record CreateStudentSuccessorCommand(UUID sourcePlanId, UUID sourceVersionId, UUID actorId,
                                         String name, String commandKey) {}
    record AdjustOccurrenceCommand(UUID plannedWorkoutId, UUID actorId, long expectedVersion,
                                   String commandKey, WorkoutSessionAdjustment.Type type,
                                   UUID plannedSessionExerciseId, UUID replacementVariationId,
                                   String beforeJson, String afterJson, String reason) {}
    record CommandResult(UUID planId, UUID planVersionId, Integer planVersionNumber,
                         WorkoutPlanStatus status, long aggregateVersion, Instant effectiveAt, boolean replayed) {}
    record OccurrenceResult(UUID plannedWorkoutId, UUID adjustmentId, long occurrenceVersion, boolean replayed) {}
}
