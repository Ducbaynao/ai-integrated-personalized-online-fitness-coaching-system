package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.user.application.service.AccountErasureWorker;
import com.fitnesscoaching.platform.modules.chat.ChatObjectStorage;
import com.fitnesscoaching.platform.modules.chat.ChatFailure;
import com.fitnesscoaching.platform.modules.auth.domain.TokenHasher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"account.erasure.worker-enabled=false", "account.erasure.request-enabled=true",
        "app.scheduling.enabled=false"})
class AccountErasureHttpIntegrationTest {
    static final PostgreSQLContainer<?> pg = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("b08_erasure_http_disposable").withUsername("fitness_app").withPassword("testpass123");
    static { pg.start(); }
    @DynamicPropertySource static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", pg::getJdbcUrl);
        registry.add("spring.datasource.username", pg::getUsername);
        registry.add("spring.datasource.password", pg::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.flyway.schemas", () -> "fitness");
        registry.add("spring.flyway.default-schema", () -> "fitness");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    @Autowired AccountErasureWorker worker;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping requestMappings;
    @MockitoBean ChatObjectStorage storage;

    @BeforeEach void isolateDueWorkFromEarlierTestRequests() {
        // The disposable database is shared by methods in this class. A request
        // intentionally left pending by one test must not be claimed by the next.
        jdbc.update("""
                UPDATE fitness.account_erasure_requests
                SET next_attempt_at=clock_timestamp()+interval '1 day'
                WHERE status IN ('REQUESTED','PROCESSING','RETRYABLE','HELD')
                """);
    }

    @Test void noExistingAdminHasHoldMutationRouteOrPermission() {
        assertThat(requestMappings.getHandlerMethods().keySet().stream()
                .flatMap(mapping -> mapping.getPatternValues().stream())
                .filter(path -> path.contains("account-erasure") && path.contains("hold")))
                .isEmpty();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.permissions
                WHERE code LIKE 'ACCOUNT_ERASURE%HOLD%'
                """, Integer.class)).isZero();
    }

    @Test void requestCutsOffAccountAndRawPushAndStatusIsCapabilityScoped() throws Exception {
        UUID user = UUID.randomUUID(), device = UUID.randomUUID(), command = UUID.randomUUID();
        String statusKey = newStatusKey();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                VALUES (?,?,'hash','Student','ACTIVE')
                """, user, user + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.push_devices(user_id,device_key,expo_token)
                VALUES (?,?,'ExpoPushToken[erasure-test]')
                """, user, device);
        String refresh = "erasure-refresh-" + UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.refresh_tokens(user_id,token_hash,issued_at,expires_at)
                VALUES (?,?,clock_timestamp(),clock_timestamp()+interval '1 day')
                """, user, TokenHasher.sha256Hex(refresh));
        jdbc.update("""
                INSERT INTO fitness.audit_logs(actor_user_id,action,target_type,target_id,before_data)
                VALUES (?,'PROFILE_EDIT','USER',?,?::jsonb)
                """, user, user, "{\"email\":\"" + user + "@example.com\"}");
        jdbc.update("""
                INSERT INTO fitness.security_events(user_id,event_type,details)
                VALUES (?,'LOGIN_SUCCESS',?::jsonb)
                """, user, "{\"subject\":\"" + user + "\"}");
        Long auditId = jdbc.queryForObject("""
                SELECT id FROM fitness.audit_logs WHERE actor_user_id=? AND target_id=?
                ORDER BY id DESC LIMIT 1
                """, Long.class, user, user);
        String priorAuditHash = jdbc.queryForObject("""
                SELECT encode(fitness.digest(to_jsonb(a)::text,'sha256'),'hex')
                FROM fitness.audit_logs a WHERE id=?
                """, String.class, auditId);
        String response = mvc.perform(post("/api/v1/account-erasure-requests")
                        .with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType("application/json")
                        .content("{\"commandKey\":\"" + command + "\",\"statusKey\":\"" + statusKey + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("REQUESTED"))
                .andReturn().getResponse().getContentAsString();
        UUID request = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(response).get("id").asText());
        assertThat(jdbc.queryForObject("SELECT status::text FROM fitness.users WHERE id=?", String.class, user))
                .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("SELECT expo_token FROM fitness.push_devices WHERE user_id=?",
                String.class, user)).startsWith("revoked:");
        assertThat(jdbc.queryForObject("""
                SELECT revoke_reason FROM fitness.refresh_tokens WHERE user_id=?
                """, String.class, user)).isEqualTo("ACCOUNT_ERASURE");
        mvc.perform(post("/api/v1/auth/token-refreshes").contentType("application/json")
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isUnauthorized());
        byte[] wrongBytes = new byte[32];
        java.util.Arrays.fill(wrongBytes, (byte) 1);
        mvc.perform(get("/api/v1/account-erasure-requests/{id}", request)
                        .header("X-Erasure-Status-Key", Base64.getUrlEncoder().withoutPadding().encodeToString(wrongBytes)))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/account-erasure-requests/{id}", request)
                        .header("X-Erasure-Status-Key", statusKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.backupState").value("BACKUP_UNVERIFIED"));
        assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request)).isEqualTo("DOMAIN_REVIEW_PENDING");
        assertThat(jdbc.queryForObject("SELECT email::text FROM fitness.users WHERE id=?",
                String.class, user)).startsWith("erased-");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.audit_logs WHERE actor_user_id=? OR target_id=?",
                Integer.class, user, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.security_events WHERE user_id=?",
                Integer.class, user)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.account_erasure_audit_redactions WHERE request_id=?
                """, Integer.class, request)).isGreaterThanOrEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT prior_sha256 FROM fitness.account_erasure_audit_redactions
                WHERE request_id=? AND source_table='audit_logs' AND source_id=?
                """, String.class, request, auditId.toString()).trim()).isEqualTo(priorAuditHash);
        assertThat(jdbc.queryForObject("SELECT before_data FROM fitness.audit_logs WHERE id=?",
                String.class, auditId)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.security_events
                WHERE user_id IS NULL AND event_type='LOGIN_SUCCESS' AND details<>'{}'::jsonb
                """, Integer.class)).isZero();
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE fitness.audit_logs SET metadata='{}'::jsonb WHERE action='PROFILE_EDIT' AND target_type='USER'
                """)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.account_erasure_events WHERE request_id=?",
                Integer.class, request)).isGreaterThanOrEqualTo(2);
    }

    @Test void objectFailureRemainsRetryableAndTwoWorkersCannotClaimSameRequest() throws Exception {
        UUID user = UUID.randomUUID(), media = UUID.randomUUID(), command = UUID.randomUUID();
        String statusKey = newStatusKey();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                VALUES (?,?,'hash','Student','ACTIVE')
                """, user, user + "@example.com");
        String response = mvc.perform(post("/api/v1/account-erasure-requests")
                        .with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType("application/json")
                        .content("{\"commandKey\":\"" + command + "\",\"statusKey\":\"" + statusKey + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID request = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(response).get("id").asText());
        jdbc.update("""
                INSERT INTO fitness.media_files(id,owner_user_id,storage_provider,bucket_name,object_key,
                   content_type,size_bytes,media_purpose,visibility,scan_status,deleted_at)
                VALUES (?,?,'S3','disposable','erasure/test','image/png',1,'CHAT_ATTACHMENT','PRIVATE','CLEAN',clock_timestamp())
                """, media, user);
        jdbc.update("""
                INSERT INTO fitness.account_erasure_objects(request_id,media_id,bucket_name,object_key)
                VALUES (?,?,'disposable','erasure/test')
                """, request, media);
        doThrow(new IllegalStateException("disposable S3 unavailable"))
                .doNothing().when(storage).deleteAndVerify("disposable", "erasure/test");
        assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request)).isEqualTo("RETRYABLE");
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_objects WHERE request_id=?",
                String.class, request)).isEqualTo("RETRYABLE");
        jdbc.update("UPDATE fitness.account_erasure_requests SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=?", request);
        jdbc.update("UPDATE fitness.account_erasure_objects SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE request_id=?", request);
        CountDownLatch inS3 = new CountDownLatch(1), releaseS3 = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            inS3.countDown();
            assertThat(releaseS3.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(storage).deleteAndVerify("disposable", "erasure/test");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var winner = pool.submit(worker::processOne);
            assertThat(inS3.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.processOne()).isFalse();
            releaseS3.countDown();
            assertThat(winner.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_objects WHERE request_id=?",
                String.class, request)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request)).isEqualTo("DOMAIN_REVIEW_PENDING");
    }

    @Test void unverifiedStorageTopologyNeverMarksObjectVerifiedOrAccountCleaned() throws Exception {
        UUID user = UUID.randomUUID(), media = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                VALUES (?,?,'hash','Student','ACTIVE')
                """, user, user + "@example.com");
        String response = mvc.perform(post("/api/v1/account-erasure-requests")
                        .with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType("application/json")
                        .content("{\"commandKey\":\"" + UUID.randomUUID()
                                + "\",\"statusKey\":\"" + newStatusKey() + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID request = UUID.fromString(new tools.jackson.databind.ObjectMapper()
                .readTree(response).get("id").asText());
        jdbc.update("""
                INSERT INTO fitness.media_files(id,owner_user_id,storage_provider,bucket_name,object_key,
                    content_type,size_bytes,media_purpose,visibility,scan_status,deleted_at)
                VALUES (?,?,'S3','disposable','erasure/topology','image/png',1,
                    'CHAT_ATTACHMENT','PRIVATE','CLEAN',clock_timestamp())
                """, media, user);
        jdbc.update("""
                INSERT INTO fitness.account_erasure_objects(request_id,media_id,bucket_name,object_key)
                VALUES (?,?,'disposable','erasure/topology')
                """, request, media);
        doThrow(new ChatFailure("CHAT_ATTACHMENT_ERASURE_TOPOLOGY_UNVERIFIED", 503))
                .when(storage).deleteAndVerify("disposable", "erasure/topology");

        assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.account_erasure_objects WHERE request_id=?
                """, String.class, request)).isEqualTo("STORAGE_REVIEW_REQUIRED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.account_erasure_requests WHERE id=?
                """, String.class, request)).isEqualTo("DOMAIN_REVIEW_PENDING");
        assertThat(jdbc.queryForObject("""
                SELECT active_cleaned_at FROM fitness.account_erasure_requests WHERE id=?
                """, java.sql.Timestamp.class, request)).isNull();
    }

    @Test void existingHoldDefersAllCleanupAndReleaseCanResumeWithoutChangingAccountCutoff() throws Exception {
        UUID user = UUID.randomUUID();
        String statusKey = newStatusKey();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                VALUES (?,?,'hash','Held Student','ACTIVE')
                """, user, user + "@example.com");
        String response = mvc.perform(post("/api/v1/account-erasure-requests")
                        .with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType("application/json")
                        .content("{\"commandKey\":\"" + UUID.randomUUID()
                                + "\",\"statusKey\":\"" + statusKey + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID request = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(response).get("id").asText());
        UUID hold = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.account_erasure_holds(id,request_id,case_id,legal_basis,scope,
                    approved_by,next_review_at)
                VALUES (?,?,'test-case','test evidence','ALL',?,clock_timestamp()+interval '29 days')
                """, hold, request, user);
        assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("SELECT display_name FROM fitness.users WHERE id=?",
                String.class, user)).isEqualTo("Held Student");
        mvc.perform(get("/api/v1/account-erasure-requests/{id}", request)
                        .header("X-Erasure-Status-Key", statusKey))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("HELD"));
        // Test-only DB release: there is intentionally no product endpoint or ADMIN permission yet.
        jdbc.update("""
                UPDATE fitness.account_erasure_holds SET released_at=clock_timestamp(),released_by=? WHERE id=?
                """, user, hold);
        assertThat(worker.processOne()).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.account_erasure_requests WHERE id=?",
                String.class, request)).isEqualTo("DOMAIN_REVIEW_PENDING");
    }

    @Test void holdCommittedDuringLastObjectDeleteStopsFinalMinimization() throws Exception {
        UUID user = UUID.randomUUID(), media = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                VALUES (?,?,'hash','Held Student','ACTIVE')
                """, user, user + "@example.com");
        String response = mvc.perform(post("/api/v1/account-erasure-requests")
                        .with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType("application/json")
                        .content("{\"commandKey\":\"" + UUID.randomUUID()
                                + "\",\"statusKey\":\"" + newStatusKey() + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID request = UUID.fromString(new tools.jackson.databind.ObjectMapper()
                .readTree(response).get("id").asText());
        jdbc.update("""
                INSERT INTO fitness.media_files(id,owner_user_id,storage_provider,bucket_name,object_key,
                    content_type,size_bytes,media_purpose,visibility,scan_status,deleted_at)
                VALUES (?,?,'S3','disposable','erasure/hold-race','image/png',1,
                    'CHAT_ATTACHMENT','PRIVATE','CLEAN',clock_timestamp())
                """, media, user);
        jdbc.update("""
                INSERT INTO fitness.account_erasure_objects(request_id,media_id,bucket_name,object_key)
                VALUES (?,?,'disposable','erasure/hold-race')
                """, request, media);
        // This is the request's only object: after S3 returns there is no next
        // object to claim, so the worker must check the hold before minimization.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.account_erasure_objects WHERE request_id=?
                """, Integer.class, request)).isEqualTo(1);
        CountDownLatch inS3 = new CountDownLatch(1), releaseS3 = new CountDownLatch(1);
        doAnswer(invocation -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            inS3.countDown();
            assertThat(releaseS3.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(storage).deleteAndVerify("disposable", "erasure/hold-race");
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var running = pool.submit(worker::processOne);
            assertThat(inS3.await(10, TimeUnit.SECONDS)).isTrue();
            jdbc.update("""
                    INSERT INTO fitness.account_erasure_holds(request_id,case_id,legal_basis,scope,
                        approved_by,next_review_at)
                    VALUES (?,'test-mid-call','test evidence','ALL',?,clock_timestamp()+interval '29 days')
                    """, request, user);
            assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM fitness.account_erasure_holds
                    WHERE request_id=? AND released_at IS NULL
                    """, Integer.class, request)).isEqualTo(1);
            assertThat(jdbc.queryForObject("""
                    SELECT status FROM fitness.account_erasure_objects WHERE request_id=?
                    """, String.class, request)).isEqualTo("CLAIMED");
            releaseS3.countDown();
            assertThat(running.get(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.account_erasure_requests WHERE id=?
                """, String.class, request)).isEqualTo("HELD");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.account_erasure_objects WHERE request_id=?
                """, String.class, request)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("""
                SELECT display_name FROM fitness.users WHERE id=?
                """, String.class, user)).isEqualTo("Held Student");
        assertThat(jdbc.queryForObject("""
                SELECT active_cleaned_at FROM fitness.account_erasure_requests WHERE id=?
                """, java.sql.Timestamp.class, request)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.account_erasure_events
                WHERE request_id=? AND event_type='ACCOUNT_CHAT_NOTIFICATION_MINIMIZED'
                """, Integer.class, request)).isZero();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.account_erasure_events
                WHERE request_id=? AND event_type='OBJECT_VERIFIED'
                """, Integer.class, request)).isEqualTo(1);
    }

    @Test void expiredWorkerAndObjectClaimsRecoverWithoutTreatingTheObjectAsDeleted() throws Exception {
        UUID user = UUID.randomUUID(), media = UUID.randomUUID();
        String statusKey = newStatusKey();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                VALUES (?,?,'hash','Student','ACTIVE')
                """, user, user + "@example.com");
        String response = mvc.perform(post("/api/v1/account-erasure-requests")
                        .with(jwt().jwt(j -> j.subject(user.toString())))
                        .contentType("application/json")
                        .content("{\"commandKey\":\"" + UUID.randomUUID()
                                + "\",\"statusKey\":\"" + statusKey + "\"}"))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString();
        UUID request = UUID.fromString(new tools.jackson.databind.ObjectMapper().readTree(response).get("id").asText());
        jdbc.update("""
                INSERT INTO fitness.media_files(id,owner_user_id,storage_provider,bucket_name,object_key,
                   content_type,size_bytes,media_purpose,visibility,scan_status,deleted_at)
                VALUES (?,?,'S3','disposable','erasure/crashed','image/png',1,'CHAT_ATTACHMENT','PRIVATE','CLEAN',clock_timestamp())
                """, media, user);
        jdbc.update("""
                INSERT INTO fitness.account_erasure_objects(request_id,media_id,bucket_name,object_key,
                    status,claim_token,lease_expires_at,attempt_count)
                VALUES (?,?,'disposable','erasure/crashed','CLAIMED',?,clock_timestamp()-interval '1 second',1)
                """, request, media, UUID.randomUUID());
        jdbc.update("""
                UPDATE fitness.account_erasure_requests SET status='PROCESSING',lease_token=?,
                    lease_expires_at=clock_timestamp()-interval '1 second',attempt_count=1 WHERE id=?
                """, UUID.randomUUID(), request);

        assertThat(worker.processOne()).isTrue();
        org.mockito.Mockito.verify(storage).deleteAndVerify("disposable", "erasure/crashed");
        assertThat(jdbc.queryForObject("""
                SELECT attempt_count FROM fitness.account_erasure_objects WHERE request_id=?
                """, Integer.class, request)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.account_erasure_objects WHERE request_id=?
                """, String.class, request)).isEqualTo("VERIFIED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.account_erasure_requests WHERE id=?
                """, String.class, request)).isEqualTo("DOMAIN_REVIEW_PENDING");
    }

    private static String newStatusKey() {
        byte[] value = new byte[32];
        new java.security.SecureRandom().nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }
}
