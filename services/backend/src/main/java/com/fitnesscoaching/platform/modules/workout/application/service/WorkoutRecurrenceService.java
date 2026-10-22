package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutRecurrenceUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanPersistencePort;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleBatchPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class WorkoutRecurrenceService implements WorkoutRecurrenceUseCase {
    private final JdbcTemplate jdbc;
    private final WorkoutPlanPersistencePort plans;
    private final WorkoutScheduleBatchPersistencePort batches;
    private final WorkoutExecutionAccessPolicy students;
    private final CoachingAuthorityQuery coaching;
    private final ObjectMapper mapper;

    public WorkoutRecurrenceService(JdbcTemplate jdbc, WorkoutPlanPersistencePort plans,
                                    WorkoutScheduleBatchPersistencePort batches,
                                    WorkoutExecutionAccessPolicy students,
                                    CoachingAuthorityQuery coaching, ObjectMapper mapper) {
        this.jdbc = jdbc; this.plans = plans; this.batches = batches; this.students = students;
        this.coaching = coaching; this.mapper = mapper;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Series confirmStudent(Create command) {
        validateCreate(command);
        if (!command.actorId().equals(command.studentId())) throw notFound();
        if (!command.studentId().equals(plans.findPlanStudent(command.planId()))) throw notFound();
        plans.lockStudentAndActor(command.studentId(), command.actorId());
        requireStudent(command.actorId());
        String fingerprint = hash("CONFIRM", command);
        var replay = receipt(command.actorId(), command.commandKey(), "CONFIRM", fingerprint);
        if (replay != null) return replay;
        var source = source(command);
        return create(command, source, null, null, "ACTIVE", fingerprint, "CONFIRM");
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Series proposeTrainer(Create command) {
        validateCreate(command);
        if (!command.studentId().equals(plans.findPlanStudent(command.planId()))) throw notFound();
        plans.lockStudentAndActor(command.studentId(), command.actorId());
        UUID period = trainerPeriod(command.actorId(), command.studentId(), DataAccessLevel.CONTRIBUTE);
        String fingerprint = hash("PROPOSE", command);
        var replay = receipt(command.actorId(), command.commandKey(), "PROPOSE", fingerprint);
        if (replay != null) return replay;
        var source = source(command);
        return create(command, source, command.actorId(), period, "PENDING", fingerprint, "PROPOSE");
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page<Series> listStudent(UUID actorId, String status, int page, int size) {
        requireStudent(actorId);
        validatePage(status, page, size);
        return list("student_id=?", new Object[]{actorId}, status, page, size);
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page<Series> listTrainer(UUID actorId, UUID studentId, String status, int page, int size) {
        UUID period = trainerPeriod(actorId, studentId, DataAccessLevel.VIEW);
        validatePage(status, page, size);
        return list("student_id=? AND proposing_trainer_id=? AND coaching_period_id=?",
                new Object[]{studentId, actorId, period}, status, page, size);
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Series detail(UUID actorId, UUID seriesId) {
        Series value = find(seriesId, false);
        authorizeRead(actorId, value);
        return value;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Page<Period> periods(UUID actorId, UUID seriesId, int page, int size) {
        Series series = detail(actorId, seriesId);
        validatePage(null, page, size);
        long total = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_periods WHERE series_id=?",
                Long.class, series.seriesId());
        var items = jdbc.query("""
                SELECT * FROM fitness.workout_recurrence_periods WHERE series_id=?
                ORDER BY block_iteration,plan_session_id LIMIT ? OFFSET ?
                """, (rs, row) -> mapPeriod(rs), series.seriesId(), size, (long) page * size);
        return new Page<>(items, page, size, total, pages(total, size));
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Series decide(Decision command) {
        if (command == null || command.actorId() == null || command.seriesId() == null
                || command.expectedSeriesVersion() < 0 || !List.of("ACCEPT", "REJECT").contains(command.decision()))
            throw invalid();
        key(command.commandKey());
        Series observed = find(command.seriesId(), false);
        if (!command.actorId().equals(observed.studentId())) throw notFound();
        plans.lockStudentAndActor(observed.studentId(), command.actorId());
        requireStudent(command.actorId());
        String fingerprint = hash("DECIDE", command.seriesId(), command.expectedSeriesVersion(), command.decision());
        var replay = receipt(command.actorId(), command.commandKey(), "DECIDE", fingerprint);
        if (replay != null) return replay;
        Series locked = find(command.seriesId(), true);
        checkVersion(locked, command.expectedSeriesVersion(), "PENDING");
        if ("ACCEPT".equals(command.decision())) {
            if (!locked.coachingPeriodId().equals(trainerPeriod(locked.proposingTrainerId(),
                    locked.studentId(), DataAccessLevel.CONTRIBUTE)))
                throw conflict("WORKOUT_RECURRENCE_SOURCE_STALE");
            // The Student's acceptance is a new authority boundary, not a remap of the Trainer's source.
            source(new Create(command.actorId(), locked.studentId(), locked.planId(),
                    locked.sourcePlanVersionId(), locked.observedPlanAggregateVersion(),
                    locked.mondayAnchorDate(), locked.timezone(), locked.items(), command.commandKey()));
        }
        Instant now = plans.databaseNow();
        String status = "ACCEPT".equals(command.decision()) ? "ACTIVE" : "REJECTED";
        transition(locked, status, command.actorId(), command.commandKey(), null, now);
        Series result = find(locked.seriesId(), false);
        saveReceipt(command.actorId(), command.commandKey(), "DECIDE", fingerprint, result, now);
        return result;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Series stop(VersionCommand command) {
        validateVersionCommand(command);
        Series observed = find(command.seriesId(), false);
        if (!command.actorId().equals(observed.studentId())) throw notFound();
        plans.lockStudentAndActor(observed.studentId(), command.actorId());
        requireStudent(command.actorId());
        String fingerprint = hash("STOP", command.seriesId(), command.expectedSeriesVersion());
        var replay = receipt(command.actorId(), command.commandKey(), "STOP", fingerprint);
        if (replay != null) return replay;
        Series locked = find(command.seriesId(), true);
        if (!List.of("ACTIVE", "PAUSED_SOURCE").contains(locked.status()))
            throw conflict("WORKOUT_RECURRENCE_STATE_CONFLICT");
        checkVersion(locked, command.expectedSeriesVersion(), locked.status());
        Instant now = plans.databaseNow();
        transition(locked, "STOPPED", command.actorId(), command.commandKey(), null, now);
        Series result = find(locked.seriesId(), false);
        saveReceipt(command.actorId(), command.commandKey(), "STOP", fingerprint, result, now);
        return result;
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public Series withdraw(VersionCommand command) {
        validateVersionCommand(command);
        Series observed = find(command.seriesId(), false);
        if (!command.actorId().equals(observed.proposingTrainerId())) throw notFound();
        plans.lockStudentAndActor(observed.studentId(), command.actorId());
        requireOriginalTrainer(command.actorId(), observed, DataAccessLevel.CONTRIBUTE);
        String fingerprint = hash("WITHDRAW", command.seriesId(), command.expectedSeriesVersion());
        var replay = receipt(command.actorId(), command.commandKey(), "WITHDRAW", fingerprint);
        if (replay != null) return replay;
        Series locked = find(command.seriesId(), true);
        checkVersion(locked, command.expectedSeriesVersion(), "PENDING");
        Instant now = plans.databaseNow();
        transition(locked, "WITHDRAWN", command.actorId(), command.commandKey(), null, now);
        Series result = find(locked.seriesId(), false);
        saveReceipt(command.actorId(), command.commandKey(), "WITHDRAW", fingerprint, result, now);
        return result;
    }

    @Override @Transactional(readOnly = true)
    public List<UUID> activeSeriesIds() {
        return jdbc.query("SELECT id FROM fitness.workout_recurrence_series WHERE status='ACTIVE' ORDER BY id",
                (rs, row) -> rs.getObject(1, UUID.class));
    }

    @Override @Transactional(isolation = Isolation.READ_COMMITTED)
    public void materialize(UUID seriesId) {
        Series observed = find(seriesId, false);
        plans.lockStudentAndActor(observed.studentId(), observed.studentId());
        Series series = find(seriesId, true);
        if (!"ACTIVE".equals(series.status())) return;
        var plan = plans.lockPlan(series.planId());
        if (plan.status() != WorkoutPlanStatus.ACTIVE) return;
        var open = plans.lockOpenVersion(series.planId());
        Instant now = plans.databaseNow();
        if (!open.id().equals(series.sourcePlanVersionId())
                || !batches.isPublishedEffective(open.id(), series.planId(), now)) {
            transition(series, "PAUSED_SOURCE", null, null, "SOURCE_VERSION_STALE", now);
            return;
        }
        ZoneId zone = zone(series.timezone());
        Instant until = now.plus(30, ChronoUnit.DAYS);
        long cycleDays = Math.multiplyExact(7L, series.planWeeks());
        long fromDays = ChronoUnit.DAYS.between(series.mondayAnchorDate(), now.atZone(zone).toLocalDate());
        long untilDays = ChronoUnit.DAYS.between(series.mondayAnchorDate(), until.atZone(zone).toLocalDate());
        long first = Math.max(0, Math.floorDiv(fromDays, cycleDays) - 1);
        long last = Math.max(first, Math.floorDiv(untilDays, cycleDays) + 1);
        for (long iteration = first; iteration <= last; iteration++) {
            for (Item item : series.items()) {
                var candidate = WorkoutRecurrenceCandidateCalculator.calculate(series.mondayAnchorDate(),
                        series.planWeeks(), iteration, item.planSessionId(), item.weekNumber(),
                        item.dayNumber(), item.firstLocalStart(), item.firstLocalEnd(), zone);
                if (!withinWindow(candidate, zone, now, until) || periodExists(seriesId, iteration,
                        item.planSessionId())) continue;
                materializePeriod(series, item, candidate, plan.coachingPeriodId(), now);
            }
        }
    }

    private void materializePeriod(Series series, Item item,
                                   WorkoutRecurrenceCandidateCalculator.Candidate candidate,
                                   UUID coachingPeriodId, Instant now) {
        String outcome;
        UUID occurrenceId = null;
        if (candidate.outcome() != WorkoutRecurrenceCandidateCalculator.Outcome.READY) {
            outcome = candidate.outcome().name();
        } else if (batches.hasScheduledUnknownEnd(series.studentId())) {
            outcome = "SKIPPED_UNKNOWN_END";
        } else if (batches.hasSourceInstant(series.studentId(), item.planSessionId(), candidate.start())) {
            outcome = "SKIPPED_DUPLICATE";
        } else if (batches.hasScheduledOverlap(series.studentId(), candidate.start(), candidate.end())) {
            outcome = "SKIPPED_OVERLAP";
        } else {
            outcome = "CREATED";
            occurrenceId = UUID.randomUUID();
        }
        UUID periodId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_recurrence_periods
                (id,series_id,block_iteration,plan_session_id,local_start,local_end,timezone,
                 start_utc_offset,end_utc_offset,planned_start_at,planned_end_at,outcome,occurrence_id,processed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """, periodId, series.seriesId(), candidate.blockIteration(), item.planSessionId(),
                candidate.localStart().toString(), candidate.localEnd().toString(), series.timezone(),
                offset(candidate.startOffset()), offset(candidate.endOffset()),
                ts(candidate.start()), ts(candidate.end()), outcome, occurrenceId, ts(now));
        if (occurrenceId == null) return;
        var session = batches.findSession(series.sourcePlanVersionId(), item.planSessionId());
        if (session == null || "COACH_REQUIRED".equals(session.supervision()))
            throw conflict("WORKOUT_RECURRENCE_SOURCE_STALE");
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,planned_start_at,planned_end_at,
                 original_planned_start_at,status,created_by,created_at,updated_at,supervision_requirement,
                 series_period_id,schedule_local_start,schedule_local_end,schedule_timezone,
                 schedule_start_utc_offset,schedule_end_utc_offset)
                VALUES (?,?,?,?,?,?,?,'SCHEDULED',?,?,?,?::fitness.supervision_requirement,?,?,?,?,?,?)
                """, occurrenceId, series.studentId(), item.planSessionId(), coachingPeriodId,
                ts(candidate.start()), ts(candidate.end()), ts(candidate.start()), series.confirmedBy(),
                ts(now), ts(now), session.supervision(), periodId, candidate.localStart().toString(),
                candidate.localEnd().toString(), series.timezone(), offset(candidate.startOffset()),
                offset(candidate.endOffset()));
    }

    private static boolean withinWindow(WorkoutRecurrenceCandidateCalculator.Candidate candidate,
                                        ZoneId zone, Instant now, Instant until) {
        if (candidate.start() != null) return !candidate.start().isBefore(now) && candidate.start().isBefore(until);
        var offsets = zone.getRules().getValidOffsets(candidate.localStart());
        if (!offsets.isEmpty()) return offsets.stream().map(candidate.localStart()::toInstant)
                .anyMatch(at -> !at.isBefore(now) && at.isBefore(until));
        var transition = zone.getRules().getTransition(candidate.localStart());
        return transition != null && !transition.getInstant().isBefore(now)
                && transition.getInstant().isBefore(until);
    }

    private boolean periodExists(UUID seriesId, long iteration, UUID sessionId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.workout_recurrence_periods
                WHERE series_id=? AND block_iteration=? AND plan_session_id=?)
                """, Boolean.class, seriesId, iteration, sessionId));
    }

    private Series create(Create command, Source source, UUID trainerId, UUID periodId,
                          String status, String fingerprint, String commandName) {
        Instant at = plans.databaseNow();
        UUID id = UUID.randomUUID();
        UUID confirmedBy = trainerId == null ? command.studentId() : null;
        jdbc.update("""
                INSERT INTO fitness.workout_recurrence_series
                (id,student_id,plan_id,source_plan_version_id,source_plan_version_number,
                 observed_plan_aggregate_version,plan_weeks,monday_anchor,timezone,status,version,
                 initiated_by,proposing_trainer_id,coaching_period_id,confirmed_by,created_at,confirmed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,0,?,?,?,?,?,?)
                """, id, command.studentId(), command.planId(), command.sourcePlanVersionId(),
                source.versionNumber(), command.expectedPlanAggregateVersion(), source.planWeeks(),
                command.mondayAnchorDate(), command.timezone(), status, command.actorId(), trainerId,
                periodId, confirmedBy, ts(at), confirmedBy == null ? null : ts(at));
        for (Item item : command.items()) {
            jdbc.update("""
                    INSERT INTO fitness.workout_recurrence_series_items
                    (series_id,source_plan_version_id,plan_session_id,week_number,day_number,sequence_number,
                     first_local_start,first_local_end) VALUES (?,?,?,?,?,?,?,?)
                    """, id, command.sourcePlanVersionId(), item.planSessionId(), item.weekNumber(), item.dayNumber(),
                    item.sequenceNumber(), item.firstLocalStart().toString(), item.firstLocalEnd().toString());
        }
        jdbc.update("""
                INSERT INTO fitness.workout_recurrence_series_history
                (series_id,series_version,from_status,to_status,actor_id,occurred_at)
                VALUES (?,0,NULL,?,?,?)
                """, id, status, command.actorId(), ts(at));
        Series result = find(id, false);
        saveReceipt(command.actorId(), command.commandKey(), commandName, fingerprint, result, at);
        return result;
    }

    private Source source(Create command) {
        var plan = plans.lockPlan(command.planId());
        var open = plans.lockOpenVersion(command.planId());
        Instant at = plans.databaseNow();
        if (plan.status() != WorkoutPlanStatus.ACTIVE || plan.version() != command.expectedPlanAggregateVersion()
                || !open.id().equals(command.sourcePlanVersionId())
                || !batches.isPublishedEffective(open.id(), command.planId(), at))
            throw conflict("WORKOUT_RECURRENCE_SOURCE_STALE");
        Integer weeks = jdbc.queryForObject("""
                SELECT MAX(week_number) FROM fitness.workout_plan_sessions WHERE workout_plan_version_id=?
                """, Integer.class, open.id());
        if (weeks == null || weeks < 1) throw conflict("WORKOUT_RECURRENCE_SOURCE_STALE");
        ZoneId zone = zone(command.timezone());
        Set<UUID> ids = new HashSet<>();
        for (Item item : command.items()) {
            if (item == null || item.planSessionId() == null || !ids.add(item.planSessionId())) throw invalid();
            var session = batches.findSession(open.id(), item.planSessionId());
            if (session == null || session.weekNumber() != item.weekNumber()
                    || session.dayNumber() != item.dayNumber()
                    || session.sequenceNumber() != item.sequenceNumber())
                throw conflict("WORKOUT_RECURRENCE_SOURCE_STALE");
            if ("COACH_REQUIRED".equals(session.supervision()))
                throw conflict("WORKOUT_RECURRENCE_COACH_REQUIRED_UNSUPPORTED");
            try {
                WorkoutRecurrenceCandidateCalculator.calculate(command.mondayAnchorDate(), weeks, 0,
                        item.planSessionId(), item.weekNumber(), item.dayNumber(),
                        item.firstLocalStart(), item.firstLocalEnd(), zone);
            } catch (IllegalArgumentException malformed) { throw invalid(); }
        }
        return new Source(open.versionNumber(), weeks);
    }

    private Page<Series> list(String predicate, Object[] subjects, String status, int page, int size) {
        String where = predicate + (status == null ? "" : " AND status=?");
        Object[] countArgs = status == null ? subjects : Arrays.copyOf(subjects, subjects.length + 1);
        if (status != null) countArgs[subjects.length] = status;
        long total = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_recurrence_series WHERE " + where,
                Long.class, countArgs);
        Object[] args = Arrays.copyOf(countArgs, countArgs.length + 2);
        args[countArgs.length] = size;
        args[countArgs.length + 1] = (long) page * size;
        var ids = jdbc.query("SELECT id FROM fitness.workout_recurrence_series WHERE " + where
                        + " ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
                (rs, row) -> rs.getObject(1, UUID.class), args);
        return new Page<>(ids.stream().map(id -> find(id, false)).toList(), page, size, total, pages(total, size));
    }

    private Series find(UUID id, boolean lock) {
        if (id == null) throw notFound();
        String sql = "SELECT * FROM fitness.workout_recurrence_series WHERE id=?" + (lock ? " FOR UPDATE" : "");
        return jdbc.query(sql, (rs, row) -> mapSeries(rs), id).stream().findFirst()
                .orElseThrow(WorkoutRecurrenceService::notFound);
    }

    private Series mapSeries(ResultSet rs) throws SQLException {
        UUID id = rs.getObject("id", UUID.class);
        var items = jdbc.query("""
                SELECT * FROM fitness.workout_recurrence_series_items
                WHERE series_id=? ORDER BY week_number,day_number,sequence_number,plan_session_id
                """, (row, n) -> new Item(row.getObject("plan_session_id", UUID.class), row.getInt("week_number"),
                row.getInt("day_number"), row.getInt("sequence_number"),
                LocalDateTime.parse(row.getString("first_local_start")),
                LocalDateTime.parse(row.getString("first_local_end"))), id);
        return new Series(id, rs.getObject("student_id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getObject("source_plan_version_id", UUID.class), rs.getInt("source_plan_version_number"),
                rs.getLong("observed_plan_aggregate_version"), rs.getInt("plan_weeks"),
                rs.getDate("monday_anchor").toLocalDate(), rs.getString("timezone"), rs.getString("status"),
                rs.getLong("version"), rs.getObject("initiated_by", UUID.class),
                rs.getObject("proposing_trainer_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class), rs.getObject("confirmed_by", UUID.class),
                instant(rs, "created_at"), instant(rs, "confirmed_at"), instant(rs, "stopped_at"), items, false);
    }

    private static Period mapPeriod(ResultSet rs) throws SQLException {
        return new Period(rs.getObject("id", UUID.class), rs.getObject("series_id", UUID.class),
                rs.getLong("block_iteration"), rs.getObject("plan_session_id", UUID.class),
                LocalDateTime.parse(rs.getString("local_start")), LocalDateTime.parse(rs.getString("local_end")),
                rs.getString("timezone"), rs.getString("start_utc_offset"),
                rs.getString("end_utc_offset"), instant(rs, "planned_start_at"),
                instant(rs, "planned_end_at"), rs.getString("outcome"),
                rs.getObject("occurrence_id", UUID.class), instant(rs, "processed_at"));
    }

    private void transition(Series prior, String status, UUID actor, String commandKey,
                            String reason, Instant at) {
        int changed = jdbc.update("""
                UPDATE fitness.workout_recurrence_series SET status=?,version=version+1,
                    confirmed_by=CASE WHEN ?='ACTIVE' THEN student_id ELSE confirmed_by END,
                    confirmed_at=CASE WHEN ?='ACTIVE' THEN ? ELSE confirmed_at END,
                    stopped_at=CASE WHEN ?='STOPPED' THEN ? ELSE stopped_at END
                WHERE id=? AND version=?
                """, status, status, status, ts(at), status, ts(at), prior.seriesId(), prior.seriesVersion());
        if (changed != 1) throw conflict("WORKOUT_RECURRENCE_VERSION_CONFLICT");
        jdbc.update("""
                INSERT INTO fitness.workout_recurrence_series_history
                (series_id,series_version,from_status,to_status,actor_id,reason,occurred_at)
                VALUES (?,?,?,?,?,?,?)
                """, prior.seriesId(), prior.seriesVersion() + 1, prior.status(), status, actor, reason, ts(at));
    }

    private Series receipt(UUID actor, String commandKey, String commandName, String fingerprint) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { while (rs.next()) {} }, "WORKOUT_RECURRENCE:" + actor + ":" + commandKey);
        var previous = jdbc.query("""
                SELECT command_name,payload_hash,response_json::text
                FROM fitness.workout_recurrence_command_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                actor, commandKey).stream().findFirst();
        if (previous.isEmpty()) return null;
        if (!commandName.equals(previous.get().command()) || !fingerprint.equals(previous.get().hash()))
            throw conflict("WORKOUT_RECURRENCE_IDEMPOTENCY_CONFLICT");
        try { return mapper.readValue(previous.get().json(), Series.class).asReplay(); }
        catch (Exception malformed) { throw new IllegalStateException("Cannot read recurrence receipt", malformed); }
    }

    private void saveReceipt(UUID actor, String key, String name, String fingerprint, Series response, Instant at) {
        jdbc.update("""
                INSERT INTO fitness.workout_recurrence_command_receipts
                (actor_id,command_key,command_name,payload_hash,series_id,response_json,created_at)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """, actor, key, name, fingerprint, response.seriesId(), json(response), ts(at));
    }

    private void authorizeRead(UUID actor, Series series) {
        if (actor != null && actor.equals(series.studentId())) { requireStudent(actor); return; }
        if (actor != null && actor.equals(series.proposingTrainerId())) {
            requireOriginalTrainer(actor, series, DataAccessLevel.VIEW);
            return;
        }
        throw notFound();
    }

    private UUID trainerPeriod(UUID actor, UUID student, DataAccessLevel level) {
        if (actor == null || student == null) throw notFound();
        var decision = coaching.evaluateCurrent(new AuthorityRequest(actor, student, DataScope.WORKOUT_PLAN, level));
        if (!decision.allowed() || decision.periodId() == null) throw notFound();
        return decision.periodId();
    }

    private void requireOriginalTrainer(UUID actor, Series series, DataAccessLevel level) {
        if (!series.coachingPeriodId().equals(trainerPeriod(actor, series.studentId(), level))) throw notFound();
    }

    private void requireStudent(UUID actor) {
        try { students.requireStudentMutation(actor, actor); }
        catch (WorkoutExecutionFailure denied) { throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_ACCESS_DENIED", 403); }
    }

    private static void checkVersion(Series series, long expected, String requiredStatus) {
        if (!requiredStatus.equals(series.status())) throw conflict("WORKOUT_RECURRENCE_STATE_CONFLICT");
        if (expected != series.seriesVersion()) throw conflict("WORKOUT_RECURRENCE_VERSION_CONFLICT");
    }

    private static void validateCreate(Create command) {
        if (command == null || command.actorId() == null || command.studentId() == null
                || command.planId() == null || command.sourcePlanVersionId() == null
                || command.expectedPlanAggregateVersion() < 0 || command.mondayAnchorDate() == null
                || command.mondayAnchorDate().getDayOfWeek() != DayOfWeek.MONDAY
                || command.timezone() == null || command.timezone().isBlank()
                || command.timezone().length() > 64 || command.items() == null || command.items().isEmpty())
            throw invalid();
        key(command.commandKey());
    }

    private static void validateVersionCommand(VersionCommand command) {
        if (command == null || command.actorId() == null || command.seriesId() == null
                || command.expectedSeriesVersion() < 0) throw invalid();
        key(command.commandKey());
    }

    private static void validatePage(String status, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || status != null && !List.of(
                "PENDING", "ACTIVE", "PAUSED_SOURCE", "STOPPED", "REJECTED", "WITHDRAWN").contains(status))
            throw invalid();
    }

    private static String key(String key) {
        if (key == null || key.isBlank() || key.length() > 120) throw invalid();
        return key;
    }

    private static int pages(long total, int size) { return (int) ((total + size - 1) / size); }
    private static ZoneId zone(String name) {
        try {
            if (!ZoneId.getAvailableZoneIds().contains(name)) throw invalid();
            return ZoneId.of(name);
        } catch (DateTimeException invalid) { throw invalid(); }
    }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static String offset(ZoneOffset value) {
        return value == null ? null : (value.equals(ZoneOffset.UTC) ? "+00:00" : value.getId());
    }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static WorkoutScheduleFailure invalid() {
        return new WorkoutScheduleFailure("WORKOUT_RECURRENCE_VALIDATION_FAILED", 400);
    }
    private static WorkoutScheduleFailure notFound() {
        return new WorkoutScheduleFailure("WORKOUT_RECURRENCE_NOT_FOUND", 404);
    }
    private static WorkoutScheduleFailure conflict(String code) { return new WorkoutScheduleFailure(code, 409); }
    private String hash(Object... values) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(Arrays.asList(values)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("Cannot serialize recurrence command", failure); }
    }
    private record Source(int versionNumber, int planWeeks) {}
    private record Receipt(String command, String hash, String json) {}
}
