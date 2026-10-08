package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort.ExecutionRecord;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort.ExerciseRecord;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class WorkoutExecutionQueryServiceTest {
    private final WorkoutExecutionReadPort store = mock(WorkoutExecutionReadPort.class);
    private final WorkoutExecutionAccessPolicy access = mock(WorkoutExecutionAccessPolicy.class);
    private final ExerciseReferenceQuery exercises = mock(ExerciseReferenceQuery.class);
    private final WorkoutExecutionQueryService service = new WorkoutExecutionQueryService(store, access, exercises);

    @Test
    void currentHasStableNotFoundSemantics() {
        UUID student = UUID.randomUUID();
        when(store.findCurrent(student)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.current(student))
                .isInstanceOfSatisfying(WorkoutExecutionFailure.class, failure ->
                        assertThat(failure.error()).isEqualTo(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND));
    }

    @Test
    void unauthorizedDetailIsConcealedInsteadOfReturningAccessDenied() {
        UUID actor = UUID.randomUUID();
        ExecutionRecord row = execution(UUID.randomUUID(), UUID.randomUUID(), List.of());
        when(store.findDetail(row.executionId())).thenReturn(Optional.of(row));
        when(access.canRead(actor, row.studentId(), row.performedStartedAt())).thenReturn(false);

        assertThatThrownBy(() -> service.detail(actor, row.executionId()))
                .isInstanceOfSatisfying(WorkoutExecutionFailure.class, failure ->
                        assertThat(failure.error()).isEqualTo(WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND));
    }

    @Test
    void unauthorizedTargetHistoryIsAnEmptyNonLeakingPage() {
        UUID actor = UUID.randomUUID(); UUID student = UUID.randomUUID();
        when(access.historyWindow(actor, student)).thenReturn(Optional.empty());

        var page = service.history(actor, student, 2, 10);

        assertThat(page.items()).isEmpty();
        assertThat(page.totalItems()).isZero();
        assertThat(page.totalPages()).isZero();
        verifyNoInteractions(store);
    }

    @Test
    void trainerWindowIsPassedToPersistenceBeforePagingAndExerciseIdsArePreserved() {
        UUID actor = UUID.randomUUID(); UUID student = UUID.randomUUID();
        UUID prescribed = UUID.randomUUID(); UUID actual = UUID.randomUUID();
        UUID canonical = UUID.randomUUID();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant until = Instant.parse("2026-02-01T00:00:00Z");
        ExerciseRecord exercise = new ExerciseRecord(UUID.randomUUID(), UUID.randomUUID(), prescribed, actual,
                "Substitution", 1, 3, 8, 12, null, null, null, null, 60, null,
                null, null, null, null, null, List.of());
        ExecutionRecord row = execution(UUID.randomUUID(), student, List.of(exercise));
        when(access.historyWindow(actor, student)).thenReturn(Optional.of(
                new WorkoutExecutionAccessPolicy.HistoryWindow(from, until)));
        when(store.findHistory(student, from, until, 0, 1)).thenReturn(
                new WorkoutExecutionReadPort.PageRecord(List.of(row), 2));
        when(exercises.resolveHistorical(prescribed)).thenReturn(Optional.of(
                new ExerciseReferenceQuery.HistoricalReference(prescribed, UUID.randomUUID(), "Prescribed",
                        "Variant", ExerciseReferenceQuery.PresentationState.ARCHIVED, canonical, "Canonical")));
        when(exercises.resolveHistorical(actual)).thenReturn(Optional.empty());

        var page = service.history(actor, student, 0, 1);

        assertThat(page.totalItems()).isEqualTo(2);
        assertThat(page.totalPages()).isEqualTo(2);
        var projected = page.items().getFirst().exercises().getFirst();
        assertThat(projected.prescribedVariationId()).isEqualTo(prescribed);
        assertThat(projected.prescribedVariation().state()).isEqualTo("ARCHIVED");
        assertThat(projected.prescribedVariation().canonicalExerciseId()).isEqualTo(canonical);
        assertThat(projected.actualVariationId()).isEqualTo(actual);
        assertThat(projected.actualVariation().state()).isEqualTo("UNAVAILABLE");
    }

    @Test
    void legacyMissingPrescribedVariationRemainsUnknown() {
        ExecutionRecord row = execution(UUID.randomUUID(), UUID.randomUUID(), List.of(new ExerciseRecord(
                UUID.randomUUID(), null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, List.of())));
        when(store.findDetail(row.executionId())).thenReturn(Optional.of(row));
        when(access.canRead(row.studentId(), row.studentId(), row.performedStartedAt())).thenReturn(true);

        var detail = service.detail(row.studentId(), row.executionId());

        assertThat(detail.exercises().getFirst().prescribedVariation()).isNull();
        assertThat(detail.exercises().getFirst().actualVariation()).isNull();
        verifyNoInteractions(exercises);
    }

    @Test
    void prescribedAndActualReferencesResolveIndependentlyWithoutCanonicalRewrite() {
        UUID active = UUID.randomUUID(); UUID archived = UUID.randomUUID(); UUID unavailable = UUID.randomUUID();
        UUID canonical = UUID.randomUUID(); UUID student = UUID.randomUUID();
        List<ExerciseRecord> raw = List.of(
                exercise(active, archived), exercise(archived, active), exercise(unavailable, unavailable));
        ExecutionRecord row = execution(UUID.randomUUID(), student, raw);
        when(store.findDetail(row.executionId())).thenReturn(Optional.of(row));
        when(access.canRead(student, student, row.performedStartedAt())).thenReturn(true);
        when(exercises.resolveHistorical(active)).thenReturn(Optional.of(new ExerciseReferenceQuery.HistoricalReference(
                active, UUID.randomUUID(), "Active", "Active variation",
                ExerciseReferenceQuery.PresentationState.ACTIVE, null, null)));
        when(exercises.resolveHistorical(archived)).thenReturn(Optional.of(new ExerciseReferenceQuery.HistoricalReference(
                archived, UUID.randomUUID(), "Archived", "Archived variation",
                ExerciseReferenceQuery.PresentationState.ARCHIVED, canonical, "Replacement")));
        when(exercises.resolveHistorical(unavailable)).thenReturn(Optional.empty());

        var projected = service.detail(student, row.executionId()).exercises();

        assertThat(projected.get(0).prescribedVariation().state()).isEqualTo("ACTIVE");
        assertThat(projected.get(0).actualVariation().state()).isEqualTo("ARCHIVED");
        assertThat(projected.get(0).actualVariationId()).isEqualTo(archived);
        assertThat(projected.get(0).actualVariation().canonicalExerciseId()).isEqualTo(canonical);
        assertThat(projected.get(1).prescribedVariationId()).isEqualTo(archived);
        assertThat(projected.get(1).actualVariation().state()).isEqualTo("ACTIVE");
        assertThat(projected.get(2).prescribedVariation().state()).isEqualTo("UNAVAILABLE");
        assertThat(projected.get(2).actualVariationId()).isEqualTo(unavailable);
    }

    private static ExerciseRecord exercise(UUID prescribed, UUID actual) {
        return new ExerciseRecord(UUID.randomUUID(), UUID.randomUUID(), prescribed, actual, null, 1, 1,
                null, null, null, null, null, null, null, null, null, null, null, null, null, List.of());
    }

    private static ExecutionRecord execution(UUID id, UUID student, List<ExerciseRecord> exercises) {
        return new ExecutionRecord(id, student, null, null, null, null, null, "LEGACY_REFERENCE_ONLY",
                Instant.parse("2026-01-15T00:00:00Z"), Instant.parse("2026-01-15T01:00:00Z"),
                WorkoutExecutionStatus.COMPLETED, null, "History", 0, null, null,
                null, null, null, null, exercises);
    }
}
