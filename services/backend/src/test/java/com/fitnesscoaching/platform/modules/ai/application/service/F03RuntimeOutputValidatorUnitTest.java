package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.domain.F03SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery.AuthoringReference;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery.HistoricalReference;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery.PresentationState;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementUnitReferenceQuery.UnitReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class F03RuntimeOutputValidatorUnitTest {

    @Mock
    private ExerciseReferenceQuery exerciseReferenceQuery;

    @Mock
    private MeasurementUnitReferenceQuery unitReferenceQuery;

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

    @Test
    @DisplayName("Unit conversion LB to KG correctly inverts numerical direction to INCREASE (+4.64 KG)")
    void validate_loadIncreaseWithUnitConversion_LbToKg_returnsValid() {
        // Snapshot with baseline in LB: 100 LB
        F03SourceSnapshot snapshot = new F03SourceSnapshot(
                STUDENT_ID, UUID.randomUUID(), "SELF_DIRECTED", null,
                TargetScope.ONE_MATERIALIZED_OCCURRENCE, PLAN_ID, 1L, 1,
                PLANNED_WORKOUT_ID, 3L, PLANNED_SESSION_EXERCISE_ID, null,
                TARGET_VARIATION_ID, BigDecimal.valueOf(100.0), 2, 3, 8, 12, null,
                true, TrainingContinuityState.RECENT_CONSISTENT, Instant.now()
        );

        // 100 LB -> 50 KG is physically an INCREASE (+4.64 KG) although 50 < 100
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
                      "before": {
                        "targetLoad": 100.0,
                        "loadUnit": "LB",
                        "loadUnitId": 77
                      },
                      "after": {
                        "targetLoad": 50.0,
                        "loadUnit": "KG",
                        "loadUnitId": 41,
                        "direction": "INCREASE",
                        "deltaLoad": 4.64,
                        "deltaPercent": 10.2
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Unit conversion KG to LB correctly inverts numerical direction to DECREASE (-10.23 LB)")
    void validate_loadDecreaseWithUnitConversion_KgToLb_returnsValid() {
        // Snapshot with baseline in KG: 50 KG
        F03SourceSnapshot snapshot = new F03SourceSnapshot(
                STUDENT_ID, UUID.randomUUID(), "SELF_DIRECTED", null,
                TargetScope.ONE_MATERIALIZED_OCCURRENCE, PLAN_ID, 1L, 1,
                PLANNED_WORKOUT_ID, 3L, PLANNED_SESSION_EXERCISE_ID, null,
                TARGET_VARIATION_ID, BigDecimal.valueOf(50.0), 1, 3, 8, 12, null,
                true, TrainingContinuityState.RECENT_CONSISTENT, Instant.now()
        );

        // 50 KG (=110.23 LB) -> 100 LB is a DECREASE (-10.23 LB) although 100 > 50
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
                      "before": {
                        "targetLoad": 50.0,
                        "loadUnit": "KG",
                        "loadUnitId": 41
                      },
                      "after": {
                        "targetLoad": 100.0,
                        "loadUnit": "LB",
                        "loadUnitId": 77,
                        "direction": "DECREASE",
                        "deltaLoad": -10.23,
                        "deltaPercent": -9.3
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Alias LBS is normalized and handled as LB")
    void validate_aliasLbs_normalizedCorrectly() {
        F03SourceSnapshot snapshot = new F03SourceSnapshot(
                STUDENT_ID, UUID.randomUUID(), "SELF_DIRECTED", null,
                TargetScope.ONE_MATERIALIZED_OCCURRENCE, PLAN_ID, 1L, 1,
                PLANNED_WORKOUT_ID, 3L, PLANNED_SESSION_EXERCISE_ID, null,
                TARGET_VARIATION_ID, BigDecimal.valueOf(100.0), 2, 3, 8, 12, null,
                true, TrainingContinuityState.RECENT_CONSISTENT, Instant.now()
        );

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
                      "before": {
                        "targetLoad": 100.0,
                        "loadUnit": "LBS"
                      },
                      "after": {
                        "targetLoad": 105.0,
                        "loadUnit": "LBS",
                        "direction": "INCREASE",
                        "deltaLoad": 5.0
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Unknown or missing baseline load rejects INCREASE/DECREASE calculation")
    void validate_unknownBaseline_increaseRejected() {
        // Snapshot with targetLoad = null (unknown/bodyweight)
        F03SourceSnapshot snapshot = new F03SourceSnapshot(
                STUDENT_ID, UUID.randomUUID(), "SELF_DIRECTED", null,
                TargetScope.ONE_MATERIALIZED_OCCURRENCE, PLAN_ID, 1L, 1,
                PLANNED_WORKOUT_ID, 3L, PLANNED_SESSION_EXERCISE_ID, null,
                TARGET_VARIATION_ID, null, null, 3, 8, 12, null,
                true, TrainingContinuityState.RECENT_CONSISTENT, Instant.now()
        );

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
                      "before": {
                        "targetLoad": null,
                        "loadUnit": null
                      },
                      "after": {
                        "targetLoad": 20.0,
                        "loadUnit": "KG",
                        "direction": "INCREASE",
                        "deltaLoad": 20.0
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Cannot calculate load delta or adjust load when baseline load is unknown or missing.");
    }

    @Test
    @DisplayName("Unknown or bodyweight baseline load permits MAINTAIN with null or 0 delta")
    void validate_unknownBaseline_maintainValid() {
        F03SourceSnapshot snapshot = new F03SourceSnapshot(
                STUDENT_ID, UUID.randomUUID(), "SELF_DIRECTED", null,
                TargetScope.ONE_MATERIALIZED_OCCURRENCE, PLAN_ID, 1L, 1,
                PLANNED_WORKOUT_ID, 3L, PLANNED_SESSION_EXERCISE_ID, null,
                TARGET_VARIATION_ID, null, null, 3, 8, 12, null,
                true, TrainingContinuityState.RECENT_CONSISTENT, Instant.now()
        );

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
                      "before": {
                        "targetLoad": null,
                        "loadUnit": null
                      },
                      "after": {
                        "targetLoad": null,
                        "loadUnit": null,
                        "direction": "MAINTAIN",
                        "deltaLoad": null
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validator.validate(payload, snapshot);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Conflicting loadUnit code and ID (e.g. KG code with LB ID) is rejected")
    void validate_conflictingCodeAndId_kgCodeWithLbId_returnsInvalid() {
        F03RuntimeOutputValidator validatorWithUnits = new F03RuntimeOutputValidator(
                objectMapper, exerciseReferenceQuery, unitReferenceQuery
        );
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        when(unitReferenceQuery.resolveAll(List.of((short) 1)))
                .thenReturn(Map.of((short) 1, new UnitReference((short) 1, "KG", "kg", "MASS")));
        when(unitReferenceQuery.resolveAll(List.of((short) 7)))
                .thenReturn(Map.of((short) 7, new UnitReference((short) 7, "LB", "lb", "MASS")));

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
                      "before": {
                        "targetLoad": 20.0,
                        "loadUnit": "KG",
                        "loadUnitId": 1
                      },
                      "after": {
                        "targetLoad": 22.5,
                        "loadUnit": "KG",
                        "loadUnitId": 7,
                        "direction": "INCREASE",
                        "deltaLoad": 2.5
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validatorWithUnits.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Conflicting after loadUnit code and ID: code 'KG' does not match unit ID 7 ('LB')");
    }

    @Test
    @DisplayName("Conflicting loadUnit code and ID in reverse (LB code with KG ID) is rejected")
    void validate_conflictingCodeAndId_lbCodeWithKgId_returnsInvalid() {
        F03RuntimeOutputValidator validatorWithUnits = new F03RuntimeOutputValidator(
                objectMapper, exerciseReferenceQuery, unitReferenceQuery
        );
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        when(unitReferenceQuery.resolveAll(List.of((short) 1)))
                .thenReturn(Map.of((short) 1, new UnitReference((short) 1, "KG", "kg", "MASS")));

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
                      "before": {
                        "targetLoad": 20.0,
                        "loadUnit": "KG",
                        "loadUnitId": 1
                      },
                      "after": {
                        "targetLoad": 22.5,
                        "loadUnit": "LB",
                        "loadUnitId": 1,
                        "direction": "INCREASE",
                        "deltaLoad": 2.5
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validatorWithUnits.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Conflicting after loadUnit code and ID: code 'LB' does not match unit ID 1 ('KG')");
    }

    @Test
    @DisplayName("Non-MASS unit dimension (e.g. CM / LENGTH) is rejected")
    void validate_nonMassDimension_returnsInvalid() {
        F03RuntimeOutputValidator validatorWithUnits = new F03RuntimeOutputValidator(
                objectMapper, exerciseReferenceQuery, unitReferenceQuery
        );
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        when(unitReferenceQuery.resolveAll(List.of((short) 1)))
                .thenReturn(Map.of((short) 1, new UnitReference((short) 1, "KG", "kg", "MASS")));
        when(unitReferenceQuery.resolveAll(List.of((short) 99)))
                .thenReturn(Map.of((short) 99, new UnitReference((short) 99, "CM", "cm", "LENGTH")));

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
                      "before": {
                        "targetLoad": 20.0,
                        "loadUnit": "KG",
                        "loadUnitId": 1
                      },
                      "after": {
                        "targetLoad": 22.5,
                        "loadUnit": "CM",
                        "loadUnitId": 99,
                        "direction": "INCREASE",
                        "deltaLoad": 2.5
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validatorWithUnits.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("after.loadUnitId 99 dimension must be MASS, but was LENGTH");
    }

    @Test
    @DisplayName("Unknown loadUnitId is rejected")
    void validate_unknownLoadUnitId_returnsInvalid() {
        F03RuntimeOutputValidator validatorWithUnits = new F03RuntimeOutputValidator(
                objectMapper, exerciseReferenceQuery, unitReferenceQuery
        );
        F03SourceSnapshot snapshot = buildOccurrenceSnapshot(true);

        when(unitReferenceQuery.resolveAll(List.of((short) 1)))
                .thenReturn(Map.of((short) 1, new UnitReference((short) 1, "KG", "kg", "MASS")));
        when(unitReferenceQuery.resolveAll(List.of((short) 999)))
                .thenReturn(Map.of());

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
                      "before": {
                        "targetLoad": 20.0,
                        "loadUnit": "KG",
                        "loadUnitId": 1
                      },
                      "after": {
                        "targetLoad": 22.5,
                        "loadUnit": "KG",
                        "loadUnitId": 999,
                        "direction": "INCREASE",
                        "deltaLoad": 2.5
                      }
                    }
                  ]
                }
                """.formatted(UUID.randomUUID(), PLANNED_WORKOUT_ID, PLANNED_SESSION_EXERCISE_ID);

        var result = validatorWithUnits.validate(payload, snapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("after.loadUnitId 999 does not exist in measurement units catalog");
    }
}
