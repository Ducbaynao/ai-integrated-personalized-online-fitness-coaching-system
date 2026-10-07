package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V28WorkoutPlanDomainMigrationIntegrationTest {
    @Test
    void cleanInstallReachesV28() {
        try (var db = database("workout_v28_clean")) {
            db.start(); var source = source(db);
            migrate(source, "28");
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT version::text FROM fitness.flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1",
                    String.class)).isEqualTo("28");
        }
    }

    @Test
    void upgradeBackfillsOwnerAndEnforcesLifecycleUniquenessHistoryAndSourceIdentity() {
        try (var db = database("workout_v28_upgrade")) {
            db.start(); var source = source(db); var jdbc = new JdbcTemplate(source);
            migrate(source, "27");
            UUID student = person(jdbc, "STUDENT");
            UUID period = selfDirectedPeriod(jdbc, student);
            UUID first = legacyDraftPlan(jdbc, student, period, student, "First");
            UUID firstVersion = legacyVersion(jdbc, first, student);
            UUID firstSession = session(jdbc, firstVersion);
            UUID variation = exerciseVariation(jdbc, student);
            prescription(jdbc, firstSession, variation);
            UUID coachedStudent = person(jdbc, "STUDENT"); UUID trainer = person(jdbc, "TRAINER");
            UUID humanPeriod = humanCoachPeriod(jdbc, coachedStudent, trainer);
            UUID trainerPlan = legacyDraftPlan(jdbc, coachedStudent, humanPeriod, trainer, "Trainer plan");
            migrate(source, "28");

            assertThat(jdbc.queryForObject("SELECT decision_owner_type::text FROM fitness.workout_plans WHERE id=?",
                    String.class, first)).isEqualTo("STUDENT");
            assertThat(jdbc.queryForObject("SELECT decision_owner_type::text FROM fitness.workout_plans WHERE id=?",
                    String.class, trainerPlan)).isEqualTo("TRAINER");
            assertThat(jdbc.queryForObject("SELECT decision_owner_id FROM fitness.workout_plans WHERE id=?",
                    UUID.class, trainerPlan)).isEqualTo(trainer);
            assertThat(jdbc.queryForObject("SELECT reason FROM fitness.workout_plan_status_history WHERE workout_plan_id=?",
                    String.class, first)).isEqualTo("MIGRATED_BASELINE");
            assertThatThrownBy(() -> derivedPlan(jdbc, student, period, first, firstVersion))
                    .isInstanceOf(DataAccessException.class);

            jdbc.update("UPDATE fitness.workout_plan_versions SET locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED' WHERE id=?",
                    student, firstVersion);
            jdbc.update("UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1 WHERE id=?", first);

            UUID second = plan(jdbc, student, period, student, "Second");
            UUID secondVersion = version(jdbc, second, student);
            UUID secondSession = session(jdbc, secondVersion);
            UUID otherPrescription = prescription(jdbc, secondSession, variation);
            assertThatThrownBy(() -> derivedPlan(jdbc, student, period, first, secondVersion))
                    .isInstanceOf(DataAccessException.class);
            jdbc.update("UPDATE fitness.workout_plan_versions SET locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED' WHERE id=?",
                    student, secondVersion);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1 WHERE id=?", second))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM fitness.workout_plans WHERE id=?", first))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("UPDATE fitness.workout_plan_status_history SET reason='x' WHERE workout_plan_id=?", first))
                    .isInstanceOf(DataAccessException.class);

            UUID occurrence = occurrence(jdbc, student, period, firstSession);
            assertThatThrownBy(() -> jdbc.update(
                    "UPDATE fitness.planned_workouts SET workout_plan_session_id=?,version=version+1 WHERE id=?",
                    secondSession, occurrence)).isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("DELETE FROM fitness.planned_workouts WHERE id=?", occurrence))
                    .isInstanceOf(DataAccessException.class);
            assertThatThrownBy(() -> jdbc.update("""
                    INSERT INTO fitness.workout_session_adjustments
                    (planned_workout_id,adjustment_type,planned_session_exercise_id,after_value,reason,adjusted_by)
                    VALUES (?,'NOTE',?,'{}'::jsonb,'wrong session',?)
                    """, occurrence, otherPrescription, student)).isInstanceOf(DataAccessException.class);
        }
    }

    @Test
    void migrationFailsRatherThanGuessingAmbiguousOwnership() {
        try (var db = database("workout_v28_owner_fail")) {
            db.start(); var source = source(db); var jdbc = new JdbcTemplate(source);
            migrate(source, "27");
            UUID student = person(jdbc, "STUDENT"); UUID admin = person(jdbc, "ADMIN");
            UUID period = selfDirectedPeriod(jdbc, student);
            legacyDraftPlan(jdbc, student, period, admin, "Ambiguous");
            assertThatThrownBy(() -> migrate(source, "28"))
                    .hasMessageContaining("V28 cannot infer workout-plan decision ownership unambiguously");
        }
    }

    @Test
    void migrationFailsWhenLegacyDataHasTwoActivePlans() {
        try (var db = database("workout_v28_active_fail")) {
            db.start(); var source = source(db); var jdbc = new JdbcTemplate(source);
            migrate(source, "27");
            UUID student = person(jdbc, "STUDENT"); UUID period = selfDirectedPeriod(jdbc, student);
            for (String name : List.of("One", "Two")) {
                UUID plan = legacyDraftPlan(jdbc, student, period, student, name);
                UUID version = legacyVersion(jdbc, plan, student);
                jdbc.update("UPDATE fitness.workout_plan_versions SET locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED' WHERE id=?",
                        student, version);
                jdbc.update("UPDATE fitness.workout_plans SET status='ACTIVE' WHERE id=?", plan);
            }
            assertThatThrownBy(() -> migrate(source, "28"))
                    .hasMessageContaining("more than one ACTIVE workout plan");
        }
    }

    private UUID plan(JdbcTemplate jdbc, UUID student, UUID period, UUID creator, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,coaching_period_id,name,source,status,created_by,decision_owner_type,decision_owner_id)
                VALUES (?,?,?,?,'STUDENT','DRAFT',?,'STUDENT',?)
                """, id, student, period, name, creator, student);
        return id;
    }
    private UUID legacyDraftPlan(JdbcTemplate jdbc, UUID student, UUID period, UUID creator, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.workout_plans(id,student_id,coaching_period_id,name,source,status,created_by) VALUES (?,?,?,?,'STUDENT','DRAFT',?)",
                id, student, period, name, creator); return id;
    }
    private UUID derivedPlan(JdbcTemplate jdbc, UUID student, UUID period, UUID basedOnPlan, UUID basedOnVersion) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,coaching_period_id,name,source,status,created_by,decision_owner_type,decision_owner_id,
                 based_on_plan_id,based_on_plan_version_id)
                VALUES (?,?,?,'Derived','STUDENT','DRAFT',?,'STUDENT',?,?,?)
                """, id, student, period, student, student, basedOnPlan, basedOnVersion);
        return id;
    }
    private UUID version(JdbcTemplate jdbc, UUID plan, UUID creator) { return legacyVersion(jdbc, plan, creator); }
    private UUID legacyVersion(JdbcTemplate jdbc, UUID plan, UUID creator) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.workout_plan_versions(id,workout_plan_id,version_number,change_level,created_by) VALUES (?,?,1,'INITIAL',?)",
                id, plan, creator); return id;
    }
    private UUID session(JdbcTemplate jdbc, UUID version) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.workout_plan_sessions(id,workout_plan_version_id,week_number,day_number,sequence_number,name) VALUES (?,?,1,1,1,'Session')",
                id, version); return id;
    }
    private UUID exerciseVariation(JdbcTemplate jdbc, UUID creator) {
        UUID exercise = UUID.randomUUID(); UUID variation = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.exercises(id,code,name,admin_status,created_by) VALUES (?,?,?,'ACTIVE',?)",
                exercise, "exercise-" + exercise, "Exercise", creator);
        jdbc.update("INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,is_active) VALUES (?,?,?,?,true)",
                variation, exercise, "variation-" + variation, "Variation");
        return variation;
    }
    private UUID prescription(JdbcTemplate jdbc, UUID session, UUID variation) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.workout_plan_session_exercises(id,workout_plan_session_id,exercise_variation_id,sequence_number) VALUES (?,?,?,1)",
                id, session, variation); return id;
    }
    private UUID occurrence(JdbcTemplate jdbc, UUID student, UUID period, UUID session) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,coaching_period_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,?, ?,clock_timestamp(),clock_timestamp(),?)
                """, id, student, session, period, student); return id;
    }
    private UUID selfDirectedPeriod(JdbcTemplate jdbc, UUID student) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.coaching_periods(id,student_id,mode,started_at,created_by) VALUES (?,?,'SELF_DIRECTED',clock_timestamp()-interval '1 day',?)",
                id, student, student); return id;
    }
    private UUID humanCoachPeriod(JdbcTemplate jdbc, UUID student, UUID trainer) {
        UUID relationship = UUID.randomUUID(), period = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id,student_id,trainer_id,status,requested_by,requested_at,accepted_at,started_at)
                VALUES (?,?,?,'ACTIVE',?,clock_timestamp()-interval '1 day',
                        clock_timestamp()-interval '1 day',clock_timestamp()-interval '1 day')
                """, relationship, student, trainer, student);
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id,student_id,mode,coaching_relationship_id,trainer_id,started_at,created_by)
                VALUES (?,?,'HUMAN_COACH',?,?,clock_timestamp()-interval '1 day',?)
                """, period, student, relationship, trainer, trainer);
        return period;
    }
    private UUID person(JdbcTemplate jdbc, String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) VALUES (?,?,'hash','Person','ACTIVE','vi-VN','Asia/Ho_Chi_Minh')",
                id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?",
                id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        } else if (role.equals("TRAINER")) {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles
                    (user_id,verification_status,verified_at,is_accepting_students,is_active)
                    VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                    """, id);
        }
        return id;
    }
    private PostgreSQLContainer<?> database(String name) {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres")).withDatabaseName(name)
                .withUsername("test_user").withPassword("test_pass");
    }
    private DriverManagerDataSource source(PostgreSQLContainer<?> db) {
        return new DriverManagerDataSource(db.getJdbcUrl(), db.getUsername(), db.getPassword());
    }
    private void migrate(DriverManagerDataSource source, String target) {
        Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness").target(target).load().migrate();
    }
}
