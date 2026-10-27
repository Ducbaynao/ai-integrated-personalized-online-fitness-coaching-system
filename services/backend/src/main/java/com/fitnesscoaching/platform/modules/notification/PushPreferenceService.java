package com.fitnesscoaching.platform.modules.notification;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.DateTimeException;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.time.Instant;

@Service
public class PushPreferenceService implements PushPreferenceQuery {
    private final JdbcTemplate jdbc;
    private final UserAccountStatusQuery accounts;
    private final InAppNotificationService notices;
    public PushPreferenceService(JdbcTemplate jdbc, UserAccountStatusQuery accounts,
                                 InAppNotificationService notices) {
        this.jdbc = jdbc; this.accounts = accounts; this.notices = notices;
    }

    public record Preference(String eventType, boolean enabled, String timezone,
                             String quietHoursStart, String quietHoursEnd) {}
    public record Delivery(String status, Instant attemptedAt, Instant providerAcceptedAt,
                           Instant deviceAcknowledgedAt) {}

    @Transactional
    public List<Delivery> deliveries(UUID actor, UUID notificationId) {
        requireAccount(actor);
        if (!notices.canReadNotice(actor, notificationId))
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
        return jdbc.query("""
                SELECT CASE a.status WHEN 'SENDING' THEN 'QUEUED'
                  WHEN 'CHECKING_RECEIPT' THEN 'TICKET_ACCEPTED' ELSE a.status END,
                  a.attempted_at,a.provider_accepted_at,a.device_acknowledged_at
                FROM fitness.push_delivery_attempts a JOIN fitness.notifications n ON n.id=a.notification_id
                WHERE a.notification_id=? AND n.recipient_user_id=? ORDER BY a.id
                """, (rs, row) -> new Delivery(rs.getString(1),
                rs.getTimestamp(2) == null ? null : rs.getTimestamp(2).toInstant(),
                rs.getTimestamp(3) == null ? null : rs.getTimestamp(3).toInstant(),
                rs.getTimestamp(4) == null ? null : rs.getTimestamp(4).toInstant()), notificationId, actor);
    }

    @Transactional(readOnly = true)
    public List<Preference> list(UUID actor) {
        requireAccount(actor);
        return jdbc.query("""
                SELECT event_type,enabled,timezone,quiet_hours_start,quiet_hours_end
                FROM fitness.notification_preferences
                WHERE user_id=? AND channel='PUSH' ORDER BY event_type
                """, (rs, row) -> new Preference(rs.getString(1), rs.getBoolean(2), rs.getString(3),
                rs.getTime(4) == null ? null : rs.getTime(4).toLocalTime().toString(),
                rs.getTime(5) == null ? null : rs.getTime(5).toLocalTime().toString()), actor);
    }

    @Override
    public List<Preference> preferencesForExport(UUID actor) { return list(actor); }

    @Transactional
    public Preference set(UUID actor, String eventType, boolean enabled, String timezone,
                          String quietStart, String quietEnd) {
        requireAccount(actor);
        if (eventType == null || !List.of("CHAT", "COACHING", "WORKOUT", "GOAL", "NUTRITION",
                "ATTENTION_REVIEW", "APPOINTMENT").contains(eventType))
            throw new ChatFailure("PUSH_PREFERENCE_INVALID", 400);
        // A user must explicitly confirm the IANA timezone; no device/profile fallback.
        try {
            if (!ZoneId.getAvailableZoneIds().contains(timezone))
                throw new ChatFailure("PUSH_TIMEZONE_INVALID", 400);
            ZoneId.of(timezone);
        }
        catch (DateTimeException | NullPointerException error) { throw new ChatFailure("PUSH_TIMEZONE_INVALID", 400); }
        if ((quietStart == null) != (quietEnd == null)) throw new ChatFailure("PUSH_QUIET_HOURS_INVALID", 400);
        LocalTime start = null, end = null;
        try {
            if (quietStart != null) { start = LocalTime.parse(quietStart); end = LocalTime.parse(quietEnd); }
        } catch (DateTimeException error) { throw new ChatFailure("PUSH_QUIET_HOURS_INVALID", 400); }
        if (start != null && start.equals(end)) throw new ChatFailure("PUSH_QUIET_HOURS_INVALID", 400);
        jdbc.update("""
                INSERT INTO fitness.notification_preferences
                  (user_id,event_type,channel,enabled,quiet_hours_start,quiet_hours_end,timezone)
                VALUES (?,?,'PUSH',?,?,?,?)
                ON CONFLICT (user_id,event_type,channel) DO UPDATE SET
                  enabled=EXCLUDED.enabled,quiet_hours_start=EXCLUDED.quiet_hours_start,
                  quiet_hours_end=EXCLUDED.quiet_hours_end,timezone=EXCLUDED.timezone,
                  updated_at=clock_timestamp()
                """, actor, eventType, enabled, start, end, timezone);
        if (!enabled) jdbc.update("""
                UPDATE fitness.push_delivery_attempts a SET status='SKIPPED',
                  claim_token=NULL,lease_expires_at=NULL
                FROM fitness.notifications n WHERE a.notification_id=n.id AND n.recipient_user_id=?
                  AND a.category=? AND a.status IN ('QUEUED','SENDING')
                """, actor, eventType);
        else {
            Instant now = jdbc.queryForObject("SELECT clock_timestamp()",
                    (rs, row) -> rs.getTimestamp(1).toInstant());
            Instant due = PushQuietHours.nextAllowed(now, timezone, start, end);
            jdbc.update("""
                    UPDATE fitness.push_delivery_attempts a SET next_attempt_at=?
                    FROM fitness.notifications n WHERE a.notification_id=n.id AND n.recipient_user_id=?
                      AND a.category=? AND a.status='QUEUED'
                    """, Timestamp.from(due), actor, eventType);
        }
        return new Preference(eventType, enabled, timezone,
                start == null ? null : start.toString(), end == null ? null : end.toString());
    }

    @Transactional
    public void register(UUID actor, UUID deviceKey, String token) {
        requireAccount(actor);
        if (deviceKey == null || token == null || token.length() > 255
                || !(token.matches("ExpoPushToken\\[[A-Za-z0-9_-]+]")
                    || token.matches("ExponentPushToken\\[[A-Za-z0-9_-]+]")))
            throw new ChatFailure("PUSH_DEVICE_INVALID", 400);
        try {
            jdbc.update("""
                    INSERT INTO fitness.push_devices(user_id,device_key,expo_token,active)
                    VALUES (?,?,?,true)
                    ON CONFLICT (user_id,device_key) DO UPDATE SET
                      expo_token=EXCLUDED.expo_token,active=true,registered_at=clock_timestamp()
                    """, actor, deviceKey, token);
        } catch (DuplicateKeyException conflict) {
            // Never silently transfer a token from a different account.
            throw new ChatFailure("PUSH_DEVICE_CONFLICT", 409);
        }
    }

    @Transactional
    public void unregister(UUID actor, UUID deviceKey) {
        requireAccount(actor);
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts a SET status='SKIPPED',claim_token=NULL,lease_expires_at=NULL
                FROM fitness.push_devices d WHERE a.device_id=d.id AND d.user_id=? AND d.device_key=?
                  AND a.status IN ('QUEUED','SENDING')
                """, actor, deviceKey);
        jdbc.update("UPDATE fitness.push_devices SET active=false WHERE user_id=? AND device_key=?", actor, deviceKey);
    }

    @Transactional
    public void deviceReceived(UUID actor, UUID deviceKey, UUID notificationId) {
        requireAccount(actor);
        if (!notices.canReadNotice(actor, notificationId))
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
        int changed = jdbc.update("""
                UPDATE fitness.push_delivery_attempts a SET status='DEVICE_ACKNOWLEDGED',
                    device_acknowledged_at=COALESCE(device_acknowledged_at,clock_timestamp()),
                    claim_token=NULL,lease_expires_at=NULL
                FROM fitness.push_devices d,fitness.notifications n
                WHERE a.device_id=d.id AND a.notification_id=n.id
                  AND d.user_id=? AND d.device_key=? AND d.active=true
                  AND n.id=? AND n.recipient_user_id=?
                  AND a.status IN ('TICKET_ACCEPTED','CHECKING_RECEIPT','PROVIDER_ACCEPTED','DEVICE_ACKNOWLEDGED')
                """, actor, deviceKey, notificationId, actor);
        if (changed == 0) throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
    }

    private void requireAccount(UUID actor) {
        if (actor == null || accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE)
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
    }
}
