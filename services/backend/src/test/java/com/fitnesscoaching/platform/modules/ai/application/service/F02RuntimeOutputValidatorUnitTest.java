package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.adapter.out.client.SimulatedF02AiServiceClient;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient.F02ServiceRequest;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery.AuthoringReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class F02RuntimeOutputValidatorUnitTest {

    private ObjectMapper objectMapper;
    @Mock
    private ExerciseReferenceQuery exerciseReferenceQuery;

    private F02RuntimeOutputValidator validator;
    private SimulatedF02AiServiceClient simulatedProvider;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validator = new F02RuntimeOutputValidator(objectMapper, exerciseReferenceQuery);
        simulatedProvider = new SimulatedF02AiServiceClient(objectMapper);
    }

    @Test
    @DisplayName("Valid 1-week proposal with matching exercise catalog passes validation")
    void validProposal_passesValidation() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();
        UUID v3 = UUID.randomUUID();
        UUID v4 = UUID.randomUUID();

        when(exerciseReferenceQuery.lockAuthoringReferences(anyCollection()))
                .thenReturn(List.of(
                        new AuthoringReference(v1, UUID.randomUUID()),
                        new AuthoringReference(v2, UUID.randomUUID()),
                        new AuthoringReference(v3, UUID.randomUUID()),
                        new AuthoringReference(v4, UUID.randomUUID())
                ));

        F02ServiceRequest request = new F02ServiceRequest(
                UUID.randomUUID(), UUID.randomUUID(), "SELF_DIRECTED", "BEGINNER", "HYPERTROPHY",
                List.of("DUMBBELLS"), null, List.of(v1, v2, v3, v4)
        );

        String json = simulatedProvider.generateProposalJson(request);
        F02RuntimeOutputValidator.ValidationResult result = validator.validate(json);

        assertThat(result.isValid()).isTrue();
        assertThat(result.failureReason()).isNull();
        assertThat(result.variationIds()).containsExactlyInAnyOrder(v1, v2, v3, v4);
    }

    @Test
    @DisplayName("Proposal with missing or inactive exercise variation is rejected")
    void unavailableExercise_failsValidation() {
        UUID v1 = UUID.randomUUID();
        UUID v2 = UUID.randomUUID();

        // Catalog returns only 1 authoring reference when 2 requested
        when(exerciseReferenceQuery.lockAuthoringReferences(anyCollection()))
                .thenReturn(List.of(new AuthoringReference(v1, UUID.randomUUID())));

        F02ServiceRequest request = new F02ServiceRequest(
                UUID.randomUUID(), null, "SELF_DIRECTED", "BEGINNER", "GENERAL_FITNESS",
                List.of(), null, List.of(v1, v2)
        );

        String json = simulatedProvider.generateProposalJson(request);
        F02RuntimeOutputValidator.ValidationResult result = validator.validate(json);

        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("WORKOUT_PLAN_EXERCISE_UNAVAILABLE");
    }

    @Test
    @DisplayName("Proposal violating D02 (durationWeeks != 1) is rejected")
    void multiWeekProposal_failsValidation() {
        String invalidJson = """
                {
                    "recommendationType": "WORKOUT_PLAN_PROPOSAL",
                    "plan": {
                        "name": "Invalid 4-Week Plan",
                        "durationWeeks": 4,
                        "sessions": []
                    }
                }
                """;

        F02RuntimeOutputValidator.ValidationResult result = validator.validate(invalidJson);

        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("durationWeeks must be exactly 1");
    }

    @Test
    @DisplayName("Malformed JSON is rejected")
    void malformedJson_failsValidation() {
        F02RuntimeOutputValidator.ValidationResult result = validator.validate("NOT_VALID_JSON");

        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Malformed JSON");
    }

    @Test
    @DisplayName("Prescription with negative restSeconds or durationSeconds fails validation")
    void negativePrescriptionFields_failsValidation() {
        String invalidJson = """
                {
                    "recommendationType": "WORKOUT_PLAN_PROPOSAL",
                    "plan": {
                        "name": "Invalid Plan",
                        "durationWeeks": 1,
                        "sessions": [
                            {
                                "weekNumber": 1,
                                "dayNumber": 1,
                                "sequenceNumber": 1,
                                "name": "Day 1",
                                "prescriptions": [
                                    {
                                        "exerciseVariationId": "00000000-0000-0000-0000-000000000001",
                                        "sequenceNumber": 1,
                                        "targetSets": 3,
                                        "restSeconds": -10
                                    }
                                ]
                            }
                        ]
                    }
                }
                """;

        F02RuntimeOutputValidator.ValidationResult result = validator.validate(invalidJson);

        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("restSeconds cannot be negative.");
    }
}
