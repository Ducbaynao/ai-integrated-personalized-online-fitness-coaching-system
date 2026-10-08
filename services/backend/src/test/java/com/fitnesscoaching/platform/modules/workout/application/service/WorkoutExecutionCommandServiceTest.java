package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase.SkipCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase.StartCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionCommandUseCase.TerminalCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionSnapshotUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutOccurrenceExecutionStateUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkoutExecutionCommandServiceTest {
    private final WorkoutExecutionPersistencePort store = mock(WorkoutExecutionPersistencePort.class);
    private final WorkoutExecutionSnapshotUseCase snapshots = mock(WorkoutExecutionSnapshotUseCase.class);
    private final WorkoutOccurrenceExecutionStateUseCase occurrences = mock(WorkoutOccurrenceExecutionStateUseCase.class);
    private final WorkoutExecutionAccessPolicy access = mock(WorkoutExecutionAccessPolicy.class);
    private final ExerciseReferenceQuery exercises = mock(ExerciseReferenceQuery.class);
    private final AuditService audit = mock(AuditService.class);
    private final WorkoutExecutionCommandService service = new WorkoutExecutionCommandService(
            store, snapshots, occurrences, access, exercises, audit);

    @Test
    void commandKeyOverDatabaseLimitIsRejectedBeforeDependenciesForEveryIdempotentCommand() {
        UUID occurrence = UUID.randomUUID(), execution = UUID.randomUUID(), actor = UUID.randomUUID();
        String tooLong = "k".repeat(121);

        assertInvalid(() -> service.start(new StartCommand(occurrence, actor, 0, tooLong)));
        assertInvalid(() -> service.skip(new SkipCommand(occurrence, actor, 0, null, tooLong)));
        assertInvalid(() -> service.complete(new TerminalCommand(execution, actor, 0, tooLong, null, null)));
        assertInvalid(() -> service.abort(new TerminalCommand(execution, actor, 0, tooLong, null, null)));

        verifyNoInteractions(store, snapshots, occurrences, access, exercises, audit);
    }

    private static void assertInvalid(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(WorkoutExecutionFailure.class, failure ->
                assertThat(failure.error()).isEqualTo(WorkoutExecutionError.VALIDATION_FAILED));
    }
}
