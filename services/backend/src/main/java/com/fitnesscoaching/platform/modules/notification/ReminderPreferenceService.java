package com.fitnesscoaching.platform.modules.notification;

import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** In-app reminder consent is independent of push consent and the always-on inbox. */
@Service
public class ReminderPreferenceService {
    private final JdbcTemplate jdbc;
    private final UserAccountStatusQuery accounts;
    public ReminderPreferenceService(JdbcTemplate jdbc, UserAccountStatusQuery accounts) {
        this.jdbc = jdbc; this.accounts = accounts;
    }

    public record Preference(String type, boolean enabled) {}

    @Transactional(readOnly = true)
    public List<Preference> list(UUID actor) {
        requireActive(actor);
        return jdbc.query("""
                SELECT event_type,enabled FROM fitness.notification_preferences
                WHERE user_id=? AND channel='IN_APP' AND event_type IN
                  ('APPOINTMENT_REMINDER','WORKOUT_REMINDER') ORDER BY event_type
                """, (rs, n) -> new Preference(rs.getString(1), rs.getBoolean(2)), actor);
    }

    @Transactional
    public Preference set(UUID actor, String type, boolean enabled) {
        requireActive(actor);
        if (!List.of("APPOINTMENT_REMINDER", "WORKOUT_REMINDER").contains(type))
            throw new ChatFailure("REMINDER_PREFERENCE_INVALID", 400);
        jdbc.update("""
                INSERT INTO fitness.notification_preferences(user_id,event_type,channel,enabled)
                VALUES (?,?,'IN_APP',?) ON CONFLICT(user_id,event_type,channel)
                DO UPDATE SET enabled=EXCLUDED.enabled,updated_at=clock_timestamp()
                """, actor, type, enabled);
        return new Preference(type, enabled);
    }

    boolean enabled(UUID actor, String type) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.notification_preferences
                WHERE user_id=? AND event_type=? AND channel='IN_APP' AND enabled=true)
                """, Boolean.class, actor, type));
    }

    private void requireActive(UUID actor) {
        if (actor == null || accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE)
            throw new ChatFailure("CHAT_NOTIFICATION_NOT_FOUND", 404);
    }
}
