package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class V47LinkedAppointmentMigrationUpgradeTest {
    private static PostgreSQLContainer<?> disposable(String name) {
        return new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg18")
                .asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(name).withUsername("fitness_app").withPassword("testpass123");
    }

    @Test void cleanInstallAddsNullableProposalObservationAndPairChecks() {
        try (var postgres = disposable("v47_link_clean_disposable")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            migrate(source, null);
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history "
                    + "WHERE version='47' AND success", Integer.class)).isOne();
            assertSchema(jdbc);
        }
    }

    @Test void upgradeFromV46PreservesLegacyAppointmentAndDoesNotInventObservation() {
        try (var postgres = disposable("v47_link_upgrade_disposable")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            migrate(source, "44");
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), appointment = UUID.randomUUID();
            UUID otherAppointment = UUID.randomUUID(), occurrence = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) "
                    + "VALUES (?,?,'hash','Student','ACTIVE')", student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("INSERT INTO fitness.planned_workouts(id,student_id,planned_start_at,"
                    + "original_planned_start_at,created_by) VALUES (?,?,"
                    + "'2026-11-01T09:00:00Z','2026-11-01T09:00:00Z',?)", occurrence, student, student);
            jdbc.update("INSERT INTO fitness.appointments(id,student_id,start_at,end_at,display_timezone,created_by) "
                    + "VALUES (?,?,'2026-11-01T09:00:00Z','2026-11-01T10:00:00Z','Etc/UTC',?)",
                    appointment, student, student);
            jdbc.update("UPDATE fitness.appointments SET planned_workout_id=?,status='CONFIRMED' WHERE id=?",
                    occurrence, appointment);
            jdbc.update("INSERT INTO fitness.appointments(id,student_id,planned_workout_id,start_at,end_at,"
                    + "display_timezone,status,created_by) VALUES (?,?,?,"
                    + "'2026-11-02T09:00:00Z','2026-11-02T10:00:00Z','Etc/UTC','CONFIRMED',?)",
                    otherAppointment, student, occurrence, student);
            migrate(source, "46");
            assertThat(jdbc.queryForObject("SELECT guarded_v2 FROM fitness.appointments WHERE id=?", Boolean.class,
                    appointment)).isFalse();
            migrate(source, null);
            assertSchema(jdbc);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE id=? "
                    + "AND NOT guarded_v2 AND coaching_period_id IS NULL AND local_start IS NULL",
                    Integer.class, appointment)).isOne();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE planned_workout_id=? "
                    + "AND status='CONFIRMED' AND NOT guarded_v2", Integer.class, occurrence)).isEqualTo(2);
        }
    }

    private static void migrate(DriverManagerDataSource source, String target) {
        var configured = Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                .locations("classpath:db/migration");
        if (target != null) configured.target(MigrationVersion.fromVersion(target));
        configured.load().migrate();
    }

    private static void assertSchema(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns WHERE table_schema='fitness'
                  AND ((table_name='workout_schedule_proposal_items'
                    AND column_name IN ('appointment_id','observed_appointment_version'))
                    OR (table_name='workout_schedule_change_proposals'
                    AND column_name IN ('appointment_id','observed_appointment_version')))
                  AND is_nullable='YES'
                """, Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_constraint WHERE conname IN "
                + "('schedule_proposal_appointment_pair_ck','change_proposal_appointment_pair_ck')",
                Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='fitness' "
                + "AND indexname='uq_appointment_guarded_confirmed_workout_v47'",
                Integer.class)).isOne();
    }
}
