package com.fitnesscoaching.platform.modules.schedule.application.service;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.AppointmentWorkoutLinkQuery;
import com.fitnesscoaching.platform.modules.notification.LifecycleNotificationWriter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Schedule-owned Appointment commands; no Workout or Coaching repository is accessed directly. */
@Service
public class AppointmentService implements com.fitnesscoaching.platform.modules.schedule.application.port.in.AppointmentChatResourceQuery {
    private final JdbcTemplate jdbc;
    private final CurrentCoachingContextQuery contexts;
    private final StudentCapabilityQuery students;
    private final TrainerCoachingEligibilityQuery trainers;
    private final UserAccountStatusQuery accounts;
    private final UserRoleQuery roles;
    private final ObjectMapper mapper;
    private final AppointmentWorkoutLinkQuery workouts;
    private final LifecycleNotificationWriter notifications;

    public AppointmentService(JdbcTemplate jdbc, CurrentCoachingContextQuery contexts,
                              StudentCapabilityQuery students, TrainerCoachingEligibilityQuery trainers,
                              UserAccountStatusQuery accounts, UserRoleQuery roles, ObjectMapper mapper,
                              AppointmentWorkoutLinkQuery workouts, LifecycleNotificationWriter notifications) {
        this.jdbc = jdbc; this.contexts = contexts; this.students = students; this.trainers = trainers;
        this.accounts = accounts; this.roles = roles; this.mapper = mapper; this.workouts = workouts;
        this.notifications = notifications;
    }

    public record LocalInterval(LocalDateTime localStart, LocalDateTime localEnd, String timezone,
                                String startUtcOffset, String endUtcOffset) {}
    public record RequestCommand(UUID actorId, UUID studentId, UUID plannedWorkoutId,
                                 LocalInterval interval, UUID commandKey) {}
    public record DecisionCommand(UUID actorId, UUID requestId, String decision,
                                  long expectedRequestVersion, UUID commandKey) {}
    public record CancelCommand(UUID actorId, UUID appointmentId, long expectedAppointmentVersion,
                                String reason, UUID commandKey) {}
    public record RescheduleCommand(UUID actorId, UUID appointmentId, long expectedAppointmentVersion,
                                    LocalInterval interval, String reason, UUID commandKey) {}
    public record RescheduleDecisionCommand(UUID actorId, UUID requestId, String decision,
                                            long expectedRequestVersion, long expectedAppointmentVersion,
                                            UUID commandKey) {}
    public record RequestView(UUID requestId, String kind, UUID studentId, UUID trainerId,
                              UUID coachingPeriodId, UUID requestedBy, String status, long version,
                              UUID appointmentId, UUID plannedWorkoutId, Long observedAppointmentVersion,
                              LocalInterval interval,
                              Instant createdAt, Instant decidedAt) {}
    public record AppointmentView(UUID appointmentId, long version, UUID studentId, UUID trainerId,
                                  UUID coachingPeriodId, UUID plannedWorkoutId, String status,
                                  Instant startAt, Instant endAt, String timezone, LocalDateTime localStart,
                                  LocalDateTime localEnd, String startUtcOffset, String endUtcOffset) {}

    @Override public boolean canRead(UUID actor, UUID student, UUID period, UUID appointmentId) {
        if (appointmentId == null) return false;
        try {
            var row = appointmentDetail(actor, appointmentId);
            return student.equals(row.studentId()) && period.equals(row.coachingPeriodId());
        } catch (AppointmentFailure denied) {
            return false;
        }
    }
    public record CommandResult(RequestView request, AppointmentView appointment, boolean replayed) {
        CommandResult asReplay() { return new CommandResult(request, appointment, true); }
    }
    public record AppointmentCommandResult(AppointmentView appointment, boolean replayed) {
        AppointmentCommandResult asReplay() { return new AppointmentCommandResult(appointment, true); }
    }
    public record Page<T>(List<T> items, int page, int size, long totalItems) {}
    public record EventView(UUID eventId, UUID appointmentId, String eventType, UUID actorId,
                            String reason, JsonNode oldState, JsonNode newState, Instant occurredAt) {}

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Page<RequestView> listRequests(UUID actor, String status, int page, int size) {
        validatePage(page, size);
        if (status != null && !List.of("PENDING", "ACCEPTED", "REJECTED").contains(status)) throw invalid();
        boolean student = roles.hasActiveRole(actor, "STUDENT");
        if (student) requireStudent(actor);
        else requireTrainer(actor);
        List<UUID> candidates = jdbc.query("SELECT id FROM fitness.appointment_requests_v2 WHERE "
                        + (student ? "student_id=?" : "trainer_id=?")
                        + " ORDER BY created_at DESC,id DESC", (rs, row) -> rs.getObject(1, UUID.class), actor);
        List<RequestView> visible = new ArrayList<>();
        for (UUID id : candidates) {
            RequestView item = loadRequest(id, false);
            if (!student && !currentTrainerPeriod(actor, item.studentId(), item.coachingPeriodId())) continue;
            if (status == null || status.equals(item.status())) visible.add(item);
        }
        return slice(visible, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Page<AppointmentView> listAppointments(UUID actor, Instant from, Instant until, int page, int size) {
        validatePage(page, size);
        if (from == null || until == null || !from.isBefore(until)) throw invalid();
        boolean student = roles.hasActiveRole(actor, "STUDENT");
        if (student) requireStudent(actor);
        else requireTrainer(actor);
        List<UUID> candidates = jdbc.query("SELECT id FROM fitness.appointments WHERE "
                        + (student ? "student_id=?" : "trainer_id=?")
                        + " AND start_at>=? AND start_at<? ORDER BY start_at ASC,id ASC",
                (rs, row) -> rs.getObject(1, UUID.class), actor, Timestamp.from(from), Timestamp.from(until));
        List<AppointmentView> visible = new ArrayList<>();
        for (UUID id : candidates) {
            AppointmentView item = loadAppointment(id);
            if (student || (item.coachingPeriodId() != null
                    && currentTrainerPeriod(actor, item.studentId(), item.coachingPeriodId()))) visible.add(item);
        }
        return slice(visible, page, size);
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public AppointmentView appointmentDetail(UUID actor, UUID id) {
        AppointmentView item = loadAppointmentOrConceal(id);
        if (actor.equals(item.studentId())) requireStudent(actor);
        else if (actor.equals(item.trainerId())) current(actor, item.studentId(), item.coachingPeriodId());
        else throw notFound();
        return item;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public Page<EventView> history(UUID actor, UUID appointmentId, int page, int size) {
        validatePage(page, size);
        appointmentDetail(actor, appointmentId);
        List<EventView> events = jdbc.query("""
                SELECT id,appointment_id,event_type,actor_id,reason,old_state::text,new_state::text,occurred_at
                FROM fitness.appointment_events_v2 WHERE appointment_id=?
                ORDER BY occurred_at ASC,id ASC
                """, (rs, row) -> new EventView(rs.getObject("id", UUID.class),
                rs.getObject("appointment_id", UUID.class), rs.getString("event_type"),
                rs.getObject("actor_id", UUID.class), rs.getString("reason"),
                parseAudit(rs.getString("old_state")), parseAudit(rs.getString("new_state")),
                rs.getTimestamp("occurred_at").toInstant()), appointmentId);
        return slice(events, page, size);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public AppointmentCommandResult cancel(CancelCommand command) {
        if (command == null || command.actorId() == null || command.appointmentId() == null
                || command.commandKey() == null || command.expectedAppointmentVersion() < 0
                || command.reason() == null || command.reason().isBlank() || command.reason().length() > 1000)
            throw invalid();
        AppointmentView observed = loadAppointmentOrConceal(command.appointmentId());
        if (!command.actorId().equals(observed.studentId()) && !command.actorId().equals(observed.trainerId()))
            throw notFound();
        if (observed.coachingPeriodId() == null)
            throw new AppointmentFailure("APPOINTMENT_PERIOD_INACTIVE", 409);
        currentForMutation(command.actorId(), observed.studentId(), observed.coachingPeriodId());
        lockCommandKey(command.actorId(), command.commandKey());
        String hash = hash("CANCEL", command.appointmentId(), command.expectedAppointmentVersion(), command.reason());
        AppointmentCommandResult replay = appointmentReceipt(command.actorId(), command.commandKey(), "CANCEL", hash);
        if (replay != null) return replay.asReplay();
        if (observed.plannedWorkoutId() != null) {
            var linked = workouts.lockForAppointment(observed.plannedWorkoutId());
            if (linked.isEmpty() || ("COACH_REQUIRED".equals(linked.get().supervisionRequirement())
                    && "SCHEDULED".equals(linked.get().status())))
                throw new AppointmentFailure("APPOINTMENT_COACH_REQUIRED_DEPENDENCY", 409);
        }
        AppointmentView locked = lockAppointment(command.appointmentId());
        if (!java.util.Objects.equals(locked.plannedWorkoutId(), observed.plannedWorkoutId())
                || !java.util.Objects.equals(locked.coachingPeriodId(), observed.coachingPeriodId()))
            throw new AppointmentFailure("APPOINTMENT_VERSION_STALE", 409);
        if (locked.version() != command.expectedAppointmentVersion())
            throw new AppointmentFailure("APPOINTMENT_VERSION_STALE", 409);
        if (!"CONFIRMED".equals(locked.status()))
            throw new AppointmentFailure("APPOINTMENT_STATUS_CONFLICT", 409);
        if (!locked.startAt().isAfter(databaseNow()))
            throw new AppointmentFailure("APPOINTMENT_START_PASSED", 409);
        jdbc.update("""
                UPDATE fitness.appointments SET status='CANCELLED',version=version+1,updated_at=clock_timestamp()
                WHERE id=? AND version=? AND status='CONFIRMED'
                """, command.appointmentId(), locked.version());
        AppointmentView updated = loadAppointment(command.appointmentId());
        UUID sourceEvent = event(updated.appointmentId(), null, "APPOINTMENT_CANCELLED", command.actorId(),
                command.reason(), locked, updated);
        notify(sourceEvent, "APPOINTMENT_CANCELLED", "APPOINTMENT", updated.appointmentId(),
                updated.studentId(), updated.trainerId(), updated.coachingPeriodId(), command.actorId(),
                command.actorId().equals(updated.studentId()) ? updated.trainerId() : updated.studentId());
        AppointmentCommandResult result = new AppointmentCommandResult(updated, false);
        saveReceipt(command.actorId(), command.commandKey(), "CANCEL", hash, null, updated.appointmentId(), result);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult requestReschedule(RescheduleCommand command) {
        if (command == null || command.actorId() == null || command.appointmentId() == null
                || command.commandKey() == null || command.interval() == null
                || command.expectedAppointmentVersion() < 0) throw invalid();
        AppointmentView observed = loadAppointmentOrConceal(command.appointmentId());
        if (!command.actorId().equals(observed.studentId()) && !command.actorId().equals(observed.trainerId()))
            throw notFound();
        if (observed.coachingPeriodId() == null)
            throw new AppointmentFailure("APPOINTMENT_PERIOD_INACTIVE", 409);
        currentForMutation(command.actorId(), observed.studentId(), observed.coachingPeriodId());
        var interval = checkedInterval(command.interval());
        lockCommandKey(command.actorId(), command.commandKey());
        String hash = hash("RESCHEDULE_REQUEST", command.appointmentId(),
                command.expectedAppointmentVersion(), command.interval(), command.reason());
        CommandResult replay = receipt(command.actorId(), command.commandKey(), "RESCHEDULE_REQUEST", hash);
        if (replay != null) return replay.asReplay();
        AppointmentView locked = lockAppointment(command.appointmentId());
        if (locked.version() != command.expectedAppointmentVersion())
            throw new AppointmentFailure("APPOINTMENT_VERSION_STALE", 409);
        if (!"CONFIRMED".equals(locked.status()))
            throw new AppointmentFailure("APPOINTMENT_STATUS_CONFLICT", 409);
        Instant now = databaseNow();
        if (!locked.startAt().isAfter(now) || !interval.startAt().isAfter(now))
            throw new AppointmentFailure("APPOINTMENT_START_PASSED", 409);
        UUID id = UUID.randomUUID();
        try { jdbc.update("""
                INSERT INTO fitness.appointment_requests_v2
                (id,kind,student_id,trainer_id,coaching_period_id,planned_workout_id,appointment_id,
                 observed_appointment_version,requested_by,local_start,local_end,timezone,
                 start_utc_offset,end_utc_offset,start_at,end_at)
                VALUES (?,'RESCHEDULE',?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, locked.studentId(), locked.trainerId(), locked.coachingPeriodId(),
                locked.plannedWorkoutId(), locked.appointmentId(), locked.version(), command.actorId(),
                command.interval().localStart(), command.interval().localEnd(), command.interval().timezone(),
                command.interval().startUtcOffset(), command.interval().endUtcOffset(),
                Timestamp.from(interval.startAt()), Timestamp.from(interval.endAt()));
        } catch (DataIntegrityViolationException conflict) {
            if (sqlState(conflict, "23505"))
                throw new AppointmentFailure("APPOINTMENT_REQUEST_ALREADY_PENDING", 409);
            throw conflict;
        }
        UUID sourceEvent = event(locked.appointmentId(), id, "RESCHEDULE_REQUESTED", command.actorId(), command.reason());
        notify(sourceEvent, "APPOINTMENT_RESCHEDULE_PENDING", "APPOINTMENT_REQUEST", id,
                locked.studentId(), locked.trainerId(), locked.coachingPeriodId(), command.actorId(),
                command.actorId().equals(locked.studentId()) ? locked.trainerId() : locked.studentId());
        CommandResult result = new CommandResult(loadRequest(id, false), null, false);
        saveReceipt(command.actorId(), command.commandKey(), "RESCHEDULE_REQUEST", hash, id,
                locked.appointmentId(), result);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult decideReschedule(RescheduleDecisionCommand command) {
        if (command == null || command.actorId() == null || command.requestId() == null
                || command.commandKey() == null || command.expectedRequestVersion() < 0
                || command.expectedAppointmentVersion() < 0
                || !List.of("ACCEPT", "REJECT").contains(command.decision())) throw invalid();
        RequestView observed = loadRequest(command.requestId(), false);
        if (!"RESCHEDULE".equals(observed.kind())) throw notFound();
        UUID counterparty = observed.requestedBy().equals(observed.studentId())
                ? observed.trainerId() : observed.studentId();
        if (!counterparty.equals(command.actorId())) throw notFound();
        currentForMutation(command.actorId(), observed.studentId(), observed.coachingPeriodId());
        lockCommandKey(command.actorId(), command.commandKey());
        String hash = hash("RESCHEDULE_DECIDE", command.requestId(), command.decision(),
                command.expectedRequestVersion(), command.expectedAppointmentVersion());
        CommandResult replay = receipt(command.actorId(), command.commandKey(), "RESCHEDULE_DECIDE", hash);
        if (replay != null) return replay.asReplay();
        RequestView locked = loadRequest(command.requestId(), true);
        if (locked.version() != command.expectedRequestVersion())
            throw new AppointmentFailure("APPOINTMENT_REQUEST_VERSION_STALE", 409);
        if (!"PENDING".equals(locked.status()))
            throw new AppointmentFailure("APPOINTMENT_REQUEST_TERMINAL", 409);
        AppointmentView appointment = null;
        if ("ACCEPT".equals(command.decision())) {
            if (locked.observedAppointmentVersion() == null
                    || locked.observedAppointmentVersion() != command.expectedAppointmentVersion())
                throw new AppointmentFailure("APPOINTMENT_VERSION_STALE", 409);
            appointment = acceptReschedule(locked, command.actorId(), command.expectedAppointmentVersion());
        }
        jdbc.update("""
                UPDATE fitness.appointment_requests_v2
                SET status=?,version=version+1,decided_at=clock_timestamp(),decided_by=?
                WHERE id=? AND status='PENDING' AND version=?
                """, "ACCEPT".equals(command.decision()) ? "ACCEPTED" : "REJECTED",
                command.actorId(), command.requestId(), locked.version());
        UUID sourceEvent = event(locked.appointmentId(), locked.requestId(),
                "RESCHEDULE_REQUEST_" + ("ACCEPT".equals(command.decision()) ? "ACCEPTED" : "REJECTED"),
                command.actorId(), null);
        notify(sourceEvent, "APPOINTMENT_RESCHEDULE_" + ("ACCEPT".equals(command.decision()) ? "ACCEPTED" : "REJECTED"),
                "APPOINTMENT_REQUEST", locked.requestId(), locked.studentId(), locked.trainerId(),
                locked.coachingPeriodId(), command.actorId(), locked.requestedBy());
        CommandResult result = new CommandResult(loadRequest(locked.requestId(), false), appointment, false);
        saveReceipt(command.actorId(), command.commandKey(), "RESCHEDULE_DECIDE", hash,
                locked.requestId(), locked.appointmentId(), result);
        return result;
    }

    private AppointmentView acceptReschedule(RequestView request, UUID actor, long expectedVersion) {
        var interval = checkedInterval(request.interval());
        if (request.plannedWorkoutId() != null) {
            var linked = workouts.lockForAppointment(request.plannedWorkoutId());
            if (linked.isEmpty()) throw new AppointmentFailure("APPOINTMENT_COACH_REQUIRED_DEPENDENCY", 409);
            if ("COACH_REQUIRED".equals(linked.get().supervisionRequirement())
                    && "SCHEDULED".equals(linked.get().status())
                    && (linked.get().plannedEndAt() == null
                    || interval.startAt().isAfter(linked.get().plannedStartAt())
                    || interval.endAt().isBefore(linked.get().plannedEndAt())))
                throw new AppointmentFailure("APPOINTMENT_COACH_REQUIRED_DEPENDENCY", 409);
        }
        AppointmentView old = lockAppointment(request.appointmentId());
        if (!java.util.Objects.equals(old.plannedWorkoutId(), request.plannedWorkoutId())
                || !request.coachingPeriodId().equals(old.coachingPeriodId())
                || !request.studentId().equals(old.studentId())
                || !request.trainerId().equals(old.trainerId()))
            throw new AppointmentFailure("APPOINTMENT_VERSION_STALE", 409);
        if (old.version() != expectedVersion) throw new AppointmentFailure("APPOINTMENT_VERSION_STALE", 409);
        if (!"CONFIRMED".equals(old.status())) throw new AppointmentFailure("APPOINTMENT_STATUS_CONFLICT", 409);
        Instant now = databaseNow();
        if (!old.startAt().isAfter(now) || !interval.startAt().isAfter(now))
            throw new AppointmentFailure("APPOINTMENT_START_PASSED", 409);
        lockCalendars(old.studentId(), old.trainerId());
        if (calendarConflict(interval.startAt(), interval.endAt(), old.studentId(), old.trainerId(), old.appointmentId()))
            throw new AppointmentFailure("APPOINTMENT_STUDENT_CONFLICT", 409);
        try { jdbc.update("""
                UPDATE fitness.appointments
                SET start_at=?,end_at=?,display_timezone=?,local_start=?,local_end=?,
                    start_utc_offset=?,end_utc_offset=?,version=version+1,updated_at=clock_timestamp()
                WHERE id=? AND version=? AND status='CONFIRMED'
                """, Timestamp.from(interval.startAt()), Timestamp.from(interval.endAt()),
                request.interval().timezone(), request.interval().localStart(), request.interval().localEnd(),
                request.interval().startUtcOffset(), request.interval().endUtcOffset(),
                old.appointmentId(), old.version());
        } catch (DataIntegrityViolationException conflict) {
            if (sqlState(conflict, "23P01"))
                throw new AppointmentFailure("APPOINTMENT_STUDENT_CONFLICT", 409);
            throw conflict;
        }
        AppointmentView updated = loadAppointment(old.appointmentId());
        event(updated.appointmentId(), request.requestId(), "APPOINTMENT_RESCHEDULED", actor, null, old, updated);
        return updated;
    }

    private boolean currentTrainerPeriod(UUID actor, UUID student, UUID period) {
        try { current(actor, student, period); return true; }
        catch (AppointmentFailure ignored) { return false; }
    }

    private void requireTrainer(UUID actor) {
        if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE
                || !roles.hasActiveRole(actor, "TRAINER") || !trainers.canCoach(actor)) throw notFound();
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid();
    }

    private static <T> Page<T> slice(List<T> items, int page, int size) {
        long start = (long) page * size;
        if (start >= items.size()) return new Page<>(List.of(), page, size, items.size());
        return new Page<>(items.subList((int) start, (int) Math.min(items.size(), start + size)), page, size, items.size());
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult request(RequestCommand command) {
        if (command == null || command.actorId() == null || command.studentId() == null
                || command.interval() == null || command.commandKey() == null) throw invalid();
        var current = currentForMutation(command.actorId(), command.studentId(), null);
        var interval = checkedInterval(command.interval());
        lockCommandKey(command.actorId(), command.commandKey());
        String hash = hash("REQUEST_CREATE", command.studentId(), command.plannedWorkoutId(), command.interval());
        CommandResult replay = receipt(command.actorId(), command.commandKey(), "REQUEST_CREATE", hash);
        if (replay != null) return replay.asReplay();
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.appointment_requests_v2
                (id,kind,student_id,trainer_id,coaching_period_id,planned_workout_id,requested_by,
                 local_start,local_end,timezone,start_utc_offset,end_utc_offset,start_at,end_at)
                VALUES (?,'CREATE',?,?,?,?,?,?,?,?,?,?,?,?)
                """, id, command.studentId(), current.trainerId(), current.periodId(), command.plannedWorkoutId(),
                command.actorId(), command.interval().localStart(), command.interval().localEnd(),
                command.interval().timezone(), command.interval().startUtcOffset(), command.interval().endUtcOffset(),
                Timestamp.from(interval.startAt()), Timestamp.from(interval.endAt()));
        UUID sourceEvent = event(null, id, "REQUEST_CREATED", command.actorId(), null);
        notify(sourceEvent, "APPOINTMENT_REQUEST_PENDING", "APPOINTMENT_REQUEST", id,
                command.studentId(), current.trainerId(), current.periodId(), command.actorId(),
                command.actorId().equals(command.studentId()) ? current.trainerId() : command.studentId());
        CommandResult result = new CommandResult(loadRequest(id, false), null, false);
        saveReceipt(command.actorId(), command.commandKey(), "REQUEST_CREATE", hash, id, null, result);
        return result;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public CommandResult decide(DecisionCommand command) {
        if (command == null || command.actorId() == null || command.requestId() == null
                || command.commandKey() == null || command.expectedRequestVersion() < 0
                || !("ACCEPT".equals(command.decision()) || "REJECT".equals(command.decision()))) throw invalid();
        RequestView observed = loadRequest(command.requestId(), false);
        if (!"CREATE".equals(observed.kind())) throw notFound();
        UUID counterparty = observed.requestedBy().equals(observed.studentId())
                ? observed.trainerId() : observed.studentId();
        if (!counterparty.equals(command.actorId())) throw notFound();
        currentForMutation(command.actorId(), observed.studentId(), observed.coachingPeriodId());
        lockCommandKey(command.actorId(), command.commandKey());
        String hash = hash("REQUEST_DECIDE", command.requestId(), command.decision(),
                command.expectedRequestVersion());
        CommandResult replay = receipt(command.actorId(), command.commandKey(), "REQUEST_DECIDE", hash);
        if (replay != null) return replay.asReplay();
        RequestView locked = loadRequest(command.requestId(), true);
        if (locked.version() != command.expectedRequestVersion())
            throw new AppointmentFailure("APPOINTMENT_REQUEST_VERSION_STALE", 409);
        if (!"PENDING".equals(locked.status()))
            throw new AppointmentFailure("APPOINTMENT_REQUEST_TERMINAL", 409);
        AppointmentView appointment = null;
        if ("ACCEPT".equals(command.decision())) {
            if (locked.appointmentId() != null)
                throw new AppointmentFailure("APPOINTMENT_REQUEST_TERMINAL", 409);
            appointment = acceptRequest(locked, command.actorId());
        }
        jdbc.update("""
                UPDATE fitness.appointment_requests_v2
                SET status=?,version=version+1,decided_at=clock_timestamp(),decided_by=?,appointment_id=?
                WHERE id=? AND status='PENDING' AND version=?
                """, "ACCEPT".equals(command.decision()) ? "ACCEPTED" : "REJECTED", command.actorId(),
                appointment == null ? null : appointment.appointmentId(), command.requestId(), locked.version());
        UUID sourceEvent = event(appointment == null ? null : appointment.appointmentId(), command.requestId(),
                "REQUEST_" + ("ACCEPT".equals(command.decision()) ? "ACCEPTED" : "REJECTED"), command.actorId(), null);
        notify(sourceEvent, "APPOINTMENT_REQUEST_" + ("ACCEPT".equals(command.decision()) ? "ACCEPTED" : "REJECTED"),
                "APPOINTMENT_REQUEST", locked.requestId(), locked.studentId(), locked.trainerId(),
                locked.coachingPeriodId(), command.actorId(), locked.requestedBy());
        CommandResult result = new CommandResult(loadRequest(command.requestId(), false), appointment, false);
        saveReceipt(command.actorId(), command.commandKey(), "REQUEST_DECIDE", hash, command.requestId(),
                appointment == null ? null : appointment.appointmentId(), result);
        return result;
    }

    @Transactional(readOnly = true, isolation = Isolation.READ_COMMITTED)
    public RequestView detail(UUID actor, UUID requestId) {
        if (actor == null || requestId == null) throw notFound();
        RequestView request = loadRequest(requestId, false);
        if (actor.equals(request.studentId())) requireStudent(actor);
        else if (actor.equals(request.trainerId())) current(actor, request.studentId(), request.coachingPeriodId());
        else throw notFound();
        return request;
    }

    private AppointmentView acceptRequest(RequestView request, UUID actor) {
        var interval = checkedInterval(request.interval());
        if (request.plannedWorkoutId() != null) {
            var occurrence = workouts.lockForAppointment(request.plannedWorkoutId()).orElseThrow(AppointmentService::notFound);
            if (!request.studentId().equals(occurrence.studentId())
                    || !request.coachingPeriodId().equals(occurrence.coachingPeriodId())) throw notFound();
            if (!"SCHEDULED".equals(occurrence.status()) || occurrence.executionSealed()
                    || !"COACH_REQUIRED".equals(occurrence.supervisionRequirement()))
                throw new AppointmentFailure("APPOINTMENT_COACH_REQUIRED_DEPENDENCY", 409);
            if (occurrence.plannedEndAt() == null || interval.startAt().isAfter(occurrence.plannedStartAt())
                    || interval.endAt().isBefore(occurrence.plannedEndAt()))
                throw new AppointmentFailure("APPOINTMENT_COACH_REQUIRED_DEPENDENCY", 409);
            if (Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS(SELECT 1 FROM fitness.appointments
                                  WHERE planned_workout_id=? AND status='CONFIRMED')
                    """, Boolean.class, occurrence.id())))
                throw new AppointmentFailure("APPOINTMENT_COACH_REQUIRED_DEPENDENCY", 409);
        }
        // Serialize new V45 writers by both actor calendars. V5's exclusion protects Trainer;
        // the V45 partial exclusion protects verified Student rows. Legacy rows are checked too.
        lockCalendars(request.studentId(), request.trainerId());
        Instant now = databaseNow();
        if (!interval.startAt().isAfter(now)) throw new AppointmentFailure("APPOINTMENT_START_PASSED", 409);
        if (calendarConflict(interval.startAt(), interval.endAt(), request.studentId(), request.trainerId(), null))
            throw new AppointmentFailure("APPOINTMENT_STUDENT_CONFLICT", 409);
        UUID id = UUID.randomUUID();
        try { jdbc.update("""
                INSERT INTO fitness.appointments
                (id,student_id,trainer_id,coaching_period_id,planned_workout_id,start_at,end_at,display_timezone,
                 local_start,local_end,start_utc_offset,end_utc_offset,guarded_v2,status,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,true,'CONFIRMED',?)
                """, id, request.studentId(), request.trainerId(), request.coachingPeriodId(), request.plannedWorkoutId(),
                Timestamp.from(interval.startAt()), Timestamp.from(interval.endAt()), request.interval().timezone(),
                request.interval().localStart(), request.interval().localEnd(),
                request.interval().startUtcOffset(), request.interval().endUtcOffset(), actor);
        } catch (DataIntegrityViolationException conflict) {
            if (sqlState(conflict, "23P01"))
                throw new AppointmentFailure("APPOINTMENT_STUDENT_CONFLICT", 409);
            throw conflict;
        }
        event(id, request.requestId(), "APPOINTMENT_CONFIRMED", actor, null);
        return loadAppointment(id);
    }

    private boolean calendarConflict(Instant start, Instant end, UUID student, UUID trainer, UUID except) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.appointments WHERE
                  status IN ('SCHEDULED','CONFIRMED') AND (?::uuid IS NULL OR id<>?::uuid)
                  AND tstzrange(start_at,end_at,'[)') && tstzrange(?::timestamptz,?::timestamptz,'[)')
                  AND (student_id=? OR trainer_id=?))
                """, Boolean.class, except, except, Timestamp.from(start), Timestamp.from(end), student, trainer));
    }

    private CurrentCoachingContextQuery.CurrentCoachingContext current(UUID actor, UUID student, UUID expectedPeriod) {
        var context = contexts.findEffectiveContext(student, true).orElseThrow(AppointmentService::notFound);
        if (context.mode() != CurrentCoachingContextQuery.Mode.HUMAN_COACH || context.trainerId() == null
                || (expectedPeriod != null && !expectedPeriod.equals(context.periodId()))) throw notFound();
        if (actor.equals(student)) requireStudent(actor);
        else if (actor.equals(context.trainerId())) {
            if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE
                    || !roles.hasActiveRole(actor, "TRAINER") || !trainers.canCoach(actor)) throw notFound();
        } else throw notFound();
        return context;
    }

    private CurrentCoachingContextQuery.CurrentCoachingContext currentForMutation(
            UUID actor, UUID student, UUID expectedPeriod) {
        try { return current(actor, student, expectedPeriod); }
        catch (AppointmentFailure concealed) {
            if (actor.equals(student)) {
                requireStudent(actor);
                throw new AppointmentFailure("APPOINTMENT_PERIOD_INACTIVE", 409);
            }
            throw concealed;
        }
    }

    private void requireStudent(UUID actor) {
        if (accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE
                || !roles.hasActiveRole(actor, "STUDENT") || !students.hasProfile(actor)) throw notFound();
    }

    private RequestView loadRequest(UUID id, boolean lock) {
        List<RequestView> rows = jdbc.query("""
                SELECT id,kind,student_id,trainer_id,coaching_period_id,requested_by,status,version,planned_workout_id,
                       observed_appointment_version,
                       appointment_id,local_start,local_end,timezone,start_utc_offset,end_utc_offset,created_at,decided_at
                FROM fitness.appointment_requests_v2 WHERE id=?
                """ + (lock ? " FOR UPDATE" : ""), (rs, row) -> new RequestView(
                rs.getObject("id", UUID.class), rs.getString("kind"), rs.getObject("student_id", UUID.class),
                rs.getObject("trainer_id", UUID.class), rs.getObject("coaching_period_id", UUID.class),
                rs.getObject("requested_by", UUID.class), rs.getString("status"), rs.getLong("version"),
                rs.getObject("appointment_id", UUID.class), rs.getObject("planned_workout_id", UUID.class),
                (Long) rs.getObject("observed_appointment_version"),
                new LocalInterval(rs.getObject("local_start", LocalDateTime.class),
                rs.getObject("local_end", LocalDateTime.class), rs.getString("timezone"),
                rs.getString("start_utc_offset"), rs.getString("end_utc_offset")),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("decided_at") == null ? null : rs.getTimestamp("decided_at").toInstant()), id);
        return rows.stream().findFirst().orElseThrow(AppointmentService::notFound);
    }

    private AppointmentView loadAppointment(UUID id) {
        return jdbc.queryForObject("""
                SELECT id,version,student_id,trainer_id,coaching_period_id,planned_workout_id,status::text,
                       start_at,end_at,display_timezone,local_start,local_end,start_utc_offset,end_utc_offset
                FROM fitness.appointments WHERE id=?
                """, (rs, row) -> appointment(rs), id);
    }

    private AppointmentView loadAppointmentOrConceal(UUID id) {
        if (id == null) throw notFound();
        try { return loadAppointment(id); }
        catch (org.springframework.dao.EmptyResultDataAccessException ignored) { throw notFound(); }
    }

    private AppointmentView lockAppointment(UUID id) {
        var rows = jdbc.query("""
                SELECT id,version,student_id,trainer_id,coaching_period_id,planned_workout_id,status::text,
                       start_at,end_at,display_timezone,local_start,local_end,start_utc_offset,end_utc_offset
                FROM fitness.appointments WHERE id=? FOR UPDATE
                """, (rs, row) -> appointment(rs), id);
        return rows.stream().findFirst().orElseThrow(AppointmentService::notFound);
    }

    private static AppointmentView appointment(ResultSet rs) throws SQLException {
        return new AppointmentView(rs.getObject("id", UUID.class), rs.getLong("version"),
                rs.getObject("student_id", UUID.class), rs.getObject("trainer_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class), rs.getObject("planned_workout_id", UUID.class),
                rs.getString("status"), rs.getTimestamp("start_at").toInstant(),
                rs.getTimestamp("end_at").toInstant(), rs.getString("display_timezone"),
                rs.getObject("local_start", LocalDateTime.class), rs.getObject("local_end", LocalDateTime.class),
                rs.getString("start_utc_offset"), rs.getString("end_utc_offset"));
    }

    private UUID event(UUID appointment, UUID request, String type, UUID actor, String reason) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.appointment_events_v2(id,appointment_id,request_id,event_type,actor_id,reason)
                VALUES (?,?,?,?,?,?)
                """, id, appointment, request, type, actor, reason);
        return id;
    }

    private UUID event(UUID appointment, UUID request, String type, UUID actor, String reason,
                       AppointmentView before, AppointmentView after) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("""
                    INSERT INTO fitness.appointment_events_v2
                    (id,appointment_id,request_id,event_type,actor_id,reason,old_state,new_state)
                    VALUES (?,?,?,?,?,?,?::jsonb,?::jsonb)
                    """, id, appointment, request, type, actor, reason,
                    mapper.writeValueAsString(before), mapper.writeValueAsString(after));
        } catch (Exception failure) { throw new IllegalStateException("Appointment audit could not be written", failure); }
        return id;
    }

    private void notify(UUID sourceEventId, String eventType, String entityType, UUID entityId,
                        UUID studentId, UUID trainerId, UUID periodId, UUID actorId, UUID recipientId) {
        notifications.committed(sourceEventId, eventType, entityType, entityId,
                studentId, trainerId, periodId, actorId, recipientId);
    }

    private void lockCommandKey(UUID actor, UUID key) {
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,45))", Object.class,
                actor + ":" + key);
    }

    private void lockCalendars(UUID student, UUID trainer) {
        var keys = List.of("S:" + student, "T:" + trainer).stream().sorted().toList();
        for (String key : keys)
            jdbc.queryForObject("SELECT pg_advisory_xact_lock(hashtextextended(?::text,46))", Object.class, key);
    }

    private CommandResult receipt(UUID actor, UUID key, String type, String hash) {
        var rows = jdbc.query("""
                SELECT command_type,payload_hash,response_json::text FROM fitness.appointment_command_receipts_v2
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, actor, key);
        if (rows.isEmpty()) return null;
        var prior = rows.getFirst();
        if (!type.equals(prior[0]) || !hash.equals(prior[1]))
            throw new AppointmentFailure("APPOINTMENT_IDEMPOTENCY_CONFLICT", 409);
        try { return mapper.readValue(prior[2], CommandResult.class); }
        catch (Exception error) { throw new IllegalStateException("Appointment receipt is not readable", error); }
    }

    private AppointmentCommandResult appointmentReceipt(UUID actor, UUID key, String type, String hash) {
        var rows = jdbc.query("""
                SELECT command_type,payload_hash,response_json::text FROM fitness.appointment_command_receipts_v2
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, row) -> new String[] {rs.getString(1), rs.getString(2), rs.getString(3)}, actor, key);
        if (rows.isEmpty()) return null;
        var prior = rows.getFirst();
        if (!type.equals(prior[0]) || !hash.equals(prior[1]))
            throw new AppointmentFailure("APPOINTMENT_IDEMPOTENCY_CONFLICT", 409);
        try { return mapper.readValue(prior[2], AppointmentCommandResult.class); }
        catch (Exception error) { throw new IllegalStateException("Appointment receipt is not readable", error); }
    }

    private void saveReceipt(UUID actor, UUID key, String type, String hash, UUID request,
                             UUID appointment, Object result) {
        try {
            jdbc.update("""
                    INSERT INTO fitness.appointment_command_receipts_v2
                    (actor_id,command_key,command_type,payload_hash,request_id,appointment_id,response_json)
                    VALUES (?,?,?,?,?,?,?::jsonb)
                    """, actor, key, type, hash, request, appointment, mapper.writeValueAsString(result));
        } catch (Exception error) { throw new IllegalStateException("Appointment receipt could not be written", error); }
    }

    private String hash(Object... parts) {
        try {
            byte[] payload = mapper.writeValueAsBytes(parts);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private JsonNode parseAudit(String value) {
        if (value == null) return null;
        try { return mapper.readTree(value); }
        catch (Exception failure) { throw new IllegalStateException("Appointment audit is not readable", failure); }
    }

    private static boolean sqlState(Throwable failure, String state) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause())
            if (cause instanceof SQLException sql && state.equals(sql.getSQLState())) return true;
        return false;
    }

    private AppointmentTimeResolver.Interval checkedInterval(LocalInterval value) {
        return AppointmentTimeResolver.resolve(value.localStart(), value.localEnd(), value.timezone(),
                value.startUtcOffset(), value.endUtcOffset());
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", java.sql.Timestamp.class).toInstant();
    }

    private static AppointmentFailure invalid() { return new AppointmentFailure("VALIDATION_FAILED", 400); }
    private static AppointmentFailure notFound() { return new AppointmentFailure("APPOINTMENT_NOT_FOUND", 404); }
}
