package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionSnapshotPersistencePort;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutSessionAdjustment;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcWorkoutExecutionSnapshotAdapter implements WorkoutExecutionSnapshotPersistencePort {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutExecutionSnapshotAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }

    @Override public SnapshotOccurrence lockOccurrence(UUID id) {
        return jdbc.query("""
                SELECT pw.id,pw.student_id,v.workout_plan_id,v.id plan_version_id,pw.workout_plan_session_id,
                       pw.coaching_period_id,pw.planned_start_at,pw.planned_end_at,pw.original_planned_start_at,
                       pw.status::text,pw.supervision_requirement::text,pw.execution_started_at,pw.version
                FROM fitness.planned_workouts pw
                JOIN fitness.workout_plan_sessions s ON s.id=pw.workout_plan_session_id
                JOIN fitness.workout_plan_versions v ON v.id=s.workout_plan_version_id
                WHERE pw.id=? FOR UPDATE OF pw
                """, (rs, n) -> new SnapshotOccurrence(rs.getObject("id", UUID.class),
                rs.getObject("student_id", UUID.class), rs.getObject("workout_plan_id", UUID.class),
                rs.getObject("plan_version_id", UUID.class), rs.getObject("workout_plan_session_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class), instant(rs.getTimestamp("planned_start_at")),
                instant(rs.getTimestamp("planned_end_at")), instant(rs.getTimestamp("original_planned_start_at")),
                rs.getString("status"), rs.getString("supervision_requirement"),
                instant(rs.getTimestamp("execution_started_at")), rs.getLong("version")), id)
                .stream().findFirst().orElseThrow(() -> new WorkoutExecutionFailure(
                        WorkoutExecutionError.WORKOUT_EXECUTION_NOT_FOUND, "Planned workout not found"));
    }

    @Override public List<SnapshotPrescription> prescriptions(UUID sessionId) {
        return jdbc.query("""
                SELECT id,exercise_variation_id,sequence_number,target_sets,target_reps_min,target_reps_max,
                       target_load,load_unit_id,target_rpe,target_rir,rest_seconds,tempo,duration_seconds,
                       distance_value,distance_unit_id,instructions
                FROM fitness.workout_plan_session_exercises
                WHERE workout_plan_session_id=? ORDER BY sequence_number,id
                """, (rs, n) -> new SnapshotPrescription(rs.getObject("id", UUID.class),
                rs.getObject("exercise_variation_id", UUID.class), rs.getInt("sequence_number"),
                (Integer) rs.getObject("target_sets"), (Integer) rs.getObject("target_reps_min"),
                (Integer) rs.getObject("target_reps_max"), rs.getBigDecimal("target_load"),
                shortValue(rs, "load_unit_id"), rs.getBigDecimal("target_rpe"), rs.getBigDecimal("target_rir"),
                (Integer) rs.getObject("rest_seconds"), rs.getString("tempo"),
                (Integer) rs.getObject("duration_seconds"), rs.getBigDecimal("distance_value"),
                shortValue(rs, "distance_unit_id"), rs.getString("instructions")), sessionId);
    }

    @Override public List<StoredAdjustment> adjustments(UUID occurrenceId) {
        return jdbc.query("""
                SELECT id,adjustment_type,planned_session_exercise_id,replacement_exercise_variation_id,
                       resolution_state,typed_target_load,typed_load_unit_id,typed_reps_min,typed_reps_max,
                       typed_target_sets,typed_duration_seconds,typed_sequence,typed_note
                FROM fitness.workout_session_adjustments WHERE planned_workout_id=?
                ORDER BY created_at,id
                """, (rs, n) -> new StoredAdjustment(rs.getObject("id", UUID.class),
                WorkoutSessionAdjustment.Type.valueOf(rs.getString("adjustment_type")),
                rs.getObject("planned_session_exercise_id", UUID.class),
                rs.getObject("replacement_exercise_variation_id", UUID.class), rs.getString("resolution_state"),
                rs.getBigDecimal("typed_target_load"), shortValue(rs, "typed_load_unit_id"),
                (Integer) rs.getObject("typed_reps_min"), (Integer) rs.getObject("typed_reps_max"),
                (Integer) rs.getObject("typed_target_sets"), (Integer) rs.getObject("typed_duration_seconds"),
                (Integer) rs.getObject("typed_sequence"), rs.getString("typed_note")), occurrenceId);
    }

    @Override public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override public void seal(UUID occurrenceId, long expected, Instant at) {
        int changed = jdbc.update("""
                UPDATE fitness.planned_workouts
                SET execution_started_at=?,version=version+1,updated_at=?
                WHERE id=? AND version=? AND status='SCHEDULED' AND execution_started_at IS NULL
                """, ts(at), ts(at), occurrenceId, expected);
        if (changed != 1) throw conflict(WorkoutExecutionError.PLANNED_WORKOUT_ALREADY_STARTED);
    }

    @Override public void skip(UUID occurrenceId, long expected, String reason, Instant at) {
        int changed = jdbc.update("""
                UPDATE fitness.planned_workouts SET status='SKIPPED',status_reason=?,version=version+1,updated_at=?
                WHERE id=? AND version=? AND status='SCHEDULED' AND execution_started_at IS NULL
                """, reason, ts(at), occurrenceId, expected);
        if (changed != 1) throw conflict(WorkoutExecutionError.PLANNED_WORKOUT_NOT_STARTABLE);
    }

    @Override public void synchronizeTerminal(UUID occurrenceId, long expected, String status, Instant at) {
        try {
            int changed = jdbc.update("""
                    UPDATE fitness.planned_workouts SET status=?::fitness.planned_workout_status,
                        version=version+1,updated_at=?
                    WHERE id=? AND version=? AND status='SCHEDULED' AND execution_started_at IS NOT NULL
                    """, status, ts(at), occurrenceId, expected);
            if (changed != 1) throw conflict(WorkoutExecutionError.WORKOUT_EXECUTION_LIFECYCLE_CONFLICT);
        } catch (DataIntegrityViolationException ex) {
            throw conflict(WorkoutExecutionError.WORKOUT_EXECUTION_LIFECYCLE_CONFLICT);
        }
    }

    private static WorkoutExecutionFailure conflict(WorkoutExecutionError error) {
        return new WorkoutExecutionFailure(error, error.name());
    }

    private static Short shortValue(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.shortValue();
    }
}
