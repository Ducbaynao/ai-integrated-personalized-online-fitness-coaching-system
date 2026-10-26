package com.fitnesscoaching.platform.modules.notification;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

/** Enqueues only in the transaction that commits the in-app domain notification. */
@Component
public class PushQueueWriter {
    private final JdbcTemplate jdbc;
    public PushQueueWriter(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Transactional(propagation = Propagation.MANDATORY)
    public void queue(UUID notificationId, UUID recipient, String eventType) {
        String category = category(eventType);
        if (category == null) return; // No push for unapproved/unknown producers.
        Instant databaseNow = jdbc.queryForObject("SELECT clock_timestamp()",
                (rs, row) -> rs.getTimestamp(1).toInstant());
        List<DevicePreference> devices = jdbc.query("""
                SELECT d.id,p.timezone,p.quiet_hours_start,p.quiet_hours_end
                FROM fitness.push_devices d
                JOIN fitness.notification_preferences p ON p.user_id=d.user_id
                  AND p.event_type=? AND p.channel='PUSH' AND p.enabled=true
                WHERE d.user_id=? AND d.active=true
                """, (rs, row) -> new DevicePreference(rs.getObject(1, UUID.class), rs.getString(2),
                rs.getTime(3) == null ? null : rs.getTime(3).toLocalTime(),
                rs.getTime(4) == null ? null : rs.getTime(4).toLocalTime()), category, recipient);
        int inserted = 0;
        for (DevicePreference device : devices) {
            Instant due = PushQuietHours.nextAllowed(databaseNow, device.timezone(),
                    device.quietStart(), device.quietEnd());
            inserted += jdbc.update("""
                    INSERT INTO fitness.push_delivery_attempts(notification_id,device_id,category,next_attempt_at)
                    VALUES (?,?,?,?) ON CONFLICT DO NOTHING
                    """, notificationId, device.id(), category, Timestamp.from(due));
        }
        if (inserted > 0) jdbc.update("""
                INSERT INTO fitness.notification_deliveries(notification_id,channel,status)
                VALUES (?,'PUSH','PENDING') ON CONFLICT DO NOTHING
                """, notificationId);
    }

    private record DevicePreference(UUID id, String timezone, LocalTime quietStart, LocalTime quietEnd) {}

    static String category(String eventType) {
        if (eventType == null) return null;
        if (eventType.equals("CHAT_MESSAGE")) return "CHAT";
        if (eventType.startsWith("ATTENTION_") || eventType.startsWith("REVIEW_TASK_")) return "ATTENTION_REVIEW";
        if (eventType.startsWith("APPOINTMENT_")) return "APPOINTMENT";
        if (eventType.startsWith("WORKOUT_") || eventType.startsWith("LINKED_WORKOUT_")) return "WORKOUT";
        if (eventType.startsWith("FITNESS_GOAL_")) return "GOAL";
        if (eventType.startsWith("NUTRITION_STRATEGY_")) return "NUTRITION";
        if (eventType.startsWith("COACHING_REQUEST_") || eventType.startsWith("COACHING_RESUME_")) return "COACHING";
        return null;
    }
}
