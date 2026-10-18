package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutMissedEvidenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcWorkoutMissedEvidenceQuery implements WorkoutMissedEvidenceQuery {
    private final JdbcTemplate jdbc;
    public JdbcWorkoutMissedEvidenceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String FIELDS = """
            SELECT pw.id,pw.student_id,pw.version,pw.planned_start_at,pw.planned_end_at,
              (pw.status='SCHEDULED' AND pw.execution_started_at IS NULL
               AND NOT EXISTS (SELECT 1 FROM fitness.workout_session_logs log
                               WHERE log.planned_workout_id=pw.id)) AS eligible
            FROM fitness.planned_workouts pw
            """;
    @Override public List<Evidence> candidates(Instant cutoff) {
        return jdbc.query(FIELDS + " WHERE pw.planned_end_at IS NOT NULL AND pw.planned_end_at<? "
                + "AND pw.status='SCHEDULED' ORDER BY pw.planned_end_at,pw.id",
                (rs,n) -> map(rs), Timestamp.from(cutoff));
    }
    @Override public Optional<Evidence> find(UUID occurrenceId) {
        return jdbc.query(FIELDS + " WHERE pw.id=?", (rs,n) -> map(rs), occurrenceId)
                .stream().findFirst();
    }
    private static Evidence map(ResultSet rs) throws SQLException {
        Timestamp end = rs.getTimestamp(5);
        return new Evidence(rs.getObject(1,UUID.class),rs.getObject(2,UUID.class),rs.getLong(3),
                rs.getTimestamp(4).toInstant(),end == null ? null : end.toInstant(),rs.getBoolean(6));
    }
}
