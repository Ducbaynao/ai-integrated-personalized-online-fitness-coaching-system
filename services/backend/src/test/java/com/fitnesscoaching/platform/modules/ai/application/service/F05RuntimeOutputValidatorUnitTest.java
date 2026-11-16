package com.fitnesscoaching.platform.modules.ai.application.service;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.ai.domain.F05SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class F05RuntimeOutputValidatorUnitTest {

    private F05RuntimeOutputValidator validator;
    private ObjectMapper objectMapper;
    private F05SourceSnapshot defaultSnapshot;
    private UUID occurrenceId;
    private Instant now;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        validator = new F05RuntimeOutputValidator(objectMapper);
        occurrenceId = UUID.randomUUID();
        now = Instant.now();

        defaultSnapshot = new F05SourceSnapshot(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SELF_DIRECTED",
                null,
                occurrenceId,
                0L,
                "SCHEDULED",
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Upper Body Push",
                now.plus(1, ChronoUnit.DAYS),
                now.plus(1, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS),
                now.plus(1, ChronoUnit.DAYS),
                "Asia/Ho_Chi_Minh",
                "2026-11-17T09:00:00",
                "2026-11-17T10:00:00",
                "+07:00",
                "+07:00",
                "SELF_PERFORMABLE",
                null,
                null,
                TrainingContinuityState.RECENT_CONSISTENT,
                now
        );
    }

    private String createValidPayload(UUID occId, String supervision, String localStart, String localEnd,
                                     String startOffset, String endOffset, String propStart, String propEnd) {
        return """
            {
              "recommendationType": "WORKOUT_RESCHEDULE_PROPOSAL",
              "summary": "Reschedule workout to tomorrow morning",
              "reasoning": ["Slot provides adequate rest."],
              "usedData": ["occurrenceId: %s"],
              "missingData": [],
              "limitations": ["Single occurrence only."],
              "confidence": 0.95,
              "reschedule": {
                "occurrenceId": "%s",
                "previousPlannedStartAt": "2026-11-16T02:00:00Z",
                "supervisionRequirement": "%s",
                "destination": {
                  "localStart": "%s",
                  "localEnd": "%s",
                  "timezone": "Asia/Ho_Chi_Minh",
                  "startUtcOffset": "%s",
                  "endUtcOffset": "%s",
                  "proposedStartAt": "%s",
                  "proposedEndAt": "%s"
                }
              }
            }
            """.formatted(occId, occId, supervision, localStart, localEnd, startOffset, endOffset, propStart, propEnd);
    }

    @Test
    @DisplayName("Valid F05 payload passes validation")
    void validPayload_passes() {
        Instant futureStart = now.plus(2, ChronoUnit.DAYS);
        Instant futureEnd = futureStart.plus(1, ChronoUnit.HOURS);
        String payload = createValidPayload(
                occurrenceId,
                "SELF_PERFORMABLE",
                "2026-11-18T09:00:00",
                "2026-11-18T10:00:00",
                "+07:00",
                "+07:00",
                futureStart.toString(),
                futureEnd.toString()
        );

        var result = validator.validate(payload, defaultSnapshot);
        assertThat(result.isValid()).isTrue();
        assertThat(result.failureReason()).isNull();
    }

    @Test
    @DisplayName("Null or empty payload fails validation")
    void emptyPayload_fails() {
        assertThat(validator.validate(null, defaultSnapshot).isValid()).isFalse();
        assertThat(validator.validate("", defaultSnapshot).isValid()).isFalse();
        assertThat(validator.validate("   ", defaultSnapshot).isValid()).isFalse();
    }

    @Test
    @DisplayName("Null source snapshot fails validation")
    void nullSnapshot_fails() {
        assertThat(validator.validate("{}", null).isValid()).isFalse();
    }

    @Test
    @DisplayName("Invalid recommendationType fails validation")
    void invalidRecommendationType_fails() {
        String payload = """
            {
              "recommendationType": "INVALID_TYPE",
              "summary": "Reschedule",
              "confidence": 0.9,
              "reschedule": {}
            }
            """;
        var result = validator.validate(payload, defaultSnapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("recommendationType must be WORKOUT_RESCHEDULE_PROPOSAL");
    }

    @Test
    @DisplayName("Mismatched occurrenceId fails validation")
    void mismatchedOccurrenceId_fails() {
        UUID otherId = UUID.randomUUID();
        Instant futureStart = now.plus(2, ChronoUnit.DAYS);
        Instant futureEnd = futureStart.plus(1, ChronoUnit.HOURS);
        String payload = createValidPayload(
                otherId,
                "SELF_PERFORMABLE",
                "2026-11-18T09:00:00",
                "2026-11-18T10:00:00",
                "+07:00",
                "+07:00",
                futureStart.toString(),
                futureEnd.toString()
        );

        var result = validator.validate(payload, defaultSnapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("occurrenceId does not match source snapshot");
    }

    @Test
    @DisplayName("T10: Degrading COACH_REQUIRED to SELF_PERFORMABLE fails validation")
    void t10_degradingCoachRequired_fails() {
        F05SourceSnapshot coachSnapshot = new F05SourceSnapshot(
                defaultSnapshot.studentId(),
                defaultSnapshot.coachingPeriodId(),
                "HUMAN_COACH",
                UUID.randomUUID(),
                occurrenceId,
                0L,
                "SCHEDULED",
                defaultSnapshot.planId(),
                defaultSnapshot.planVersionId(),
                defaultSnapshot.planSessionId(),
                "Heavy Deadlift",
                defaultSnapshot.plannedStartAt(),
                defaultSnapshot.plannedEndAt(),
                defaultSnapshot.originalPlannedStartAt(),
                defaultSnapshot.timezone(),
                defaultSnapshot.localStart(),
                defaultSnapshot.localEnd(),
                defaultSnapshot.startUtcOffset(),
                defaultSnapshot.endUtcOffset(),
                "COACH_REQUIRED",
                null,
                null,
                TrainingContinuityState.RECENT_CONSISTENT,
                now
        );

        Instant futureStart = now.plus(2, ChronoUnit.DAYS);
        Instant futureEnd = futureStart.plus(1, ChronoUnit.HOURS);
        String payload = createValidPayload(
                occurrenceId,
                "SELF_PERFORMABLE", // degraded!
                "2026-11-18T09:00:00",
                "2026-11-18T10:00:00",
                "+07:00",
                "+07:00",
                futureStart.toString(),
                futureEnd.toString()
        );

        var result = validator.validate(payload, coachSnapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("COACH_REQUIRED supervision requirement cannot be degraded to SELF_PERFORMABLE");
    }

    @Test
    @DisplayName("Preserving COACH_REQUIRED passes validation")
    void preservingCoachRequired_passes() {
        F05SourceSnapshot coachSnapshot = new F05SourceSnapshot(
                defaultSnapshot.studentId(),
                defaultSnapshot.coachingPeriodId(),
                "HUMAN_COACH",
                UUID.randomUUID(),
                occurrenceId,
                0L,
                "SCHEDULED",
                defaultSnapshot.planId(),
                defaultSnapshot.planVersionId(),
                defaultSnapshot.planSessionId(),
                "Heavy Deadlift",
                defaultSnapshot.plannedStartAt(),
                defaultSnapshot.plannedEndAt(),
                defaultSnapshot.originalPlannedStartAt(),
                defaultSnapshot.timezone(),
                defaultSnapshot.localStart(),
                defaultSnapshot.localEnd(),
                defaultSnapshot.startUtcOffset(),
                defaultSnapshot.endUtcOffset(),
                "COACH_REQUIRED",
                null,
                null,
                TrainingContinuityState.RECENT_CONSISTENT,
                now
        );

        Instant futureStart = now.plus(2, ChronoUnit.DAYS);
        Instant futureEnd = futureStart.plus(1, ChronoUnit.HOURS);
        String payload = createValidPayload(
                occurrenceId,
                "COACH_REQUIRED",
                "2026-11-18T09:00:00",
                "2026-11-18T10:00:00",
                "+07:00",
                "+07:00",
                futureStart.toString(),
                futureEnd.toString()
        );

        var result = validator.validate(payload, coachSnapshot);
        assertThat(result.isValid()).isTrue();
    }

    @Test
    @DisplayName("Destination localEnd before localStart fails validation")
    void destinationEndBeforeStart_fails() {
        Instant futureStart = now.plus(2, ChronoUnit.DAYS);
        Instant futureEnd = futureStart.plus(1, ChronoUnit.HOURS);
        String payload = createValidPayload(
                occurrenceId,
                "SELF_PERFORMABLE",
                "2026-11-18T10:00:00",
                "2026-11-18T09:00:00", // end before start!
                "+07:00",
                "+07:00",
                futureStart.toString(),
                futureEnd.toString()
        );

        var result = validator.validate(payload, defaultSnapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("destination localEnd must be strictly after localStart");
    }

    @Test
    @DisplayName("Destination with past proposedStartAt fails validation")
    void pastProposedStart_fails() {
        Instant pastStart = now.minus(2, ChronoUnit.DAYS);
        Instant pastEnd = pastStart.plus(1, ChronoUnit.HOURS);
        String payload = createValidPayload(
                occurrenceId,
                "SELF_PERFORMABLE",
                "2026-11-14T09:00:00",
                "2026-11-14T10:00:00",
                "+07:00",
                "+07:00",
                pastStart.toString(),
                pastEnd.toString()
        );

        var result = validator.validate(payload, defaultSnapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Proposed reschedule slot cannot be in the past");
    }

    @Test
    @DisplayName("Destination exceeding 90 days fails validation")
    void slotExceeding90Days_fails() {
        Instant farFutureStart = now.plus(100, ChronoUnit.DAYS);
        Instant farFutureEnd = farFutureStart.plus(1, ChronoUnit.HOURS);
        String payload = createValidPayload(
                occurrenceId,
                "SELF_PERFORMABLE",
                "2027-03-01T09:00:00",
                "2027-03-01T10:00:00",
                "+07:00",
                "+07:00",
                farFutureStart.toString(),
                farFutureEnd.toString()
        );

        var result = validator.validate(payload, defaultSnapshot);
        assertThat(result.isValid()).isFalse();
        assertThat(result.failureReason()).contains("Proposed reschedule slot cannot exceed 90 days");
    }
}
