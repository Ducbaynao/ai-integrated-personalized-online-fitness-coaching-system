package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityReason;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkoutExecutionAccessPolicyTest {
    private final UserAccountStatusQuery accounts = mock(UserAccountStatusQuery.class);
    private final StudentCapabilityQuery students = mock(StudentCapabilityQuery.class);
    private final CoachingAuthorityQuery coaching = mock(CoachingAuthorityQuery.class);
    private final WorkoutExecutionAccessPolicy policy = new WorkoutExecutionAccessPolicy(accounts, students, coaching);

    @Test
    void onlyActiveOwningStudentMayMutate() {
        UUID student = UUID.randomUUID();
        when(accounts.getAccountStatus(student)).thenReturn(Optional.of(AccountStatus.ACTIVE));
        when(students.hasProfile(student)).thenReturn(true);
        policy.requireStudentMutation(student, student);

        assertDenied(() -> policy.requireStudentMutation(UUID.randomUUID(), student));
    }

    @Test
    void trainerReadDelegatesToWorkoutHistoryHistoricalAuthorityAndFailsClosed() {
        UUID trainer = UUID.randomUUID(), student = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        when(coaching.evaluateHistorical(any())).thenReturn(AuthorityDecision.denied(AuthorityReason.PERMISSION_NOT_GRANTED));
        assertDenied(() -> policy.requireRead(trainer, student, occurredAt));

        when(coaching.evaluateHistorical(any())).thenReturn(new AuthorityDecision(true, AuthorityReason.ALLOWED,
                UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), 1, null, null, null));
        policy.requireRead(trainer, student, occurredAt);
    }

    @Test
    void trainerHistoryWindowRequiresCurrentWorkoutHistoryViewAndPreservesHalfOpenBounds() {
        UUID trainer = UUID.randomUUID(), student = UUID.randomUUID();
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant until = Instant.parse("2026-02-01T00:00:00Z");
        when(coaching.evaluateCurrent(new AuthorityRequest(trainer, student,
                DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW))).thenReturn(new AuthorityDecision(
                true, AuthorityReason.ALLOWED, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), 1,
                DataAccessLevel.VIEW, from, until));

        var window = policy.historyWindow(trainer, student);

        assertThat(window).isPresent();
        assertThat(window.orElseThrow().from()).isEqualTo(from);
        assertThat(window.orElseThrow().until()).isEqualTo(until);
    }

    @Test
    void missingHistoryFromFailsClosedForTrainerList() {
        UUID trainer = UUID.randomUUID(), student = UUID.randomUUID();
        when(coaching.evaluateCurrent(any())).thenReturn(new AuthorityDecision(
                true, AuthorityReason.ALLOWED, UUID.randomUUID(), 1, UUID.randomUUID(), UUID.randomUUID(), 1,
                DataAccessLevel.VIEW, null, null));

        assertThat(policy.historyWindow(trainer, student)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = AuthorityReason.class, names = {
            "RELATIONSHIP_NOT_ACTIVE",
            "PERIOD_NOT_EFFECTIVE",
            "PERMISSION_NOT_GRANTED",
            "RESOURCE_OUTSIDE_HISTORY_WINDOW"
    })
    void formerPausedEndedRevokedAndOutOfWindowTrainerReadsFailClosed(AuthorityReason reason) {
        UUID trainer = UUID.randomUUID(), student = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-01-01T00:00:00Z");
        when(coaching.evaluateHistorical(any())).thenReturn(AuthorityDecision.denied(reason));

        assertThat(policy.canRead(trainer, student, occurredAt)).isFalse();
        assertDenied(() -> policy.requireRead(trainer, student, occurredAt));
    }

    private static void assertDenied(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOf(WorkoutExecutionFailure.class)
                .extracting(value -> ((WorkoutExecutionFailure) value).error())
                .isEqualTo(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED);
    }
}
