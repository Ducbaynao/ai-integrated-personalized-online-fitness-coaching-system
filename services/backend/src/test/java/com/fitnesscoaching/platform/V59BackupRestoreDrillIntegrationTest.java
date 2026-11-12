package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.support.adapter.out.persistence.SupportCasePersistenceAdapter;
import com.fitnesscoaching.platform.modules.trainer.application.service.TrainerDocumentStorageService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class V59BackupRestoreDrillIntegrationTest {

    @Container
    private static final GenericContainer<?> s3 = new GenericContainer<>(
            "adobe/s3mock@sha256:ab01a6946750f451ca215a47e91030695b260e4003b8a5a6201d25029b8fca92")
            .withEnv("COM_ADOBE_TESTING_S3MOCK_STORE_INITIAL_BUCKETS", "fitness-media")
            .withExposedPorts(9090)
            .waitingFor(Wait.forHttp("/favicon.ico").forPort(9090));


    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg18")
                    .withDatabaseName("digital_fitness_v59_drill")
                    .withUsername("fitness_app")
                    .withPassword("change-me");

    @Test
    @DisplayName("V59 Backup and Restore drill: verifies full schema V59, representative business data, sequence continuity, and storage integrity after restore")
    void v59BackupRestoreDrill_verifiesIntegrityAndSequence() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

        // Step 1: Migrate all migrations up to V59
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .schemas("fitness")
                .defaultSchema("fitness")
                .locations("classpath:db/migration")
                .target("59")
                .load();

        flyway.migrate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("59");

        // Step 2: Seed representative business data at V59
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'student_v59@example.com', 'hash', 'Student V59 Drill', 'ACTIVE', ?, ?)
                """, userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        // Insert legacy-style support case to ensure sequence was set
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-880000', ?, 'Representative Ticket 880k', 'V59 Seed Ticket', 'MEDIUM'::fitness.severity_level, 'OPEN', ?, ?)
                """, UUID.randomUUID(), userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        // Use SupportCasePersistenceAdapter to generate next case via V59 sequence
        SupportCasePersistenceAdapter adapter = new SupportCasePersistenceAdapter(namedParameterJdbcTemplate);
        String sequenceGeneratedCaseNumber = adapter.nextCaseNumber();

        UUID v59CaseId = UUID.randomUUID();
        adapter.insertSupportCase(v59CaseId, sequenceGeneratedCaseNumber, userId, "V59 Sequence Ticket",
                "Generated via support_case_number_seq", "HIGH", "OPEN", now);

        assertThat(sequenceGeneratedCaseNumber).startsWith("SUP-");

        // Step 3: Verify sequence nextval works on V59
        Long currentSeqVal = jdbcTemplate.queryForObject(
                "SELECT last_value FROM fitness.support_case_number_seq", Long.class);
        assertThat(currentSeqVal).isNotNull();

        // Step 4: Run backup-system.ps1 against this isolated container
        String containerName = postgres.getContainerId();
        String tempBackupDir = new File(System.getProperty("java.io.tmpdir"), "v59_drill_backup_" + UUID.randomUUID()).getAbsolutePath();

        String powerShell = System.getProperty("os.name").startsWith("Windows") ? "powershell.exe" : "pwsh";
        String s3Endpoint = "http://" + s3.getHost() + ":" + s3.getMappedPort(9090);
        String objectKey = "verification-docs/" + UUID.randomUUID() + "/cert.pdf";
        byte[] media = "%PDF-1.4 V59 restore drill".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        TrainerDocumentStorageService sourceStorage = new TrainerDocumentStorageService(
                s3Endpoint, "fitness-media", "test", "test", "", 3310);
        sourceStorage.storeFile(objectKey, media, "application/pdf");

        ProcessBuilder backupPb = new ProcessBuilder(
                powerShell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools/backup-system.ps1",
                "-ContainerName", containerName,
                "-DbName", "digital_fitness_v59_drill",
                "-DbUser", "fitness_app",
                "-S3Endpoint", s3Endpoint,
                "-S3Bucket", "fitness-media",
                "-OutputDir", tempBackupDir
        );
        backupPb.directory(new File("../..").getCanonicalFile());
        backupPb.redirectErrorStream(true);
        Process backupProc = backupPb.start();

        StringBuilder backupOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(backupProc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                backupOutput.append(line).append("\n");
            }
        }
        int backupExit = backupProc.waitFor();
        assertThat(backupExit).as("Backup script should exit with 0. Output:\n" + backupOutput).isEqualTo(0);

        File manifestFile = new File(tempBackupDir, "manifest.json");
        assertThat(manifestFile).exists();

        // Step 5: Run restore-system.ps1 into isolated restore DB
        ProcessBuilder restorePb = new ProcessBuilder(
                powerShell, "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", "tools/restore-system.ps1",
                "-BackupDir", tempBackupDir,
                "-ContainerName", containerName,
                "-TargetDbName", "digital_fitness_v59_restored",
                "-DbUser", "fitness_app",
                "-TargetS3Endpoint", s3Endpoint,
                "-TargetS3Bucket", "fitness-media-v59-restored"
        );
        restorePb.directory(new File("../..").getCanonicalFile());
        restorePb.redirectErrorStream(true);
        Process restoreProc = restorePb.start();

        StringBuilder restoreOutput = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(restoreProc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                restoreOutput.append(line).append("\n");
            }
        }
        int restoreExit = restoreProc.waitFor();
        assertThat(restoreExit).as("Restore script should exit with 0. Output:\n" + restoreOutput).isEqualTo(0);

        // Step 6: Verify restored database state
        DriverManagerDataSource restoredDs = new DriverManagerDataSource();
        restoredDs.setDriverClassName("org.postgresql.Driver");
        restoredDs.setUrl(postgres.getJdbcUrl().replace("digital_fitness_v59_drill", "digital_fitness_v59_restored"));
        restoredDs.setUsername(postgres.getUsername());
        restoredDs.setPassword(postgres.getPassword());

        JdbcTemplate restoredJdbc = new JdbcTemplate(restoredDs);

        // Verify Flyway schema version 59
        String restoredFlywayVer = restoredJdbc.queryForObject(
                "SELECT version FROM fitness.flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class);
        assertThat(restoredFlywayVer).isEqualTo("59");

        // Verify representative data preservation
        Integer totalCases = restoredJdbc.queryForObject(
                "SELECT count(*) FROM fitness.support_cases", Integer.class);
        assertThat(totalCases).isEqualTo(2);

        String restoredSubject = restoredJdbc.queryForObject(
                "SELECT subject FROM fitness.support_cases WHERE case_number = ?", String.class, sequenceGeneratedCaseNumber);
        assertThat(restoredSubject).isEqualTo("V59 Sequence Ticket");

        // Verify sequence is functional after restore and continues collision-free
        Long restoredNextSeq = restoredJdbc.queryForObject(
                "SELECT nextval('fitness.support_case_number_seq')", Long.class);
        assertThat(restoredNextSeq).isGreaterThanOrEqualTo(currentSeqVal);

        TrainerDocumentStorageService restoredStorage = new TrainerDocumentStorageService(
                s3Endpoint, "fitness-media-v59-restored", "test", "test", "", 3310);
        assertThat(restoredStorage.loadFile(objectKey)).isEqualTo(media);

        // Cleanup temp backup dir
        try {
            org.springframework.util.FileSystemUtils.deleteRecursively(new File(tempBackupDir));
        } catch (Exception ignored) {
        }
    }
}
