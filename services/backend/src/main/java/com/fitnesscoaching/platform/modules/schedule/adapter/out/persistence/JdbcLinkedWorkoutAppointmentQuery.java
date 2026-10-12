package com.fitnesscoaching.platform.modules.schedule.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.LinkedWorkoutAppointmentQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public class JdbcLinkedWorkoutAppointmentQuery implements LinkedWorkoutAppointmentQuery {
    private final JdbcTemplate jdbc;
    public JdbcLinkedWorkoutAppointmentQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public boolean hasAnyLinkedForMutation(UUID occurrenceId) {
        return !jdbc.query("SELECT id FROM fitness.appointments WHERE planned_workout_id=? ORDER BY id FOR SHARE",
                (rs, row) -> rs.getObject(1, UUID.class), occurrenceId).isEmpty();
    }

    @Override public boolean hasAnyLinked(UUID occurrenceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.appointments WHERE planned_workout_id=?)
                """, Boolean.class, occurrenceId));
    }
}
