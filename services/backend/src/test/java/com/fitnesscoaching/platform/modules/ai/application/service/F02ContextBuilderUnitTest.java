package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.modules.ai.domain.F02SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.Mode;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.Gender;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class F02ContextBuilderUnitTest {

    @Mock
    private CurrentCoachingContextQuery coachingContextQuery;
    @Mock
    private ObjectProvider<GetStudentProfileUseCase> studentProfileProvider;
    @Mock
    private ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider;
    @Mock
    private ObjectProvider<WorkoutCompletionEvidenceQuery> completionQueryProvider;
    @Mock
    private ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider;
    @Mock
    private ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider;

    @Mock
    private GetStudentProfileUseCase studentProfileService;
    @Mock
    private GetCurrentFitnessGoalUseCase goalService;
    @Mock
    private WorkoutCompletionEvidenceQuery completionQuery;
    @Mock
    private WorkoutPlanQueryUseCase workoutPlanQuery;
    @Mock
    private CoachingAuthorityQuery coachingAuthorityQuery;

    private Clock clock;
    private F02ContextBuilder contextBuilder;

    private final UUID studentId = UUID.randomUUID();
    private final UUID actorId = studentId;
    private final UUID trainerId = UUID.randomUUID();
    private final UUID periodId = UUID.randomUUID();
    private final UUID goalId = UUID.randomUUID();
    private final Instant fixedNow = Instant.parse("2026-10-30T10:00:00Z");

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(fixedNow, ZoneOffset.UTC);
        contextBuilder = new F02ContextBuilder(
                coachingContextQuery,
                studentProfileProvider,
                goalProvider,
                completionQueryProvider,
                workoutPlanQueryProvider,
                coachingAuthorityQueryProvider,
                clock
        );
    }

    private Map<String, Object> validPreferences() {
        return new HashMap<>(Map.of(
                "preferredDaysPerWeek", 3,
                "preferredSessionMinutes", 60,
                "equipment", List.of("DUMBBELLS"),
                "limitationsConfirmed", true,
                "startDate", "2026-10-31"
        ));
    }

    private StudentProfile createProfile(TrainingExperienceLevel level, BigDecimal months, boolean onboardingCompleted) {
        return new StudentProfile(
                studentId,
                LocalDate.of(1995, 5, 20),
                Gender.MALE,
                level,
                months,
                3,
                60,
                onboardingCompleted ? fixedNow.minus(30, ChronoUnit.DAYS) : null,
                fixedNow.minus(30, ChronoUnit.DAYS),
                fixedNow
        );
    }

    private FitnessGoal createActiveGoal(int versionNumber) {
        FitnessGoalVersion version = new FitnessGoalVersion(
                UUID.randomUUID(),
                goalId,
                versionNumber,
                LocalDate.of(2026, 10, 1),
                LocalDate.of(2026, 12, 31),
                90,
                fixedNow.minus(5, ChronoUnit.DAYS),
                null,
                null,
                "Initial",
                "Initial version",
                studentId,
                null,
                fixedNow.minus(5, ChronoUnit.DAYS),
                null,
                null,
                null,
                List.of(),
                List.of()
        );
        return new FitnessGoal(
                goalId,
                studentId,
                "Hypertrophy Goal",
                GoalStatus.ACTIVE,
                studentId,
                fixedNow.minus(5, ChronoUnit.DAYS),
                null,
                null,
                null,
                null,
                fixedNow.minus(5, ChronoUnit.DAYS),
                fixedNow,
                version
        );
    }

    @Test
    @DisplayName("Fail-Fast: Missing student training experience level reports field error")
    void profileMissingExperienceLevel_reportsFieldError() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(null, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(f -> "trainingExperienceLevel".equals(f.field()));
                });
    }

    @Test
    @DisplayName("Fail-Fast: Missing active fitness goal reports field error")
    void missingActiveGoal_reportsFieldError() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(f -> "fitnessGoal".equals(f.field()));
                });
    }

    @Test
    @DisplayName("Input readiness: Missing equipment, limitations and start date reports multiple FieldErrorDto items")
    void missingMandatoryInputs_reportsDetailedFieldErrors() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Map<String, Object> emptyPrefs = Map.of();

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, emptyPrefs, List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    List<String> fields = ve.getFieldErrors().stream().map(FieldErrorDto::field).toList();
                    assertThat(fields).contains("equipment", "limitations", "startDate");
                });
    }

    @Test
    @DisplayName("T08: Beginner with 0 months and completed onboarding is classified as CONFIRMED_NEW")
    void beginnerConfirmedNew_classifiedCorrectly() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        assertThat(envelope.request().continuityState()).isEqualTo("CONFIRMED_NEW");
        assertThat(envelope.request().daysSinceLastWorkout()).isNull();
        assertThat(envelope.sourceSnapshot().continuityState()).isEqualTo(TrainingContinuityState.CONFIRMED_NEW);
    }

    @Test
    @DisplayName("T08: Beginner with prior months > 0 or incomplete onboarding is UNKNOWN_HISTORY (not confirmed new)")
    void beginnerWithPriorMonths_classifiedAsUnknownHistory() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        // Has 6 months self-reported prior experience
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.valueOf(6), true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        assertThat(envelope.request().continuityState()).isEqualTo("UNKNOWN_HISTORY");
        assertThat(envelope.sourceSnapshot().continuityState()).isEqualTo(TrainingContinuityState.UNKNOWN_HISTORY);
    }

    @Test
    @DisplayName("T08: Non-beginner (INTERMEDIATE) with 0 platform completions is UNKNOWN_HISTORY")
    void intermediateWithoutCompletions_classifiedAsUnknownHistory() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.INTERMEDIATE, BigDecimal.valueOf(24), true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        assertThat(envelope.request().continuityState()).isEqualTo("UNKNOWN_HISTORY");
    }

    @Test
    @DisplayName("Trainer without WORKOUT_HISTORY access is classified as RESTRICTED_HISTORY and blocked")
    void coachWithoutHistoryAccess_classifiedAsRestrictedHistory() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.HUMAN_COACH, trainerId, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.INTERMEDIATE, BigDecimal.valueOf(12), true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        when(coachingAuthorityQueryProvider.getIfAvailable()).thenReturn(coachingAuthorityQuery);
        when(coachingAuthorityQuery.evaluateCurrent(any())).thenReturn(AuthorityDecision.denied(CoachingSharing.AuthorityReason.PERMISSION_NOT_GRANTED));

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, trainerId, validPreferences(), List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(f -> "trainingContinuity".equals(f.field()) && "RESTRICTED".equals(f.code()));
                });
    }

    @Test
    @DisplayName("Future completion timestamps (> asOf) are filtered out and not counted as valid workouts")
    void futureCompletions_areFilteredOut() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Instant futureWorkout = fixedNow.plus(2, ChronoUnit.DAYS);
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of(
                new WorkoutCompletionEvidenceQuery.Completion(UUID.randomUUID(), studentId, 1L, futureWorkout.minus(1, ChronoUnit.HOURS), futureWorkout)
        ));
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        // Future workout ignored; falls back to CONFIRMED_NEW beginner
        assertThat(envelope.request().continuityState()).isEqualTo("CONFIRMED_NEW");
        assertThat(envelope.request().daysSinceLastWorkout()).isNull();
    }

    @Test
    @DisplayName("T08: Trainee with workout completion 120 days ago (>= 100 days) is PROLONGED_INACTIVITY")
    void traineeWith120DaysInactivity_classifiedAsProlongedInactivity() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.INTERMEDIATE, BigDecimal.valueOf(12), true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Instant pastWorkout = fixedNow.minus(120, ChronoUnit.DAYS);
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of(
                new WorkoutCompletionEvidenceQuery.Completion(UUID.randomUUID(), studentId, 1L, pastWorkout.minus(1, ChronoUnit.HOURS), pastWorkout)
        ));
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        assertThat(envelope.request().continuityState()).isEqualTo("PROLONGED_INACTIVITY");
        assertThat(envelope.request().daysSinceLastWorkout()).isEqualTo(120);
    }

    @Test
    @DisplayName("Inactivity of 40 days without consistent routine is UNKNOWN_HISTORY (no arbitrary 30-day prolonged threshold)")
    void fortyDaysInactivity_classifiedAsUnknownHistory() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.INTERMEDIATE, BigDecimal.valueOf(12), true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Instant pastWorkout = fixedNow.minus(40, ChronoUnit.DAYS);
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of(
                new WorkoutCompletionEvidenceQuery.Completion(UUID.randomUUID(), studentId, 1L, pastWorkout.minus(1, ChronoUnit.HOURS), pastWorkout)
        ));
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        assertThat(envelope.request().continuityState()).isEqualTo("UNKNOWN_HISTORY");
        assertThat(envelope.request().daysSinceLastWorkout()).isEqualTo(40);
    }

    @Test
    @DisplayName("Consistent trainee with >= 3 workouts in past 28 days and last workout <= 14 days is RECENT_CONSISTENT")
    void consistentTrainee_classifiedAsRecentConsistent() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.ADVANCED, BigDecimal.valueOf(36), true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Instant w1 = fixedNow.minus(2, ChronoUnit.DAYS);
        Instant w2 = fixedNow.minus(5, ChronoUnit.DAYS);
        Instant w3 = fixedNow.minus(8, ChronoUnit.DAYS);
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of(
                new WorkoutCompletionEvidenceQuery.Completion(UUID.randomUUID(), studentId, 1L, w1.minus(1, ChronoUnit.HOURS), w1),
                new WorkoutCompletionEvidenceQuery.Completion(UUID.randomUUID(), studentId, 1L, w2.minus(1, ChronoUnit.HOURS), w2),
                new WorkoutCompletionEvidenceQuery.Completion(UUID.randomUUID(), studentId, 1L, w3.minus(1, ChronoUnit.HOURS), w3)
        ));
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        assertThat(envelope.request().continuityState()).isEqualTo("RECENT_CONSISTENT");
        assertThat(envelope.request().daysSinceLastWorkout()).isEqualTo(2);
    }

    @Test
    @DisplayName("Single recent workout does not infer consistent routine (returns UNKNOWN_HISTORY)")
    void singleRecentWorkout_doesNotInferRecentConsistent() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.INTERMEDIATE, BigDecimal.valueOf(12), true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Instant w1 = fixedNow.minus(3, ChronoUnit.DAYS);
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of(
                new WorkoutCompletionEvidenceQuery.Completion(UUID.randomUUID(), studentId, 1L, w1.minus(1, ChronoUnit.HOURS), w1)
        ));
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, validPreferences(), List.of());

        assertThat(envelope.request().continuityState()).isEqualTo("UNKNOWN_HISTORY");
        assertThat(envelope.request().daysSinceLastWorkout()).isEqualTo(3);
    }

    @Test
    @DisplayName("Missing measurements: suspect/zero bodyWeightKg is dropped (unknown, never zero)")
    void suspectMeasurement_isDropped() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        Map<String, Object> prefs = validPreferences();
        prefs.put("bodyWeightKg", 0.0); // Suspect zero measurement

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, prefs, List.of());

        assertThat(envelope.request().preferences()).doesNotContainKey("bodyWeightKg");
    }

    @Test
    @DisplayName("Limitations: limitationsConfirmed = false is rejected with NOT_CONFIRMED")
    void limitationsConfirmed_false_isRejected() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Map<String, Object> prefs = validPreferences();
        prefs.put("limitationsConfirmed", false);

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, prefs, List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(fe -> "limitationsConfirmed".equals(fe.field()) && "NOT_CONFIRMED".equals(fe.code()));
                });
    }

    @Test
    @DisplayName("Limitations: hasPhysicalLimitations = false is accepted as confirmed no limitations")
    void hasPhysicalLimitations_false_isAccepted() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));
        when(completionQueryProvider.getIfAvailable()).thenReturn(completionQuery);
        when(completionQuery.completedForStudent(studentId)).thenReturn(List.of());
        when(workoutPlanQueryProvider.getIfAvailable()).thenReturn(workoutPlanQuery);
        when(workoutPlanQuery.findActive(actorId, studentId)).thenReturn(Optional.empty());

        Map<String, Object> prefs = validPreferences();
        prefs.remove("limitationsConfirmed");
        prefs.put("hasPhysicalLimitations", false);

        var envelope = contextBuilder.buildContextEnvelope(studentId, actorId, prefs, List.of());
        assertThat(envelope.request().preferences()).containsEntry("hasPhysicalLimitations", false);
        assertThat(envelope.request().preferences()).containsEntry("limitationsConfirmed", true);
    }

    @Test
    @DisplayName("Limitations: hasPhysicalLimitations = true without description is rejected with REQUIRED")
    void hasPhysicalLimitations_true_withoutDescription_isRejected() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Map<String, Object> prefs = validPreferences();
        prefs.remove("limitationsConfirmed");
        prefs.put("hasPhysicalLimitations", true);
        prefs.put("limitationsDescription", "   ");

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, prefs, List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(fe -> "limitationsDescription".equals(fe.field()) && "REQUIRED".equals(fe.code()));
                });
    }

    @Test
    @DisplayName("Limitations: medicalClearance alone does not substitute for limitations declaration")
    void medicalClearance_alone_doesNotSubstituteLimitations() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Map<String, Object> prefs = validPreferences();
        prefs.remove("limitationsConfirmed");
        prefs.put("medicalClearance", true);

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, prefs, List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(fe -> "limitations".equals(fe.field()) && "REQUIRED".equals(fe.code()));
                });
    }

    @Test
    @DisplayName("Equipment: allowBodyweight = false and empty equipment list is rejected")
    void allowBodyweight_false_emptyEquipment_isRejected() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Map<String, Object> prefs = validPreferences();
        prefs.put("equipment", List.of());
        prefs.put("allowBodyweight", false);

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, prefs, List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(fe -> "equipment".equals(fe.field()) && "REQUIRED".equals(fe.code()));
                });
    }

    @Test
    @DisplayName("Dates and numbers: past date and invalid days are rejected")
    void pastDate_andInvalidDays_areRejected() {
        when(coachingContextQuery.findEffectiveContext(studentId, false))
                .thenReturn(Optional.of(new CurrentCoachingContext(periodId, Mode.SELF_DIRECTED, null, null, fixedNow, null)));
        when(studentProfileProvider.getIfAvailable()).thenReturn(studentProfileService);
        when(studentProfileService.getStudentProfile(studentId)).thenReturn(createProfile(TrainingExperienceLevel.BEGINNER, BigDecimal.ZERO, true));
        when(goalProvider.getIfAvailable()).thenReturn(goalService);
        when(goalService.getCurrentFitnessGoal(studentId, false)).thenReturn(Optional.of(createActiveGoal(1)));

        Map<String, Object> prefs = validPreferences();
        prefs.put("startDate", "2026-10-20"); // 10 days before fixedNow (2026-10-30)
        prefs.put("preferredDaysPerWeek", 8); // > 7

        assertThatThrownBy(() -> contextBuilder.buildContextEnvelope(studentId, actorId, prefs, List.of()))
                .isInstanceOf(AiRunValidationException.class)
                .satisfies(ex -> {
                    AiRunValidationException ve = (AiRunValidationException) ex;
                    assertThat(ve.getFieldErrors()).anyMatch(fe -> "startDate".equals(fe.field()) && "PAST_DATE".equals(fe.code()));
                    assertThat(ve.getFieldErrors()).anyMatch(fe -> "preferredDaysPerWeek".equals(fe.field()) && "INVALID_VALUE".equals(fe.code()));
                });
    }
}
