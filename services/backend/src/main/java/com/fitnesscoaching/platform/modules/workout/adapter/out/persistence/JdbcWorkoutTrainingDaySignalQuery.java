package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutTrainingDaySignalQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

@Repository
public class JdbcWorkoutTrainingDaySignalQuery implements WorkoutTrainingDaySignalQuery {
    private final JdbcTemplate jdbc;

    public JdbcWorkoutTrainingDaySignalQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean hasConfirmedOrPerformedWorkout(UUID studentId, LocalDate localDate, ZoneId timezone) {
        Timestamp from = Timestamp.from(localDate.atStartOfDay(timezone).toInstant());
        Timestamp until = Timestamp.from(localDate.plusDays(1).atStartOfDay(timezone).toInstant());
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(
                    SELECT 1 FROM fitness.planned_workouts p
                    WHERE p.student_id=? AND p.planned_start_at>=? AND p.planned_start_at<?
                      AND p.status IN ('SCHEDULED','COMPLETED','ATTEMPTED')
                    UNION ALL
                    SELECT 1 FROM fitness.workout_session_logs e
                    WHERE e.student_id=? AND e.performed_start_at>=? AND e.performed_start_at<?
                      AND e.status IN ('IN_PROGRESS','COMPLETED','PARTIALLY_COMPLETED')
                )
                """, Boolean.class, studentId, from, until, studentId, from, until));
    }
}
