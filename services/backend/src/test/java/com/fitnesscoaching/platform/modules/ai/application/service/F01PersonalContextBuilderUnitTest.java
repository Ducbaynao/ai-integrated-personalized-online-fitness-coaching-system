package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope.PersonalContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanSummary;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class F01PersonalContextBuilderUnitTest {

    private CurrentCoachingContextQuery coachingContextQuery;
    private GetStudentProfileUseCase studentProfileUseCase;
    private GetCurrentFitnessGoalUseCase goalUseCase;
    private WorkoutPlanQueryUseCase workoutPlanQueryUseCase;

    private ObjectProvider<GetStudentProfileUseCase> studentProfileProvider;
    private ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider;
    private ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider;

    private F01PersonalContextBuilder builder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        coachingContextQuery = mock(CurrentCoachingContextQuery.class);
        studentProfileUseCase = mock(GetStudentProfileUseCase.class);
        goalUseCase = mock(GetCurrentFitnessGoalUseCase.class);
        workoutPlanQueryUseCase = mock(WorkoutPlanQueryUseCase.class);

        studentProfileProvider = mock(ObjectProvider.class);
        goalProvider = mock(ObjectProvider.class);
        workoutPlanQueryProvider = mock(ObjectProvider.class);

        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileUseCase);
        when(goalProvider.getIfAvailable()).thenReturn(goalUseCase);
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQueryUseCase);

        builder = new F01PersonalContextBuilder(
                coachingContextQuery,
                studentProfileProvider,
                goalProvider,
                workoutPlanQueryProvider
        );
    }

    @Test
    @DisplayName("Student building personal context for self succeeds with resolved facts")
    void student_buildContext_self_success() {
        UUID studentId = UUID.randomUUID();

        StudentProfile profile = new StudentProfile(
                studentId, LocalDate.of(1995, 5, 20), Gender.MALE,
                TrainingExperienceLevel.INTERMEDIATE, BigDecimal.valueOf(18),
                3, 60, Instant.now(), Instant.now(), Instant.now()
        );
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(profile);

        FitnessGoalVersion goalVersion = new FitnessGoalVersion(
                UUID.randomUUID(), UUID.randomUUID(), 2, LocalDate.now(), LocalDate.now().plusMonths(3),
                90, Instant.now(), null, null, null, null, studentId, null, Instant.now(), null,
                null, null, null, null
        );
        FitnessGoal goal = new FitnessGoal(
                UUID.randomUUID(), studentId, "Tăng cơ giảm mỡ", GoalStatus.ACTIVE,
                studentId, Instant.now(), null, null, null, "Mục tiêu trọng tâm",
                Instant.now(), Instant.now(), goalVersion
        );
        when(goalUseCase.getCurrentFitnessGoal(studentId)).thenReturn(Optional.of(goal));

        PlanSummary planSummary = new PlanSummary(
                UUID.randomUUID(), studentId, goal.id(), null, "Hypertrophy Push Pull Legs",
                "Description", "MANUAL", WorkoutPlanStatus.ACTIVE, 1L, DecisionOwnerType.STUDENT,
                studentId, null, null, Instant.now(), Instant.now(), null, null, 1,
                Instant.now(), null, WorkoutPlanViews.ReadContext.CURRENT
        );
        PlanDetail planDetail = new PlanDetail(planSummary, null);
        when(workoutPlanQueryUseCase.findActive(studentId, studentId)).thenReturn(Optional.of(planDetail));

        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.empty());

        PersonalContext ctx = builder.buildPersonalContext(studentId, "STUDENT", null);

        assertThat(ctx.studentId()).isEqualTo(studentId);
        assertThat(ctx.coachingMode()).isEqualTo("SELF_DIRECTED");
        assertThat(ctx.trainingExperience()).isEqualTo("INTERMEDIATE");
        assertThat(ctx.activeGoal()).isNotNull();
        assertThat(ctx.activeGoal().goalType()).isEqualTo("Tăng cơ giảm mỡ");
        assertThat(ctx.activeGoal().version()).isEqualTo(2);
        assertThat(ctx.currentActivePlanName()).isEqualTo("Hypertrophy Push Pull Legs");
    }

    @Test
    @DisplayName("Student attempting to query another student's context throws 403 AiRunAccessDeniedException (T03)")
    void student_queryAnotherStudent_throwsAccessDenied_T03() {
        UUID student1 = UUID.randomUUID();
        UUID student2 = UUID.randomUUID();

        assertThatThrownBy(() -> builder.buildPersonalContext(student1, "STUDENT", student2))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Student is not authorized to query another student's personal context");
    }

    @Test
    @DisplayName("Trainer query without studentId throws 400 AiRunValidationException")
    void trainer_queryWithoutStudentId_throwsValidationException() {
        UUID trainerId = UUID.randomUUID();

        assertThatThrownBy(() -> builder.buildPersonalContext(trainerId, "TRAINER", null))
                .isInstanceOf(AiRunValidationException.class)
                .hasMessageContaining("studentId is required for Trainer personalized knowledge inquiry");
    }

    @Test
    @DisplayName("Trainer query student without coaching relationship throws 403 AiRunAccessDeniedException (T03)")
    void trainer_queryUnassignedStudent_throwsAccessDenied_T03() {
        UUID trainerId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();

        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> builder.buildPersonalContext(trainerId, "TRAINER", studentId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer lacks active coaching relationship with student");
    }

    @Test
    @DisplayName("Trainer query student assigned to another trainer throws 403 AiRunAccessDeniedException (T03)")
    void trainer_queryStudentAssignedToOtherTrainer_throwsAccessDenied_T03() {
        UUID trainerId = UUID.randomUUID();
        UUID otherTrainerId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();

        CurrentCoachingContext coachingCtx = new CurrentCoachingContext(
                UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), otherTrainerId,
                Instant.now(), Instant.now().plusSeconds(86400)
        );
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.of(coachingCtx));

        assertThatThrownBy(() -> builder.buildPersonalContext(trainerId, "TRAINER", studentId))
                .isInstanceOf(AiRunAccessDeniedException.class)
                .hasMessageContaining("Trainer is not assigned to student");
    }

    @Test
    @DisplayName("Trainer query student with active relationship succeeds with HUMAN_COACH mode")
    void trainer_queryAssignedStudent_success() {
        UUID trainerId = UUID.randomUUID();
        UUID studentId = UUID.randomUUID();

        CurrentCoachingContext coachingCtx = new CurrentCoachingContext(
                UUID.randomUUID(), Mode.HUMAN_COACH, UUID.randomUUID(), trainerId,
                Instant.now(), Instant.now().plusSeconds(86400)
        );
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.of(coachingCtx));
        when(studentProfileUseCase.getStudentProfile(studentId)).thenReturn(null);
        when(goalUseCase.getCurrentFitnessGoal(studentId)).thenReturn(Optional.empty());
        when(workoutPlanQueryUseCase.findActive(trainerId, studentId)).thenReturn(Optional.empty());

        PersonalContext ctx = builder.buildPersonalContext(trainerId, "TRAINER", studentId);

        assertThat(ctx.studentId()).isEqualTo(studentId);
        assertThat(ctx.coachingMode()).isEqualTo("HUMAN_COACH");
        assertThat(ctx.trainingExperience()).isEqualTo("UNKNOWN");
        assertThat(ctx.activeGoal()).isNull();
        assertThat(ctx.currentActivePlanName()).isNull();
    }

    @Test
    @DisplayName("Paused or non-active goal is excluded from PersonalContext (remains null)")
    void pausedGoal_excludedFromPersonalContext() {
        UUID studentId = UUID.randomUUID();

        FitnessGoal pausedGoal = new FitnessGoal(
                UUID.randomUUID(), studentId, "Mục tiêu tạm ngưng", GoalStatus.PAUSED,
                studentId, Instant.now(), Instant.now(), null, null, "Đang hồi phục chấn thương",
                Instant.now(), Instant.now(), null
        );
        when(goalUseCase.getCurrentFitnessGoal(studentId)).thenReturn(Optional.of(pausedGoal));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.empty());

        PersonalContext ctx = builder.buildPersonalContext(studentId, "STUDENT", null);

        assertThat(ctx.activeGoal()).isNull();
    }

    @Test
    @DisplayName("Workout plan query exception does not fail personal context building, leaves plan name null")
    void workoutPlanQueryFailure_handledGracefully() {
        UUID studentId = UUID.randomUUID();

        when(workoutPlanQueryUseCase.findActive(studentId, studentId))
                .thenThrow(new RuntimeException("Workout plan permission denied or error"));
        when(coachingContextQuery.findEffectiveContext(studentId, false)).thenReturn(Optional.empty());

        PersonalContext ctx = builder.buildPersonalContext(studentId, "STUDENT", null);

        assertThat(ctx.currentActivePlanName()).isNull();
    }
}
