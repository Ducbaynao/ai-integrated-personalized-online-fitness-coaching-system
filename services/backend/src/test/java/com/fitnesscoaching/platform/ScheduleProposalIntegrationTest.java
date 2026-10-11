package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
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
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;

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
class ScheduleProposalIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("schedule_proposals_b06").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired ScheduleProposalUseCase proposals;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    @Test void httpCreateListDetailAndDecisionUseContractShapes() throws Exception {
        Fixture f = fixture();
        LocalDateTime start = at(10);
        String body = """
                {"planId":"%s","sourcePlanVersionId":"%s","expectedPlanAggregateVersion":1,
                 "weekAnchorDate":"%s","timezone":"Etc/UTC","commandKey":"http-create",
                 "items":[{"clientItemId":"%s","planSessionId":"%s","weekNumber":1,
                  "dayNumber":1,"sequenceNumber":1,"localStart":"%s","localEnd":"%s",
                  "startUtcOffset":"+00:00","endUtcOffset":"+00:00"}]}
                """.formatted(f.plan(), f.version(), anchor(), UUID.randomUUID(), f.session(),
                start, start.plusHours(1));
        String created = mvc.perform(post("/api/v1/students/{student}/planned-workout-schedule-proposals", f.student())
                .with(jwt().jwt(jwt -> jwt.subject(f.trainer().toString())))
                .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.proposal.status").value("PENDING"))
                .andExpect(jsonPath("$.proposal.proposalVersion").value(0))
                .andReturn().getResponse().getContentAsString();
        assertThat(countOccurrences(f)).isZero();
        String proposalId = new tools.jackson.databind.ObjectMapper().readTree(created)
                .get("proposal").get("proposalId").asText();
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/me")
                .with(jwt().jwt(jwt -> jwt.subject(f.student().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].proposalId").value(proposalId));
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/{id}", proposalId)
                .with(jwt().jwt(jwt -> jwt.subject(f.trainer().toString()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sourcePlanVersionId").value(f.version().toString()));
        mvc.perform(post("/api/v1/planned-workout-schedule-proposals/{id}/decisions", proposalId)
                .with(jwt().jwt(jwt -> jwt.subject(f.student().toString())))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedProposalVersion\":0,\"decision\":\"ACCEPT\",\"commandKey\":\"http-accept\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.proposal.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.confirmedBatch.items[0].occurrenceVersion").value(0));
        assertThat(countOccurrences(f)).isOne();
    }

    @Test void trainerProposalCreatesNoOccurrenceAndStudentAcceptsWholeBatchWithReplay() {
        Fixture f = fixture();
        Create command = command(f, "proposal-create", at(10));
        CommandResult created = proposals.create(command);
        assertThat(created.proposal().status()).isEqualTo("PENDING");
        assertThat(created.proposal().proposalVersion()).isZero();
        assertThat(created.proposal().coachingPeriodId()).isEqualTo(f.period());
        assertThat(proposals.create(command)).isEqualTo(new CommandResult(created.proposal(), true));
        assertThat(proposals.listMine(f.student(), "PENDING", 0, 20).items()).hasSize(1);
        assertThat(proposals.detail(f.trainer(), created.proposal().proposalId())).isEqualTo(created.proposal());
        assertThat(countOccurrences(f)).isZero();

        Decide decide = new Decide(f.student(), created.proposal().proposalId(), 0, "ACCEPT", null, "accept-key");
        DecisionResult accepted = proposals.decide(decide);
        assertThat(accepted.proposal().status()).isEqualTo("ACCEPTED");
        assertThat(accepted.proposal().proposalVersion()).isOne();
        assertThat(accepted.confirmedBatch().sourceProposalId()).isEqualTo(created.proposal().proposalId());
        assertThat(accepted.confirmedBatch().items().getFirst().occurrenceVersion()).isZero();
        assertThat(countOccurrences(f)).isOne();
        assertThat(proposals.decide(decide).replayed()).isTrue();
        assertThat(countOccurrences(f)).isOne();
        assertThatThrownBy(() -> proposals.decide(new Decide(f.student(), created.proposal().proposalId(),
                0, "REJECT", null, "accept-key"))).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo("WORKOUT_SCHEDULE_IDEMPOTENCY_CONFLICT");
    }

    @Test void rejectDoesNotRunUnknownEndGuardAndWithdrawNeedsCurrentAuthority() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "reject-propose", at(10))).proposal().proposalId();
        insertUnknownEnd(f);
        DecisionResult rejected = proposals.decide(new Decide(f.student(), proposal, 0, "REJECT", "Không tập", "reject"));
        assertThat(rejected.confirmedBatch()).isNull();
        assertThat(rejected.proposal().status()).isEqualTo("REJECTED");
        assertThat(countOccurrences(f)).isOne(); // the legacy fixture only
        UUID another = proposals.create(command(f, "withdraw-propose", at(12))).proposal().proposalId();
        assertThat(proposals.withdraw(new Withdraw(f.trainer(), another, 0, "withdraw")).proposal().status())
                .isEqualTo("WITHDRAWN");
        assertThat(proposals.withdraw(new Withdraw(f.trainer(), another, 0, "withdraw")).replayed()).isTrue();
    }

    @Test void acceptUnknownEndDuplicateAndOverlapRollBackProposalTransition() {
        Fixture unknown = fixture();
        UUID proposal = proposals.create(command(unknown, "unknown-propose", at(10))).proposal().proposalId();
        insertUnknownEnd(unknown);
        assertFailure("WORKOUT_SCHEDULE_EXISTING_END_UNKNOWN", () ->
                proposals.decide(new Decide(unknown.student(), proposal, 0, "ACCEPT", null, "unknown-accept")));
        assertThat(proposals.detail(unknown.student(), proposal).status()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.workout_schedule_batches WHERE student_id=?",
                Integer.class, unknown.student())).isZero();

        Fixture f = fixture();
        UUID first = proposals.create(command(f, "first-propose", at(10))).proposal().proposalId();
        proposals.decide(new Decide(f.student(), first, 0, "ACCEPT", null, "first-accept"));
        UUID duplicate = proposals.create(command(f, "duplicate-propose", at(10))).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_DUPLICATE", () ->
                proposals.decide(new Decide(f.student(), duplicate, 0, "ACCEPT", null, "duplicate-accept")));
        UUID overlap = proposals.create(command(f, "overlap-propose", at(10).plusMinutes(30))).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_OVERLAP", () ->
                proposals.decide(new Decide(f.student(), overlap, 0, "ACCEPT", null, "overlap-accept")));
        assertThat(countOccurrences(f)).isOne();
    }

    @Test void revokedGrantInvalidatesAcceptAndFormerTrainerCannotReadOrWithdraw() throws Exception {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "revoke-propose", at(10))).proposal().proposalId();
        sharing.revoke(f.student(), f.relationship(), f.permission(), 0,
                "No schedule sharing", UUID.randomUUID());
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_INVALIDATED", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "revoke-accept")));
        assertThat(countOccurrences(f)).isZero();
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () -> proposals.detail(f.trainer(), proposal));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.withdraw(new Withdraw(f.trainer(), proposal, 0, "revoke-withdraw")));
        mvc.perform(get("/api/v1/planned-workout-schedule-proposals/{id}", proposal)
                .with(jwt().jwt(jwt -> jwt.subject(f.trainer().toString()))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND"));
    }

    @Test void stalePlanAndProposalVersionCannotAcceptOrRemap() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "stale-propose", at(10))).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_VERSION_CONFLICT", () ->
                proposals.decide(new Decide(f.student(), proposal, 3, "ACCEPT", null, "stale-proposal")));
        jdbc.update("UPDATE fitness.workout_plans SET version=version+1 WHERE id=?", f.plan());
        assertFailure("WORKOUT_SCHEDULE_PLAN_VERSION_STALE", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "stale-plan")));
        assertThat(countOccurrences(f)).isZero();
        assertThat(proposals.detail(f.student(), proposal).status()).isEqualTo("PENDING");
    }

    @Test void changedEffectiveVersionIsNotRemappedAndOldReceiptStillReplaysForStudent() {
        Fixture f = fixture();
        UUID accepted = proposals.create(command(f, "accepted-before-change", at(10))).proposal().proposalId();
        Decide acceptedCommand = new Decide(f.student(), accepted, 0, "ACCEPT", null, "accepted-replay");
        proposals.decide(acceptedCommand);
        UUID stale = proposals.create(command(f, "version-before-change", at(13))).proposal().proposalId();
        jdbc.update("UPDATE fitness.workout_plan_versions SET effective_until=clock_timestamp() WHERE id=?", f.version());
        UUID next = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,change_level,created_by,effective_from,locked_at,locked_by,lock_reason)
                VALUES (?,?,2,'MAJOR',?,clock_timestamp(),clock_timestamp(),?,'ACTIVATED')
                """, next, f.plan(), f.trainer(), f.trainer());
        assertFailure("WORKOUT_SCHEDULE_PLAN_VERSION_STALE", () ->
                proposals.decide(new Decide(f.student(), stale, 0, "ACCEPT", null, "stale-source")));
        assertThat(proposals.decide(acceptedCommand).replayed()).isTrue();
        assertThat(countOccurrences(f)).isOne();
    }

    @Test void proposalValidationRejectsDstGapAndAcceptRechecksNinetyDayBoundary() {
        Fixture f = fixture();
        LocalDate week = LocalDate.of(2027, 3, 8);
        LocalDateTime gap = LocalDateTime.of(2027, 3, 14, 2, 30);
        Create invalid = new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, week,
                "America/New_York", List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                gap, gap.plusHours(1), "-05:00", "-04:00")), "gap-key");
        assertFailure("VALIDATION_FAILED", () -> proposals.create(invalid));
        LocalDate distant = LocalDate.now(ZoneOffset.UTC).plusWeeks(15)
                .with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
        LocalDateTime start = distant.atTime(10, 0);
        Create future = new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, distant,
                "Etc/UTC", List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                start, start.plusHours(1), "+00:00", "+00:00")), "future-propose");
        UUID proposal = proposals.create(future).proposal().proposalId();
        assertFailure("VALIDATION_FAILED", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "future-accept")));
        assertThat(countOccurrences(f)).isZero();
    }

    @Test void acceptingMultiItemProposalRollsBackEveryNewItemOnOverlap() {
        Fixture f = fixture();
        UUID first = proposals.create(command(f, "occupy-first", at(10))).proposal().proposalId();
        proposals.decide(new Decide(f.student(), first, 0, "ACCEPT", null, "occupy-accept"));
        Create multi = new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, anchor(), "Etc/UTC",
                List.of(new Item(UUID.randomUUID(), f.session(), 1, 1, 1, at(12), at(13), "+00:00", "+00:00"),
                        new Item(UUID.randomUUID(), f.secondSession(), 1, 2, 1, at(10).plusMinutes(30),
                                at(11).plusMinutes(30), "+00:00", "+00:00")), "multi-propose");
        UUID proposal = proposals.create(multi).proposal().proposalId();
        assertFailure("WORKOUT_SCHEDULE_OVERLAP", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "multi-accept")));
        assertThat(countOccurrences(f)).isOne();
        assertThat(proposals.detail(f.student(), proposal).status()).isEqualTo("PENDING");
    }

    @Test void viewGrantAllowsDetailButNotMutationAndUnrelatedStudentsAreConcealed() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "view-propose", at(10))).proposal().proposalId();
        sharing.grantOrReplace(f.student(), f.relationship(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, 0L, UUID.randomUUID());
        assertThat(proposals.detail(f.trainer(), proposal).proposalId()).isEqualTo(proposal);
        assertFailure("WORKOUT_SCHEDULE_ACCESS_DENIED", () ->
                proposals.create(command(f, "view-create", at(12))));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.withdraw(new Withdraw(f.trainer(), proposal, 0, "view-withdraw")));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_INVALIDATED", () ->
                proposals.decide(new Decide(f.student(), proposal, 0, "ACCEPT", null, "view-accept")));
        UUID unrelated = person("STUDENT");
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () -> proposals.detail(unrelated, proposal));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.decide(new Decide(unrelated, proposal, 0, "REJECT", null, "unrelated")));
        assertThat(proposals.listMine(unrelated, null, 0, 20).items()).isEmpty();
    }

    @Test void crossStudentPlanAndUnknownProposalAreConcealed() {
        Fixture owner = fixture(), other = fixture();
        Create c = command(owner, "cross-plan", at(10));
        assertFailure("WORKOUT_PLAN_NOT_FOUND", () -> proposals.create(new Create(other.trainer(),
                other.student(), c.planId(), c.sourcePlanVersionId(), c.expectedPlanAggregateVersion(),
                c.weekAnchorDate(), c.timezone(), c.items(), c.commandKey())));
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () ->
                proposals.detail(owner.trainer(), UUID.randomUUID()));
    }

    @Test void endedRelationshipConcealsFormerTrainerButStudentMayRejectPendingProposal() {
        Fixture f = fixture();
        UUID proposal = proposals.create(command(f, "end-propose", at(10))).proposal().proposalId();
        lifecycle.relationshipAction(f.student(), f.relationship(), "END", 1,
                "Coaching ended", UUID.randomUUID());
        assertFailure("WORKOUT_SCHEDULE_PROPOSAL_NOT_FOUND", () -> proposals.detail(f.trainer(), proposal));
        assertThat(proposals.decide(new Decide(f.student(), proposal, 0, "REJECT", null, "end-reject"))
                .proposal().status()).isEqualTo("REJECTED");
        assertThat(countOccurrences(f)).isZero();
    }

    @Test void differentKeysConcurrentAcceptanceCreatesExactlyOneOccurrence() throws Exception {
        Fixture f = fixture();
        UUID first = proposals.create(command(f, "concurrent-a", at(10))).proposal().proposalId();
        UUID second = proposals.create(command(f, "concurrent-b", at(10))).proposal().proposalId();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> decideOutcome(f.student(), first, "concurrent-accept-a"));
            var b = pool.submit(() -> decideOutcome(f.student(), second, "concurrent-accept-b"));
            assertThat(List.of(a.get(), b.get())).containsExactlyInAnyOrder("ACCEPTED", "WORKOUT_SCHEDULE_DUPLICATE");
        }
        assertThat(countOccurrences(f)).isOne();
    }

    private String decideOutcome(UUID student, UUID proposal, String key) {
        try { proposals.decide(new Decide(student, proposal, 0, "ACCEPT", null, key)); return "ACCEPTED"; }
        catch (WorkoutScheduleFailure failure) { return failure.code(); }
    }
    private void assertFailure(String code, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(WorkoutScheduleFailure.class)
                .extracting("code").isEqualTo(code);
    }
    private int countOccurrences(Fixture f) {
        return jdbc.queryForObject("SELECT count(*) FROM fitness.planned_workouts WHERE student_id=?",
                Integer.class, f.student());
    }
    private void insertUnknownEnd(Fixture f) {
        jdbc.update("""
                INSERT INTO fitness.planned_workouts
                (id,student_id,workout_plan_session_id,planned_start_at,original_planned_start_at,created_by)
                VALUES (?,?,?,clock_timestamp()+interval '2 days',clock_timestamp()+interval '2 days',?)
                """, UUID.randomUUID(), f.student(), f.session(), f.student());
    }
    private Create command(Fixture f, String key, LocalDateTime start) {
        Item item = new Item(UUID.randomUUID(), f.session(), 1, 1, 1,
                start, start.plusHours(1), "+00:00", "+00:00");
        return new Create(f.trainer(), f.student(), f.plan(), f.version(), 1, anchor(), "Etc/UTC",
                List.of(item), key);
    }
    private static LocalDate anchor() {
        return LocalDate.now(ZoneOffset.UTC).plusWeeks(1).with(TemporalAdjusters.nextOrSame(DayOfWeek.MONDAY));
    }
    private static LocalDateTime at(int hour) { return anchor().atTime(hour, 0); }

    private Fixture fixture() {
        UUID student = person("STUDENT"), trainer = person("TRAINER");
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        Outcome active = lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
        var permission = sharing.grantOrReplace(student, active.relationship().id(), DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.CONTRIBUTE, null, null, null, null, UUID.randomUUID());
        UUID plan = UUID.randomUUID(), version = UUID.randomUUID();
        UUID session = UUID.randomUUID(), secondSession = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.workout_plans
                (id,student_id,name,source,status,created_by,decision_owner_type,decision_owner_id,coaching_period_id)
                VALUES (?,?,'Trainer plan','TRAINER','DRAFT',?,'TRAINER',?,?)
                """, plan, student, trainer, trainer, active.currentPeriod().id());
        jdbc.update("""
                INSERT INTO fitness.workout_plan_versions
                (id,workout_plan_id,version_number,change_level,created_by)
                VALUES (?,?,1,'INITIAL',?)
                """, version, plan, trainer);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                VALUES (?,?,1,1,1,'Session','SELF_ALLOWED')
                """, session, version);
        jdbc.update("""
                INSERT INTO fitness.workout_plan_sessions
                (id,workout_plan_version_id,week_number,day_number,sequence_number,name,supervision_requirement)
                VALUES (?,?,1,2,1,'Second','SELF_ALLOWED')
                """, secondSession, version);
        jdbc.update("""
                UPDATE fitness.workout_plan_versions
                SET effective_from=clock_timestamp(),locked_at=clock_timestamp(),locked_by=?,lock_reason='ACTIVATED'
                WHERE id=?
                """, trainer, version);
        jdbc.update("""
                UPDATE fitness.workout_plans SET status='ACTIVE',version=version+1,
                    assigned_by=?,assigned_at=clock_timestamp() WHERE id=?
                """, trainer, plan);
        return new Fixture(student, trainer, active.relationship().id(), active.currentPeriod().id(),
                permission.id(), plan, version, session, secondSession);
    }
    private UUID person(String role) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'hash','Person','ACTIVE','vi-VN','Etc/UTC')
                """, id, id + "@example.com");
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?
                """, id, id, role);
        if ("STUDENT".equals(role)) jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        else jdbc.update("""
                INSERT INTO fitness.trainer_profiles
                (user_id,verification_status,verified_at,is_accepting_students,is_active)
                VALUES (?,'VERIFIED',clock_timestamp(),true,true)
                """, id);
        return id;
    }
    private record Fixture(UUID student, UUID trainer, UUID relationship, UUID period, UUID permission,
                           UUID plan, UUID version, UUID session, UUID secondSession) {}
}
