package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V24CoachingLifecycleMigrationIntegrationTest {
    @Test
    void upgradesValidLegacyRowsWithoutRewritingHistoryAndEnforcesCurrentUniqueness() {
        try (var db = database("coaching_v24_valid")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            var jdbc = new JdbcTemplate(source);
            migrate(source, "23");
            UUID student = person(jdbc, "STUDENT"), trainerA = person(jdbc, "TRAINER"),
                    trainerB = person(jdbc, "TRAINER");
            UUID existing = relationship(jdbc, student, trainerA, "ACTIVE");
            UUID finiteStudent = person(jdbc, "STUDENT");
            UUID finiteRelationship = relationship(jdbc, finiteStudent, trainerB, "ACTIVE");
            UUID finitePeriod = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.coaching_periods
                    (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,ended_at,created_by)
                    VALUES (?,?,'HUMAN_COACH',?,?,now()-interval '1 day',now()+interval '1 day',?)
                    """, finitePeriod, finiteStudent, finiteRelationship, trainerB, finiteStudent);
            Timestamp scheduledEnd = jdbc.queryForObject(
                    "SELECT ended_at FROM fitness.coaching_periods WHERE id=?", Timestamp.class, finitePeriod);
            migrate(source, "24");
            assertThat(jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                    Timestamp.class, finitePeriod)).isEqualTo(scheduledEnd);
            jdbc.update("UPDATE fitness.coaching_periods SET ended_at=now() WHERE id=?", finitePeriod);
            assertThat(jdbc.queryForObject("SELECT ended_at FROM fitness.coaching_periods WHERE id=?",
                    Timestamp.class, finitePeriod)).isBefore(scheduledEnd);
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE fitness.coaching_periods SET ended_at=now()+interval '1 day' WHERE id=?
                    """, finitePeriod)).isInstanceOf(DataAccessException.class);
            assertThat(jdbc.queryForObject("SELECT version FROM fitness.coaching_relationships WHERE id=?",
                    Long.class, existing)).isZero();
            assertThat(jdbc.queryForObject("SELECT status FROM fitness.coaching_relationships WHERE id=?",
                    String.class, existing)).isEqualTo("ACTIVE");
            assertThatThrownBy(() -> relationship(jdbc, student, trainerB, "ACTIVE"))
                    .isInstanceOf(DataIntegrityViolationException.class);
            UUID otherStudent = person(jdbc, "STUDENT");
            jdbc.update("""
                    INSERT INTO fitness.coaching_relationships(student_id,trainer_id,status,requested_by)
                    VALUES (?,?,'PENDING',?)
                    """, otherStudent, trainerA, otherStudent);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fitness.coaching_relationships(student_id,trainer_id,status,requested_by)
                    VALUES (?,?,'PENDING',?)
                    """, otherStudent, trainerA, trainerA)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fitness.coaching_relationships(student_id,trainer_id,status,requested_by)
                    VALUES (?,?,'PENDING',?)
                    """, otherStudent, trainerB, trainerA)).isInstanceOf(DataIntegrityViolationException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fitness.coaching_periods(student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                    VALUES (?,'HUMAN_COACH',?,?,now(),?)
                    """, student, existing, trainerB, student)).isInstanceOf(DataAccessException.class);
            jdbc.update("""
                    INSERT INTO fitness.coaching_periods(student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                    VALUES (?,'HUMAN_COACH',?,?,now(),?)
                    """, student, existing, trainerA, student);
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE fitness.coaching_periods SET started_at=started_at - interval '1 day'
                    WHERE coaching_relationship_id=?
                    """, existing)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fitness.coaching_periods(student_id,mode,started_at,created_by)
                    VALUES (?,'SELF_DIRECTED',now(),?)
                    """, student, student)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM fitness.coaching_relationships WHERE id=?", existing))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void failsClearlyOnInvalidLegacyStudentMultiplicity() {
        try (var db = database("coaching_v24_invalid")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            var jdbc = new JdbcTemplate(source);
            migrate(source, "23");
            UUID student = person(jdbc, "STUDENT"), trainerA = person(jdbc, "TRAINER"),
                    trainerB = person(jdbc, "TRAINER");
            relationship(jdbc, student, trainerA, "ACTIVE");
            relationship(jdbc, student, trainerB, "ACTIVE");
            assertThatThrownBy(() -> migrate(source, "24"))
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("one current coaching relationship per student");
        }
    }

    @Test
    void failsClearlyOnInvalidLegacyInitiator() {
        try (var db = database("coaching_v24_invalid_initiator")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            var jdbc = new JdbcTemplate(source);
            migrate(source, "23");
            UUID student = person(jdbc, "STUDENT"), trainer = person(jdbc, "TRAINER"),
                    outsider = person(jdbc, "STUDENT");
            jdbc.update("""
                    INSERT INTO fitness.coaching_relationships(student_id,trainer_id,status,requested_by)
                    VALUES (?,?,'PENDING',?)
                    """, student, trainer, outsider);
            assertThatThrownBy(() -> migrate(source, "24"))
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("request initiator outside its relationship");
        }
    }

    @Test
    void failsClearlyOnInvalidLegacyLifecycleTimestamps() {
        try (var db = database("coaching_v24_invalid_timestamps")) {
            db.start();
            var source = new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
            var jdbc = new JdbcTemplate(source);
            migrate(source, "23");
            UUID student = person(jdbc, "STUDENT"), trainer = person(jdbc, "TRAINER");
            jdbc.update("""
                    INSERT INTO fitness.coaching_relationships
                    (student_id,trainer_id,status,requested_by,accepted_at,started_at)
                    VALUES (?,?,'PENDING',?,now(),now())
                    """, student, trainer, student);
            assertThatThrownBy(() -> migrate(source, "24"))
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("timestamps inconsistent with lifecycle status");
        }
    }

    private PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(name).withUsername("test_user").withPassword("test_pass");
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

    private UUID relationship(JdbcTemplate jdbc, UUID student, UUID trainer, String status) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                  (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,?::fitness.coaching_relationship_status,?,now(),now(),now())
                """, id, student, trainer, status, student);
        return id;
    }
}
