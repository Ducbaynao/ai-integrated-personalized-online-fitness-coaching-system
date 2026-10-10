package com.fitnesscoaching.platform.modules.workout.adapter.in.web;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.*;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

import java.time.*;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkoutScheduleBatchWebTest {
    @Test void controllerPassesActorAndTokensAndReturnsDeclaredCreatedPayload() {
        UUID student = UUID.randomUUID(), plan = UUID.randomUUID(), version = UUID.randomUUID();
        UUID session = UUID.randomUUID(), itemId = UUID.randomUUID(), occurrence = UUID.randomUUID();
        LocalDate monday = LocalDate.of(2026, 10, 12);
        LocalDateTime local = monday.atTime(18, 30);
        ConfirmedBatch expected = new ConfirmedBatch(UUID.randomUUID(), student, plan, version, 1,
                monday, "Asia/Ho_Chi_Minh", "STUDENT_DIRECT", null, student, Instant.now(),
                List.of(new ConfirmedItem(itemId, occurrence, 0, session, 1, 1, 1, local,
                        local.plusHours(1), "+07:00", "+07:00", local.toInstant(ZoneOffset.ofHours(7)),
                        local.plusHours(1).toInstant(ZoneOffset.ofHours(7)), "SELF_ALLOWED")), false);
        final ConfirmBatch[] received = new ConfirmBatch[1];
        WorkoutScheduleBatchUseCase command = input -> { received[0] = input; return expected; };
        var controller = new WorkoutScheduleBatchController(command);
        var request = new WorkoutScheduleBatchController.Request(plan, version, 7L, monday,
                "Asia/Ho_Chi_Minh", List.of(new WorkoutScheduleBatchController.InputItem(itemId, session,
                1, 1, 1, local, local.plusHours(1), "+07:00", "+07:00")), "stable-key");
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject(student.toString()).build();
        var response = controller.confirm(jwt, request);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getBody()).isEqualTo(expected);
        assertThat(received[0].actorId()).isEqualTo(student);
        assertThat(received[0].expectedPlanAggregateVersion()).isEqualTo(7);
        assertThat(received[0].items().getFirst().clientItemId()).isEqualTo(itemId);
    }

    @Test void unknownLegacyEndErrorIsBatchLevelAndDoesNotRevealOccurrence() {
        var advice = new WorkoutScheduleBatchErrorAdvice(Clock.systemUTC());
        var response = advice.handle(new WorkoutScheduleFailure("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN", 409));
        assertThat(response.getStatusCode().value()).isEqualTo(409);
        assertThat(response.getBody().errorCode()).isEqualTo("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN");
        assertThat(response.getBody().itemErrors()).isEmpty();
        assertThat(response.getBody().message()).doesNotContain("occurrence", "plannedStartAt");
    }
}
