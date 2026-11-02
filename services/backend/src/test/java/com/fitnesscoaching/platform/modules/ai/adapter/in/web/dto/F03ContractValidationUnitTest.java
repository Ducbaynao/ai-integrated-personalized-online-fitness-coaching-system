package com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto;

import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class F03ContractValidationUnitTest {

    private ObjectMapper objectMapper;
    private Path fixturesDir;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        fixturesDir = Paths.get("../../contracts/ai-schemas/fixtures/f03").toAbsolutePath().normalize();
    }

    private String readFixture(String filename) throws Exception {
        Path path = fixturesDir.resolve(filename);
        if (Files.exists(path)) {
            return Files.readString(path, StandardCharsets.UTF_8);
        }
        // Fallback relative to project root
        Path rootPath = Paths.get("contracts/ai-schemas/fixtures/f03").resolve(filename).toAbsolutePath().normalize();
        return Files.readString(rootPath, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("Deserialize valid exercise substitution on single occurrence")
    void deserialize_validExerciseSubstitutionOccurrence() throws Exception {
        String json = readFixture("valid-exercise-substitution-occurrence.json");
        F03WorkoutAdjustmentPayload payload = objectMapper.readValue(json, F03WorkoutAdjustmentPayload.class);

        assertThat(payload.recommendationType()).isEqualTo("WORKOUT_ADJUSTMENT_PROPOSAL");
        assertThat(payload.confidence()).isGreaterThan(0.9);
        assertThat(payload.changes()).hasSize(1);

        F03WorkoutAdjustmentPayload.TypedChange change = payload.changes().getFirst();
        assertThat(change.changeType()).isEqualTo(F03WorkoutAdjustmentPayload.ChangeType.EXERCISE_SUBSTITUTION);
        assertThat(change.scope()).isEqualTo(F03WorkoutAdjustmentPayload.TargetScope.ONE_MATERIALIZED_OCCURRENCE);
        assertThat(change.targetRef().plannedWorkoutId()).isEqualTo(UUID.fromString("3fa85f64-5717-4562-b3fc-2c963f66afa6"));
        assertThat(change.targetRef().plannedSessionExerciseId()).isEqualTo(UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"));

        F03WorkoutAdjustmentPayload.SubstitutionBefore before = objectMapper.treeToValue(
                change.before(), F03WorkoutAdjustmentPayload.SubstitutionBefore.class);
        assertThat(before.exerciseVariationId()).isEqualTo(UUID.fromString("22222222-2222-4222-8222-222222222222"));

        F03WorkoutAdjustmentPayload.SubstitutionAfter after = objectMapper.treeToValue(
                change.after(), F03WorkoutAdjustmentPayload.SubstitutionAfter.class);
        assertThat(after.replacementVariationId()).isEqualTo(UUID.fromString("33333333-3333-4333-8333-333333333333"));
        assertThat(after.substitutionCategory()).isEqualTo("EQUIPMENT_UNAVAILABLE");
    }

    @Test
    @DisplayName("Deserialize valid exercise substitution on template / future occurrences")
    void deserialize_validExerciseSubstitutionTemplate() throws Exception {
        String json = readFixture("valid-exercise-substitution-template.json");
        F03WorkoutAdjustmentPayload payload = objectMapper.readValue(json, F03WorkoutAdjustmentPayload.class);

        F03WorkoutAdjustmentPayload.TypedChange change = payload.changes().getFirst();
        assertThat(change.scope()).isEqualTo(F03WorkoutAdjustmentPayload.TargetScope.TEMPLATE_OR_FUTURE_OCCURRENCES);
        assertThat(change.targetRef().planId()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        assertThat(change.targetRef().planVersionNumber()).isEqualTo(1);
        assertThat(change.targetRef().exercisePrescriptionId()).isEqualTo(UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    }

    @Test
    @DisplayName("Deserialize valid load adjustment with positive delta and KG unit")
    void deserialize_validLoadAdjustment() throws Exception {
        String json = readFixture("valid-load-adjustment.json");
        F03WorkoutAdjustmentPayload payload = objectMapper.readValue(json, F03WorkoutAdjustmentPayload.class);

        F03WorkoutAdjustmentPayload.TypedChange change = payload.changes().getFirst();
        assertThat(change.changeType()).isEqualTo(F03WorkoutAdjustmentPayload.ChangeType.LOAD_ADJUSTMENT);

        F03WorkoutAdjustmentPayload.LoadAfter after = objectMapper.treeToValue(
                change.after(), F03WorkoutAdjustmentPayload.LoadAfter.class);
        assertThat(after.targetLoad().doubleValue()).isEqualTo(82.5);
        assertThat(after.deltaLoad().doubleValue()).isEqualTo(2.5);
        assertThat(after.direction()).isEqualTo("INCREASE");
        assertThat(after.loadUnit()).isEqualTo("KG");
        assertThat(after.loadUnitId()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deserialize valid bodyweight load adjustment with null targetLoad")
    void deserialize_validLoadAdjustmentBodyweight() throws Exception {
        String json = readFixture("valid-load-adjustment-bodyweight.json");
        F03WorkoutAdjustmentPayload payload = objectMapper.readValue(json, F03WorkoutAdjustmentPayload.class);

        F03WorkoutAdjustmentPayload.TypedChange change = payload.changes().getFirst();
        F03WorkoutAdjustmentPayload.LoadBefore before = objectMapper.treeToValue(
                change.before(), F03WorkoutAdjustmentPayload.LoadBefore.class);
        F03WorkoutAdjustmentPayload.LoadAfter after = objectMapper.treeToValue(
                change.after(), F03WorkoutAdjustmentPayload.LoadAfter.class);

        assertThat(before.targetLoad()).isNull();
        assertThat(after.targetLoad()).isNull();
        assertThat(after.deltaLoad()).isNull();
        assertThat(after.direction()).isEqualTo("MAINTAIN");
    }

    @Test
    @DisplayName("Deserialize valid volume adjustment with reps progression")
    void deserialize_validVolumeAdjustmentReps() throws Exception {
        String json = readFixture("valid-volume-adjustment-reps.json");
        F03WorkoutAdjustmentPayload payload = objectMapper.readValue(json, F03WorkoutAdjustmentPayload.class);

        F03WorkoutAdjustmentPayload.TypedChange change = payload.changes().getFirst();
        assertThat(change.changeType()).isEqualTo(F03WorkoutAdjustmentPayload.ChangeType.VOLUME_ADJUSTMENT);

        F03WorkoutAdjustmentPayload.VolumeBefore before = objectMapper.treeToValue(
                change.before(), F03WorkoutAdjustmentPayload.VolumeBefore.class);
        F03WorkoutAdjustmentPayload.VolumeAfter after = objectMapper.treeToValue(
                change.after(), F03WorkoutAdjustmentPayload.VolumeAfter.class);

        assertThat(before.targetSets()).isEqualTo(3);
        assertThat(after.targetSets()).isEqualTo(4);
        assertThat(after.deltaSets()).isEqualTo(1);
    }

    @Test
    @DisplayName("Deserialize valid volume adjustment with duration progression")
    void deserialize_validVolumeAdjustmentDuration() throws Exception {
        String json = readFixture("valid-volume-adjustment-duration.json");
        F03WorkoutAdjustmentPayload payload = objectMapper.readValue(json, F03WorkoutAdjustmentPayload.class);

        F03WorkoutAdjustmentPayload.TypedChange change = payload.changes().getFirst();
        F03WorkoutAdjustmentPayload.VolumeBefore before = objectMapper.treeToValue(
                change.before(), F03WorkoutAdjustmentPayload.VolumeBefore.class);
        F03WorkoutAdjustmentPayload.VolumeAfter after = objectMapper.treeToValue(
                change.after(), F03WorkoutAdjustmentPayload.VolumeAfter.class);

        assertThat(before.durationSeconds()).isEqualTo(45);
        assertThat(after.durationSeconds()).isEqualTo(60);
        assertThat(after.deltaDurationSeconds()).isEqualTo(15);
    }

    @Test
    @DisplayName("TargetRef validation enforces mandatory IDs per scope")
    void targetRef_validation() {
        // Occurrence scope requires plannedWorkoutId and plannedSessionExerciseId
        assertThatThrownBy(() -> new F03WorkoutAdjustmentPayload.TargetRef(
                F03WorkoutAdjustmentPayload.TargetScope.ONE_MATERIALIZED_OCCURRENCE,
                null, UUID.randomUUID(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);

        // Template scope requires planId, planVersionNumber, and exercisePrescriptionId
        assertThatThrownBy(() -> new F03WorkoutAdjustmentPayload.TargetRef(
                F03WorkoutAdjustmentPayload.TargetScope.TEMPLATE_OR_FUTURE_OCCURRENCES,
                null, null, null, 1, 1, UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
