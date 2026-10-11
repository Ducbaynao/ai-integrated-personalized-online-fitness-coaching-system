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
                SELECT EXISTS (SELECT 1 FROM fitness.appointments
                WHERE planned_workout_id=? AND trainer_id IS NOT NULL
                  AND status IN ('CONFIRMED', 'COMPLETED'))
                """, Boolean.class, occurrenceId));
    }

    @Override public boolean lockForExecution(UUID occurrenceId) {
        return !jdbc.query("""
                SELECT id FROM fitness.appointments
                WHERE planned_workout_id=? AND trainer_id IS NOT NULL
                  AND status IN ('CONFIRMED', 'COMPLETED')
                ORDER BY id LIMIT 1 FOR SHARE
                """, (rs, row) -> rs.getObject(1, UUID.class), occurrenceId).isEmpty();
    }
}
