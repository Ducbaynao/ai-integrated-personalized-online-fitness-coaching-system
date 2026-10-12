package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutScheduleCalendarReadPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Repository
public class JdbcWorkoutScheduleCalendarReadAdapter implements WorkoutScheduleCalendarReadPort {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutScheduleCalendarReadAdapter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Page findStudentWindow(UUID studentId, Instant from, Instant until, int page, int size) {
        long total = jdbc.queryForObject("""
                SELECT count(*) FROM fitness.planned_workouts
                WHERE student_id=? AND planned_start_at>=? AND planned_start_at<?
                """, Long.class, studentId, Timestamp.from(from), Timestamp.from(until));
        var rows = jdbc.query("""
                SELECT pw.id,pw.version,pw.student_id,pw.status::text,pw.planned_start_at,
                       pw.original_planned_start_at,pw.rescheduled_from_id,
                       (SELECT child.id FROM fitness.planned_workouts child
                        WHERE child.rescheduled_from_id=pw.id) AS replacement_occurrence_id,
                       pw.planned_end_at,pw.schedule_timezone,
                       pw.schedule_local_start,pw.schedule_local_end,pw.schedule_start_utc_offset,
                       pw.schedule_end_utc_offset,pw.execution_started_at,pw.supervision_requirement::text,
                       (pw.supervision_override_by IS NOT NULL AND pw.supervision_override_reason IS NOT NULL)
                           AS has_valid_supervision_override,
                       s.id AS session_id,s.week_number,s.day_number,s.sequence_number,s.name AS session_name,
                       s.session_focus,s.estimated_duration_minutes,s.supervision_requirement::text AS session_supervision,
                       v.id AS version_id,v.version_number,p.id AS plan_id,p.name AS plan_name
                FROM fitness.planned_workouts pw
                LEFT JOIN fitness.workout_plan_sessions s ON s.id=pw.workout_plan_session_id
                LEFT JOIN fitness.workout_plan_versions v ON v.id=s.workout_plan_version_id
                LEFT JOIN fitness.workout_plans p ON p.id=v.workout_plan_id
                WHERE pw.student_id=? AND pw.planned_start_at>=? AND pw.planned_start_at<?
                ORDER BY pw.planned_start_at ASC,pw.id ASC LIMIT ? OFFSET ?
                """, (rs, n) -> map(rs), studentId, Timestamp.from(from), Timestamp.from(until),
                size, (long) page * size);
        return new Page(rows, total);
    }

    @Override public boolean hasInProgressExecution(UUID studentId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.workout_session_logs
                WHERE student_id=? AND status='IN_PROGRESS')
                """, Boolean.class, studentId));
    }

    @Override public Row findOccurrence(UUID id) {
        return jdbc.query("""
                SELECT pw.id,pw.version,pw.student_id,pw.status::text,pw.planned_start_at,
                       pw.original_planned_start_at,pw.rescheduled_from_id,
                       (SELECT child.id FROM fitness.planned_workouts child
                        WHERE child.rescheduled_from_id=pw.id) AS replacement_occurrence_id,
                       pw.planned_end_at,pw.schedule_timezone,pw.schedule_local_start,pw.schedule_local_end,
                       pw.schedule_start_utc_offset,pw.schedule_end_utc_offset,pw.execution_started_at,
                       pw.supervision_requirement::text,
                       (pw.supervision_override_by IS NOT NULL AND pw.supervision_override_reason IS NOT NULL)
                           AS has_valid_supervision_override,
                       s.id AS session_id,s.week_number,s.day_number,s.sequence_number,s.name AS session_name,
                       s.session_focus,s.estimated_duration_minutes,s.supervision_requirement::text AS session_supervision,
                       v.id AS version_id,v.version_number,p.id AS plan_id,p.name AS plan_name
                FROM fitness.planned_workouts pw
                LEFT JOIN fitness.workout_plan_sessions s ON s.id=pw.workout_plan_session_id
                LEFT JOIN fitness.workout_plan_versions v ON v.id=s.workout_plan_version_id
                LEFT JOIN fitness.workout_plans p ON p.id=v.workout_plan_id
                WHERE pw.id=?
                """, (rs, n) -> map(rs), id).stream().findFirst().orElse(null);
    }

    private static Row map(ResultSet rs) throws SQLException {
        UUID sessionId = rs.getObject("session_id", UUID.class);
        UUID planId = rs.getObject("plan_id", UUID.class);
        SessionSummary session = sessionId == null ? null : new SessionSummary(sessionId,
                rs.getInt("week_number"), rs.getInt("day_number"), rs.getInt("sequence_number"),
                rs.getString("session_name"), rs.getString("session_focus"),
                (Integer) rs.getObject("estimated_duration_minutes"), rs.getString("session_supervision"));
        PlanSummary plan = planId == null ? null : new PlanSummary(planId,
                rs.getObject("version_id", UUID.class), rs.getInt("version_number"), rs.getString("plan_name"));
        return new Row(rs.getObject("id", UUID.class), rs.getLong("version"),
                rs.getObject("student_id", UUID.class), rs.getString("status"),
                instant(rs, "planned_start_at"), instant(rs, "original_planned_start_at"),
                rs.getObject("rescheduled_from_id", UUID.class),
                rs.getObject("replacement_occurrence_id", UUID.class), instant(rs, "planned_end_at"),
                rs.getString("schedule_timezone"),
                rs.getString("schedule_local_start"), rs.getString("schedule_local_end"),
                rs.getString("schedule_start_utc_offset"), rs.getString("schedule_end_utc_offset"),
                plan, session, instant(rs, "execution_started_at"),
                rs.getString("supervision_requirement"), rs.getBoolean("has_valid_supervision_override"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
