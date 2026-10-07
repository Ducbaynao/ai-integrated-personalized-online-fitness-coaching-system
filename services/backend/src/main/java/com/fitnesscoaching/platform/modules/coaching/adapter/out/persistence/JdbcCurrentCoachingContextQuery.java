package com.fitnesscoaching.platform.modules.coaching.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcCurrentCoachingContextQuery implements CurrentCoachingContextQuery {
    private final JdbcTemplate jdbc;
    public JdbcCurrentCoachingContextQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Optional<CurrentCoachingContext> findEffectiveContext(UUID studentId, boolean lock) {
        String lockClause = lock ? " FOR SHARE" : "";
        List<CurrentCoachingContext> rows = jdbc.query("""
                SELECT id, mode::text, coaching_relationship_id, trainer_id, started_at, ended_at
                FROM fitness.coaching_periods
                WHERE student_id = ? AND started_at <= clock_timestamp()
                  AND (ended_at IS NULL OR ended_at > clock_timestamp())
                ORDER BY started_at DESC LIMIT 1
                """ + lockClause, (rs, row) -> new CurrentCoachingContext(
                rs.getObject("id", UUID.class), Mode.valueOf(rs.getString("mode")),
                rs.getObject("coaching_relationship_id", UUID.class), rs.getObject("trainer_id", UUID.class),
                rs.getTimestamp("started_at").toInstant(),
                Optional.ofNullable(rs.getTimestamp("ended_at")).map(Timestamp::toInstant).orElse(null)), studentId);
        return rows.stream().findFirst();
    }
}
