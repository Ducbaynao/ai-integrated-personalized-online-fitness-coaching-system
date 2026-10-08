package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutExecutionReadPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class WorkoutExecutionReadPersistenceIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("workout_execution_read_b05").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired WorkoutExecutionReadPort reads;

    @Test
    void legacyReferenceOnlyGraphReadsWithoutFabricatingFrozenEvidence() {
        UUID student = student();
        UUID variation = variation(student, "Legacy");
        UUID execution = legacyExecution(student, Instant.parse("2026-01-15T00:00:00Z"));
        UUID exercise = UUID.randomUUID(); UUID set = UUID.randomUUID(); UUID clientSet = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.exercise_logs
                (id,workout_session_log_id,exercise_variation_id,actual_exercise_variation_id,sequence_number,notes)
                VALUES (?,?,?,?,1,'Legacy exercise facts')
                """, exercise, execution, variation, variation);
        jdbc.update("""
                INSERT INTO fitness.set_logs
                (id,exercise_log_id,client_set_id,set_number,set_type,completion_status,repetitions,notes)
                VALUES (?,?,?,1,'WORKING','COMPLETED',9,'Legacy set facts')
                """, set, exercise, clientSet);

        var read = reads.findDetail(execution).orElseThrow();

        assertThat(read.snapshotMode()).isEqualTo("LEGACY_REFERENCE_ONLY");
        assertThat(read.planId()).isNull();
        assertThat(read.planSessionId()).isNull();
        assertThat(read.frozenAt()).isNull();
        assertThat(read.plannedStartAt()).isNull();
        assertThat(read.supervisionRequirement()).isNull();
        assertThat(read.exercises()).hasSize(1);
        assertThat(read.exercises().getFirst().sourcePrescriptionId()).isNull();
        assertThat(read.exercises().getFirst().sequenceNumber()).isNull();
        assertThat(read.exercises().getFirst().baselineSetCount()).isNull();
        assertThat(read.exercises().getFirst().prescribedVariationId()).isEqualTo(variation);
        assertThat(read.exercises().getFirst().sets().getFirst().repetitions()).isEqualTo(9);
    }

    @Test
    void historyWindowFiltersBeforePageAndCountWithHalfOpenBoundaries() {
        UUID student = student();
        Instant from = Instant.parse("2026-02-01T00:00:00Z").truncatedTo(ChronoUnit.MICROS);
        Instant inside = from.plusSeconds(60);
        Instant until = Instant.parse("2026-03-01T00:00:00Z").truncatedTo(ChronoUnit.MICROS);
        legacyExecution(student, from.minus(1, ChronoUnit.MICROS));
        UUID atFrom = legacyExecution(student, from);
        UUID atInside = legacyExecution(student, inside);
        legacyExecution(student, until);
        legacyExecution(student, until.plus(1, ChronoUnit.MICROS));

        var first = reads.findHistory(student, from, until, 0, 1);
        var second = reads.findHistory(student, from, until, 1, 1);
        var after = reads.findHistory(student, from, until, 2, 1);

        assertThat(first.totalItems()).isEqualTo(2);
        assertThat(first.items()).extracting(WorkoutExecutionReadPort.ExecutionRecord::executionId)
                .containsExactly(atInside);
        assertThat(second.totalItems()).isEqualTo(2);
        assertThat(second.items()).extracting(WorkoutExecutionReadPort.ExecutionRecord::executionId)
                .containsExactly(atFrom);
        assertThat(after.totalItems()).isEqualTo(2);
        assertThat(after.items()).isEmpty();
    }

    private UUID student() {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Read Student','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        return id;
    }

    private UUID legacyExecution(UUID student, Instant started) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_session_logs
                (id,student_id,performed_start_at,performed_end_at,status,notes,logged_by,snapshot_mode)
                VALUES (?,?,?,?,'COMPLETED','Legacy actual facts',?,'LEGACY_REFERENCE_ONLY')
                """, id, student, Timestamp.from(started), Timestamp.from(started.plusSeconds(1)), student);
        return id;
    }

    private UUID variation(UUID creator, String name) {
        UUID exercise = UUID.randomUUID(), variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, name, creator);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, name + " variation");
        return variation;
    }
}
