package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V25CoachingLifecycleReviewMigrationIntegrationTest {
    @Test
    void cleanInstallRejectsActivationWithInactiveTrainerProfile() {
        try (var db = database("coaching_v25_clean")) {
            db.start();
            var source = source(db);
            var jdbc = new JdbcTemplate(source);
            migrate(source, "25");
            UUID student = person(jdbc, "STUDENT"), trainer = person(jdbc, "TRAINER");
            UUID relationship = pending(jdbc, student, trainer);
            jdbc.update("UPDATE fitness.trainer_profiles SET is_active=false WHERE user_id=?", trainer);

            assertThatThrownBy(() -> activate(jdbc, relationship)).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT status FROM fitness.coaching_relationships WHERE id=?",
                    String.class, relationship)).isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_periods WHERE student_id=?",
                    Integer.class, student)).isZero();
        }
    }

    @Test
    void upgradesV24WithoutRewritingRowsAndOnlyAllowsImmediatePeriodClose() throws Exception {
        try (var db = database("coaching_v25_upgrade")) {
            db.start();
            var source = source(db);
            var jdbc = new JdbcTemplate(source);
            migrate(source, "24");
            UUID student = person(jdbc, "STUDENT"), trainer = person(jdbc, "TRAINER");
            UUID legacyActive = pending(jdbc, student, trainer);
            activate(jdbc, legacyActive);
            jdbc.update("UPDATE fitness.trainer_profiles SET is_active=false WHERE user_id=?", trainer);
            UUID finiteStudent = person(jdbc, "STUDENT");
            UUID finite = period(jdbc, finiteStudent, true);
            Timestamp originalEnd = jdbc.queryForObject(
                    "SELECT ended_at FROM fitness.coaching_periods WHERE id=?", Timestamp.class, finite);

            migrate(source, "25");
            assertThat(jdbc.queryForObject("SELECT status FROM fitness.coaching_relationships WHERE id=?",
                    String.class, legacyActive)).isEqualTo("ACTIVE");
            assertThat(jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                    Timestamp.class, finite)).isEqualTo(originalEnd);
            UUID nextStudent = person(jdbc, "STUDENT");
            UUID nextPending = pending(jdbc, nextStudent, trainer);
            assertThatThrownBy(() -> activate(jdbc, nextPending)).isInstanceOf(DataAccessException.class);

            assertRejectedEnd(jdbc, finite, "clock_timestamp()+interval '2 days'", originalEnd);
            assertRejectedEnd(jdbc, finite, "clock_timestamp()+interval '1 hour'", originalEnd);
            assertRejectedEnd(jdbc, finite, "clock_timestamp()-interval '1 hour'", originalEnd);
            UUID open = period(jdbc, person(jdbc, "STUDENT"), false);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '1 hour' WHERE id=?", open))
                    .isInstanceOf(DataAccessException.class);

            try (Connection connection = source.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement close = connection.prepareStatement(
                        "UPDATE fitness.coaching_periods SET ended_at=clock_timestamp() WHERE id=?")) {
                    close.setObject(1, finite);
                    assertThat(close.executeUpdate()).isEqualTo(1);
                }
                try (PreparedStatement reschedule = connection.prepareStatement(
                        "UPDATE fitness.coaching_periods SET ended_at=clock_timestamp()+interval '1 hour' WHERE id=?")) {
                    reschedule.setObject(1, finite);
                    assertThatThrownBy(reschedule::executeUpdate).isInstanceOf(Exception.class);
                } finally {
                    connection.rollback();
                }
            }
            assertThat(jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                    Timestamp.class, finite)).isEqualTo(originalEnd);

            jdbc.update("UPDATE fitness.coaching_periods SET ended_at=clock_timestamp() WHERE id=?", finite);
            Timestamp closedAt = jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                    Timestamp.class, finite);
            assertThat(closedAt).isBefore(originalEnd);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.coaching_periods SET ended_at=clock_timestamp() WHERE id=?", finite))
                    .isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                    Timestamp.class, finite)).isEqualTo(closedAt);
        }
    }

    private void assertRejectedEnd(JdbcTemplate jdbc, UUID periodId, String expression, Timestamp original) {
        assertThatThrownBy(() -> jdbc.update(
                "UPDATE fitness.coaching_periods SET ended_at=" + expression + " WHERE id=?", periodId))
                .isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                Timestamp.class, periodId)).isEqualTo(original);
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

    private UUID person(JdbcTemplate jdbc, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'test-hash','Test Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,now() FROM fitness.roles WHERE code=?
                """, id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        } else {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles(user_id,verification_status,verified_at,is_accepting_students,is_active)
                    VALUES (?,'VERIFIED',now(),true,true)
                    """, id);
        }
        return id;
    }

    private UUID pending(JdbcTemplate jdbc, UUID student, UUID trainer) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships(id,student_id,trainer_id,status,requested_by)
                VALUES (?,?,?,'PENDING',?)
                """, id, student, trainer, student);
        return id;
    }

    private void activate(JdbcTemplate jdbc, UUID relationship) {
        jdbc.update("""
                UPDATE fitness.coaching_relationships
                SET status='ACTIVE', version=version+1, accepted_at=clock_timestamp(), started_at=clock_timestamp()
                WHERE id=?
                """, relationship);
    }

    private UUID period(JdbcTemplate jdbc, UUID student, boolean finite) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,ended_at,created_by)
                VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',
                        CASE WHEN ? THEN clock_timestamp()+interval '1 day' ELSE NULL END,?)
                """, id, student, finite, student);
        return id;
    }
}
