package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.LinkedWorkoutAppointmentQuery;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.LinkedWorkoutAppointmentQuery.Linked;
import com.fitnesscoaching.platform.modules.notification.LifecycleNotificationWriter;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleChangePersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleChangePersistencePort.Occurrence;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class WorkoutScheduleChangeService implements WorkoutScheduleChangeUseCase {
    private final WorkoutPlanPersistencePort plans;
    private final WorkoutScheduleChangePersistencePort changes;
    private final WorkoutExecutionAccessPolicy access;
    private final LinkedWorkoutAppointmentQuery appointments;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final LifecycleNotificationWriter notifications;

    public WorkoutScheduleChangeService(WorkoutPlanPersistencePort plans, WorkoutScheduleChangePersistencePort changes,
                                        WorkoutExecutionAccessPolicy access, LinkedWorkoutAppointmentQuery appointments,
                                        AuditService audit, ObjectMapper mapper,
                                        LifecycleNotificationWriter notifications) {
        this.plans = plans; this.changes = changes; this.access = access;
        this.appointments = appointments; this.audit = audit; this.mapper = mapper;
        this.notifications = notifications;
    }

    @Override public void lockStudentAndActor(UUID studentId, UUID actorId) {
        plans.lockStudentAndActor(studentId, actorId);
    }

    // Schedule must acquire the Student boundary and authorize the Trainer before calling this Workout-owned inspection.
    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public ProposalSource inspectProposalCreation(UUID studentId, UUID occurrenceId,
                                                  long expectedOccurrenceVersion, String changeType,
                                                  Destination destination) {
        if (studentId == null || occurrenceId == null || expectedOccurrenceVersion < 0
                || !"RESCHEDULE".equals(changeType) && !"CANCEL".equals(changeType))
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        if (!studentId.equals(plans.findOccurrenceStudent(occurrenceId)))
            throw fail("PLANNED_WORKOUT_NOT_FOUND", 404);
        Occurrence old = changes.lockOccurrence(occurrenceId);
        Instant at = changes.databaseNow();
        checkMutable(old, expectedOccurrenceVersion);
        if (!old.plannedStartAt().isAfter(at)) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_START_PASSED", 409);
        List<Linked> links = appointments.lockLinkedForChange(old.id());
        if (links.size() > 1) throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        if (changes.hasSuccessor(old.id())) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_NOT_CHANGEABLE", 409);
        if ("RESCHEDULE".equals(changeType)) {
            if (old.sessionId() == null || old.planVersionId() == null || old.localStart() == null
                    || old.localEnd() == null || old.timezone() == null || old.startUtcOffset() == null
                    || old.endUtcOffset() == null)
                throw fail("WORKOUT_SCHEDULE_LEGACY_SOURCE_UNAVAILABLE", 409);
            try { LocalDateTime.parse(old.localStart()); LocalDateTime.parse(old.localEnd()); }
            catch (DateTimeException malformed) { throw fail("WORKOUT_SCHEDULE_LEGACY_SOURCE_UNAVAILABLE", 409); }
            if ("COACH_REQUIRED".equals(old.supervisionRequirement()) && links.isEmpty())
                throw fail("WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED", 409);
            if (destination == null || destination.localStart() == null || destination.localEnd() == null)
                throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
            ZoneId zone = zone(destination.timezone());
            Instant start = instant(destination.localStart(), destination.startUtcOffset(), zone);
            Instant end = instant(destination.localEnd(), destination.endUtcOffset(), zone);
            if (!end.isAfter(start)) throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
            if (!start.isAfter(at) || start.isAfter(at.plus(90, ChronoUnit.DAYS)))
                throw fail("WORKOUT_SCHEDULE_WINDOW_INVALID", 400);
            if (!links.isEmpty() && !appointments.qualifiesForReplacement(links.getFirst(), studentId,
                    old.coachingPeriodId(), start, end))
                throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        } else if (destination != null) throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        return new ProposalSource(old.id(), old.studentId(), old.version(), old.planVersionId(), old.sessionId(),
                old.plannedStartAt(), old.plannedEndAt(), old.originalPlannedStartAt(), old.timezone(),
                old.localStart(), old.localEnd(), old.startUtcOffset(), old.endUtcOffset(),
                links.isEmpty() ? null : links.getFirst().appointmentId(),
                links.isEmpty() ? null : links.getFirst().version());
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChangeResult reschedule(Reschedule c) {
        if (c == null || c.occurrenceId() == null || c.actorId() == null || c.expectedOccurrenceVersion() < 0
                || c.reason() != null && c.reason().length() > 1000)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        String key = key(c.commandKey());
        var student = student(c.occurrenceId(), c.actorId());
        plans.lockStudentAndActor(student, c.actorId());
        requireStudent(c.actorId(), student);
        String hash = hash("RESCHEDULE", c.occurrenceId(), c.expectedOccurrenceVersion(), c.destination(), c.reason(),
                c.expectedAppointmentVersion(), c.expectedAppointmentId());
        var receipt = changes.lockReceipt(c.actorId(), key);
        if (receipt.isPresent()) return replay(receipt.get(), "RESCHEDULE", hash);

        Occurrence old = changes.lockOccurrence(c.occurrenceId());
        Instant at = changes.databaseNow();
        checkMutable(old, c.expectedOccurrenceVersion());
        if (!old.plannedStartAt().isAfter(at)) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_START_PASSED", 409);
        if (old.sessionId() == null || old.planVersionId() == null || old.localStart() == null
                || old.localEnd() == null || old.timezone() == null || old.startUtcOffset() == null
                || old.endUtcOffset() == null)
            throw fail("WORKOUT_SCHEDULE_LEGACY_SOURCE_UNAVAILABLE", 409);
        List<Linked> links = appointments.lockLinkedForChange(old.id());
        if (links.size() > 1) throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        if (c.expectedAppointmentId() != null && (links.isEmpty()
                || !links.getFirst().appointmentId().equals(c.expectedAppointmentId())))
            throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        if (c.expectedAppointmentVersion() != null && (links.isEmpty()
                || links.getFirst().version() != c.expectedAppointmentVersion()))
            throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        if ("COACH_REQUIRED".equals(old.supervisionRequirement()) && links.isEmpty())
            throw fail("WORKOUT_SCHEDULE_COACH_REQUIRED_RESCHEDULE_UNSUPPORTED", 409);
        if (changes.hasSuccessor(old.id())) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_NOT_CHANGEABLE", 409);
        Destination dest = c.destination();
        if (dest == null || dest.localStart() == null || dest.localEnd() == null)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        ZoneId zone = zone(dest.timezone());
        Instant start = instant(dest.localStart(), dest.startUtcOffset(), zone);
        Instant end = instant(dest.localEnd(), dest.endUtcOffset(), zone);
        if (!end.isAfter(start)) throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        if (!start.isAfter(at) || start.isAfter(at.plus(90, ChronoUnit.DAYS)))
            throw fail("WORKOUT_SCHEDULE_WINDOW_INVALID", 400);
        if (changes.hasOtherScheduledUnknownEnd(student, old.id()))
            throw fail("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN", 409);
        if (changes.hasSourceInstant(student, old.sessionId(), start))
            throw fail("WORKOUT_SCHEDULE_DUPLICATE", 409);
        if (changes.hasOtherScheduledOverlap(student, old.id(), start, end))
            throw fail("WORKOUT_SCHEDULE_OVERLAP", 409);
        if (!links.isEmpty() && !appointments.qualifiesForReplacement(links.getFirst(), student,
                old.coachingPeriodId(), start, end))
            throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        UUID replacement = UUID.randomUUID(), change = UUID.randomUUID();
        try { changes.reschedule(old, replacement, change, c.actorId(), dest, start, end, at, c.reason()); }
        catch (DataIntegrityViolationException race) { throw databaseConflict(race); }
        if (!links.isEmpty() && !appointments.transfer(links.getFirst(), old.id(), replacement, c.actorId()))
            throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        ChangeResult result = new ChangeResult("RESCHEDULE", old.id(), old.version() + 1, "RESCHEDULED",
                old.originalPlannedStartAt(), old.plannedStartAt(), old.plannedEndAt(), replacement, 0L,
                start, end, old.id(), at, false);
        changes.saveReceipt(c.actorId(), key, "RESCHEDULE", hash, change, result, at);
        record(c.actorId(), "PLANNED_WORKOUT_RESCHEDULED", old.id(), at);
        return result;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChangeResult cancel(Cancel c) {
        if (c == null || c.occurrenceId() == null || c.actorId() == null || c.expectedOccurrenceVersion() < 0
                || c.reason() != null && c.reason().length() > 1000)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        String key = key(c.commandKey());
        var student = student(c.occurrenceId(), c.actorId());
        plans.lockStudentAndActor(student, c.actorId());
        requireStudent(c.actorId(), student);
        String hash = hash("CANCEL", c.occurrenceId(), c.expectedOccurrenceVersion(), c.reason(),
                c.expectedAppointmentVersion(), c.expectedAppointmentId());
        var receipt = changes.lockReceipt(c.actorId(), key);
        if (receipt.isPresent()) return replay(receipt.get(), "CANCEL", hash);

        Occurrence old = changes.lockOccurrence(c.occurrenceId());
        Instant at = changes.databaseNow();
        checkMutable(old, c.expectedOccurrenceVersion());
        if (changes.hasSuccessor(old.id())) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_NOT_CHANGEABLE", 409);
        List<Linked> links = appointments.lockLinkedForChange(old.id());
        if (links.size() > 1) throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        if (c.expectedAppointmentId() != null && (links.isEmpty()
                || !links.getFirst().appointmentId().equals(c.expectedAppointmentId())))
            throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        if (c.expectedAppointmentVersion() != null && (links.isEmpty()
                || links.getFirst().version() != c.expectedAppointmentVersion()))
            throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        if (!old.plannedStartAt().isAfter(at) && (c.reason() == null || c.reason().isBlank()))
            throw fail("WORKOUT_SCHEDULE_CHANGE_REASON_REQUIRED", 400);
        UUID change = UUID.randomUUID();
        try { changes.cancel(old, change, c.actorId(), at, c.reason()); }
        catch (DataIntegrityViolationException race) { throw databaseConflict(race); }
        if (!links.isEmpty() && !appointments.release(links.getFirst(), old.id(), c.actorId()))
            throw fail("WORKOUT_SCHEDULE_APPOINTMENT_LINKED", 409);
        ChangeResult result = new ChangeResult("CANCEL", old.id(), old.version() + 1, "CANCELLED",
                old.originalPlannedStartAt(), old.plannedStartAt(), old.plannedEndAt(), null, null,
                null, null, null, at, false);
        changes.saveReceipt(c.actorId(), key, "CANCEL", hash, change, result, at);
        record(c.actorId(), "PLANNED_WORKOUT_CANCELLED", old.id(), at);
        if (!links.isEmpty()) {
            Linked linked = links.getFirst();
            notifications.committed(change, "LINKED_WORKOUT_CANCELLED", "PLANNED_WORKOUT", old.id(),
                    old.studentId(), linked.trainerId(), linked.periodId(), c.actorId(), linked.trainerId());
        }
        return result;
    }

    private UUID student(UUID id, UUID actor) {
        UUID student = plans.findOccurrenceStudent(id);
        if (!student.equals(actor)) throw fail("PLANNED_WORKOUT_NOT_FOUND", 404);
        return student;
    }
    private void requireStudent(UUID actor, UUID student) {
        try { access.requireStudentMutation(actor, student); }
        catch (WorkoutExecutionFailure denied) { throw fail("WORKOUT_SCHEDULE_ACCESS_DENIED", 403); }
    }
    private void checkMutable(Occurrence old, long expected) {
        if (old.version() != expected) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT", 409);
        if (old.executionStartedAt() != null) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_SEALED", 409);
        if (!"SCHEDULED".equals(old.status())) throw fail("WORKOUT_SCHEDULE_OCCURRENCE_NOT_CHANGEABLE", 409);
    }
    private ChangeResult replay(WorkoutScheduleChangePersistencePort.Receipt receipt, String command, String hash) {
        if (!command.equals(receipt.commandName()) || !hash.equals(receipt.payloadHash()))
            throw fail("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT", 409);
        try { return mapper.readValue(receipt.responseJson(), ChangeResult.class).asReplay(); }
        catch (Exception malformed) { throw new IllegalStateException("Cannot read schedule change receipt", malformed); }
    }
    private static String key(String value) {
        if (value == null || value.isBlank() || value.length() > 120)
            throw fail("WORKOUT_SCHEDULE_CHANGE_VALIDATION_FAILED", 400);
        return value;
    }
    private static ZoneId zone(String value) {
        if (value == null || value.isBlank() || value.length() > 64 || !ZoneId.getAvailableZoneIds().contains(value))
            throw fail("WORKOUT_SCHEDULE_TIMEZONE_INVALID", 400);
        return ZoneId.of(value);
    }
    private static Instant instant(LocalDateTime local, String offsetValue, ZoneId zone) {
        if (offsetValue == null || offsetValue.isBlank())
            throw fail("WORKOUT_SCHEDULE_DST_OFFSET_REQUIRED", 400);
        try {
            ZoneOffset offset = ZoneOffset.of(offsetValue);
            var valid = zone.getRules().getValidOffsets(local);
            if (valid.isEmpty()) throw fail("WORKOUT_SCHEDULE_DST_TIME_NONEXISTENT", 400);
            if (!valid.contains(offset)) throw fail("WORKOUT_SCHEDULE_DST_OFFSET_INVALID", 400);
            return local.toInstant(offset).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeException invalid) { throw fail("WORKOUT_SCHEDULE_DST_OFFSET_INVALID", 400); }
    }
    private String hash(Object... values) {
        try {
            String payload = mapper.writeValueAsString(values);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private void record(UUID actor, String action, UUID target, Instant at) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole("STUDENT")
                .action(action).targetType("PLANNED_WORKOUT").targetId(target)
                .beforeDataJson("{}").afterDataJson("{}").occurredAt(at).build());
    }
    private static WorkoutScheduleFailure databaseConflict(DataIntegrityViolationException failure) {
        String detail = failure.getMostSpecificCause().getMessage();
        if (detail != null) {
            if (detail.contains("uq_workout_schedule_materialized_source_instant_v33"))
                return fail("WORKOUT_SCHEDULE_DUPLICATE", 409);
            if (detail.contains("ex_workout_schedule_materialized_occupied_v33"))
                return fail("WORKOUT_SCHEDULE_OVERLAP", 409);
            if (detail.contains("uq_workout_schedule_change_predecessor")
                    || detail.contains("workout_schedule_occurrence_changes_previous_occurrence_id_key"))
                return fail("WORKOUT_SCHEDULE_OCCURRENCE_NOT_CHANGEABLE", 409);
        }
        return fail("WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT", 409);
    }
    private static WorkoutScheduleFailure fail(String code, int status) { return new WorkoutScheduleFailure(code, status); }
}
