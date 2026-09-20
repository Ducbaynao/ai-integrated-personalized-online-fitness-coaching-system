package com.fitnesscoaching.platform.modules.user.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.common.exception.UserNotFoundException;
import com.fitnesscoaching.platform.modules.user.application.model.UserProfileUpdateData;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsData;
import com.fitnesscoaching.platform.modules.user.application.port.out.UserUpdatePort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class UserPersistenceAdapter implements UserUpdatePort {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public UserPersistenceAdapter(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Override
    public void updateUserProfile(UUID userId, UserProfileUpdateData data) {
        StringBuilder sql = new StringBuilder("UPDATE fitness.users SET updated_at = ?");
        List<Object> params = new ArrayList<>();
        params.add(Timestamp.from(data.updatedAt()));

        if (data.displayName() != null) {
            sql.append(", display_name = ?");
            params.add(data.displayName());
        }
        if (data.phoneNumberSpecified()) {
            sql.append(", phone_number = ?");
            params.add(data.phoneNumber());
        }
        if (data.preferredLocale() != null) {
            sql.append(", preferred_locale = ?");
            params.add(data.preferredLocale());
        }
        if (data.timezone() != null) {
            sql.append(", timezone = ?");
            params.add(data.timezone());
        }

        sql.append(" WHERE id = ?");
        params.add(userId);

        int updated = jdbcTemplate.update(sql.toString(), params.toArray());
        if (updated == 0) {
            throw new UserNotFoundException("User not found for update: " + userId);
        }
    }

    @Override
    public void upsertUserSettings(UUID userId, UserSettingsData data) {
        String accessibilityJson = toJson(data.accessibilityPreferences());
        String privacyJson = toJson(data.privacyPreferences());
        Timestamp now = Timestamp.from(data.updatedAt());

        String sql = """
                INSERT INTO fitness.user_settings (
                    user_id,
                    week_starts_on,
                    measurement_system,
                    accessibility_preferences,
                    privacy_preferences,
                    created_at,
                    updated_at
                ) VALUES (
                    ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?
                )
                ON CONFLICT (user_id) DO UPDATE SET
                    week_starts_on = EXCLUDED.week_starts_on,
                    measurement_system = EXCLUDED.measurement_system,
                    accessibility_preferences = EXCLUDED.accessibility_preferences,
                    privacy_preferences = EXCLUDED.privacy_preferences,
                    updated_at = EXCLUDED.updated_at
                """;

        jdbcTemplate.update(
                sql,
                userId,
                data.weekStartsOn(),
                data.measurementSystem(),
                accessibilityJson,
                privacyJson,
                now,
                now
        );
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to serialize preferences map to JSON", e);
        }
    }
}
