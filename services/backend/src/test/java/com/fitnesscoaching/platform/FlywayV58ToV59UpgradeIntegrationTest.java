package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.support.adapter.out.persistence.SupportCasePersistenceAdapter;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class FlywayV58ToV59UpgradeIntegrationTest {

    @Container
    private static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("pgvector/pgvector:pg18")
                    .withDatabaseName("digital_fitness")
                    .withUsername("fitness_app")
                    .withPassword("change-me");

    @Test
    @DisplayName("Flyway V58 -> V59 schema upgrade drill: preserves legacy cases, synchronizes sequence past max, ensures collision-free concurrent creation")
    void flywayUpgradeV58toV59_drill() throws Exception {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUsername(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());

        JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
        NamedParameterJdbcTemplate namedParameterJdbcTemplate = new NamedParameterJdbcTemplate(dataSource);

        // Step 1: Migrate up to V58 only
        Flyway flywayV58 = Flyway.configure()
                .dataSource(dataSource)
                .schemas("fitness")
                .defaultSchema("fitness")
                .locations("classpath:db/migration")
                .target("58")
                .load();

        flywayV58.migrate();

        assertThat(flywayV58.info().current().getVersion().getVersion()).isEqualTo("58");

        // Step 2: Insert representative legacy support cases in V58 state
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();

        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'legacy_user@example.com', 'hash', 'Legacy Student', 'ACTIVE', ?, ?)
                """, userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        UUID case1Id = UUID.randomUUID();
        UUID case2Id = UUID.randomUUID();
        UUID case3Id = UUID.randomUUID();

        // Legacy Case 1: SUP-100000
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-100000', ?, 'Legacy Ticket 100k', 'First legacy ticket', 'LOW'::fitness.severity_level, 'RESOLVED', ?, ?)
                """, case1Id, userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        // Legacy Case 2: SUP-950000 (high existing number)
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-950000', ?, 'Legacy Ticket 950k', 'High sequence legacy ticket', 'HIGH'::fitness.severity_level, 'OPEN', ?, ?)
                """, case2Id, userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        // Legacy Case 3: Non-numeric suffix to verify regex robustness
        jdbcTemplate.update("""
                INSERT INTO fitness.support_cases (id, case_number, requester_id, subject, description, priority, status, opened_at, updated_at)
                VALUES (?, 'SUP-CUSTOM-X', ?, 'Custom Legacy Ticket', 'Non-numeric ticket', 'MEDIUM'::fitness.severity_level, 'OPEN', ?, ?)
                """, case3Id, userId, java.sql.Timestamp.from(now), java.sql.Timestamp.from(now));

        // Step 3: Run Flyway migration to V59
        Flyway flywayV59 = Flyway.configure()
                .dataSource(dataSource)
                .schemas("fitness")
                .defaultSchema("fitness")
                .locations("classpath:db/migration")
                .target("59")
                .load();

        flywayV59.migrate();

        assertThat(flywayV59.info().current().getVersion().getVersion()).isEqualTo("59");

        // Step 4: Verify sequence existence and value advancement past max_val (950000)
        Long nextSequenceVal = jdbcTemplate.queryForObject(
                "SELECT nextval('fitness.support_case_number_seq')", Long.class);

        assertThat(nextSequenceVal)
                .as("Sequence nextval should be greater than 950000 (the legacy max)")
                .isGreaterThanOrEqualTo(950001L);

        // Step 5: Test concurrent ticket creation with SupportCasePersistenceAdapter
        SupportCasePersistenceAdapter adapter = new SupportCasePersistenceAdapter(namedParameterJdbcTemplate);

        int threadCount = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        Set<String> generatedNumbers = ConcurrentHashMap.newKeySet();
        List<Exception> errors = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    latch.await();
                    String caseNumber = adapter.nextCaseNumber();
                    generatedNumbers.add(caseNumber);

                    UUID newCaseId = UUID.randomUUID();
                    adapter.insertSupportCase(newCaseId, caseNumber, userId, "Concurrent ticket",
                            "Concurrent ticket description", "MEDIUM", "OPEN", Instant.now());
                } catch (Exception ex) {
                    errors.add(ex);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        latch.countDown(); // trigger all threads simultaneously
        boolean completed = doneLatch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(errors).isEmpty();
        assertThat(generatedNumbers).hasSize(threadCount); // No duplicate case numbers

        // Step 6: Verify legacy data preservation
        Integer totalCases = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.support_cases", Integer.class);
        assertThat(totalCases).isEqualTo(3 + threadCount);

        String legacy1Subject = jdbcTemplate.queryForObject(
                "SELECT subject FROM fitness.support_cases WHERE case_number = 'SUP-100000'", String.class);
        assertThat(legacy1Subject).isEqualTo("Legacy Ticket 100k");

        String legacy2Subject = jdbcTemplate.queryForObject(
                "SELECT subject FROM fitness.support_cases WHERE case_number = 'SUP-950000'", String.class);
        assertThat(legacy2Subject).isEqualTo("Legacy Ticket 950k");

        String legacy3Subject = jdbcTemplate.queryForObject(
                "SELECT subject FROM fitness.support_cases WHERE case_number = 'SUP-CUSTOM-X'", String.class);
        assertThat(legacy3Subject).isEqualTo("Custom Legacy Ticket");
    }
}
