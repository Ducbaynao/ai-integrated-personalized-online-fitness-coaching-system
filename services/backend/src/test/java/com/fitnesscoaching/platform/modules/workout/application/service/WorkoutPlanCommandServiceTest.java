package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.common.exception.DataSharingAccessLevelInsufficientException;
import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanCommandUseCase.ActivateCommand;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort.OpenVersion;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort.Receipt;
import com.fitnesscoaching.platform.modules.workout.domain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkoutPlanCommandServiceTest {
    @Mock WorkoutPlanPersistencePort store;
    @Mock CurrentCoachingContextQuery contexts;
    @Mock WorkoutPlanAccessPolicy accessPolicy;
    @Mock ExerciseReferenceQuery exercises;
    @Mock AuditService audit;
    WorkoutPlanCommandService service;
    UUID student; UUID actor; UUID planId; UUID periodId;

    @BeforeEach void setUp() {
        service = new WorkoutPlanCommandService(store, contexts, accessPolicy, exercises, audit, new ObjectMapper());
        student = UUID.randomUUID(); actor = student; planId = UUID.randomUUID(); periodId = UUID.randomUUID();
    }

    @Test void activatesStudentOwnedDraftInSelfDirectedContext() {
        UUID versionId = UUID.randomUUID(); UUID variationId = UUID.randomUUID(); Instant at = Instant.parse("2026-10-07T10:00:00Z");
        when(store.findPlanStudent(planId)).thenReturn(student);
        when(store.lockReceipt(student, "key")).thenReturn(Optional.empty());
        when(contexts.findEffectiveContext(student, true)).thenReturn(Optional.of(selfContext()));
        when(store.lockPlan(planId)).thenReturn(plan(DecisionOwnerType.STUDENT, student));
        when(store.hasAnotherActivePlan(student, planId)).thenReturn(false);
        when(store.lockOpenVersion(planId)).thenReturn(new OpenVersion(versionId, 1, at.minusSeconds(60)));
        when(store.exerciseVariationIds(versionId)).thenReturn(List.of(variationId));
        when(exercises.lockAuthoringReferences(List.of(variationId))).thenReturn(List.of(
                new ExerciseReferenceQuery.AuthoringReference(variationId, UUID.randomUUID())));
        when(store.databaseNow()).thenReturn(at);

        var result = service.activate(new ActivateCommand(planId, student, 0, "key"));

        assertThat(result.status()).isEqualTo(WorkoutPlanStatus.ACTIVE);
        assertThat(result.aggregateVersion()).isOne();
        verify(store).activate(planId, versionId, student, 0, at);
        verify(store).saveReceipt(any(Receipt.class));
        verify(audit).recordAudit(any());
    }

    @Test void studentCannotStrategicallyMutateTrainerAuthoredAggregate() {
        UUID trainer = UUID.randomUUID();
        when(store.findPlanStudent(planId)).thenReturn(student);
        when(store.lockReceipt(student, "key")).thenReturn(Optional.empty());
        when(contexts.findEffectiveContext(student, true)).thenReturn(Optional.of(selfContext()));
        when(store.lockPlan(planId)).thenReturn(plan(DecisionOwnerType.TRAINER, trainer));

        assertThatThrownBy(() -> service.activate(new ActivateCommand(planId, student, 0, "key")))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.WORKOUT_PLAN_SUCCESSOR_REQUIRED);
        verify(store, never()).activate(any(), any(), any(), anyLong(), any());
    }

    @Test void trainerWithViewOnlyCannotMutatePlan() {
        UUID trainer = UUID.randomUUID(); actor = trainer;
        when(store.findPlanStudent(planId)).thenReturn(student);
        when(store.lockReceipt(trainer, "key")).thenReturn(Optional.empty());
        when(contexts.findEffectiveContext(student, true)).thenReturn(Optional.of(new CurrentCoachingContextQuery.CurrentCoachingContext(
                periodId, CurrentCoachingContextQuery.Mode.HUMAN_COACH, UUID.randomUUID(), trainer,
                Instant.EPOCH, null)));
        when(store.lockPlan(planId)).thenReturn(plan(DecisionOwnerType.TRAINER, trainer));
        doThrow(new DataSharingAccessLevelInsufficientException("insufficient"))
                .when(accessPolicy).verifyCurrentTrainer(trainer, student, com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel.MANAGE);

        assertThatThrownBy(() -> service.activate(new ActivateCommand(planId, trainer, 0, "key")))
                .isInstanceOf(DataSharingAccessLevelInsufficientException.class);
    }

    @Test void mismatchedCommandKeyReuseConflictsBeforeDomainMutation() {
        when(store.findPlanStudent(planId)).thenReturn(student);
        when(store.lockReceipt(student, "key")).thenReturn(Optional.of(new Receipt(student, "key", "ACTIVATE",
                "0".repeat(64), planId, null, null, "ACTIVE", 1L, Instant.EPOCH, "{}", Instant.EPOCH)));

        assertThatThrownBy(() -> service.activate(new ActivateCommand(planId, student, 0, "key")))
                .isInstanceOf(WorkoutPlanFailure.class)
                .extracting(ex -> ((WorkoutPlanFailure) ex).error())
                .isEqualTo(WorkoutPlanError.WORKOUT_PLAN_IDEMPOTENCY_CONFLICT);
        verify(store, never()).lockPlan(any());
    }

    private WorkoutPlan plan(DecisionOwnerType owner, UUID ownerId) {
        return new WorkoutPlan(planId, student, WorkoutPlanStatus.DRAFT, 0, owner, ownerId, periodId, null, null);
    }
    private CurrentCoachingContextQuery.CurrentCoachingContext selfContext() {
        return new CurrentCoachingContextQuery.CurrentCoachingContext(periodId,
                CurrentCoachingContextQuery.Mode.SELF_DIRECTED, null, null, Instant.EPOCH, null);
    }
}
