package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V55MeasurementErasureMigrationTest {
    @Test void cleanInstallKeepsOrdinaryMeasurementValueRequired() { verify(false); }

    @Test void upgradeFromV54PreservesLegacyValueAndEnablesEvidencedTombstone() { verify(true); }

    private void verify(boolean upgrade) {
        try (var pg = new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName("b08_v55_disposable").withUsername("fitness_app").withPassword("testpass123")) {
            pg.start();
            var ds = new DriverManagerDataSource(pg.getJdbcUrl(), pg.getUsername(), pg.getPassword());
            var jdbc = new JdbcTemplate(ds);
            if (upgrade) flyway(ds, "54").migrate();
            UUID student = UUID.randomUUID(), measurement = UUID.randomUUID();
            if (upgrade) {
                jdbc.update("""
                        INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                        VALUES (?,?,'hash','Legacy','ACTIVE')
                        """, student, student + "@example.com");
                jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
                jdbc.update("""
                        INSERT INTO fitness.measurement_units(code,symbol,dimension)
                        VALUES ('ERASURE_KG','kg','MASS')
                        """);
                jdbc.update("""
                        INSERT INTO fitness.metric_definitions(code,display_name,collection_type,default_unit_id)
                        VALUES ('ERASURE_WEIGHT','Erasure weight','DIRECT',
                            (SELECT id FROM fitness.measurement_units WHERE code='ERASURE_KG'))
                        """);
                jdbc.update("""
                        INSERT INTO fitness.measurement_sources(code,name,source_kind)
                        VALUES ('ERASURE_MANUAL','Erasure manual','MANUAL')
                        """);
                jdbc.update("""
                        INSERT INTO fitness.measurements
                            (id,student_id,metric_definition_id,numeric_value,unit_id,measured_at,source_id,validation_status)
                        VALUES (?,?,(SELECT id FROM fitness.metric_definitions WHERE code='ERASURE_WEIGHT'),
                            75,(SELECT id FROM fitness.measurement_units WHERE code='ERASURE_KG'),
                            clock_timestamp(),(SELECT id FROM fitness.measurement_sources WHERE code='ERASURE_MANUAL'),'VALID')
                        """, measurement, student);
            }
            flyway(ds, null).migrate();
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.flyway_schema_history WHERE version='55' AND success
                    """, Integer.class)).isEqualTo(1);
            if (!upgrade) return;
            assertThat(jdbc.queryForObject("SELECT numeric_value FROM fitness.measurements WHERE id=?",
                    BigDecimal.class, measurement)).isEqualByComparingTo("75");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.current_valid_measurements WHERE id=?",
                    Integer.class, measurement)).isEqualTo(1);
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE fitness.measurements SET numeric_value=NULL WHERE id=?
                    """, measurement)).isInstanceOf(org.springframework.dao.DataAccessException.class);
            UUID request = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO fitness.account_erasure_requests(id,user_id,command_key,status_key_hash,target_at)
                    VALUES (?,?,?,repeat('a',64),clock_timestamp()+interval '24 hours')
                    """, request, student, UUID.randomUUID());
            jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", student);
            jdbc.update("""
                    UPDATE fitness.measurements SET numeric_value=NULL,unit_id=NULL,
                        erased_at=clock_timestamp(),erasure_request_id=? WHERE id=?
                    """, request, measurement);
            assertThat(jdbc.queryForObject("SELECT numeric_value FROM fitness.measurements WHERE id=?",
                    BigDecimal.class, measurement)).isNull();
            assertThat(jdbc.queryForObject("SELECT erasure_request_id FROM fitness.measurements WHERE id=?",
                    UUID.class, measurement)).isEqualTo(request);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.current_valid_measurements WHERE id=?",
                    Integer.class, measurement)).isZero();
            assertThatThrownBy(() -> jdbc.update("""
                    UPDATE fitness.measurements SET numeric_value=1 WHERE id=?
                    """, measurement)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        }
    }

    private Flyway flyway(DriverManagerDataSource ds, String target) {
        var config = Flyway.configure().dataSource(ds).schemas("fitness").defaultSchema("fitness")
                .locations("classpath:db/migration");
        if (target != null) config.target(MigrationVersion.fromVersion(target));
        return config.load();
    }
}
