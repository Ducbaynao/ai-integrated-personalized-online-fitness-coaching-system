package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.chat.ChatService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.notification.ExpoPushGateway;
import com.fitnesscoaching.platform.modules.notification.PushDeliveryWorker;
import com.fitnesscoaching.platform.modules.notification.PushPreferenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.scheduling.enabled=false", "push.expo.enabled=true"})
class PushDispatchIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("b08_push_worker_disposable").withUsername("fitness_app").withPassword("testpass123");
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
    @Autowired ChatService chat;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired PushDeliveryWorker worker;
    @Autowired PushPreferenceService preferences;
    @MockitoBean ExpoPushGateway expo;

    @Test void twentyQuietRowsCannotStarveAReadyRecipient() {
        Pair quiet = pair("quiet"), ready = pair("ready");
        Instant now = databaseNow();
        LocalTime local = now.atZone(ZoneOffset.UTC).toLocalTime();
        register(quiet.trainer(), "quiet", local.minusHours(2), local.plusHours(2));
        register(ready.trainer(), "ready", null, null);
        for (int n = 0; n < 20; n++) chat.send(quiet.student(), quiet.student(), "quiet " + n, UUID.randomUUID());
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.push_delivery_attempts a
                JOIN fitness.push_devices d ON d.id=a.device_id
                WHERE d.user_id=? AND a.status='QUEUED' AND a.next_attempt_at>clock_timestamp()
                """, Integer.class, quiet.trainer())).isEqualTo(20);
        UUID due = notice(chat.send(ready.student(), ready.student(), "due", UUID.randomUUID()).id());
        when(expo.send("ExpoPushToken[ready]", due))
                .thenReturn(new ExpoPushGateway.Result(true, "ticket-ready", null));
        worker.dispatch();
        verify(expo).send("ExpoPushToken[ready]", due);
        assertThat(status(due)).isEqualTo("TICKET_ACCEPTED");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.push_delivery_attempts a
                JOIN fitness.push_devices d ON d.id=a.device_id
                WHERE d.user_id=? AND a.status='QUEUED'
                """, Integer.class, quiet.trainer())).isEqualTo(20);
        preferences.set(quiet.trainer(), "CHAT", true, "Etc/UTC", null, null);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.push_delivery_attempts a
                JOIN fitness.push_devices d ON d.id=a.device_id
                WHERE d.user_id=? AND a.status='QUEUED' AND a.next_attempt_at<=clock_timestamp()
                """, Integer.class, quiet.trainer())).isEqualTo(20);
        preferences.set(quiet.trainer(), "CHAT", false, "Etc/UTC", null, null);
    }

    @Test void slowProviderHoldsNoDatabaseLockAndConcurrentWorkerDoesNotResendAfterOptOut() throws Exception {
        Pair pair = pair("slow");
        register(pair.trainer(), "slow", null, null);
        UUID due = notice(chat.send(pair.student(), pair.student(), "slow", UUID.randomUUID()).id());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(expo.send("ExpoPushToken[slow]", due)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
            return new ExpoPushGateway.Result(true, "too-late-ticket", null);
        });
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(worker::dispatch);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            var second = threads.submit(() -> {
                // If the provider call still held FOR UPDATE, the real opt-out command would block.
                preferences.set(pair.trainer(), "CHAT", false, "Etc/UTC", null, null);
                worker.dispatch();
            });
            second.get(5, TimeUnit.SECONDS);
            release.countDown();
            first.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(expo, times(1)).send("ExpoPushToken[slow]", due);
        assertThat(status(due)).isEqualTo("SKIPPED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.notification_deliveries
                WHERE notification_id=? AND channel='PUSH'
                """, String.class, due)).isEqualTo("PENDING");
    }

    @Test void expiredSendLeaseAndUnknownTicketAreNeverBlindlyResent() {
        Pair pair = pair("crashed");
        register(pair.trainer(), "crashed", null, null);
        UUID due = notice(chat.send(pair.student(), pair.student(), "crash", UUID.randomUUID()).id());
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status='SENDING',claim_token=?,
                  lease_expires_at=clock_timestamp()-interval '1 second',send_attempt_count=1
                WHERE notification_id=?
                """, UUID.randomUUID(), due);
        worker.dispatch();
        verify(expo, never()).send("ExpoPushToken[crashed]", due);
        assertThat(status(due)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_error FROM fitness.push_delivery_attempts "
                + "WHERE notification_id=?", String.class, due)).isEqualTo("TICKET_OUTCOME_UNKNOWN");
    }

    @Test void uncertainHttpTicketOutcomeIsTerminalAndNeverClaimsDeviceDelivery() {
        Pair pair = pair("unknown");
        register(pair.trainer(), "unknown", null, null);
        UUID due = notice(chat.send(pair.student(), pair.student(), "unknown", UUID.randomUUID()).id());
        when(expo.send("ExpoPushToken[unknown]", due))
                .thenReturn(new ExpoPushGateway.Result(false, null, "TICKET_OUTCOME_UNKNOWN"));
        worker.dispatch();
        worker.dispatch();
        verify(expo, times(1)).send("ExpoPushToken[unknown]", due);
        assertThat(status(due)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_accepted_at FROM fitness.push_delivery_attempts "
                + "WHERE notification_id=?", Timestamp.class, due)).isNull();
    }

    @Test void unavailableReceiptIsDeferredAndExpiredReceiptLeaseCanBeRetried() {
        Pair pair = pair("receipt");
        register(pair.trainer(), "receipt", null, null);
        UUID due = notice(chat.send(pair.student(), pair.student(), "receipt", UUID.randomUUID()).id());
        when(expo.send("ExpoPushToken[receipt]", due))
                .thenReturn(new ExpoPushGateway.Result(true, "receipt-ticket", null));
        worker.dispatch();
        jdbc.update("UPDATE fitness.push_delivery_attempts SET next_attempt_at=clock_timestamp() "
                + "WHERE notification_id=?", due);
        when(expo.receipt("receipt-ticket"))
                .thenReturn(new ExpoPushGateway.Result(false, null, "RECEIPT_UNAVAILABLE"))
                .thenReturn(new ExpoPushGateway.Result(true, "receipt-ticket", null));
        worker.inspectReceipts();
        assertThat(status(due)).isEqualTo("TICKET_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT receipt_attempt_count FROM fitness.push_delivery_attempts "
                + "WHERE notification_id=?", Integer.class, due)).isEqualTo(1);
        jdbc.update("""
                UPDATE fitness.push_delivery_attempts SET status='CHECKING_RECEIPT',claim_token=?,
                  lease_expires_at=clock_timestamp()-interval '1 second',receipt_attempt_count=2
                WHERE notification_id=?
                """, UUID.randomUUID(), due);
        worker.inspectReceipts();
        assertThat(status(due)).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT device_acknowledged_at FROM fitness.push_delivery_attempts "
                + "WHERE notification_id=?", Timestamp.class, due)).isNull();
    }

    private Pair pair(String suffix) {
        UUID student = user("Student " + suffix), trainer = user("Trainer " + suffix);
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", student);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                + "SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'", student, student);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                + "SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='TRAINER'", trainer, trainer);
        jdbc.update("INSERT INTO fitness.trainer_profiles "
                + "(user_id,verification_status,verified_at,is_accepting_students,is_active) "
                + "VALUES (?,'VERIFIED',clock_timestamp(),true,true)", trainer);
        var pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT", 0, null, UUID.randomUUID());
        return new Pair(student, trainer);
    }

    private UUID user(String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                + "VALUES (?,?,'hash',?,'ACTIVE','vi-VN','Etc/UTC')", id, id + "@example.com", name);
        return id;
    }

    private void register(UUID trainer, String suffix, LocalTime quietStart, LocalTime quietEnd) {
        jdbc.update("INSERT INTO fitness.push_devices(user_id,device_key,expo_token) VALUES (?,?,?)",
                trainer, UUID.randomUUID(), "ExpoPushToken[" + suffix + "]");
        jdbc.update("""
                INSERT INTO fitness.notification_preferences
                  (user_id,event_type,channel,enabled,timezone,quiet_hours_start,quiet_hours_end)
                VALUES (?,'CHAT','PUSH',true,'Etc/UTC',?,?)
                """, trainer, quietStart, quietEnd);
    }

    private UUID notice(UUID message) {
        return jdbc.queryForObject("SELECT id FROM fitness.notifications WHERE related_entity_id=?",
                UUID.class, message);
    }

    private String status(UUID notice) {
        return jdbc.queryForObject("SELECT status FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice);
    }

    private Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", (rs, row) -> rs.getTimestamp(1).toInstant());
    }

    private record Pair(UUID student, UUID trainer) {}
}
