package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionBaselineQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort.SessionSummary;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
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

    public WorkoutScheduleCalendarService(WorkoutScheduleCalendarReadPort store,
                                          WorkoutExecutionAccessPolicy access,
                                          WorkoutOccurrenceActionPolicy actions,
                                          WorkoutExecutionBaselineQuery baselines) {
        this.store = store; this.access = access; this.actions = actions; this.baselines = baselines;
    }

    @Override @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page listMine(UUID actorId, Instant from, Instant until, int page, int size) {
        if (actorId == null || from == null || until == null || !from.isBefore(until)
                || Duration.between(from, until).compareTo(Duration.ofDays(93)) > 0
                || page < 0 || size < 1 || size > 100)
            throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_WINDOW_INVALID", 400);
        try { access.requireStudentMutation(actorId, actorId); }
        catch (WorkoutExecutionFailure denied) {
            throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_ACCESS_DENIED", 403);
        }
        var raw = store.findStudentWindow(actorId, from, until, page, size);
        boolean hasInProgress = store.hasInProgressExecution(actorId);
        List<Occurrence> items = raw.items().stream().map(row -> {
            List<String> available = new ArrayList<>(2);
            // Legacy rows without a source session cannot pass the B05 snapshot/skip lookup.
            if (row.sessionSummary() != null) {
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
