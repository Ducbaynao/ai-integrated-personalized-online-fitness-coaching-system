package com.fitnesscoaching.platform.modules.workout.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class JdbcWorkoutCompletionEvidenceQuery implements WorkoutCompletionEvidenceQuery {
    private final JdbcTemplate jdbc;

    public JdbcWorkoutCompletionEvidenceQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<Completion> completed() {
        return jdbc.query("""
                SELECT id,student_id,version,performed_start_at,performed_end_at
                FROM fitness.workout_session_logs WHERE status='COMPLETED'
                ORDER BY student_id,COALESCE(performed_end_at,performed_start_at),id
                """, (rs,n) -> new Completion(rs.getObject(1,java.util.UUID.class),
                rs.getObject(2,java.util.UUID.class),rs.getLong(3),rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()));
    }

    @Override public List<Completion> completedForStudent(UUID studentId) {
        return jdbc.query("""
                SELECT id,student_id,version,performed_start_at,performed_end_at
                FROM fitness.workout_session_logs
                WHERE student_id=? AND status='COMPLETED'
                ORDER BY COALESCE(performed_end_at,performed_start_at),id
                """, (rs,n) -> new Completion(rs.getObject(1,UUID.class),
                rs.getObject(2,UUID.class),rs.getLong(3),rs.getTimestamp(4).toInstant(),
                rs.getTimestamp(5)==null?null:rs.getTimestamp(5).toInstant()),studentId);
    }
}
