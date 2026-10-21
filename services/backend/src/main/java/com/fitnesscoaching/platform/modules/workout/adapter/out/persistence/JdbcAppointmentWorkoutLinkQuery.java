package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.AppointmentWorkoutLinkQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcAppointmentWorkoutLinkQuery implements AppointmentWorkoutLinkQuery {
    private final JdbcTemplate jdbc;

    public JdbcAppointmentWorkoutLinkQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public Optional<Occurrence> lockForAppointment(UUID id) {
        var rows = jdbc.query("""
                SELECT id, student_id, coaching_period_id, status::text,
                       supervision_requirement::text, planned_start_at, planned_end_at,
                       EXISTS(SELECT 1 FROM fitness.workout_session_logs l WHERE l.planned_workout_id=planned_workouts.id) AS sealed
                FROM fitness.planned_workouts WHERE id=? FOR UPDATE
                """, (rs, row) -> new Occurrence(rs.getObject("id", UUID.class),
                rs.getObject("student_id", UUID.class), rs.getObject("coaching_period_id", UUID.class),
                rs.getString("status"), rs.getString("supervision_requirement"),
                rs.getTimestamp("planned_start_at").toInstant(),
                Optional.ofNullable(rs.getTimestamp("planned_end_at")).map(Timestamp::toInstant).orElse(null),
                rs.getBoolean("sealed")), id);
        return rows.stream().findFirst();
    }
}
