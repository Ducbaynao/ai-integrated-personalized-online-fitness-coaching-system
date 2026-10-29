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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    @Autowired PlatformTransactionManager transactions;

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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE recipient_user_id=? "
                +"AND related_entity_type='COACHING_REVIEW_TASK' AND related_entity_id=? "
                +"AND event_type='REVIEW_TASK_COMPLETED'",Integer.class,student,task.id())).isEqualTo(1);
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
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications "
                +"WHERE related_entity_id=? AND event_type='ATTENTION_DETECTED'",Integer.class,signal.id()))
                .isEqualTo(2);
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
        mvc.perform(get("/api/v1/students/{id}/attention-return-status",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        var permission=sharing.grantOrReplace(student,relationship,DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW,DataAccessLevel.VIEW,null,null,null,null,UUID.randomUUID());
        mvc.perform(get("/api/v1/students/{id}/attention-signals",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(signal.id().toString()));
        mvc.perform(get("/api/v1/students/{id}/attention-return-status",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"));
        sharing.revoke(student,relationship,permission.id(),permission.version(),"No longer shared",UUID.randomUUID());
        mvc.perform(get("/api/v1/students/{id}/attention-signals",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());
        mvc.perform(get("/api/v1/attention-signals/{id}",signal.id())
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/students/{id}/attention-return-status",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        assertThat(service.signal(student,signal.id()).id()).isEqualTo(signal.id());
    }

    @Test void disabledStudentConcealsAttentionAndReviewFromCurrentTrainer() throws Exception {
        UUID student=student(), trainer=trainer();
        activate(student,trainer);
        service.confirmTimezone(student,"Etc/UTC",UUID.randomUUID());
        service.evaluate();
        var task=service.reviews(student,student,0,20).items().getFirst();
        mvc.perform(get("/api/v1/students/{id}/review-tasks",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(task.id().toString()));

        jdbc.update("UPDATE fitness.users SET status='DISABLED' WHERE id=?",student);
        mvc.perform(get("/api/v1/students/{id}/review-tasks",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/review-tasks/{id}",task.id())
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/students/{id}/attention-signals",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/students/{id}/attention-return-status",student)
                .with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isNotFound());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.coaching_review_tasks WHERE id=?",
                Integer.class,task.id())).isEqualTo(1);
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

    @Test void returnRuleUsesCompletedFactsAndConfirmedLocalCalendarDaysNotMissingLogs() {
        for (int days : List.of(99,100,101)) {
            UUID student=student();
            service.confirmTimezone(student,"America/New_York",UUID.randomUUID());
            ZonedDateTime first=ZonedDateTime.of(2025,3,8,12,0,0,0,ZoneId.of("America/New_York"));
            UUID earlier=completed(student,first.toInstant());
            UUID later=completed(student,first.plusDays(days).toInstant());
            assertThat(service.returnStatus(student,student).status())
                    .isEqualTo(days<100?"NO_QUALIFYING_GAP":"DETECTED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.attention_signals WHERE student_id=?",
                    Integer.class,student)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE recipient_user_id=?",
                    Integer.class,student)).isZero();
            service.evaluate();
            var returns=service.signals(student,student,0,20).items().stream()
                    .filter(signal->signal.rule().equals("RETURN_AFTER_100_DAYS_V1")).toList();
            assertThat(returns).hasSize(days<100?0:1);
            assertThat(service.returnStatus(student,student).status())
                    .isEqualTo(days<100?"NO_QUALIFYING_GAP":"DETECTED");
            if (days>=100) {
                var evidence=returns.getFirst().returnEvidence();
                assertThat(returns.getFirst().severity()).isEqualTo("LOW");
                assertThat(evidence.previousExecutionId()).isEqualTo(earlier);
                assertThat(evidence.currentExecutionId()).isEqualTo(later);
                assertThat(evidence.calendarDays()).isEqualTo(days);
                assertThat(evidence.timezone()).isEqualTo("America/New_York");
                assertThat(ChronoUnit.DAYS.between(evidence.previousLocalDate(),evidence.currentLocalDate()))
                        .isEqualTo(days);
                service.evaluate();
                assertThat(service.signals(student,student,0,20).items().stream()
                        .filter(signal->signal.rule().equals("RETURN_AFTER_100_DAYS_V1"))).hasSize(1);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE recipient_user_id=? "
                        +"AND related_entity_type='WORKOUT_ATTENTION_SIGNAL'",Integer.class,student)).isZero();
            }
        }
        UUID missingPrior=student(),missingZone=student();
        service.confirmTimezone(missingPrior,"Asia/Bangkok",UUID.randomUUID());
        completed(missingPrior,Instant.parse("2025-08-01T10:00:00Z"));
        completed(missingZone,Instant.parse("2025-01-01T10:00:00Z"));
        completed(missingZone,Instant.parse("2025-08-01T10:00:00Z"));
        service.evaluate();
        assertThat(service.signals(missingPrior,missingPrior,0,20).items()).isEmpty();
        assertThat(service.signals(missingZone,missingZone,0,20).items()).isEmpty();
        assertThat(service.returnStatus(missingPrior,missingPrior).reason())
                .isEqualTo("PREVIOUS_COMPLETION_MISSING");
        assertThat(service.returnStatus(missingZone,missingZone).reason())
                .isEqualTo("TIMEZONE_UNCONFIRMED");
        UUID sameDay=student(),unknownEnd=student();
        service.confirmTimezone(sameDay,"Asia/Bangkok",UUID.randomUUID());
        service.confirmTimezone(unknownEnd,"Asia/Bangkok",UUID.randomUUID());
        completed(sameDay,Instant.parse("2025-01-01T01:00:00Z"));
        UUID lastSameDay=completed(sameDay,Instant.parse("2025-01-01T11:00:00Z"));
        completed(sameDay,Instant.parse("2025-04-11T11:00:00Z"));
        jdbc.update("""
                INSERT INTO fitness.workout_session_logs
                  (student_id,performed_start_at,status,logged_by)
                VALUES (?,?,'COMPLETED',?)
                """,unknownEnd,Timestamp.from(Instant.parse("2025-01-01T10:00:00Z")),unknownEnd);
        completed(unknownEnd,Instant.parse("2025-08-01T10:00:00Z"));
        service.evaluate();
        var sameDayReturn=service.signals(sameDay,sameDay,0,20).items().stream()
                .filter(signal->signal.rule().equals("RETURN_AFTER_100_DAYS_V1")).findFirst().orElseThrow();
        assertThat(sameDayReturn.returnEvidence().previousExecutionId()).isEqualTo(lastSameDay);
        assertThat(service.signals(unknownEnd,unknownEnd,0,20).items()).isEmpty();
        assertThat(service.returnStatus(unknownEnd,unknownEnd).reason())
                .isEqualTo("COMPLETION_TIME_MISSING");
    }

    @Test void returnStatusHttpReadsOnlyTheRequestedStudentsCompletions() throws Exception {
        UUID studentA=student(),studentB=student();
        service.confirmTimezone(studentA,"Asia/Bangkok",UUID.randomUUID());
        service.confirmTimezone(studentB,"Asia/Bangkok",UUID.randomUUID());
        completed(studentA,Instant.parse("2025-01-01T10:00:00Z"));
        completed(studentA,Instant.parse("2025-04-11T10:00:00Z"));
        completed(studentB,Instant.parse("2025-06-01T10:00:00Z"));
        mvc.perform(get("/api/v1/attention-return-status/me")
                .with(jwt().jwt(j->j.subject(studentA.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DETECTED"));
        mvc.perform(get("/api/v1/attention-return-status/me")
                .with(jwt().jwt(j->j.subject(studentB.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UNKNOWN"))
                .andExpect(jsonPath("$.reason").value("PREVIOUS_COMPLETION_MISSING"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM pg_indexes WHERE schemaname='fitness'
                  AND tablename='workout_session_logs'
                  AND indexname IN ('idx_workout_logs_student_performed','ix_workout_execution_student_time')
                """,Integer.class)).isEqualTo(2);
        var plan=jdbc.queryForList("""
                EXPLAIN SELECT id,student_id,version,performed_start_at,performed_end_at
                FROM fitness.workout_session_logs
                WHERE student_id=? AND status='COMPLETED'
                ORDER BY COALESCE(performed_end_at,performed_start_at),id
                """,String.class,studentA);
        assertThat(String.join(" ",plan)).contains("student_id");
    }

    @Test void newlyCommittedReturnAndReviewNoticeAreAtomicAndConcealedAfterPause() throws Exception {
        UUID student=student(),trainer=trainer();
        UUID relationship=activate(student,trainer);
        var grant=sharing.grantOrReplace(student,relationship,DataScope.WORKOUT_HISTORY,
                SharingDecision.ALLOW,DataAccessLevel.VIEW,null,null,null,null,UUID.randomUUID());
        service.confirmTimezone(student,"Asia/Bangkok",UUID.randomUUID());
        service.evaluate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE recipient_user_id=? "
                +"AND related_entity_type='COACHING_REVIEW_TASK'",Integer.class,trainer)).isPositive();
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        completed(student,now.minus(101,ChronoUnit.DAYS));
        completed(student,now);
        service.evaluate();
        var signal=service.signals(student,student,0,20).items().stream()
                .filter(row->row.rule().equals("RETURN_AFTER_100_DAYS_V1")).findFirst().orElseThrow();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE related_entity_id=?",
                Integer.class,signal.id())).isEqualTo(2);
        service.evaluate();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE related_entity_id=?",
                Integer.class,signal.id())).isEqualTo(2);
        mvc.perform(get("/api/v1/notifications/me").with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk());
        sharing.revoke(student,relationship,grant.id(),grant.version(),"Revoked",UUID.randomUUID());
        mvc.perform(get("/api/v1/notifications/me").with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.relatedEntityId=='"+signal.id()+"')]").isEmpty());
        lifecycle.relationshipAction(student,relationship,"PAUSE",1,null,UUID.randomUUID());
        mvc.perform(get("/api/v1/notifications/me").with(jwt().jwt(j->j.subject(trainer.toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[?(@.relatedEntityId=='"+signal.id()+"')]").isEmpty());
        mvc.perform(get("/api/v1/attention-signals/{id}",signal.id())
                .with(jwt().jwt(j->j.subject(trainer.toString())))).andExpect(status().isNotFound());
        assertThat(service.signal(student,signal.id()).id()).isEqualTo(signal.id());
    }

    @Test void concurrentJobsDedupeTheSameReturnAndRollbackLeavesNoPartialNotice() throws Exception {
        UUID student=student();
        service.confirmTimezone(student,"Asia/Bangkok",UUID.randomUUID());
        Instant now=jdbc.queryForObject("SELECT clock_timestamp()",Timestamp.class).toInstant();
        completed(student,now.minus(101,ChronoUnit.DAYS));
        completed(student,now);
        var ready=new CountDownLatch(2);
        var start=new CountDownLatch(1);
        try (var workers=Executors.newFixedThreadPool(2)) {
            var tasks=new java.util.ArrayList<java.util.concurrent.Future<?>>();
            for (int i=0;i<2;i++) tasks.add(workers.submit(()->{
                ready.countDown();
                if (!start.await(10,TimeUnit.SECONDS)) throw new IllegalStateException("scheduler gate");
                service.evaluate();
                return null;
            }));
            assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();
            start.countDown();
            for (var task:tasks) task.get(30,TimeUnit.SECONDS);
        }
        var signals=service.signals(student,student,0,20).items().stream()
                .filter(row->row.rule().equals("RETURN_AFTER_100_DAYS_V1")).toList();
        assertThat(signals).hasSize(1);
        UUID id=signals.getFirst().id();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.attention_signal_events WHERE signal_id=?",
                Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE related_entity_id=?",
                Integer.class,id)).isEqualTo(1);

        UUID other=student();
        service.confirmTimezone(other,"Asia/Bangkok",UUID.randomUUID());
        completed(other,now.minus(101,ChronoUnit.DAYS));
        completed(other,now);
        assertThatThrownBy(()->new TransactionTemplate(transactions).execute(status->{
            service.evaluate();
            throw new IllegalStateException("rollback after producer");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.attention_signals WHERE student_id=? "
                +"AND signal_type='RETURN_AFTER_100_DAYS_V1'",Integer.class,other)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.notifications WHERE recipient_user_id=? "
                +"AND related_entity_type='WORKOUT_ATTENTION_SIGNAL'",Integer.class,other)).isZero();
    }

    private UUID completed(UUID student,Instant end) {
        UUID id=UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_session_logs
                  (id,student_id,performed_start_at,performed_end_at,status,logged_by)
                VALUES (?,?,?,?,'COMPLETED',?)
                """,id,student,Timestamp.from(end.minusSeconds(1800)),Timestamp.from(end),student);
        return id;
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
