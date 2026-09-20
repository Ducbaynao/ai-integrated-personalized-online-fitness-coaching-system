package com.fitnesscoaching.platform.modules.user.adapter.out.persistence;

import tools.jackson.databind.ObjectMapper;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.user.application.model.CurrentUserView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.jdbc.core.RowMapper;

import org.mockito.quality.Strictness;
import org.mockito.junit.jupiter.MockitoSettings;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CurrentUserReadAdapterUnitTest {

    @Mock
    private JdbcTemplate jdbcTemplate;

    private ObjectMapper objectMapper;
    private CurrentUserReadAdapter adapter;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        adapter = new CurrentUserReadAdapter(jdbcTemplate, objectMapper);
    }

    @Test
    @DisplayName("Malformed persisted JSON in user_settings throws IllegalStateException and does not leak raw payload")
    void malformedPersistedJsonThrowsExceptionWithoutExposingRawJson() {
        UUID userId = UUID.randomUUID();
        mockUserAccountQuery(userId);

        when(jdbcTemplate.query(
                anyString(),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getInt("week_starts_on")).thenReturn(1);
            when(rs.getString("measurement_system")).thenReturn("METRIC");
            when(rs.getString("accessibility_preferences")).thenReturn("{invalid-json-payload-secret-12345");
            return extractor.extractData(rs);
        });

        assertThatThrownBy(() -> adapter.findCurrentUserById(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("accessibility_preferences")
                .hasMessageNotContaining("secret-12345");
    }

    @Test
    @DisplayName("Non-object persisted JSON in user_settings throws IllegalStateException and does not leak raw payload")
    void nonObjectPersistedJsonThrowsExceptionWithoutExposingRawJson() {
        UUID userId = UUID.randomUUID();
        mockUserAccountQuery(userId);

        when(jdbcTemplate.query(
                anyString(),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getInt("week_starts_on")).thenReturn(1);
            when(rs.getString("measurement_system")).thenReturn("METRIC");
            when(rs.getString("accessibility_preferences")).thenReturn("{}");
            when(rs.getString("privacy_preferences")).thenReturn("[1, 2, 3, \"sensitive-private-token\"]");
            return extractor.extractData(rs);
        });

        assertThatThrownBy(() -> adapter.findCurrentUserById(userId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("privacy_preferences")
                .hasMessageNotContaining("sensitive-private-token");
    }

    @Test
    @DisplayName("Valid JSON objects and empty/null/blank settings map cleanly")
    void validJsonAndNullOrBlankMapsCleanly() {
        UUID userId = UUID.randomUUID();
        mockUserAccountQuery(userId);

        when(jdbcTemplate.query(
                anyString(),
                any(ResultSetExtractor.class),
                eq(userId)
        )).thenAnswer(invocation -> {
            ResultSetExtractor<?> extractor = invocation.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.next()).thenReturn(true);
            when(rs.getInt("week_starts_on")).thenReturn(0);
            when(rs.getString("measurement_system")).thenReturn("IMPERIAL");
            when(rs.getString("accessibility_preferences")).thenReturn("{\"highContrast\": true}");
            when(rs.getString("privacy_preferences")).thenReturn("   ");
            return extractor.extractData(rs);
        });

        when(jdbcTemplate.queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM fitness.student_profiles WHERE user_id = ?)"),
                eq(Boolean.class),
                eq(userId)
        )).thenReturn(true);

        when(jdbcTemplate.queryForObject(
                eq("SELECT EXISTS(SELECT 1 FROM fitness.trainer_profiles WHERE user_id = ?)"),
                eq(Boolean.class),
                eq(userId)
        )).thenReturn(false);

        Optional<CurrentUserView> result = adapter.findCurrentUserById(userId);
        assertThat(result).isPresent();
        CurrentUserView view = result.get();
        assertThat(view.settings().weekStartsOn()).isEqualTo(0);
        assertThat(view.settings().measurementSystem()).isEqualTo("IMPERIAL");
        assertThat(view.settings().accessibilityPreferences()).containsEntry("highContrast", true);
        assertThat(view.settings().privacyPreferences()).isEmpty();
        assertThat(view.capabilities().hasStudentProfile()).isTrue();
        assertThat(view.capabilities().hasTrainerProfile()).isFalse();
        assertThat(view.capabilities().canCoach()).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void mockUserAccountQuery(UUID userId) {
        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("FROM fitness.users"),
                any(RowMapper.class),
                eq(userId)
        )).thenAnswer(inv -> {
            RowMapper<?> mapper = inv.getArgument(1);
            java.sql.ResultSet rs = org.mockito.Mockito.mock(java.sql.ResultSet.class);
            when(rs.getObject("id")).thenReturn(userId);
            when(rs.getString("email")).thenReturn("user@example.com");
            when(rs.getString("display_name")).thenReturn("Test User");
            when(rs.getString("status")).thenReturn("ACTIVE");
            when(rs.getString("preferred_locale")).thenReturn("vi-VN");
            when(rs.getString("timezone")).thenReturn("Asia/Ho_Chi_Minh");
            when(rs.getTimestamp("email_verified_at")).thenReturn(Timestamp.from(Instant.now()));
            when(rs.getTimestamp("created_at")).thenReturn(Timestamp.from(Instant.now()));
            when(rs.getString("phone_number")).thenReturn(null);
            return List.of(mapper.mapRow(rs, 0));
        });

        when(jdbcTemplate.query(
                org.mockito.ArgumentMatchers.contains("FROM fitness.roles"),
                any(RowMapper.class),
                eq(userId)
        )).thenReturn(List.of("STUDENT"));
    }
}
