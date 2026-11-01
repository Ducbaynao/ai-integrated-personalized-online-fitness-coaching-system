package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutReminderQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcWorkoutReminderQuery implements WorkoutReminderQuery {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutReminderQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SELECT = """
            SELECT id,version,student_id,planned_start_at FROM fitness.planned_workouts
            WHERE status='SCHEDULED' AND supervision_requirement<>'COACH_REQUIRED'
              AND execution_started_at IS NULL
            """;

    @Override public List<Slot> startingBetween(Instant after, Instant through) {
        return jdbc.query(SELECT + " AND planned_start_at>? AND planned_start_at<=? ORDER BY planned_start_at,id",
                (rs, n) -> new Slot(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant()),
                Timestamp.from(after), Timestamp.from(through));
    }

    @Override public Optional<Slot> scheduled(UUID id) {
        return jdbc.query(SELECT + " AND id=? FOR SHARE", (rs, n) -> new Slot(rs.getObject(1, UUID.class),
                rs.getLong(2), rs.getObject(3, UUID.class), rs.getTimestamp(4).toInstant()), id)
                .stream().findFirst();
    }
}
