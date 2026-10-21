package com.fitnesscoaching.platform.modules.schedule.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.QualifyingWorkoutAppointmentQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcQualifyingWorkoutAppointmentQuery implements QualifyingWorkoutAppointmentQuery {
    private final JdbcTemplate jdbc;

    public JdbcQualifyingWorkoutAppointmentQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean existsForOccurrence(UUID occurrenceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (
                  SELECT 1 FROM fitness.appointments a
                  JOIN fitness.planned_workouts p ON p.id=a.planned_workout_id
                  JOIN fitness.coaching_periods cp ON cp.id=a.coaching_period_id
                  WHERE p.id=? AND a.status='CONFIRMED'
                    AND a.student_id=p.student_id AND a.student_id=cp.student_id
                    AND a.trainer_id=cp.trainer_id AND p.coaching_period_id=cp.id
                    AND cp.mode='HUMAN_COACH' AND cp.started_at<=clock_timestamp()
                    AND (cp.ended_at IS NULL OR cp.ended_at>clock_timestamp())
                    AND p.planned_end_at IS NOT NULL
                    AND a.start_at<=p.planned_start_at AND a.end_at>=p.planned_end_at
                    AND clock_timestamp()>=a.start_at AND clock_timestamp()<a.end_at)
                """, Boolean.class, occurrenceId));
    }

    @Override public boolean lockForExecution(UUID occurrenceId) {
        return !jdbc.query("""
                SELECT a.id FROM fitness.appointments a
                JOIN fitness.planned_workouts p ON p.id=a.planned_workout_id
                JOIN fitness.coaching_periods cp ON cp.id=a.coaching_period_id
                WHERE p.id=? AND a.status='CONFIRMED'
                  AND a.student_id=p.student_id AND a.student_id=cp.student_id
                  AND a.trainer_id=cp.trainer_id AND p.coaching_period_id=cp.id
                  AND cp.mode='HUMAN_COACH' AND cp.started_at<=clock_timestamp()
                  AND (cp.ended_at IS NULL OR cp.ended_at>clock_timestamp())
                  AND p.planned_end_at IS NOT NULL
                  AND a.start_at<=p.planned_start_at AND a.end_at>=p.planned_end_at
                  AND clock_timestamp()>=a.start_at AND clock_timestamp()<a.end_at
                ORDER BY a.id LIMIT 1 FOR SHARE OF a
                """, (rs, row) -> rs.getObject(1, UUID.class), occurrenceId).isEmpty();
    }
}
