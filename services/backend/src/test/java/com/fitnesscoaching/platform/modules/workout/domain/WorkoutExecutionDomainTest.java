package com.fitnesscoaching.platform.modules.workout.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkoutExecutionDomainTest {
    @Test
    void completionIsDerivedOnlyFromPrescribedBaselineSets() {
        WorkoutExecution complete = execution(List.of(exercise(2, List.of(set(1, "COMPLETED"),
                set(2, "COMPLETED"), extra("COMPLETED")))));
        WorkoutExecution partial = execution(List.of(exercise(2, List.of(set(1, "COMPLETED"),
                set(2, "SKIPPED")))));
        WorkoutExecution none = execution(List.of(exercise(1, List.of(extra("COMPLETED")))));

        assertThat(complete.deriveCompletion()).isEqualTo(WorkoutExecutionStatus.COMPLETED);
        assertThat(partial.deriveCompletion()).isEqualTo(WorkoutExecutionStatus.PARTIALLY_COMPLETED);
        assertFailure(none::deriveCompletion, WorkoutExecutionError.WORKOUT_EXECUTION_LIFECYCLE_CONFLICT);
    }

    @Test
    void abortRequiresZeroCompletedBaselineSetsAndTerminalIsImmutable() {
        execution(List.of(exercise(1, List.of(set(1, "PLANNED"))))).requireAbortable();
        assertFailure(() -> execution(List.of(exercise(1, List.of(set(1, "COMPLETED"))))).requireAbortable(),
                WorkoutExecutionError.WORKOUT_EXECUTION_LIFECYCLE_CONFLICT);
        WorkoutExecution terminal = new WorkoutExecution(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), null, 1L, "FROZEN", Instant.now(), Instant.now().plusSeconds(1),
                WorkoutExecutionStatus.ABORTED, null, null, 1, List.of());
        assertFailure(terminal::requireInProgress, WorkoutExecutionError.WORKOUT_EXECUTION_TERMINAL);
    }

    @Test
    void setRejectsInvalidIdentityEnumsAndExecutableRanges() {
        assertFailure(() -> new SetExecution(null, UUID.randomUUID(), 0, 1, "WORKING", "COMPLETED",
                null, null, null, null, null, null, null, null, null, null, null),
                WorkoutExecutionError.VALIDATION_FAILED);
        assertFailure(() -> new SetExecution(null, UUID.randomUUID(), null, 1, "UNKNOWN", "COMPLETED",
                null, null, null, null, null, null, null, null, null, null, null),
                WorkoutExecutionError.VALIDATION_FAILED);
    }

    private static WorkoutExecution execution(List<ExerciseExecution> exercises) {
        return new WorkoutExecution(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), null, 1L, "FROZEN", Instant.now(), null, WorkoutExecutionStatus.IN_PROGRESS,
                null, null, 0, exercises);
    }

    private static ExerciseExecution exercise(int baseline, List<SetExecution> sets) {
        return new ExerciseExecution(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                null, 1, baseline, null, null, null, null, null, null, null, null,
                null, null, null, null, null, sets);
    }

    private static SetExecution set(int baseline, String status) {
        return new SetExecution(null, UUID.randomUUID(), baseline, baseline, "WORKING", status,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static SetExecution extra(String status) {
        return new SetExecution(null, UUID.randomUUID(), null, 99, "WORKING", status,
                null, null, null, null, null, null, null, null, null, null, null);
    }

    private static void assertFailure(org.assertj.core.api.ThrowableAssert.ThrowingCallable call,
                                      WorkoutExecutionError error) {
        assertThatThrownBy(call).isInstanceOf(WorkoutExecutionFailure.class)
                .extracting(value -> ((WorkoutExecutionFailure) value).error()).isEqualTo(error);
    }
}
