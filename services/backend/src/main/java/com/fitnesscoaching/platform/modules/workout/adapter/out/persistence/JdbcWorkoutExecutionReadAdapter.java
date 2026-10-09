package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutExecutionStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcWorkoutExecutionReadAdapter implements WorkoutExecutionReadPort {
    private final JdbcTemplate jdbc;

    public JdbcWorkoutExecutionReadAdapter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<ExecutionRecord> findCurrent(UUID studentId) {
        return ids("""
                SELECT id FROM fitness.workout_session_logs
                WHERE student_id=? AND status='IN_PROGRESS'
                """, studentId).stream().findFirst().flatMap(this::findDetail);
    }

    @Override
    public Optional<ExecutionRecord> findDetail(UUID executionId) {
        Optional<ExecutionRecord> root = jdbc.query("""
                SELECT id,student_id,planned_workout_id,source_workout_plan_id,workout_plan_version_id,
                       workout_plan_session_id,coaching_period_id,snapshot_mode,performed_start_at,
                       performed_end_at,status::text,overall_rpe,notes,version,snapshot_frozen_at,
                       frozen_occurrence_version,frozen_planned_start_at,frozen_original_planned_start_at,
                       frozen_planned_end_at,frozen_supervision_requirement::text
                FROM fitness.workout_session_logs WHERE id=?
                """, (rs, row) -> root(rs), executionId).stream().findFirst();
        if (root.isEmpty()) return root;
        ExecutionRecord value = root.get();
        return Optional.of(copyWithExercises(value, exercises(executionId)));
    }

    @Override
    public PageRecord findHistory(UUID studentId, Instant from, Instant until, int page, int size) {
        String predicate = """
                student_id=?
                AND (?::timestamptz IS NULL OR performed_start_at>=?::timestamptz)
                AND (?::timestamptz IS NULL OR performed_start_at<?::timestamptz)
                """;
        Object[] boundary = {studentId, timestamp(from), timestamp(from), timestamp(until), timestamp(until)};
        Long total = jdbc.queryForObject("SELECT count(*) FROM fitness.workout_session_logs WHERE " + predicate,
                Long.class, boundary);
        long offset = Math.multiplyExact((long) page, size);
        Object[] pageArgs = {studentId, timestamp(from), timestamp(from), timestamp(until), timestamp(until),
                size, offset};
        List<ExecutionRecord> items = ids("SELECT id FROM fitness.workout_session_logs WHERE " + predicate
                + " ORDER BY performed_start_at DESC,id LIMIT ? OFFSET ?", pageArgs).stream()
                .map(this::findDetail).flatMap(Optional::stream).toList();
        return new PageRecord(items, total == null ? 0 : total);
    }

    private List<ExerciseRecord> exercises(UUID executionId) {
        return jdbc.query("""
                SELECT id,planned_session_exercise_id,exercise_variation_id,actual_exercise_variation_id,
                       substitution_reason,frozen_sequence_number,baseline_set_count,target_reps_min,target_reps_max,
                       target_load,target_load_unit_id,target_rpe,target_rir,target_rest_seconds,target_tempo,
                       target_duration_seconds,target_distance_value,target_distance_unit_id,frozen_instructions,
                       frozen_prescription_note
                FROM fitness.exercise_logs WHERE workout_session_log_id=?
                ORDER BY COALESCE(frozen_sequence_number,sequence_number),id
                """, (rs, row) -> new ExerciseRecord(rs.getObject("id", UUID.class),
                rs.getObject("planned_session_exercise_id", UUID.class),
                rs.getObject("exercise_variation_id", UUID.class),
                rs.getObject("actual_exercise_variation_id", UUID.class), rs.getString("substitution_reason"),
                (Integer) rs.getObject("frozen_sequence_number"), (Integer) rs.getObject("baseline_set_count"),
                (Integer) rs.getObject("target_reps_min"), (Integer) rs.getObject("target_reps_max"),
                rs.getBigDecimal("target_load"), shortValue(rs, "target_load_unit_id"),
                rs.getBigDecimal("target_rpe"), rs.getBigDecimal("target_rir"),
                (Integer) rs.getObject("target_rest_seconds"), rs.getString("target_tempo"),
                (Integer) rs.getObject("target_duration_seconds"), rs.getBigDecimal("target_distance_value"),
                shortValue(rs, "target_distance_unit_id"), rs.getString("frozen_instructions"),
                rs.getString("frozen_prescription_note"), sets(rs.getObject("id", UUID.class))), executionId);
    }

    private List<SetRecord> sets(UUID exerciseId) {
        return jdbc.query("""
                SELECT id,client_set_id,baseline_set_number,set_number,set_type::text,completion_status::text,
                       repetitions,load_value,load_unit_id,duration_seconds,distance_value,distance_unit_id,
                       rpe,rir,tempo,rest_after_seconds,notes,completed_at
                FROM fitness.set_logs WHERE exercise_log_id=? ORDER BY set_number,id
                """, (rs, row) -> set(rs), exerciseId);
    }

    private List<UUID> ids(String sql, Object... args) {
        return jdbc.query(sql, (rs, row) -> rs.getObject(1, UUID.class), args);
    }

    private static ExecutionRecord root(ResultSet rs) throws SQLException {
        return new ExecutionRecord(rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
                rs.getObject("planned_workout_id", UUID.class), rs.getObject("source_workout_plan_id", UUID.class),
                rs.getObject("workout_plan_version_id", UUID.class),
                rs.getObject("workout_plan_session_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class), rs.getString("snapshot_mode"),
                instant(rs.getTimestamp("performed_start_at")), instant(rs.getTimestamp("performed_end_at")),
                WorkoutExecutionStatus.valueOf(rs.getString("status")), rs.getBigDecimal("overall_rpe"),
                rs.getString("notes"), rs.getLong("version"), instant(rs.getTimestamp("snapshot_frozen_at")),
                (Long) rs.getObject("frozen_occurrence_version"),
                instant(rs.getTimestamp("frozen_planned_start_at")),
                instant(rs.getTimestamp("frozen_original_planned_start_at")),
                instant(rs.getTimestamp("frozen_planned_end_at")),
                rs.getString("frozen_supervision_requirement"), List.of());
    }

    private static ExecutionRecord copyWithExercises(ExecutionRecord value, List<ExerciseRecord> exercises) {
        return new ExecutionRecord(value.executionId(), value.studentId(), value.plannedWorkoutId(), value.planId(),
                value.planVersionId(), value.planSessionId(), value.coachingPeriodId(), value.snapshotMode(),
                value.performedStartedAt(), value.performedEndedAt(), value.status(), value.overallRpe(),
                value.sessionNote(), value.version(), value.frozenAt(), value.sourceOccurrenceVersion(),
                value.plannedStartAt(), value.originalPlannedStartAt(), value.plannedEndAt(),
                value.supervisionRequirement(), exercises);
    }

    private static SetRecord set(ResultSet rs) throws SQLException {
        return new SetRecord(rs.getObject("client_set_id", UUID.class),
                (Integer) rs.getObject("baseline_set_number"), rs.getInt("set_number"), rs.getString("set_type"),
                rs.getString("completion_status"), (Integer) rs.getObject("repetitions"),
                rs.getBigDecimal("load_value"), shortValue(rs, "load_unit_id"),
                (Integer) rs.getObject("duration_seconds"), rs.getBigDecimal("distance_value"),
                shortValue(rs, "distance_unit_id"), rs.getBigDecimal("rpe"), rs.getBigDecimal("rir"),
                rs.getString("tempo"), (Integer) rs.getObject("rest_after_seconds"), rs.getString("notes"),
                instant(rs.getTimestamp("completed_at")));
    }

    private static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    private static Short shortValue(ResultSet rs, String column) throws SQLException {
        Number value = (Number) rs.getObject(column);
        return value == null ? null : value.shortValue();
    }
}
