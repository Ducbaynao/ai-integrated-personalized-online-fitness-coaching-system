package com.fitnesscoaching.platform.modules.workout.application.port.in;

import com.fitnesscoaching.platform.modules.workout.domain.SetExecution;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecution;

import java.math.BigDecimal;
import java.util.UUID;

public interface WorkoutExecutionCommandUseCase {
    WorkoutExecution start(StartCommand command);
    PlannedResult skip(SkipCommand command);
    WorkoutExecution upsertSet(UpsertSetCommand command);
    WorkoutExecution substitute(SubstituteExerciseCommand command);
    WorkoutExecution complete(TerminalCommand command);
    WorkoutExecution abort(TerminalCommand command);

    record StartCommand(UUID occurrenceId, UUID actorId, long expectedOccurrenceVersion, String commandKey) {}
    record SkipCommand(UUID occurrenceId, UUID actorId, long expectedOccurrenceVersion,
                       String reason, String commandKey) {}
    record UpsertSetCommand(UUID executionId, UUID exerciseExecutionId, UUID actorId, long expectedVersion,
                            SetExecution set) {}
    record SubstituteExerciseCommand(UUID executionId, UUID exerciseExecutionId, UUID actorId,
                                     long expectedVersion, UUID actualVariationId, String reason) {}
    record TerminalCommand(UUID executionId, UUID actorId, long expectedVersion, String commandKey,
                           BigDecimal overallRpe, String sessionNote) {}
    record PlannedResult(UUID occurrenceId, String status, long version, boolean replayed) {}
}
