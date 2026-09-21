package com.fitnesscoaching.platform.modules.user.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import com.fitnesscoaching.platform.modules.user.application.model.UserCapabilitiesView;
import com.fitnesscoaching.platform.modules.user.application.model.UserSettingsView;
import com.fitnesscoaching.platform.modules.user.application.port.in.CurrentUserQuery;
import com.fitnesscoaching.platform.modules.user.application.port.out.CoachingAuthorityQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class CurrentUserReadAdapter implements CurrentUserQuery {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final CoachingAuthorityQuery coachingAuthorityQuery;

    public CurrentUserReadAdapter(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            CoachingAuthorityQuery coachingAuthorityQuery
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.coachingAuthorityQuery = coachingAuthorityQuery;
    }

    @Override
    public Optional<CurrentUserView> findCurrentUserById(UUID userId) {
        String userSql = """
                SELECT id, email, display_name, status, preferred_locale, timezone,
                       email_verified_at, created_at, phone_number
                FROM fitness.users
                WHERE id = ?
                """;

        List<BaseUserData> userResults = jdbcTemplate.query(
                userSql,
                (rs, rowNum) -> {
                    UUID id = (UUID) rs.getObject("id");
                    String email = rs.getString("email");
                    String displayName = rs.getString("display_name");
                    String statusStr = rs.getString("status");
                    AccountStatus status = statusStr != null ? AccountStatus.valueOf(statusStr) : null;
                    String preferredLocale = rs.getString("preferred_locale");
                    String timezone = rs.getString("timezone");
                    Timestamp verifiedTs = rs.getTimestamp("email_verified_at");
                    Instant emailVerifiedAt = verifiedTs != null ? verifiedTs.toInstant() : null;
                    Timestamp createdTs = rs.getTimestamp("created_at");
                    Instant createdAt = createdTs != null ? createdTs.toInstant() : null;
                    String phoneNumber = rs.getString("phone_number");

                    return new BaseUserData(
                            id,
                            email,
                            displayName,
                            status,
                            preferredLocale,
                            timezone,
                            emailVerifiedAt,
                            createdAt,
                            phoneNumber
                    );
                },
                userId
        );

        if (userResults.isEmpty()) {
            return Optional.empty();
        }

        BaseUserData user = userResults.get(0);

        List<String> roles = jdbcTemplate.query(
                """
                SELECT r.code
                FROM fitness.roles r
                JOIN fitness.user_roles ur ON ur.role_id = r.id
                WHERE ur.user_id = ? AND ur.revoked_at IS NULL
                ORDER BY r.code ASC
                """,
                (rs, rowNum) -> rs.getString("code"),
                userId
        );

        UserSettingsView settings = jdbcTemplate.query(
                """
                SELECT week_starts_on, measurement_system, accessibility_preferences, privacy_preferences
                FROM fitness.user_settings
                WHERE user_id = ?
                """,
                rs -> {
                    if (rs.next()) {
                        int weekStartsOn = rs.getInt("week_starts_on");
                        String measurementSystem = rs.getString("measurement_system");
                        Map<String, Object> accessibility = parseJsonMap(
                                rs.getString("accessibility_preferences"), "accessibility_preferences");
                        Map<String, Object> privacy = parseJsonMap(
                                rs.getString("privacy_preferences"), "privacy_preferences");
                        return new UserSettingsView(
                                weekStartsOn,
                                measurementSystem != null ? measurementSystem : "METRIC",
                                accessibility,
                                privacy
                        );
                    }
                    return UserSettingsView.defaults();
                },
                userId
        );
        if (settings == null) {
            settings = UserSettingsView.defaults();
        }

        Boolean hasStudent = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.student_profiles WHERE user_id = ?)",
                Boolean.class,
                userId
        );
        Boolean hasTrainer = jdbcTemplate.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM fitness.trainer_profiles WHERE user_id = ?)",
                Boolean.class,
                userId
        );

        /*
         * Coaching authority is derived from the single Trainer Eligibility Policy.
         * canCoach is true only when account is ACTIVE, user has active TRAINER role,
         * trainer profile exists and is active, verificationStatus is VERIFIED,
         * and activityStatus is ACTIVE.
         */
        boolean canCoach = coachingAuthorityQuery.canCoach(userId);

        UserCapabilitiesView capabilities = new UserCapabilitiesView(
                Boolean.TRUE.equals(hasStudent),
                Boolean.TRUE.equals(hasTrainer),
                canCoach
        );

        return Optional.of(new CurrentUserView(
                user.id(),
                user.email(),
                user.displayName(),
                user.status(),
                user.preferredLocale(),
                user.timezone(),
                user.emailVerifiedAt(),
                user.createdAt(),
                user.phoneNumber(),
                roles,
                capabilities,
                settings
        ));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJsonMap(String json, String fieldName) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        String trimmed = json.trim();
        if ("{}".equals(trimmed)) {
            return Collections.emptyMap();
        }
        try {
            tools.jackson.databind.JsonNode node = objectMapper.readTree(trimmed);
            if (!node.isObject()) {
                throw new IllegalStateException("Persisted settings field '" + fieldName + "' is not a JSON object");
            }
            if (node.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, Object> map = objectMapper.treeToValue(node, Map.class);
            return map != null ? map : Collections.emptyMap();
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to parse persisted settings JSON field: " + fieldName, e);
        }
    }

    private record BaseUserData(
            UUID id,
            String email,
            String displayName,
            AccountStatus status,
            String preferredLocale,
            String timezone,
            Instant emailVerifiedAt,
            Instant createdAt,
            String phoneNumber
    ) {}
}
