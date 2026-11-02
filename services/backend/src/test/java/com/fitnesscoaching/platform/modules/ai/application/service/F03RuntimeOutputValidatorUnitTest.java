package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.domain.F03SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery.AuthoringReference;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery.HistoricalReference;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery.PresentationState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class F03RuntimeOutputValidatorUnitTest {

    @Mock
    private ExerciseReferenceQuery exerciseReferenceQuery;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private F03RuntimeOutputValidator validator;

    private static final UUID STUDENT_ID = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PLAN_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID PLANNED_WORKOUT_ID = UUID.fromString("33333333-3333-4333-8333-333333333333");
    private static final UUID PLANNED_SESSION_EXERCISE_ID = UUID.fromString("44444444-4444-4444-8444-444444444444");
    private static final UUID TARGET_VARIATION_ID = UUID.fromString("55555555-5555-4555-8555-555555555555");
    private static final UUID REPLACEMENT_VARIATION_ID = UUID.fromString("66666666-6666-4666-8666-666666666666");
    private static final UUID BASE_EXERCISE_ID = UUID.fromString("77777777-7777-4777-8777-777777777777");

    @BeforeEach
    void setUp() {
        validator = new F03RuntimeOutputValidator(objectMapper, exerciseReferenceQuery);
    }

    private F03SourceSnapshot buildOccurrenceSnapshot(boolean allowLoadIncrease) {
        return new F03SourceSnapshot(
                STUDENT_ID,
                UUID.randomUUID(),
                "SELF_DIRECTED",
                null,
                TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                PLAN_ID,
                1L,
                1,
                PLANNED_WORKOUT_ID,
                3L,
                PLANNED_SESSION_EXERCISE_ID,
                null,
                TARGET_VARIATION_ID,
                BigDecimal.valueOf(20.0),
                1,
                3,
                8,
                12,
                null,
                allowLoadIncrease,
                TrainingContinuityState.RECENT_CONSISTENT,
                Instant.now()
        );
    }

    @Test
    @DisplayName("Valid substitution: active and shares base exercise (sibling)")
    void validate_validSubstitution_returnsValid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        when(exerciseReferenceQuery.lockAuthoringReferences(List.of(REPLACEMENT_VARIATION_ID)))
                .thenReturn(List.of(new AuthoringReference(REPLACEMENT_VARIATION_ID, BASE_EXERCISE_ID)));
        when(exerciseReferenceQuery.resolveHistorical(TARGET_VARIATION_ID))
                .thenReturn(Optional.of(new HistoricalReference(TARGET_VARIATION_ID, BASE_EXERCISE_ID, "Bench Press", "Barbell",
                        PresentationState.ACTIVE, null, null)));
        when(exerciseReferenceQuery.resolveHistorical(REPLACEMENT_VARIATION_ID))
                .thenReturn(Optional.of(new HistoricalReference(REPLACEMENT_VARIATION_ID, BASE_EXERCISE_ID, "Bench Press", "Dumbbell",
                        PresentationState.ACTIVE, null, null)));

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "summary": "Switch to Dumbbell Bench Press",
                  "confidence": 0.9,
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "EXERCISE_SUBSTITUTION",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 3
                      },
                      "before": {
                        "exerciseVariationId": "%s",
                        "exerciseName": "Barbell Bench Press"
                      },
                      "after": {
                        "replacementVariationId": "%s",
                        "replacementExerciseName": "Dumbbell Bench Press",
                        "substitutionCategory": "EQUIPMENT_ALTERNATIVE"
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID, TARGET_VARIATION_ID, REPLACEMENT_VARIATION_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("OD-3 Equivalence check fails: substitution across unverified exercise parent")
    void validate_unverifiedSubstitution_returnsInvalid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);
        UUID differentBaseExerciseId = UUID.randomUUID();

        when(exerciseReferenceQuery.lockAuthoringReferences(List.of(REPLACEMENT_VARIATION_ID)))
                .thenReturn(List.of(new AuthoringReference(REPLACEMENT_VARIATION_ID, differentBaseExerciseId)));
        when(exerciseReferenceQuery.resolveHistorical(TARGET_VARIATION_ID))
                .thenReturn(Optional.of(new HistoricalReference(TARGET_VARIATION_ID, BASE_EXERCISE_ID, "Bench Press", "Barbell",
                        PresentationState.ACTIVE, null, null)));
        when(exerciseReferenceQuery.resolveHistorical(REPLACEMENT_VARIATION_ID))
                .thenReturn(Optional.of(new HistoricalReference(REPLACEMENT_VARIATION_ID, differentBaseExerciseId, "Leg Press", "Machine",
                        PresentationState.ACTIVE, null, null)));

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "summary": "Switch Bench to Leg Press",
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "EXERCISE_SUBSTITUTION",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 3
                      },
                      "before": {
                        "exerciseVariationId": "%s"
                      },
                      "after": {
                        "replacementVariationId": "%s"
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID, TARGET_VARIATION_ID, REPLACEMENT_VARIATION_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("UNVERIFIED_SUBSTITUTION");
    }

    @Test
    @DisplayName("Substitution with inactive variation fails")
    void validate_inactiveReplacement_returnsInvalid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        when(exerciseReferenceQuery.lockAuthoringReferences(List.of(REPLACEMENT_VARIATION_ID)))
                .thenReturn(List.of()); // Inactive / not found

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "EXERCISE_SUBSTITUTION",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 3
                      },
                      "before": { "exerciseVariationId": "%s" },
                      "after": { "replacementVariationId": "%s" }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID, TARGET_VARIATION_ID, REPLACEMENT_VARIATION_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("WORKOUT_PLAN_EXERCISE_UNAVAILABLE");
    }

    @Test
    @DisplayName("Load increase when allowLoadIncrease is true succeeds")
    void validate_validLoadIncrease_returnsValid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "LOAD_ADJUSTMENT",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 3
                      },
                      "before": { "targetLoad": 20.0 },
                      "after": {
                        "targetLoad": 22.5,
                        "direction": "INCREASE",
                        "deltaLoad": 2.5
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Load increase when allowLoadIncrease is false is rejected")
    void validate_prohibitedLoadIncrease_returnsInvalid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(false);

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "LOAD_ADJUSTMENT",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 3
                      },
                      "before": { "targetLoad": 20.0 },
                      "after": {
                        "targetLoad": 22.5,
                        "direction": "INCREASE",
                        "deltaLoad": 2.5
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("LOAD_INCREASE_NOT_PERMITTED");
    }

    @Test
    @DisplayName("Load delta arithmetic mismatch is rejected")
    void validate_loadDeltaMismatch_returnsInvalid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "LOAD_ADJUSTMENT",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 3
                      },
                      "before": { "targetLoad": 20.0 },
                      "after": {
                        "targetLoad": 22.5,
                        "direction": "INCREASE",
                        "deltaLoad": 5.0
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("deltaLoad");
    }

    @Test
    @DisplayName("Volume increase when allowLoadIncrease is false is rejected")
    void validate_prohibitedVolumeIncrease_returnsInvalid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(false);

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "VOLUME_ADJUSTMENT",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 3
                      },
                      "before": { "targetSets": 3 },
                      "after": {
                        "targetSets": 4,
                        "deltaSets": 1
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("VOLUME_INCREASE_NOT_PERMITTED");
    }

    @Test
    @DisplayName("Version guard mismatch is rejected")
    void validate_versionGuardMismatch_returnsInvalid() {
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        String payload = """
                {
                  "recommendationType": "WORKOUT_ADJUSTMENT_PROPOSAL",
                  "changes": [
                    {
                      "changeId": "%s",
                      "changeType": "LOAD_ADJUSTMENT",
                      "scope": "ONE_MATERIALIZED_OCCURRENCE",
                      "targetRef": {
                        "scope": "ONE_MATERIALIZED_OCCURRENCE",
                        "plannedWorkoutId": "%s",
                        "plannedSessionExerciseId": "%s"
                      },
                      "versionGuards": {
                        "expectedOccurrenceVersion": 999
                      },
                      "before": { "targetLoad": 20.0 },
                      "after": {
                        "targetLoad": 22.5,
                        "direction": "INCREASE",
                        "deltaLoad": 2.5
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("expectedOccurrenceVersion");
    }
}
