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

class V27CoachingSharingMigrationIntegrationTest {
    @Test
    void cleanInstallEnforcesNonOverlappingImmutableHistoryAndVersionedRevocation() {
        try (var db = database("coaching_v27_clean")) {
            db.start();
            var source = source(db);
            var jdbc = new JdbcTemplate(source);
            migrate(source, "27");
            Pair pair = activePair(jdbc);
            UUID first = permission(jdbc, pair, "FITNESS_GOAL", "ALLOW", "CONTRIBUTE");

            assertThatThrownBy(() -> permission(jdbc, pair, "FITNESS_GOAL", "DENY", "VIEW"))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.data_sharing_permissions SET access_level='MANAGE' WHERE id=?", first))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update(
                    "DELETE FROM fitness.data_sharing_permissions WHERE id=?", first))
                    .isInstanceOf(DataAccessException.class);

            jdbc.update("""
                    UPDATE fitness.data_sharing_permissions
                    SET revoked_at=clock_timestamp(), revoke_reason='REPLACED', version=version+1
                    WHERE id=? AND version=0
                    """, first);
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM fitness.data_sharing_permissions WHERE id=?", Long.class, first)).isOne();
            UUID replacement = permission(jdbc, pair, "FITNESS_GOAL", "ALLOW", "MANAGE");
            assertThat(replacement).isNotEqualTo(first);
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE fitness.data_sharing_permissions
                    SET revoked_at=clock_timestamp(), revoke_reason='RETRY', version=version+1 WHERE id=?
                    """, first)).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void upgradeBackfillsLeastPrivilegeWithoutRewritingLegacyIdentityOrCreationTime() {
        try (var db = database("coaching_v27_upgrade")) {
            db.start();
            var source = source(db);
            var jdbc = new JdbcTemplate(source);
            migrate(source, "26");
            Pair pair = activePair(jdbc);
            UUID goal = legacyPermission(jdbc, pair, "FITNESS_GOAL", "ALLOW");
            UUID history = legacyPermission(jdbc, pair, "WORKOUT_HISTORY", "ALLOW");
            Timestamp createdAt = jdbc.queryForObject(
                    "SELECT created_at FROM fitness.data_sharing_permissions WHERE id=?", Timestamp.class, goal);

            migrate(source, "27");

            assertThat(jdbc.queryForObject(
                    "SELECT access_level::text FROM fitness.data_sharing_permissions WHERE id=?", String.class, goal))
                    .isEqualTo("CONTRIBUTE");
            assertThat(jdbc.queryForObject(
                    "SELECT access_level::text FROM fitness.data_sharing_permissions WHERE id=?", String.class, history))
                    .isEqualTo("VIEW");
            assertThat(jdbc.queryForObject(
                    "SELECT created_at FROM fitness.data_sharing_permissions WHERE id=?", Timestamp.class, goal))
                    .isEqualTo(createdAt);
            assertThat(jdbc.queryForObject(
                    "SELECT version FROM fitness.data_sharing_permissions WHERE id=?", Long.class, goal)).isZero();
        }
    }

    private UUID permission(JdbcTemplate jdbc, Pair pair, String scope, String decision, String level) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,access_level,
                 valid_from,granted_by)
                VALUES (?,?,?,?,?::fitness.data_scope_code,?::fitness.permission_decision,
                        ?::fitness.data_access_level,clock_timestamp(),?)
                """, id, pair.relationship(), pair.student(), pair.trainer(), scope, decision, level, pair.student());
        return id;
    }

    private UUID legacyPermission(JdbcTemplate jdbc, Pair pair, String scope, String decision) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,valid_from,granted_by)
                VALUES (?,?,?,?,?::fitness.data_scope_code,?::fitness.permission_decision,clock_timestamp(),?)
                """, id, pair.relationship(), pair.student(), pair.trainer(), scope, decision, pair.student());
        return id;
    }

    private Pair activePair(JdbcTemplate jdbc) {
        UUID student = person(jdbc, "STUDENT");
        UUID trainer = person(jdbc, "TRAINER");
        UUID relationship = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,clock_timestamp(),clock_timestamp(),clock_timestamp())
                """, relationship, student, trainer, student);
        return new Pair(student, trainer, relationship);
    }

    private UUID person(JdbcTemplate jdbc, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'test-hash','Test Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?
                """, id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        } else {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles
                    (user_id,verification_status,verified_at,is_accepting_students,is_active)
                    VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                    """, id);
        }
        return id;
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

    private record Pair(UUID student, UUID trainer, UUID relationship) {
    }
}
