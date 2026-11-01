package com.fitnesscoaching.platform.modules.schedule.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.AppointmentReminderQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Component
public class JdbcAppointmentReminderQuery implements AppointmentReminderQuery {
    private final JdbcTemplate jdbc;
    public JdbcAppointmentReminderQuery(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final String SELECT = """
            SELECT id,version,student_id,trainer_id,coaching_period_id,start_at
            FROM fitness.appointments WHERE guarded_v2 AND status='CONFIRMED'
              AND trainer_id IS NOT NULL AND coaching_period_id IS NOT NULL
            """;

    @Override public List<Slot> startingBetween(Instant after, Instant through) {
        return jdbc.query(SELECT + " AND start_at>? AND start_at<=? ORDER BY start_at,id",
                (rs, n) -> new Slot(rs.getObject(1, UUID.class), rs.getLong(2),
                        rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                        rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant()),
                Timestamp.from(after), Timestamp.from(through));
    }

    @Override public Optional<Slot> confirmed(UUID id) {
        return jdbc.query(SELECT + " AND id=? FOR SHARE", (rs, n) -> new Slot(rs.getObject(1, UUID.class),
                rs.getLong(2), rs.getObject(3, UUID.class), rs.getObject(4, UUID.class),
                rs.getObject(5, UUID.class), rs.getTimestamp(6).toInstant()), id).stream().findFirst();
    }
}
