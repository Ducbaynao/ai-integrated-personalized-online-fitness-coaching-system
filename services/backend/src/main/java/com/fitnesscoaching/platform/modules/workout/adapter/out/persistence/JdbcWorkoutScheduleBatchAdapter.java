package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmedBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmedItem;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleBatchPersistencePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkoutScheduleBatchAdapter implements WorkoutScheduleBatchPersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutScheduleBatchAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Timestamp ts(Instant instant) { return Timestamp.from(instant); }

    @Override public Optional<Receipt> lockReceipt(UUID actorId, String commandKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { while (rs.next()) {} }, "WORKOUT_SCHEDULE:" + actorId + ":" + commandKey);
        return jdbc.query("""
                SELECT command_name,payload_hash,response_json::text FROM fitness.workout_schedule_command_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, n) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                actorId, commandKey).stream().findFirst();
    }

    @Override public Session findSession(UUID versionId, UUID sessionId) {
        return jdbc.query("""
                SELECT id,week_number,day_number,sequence_number,supervision_requirement::text
                FROM fitness.workout_plan_sessions WHERE workout_plan_version_id=? AND id=?
                """, (rs, n) -> new Session(rs.getObject(1, UUID.class), rs.getInt(2), rs.getInt(3),
                rs.getInt(4), rs.getString(5)), versionId, sessionId).stream().findFirst().orElse(null);
    }

    @Override public boolean isPublishedEffective(UUID versionId, UUID planId, Instant at) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.workout_plan_versions
                WHERE id=? AND workout_plan_id=? AND locked_at IS NOT NULL
                  AND effective_from<=? AND (effective_until IS NULL OR ?<effective_until))
                """, Boolean.class, versionId, planId, ts(at), ts(at)));
    }

    @Override public boolean hasScheduledUnknownEnd(UUID studentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.planned_workouts
                WHERE student_id=? AND status='SCHEDULED' AND planned_end_at IS NULL)
                """, Boolean.class, studentId));
    }

    @Override public boolean hasSourceInstant(UUID studentId, UUID sessionId, Instant start) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.planned_workouts
                WHERE student_id=? AND workout_plan_session_id=? AND planned_start_at=?)
                """, Boolean.class, studentId, sessionId, ts(start)));
    }

    @Override public boolean hasScheduledOverlap(UUID studentId, Instant start, Instant end) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.planned_workouts
                WHERE student_id=? AND status='SCHEDULED' AND planned_end_at IS NOT NULL
                  AND planned_start_at<? AND planned_end_at>?)
                """, Boolean.class, studentId, ts(end), ts(start)));
    }

    @Override public void saveBatch(ConfirmedBatch b) {
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_batches
                (id,student_id,plan_id,plan_version_id,plan_version_number,week_anchor_date,timezone,
                 confirmation_source,source_proposal_id,confirmed_by,confirmed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, b.batchId(), b.studentId(), b.planId(), b.planVersionId(), b.planVersionNumber(),
                b.weekAnchorDate(), b.timezone(), b.confirmationSource(), b.sourceProposalId(),
                b.confirmedBy(), ts(b.confirmedAt()));
    }

    @Override public void saveOccurrence(ConfirmedBatch b, ConfirmedItem item, UUID coachingPeriodId) {
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,planned_start_at,planned_end_at,
                 original_planned_start_at,status,created_by,created_at,updated_at,supervision_requirement,
                 schedule_batch_id,schedule_local_start,schedule_local_end,schedule_timezone,
                 schedule_start_utc_offset,schedule_end_utc_offset)
                VALUES (?,?,?,?,?,?,?,'SCHEDULED',?,?,?,?::fitness.supervision_requirement,?,?,?,?,?,?)
                """, item.occurrenceId(), b.studentId(), item.planSessionId(), coachingPeriodId,
                ts(item.plannedStartAt()), ts(item.plannedEndAt()), ts(item.plannedStartAt()),
                b.confirmedBy(), ts(b.confirmedAt()), ts(b.confirmedAt()), item.supervisionRequirement(),
                b.batchId(), item.localStart().toString(), item.localEnd().toString(), b.timezone(),
                item.startUtcOffset(), item.endUtcOffset());
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_batch_items
                (batch_id,client_item_id,occurrence_id,plan_session_id,week_number,day_number,sequence_number)
                VALUES (?,?,?,?,?,?,?)
                """, b.batchId(), item.clientItemId(), item.occurrenceId(), item.planSessionId(),
                item.weekNumber(), item.dayNumber(), item.sequenceNumber());
    }

    @Override public void saveReceipt(UUID actorId, String commandKey, String hash, UUID batchId,
                                      String responseJson, Instant at) {
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_command_receipts
                (actor_id,command_key,command_name,payload_hash,batch_id,response_json,created_at)
                VALUES (? ,?,'CONFIRM_DIRECT',?, ?,?::jsonb,?)
                """, actorId, commandKey, hash, batchId, responseJson, ts(at));
    }
}
