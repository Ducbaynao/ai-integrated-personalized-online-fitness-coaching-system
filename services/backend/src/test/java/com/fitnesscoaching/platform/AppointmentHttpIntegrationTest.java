package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentFailure;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentService;
import com.fitnesscoaching.platform.modules.schedule.application.service.AppointmentService.*;
import com.fitnesscoaching.platform.modules.notification.InAppNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.Timestamp;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppointmentHttpIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("appointment_http_disposable").withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
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
    @Autowired MockMvc mvc;
    @Autowired AppointmentService appointments;
    @Autowired InAppNotificationService notifications;
    @Autowired CoachingLifecycleUseCase lifecycle;

    @Test void studentRequestAndTrainerAcceptCommitOneAppointmentAndReplay() throws Exception {
        Fixture f = fixture();
        UUID key = UUID.randomUUID();
        LocalInterval interval = interval(10, 11);
        RequestCommand command = new RequestCommand(f.student(), f.student(), null, interval, key);
        var created = appointments.request(command);
        assertThat(created.request().status()).isEqualTo("PENDING");
        assertThat(appointments.request(command).replayed()).isTrue();
        var pendingNotice = notifications.list(f.trainer(), 0, 20).items().stream()
                .filter(item -> "APPOINTMENT_REQUEST_PENDING".equals(item.eventType()))
                .findFirst().orElseThrow();
        assertThat(notifications.markRead(f.trainer(), pendingNotice.id()).readAt()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointment_requests_v2 WHERE student_id=?",
                Integer.class, f.student())).isOne();
        var accepted = appointments.decide(new DecisionCommand(f.trainer(), created.request().requestId(),
                "ACCEPT", 0, UUID.randomUUID()));
        assertThat(accepted.request().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.request().version()).isOne();
        assertThat(accepted.appointment().status()).isEqualTo("CONFIRMED");
        assertThat(accepted.appointment().coachingPeriodId()).isEqualTo(f.period());
        assertThatThrownBy(() -> jdbc.update("UPDATE fitness.appointments SET guarded_v2=false WHERE id=?",
                accepted.appointment().appointmentId()))
                .hasMessageContaining("guarded Appointment cannot drop the V45 guard");
        assertThat(notifications.list(f.student(), 0, 20).items())
                .anySatisfy(item -> assertThat(item.eventType()).isEqualTo("APPOINTMENT_REQUEST_ACCEPTED"));
        assertThat(appointments.detail(f.student(), created.request().requestId()).status()).isEqualTo("ACCEPTED");
        mvc.perform(get("/api/v1/appointment-requests/{id}", created.request().requestId())
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(created.request().requestId().toString()));
    }

    @Test void httpCreateAndRejectReturnContractShapeWithoutAppointment() throws Exception {
        Fixture f = fixture();
        LocalDate day = futureDate();
        String body = """
                {"localStart":"%sT10:00:00","localEnd":"%sT11:00:00", "timezone":"Etc/UTC",
                 "startUtcOffset":"+00:00","endUtcOffset":"+00:00","commandKey":"%s"}
                """.formatted(day, day, UUID.randomUUID());
        var response = mvc.perform(post("/api/v1/students/{studentId}/appointment-requests", f.student())
                        .with(jwt().jwt(token -> token.subject(f.student().toString())))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.request.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        UUID requestId = UUID.fromString(response.split("\\\"requestId\\\":\\\"")[1].split("\\\"")[0]);
        mvc.perform(post("/api/v1/appointment-requests/{requestId}/decisions", requestId)
                        .with(jwt().jwt(token -> token.subject(f.trainer().toString())))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"REJECT\",\"expectedRequestVersion\":0,\"commandKey\":\""
                                + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.request.status").value("REJECTED"))
                .andExpect(jsonPath("$.appointment").value(org.hamcrest.Matchers.nullValue()));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE student_id=?",
                Integer.class, f.student())).isZero();
    }

    @Test void foreignActorCannotReadOrDecideAndStaleDecisionDoesNotCreateAppointment() {
        Fixture f = fixture(), outsider = fixture();
        var created = appointments.request(new RequestCommand(f.student(), f.student(), null,
                interval(12, 13), UUID.randomUUID()));
        assertThatThrownBy(() -> appointments.detail(outsider.trainer(), created.request().requestId()))
                .isInstanceOfSatisfying(AppointmentFailure.class,
                        failure -> assertThat(failure.status()).isEqualTo(404));
        assertThatThrownBy(() -> appointments.decide(new DecisionCommand(outsider.trainer(),
                created.request().requestId(), "ACCEPT", 0, UUID.randomUUID())))
                .isInstanceOfSatisfying(AppointmentFailure.class,
                        failure -> assertThat(failure.status()).isEqualTo(404));
        assertThatThrownBy(() -> appointments.decide(new DecisionCommand(f.trainer(),
                created.request().requestId(), "ACCEPT", 2, UUID.randomUUID())))
                .isInstanceOfSatisfying(AppointmentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("APPOINTMENT_REQUEST_VERSION_STALE"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE student_id=?",
                Integer.class, f.student())).isZero();
    }

    @Test void confirmedCoachRequiredAppointmentLinksOnlyContainedUnsealedOccurrence() {
        Fixture f = fixture();
        LocalDate day = futureDate();
        UUID occurrence = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,coaching_period_id,planned_start_at,planned_end_at,
                 original_planned_start_at,supervision_requirement,created_by)
                VALUES (?,?,?,?,?,?,'COACH_REQUIRED',?)
                """, occurrence, f.student(), f.period(),
                Timestamp.from(day.atTime(10, 0).toInstant(ZoneOffset.UTC)),
                Timestamp.from(day.atTime(11, 0).toInstant(ZoneOffset.UTC)),
                Timestamp.from(day.atTime(10, 0).toInstant(ZoneOffset.UTC)), f.student());
        LocalInterval tooShort = interval(10, 10);
        assertThatThrownBy(() -> appointments.request(new RequestCommand(f.student(), f.student(), occurrence,
                tooShort, UUID.randomUUID()))).isInstanceOf(AppointmentFailure.class);
        var created = appointments.request(new RequestCommand(f.student(), f.student(), occurrence,
                interval(9, 12), UUID.randomUUID()));
        var accepted = appointments.decide(new DecisionCommand(f.trainer(), created.request().requestId(),
                "ACCEPT", 0, UUID.randomUUID()));
        assertThat(accepted.appointment().plannedWorkoutId()).isEqualTo(occurrence);
        assertThat(accepted.request().plannedWorkoutId()).isEqualTo(occurrence);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM fitness.appointments WHERE planned_workout_id=? AND status='CONFIRMED'
                """, Integer.class, occurrence)).isOne();
        assertThatThrownBy(() -> appointments.cancel(new AppointmentService.CancelCommand(f.student(),
                accepted.appointment().appointmentId(), accepted.appointment().version(),
                "Hủy", UUID.randomUUID())))
                .isInstanceOfSatisfying(AppointmentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("APPOINTMENT_COACH_REQUIRED_DEPENDENCY"));
        assertThat(appointments.appointmentDetail(f.student(), accepted.appointment().appointmentId()).status())
                .isEqualTo("CONFIRMED");
    }

    @Test void twoAcceptsOfOverlappingRequestsNeverBothCommit() throws Exception {
        Fixture f = fixture();
        var first = appointments.request(new RequestCommand(f.student(), f.student(), null,
                interval(10, 11), UUID.randomUUID()));
        var second = appointments.request(new RequestCommand(f.student(), f.student(), null,
                interval(10, 11), UUID.randomUUID()));
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> {
                start.await();
                try { appointments.decide(new DecisionCommand(f.trainer(), first.request().requestId(),
                        "ACCEPT", 0, UUID.randomUUID())); return "ACCEPTED"; }
                catch (AppointmentFailure conflict) { return conflict.code(); }
            });
            var b = pool.submit(() -> {
                start.await();
                try { appointments.decide(new DecisionCommand(f.trainer(), second.request().requestId(),
                        "ACCEPT", 0, UUID.randomUUID())); return "ACCEPTED"; }
                catch (AppointmentFailure conflict) { return conflict.code(); }
            });
            start.countDown();
            var results = java.util.List.of(a.get(20, TimeUnit.SECONDS), b.get(20, TimeUnit.SECONDS));
            assertThat(results).containsExactlyInAnyOrder("ACCEPTED", "APPOINTMENT_STUDENT_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointments WHERE student_id=? AND status='CONFIRMED'",
                Integer.class, f.student())).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.appointment_requests_v2 WHERE student_id=? AND status='PENDING'",
                Integer.class, f.student())).isOne();
    }

    @Test void legacyAppointmentRetainsNullableIntentAndCannotBeSilentlyDecided() throws Exception {
        Fixture f = fixture();
        UUID legacy = UUID.randomUUID();
        LocalDate day = futureDate();
        LegacyAppointmentFixture.insert(jdbc, """
                INSERT INTO fitness.appointments
                (id,student_id,trainer_id,start_at,end_at,display_timezone,status,created_by)
                VALUES (?,?,?,?,?,'Etc/UTC','CONFIRMED',?)
                """, legacy, f.student(), f.trainer(),
                Timestamp.from(day.atTime(14, 0).toInstant(ZoneOffset.UTC)),
                Timestamp.from(day.atTime(15, 0).toInstant(ZoneOffset.UTC)), f.student());
        mvc.perform(get("/api/v1/appointments/{id}", legacy)
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.localStart").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.coachingPeriodId").value(org.hamcrest.Matchers.nullValue()));
        assertThatThrownBy(() -> appointments.cancel(new AppointmentService.CancelCommand(f.student(), legacy,
                0, "Không tham dự", UUID.randomUUID())))
                .isInstanceOfSatisfying(AppointmentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("APPOINTMENT_PERIOD_INACTIVE"));
    }

    @Test void studentCancelIsVersionedAuditedAndReplayStable() throws Exception {
        Fixture f = fixture();
        var created = appointments.request(new RequestCommand(f.student(), f.student(), null,
                interval(9, 10), UUID.randomUUID()));
        var confirmed = appointments.decide(new DecisionCommand(f.trainer(), created.request().requestId(),
                "ACCEPT", 0, UUID.randomUUID())).appointment();
        UUID key = UUID.randomUUID();
        var command = new AppointmentService.CancelCommand(f.student(), confirmed.appointmentId(),
                confirmed.version(), "Không thể tham gia", key);
        var cancelled = appointments.cancel(command);
        assertThat(cancelled.appointment().status()).isEqualTo("CANCELLED");
        assertThat(cancelled.appointment().version()).isEqualTo(confirmed.version() + 1);
        assertThat(appointments.cancel(command).replayed()).isTrue();
        assertThat(appointments.history(f.student(), confirmed.appointmentId(), 0, 20).items())
                .anySatisfy(event -> assertThat(event.eventType()).isEqualTo("APPOINTMENT_CANCELLED"));
        mvc.perform(get("/api/v1/appointments/{id}", confirmed.appointmentId())
                        .with(jwt().jwt(token -> token.subject(f.student().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("CANCELLED"));
        assertThatThrownBy(() -> appointments.cancel(new AppointmentService.CancelCommand(f.student(),
                confirmed.appointmentId(), confirmed.version(), "Khác", UUID.randomUUID())))
                .isInstanceOfSatisfying(AppointmentFailure.class,
                        failure -> assertThat(failure.code()).isEqualTo("APPOINTMENT_VERSION_STALE"));
    }

    @Test void trainerRescheduleRequestNeedsStudentDecisionAndPreservesHistory() {
        Fixture f = fixture();
        var created = appointments.request(new RequestCommand(f.student(), f.student(), null,
                interval(8, 9), UUID.randomUUID()));
        var confirmed = appointments.decide(new DecisionCommand(f.trainer(), created.request().requestId(),
                "ACCEPT", 0, UUID.randomUUID())).appointment();
        var proposed = appointments.requestReschedule(new AppointmentService.RescheduleCommand(
                f.trainer(), confirmed.appointmentId(), confirmed.version(), interval(13, 14),
                "Đổi giờ", UUID.randomUUID()));
        assertThat(proposed.request().kind()).isEqualTo("RESCHEDULE");
        assertThat(appointments.appointmentDetail(f.student(), confirmed.appointmentId()).startAt())
                .isEqualTo(confirmed.startAt());
        var accepted = appointments.decideReschedule(new AppointmentService.RescheduleDecisionCommand(
                f.student(), proposed.request().requestId(), "ACCEPT", 0, confirmed.version(), UUID.randomUUID()));
        assertThat(accepted.appointment().appointmentId()).isEqualTo(confirmed.appointmentId());
        assertThat(accepted.appointment().version()).isEqualTo(confirmed.version() + 1);
        assertThat(accepted.appointment().startAt()).isAfter(confirmed.startAt());
        assertThat(appointments.history(f.student(), confirmed.appointmentId(), 0, 20).items())
                .anySatisfy(event -> {
                    assertThat(event.eventType()).isEqualTo("APPOINTMENT_RESCHEDULED");
                    assertThat(event.oldState()).isNotNull();
                    assertThat(event.newState()).isNotNull();
                });
    }

    private LocalInterval interval(int start, int end) {
        LocalDate day = futureDate();
        return new LocalInterval(day.atTime(start, 0), day.atTime(end, 0), "Etc/UTC", "+00:00", "+00:00");
    }
    private static LocalDate futureDate() { return LocalDate.now(ZoneOffset.UTC).plusDays(20); }

    private Fixture fixture() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        Outcome active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
        return new Fixture(student, trainer, active.currentPeriod().id());
    }

    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status) "
                + "VALUES (?,?,'hash','Appointment Test','ACTIVE')", id, id + "@example.com");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                + "SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?", id, id, role);
        if ("STUDENT".equals(role)) jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        else jdbc.update("INSERT INTO fitness.trainer_profiles"
                + "(user_id,verification_status,verified_at,is_accepting_students,is_active) "
                + "VALUES (?,'VERIFIED',clock_timestamp(),true,true)", id);
        return id;
    }

    private record Fixture(UUID student, UUID trainer, UUID period) {}
}
