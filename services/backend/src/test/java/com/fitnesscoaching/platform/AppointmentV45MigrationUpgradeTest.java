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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AppointmentV45MigrationUpgradeTest {
    private static PostgreSQLContainer<?> disposable(String database) {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(database).withUsername("fitness_app").withPassword("testpass123");
    }

    @Test void cleanInstallCreatesGuardedSchemaWithoutChangingHistoricalContract() {
        try (var postgres = disposable("appointment_v45_clean_disposable")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            var jdbc = new JdbcTemplate(source);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.flyway_schema_history WHERE version='45' AND success",
                    Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM pg_indexes WHERE schemaname='fitness' "
                    + "AND indexname='uq_lifecycle_event_notification_v2'", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM information_schema.columns "
                    + "WHERE table_schema='fitness' AND table_name='appointments' "
                    + "AND column_name='local_start' AND is_nullable='YES'", Integer.class)).isEqualTo(1);
            UUID student = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) "
                    + "VALUES (?,?,'hash','Student','ACTIVE')", student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            assertNewUnguardedInsertRejected(jdbc, student, false);
            assertNewUnguardedInsertRejected(jdbc, student, true);
            assertNewGuardedInsertRequiresCompleteIntent(jdbc, student);
        }
    }

    @Test void upgradePreservesLegacyAppointmentAndNotificationWithoutInventingPeriodOrIntent() {
        try (var postgres = disposable("appointment_v45_upgrade_disposable")) {
            postgres.start();
            var source = new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").target(MigrationVersion.fromVersion("44"))
                    .load().migrate();
            var jdbc = new JdbcTemplate(source);
            UUID student = UUID.randomUUID(), appointment = UUID.randomUUID(), overlapping = UUID.randomUUID(),
                    notice = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) "
                    + "VALUES (?,?,'hash','Legacy Student','ACTIVE')", student, student + "@example.com");
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
            jdbc.update("INSERT INTO fitness.appointments(id,student_id,start_at,end_at,display_timezone,created_by) "
                    + "VALUES (?,?,'2026-11-01T09:00:00Z','2026-11-01T10:00:00Z','Etc/UTC',?)",
                    appointment, student, student);
            jdbc.update("INSERT INTO fitness.appointments(id,student_id,start_at,end_at,display_timezone,created_by) "
                    + "VALUES (?,?,'2026-11-01T09:30:00Z','2026-11-01T10:30:00Z','Etc/UTC',?)",
                    overlapping, student, student);
            jdbc.update("INSERT INTO fitness.notifications(id,recipient_user_id,event_type,title,body) "
                    + "VALUES (?,?,'CHAT_MESSAGE','Legacy','generic')", notice, student);
            Flyway.configure().dataSource(source).schemas("fitness").defaultSchema("fitness")
                    .locations("classpath:db/migration").load().migrate();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE id=? "
                    + "AND coaching_period_id IS NULL AND local_start IS NULL "
                    + "AND start_utc_offset IS NULL AND NOT guarded_v2 AND version=0", Integer.class,
                    appointment)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE id=? "
                    + "AND NOT guarded_v2", Integer.class, overlapping)).isEqualTo(1);
            assertNewUnguardedInsertRejected(jdbc, student, false);
            assertNewUnguardedInsertRejected(jdbc, student, true);
            assertNewGuardedInsertRequiresCompleteIntent(jdbc, student);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE id=? "
                    + "AND source_event_id IS NULL AND source_student_id IS NULL", Integer.class,
                    notice)).isEqualTo(1);
            UUID sourceEvent = UUID.randomUUID();
            jdbc.update("INSERT INTO fitness.notifications(recipient_user_id,event_type,title,body,"
                    + "related_entity_type,related_entity_id,source_event_id,source_student_id) "
                    + "VALUES (?,'APPOINTMENT_REQUEST_PENDING','Lịch hẹn','Bạn có cập nhật mới',"
                    + "'APPOINTMENT_REQUEST',?,?,?)", student, appointment, sourceEvent, student);
            assertThatThrownBy(() -> jdbc.update("INSERT INTO fitness.notifications(recipient_user_id,event_type,title,body,"
                    + "related_entity_type,related_entity_id,source_event_id,source_student_id) "
                    + "VALUES (?,'APPOINTMENT_REQUEST_PENDING','Lịch hẹn','Bạn có cập nhật mới',"
                    + "'APPOINTMENT_REQUEST',?,?,?)", student, appointment, sourceEvent, student))
                    .hasMessageContaining("uq_lifecycle_event_notification_v2");
        }
    }

    private static void assertNewUnguardedInsertRejected(JdbcTemplate jdbc, UUID student, boolean explicitFalse) {
        String columns = "id,student_id,start_at,end_at,display_timezone,created_by"
                + (explicitFalse ? ",guarded_v2" : "");
        String values = "?,?,'2026-11-01T09:15:00Z','2026-11-01T09:45:00Z','Etc/UTC',?"
                + (explicitFalse ? ",false" : "");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fitness.appointments(" + columns + ") VALUES ("
                + values + ")", UUID.randomUUID(), student, student))
                .hasMessageContaining("new Appointment must use the V45 guard");
    }

    private static void assertNewGuardedInsertRequiresCompleteIntent(JdbcTemplate jdbc, UUID student) {
        assertThatThrownBy(() -> jdbc.update("INSERT INTO fitness.appointments"
                + "(id,student_id,start_at,end_at,display_timezone,created_by,guarded_v2) "
                + "VALUES (?,?,'2026-11-01T09:15:00Z','2026-11-01T09:45:00Z','Etc/UTC',?,true)",
                UUID.randomUUID(), student, student))
                .hasMessageContaining("appointment_guarded_v2_complete_ck");
    }
}
