package com.fitnesscoaching.platform.modules.coaching.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.HumanReviewPeriodQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class JdbcHumanReviewPeriodQuery implements HumanReviewPeriodQuery {
    private final JdbcTemplate jdbc;
    public JdbcHumanReviewPeriodQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override public List<Period> activePeriods() {
        return jdbc.query("""
                SELECT p.id,p.student_id,p.trainer_id,p.started_at
                FROM fitness.coaching_periods p JOIN fitness.coaching_relationships r
                  ON r.id=p.coaching_relationship_id
                WHERE p.mode='HUMAN_COACH' AND p.started_at<=clock_timestamp()
                  AND (p.ended_at IS NULL OR p.ended_at>clock_timestamp()) AND r.status='ACTIVE'
                ORDER BY p.student_id,p.started_at
                """, (rs,n) -> new Period(rs.getObject(1,java.util.UUID.class),
                rs.getObject(2,java.util.UUID.class),rs.getObject(3,java.util.UUID.class),
                rs.getTimestamp(4).toInstant()));
    }
}
