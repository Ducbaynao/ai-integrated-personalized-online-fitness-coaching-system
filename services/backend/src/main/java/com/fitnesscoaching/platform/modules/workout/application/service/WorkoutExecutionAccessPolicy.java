package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.HistoricalAuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Component
public class WorkoutExecutionAccessPolicy {
    private final UserAccountStatusQuery accounts;
    private final StudentCapabilityQuery students;
    private final CoachingAuthorityQuery coaching;

    public WorkoutExecutionAccessPolicy(UserAccountStatusQuery accounts, StudentCapabilityQuery students,
                                        CoachingAuthorityQuery coaching) {
        this.accounts = accounts;
        this.students = students;
        this.coaching = coaching;
    }

    public void requireStudentMutation(UUID actorId, UUID studentId) {
        if (actorId == null || studentId == null || !actorId.equals(studentId)
                || accounts.getAccountStatus(actorId).orElse(null) != AccountStatus.ACTIVE
                || !students.hasProfile(actorId)) {
            throw denied();
        }
    }

    public void requireRead(UUID actorId, UUID studentId, Instant performedStartedAt) {
        if (!canRead(actorId, studentId, performedStartedAt)) {
            throw denied();
        }
    }

    public boolean canRead(UUID actorId, UUID studentId, Instant performedStartedAt) {
        if (actorId != null && actorId.equals(studentId)) {
            try {
                requireStudentMutation(actorId, studentId);
                return true;
            } catch (WorkoutExecutionFailure denied) {
                return false;
            }
        }
        return actorId != null && studentId != null && performedStartedAt != null && coaching.evaluateHistorical(
                new HistoricalAuthorityRequest(actorId, studentId, DataScope.WORKOUT_HISTORY,
                        DataAccessLevel.VIEW, performedStartedAt)).allowed();
    }

    public Optional<HistoryWindow> historyWindow(UUID actorId, UUID studentId) {
        if (actorId == null || studentId == null) return Optional.empty();
        if (actorId.equals(studentId)) {
            try {
                requireStudentMutation(actorId, studentId);
                return Optional.of(HistoryWindow.unbounded());
            } catch (WorkoutExecutionFailure denied) {
                return Optional.empty();
            }
        }
        var decision = coaching.evaluateCurrent(new AuthorityRequest(actorId, studentId,
                DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW));
        if (!decision.allowed() || decision.historyFrom() == null) return Optional.empty();
        return Optional.of(new HistoryWindow(decision.historyFrom(), decision.historyUntil()));
    }

    public record HistoryWindow(Instant from, Instant until) {
        static HistoryWindow unbounded() { return new HistoryWindow(null, null); }
    }

    private static WorkoutExecutionFailure denied() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED,
                "Workout execution is not available");
    }
}
