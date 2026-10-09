package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkoutExecutionWebMapperTest {
    private final WorkoutExecutionWebMapper mapper = new WorkoutExecutionWebMapper();

    @Test
    void mapsCompleteFrozenProjectionAndIndependentHistoricalPresentations() {
        UUID prescribed = UUID.randomUUID(), actual = UUID.randomUUID(), canonical = UUID.randomUUID();
        Instant completed = Instant.parse("2026-01-01T01:00:00Z");
        var kilograms = new WorkoutExecutionViews.UnitPresentation((short) 41, "KG", "kg", "MASS");
        var pounds = new WorkoutExecutionViews.UnitPresentation((short) 77, "LB", "lb", "MASS");
        var set = new WorkoutExecutionViews.SetView(UUID.randomUUID(), 1, 1, "WORKING", "COMPLETED",
                8, new BigDecimal("50"), (short) 77, pounds, null, null, null, null,
                new BigDecimal("8"), new BigDecimal("2"), "3-1-1", 90, "Good", completed);
        var exercise = new WorkoutExecutionViews.Exercise(UUID.randomUUID(), UUID.randomUUID(), prescribed, actual,
                new WorkoutExecutionViews.ExercisePresentation(prescribed, UUID.randomUUID(), "Squat", "Back",
                        "ARCHIVED", canonical, "Replacement"),
                new WorkoutExecutionViews.ExercisePresentation(actual, UUID.randomUUID(), "Squat", "Front",
                        "ACTIVE", null, null), "Equipment", 1, 3, 6, 8,
                new BigDecimal("50"), (short) 41, kilograms, null, null, 90, null, null, null, null, null,
                "Brace", "Controlled", List.of(set));
        var source = detail("FROZEN", List.of(exercise));

        var wire = mapper.detail(source);

        assertThat(wire.execution().planId()).isEqualTo(source.planId());
        assertThat(wire.exercises().getFirst().prescribedVariationId()).isEqualTo(prescribed);
        assertThat(wire.exercises().getFirst().prescribedVariationPresentation().state()).isEqualTo("ARCHIVED");
        assertThat(wire.exercises().getFirst().prescribedVariationPresentation().canonicalExerciseId())
                .isEqualTo(canonical);
        assertThat(wire.exercises().getFirst().actualVariationId()).isEqualTo(actual);
        assertThat(wire.exercises().getFirst().actualVariationPresentation().state()).isEqualTo("ACTIVE");
        assertThat(wire.exercises().getFirst().loadUnitId()).isEqualTo((short) 41);
        assertThat(wire.exercises().getFirst().loadUnit().symbol()).isEqualTo("kg");
        assertThat(wire.exercises().getFirst().sets().getFirst().loadUnitId()).isEqualTo((short) 77);
        assertThat(wire.exercises().getFirst().sets().getFirst().loadUnit().code()).isEqualTo("LB");
        assertThat(wire.exercises().getFirst().sets().getFirst().completedAt()).isEqualTo(completed);
    }

    @Test
    void preservesLegacyUnknownsAndPageUsesSummaryOnly() {
        var legacy = new WorkoutExecutionViews.Detail(UUID.randomUUID(), UUID.randomUUID(), null, null, null,
                null, null, "LEGACY_REFERENCE_ONLY", Instant.EPOCH, Instant.EPOCH.plusSeconds(1),
                WorkoutExecutionStatus.COMPLETED, null, "Legacy", 0, null, null,
                null, null, null, null, List.of(new WorkoutExecutionViews.Exercise(UUID.randomUUID(), null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, List.of())));

        var detail = mapper.detail(legacy);
        var page = mapper.page(new WorkoutExecutionViews.Page(List.of(legacy), 0, 20, 1, 1));

        assertThat(detail.execution().planId()).isNull();
        assertThat(detail.execution().snapshotFrozenAt()).isNull();
        assertThat(detail.execution().supervisionRequirement()).isNull();
        assertThat(detail.exercises().getFirst().prescribedVariationPresentation()).isNull();
        assertThat(detail.exercises().getFirst().loadUnit()).isNull();
        assertThat(detail.exercises().getFirst().distanceUnit()).isNull();
        assertThat(page.items()).containsExactly(detail.execution());
    }

    private static WorkoutExecutionViews.Detail detail(String mode, List<WorkoutExecutionViews.Exercise> exercises) {
        Instant started = Instant.parse("2026-01-01T00:00:00Z");
        return new WorkoutExecutionViews.Detail(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), mode, started, null,
                WorkoutExecutionStatus.IN_PROGRESS, null, null, 2, started, 4L, started.minusSeconds(60),
                started.minusSeconds(120), started.plusSeconds(3600), "SELF_PERFORMABLE", exercises);
    }
}
