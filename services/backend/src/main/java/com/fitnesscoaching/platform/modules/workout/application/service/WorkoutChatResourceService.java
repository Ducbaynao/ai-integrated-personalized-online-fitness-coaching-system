package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutChatResourceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class WorkoutChatResourceService implements WorkoutChatResourceQuery {
    private final WorkoutPlanQueryUseCase plans;
    private final WorkoutScheduleCalendarQuery calendar;

    public WorkoutChatResourceService(WorkoutPlanQueryUseCase plans, WorkoutScheduleCalendarQuery calendar) {
        this.plans = plans;
        this.calendar = calendar;
    }

    @Override public boolean canReadPublishedVersion(UUID actor, UUID student, UUID period,
                                                      UUID planId, UUID versionId) {
        if (planId == null || versionId == null) return false;
        try {
            var plan = plans.detail(actor, planId).plan();
            if (!student.equals(plan.studentId()) || !period.equals(plan.coachingPeriodId())) return false;
            return plans.version(actor, planId, versionId).version().lockedAt() != null;
        } catch (WorkoutPlanFailure denied) {
            return false;
        }
    }

    @Override public boolean canReadOccurrence(UUID actor, UUID student, UUID period, UUID occurrenceId) {
        if (occurrenceId == null) return false;
        try {
            var occurrence = calendar.detail(actor, occurrenceId);
            return student.equals(occurrence.studentId()) && occurrence.planSummary() != null
                    && period.equals(plans.detail(actor, occurrence.planSummary().planId()).plan().coachingPeriodId());
        } catch (WorkoutPlanFailure | WorkoutScheduleFailure denied) {
            return false;
        }
    }
}
