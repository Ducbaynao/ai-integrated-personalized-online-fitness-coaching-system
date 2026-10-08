package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.HistoricalAuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import org.springframework.stereotype.Component;

import java.time.Instant;
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
        if (actorId != null && actorId.equals(studentId)) {
            requireStudentMutation(actorId, studentId);
            return;
        }
        if (actorId == null || performedStartedAt == null || !coaching.evaluateHistorical(
                new HistoricalAuthorityRequest(actorId, studentId, DataScope.WORKOUT_HISTORY,
                        DataAccessLevel.VIEW, performedStartedAt)).allowed()) {
            throw denied();
        }
    }

    private static WorkoutExecutionFailure denied() {
        return new WorkoutExecutionFailure(WorkoutExecutionError.WORKOUT_EXECUTION_ACCESS_DENIED,
                "Workout execution is not available");
    }
}
