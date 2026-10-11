package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleBatchPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
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
import java.util.*;

@Service
public class WorkoutScheduleBatchService implements WorkoutScheduleBatchUseCase {
    private final WorkoutPlanPersistencePort plans;
    private final WorkoutScheduleBatchPersistencePort batches;
    private final AuditService audit;
    private final ObjectMapper mapper;

    public WorkoutScheduleBatchService(WorkoutPlanPersistencePort plans, WorkoutScheduleBatchPersistencePort batches,
                                       AuditService audit, ObjectMapper mapper) {
        this.plans = plans; this.batches = batches; this.audit = audit; this.mapper = mapper;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConfirmedBatch confirmDirect(ConfirmBatch c) {
        validateBase(c);
        UUID student = plans.findPlanStudent(c.planId());
        if (!student.equals(c.actorId())) throw fail("WORKOUT_PLAN_NOT_FOUND", 404);
        plans.lockStudentAndActor(student, c.actorId());
        String hash = hash(c);
        var prior = batches.lockReceipt(c.actorId(), c.commandKey());
        if (prior.isPresent()) {
            if (!"CONFIRM_DIRECT".equals(prior.get().commandName()) || !hash.equals(prior.get().payloadHash()))
                throw fail("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT", 409);
            return fromJson(prior.get().responseJson()).asReplay();
        }
        ConfirmedBatch result = materialize(c, student, "STUDENT_DIRECT", null, null);
        batches.saveReceipt(c.actorId(), c.commandKey(), hash, result.batchId(), json(result), result.confirmedAt());
        return result;
    }

    @Override public UUID studentForPlan(UUID planId) {
        if (planId == null) throw fail("WORKOUT_PLAN_NOT_FOUND", 404);
        return plans.findPlanStudent(planId);
    }

    @Override public void lockStudentAndActor(UUID studentId, UUID actorId) {
        plans.lockStudentAndActor(studentId, actorId);
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Source inspectProposalSource(ConfirmBatch c) {
        validateBase(c);
        UUID student = plans.findPlanStudent(c.planId());
        plans.lockStudentAndActor(student, c.actorId());
        var plan = plans.lockPlan(c.planId());
        var open = plans.lockOpenVersion(c.planId());
        Instant at = plans.databaseNow();
        checkedVersion(c, plan, open, at);
        validateInternalOverlap(resolveItems(c, open.id(), at, false));
        return new Source(open.id(), open.versionNumber());
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public ConfirmedBatch confirmProposal(ConfirmBatch c, UUID proposalId, UUID trainerId) {
        validateBase(c);
        if (proposalId == null || trainerId == null) throw fail("VALIDATION_FAILED", 400);
        UUID student = plans.findPlanStudent(c.planId());
        if (!student.equals(c.actorId())) throw fail("WORKOUT_PLAN_NOT_FOUND", 404);
        plans.lockStudentAndActor(student, c.actorId());
        return materialize(c, student, "TRAINER_PROPOSAL", proposalId, trainerId);
    }

    private ConfirmedBatch materialize(ConfirmBatch c, UUID student, String source,
                                       UUID proposalId, UUID trainerId) {
        var plan = plans.lockPlan(c.planId());
        var open = plans.lockOpenVersion(c.planId());
        Instant at = plans.databaseNow();
        checkedVersion(c, plan, open, at);
        if (batches.hasScheduledUnknownEnd(student))
            throw fail("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN", 409);
        List<ConfirmedItem> created = resolveItems(c, open.id(), at, true);
        validateInternalOverlap(created);
        for (ConfirmedItem item : created) {
            if (batches.hasSourceInstant(student, item.planSessionId(), item.plannedStartAt()))
                throw fail("WORKOUT_SCHEDULE_DUPLICATE", 409, item.clientItemId());
            if (batches.hasScheduledOverlap(student, item.plannedStartAt(), item.plannedEndAt()))
                throw fail("WORKOUT_SCHEDULE_OVERLAP", 409, item.clientItemId());
        }
        ConfirmedBatch result = new ConfirmedBatch(UUID.randomUUID(), student, c.planId(), open.id(),
                open.versionNumber(), c.weekAnchorDate(), c.timezone(), source, proposalId, c.actorId(),
                at, List.copyOf(created), false);
        batches.saveBatch(result);
        for (ConfirmedItem item : created) {
            try { batches.saveOccurrence(result, item, plan.coachingPeriodId()); }
            catch (DataIntegrityViolationException race) {
                String detail = race.getMostSpecificCause().getMessage();
                if (detail != null && detail.contains("uq_workout_schedule_new_source_instant"))
                    throw fail("WORKOUT_SCHEDULE_DUPLICATE", 409, item.clientItemId());
                if (detail != null && detail.contains("ex_workout_schedule_new_occupied_range"))
                    throw fail("WORKOUT_SCHEDULE_OVERLAP", 409, item.clientItemId());
                throw race;
            }
        }
        audit.recordAudit(AuditRecord.builder().actorUserId(c.actorId()).actorRole("STUDENT")
                .action("WORKOUT_SCHEDULE_BATCH_CONFIRMED").targetType("WORKOUT_SCHEDULE_BATCH")
                .targetId(result.batchId()).beforeDataJson("{}")
                .afterDataJson(trainerId == null ? "{}" : json(Map.of("proposalId", proposalId, "trainerId", trainerId)))
                .occurredAt(at).build());
        return result;
    }

    private static void validateInternalOverlap(List<ConfirmedItem> created) {
        List<ConfirmedItem> ordered = created.stream().sorted(Comparator.comparing(ConfirmedItem::plannedStartAt)).toList();
        for (int i = 1; i < ordered.size(); i++)
            if (ordered.get(i).plannedStartAt().isBefore(ordered.get(i - 1).plannedEndAt()))
                throw fail("WORKOUT_SCHEDULE_OVERLAP", 409, ordered.get(i).clientItemId());
    }

    private void checkedVersion(ConfirmBatch c,
            com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlan plan,
            WorkoutPlanPersistencePort.OpenVersion open, Instant at) {
        if (plan.status() != WorkoutPlanStatus.ACTIVE)
            throw fail("WORKOUT_SCHEDULE_SOURCE_MISMATCH", 409);
        if (plan.version() != c.expectedPlanAggregateVersion())
            throw fail("WORKOUT_SCHEDULE_PLAN_VERSION_STALE", 409);
        if (!open.id().equals(c.sourcePlanVersionId())
                || !batches.isPublishedEffective(open.id(), c.planId(), at))
            throw fail("WORKOUT_SCHEDULE_PLAN_VERSION_STALE", 409);
    }

    private List<ConfirmedItem> resolveItems(ConfirmBatch c, UUID versionId, Instant at, boolean enforceFuture) {
        ZoneId zone = zone(c.timezone());
        if (c.weekAnchorDate().getYear() < 1 || c.weekAnchorDate().getYear() > 9999
                || c.weekAnchorDate().getDayOfWeek() != DayOfWeek.MONDAY)
            throw fail("VALIDATION_FAILED", 400);
        Set<UUID> itemIds = new HashSet<>();
        Set<UUID> sessions = new HashSet<>();
        List<ConfirmedItem> created = new ArrayList<>();
        for (Item item : c.items()) {
            validateItem(item);
            if (!itemIds.add(item.clientItemId()) || !sessions.add(item.planSessionId()))
                throw fail("VALIDATION_FAILED", 400, item.clientItemId());
            var session = batches.findSession(versionId, item.planSessionId());
            if (session == null || session.weekNumber() != item.weekNumber()
                    || session.dayNumber() != item.dayNumber() || session.sequenceNumber() != item.sequenceNumber())
                throw fail("WORKOUT_SCHEDULE_SOURCE_MISMATCH", 409, item.clientItemId());
            if ("COACH_REQUIRED".equals(session.supervision()))
                throw fail("WORKOUT_SCHEDULE_COACH_REQUIRED_UNSUPPORTED", 409, item.clientItemId());
            LocalDate weekStart;
            try { weekStart = c.weekAnchorDate().plusWeeks(item.weekNumber() - 1L); }
            catch (DateTimeException overflow) { throw fail("VALIDATION_FAILED", 400, item.clientItemId()); }
            LocalDate date = item.localStart().toLocalDate();
            if (date.isBefore(weekStart) || date.isAfter(weekStart.plusDays(6)))
                throw fail("VALIDATION_FAILED", 400, item.clientItemId());
            Instant start = resolveInstant(item.localStart(), item.startUtcOffset(), zone, item.clientItemId());
            Instant end = resolveInstant(item.localEnd(), item.endUtcOffset(), zone, item.clientItemId());
            if (!end.isAfter(start) || (enforceFuture && (!start.isAfter(at)
                    || start.isAfter(at.plus(90, ChronoUnit.DAYS)))))
                throw fail("VALIDATION_FAILED", 400, item.clientItemId());
            created.add(new ConfirmedItem(item.clientItemId(), UUID.randomUUID(), 0, item.planSessionId(),
                    item.weekNumber(), item.dayNumber(), item.sequenceNumber(), item.localStart(), item.localEnd(),
                    item.startUtcOffset(), item.endUtcOffset(), start, end, session.supervision()));
        }
        return created;
    }

    private static void validateBase(ConfirmBatch c) {
        if (c == null || c.actorId() == null || c.planId() == null || c.sourcePlanVersionId() == null
                || c.expectedPlanAggregateVersion() < 0 || c.weekAnchorDate() == null || c.timezone() == null
                || c.timezone().isBlank() || c.timezone().length() > 64 || c.items() == null || c.items().isEmpty()
                || c.commandKey() == null || c.commandKey().isBlank() || c.commandKey().length() > 120)
            throw fail("VALIDATION_FAILED", 400);
    }
    private static void validateItem(Item item) {
        if (item == null) throw fail("VALIDATION_FAILED", 400);
        if (item.clientItemId() == null || item.planSessionId() == null || item.weekNumber() < 1
                || item.dayNumber() < 1 || item.dayNumber() > 7 || item.sequenceNumber() < 1
                || item.localStart() == null || item.localEnd() == null
                || item.localStart().getYear() < 1 || item.localStart().getYear() > 9999
                || item.localEnd().getYear() < 1 || item.localEnd().getYear() > 9999
                || item.startUtcOffset() == null || item.endUtcOffset() == null)
            throw fail("VALIDATION_FAILED", 400, item.clientItemId());
    }
    private static ZoneId zone(String name) {
        try {
            if (!ZoneId.getAvailableZoneIds().contains(name)) throw fail("VALIDATION_FAILED", 400);
            return ZoneId.of(name);
        } catch (DateTimeException invalid) { throw fail("VALIDATION_FAILED", 400); }
    }
    static Instant resolveInstant(LocalDateTime local, String offsetText, ZoneId zone, UUID itemId) {
        try {
            if (!offsetText.matches("[+-](0\\d|1[0-4]):[0-5]\\d"))
                throw fail("VALIDATION_FAILED", 400, itemId);
            ZoneOffset offset = ZoneOffset.of(offsetText);
            if (!zone.getRules().getValidOffsets(local).contains(offset))
                throw fail("VALIDATION_FAILED", 400, itemId);
            return local.toInstant(offset).truncatedTo(ChronoUnit.MICROS);
        } catch (DateTimeException invalid) { throw fail("VALIDATION_FAILED", 400, itemId); }
    }
    private String hash(ConfirmBatch c) {
        String payload = json(List.of(c.planId(), c.sourcePlanVersionId(), c.expectedPlanAggregateVersion(),
                c.weekAnchorDate(), c.timezone(), c.items()));
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(payload.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize schedule command", failure); }
    }
    private ConfirmedBatch fromJson(String value) {
        try { return mapper.readValue(value, ConfirmedBatch.class); }
        catch (Exception failure) { throw new IllegalStateException("Cannot read schedule receipt", failure); }
    }
    private static WorkoutScheduleFailure fail(String code, int status) {
        return new WorkoutScheduleFailure(code, status);
    }
    private static WorkoutScheduleFailure fail(String code, int status, UUID itemId) {
        return new WorkoutScheduleFailure(code, status, itemId);
    }
}
