package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionBaselineQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort.SessionSummary;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class WorkoutScheduleCalendarService implements WorkoutScheduleCalendarQuery {
    private final WorkoutScheduleCalendarReadPort store;
    private final WorkoutExecutionAccessPolicy access;
    private final WorkoutOccurrenceActionPolicy actions;
    private final WorkoutExecutionBaselineQuery baselines;
    private final CoachingAuthorityQuery coaching;

    public WorkoutScheduleCalendarService(WorkoutScheduleCalendarReadPort store,
                                          WorkoutExecutionAccessPolicy access,
                                          WorkoutOccurrenceActionPolicy actions,
                                          WorkoutExecutionBaselineQuery baselines,
                                          CoachingAuthorityQuery coaching) {
        this.store = store; this.access = access; this.actions = actions; this.baselines = baselines;
        this.coaching = coaching;
    }

    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page listMine(UUID actorId, Instant from, Instant until, int page, int size) {
        validateWindow(from, until, page, size);
        try { access.requireStudentMutation(actorId, actorId); }
        catch (WorkoutExecutionFailure denied) {
            throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_ACCESS_DENIED", 403);
        }
        return pageForStudent(actorId, from, until, page, size, true);
    }

    // Coaching's current-authority query locks the relationship/grant boundary; this transaction
    // must remain writable to permit that PostgreSQL lock and hold it through the projection read.
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page listForTrainer(UUID actorId, UUID studentId, Instant from, Instant until, int page, int size) {
        validateWindow(from, until, page, size);
        if (actorId == null || studentId == null || !coaching.evaluateCurrent(new AuthorityRequest(
                actorId, studentId, DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW)).allowed())
            throw new WorkoutScheduleFailure("PLANNED_WORKOUT_NOT_FOUND", 404);
        return pageForStudent(studentId, from, until, page, size, false);
    }

    private static void validateWindow(Instant from, Instant until, int page, int size) {
        if (from == null || until == null || !from.isBefore(until)
                || Duration.between(from, until).compareTo(Duration.ofDays(93)) > 0
                || page < 0 || size < 1 || size > 100)
            throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_WINDOW_INVALID", 400);
    }

    private Page pageForStudent(UUID studentId, Instant from, Instant until, int page, int size,
                                boolean includeStudentActions) {
        var raw = store.findStudentWindow(studentId, from, until, page, size);
        boolean hasInProgress = includeStudentActions && store.hasInProgressExecution(studentId);
        List<Occurrence> items = raw.items().stream().map(row -> {
            List<String> available = new ArrayList<>(2);
            // Legacy rows without a source session cannot pass the B05 snapshot/skip lookup.
            if (includeStudentActions && row.sessionSummary() != null) {
                if (actions.canStart(row.occurrenceId(), row.status(), row.executionStartedAt(),
                        row.supervisionRequirement(), row.hasValidSupervisionOverride(), hasInProgress)
                        && baselines.isReady(row.occurrenceId(), row.sessionSummary().planSessionId()))
                    available.add("START");
                if (actions.canSkip(row.status(), row.executionStartedAt())) available.add("SKIP");
            }
            return new Occurrence(row.occurrenceId(), row.occurrenceVersion(), row.studentId(), row.status(),
                    row.plannedStartAt(), row.originalPlannedStartAt(), row.plannedEndAt(), row.timezone(),
                    row.localStart(), row.localEnd(), row.startUtcOffset(), row.endUtcOffset(),
                    row.planSummary(), presentedSession(row.sessionSummary()), List.copyOf(available));
        }).toList();
        int totalPages = (int) ((raw.totalItems() + size - 1) / size);
        return new Page(items, page, size, raw.totalItems(), totalPages);
    }

    private static SessionSummary presentedSession(SessionSummary source) {
        if (source == null) return null;
        String supervision = switch (source.supervisionRequirement()) {
            case "SELF_ALLOWED" -> "SELF_PERFORMABLE";
            case "COACH_PREFERRED" -> "COACH_OPTIONAL";
            case "COACH_REQUIRED" -> "COACH_REQUIRED";
            case null -> null;
            default -> throw new IllegalStateException("Unsupported workout supervision value");
        };
        return new SessionSummary(source.planSessionId(), source.weekNumber(), source.dayNumber(),
                source.sequenceNumber(), source.name(), source.focus(),
                source.estimatedDurationMinutes(), supervision);
    }
}
