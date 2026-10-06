package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V26CoachingPeriodSchedulingMigrationIntegrationTest {
    @Test
    void cleanInstallAllowsSchedulingAndShorteningButNotExtensionOrExpiredRewrite() {
        try (var db = database("coaching_v26_clean")) {
            db.start();
            var jdbc = new JdbcTemplate(source(db));
            migrate(source(db), "26");
            UUID period = openPeriod(jdbc);

            jdbc.update("UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '2 days' WHERE id=?", period);
            Timestamp firstEnd = end(jdbc, period);
            assertThat(firstEnd).isAfter(jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class));

            jdbc.update("UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '1 day' WHERE id=?", period);
            Timestamp shortenedEnd = end(jdbc, period);
            assertThat(shortenedEnd).isBefore(firstEnd);
            assertRejected(jdbc, period, "clock_timestamp()+interval '3 days'", shortenedEnd);
            assertRejected(jdbc, period, "clock_timestamp()-interval '1 hour'", shortenedEnd);
            assertRejected(jdbc, period, "NULL", shortenedEnd);

            jdbc.update("UPDATE fitness.coaching_periods SET ended_at=clock_timestamp() WHERE id=?", period);
            Timestamp closedEnd = end(jdbc, period);
            assertThat(closedEnd).isBefore(shortenedEnd);
            assertRejected(jdbc, period, "clock_timestamp()+interval '1 hour'", closedEnd);
        }
    }

    @Test
    void upgradesV25WithoutRewritingExistingPeriods() {
        try (var db = database("coaching_v26_upgrade")) {
            db.start();
            var source = source(db);
            var jdbc = new JdbcTemplate(source);
            migrate(source, "25");
            UUID finite = finitePeriod(jdbc);
            UUID open = openPeriod(jdbc);
            Timestamp originalEnd = end(jdbc, finite);
            assertRejected(jdbc, open, "clock_timestamp()+interval '1 day'", null);

            migrate(source, "26");
            assertThat(end(jdbc, finite)).isEqualTo(originalEnd);
            assertThat(end(jdbc, open)).isNull();
            jdbc.update("UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '1 hour' WHERE id=?", finite);
            assertThat(end(jdbc, finite)).isBefore(originalEnd);
            jdbc.update("UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '1 day' WHERE id=?", open);
            assertThat(end(jdbc, open)).isNotNull();
        }
    }

    private void assertRejected(JdbcTemplate jdbc, UUID period, String expression, Timestamp expectedEnd) {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE fitness.coaching_periods SET ended_at=" + expression + " WHERE id=?", period))
                .isInstanceOf(DataAccessException.class);
        assertThat(end(jdbc, period)).isEqualTo(expectedEnd);
    }

    private UUID openPeriod(JdbcTemplate jdbc) {
        return period(jdbc, false);
    }

    private UUID finitePeriod(JdbcTemplate jdbc) {
        return period(jdbc, true);
    }

    private UUID period(JdbcTemplate jdbc, boolean finite) {
        UUID student = UUID.randomUUID();
        UUID period = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'test-hash','Test Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, student, student + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        jdbc.update("""
                INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,ended_at,created_by)
                VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',
                        CASE WHEN ? THEN clock_timestamp()+interval '2 days' ELSE NULL END,?)
                """, period, student, finite, student);
        return period;
    }

    private Timestamp end(JdbcTemplate jdbc, UUID period) {
        return jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?", Timestamp.class, period);
    }

    private PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(name).withUsername("test_user").withPassword("test_pass");
    }

    private DriverManagerDataSource source(PostgreSQLContainer<?> db) {
        return new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }

    private void migrate(DriverManagerDataSource source, String target) {
        Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                .target(target).load().migrate();
    }
}
