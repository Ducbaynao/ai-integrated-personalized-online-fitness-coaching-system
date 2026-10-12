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
import com.fitnesscoaching.platform.modules.schedule.application.port.in.LinkedWorkoutAppointmentQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleChangePersistencePort;
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
    private final WorkoutScheduleChangePersistencePort changes;
    private final LinkedWorkoutAppointmentQuery appointments;

    public WorkoutScheduleCalendarService(WorkoutScheduleCalendarReadPort store,
                                          WorkoutExecutionAccessPolicy access,
                                          WorkoutOccurrenceActionPolicy actions,
                                          WorkoutExecutionBaselineQuery baselines,
                                          CoachingAuthorityQuery coaching,
                                          WorkoutScheduleChangePersistencePort changes,
                                          LinkedWorkoutAppointmentQuery appointments) {
        this.store = store; this.access = access; this.actions = actions; this.baselines = baselines;
        this.coaching = coaching; this.changes = changes; this.appointments = appointments;
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

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Occurrence detail(UUID actorId, UUID occurrenceId) {
        if (actorId == null || occurrenceId == null)
            throw new WorkoutScheduleFailure("PLANNED_WORKOUT_NOT_FOUND", 404);
        var row = store.findOccurrence(occurrenceId);
        if (row == null) throw new WorkoutScheduleFailure("PLANNED_WORKOUT_NOT_FOUND", 404);
        boolean mine = actorId.equals(row.studentId());
        if (mine) {
            try { access.requireStudentMutation(actorId, row.studentId()); }
            catch (WorkoutExecutionFailure denied) {
                throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_ACCESS_DENIED", 403);
            }
        } else if (!coaching.evaluateCurrent(new AuthorityRequest(actorId, row.studentId(),
                DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW)).allowed()) {
            throw new WorkoutScheduleFailure("PLANNED_WORKOUT_NOT_FOUND", 404);
        }
        return present(row, mine, mine && store.hasInProgressExecution(row.studentId()),
                changes.databaseNow());
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
        Instant now = includeStudentActions ? changes.databaseNow() : null;
        List<Occurrence> items = raw.items().stream().map(row -> present(row, includeStudentActions,
                hasInProgress, now)).toList();
        int totalPages = (int) ((raw.totalItems() + size - 1) / size);
        return new Page(items, page, size, raw.totalItems(), totalPages);
    }

    private Occurrence present(WorkoutScheduleCalendarReadPort.Row row, boolean includeStudentActions,
                               boolean hasInProgress, Instant now) {
            List<String> available = new ArrayList<>(2);
            // Legacy rows without a source session cannot pass the B05 snapshot/skip lookup.
            if (includeStudentActions && row.sessionSummary() != null) {
                if (actions.canStart(row.occurrenceId(), row.status(), row.executionStartedAt(),
                        row.supervisionRequirement(), row.hasValidSupervisionOverride(), hasInProgress)
                        && baselines.isReady(row.occurrenceId(), row.sessionSummary().planSessionId()))
                    available.add("START");
                if (actions.canSkip(row.status(), row.executionStartedAt())) available.add("SKIP");
            }
            if (includeStudentActions && "SCHEDULED".equals(row.status())
                    && row.executionStartedAt() == null && !appointments.hasAnyLinked(row.occurrenceId())
                    && !changes.hasSuccessor(row.occurrenceId())) {
                if (row.plannedStartAt().isAfter(now) && row.planSummary() != null && row.sessionSummary() != null
                        && row.timezone() != null && row.localStart() != null && row.localEnd() != null
                        && row.startUtcOffset() != null && row.endUtcOffset() != null
                        && !"COACH_REQUIRED".equals(row.supervisionRequirement())
                        && !changes.hasOtherScheduledUnknownEnd(row.studentId(), row.occurrenceId()))
                    available.add("RESCHEDULE");
                available.add("CANCEL");
            }
            return new Occurrence(row.occurrenceId(), row.occurrenceVersion(), row.studentId(), row.status(),
                    row.plannedStartAt(), row.originalPlannedStartAt(), row.rescheduledFromOccurrenceId(),
                    row.replacementOccurrenceId(), row.plannedEndAt(), row.timezone(),
                    row.localStart(), row.localEnd(), row.startUtcOffset(), row.endUtcOffset(),
                    row.planSummary(), presentedSession(row.sessionSummary()), List.copyOf(available));
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
