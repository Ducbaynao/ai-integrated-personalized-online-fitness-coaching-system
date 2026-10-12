package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.ChangeResult;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleChangePersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkoutScheduleChangeAdapter implements WorkoutScheduleChangePersistencePort {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public JdbcWorkoutScheduleChangeAdapter(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc; this.mapper = mapper;
    }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }

    @Override public Optional<Receipt> lockReceipt(UUID actorId, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { while (rs.next()) {} }, "WORKOUT_CHANGE:" + actorId + ":" + key);
        return jdbc.query("""
                SELECT command_name,payload_hash,response_json::text
                FROM fitness.workout_schedule_change_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                actorId, key).stream().findFirst();
    }

    @Override public Occurrence lockOccurrence(UUID id) {
        return jdbc.query("""
                SELECT pw.id,pw.student_id,pw.workout_plan_session_id,s.workout_plan_version_id,
                       pw.coaching_period_id,pw.version,pw.status::text,pw.execution_started_at,
                       pw.planned_start_at,pw.planned_end_at,pw.original_planned_start_at,
                       pw.schedule_local_start,pw.schedule_local_end,pw.schedule_timezone,
                       pw.schedule_start_utc_offset,pw.schedule_end_utc_offset,
                       pw.supervision_requirement::text,pw.schedule_batch_id
                FROM fitness.planned_workouts pw
                LEFT JOIN fitness.workout_plan_sessions s ON s.id=pw.workout_plan_session_id
                WHERE pw.id=? FOR UPDATE OF pw
                """, (rs, row) -> new Occurrence(rs.getObject("id", UUID.class),
                rs.getObject("student_id", UUID.class), rs.getObject("workout_plan_session_id", UUID.class),
                rs.getObject("workout_plan_version_id", UUID.class), rs.getObject("coaching_period_id", UUID.class),
                rs.getLong("version"), rs.getString("status"), instant(rs.getTimestamp("execution_started_at")),
                instant(rs.getTimestamp("planned_start_at")), instant(rs.getTimestamp("planned_end_at")),
                instant(rs.getTimestamp("original_planned_start_at")), rs.getString("schedule_local_start"),
                rs.getString("schedule_local_end"), rs.getString("schedule_timezone"),
                rs.getString("schedule_start_utc_offset"), rs.getString("schedule_end_utc_offset"),
                rs.getString("supervision_requirement"), rs.getObject("schedule_batch_id", UUID.class)), id)
                .stream().findFirst().orElseThrow(() -> new WorkoutScheduleFailure("PLANNED_WORKOUT_NOT_FOUND", 404));
    }

    @Override public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override public boolean hasSuccessor(UUID id) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.planned_workouts WHERE rescheduled_from_id=?)
                """, Boolean.class, id));
    }

    @Override public boolean hasOtherScheduledUnknownEnd(UUID studentId, UUID oldId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.planned_workouts
                WHERE student_id=? AND id<>? AND status='SCHEDULED' AND planned_end_at IS NULL)
                """, Boolean.class, studentId, oldId));
    }

    @Override public boolean hasSourceInstant(UUID studentId, UUID sessionId, Instant start) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.planned_workouts
                WHERE student_id=? AND workout_plan_session_id=? AND planned_start_at=?)
                """, Boolean.class, studentId, sessionId, ts(start)));
    }

    @Override public boolean hasOtherScheduledOverlap(UUID studentId, UUID oldId, Instant start, Instant end) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.planned_workouts
                WHERE student_id=? AND id<>? AND status='SCHEDULED' AND planned_end_at IS NOT NULL
                  AND planned_start_at<? AND planned_end_at>?)
                """, Boolean.class, studentId, oldId, ts(end), ts(start)));
    }

    @Override public void reschedule(Occurrence old, UUID replacementId, UUID changeId, UUID actorId,
                                     Destination destination, Instant start, Instant end, Instant at, String reason) {
        int changed = jdbc.update("""
                UPDATE fitness.planned_workouts SET status='RESCHEDULED',status_reason=?,version=version+1,
                    updated_at=? WHERE id=? AND version=? AND status='SCHEDULED' AND execution_started_at IS NULL
                """, reason, ts(at), old.id(), old.version());
        if (changed != 1) throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT", 409);
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_occurrence_changes
                (id,student_id,previous_occurrence_id,replacement_occurrence_id,change_type,actor_id,
                 old_planned_start_at,old_planned_end_at,new_planned_start_at,new_planned_end_at,reason,occurred_at)
                VALUES (?,?,?,?,'RESCHEDULE',?,?,?,?,?,?,?)
                """, changeId, old.studentId(), old.id(), replacementId, actorId,
                ts(old.plannedStartAt()), ts(old.plannedEndAt()), ts(start), ts(end), reason, ts(at));
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,planned_start_at,planned_end_at,
                 original_planned_start_at,status,rescheduled_from_id,created_by,created_at,updated_at,
                 supervision_requirement,schedule_change_id,schedule_local_start,schedule_local_end,
                 schedule_timezone,schedule_start_utc_offset,schedule_end_utc_offset)
                VALUES (?,?,?,?,?, ?,?,'SCHEDULED',?,?, ?,?,?::fitness.supervision_requirement,?,?,?,?,?,?)
                """, replacementId, old.studentId(), old.sessionId(), old.coachingPeriodId(), ts(start), ts(end),
                ts(old.originalPlannedStartAt()), old.id(), actorId, ts(at), ts(at),
                old.supervisionRequirement(), changeId, destination.localStart().toString(),
                destination.localEnd().toString(), destination.timezone(),
                destination.startUtcOffset(), destination.endUtcOffset());
    }

    @Override public void cancel(Occurrence old, UUID changeId, UUID actorId, Instant at, String reason) {
        int changed = jdbc.update("""
                UPDATE fitness.planned_workouts SET status='CANCELLED',status_reason=?,version=version+1,
                    updated_at=? WHERE id=? AND version=? AND status='SCHEDULED' AND execution_started_at IS NULL
                """, reason, ts(at), old.id(), old.version());
        if (changed != 1) throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_OCCURRENCE_VERSION_CONFLICT", 409);
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_occurrence_changes
                (id,student_id,previous_occurrence_id,change_type,actor_id,old_planned_start_at,
                 old_planned_end_at,reason,occurred_at)
                VALUES (?,?,?,'CANCEL',?,?,?,?,?)
                """, changeId, old.studentId(), old.id(), actorId, ts(old.plannedStartAt()),
                ts(old.plannedEndAt()), reason, ts(at));
    }

    @Override public void saveReceipt(UUID actorId, String key, String name, String hash, UUID changeId,
                                      ChangeResult result, Instant at) {
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_change_receipts
                (actor_id,command_key,command_name,payload_hash,change_id,response_json,created_at)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """, actorId, key, name, hash, changeId, mapper.writeValueAsString(result), ts(at));
    }
}
