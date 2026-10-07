package com.fitnesscoaching.platform.modules.workout.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WorkoutPlanDomainTest {
    @Test
    void lifecycleContainsExactlyTheApprovedTransitions() {
        assertThat(allowed(WorkoutPlanStatus.DRAFT)).containsExactlyInAnyOrder(
                WorkoutPlanStatus.ACTIVE, WorkoutPlanStatus.ARCHIVED);
        assertThat(allowed(WorkoutPlanStatus.ACTIVE)).containsExactlyInAnyOrder(
                WorkoutPlanStatus.PAUSED, WorkoutPlanStatus.COMPLETED, WorkoutPlanStatus.ARCHIVED);
        assertThat(allowed(WorkoutPlanStatus.PAUSED)).containsExactlyInAnyOrder(
                WorkoutPlanStatus.ACTIVE, WorkoutPlanStatus.COMPLETED, WorkoutPlanStatus.ARCHIVED);
        assertThat(allowed(WorkoutPlanStatus.COMPLETED)).containsExactly(WorkoutPlanStatus.ARCHIVED);
        assertThat(allowed(WorkoutPlanStatus.ARCHIVED)).isEmpty();
        for (WorkoutPlanStatus status : WorkoutPlanStatus.values()) assertThat(status.canTransitionTo(status)).isFalse();
    }

    @Test
    void transitionIncrementsAggregateVersionAndArchivedIsTerminal() {
        UUID student = UUID.randomUUID();
        WorkoutPlan draft = new WorkoutPlan(UUID.randomUUID(), student, WorkoutPlanStatus.DRAFT, 7,
                DecisionOwnerType.STUDENT, student, UUID.randomUUID(), null, null);
        WorkoutPlan active = draft.transitionTo(WorkoutPlanStatus.ACTIVE);
        assertThat(active.version()).isEqualTo(8);
        WorkoutPlan archived = active.transitionTo(WorkoutPlanStatus.ARCHIVED);
        assertThatThrownBy(() -> archived.transitionTo(WorkoutPlanStatus.ACTIVE))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.WORKOUT_PLAN_LIFECYCLE_CONFLICT);
    }

    @Test
    void trainerAuthoredPlanRequiresStudentSuccessorAndLineageMustBePaired() {
        UUID student = UUID.randomUUID();
        WorkoutPlan delivered = new WorkoutPlan(UUID.randomUUID(), student, WorkoutPlanStatus.ACTIVE, 1,
                DecisionOwnerType.TRAINER, UUID.randomUUID(), UUID.randomUUID(), null, null);
        assertThat(delivered.requiresStudentSuccessor(student)).isTrue();
        assertThatThrownBy(() -> new WorkoutPlan(UUID.randomUUID(), student, WorkoutPlanStatus.DRAFT, 0,
                DecisionOwnerType.STUDENT, student, UUID.randomUUID(), UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void minorIsStrictlyOneMaterializedOccurrence() {
        assertThat(WorkoutChangeScope.ONE_MATERIALIZED_OCCURRENCE.isMinor()).isTrue();
        assertThatThrownBy(WorkoutChangeScope.TEMPLATE_OR_FUTURE_OCCURRENCES::requireMinor)
                .isInstanceOf(WorkoutPlanFailure.class);
        assertThatThrownBy(WorkoutChangeScope.MULTIPLE_OCCURRENCES::requireMinor)
                .isInstanceOf(WorkoutPlanFailure.class);
    }

    @Test
    void prescriptionRejectsValuesOutsideExecutableSchemaRanges() {
        assertInvalidPrescription(0, null, null, null, null, null);
        assertInvalidPrescription(-1, null, null, null, null, null);
        assertInvalidPrescription(1, -1, null, null, null, null);
        assertInvalidPrescription(1, null, -1, null, null, null);
        assertInvalidPrescription(1, 2, 1, null, null, null);
        assertInvalidPrescription(1, null, null, new BigDecimal("-0.01"), null, null);
        assertInvalidPrescription(1, null, null, null, -1, null);
        assertInvalidPrescription(1, null, null, null, null, -1);
    }

    @Test
    void prescriptionAllowsZeroWhereExecutableSchemaAllowsZero() {
        ExercisePrescription prescription = new ExercisePrescription(null, UUID.randomUUID(), 1,
                1, 0, 0, BigDecimal.ZERO, 0, 0, null);

        assertThat(prescription.targetSets()).isOne();
        assertThat(prescription.targetRepsMin()).isZero();
        assertThat(prescription.targetRepsMax()).isZero();
        assertThat(prescription.targetLoad()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(prescription.restSeconds()).isZero();
        assertThat(prescription.durationSeconds()).isZero();
    }

    private static void assertInvalidPrescription(Integer sets, Integer repsMin, Integer repsMax,
                                                  BigDecimal load, Integer rest, Integer duration) {
        assertThatThrownBy(() -> new ExercisePrescription(null, UUID.randomUUID(), 1,
                sets, repsMin, repsMax, load, rest, duration, null))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.VALIDATION_FAILED);
    }

    private static EnumSet<WorkoutPlanStatus> allowed(WorkoutPlanStatus source) {
        EnumSet<WorkoutPlanStatus> result = EnumSet.noneOf(WorkoutPlanStatus.class);
        for (WorkoutPlanStatus target : WorkoutPlanStatus.values()) if (source.canTransitionTo(target)) result.add(target);
        return result;
    }
}
