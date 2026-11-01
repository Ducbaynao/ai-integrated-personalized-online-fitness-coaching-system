package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.chat.ChatService;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.notification.ExpoPushGateway;
import com.fitnesscoaching.platform.modules.notification.PushDeliveryWorker;
import com.fitnesscoaching.platform.modules.notification.PushPreferenceService;
import com.fitnesscoaching.platform.modules.notification.ReminderPreferenceService;
import com.fitnesscoaching.platform.modules.notification.TimedReminderService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

@SpringBootTest
@AutoConfigureMockMvc
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
    @Autowired ReminderPreferenceService reminderPreferences;
    @Autowired TimedReminderService reminders;
    @Autowired MockMvc mvc;
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

    @Test void inAppReminderPreferenceHttpIsOffByDefaultAndIndependentFromPush() throws Exception {
        UUID student = user("Reminder HTTP Student");
        mvc.perform(get("/api/v1/notifications/me/reminder-preferences")
                .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(put("/api/v1/notifications/me/reminder-preferences/APPOINTMENT_REMINDER")
                .with(jwt().jwt(j -> j.subject(student.toString())))
                .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk()).andExpect(jsonPath("$.enabled").value(true));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notification_preferences
                WHERE user_id=? AND event_type='APPOINTMENT_REMINDER' AND channel='PUSH'
                """, Integer.class, student)).isZero();
        mvc.perform(put("/api/v1/notifications/me/reminder-preferences/UNKNOWN")
                .with(jwt().jwt(j -> j.subject(student.toString())))
                .contentType("application/json").content("{\"enabled\":true}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        mvc.perform(put("/api/v1/notifications/me/reminder-preferences/WORKOUT_REMINDER")
                .with(jwt().jwt(j -> j.subject(student.toString())))
                .contentType("application/json").content("{}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isBadRequest());
        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", student);
        mvc.perform(get("/api/v1/notifications/me/reminder-preferences")
                .with(jwt().jwt(j -> j.subject(student.toString()))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
    }

    @Test void appointmentAndWorkoutRemindersRequireIndependentOptInsAndDeduplicate() {
        Pair pair = pair("reminder");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        UUID workout = workout(pair.student(), databaseNow().plusSeconds(31 * 60));
        reminders.dispatchDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.timed_reminders WHERE source_id IN (?,?)",
                Integer.class, appointment, workout)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE event_type LIKE '%_REMINDER'",
                Integer.class)).isZero();

        reminderPreferences.set(pair.student(), "APPOINTMENT_REMINDER", true);
        reminderPreferences.set(pair.student(), "WORKOUT_REMINDER", true);
        registerReminderPush(pair.trainer(), "reminder", "APPOINTMENT_REMINDER", null, null);
        // New schedule versions are separate identities; old unconsented versions stay skipped.
        Instant movedStart = databaseNow().plusSeconds(40 * 60);
        jdbc.update("""
                UPDATE fitness.appointments SET start_at=?,end_at=?,local_start=?,local_end=?,
                  version=version+1 WHERE id=?
                """, Timestamp.from(movedStart), Timestamp.from(movedStart.plusSeconds(3600)),
                LocalDateTime.ofInstant(movedStart, ZoneOffset.UTC),
                LocalDateTime.ofInstant(movedStart.plusSeconds(3600), ZoneOffset.UTC), appointment);
        jdbc.update("UPDATE fitness.planned_workouts SET version=version+1 WHERE id=?", workout);
        reminders.dispatchDue();
        reminders.dispatchDue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notifications
                WHERE event_type='APPOINTMENT_REMINDER' AND recipient_user_id=? AND inbox_visible=true
                """, Integer.class, pair.student())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notifications
                WHERE event_type='APPOINTMENT_REMINDER' AND recipient_user_id=? AND inbox_visible=false
                """, Integer.class, pair.trainer())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notifications
                WHERE event_type='WORKOUT_REMINDER' AND recipient_user_id=? AND inbox_visible=true
                """, Integer.class, pair.student())).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notifications
                WHERE event_type='WORKOUT_REMINDER' AND recipient_user_id=?
                """, Integer.class, pair.trainer())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.push_delivery_attempts WHERE category='APPOINTMENT_REMINDER'",
                Integer.class)).isEqualTo(1);
        // This class shares one disposable database across methods; do not let this
        // intentionally unsent push enter unrelated worker tests with an unstubbed provider.
        jdbc.update("UPDATE fitness.push_delivery_attempts SET status='SKIPPED' WHERE category='APPOINTMENT_REMINDER'");
    }

    @Test void cancelledAppointmentAndCoachRequiredWorkoutAreNeverReminded() {
        Pair pair = pair("invalid");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        UUID workout = workout(pair.student(), databaseNow().plusSeconds(31 * 60));
        reminderPreferences.set(pair.student(), "APPOINTMENT_REMINDER", true);
        reminderPreferences.set(pair.student(), "WORKOUT_REMINDER", true);
        jdbc.update("UPDATE fitness.appointments SET status='CANCELLED',version=version+1 WHERE id=?", appointment);
        jdbc.update("UPDATE fitness.planned_workouts SET supervision_requirement='COACH_REQUIRED',version=version+1 WHERE id=?", workout);
        reminders.dispatchDue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.timed_reminders WHERE source_id IN (?,?)",
                Integer.class, appointment, workout)).isZero();
    }

    @Test void appointmentMutationInvalidatesQueuedPushAndNewVersionCanBeReminded() {
        Pair pair = pair("mutation");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        registerReminderPush(pair.trainer(), "mutation", "APPOINTMENT_REMINDER", null, null);
        reminders.dispatchDue();
        UUID old = jdbc.queryForObject("""
                SELECT notification_id FROM fitness.timed_reminders
                WHERE source_id=? AND recipient_user_id=? AND channel='PUSH'
                """, UUID.class, appointment, pair.trainer());
        assertThat(status(old)).isEqualTo("QUEUED");
        Instant movedStart = databaseNow().plusSeconds(40 * 60);
        jdbc.update("""
                UPDATE fitness.appointments SET start_at=?,end_at=?,local_start=?,local_end=?,
                  version=version+1 WHERE id=?
                """, Timestamp.from(movedStart), Timestamp.from(movedStart.plusSeconds(3600)),
                LocalDateTime.ofInstant(movedStart, ZoneOffset.UTC),
                LocalDateTime.ofInstant(movedStart.plusSeconds(3600), ZoneOffset.UTC), appointment);
        assertThat(status(old)).isEqualTo("SKIPPED");
        reminders.dispatchDue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.timed_reminders WHERE source_id=? AND recipient_user_id=?
                  AND channel='PUSH' AND status='EMITTED'
                """, Integer.class, appointment, pair.trainer())).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.push_delivery_attempts a
                JOIN fitness.timed_reminders r ON r.notification_id=a.notification_id
                WHERE r.source_id=? AND a.status='QUEUED'
                """, Integer.class, appointment)).isEqualTo(1);
        jdbc.update("UPDATE fitness.appointments SET status='CANCELLED',version=version+1 WHERE id=?", appointment);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.push_delivery_attempts a
                JOIN fitness.timed_reminders r ON r.notification_id=a.notification_id
                WHERE r.source_id=? AND a.status='QUEUED'
                """, Integer.class, appointment)).isZero();
    }

    @Test void quietHoursCannotDelayReminderPastAppointmentStart() {
        Pair pair = pair("quiet-reminder");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        LocalTime local = databaseNow().atZone(ZoneOffset.UTC).toLocalTime();
        registerReminderPush(pair.trainer(), "quiet-reminder", "APPOINTMENT_REMINDER",
                local.minusHours(1), local.plusHours(2));
        reminders.dispatchDue();
        UUID notice = jdbc.queryForObject("""
                SELECT notification_id FROM fitness.timed_reminders
                WHERE source_id=? AND recipient_user_id=? AND channel='PUSH'
                """, UUID.class, appointment, pair.trainer());
        // Simulate a due claim to exercise the final quiet-hours/start guard without clock sleeps.
        jdbc.update("UPDATE fitness.push_delivery_attempts SET next_attempt_at=clock_timestamp() WHERE notification_id=?",
                notice);
        worker.dispatch();
        assertThat(status(notice)).isEqualTo("SKIPPED");
        verify(expo, never()).send("ExpoPushToken[quiet-reminder]", notice);
    }

    @Test void disabledRecipientIsNotSentAQueuedReminder() {
        Pair pair = pair("disabled-reminder");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        registerReminderPush(pair.trainer(), "disabled-reminder", "APPOINTMENT_REMINDER", null, null);
        reminders.dispatchDue();
        UUID notice = jdbc.queryForObject("""
                SELECT notification_id FROM fitness.timed_reminders
                WHERE source_id=? AND recipient_user_id=? AND channel='PUSH'
                """, UUID.class, appointment, pair.trainer());
        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?", pair.trainer());
        worker.dispatch();
        assertThat(status(notice)).isEqualTo("SKIPPED");
        verify(expo, never()).send("ExpoPushToken[disabled-reminder]", notice);
    }

    @Test void twoMaterializersCreateOneReminderPerIdentity() throws Exception {
        Pair pair = pair("two-reminder-workers");
        UUID workout = workout(pair.student(), databaseNow().plusSeconds(30 * 60));
        reminderPreferences.set(pair.student(), "WORKOUT_REMINDER", true);
        CountDownLatch ready = new CountDownLatch(2), release = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var first = threads.submit(() -> {
                ready.countDown(); release.await(); reminders.dispatchDue(); return null;
            });
            var second = threads.submit(() -> {
                ready.countDown(); release.await(); reminders.dispatchDue(); return null;
            });
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            release.countDown();
            first.get(15, TimeUnit.SECONDS);
            second.get(15, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.timed_reminders
                WHERE source_type='PLANNED_WORKOUT' AND source_id=? AND channel='IN_APP'
                """, Integer.class, workout)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notifications
                WHERE related_entity_type='PLANNED_WORKOUT' AND related_entity_id=?
                  AND event_type='WORKOUT_REMINDER' AND inbox_visible=true
                """, Integer.class, workout)).isEqualTo(1);
    }

    @Test void pausedPeriodConcealsTrainerReminderAndSkipsQueuedPush() {
        Pair pair = pair("paused-reminder");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        registerReminderPush(pair.trainer(), "paused-reminder", "APPOINTMENT_REMINDER", null, null);
        reminders.dispatchDue();
        UUID notice = jdbc.queryForObject("""
                SELECT notification_id FROM fitness.timed_reminders
                WHERE source_id=? AND recipient_user_id=? AND channel='PUSH'
                """, UUID.class, appointment, pair.trainer());
        UUID relationship = jdbc.queryForObject("""
                SELECT id FROM fitness.coaching_relationships
                WHERE student_id=? AND trainer_id=? ORDER BY created_at DESC LIMIT 1
                """, UUID.class, pair.student(), pair.trainer());
        lifecycle.relationshipAction(pair.student(), relationship, "PAUSE", 1, null, UUID.randomUUID());
        worker.dispatch();
        assertThat(status(notice)).isEqualTo("SKIPPED");
        verify(expo, never()).send("ExpoPushToken[paused-reminder]", notice);
    }

    @Test void studentWorkoutReminderDoesNotRequireAnActiveTrainerPeriod() {
        Pair pair = pair("independent-workout-reminder");
        UUID workout = workout(pair.student(), databaseNow().plusSeconds(30 * 60));
        reminderPreferences.set(pair.student(), "WORKOUT_REMINDER", true);
        UUID relationship = jdbc.queryForObject("""
                SELECT id FROM fitness.coaching_relationships
                WHERE student_id=? AND trainer_id=? ORDER BY created_at DESC LIMIT 1
                """, UUID.class, pair.student(), pair.trainer());
        lifecycle.relationshipAction(pair.student(), relationship, "PAUSE", 1, null, UUID.randomUUID());
        reminders.dispatchDue();
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notifications
                WHERE recipient_user_id=? AND related_entity_type='PLANNED_WORKOUT'
                  AND related_entity_id=? AND event_type='WORKOUT_REMINDER' AND inbox_visible=true
                """, Integer.class, pair.student(), workout)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.notifications
                WHERE recipient_user_id=? AND related_entity_id=? AND event_type='WORKOUT_REMINDER'
                """, Integer.class, pair.trainer(), workout)).isZero();
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
        // Opt-out prevents future sends, but cannot retract a provider call already in flight.
        assertThat(status(due)).isEqualTo("TICKET_ACCEPTED");
        assertThat(jdbc.queryForObject("""
                SELECT status FROM fitness.notification_deliveries
                WHERE notification_id=? AND channel='PUSH'
                """, String.class, due)).isEqualTo("SENT");
    }

    @Test void cancelledAppointmentWhileExpoIsSendingKeepsAcceptedTicketAndReceipt() throws Exception {
        Pair pair = pair("cancel-in-flight");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        registerReminderPush(pair.trainer(), "cancel-in-flight", "APPOINTMENT_REMINDER", null, null);
        reminders.dispatchDue();
        UUID notice = reminderNotice(appointment, pair.trainer());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(expo.send("ExpoPushToken[cancel-in-flight]", notice)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
            return new ExpoPushGateway.Result(true, "cancelled-source-ticket", null);
        });
        try (var threads = Executors.newSingleThreadExecutor()) {
            var sending = threads.submit(worker::dispatch);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(status(notice)).isEqualTo("SENDING");
            // A separate committed source transaction fires the V54 invalidation trigger.
            jdbc.update("UPDATE fitness.appointments SET status='CANCELLED',version=version+1 WHERE id=?", appointment);
            release.countDown();
            sending.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(expo, times(1)).send("ExpoPushToken[cancel-in-flight]", notice);
        assertThat(status(notice)).isEqualTo("TICKET_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT expo_ticket_id FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("cancelled-source-ticket");
        assertThat(jdbc.queryForObject("SELECT status FROM fitness.notification_deliveries WHERE notification_id=? AND channel='PUSH'",
                String.class, notice)).isEqualTo("SENT");
        jdbc.update("UPDATE fitness.push_delivery_attempts SET next_attempt_at=clock_timestamp() WHERE notification_id=?", notice);
        when(expo.receipt("cancelled-source-ticket"))
                .thenReturn(new ExpoPushGateway.Result(true, "cancelled-source-ticket", null));
        worker.inspectReceipts();
        assertThat(status(notice)).isEqualTo("PROVIDER_ACCEPTED");
        assertThat(jdbc.queryForObject("SELECT device_acknowledged_at FROM fitness.push_delivery_attempts WHERE notification_id=?",
                Timestamp.class, notice)).isNull();
        reminders.dispatchDue();
        worker.dispatch();
        verify(expo, times(1)).send("ExpoPushToken[cancel-in-flight]", notice);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.timed_reminders WHERE source_id=? AND status='EMITTED'",
                Integer.class, appointment)).isEqualTo(1);
    }

    @Test void optOutWhileExpoIsSendingKeepsUnknownOutcomeWithoutRetry() throws Exception {
        Pair pair = pair("opt-out-in-flight");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        registerReminderPush(pair.trainer(), "opt-out-in-flight", "APPOINTMENT_REMINDER", null, null);
        reminders.dispatchDue();
        UUID notice = reminderNotice(appointment, pair.trainer());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(expo.send("ExpoPushToken[opt-out-in-flight]", notice)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
            return new ExpoPushGateway.Result(false, null, "TICKET_OUTCOME_UNKNOWN");
        });
        try (var threads = Executors.newSingleThreadExecutor()) {
            var sending = threads.submit(worker::dispatch);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            preferences.set(pair.trainer(), "APPOINTMENT_REMINDER", false, "Etc/UTC", null, null);
            release.countDown();
            sending.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        worker.dispatch();
        verify(expo, times(1)).send("ExpoPushToken[opt-out-in-flight]", notice);
        assertThat(status(notice)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_error FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("TICKET_OUTCOME_UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT expo_ticket_id FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isNull();
    }

    @Test void periodPauseWhileExpoIsSendingKeepsProviderFailureAndConcealsDeepLink() throws Exception {
        Pair pair = pair("pause-in-flight");
        UUID appointment = appointment(pair, databaseNow().plusSeconds(30 * 60));
        registerReminderPush(pair.trainer(), "pause-in-flight", "APPOINTMENT_REMINDER", null, null);
        reminders.dispatchDue();
        UUID notice = reminderNotice(appointment, pair.trainer());
        UUID relationship = jdbc.queryForObject("""
                SELECT id FROM fitness.coaching_relationships
                WHERE student_id=? AND trainer_id=? ORDER BY created_at DESC LIMIT 1
                """, UUID.class, pair.student(), pair.trainer());
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        when(expo.send("ExpoPushToken[pause-in-flight]", notice)).thenAnswer(invocation -> {
            entered.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timed out");
            return new ExpoPushGateway.Result(false, null, "TICKET_REJECTED");
        });
        try (var threads = Executors.newSingleThreadExecutor()) {
            var sending = threads.submit(worker::dispatch);
            assertThat(entered.await(10, TimeUnit.SECONDS)).isTrue();
            lifecycle.relationshipAction(pair.student(), relationship, "PAUSE", 1, null, UUID.randomUUID());
            mvc.perform(get("/api/v1/appointments/" + appointment)
                    .with(jwt().jwt(j -> j.subject(pair.trainer().toString()))))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
            release.countDown();
            sending.get(10, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        verify(expo, times(1)).send("ExpoPushToken[pause-in-flight]", notice);
        assertThat(status(notice)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_error FROM fitness.push_delivery_attempts WHERE notification_id=?",
                String.class, notice)).isEqualTo("TICKET_REJECTED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notification_deliveries WHERE notification_id=? AND channel='PUSH' AND status='SENT'",
                Integer.class, notice)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.timed_reminders WHERE source_id=? AND status='EMITTED'",
                Integer.class, appointment)).isEqualTo(1);
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

    private void registerReminderPush(UUID actor, String suffix, String category, LocalTime start, LocalTime end) {
        jdbc.update("INSERT INTO fitness.push_devices(user_id,device_key,expo_token) VALUES (?,?,?)",
                actor, UUID.randomUUID(), "ExpoPushToken[" + suffix + "]");
        jdbc.update("""
                INSERT INTO fitness.notification_preferences
                  (user_id,event_type,channel,enabled,timezone,quiet_hours_start,quiet_hours_end)
                VALUES (?,?,'PUSH',true,'Etc/UTC',?,?)
                """, actor, category, start, end);
    }

    private UUID appointment(Pair pair, Instant start) {
        UUID id = UUID.randomUUID();
        UUID period = jdbc.queryForObject("""
                SELECT id FROM fitness.coaching_periods WHERE student_id=? AND trainer_id=?
                  AND ended_at IS NULL ORDER BY started_at DESC LIMIT 1
                """, UUID.class, pair.student(), pair.trainer());
        Instant end = start.plusSeconds(3600);
        jdbc.update("""
                INSERT INTO fitness.appointments
                  (id,student_id,trainer_id,coaching_period_id,start_at,end_at,display_timezone,
                   local_start,local_end,start_utc_offset,end_utc_offset,status,created_by,guarded_v2)
                VALUES (?,?,?,?,?,?, 'Etc/UTC',?,?,'+00:00','+00:00','CONFIRMED',?,true)
                """, id, pair.student(), pair.trainer(), period, Timestamp.from(start), Timestamp.from(end),
                LocalDateTime.ofInstant(start, ZoneOffset.UTC), LocalDateTime.ofInstant(end, ZoneOffset.UTC),
                pair.student());
        return id;
    }

    private UUID workout(UUID student, Instant start) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                  (id,student_id,planned_start_at,planned_end_at,original_planned_start_at,
                   status,supervision_requirement,created_by)
                VALUES (?,?,?,?,?,'SCHEDULED','SELF_ALLOWED',?)
                """, id, student, Timestamp.from(start), Timestamp.from(start.plusSeconds(3600)),
                Timestamp.from(start), student);
        return id;
    }

    private UUID notice(UUID message) {
        return jdbc.queryForObject("SELECT id FROM fitness.notifications WHERE related_entity_id=?",
                UUID.class, message);
    }

    private UUID reminderNotice(UUID source, UUID recipient) {
        return jdbc.queryForObject("""
                SELECT notification_id FROM fitness.timed_reminders
                WHERE source_id=? AND recipient_user_id=? AND channel='PUSH'
                """, UUID.class, source, recipient);
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
