package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.progress.AttentionReviewFailure;
import com.fitnesscoaching.platform.modules.progress.AttentionReviewService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AttentionReviewIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("m7_attention_review_disposable")
            .withUsername("fitness_app").withPassword("testpass123");
    static { postgres.start(); }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",postgres::getJdbcUrl);
        registry.add("spring.datasource.username",postgres::getUsername);
        registry.add("spring.datasource.password",postgres::getPassword);
        registry.add("spring.datasource.driver-class-name",()->"org.postgresql.Driver");
        registry.add("spring.flyway.enabled",()->"true");
        registry.add("spring.flyway.schemas",()->"fitness");
        registry.add("spring.flyway.default-schema",()->"fitness");
        registry.add("spring.jpa.hibernate.ddl-auto",()->"validate");
    }
    @Autowired AttentionReviewService service;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void explicitZoneCreatesRealTaskAndStudentRetainsReviewAfterPause() throws Exception {
        UUID student=student(),trainer=trainer();
        UUID relationship=activate(student,trainer);
        mvc.perform(get("/api/v1/review-timezone/me").with(jwt().jwt(j->j.subject(student.toString()))))
                .andExpect(status().isNoContent());
        UUID key=UUID.randomUUID();
        mvc.perform(put("/api/v1/review-timezone/me").with(jwt().jwt(j->j.subject(student.toString())))
                .contentType("application/json")
                .content("{\"timezone\":\"America/New_York\",\"commandKey\":\""+key+"\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.timezone").value("America/New_York"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.student_review_timezone_events "
                + "WHERE student_id=? AND command_key=?",Integer.class,student,key)).isEqualTo(1);
        service.evaluate();
        var task=service.reviews(student,student,0,20).items().getFirst();
        assertThat(task.dueDate()).isEqualTo(task.cycleStartDate().plusDays(7));
        assertThat(task.timezone()).isEqualTo("America/New_York");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_review_task_events "
                + "WHERE task_id=? AND event_type='CREATED'",Integer.class,task.id())).isEqualTo(1);
        mvc.perform(get("/api/v1/students/{id}/review-tasks",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(task.id().toString()));
        UUID completeKey=UUID.randomUUID();
        var completed=service.complete(trainer,task.id(),task.version(),completeKey,
                "Reviewed available data",List.of(),List.of("Body measurement unavailable"));
        assertThat(completed.status()).isEqualTo("COMPLETED");
        assertThat(service.complete(trainer,task.id(),task.version(),completeKey,
                "Reviewed available data",List.of(),List.of("Body measurement unavailable")).version())
                .isEqualTo(completed.version());
        assertThatThrownBy(()->service.complete(trainer,task.id(),task.version(),UUID.randomUUID(),
                "Again",List.of(),List.of())).isInstanceOf(AttentionReviewFailure.class)
                .hasMessage("REVIEW_VERSION_CONFLICT");
        service.acknowledgeReview(student,task.id(),completed.version(),UUID.randomUUID());
        lifecycle.relationshipAction(student,relationship,"PAUSE",1,null,UUID.randomUUID());
        mvc.perform(get("/api/v1/review-tasks/{id}",task.id())
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/review-tasks/{id}",task.id())
                .with(jwt().jwt(j->j.subject(student.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"));
    }

    @Test void missedNeedsKnownEndAndNoExecutionThenResolvesWithAudit() {
        UUID student=student();
        UUID due=occurrence(student,Instant.now().minusSeconds(3*86400),
                Instant.now().minusSeconds(2*86400),false);
        UUID unknown=occurrence(student,Instant.now().minusSeconds(3*86400),null,false);
        UUID started=occurrence(student,Instant.now().minusSeconds(3*86400),
                Instant.now().minusSeconds(2*86400),true);
        service.evaluate();
        var signals=service.signals(student,student,0,20).items();
        assertThat(signals).extracting(AttentionReviewService.Signal::occurrenceId)
                .contains(due).doesNotContain(unknown,started);
        var signal=signals.stream().filter(s->due.equals(s.occurrenceId())).findFirst().orElseThrow();
        assertThat(signal.severity()).isEqualTo("MEDIUM");
        jdbc.update("UPDATE fitness.planned_workouts SET status='SKIPPED',version=version+1 WHERE id=?",due);
        service.evaluate();
        assertThat(service.signal(student,signal.id()).status()).isEqualTo("RESOLVED");
        assertThat(service.signalHistory(student,signal.id())).extracting(AttentionReviewService.SignalEvent::eventType)
                .contains("DETECTED","SOURCE_CORRECTED");
    }

    @Test void overdueUsesCalendarDateAndPreservesSeverityHistory() {
        UUID student=student(),trainer=trainer();
        activate(student,trainer);
        service.confirmTimezone(student,"America/New_York",UUID.randomUUID());
        service.evaluate();
        var task=service.reviews(student,student,0,20).items().getFirst();
        LocalDate today=LocalDate.now(java.time.ZoneId.of(task.timezone()));
        jdbc.update("UPDATE fitness.coaching_review_tasks SET cycle_start_date=?,due_date=? WHERE id=?",
                today.minusDays(9),today.minusDays(2),task.id());
        service.evaluate();
        var signal=service.signals(student,student,0,20).items().stream()
                .filter(s->s.rule().equals("REVIEW_OVERDUE_V1")).findFirst().orElseThrow();
        assertThat(signal.severity()).isEqualTo("LOW");
        jdbc.update("UPDATE fitness.coaching_review_tasks SET cycle_start_date=?,due_date=? WHERE id=?",
                today.minusDays(15),today.minusDays(8),task.id());
        service.evaluate();
        assertThat(service.signal(student,signal.id()).severity()).isEqualTo("MEDIUM");
        assertThat(service.signalHistory(student,signal.id())).extracting(AttentionReviewService.SignalEvent::eventType)
                .contains("DETECTED","SEVERITY_CHANGED");
    }

    @Test void trainerWorkoutEvidenceRequiresCurrentViewAndRevocationConcealsCountAndDetail() throws Exception {
        UUID student=student(),trainer=trainer();
        UUID relationship=activate(student,trainer);
        UUID occurrence=occurrence(student,Instant.now().minusSeconds(3*86400),
                Instant.now().minusSeconds(2*86400),false);
        service.evaluate();
        var signal=service.signals(student,student,0,20).items().stream()
                .filter(s->occurrence.equals(s.occurrenceId())).findFirst().orElseThrow();
        mvc.perform(get("/api/v1/students/{id}/attention-signals",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/attention-signals/{id}",signal.id())
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        var permission=sharing.grantOrReplace(student,relationship,DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW,DataAccessLevel.VIEW,null,null,null,null,UUID.randomUUID());
        mvc.perform(get("/api/v1/students/{id}/attention-signals",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(signal.id().toString()));
        sharing.revoke(student,relationship,permission.id(),permission.version(),"No longer shared",UUID.randomUUID());
        mvc.perform(get("/api/v1/students/{id}/attention-signals",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/attention-signals/{id}",signal.id())
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        assertThat(service.signal(student,signal.id()).id()).isEqualTo(signal.id());
    }

    @Test void invalidTimezoneAndStaleReviewCommandHaveStableHttpErrors() throws Exception {
        UUID student=student(),trainer=trainer();
        activate(student,trainer);
        mvc.perform(put("/api/v1/review-timezone/me")
                .with(jwt().jwt(j->j.subject(student.toString()))).contentType("application/json")
                .content("{\"timezone\":\"Not/A_Zone\",\"commandKey\":\""+UUID.randomUUID()+"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_TIMEZONE_INVALID"));
        service.confirmTimezone(student,"Asia/Bangkok",UUID.randomUUID());
        service.evaluate();
        var task=service.reviews(student,student,0,20).items().getFirst();
        mvc.perform(post("/api/v1/review-tasks/{id}/complete",task.id())
                .with(jwt().jwt(j->j.subject(trainer.toString()))).contentType("application/json")
                .content("{\"expectedVersion\":999,\"commandKey\":\""+UUID.randomUUID()
                        +"\",\"summary\":\"Reviewed\",\"evidenceRefs\":[],\"missingData\":[]}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode").value("REVIEW_VERSION_CONFLICT"));
    }

    @Test void periodPauseCancelsOpenTaskAndResolvesOverdueWithoutDeletingStudentHistory() throws Exception {
        UUID student=student(),trainer=trainer();
        UUID relationship=activate(student,trainer);
        service.confirmTimezone(student,"Asia/Bangkok",UUID.randomUUID());
        service.evaluate();
        var task=service.reviews(student,student,0,20).items().getFirst();
        LocalDate today=LocalDate.now(java.time.ZoneId.of(task.timezone()));
        jdbc.update("UPDATE fitness.coaching_review_tasks SET cycle_start_date=?,due_date=? WHERE id=?",
                today.minusDays(9),today.minusDays(2),task.id());
        service.evaluate();
        var overdue=service.signals(student,student,0,20).items().stream()
                .filter(s->task.id().equals(s.reviewTaskId())).findFirst().orElseThrow();
        lifecycle.relationshipAction(student,relationship,"PAUSE",1,null,UUID.randomUUID());
        service.evaluate();
        assertThat(service.review(student,task.id()).status()).isEqualTo("CANCELLED");
        assertThat(service.signal(student,overdue.id()).status()).isEqualTo("RESOLVED");
        mvc.perform(get("/api/v1/review-tasks/{id}",task.id())
                .with(jwt().jwt(j->j.subject(trainer.toString())))).andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/attention-signals/{id}",overdue.id())
                .with(jwt().jwt(j->j.subject(trainer.toString())))).andExpect(status().isNotFound());
    }

    private UUID occurrence(UUID student,Instant start,Instant end,boolean sealed) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.planned_workouts(id,student_id,planned_start_at,planned_end_at,
                  original_planned_start_at,created_by,execution_started_at)
                VALUES (?,?,?,?,?,?,?)
                """,id,student,Timestamp.from(start),end==null?null:Timestamp.from(end),
                Timestamp.from(start),student,sealed?Timestamp.from(start):null);
        return id;
    }
    private UUID activate(UUID student,UUID trainer) {
        var pending=lifecycle.initiate(student,student,trainer,UUID.randomUUID());
        lifecycle.relationshipAction(trainer,pending.relationship().id(),"ACCEPT",0,null,UUID.randomUUID());
        return pending.relationship().id();
    }
    private UUID student() {
        UUID id=user("Student");
        jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)",id);
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                +"SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='STUDENT'",id,id);
        return id;
    }
    private UUID trainer() {
        UUID id=user("Trainer");
        jdbc.update("INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at) "
                +"SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code='TRAINER'",id,id);
        jdbc.update("INSERT INTO fitness.trainer_profiles "
                +"(user_id,verification_status,verified_at,is_accepting_students,is_active) "
                +"VALUES (?,'VERIFIED',clock_timestamp(),true,true)",id);
        return id;
    }
    private UUID user(String name) {
        UUID id=UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone) "
                +"VALUES (?,?,'hash',?,'ACTIVE','vi-VN','Etc/UTC')",id,id+"@example.com",name);
        return id;
    }
}
