package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingLifecycleUseCase;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingSharingUseCase;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.PermissionSummary;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.PermissionSummaryItem;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.Outcome;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.coaching.domain.SharingDecision;
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
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CoachingMobileReadContractsIntegrationTest {
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("coaching_mobile_reads").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired CoachingLifecycleUseCase lifecycle;
    @Autowired CoachingSharingUseCase sharing;

    @Test
    void trainerDiscoveryFiltersAuthoritativelySearchesDisplayNameAndPaginates() throws Exception {
        UUID student = person("STUDENT", "Directory Student", "directory.student@example.com");
        person("TRAINER", "M2E Paging Alpha", "paging.alpha@example.com");
        person("TRAINER", "M2E Paging Bravo", "paging.bravo@example.com");
        UUID notAccepting = person("TRAINER", "M2E Paging Hidden Availability", "hidden.availability@example.com");
        UUID pending = person("TRAINER", "M2E Paging Hidden Verification", "hidden.verification@example.com");
        UUID suspended = person("TRAINER", "M2E Paging Hidden Account", "hidden.account@example.com");
        jdbc.update("UPDATE fitness.trainer_profiles SET is_accepting_students=false WHERE user_id=?", notAccepting);
        jdbc.update("UPDATE fitness.trainer_profiles SET verification_status='PENDING' WHERE user_id=?", pending);
        jdbc.update("UPDATE fitness.users SET status='SUSPENDED' WHERE id=?", suspended);

        mvc.perform(get("/api/v1/coaching/trainers")
                        .param("query", "paging")
                        .param("page", "0").param("size", "1")
                        .with(jwtFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("M2E Paging Alpha"))
                .andExpect(jsonPath("$.items[0].trainerId").isNotEmpty())
                .andExpect(jsonPath("$.items[0].email").doesNotExist())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(1));

        mvc.perform(get("/api/v1/coaching/trainers")
                        .param("query", "PAGING")
                        .param("page", "1").param("size", "1")
                        .with(jwtFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].displayName").value("M2E Paging Bravo"));
    }

    @Test
    void studentLookupIsExactNormalizedPrivacyMinimalAndNonEnumerating() throws Exception {
        UUID trainer = person("TRAINER", "Lookup Trainer", "lookup.trainer@example.com");
        UUID student = person("STUDENT", "Lookup Student", "lookup.student@example.com");

        mvc.perform(get("/api/v1/coaching/students/lookup")
                        .param("email", "  LOOKUP.STUDENT@EXAMPLE.COM  ")
                        .with(jwtFor(trainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentId").value(student.toString()))
                .andExpect(jsonPath("$.displayName").value("Lookup Student"))
                .andExpect(jsonPath("$.email").doesNotExist())
                .andExpect(jsonPath("$.phoneNumber").doesNotExist());

        mvc.perform(get("/api/v1/coaching/students/lookup")
                        .param("email", "lookup.student")
                        .with(jwtFor(trainer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_COUNTERPARTY_NOT_FOUND"));

        UUID unavailable = person("STUDENT", "Unavailable Student", "unavailable.student@example.com");
        jdbc.update("UPDATE fitness.users SET status='SUSPENDED' WHERE id=?", unavailable);
        mvc.perform(get("/api/v1/coaching/students/lookup")
                        .param("email", "unavailable.student@example.com")
                        .with(jwtFor(trainer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_COUNTERPARTY_NOT_FOUND"));

        lifecycle.initiate(trainer, student, trainer, UUID.randomUUID());
        mvc.perform(get("/api/v1/coaching/students/lookup")
                        .param("email", "lookup.student@example.com")
                        .with(jwtFor(trainer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_COUNTERPARTY_NOT_FOUND"));
    }

    @Test
    void relationshipCollectionPreservesStudentHistoryAndConcealsFormerTrainer() throws Exception {
        UUID student = person("STUDENT", "History Student", "history.student@example.com");
        UUID former = person("TRAINER", "Former Trainer", "former.trainer@example.com");
        UUID pausedTrainer = person("TRAINER", "Paused Trainer", "paused.trainer@example.com");
        UUID pendingTrainer = person("TRAINER", "Pending Trainer", "pending.trainer@example.com");
        UUID activeStudent = person("STUDENT", "Active Student", "active.student@example.com");
        UUID activeTrainer = person("TRAINER", "Active Trainer", "active.trainer@example.com");

        Outcome formerActive = accepted(student, former);
        lifecycle.relationshipAction(student, formerActive.relationship().id(), "END", 1,
                "changed trainer", UUID.randomUUID());
        Outcome pausedActive = accepted(student, pausedTrainer);
        lifecycle.relationshipAction(student, pausedActive.relationship().id(), "PAUSE", 1,
                null, UUID.randomUUID());
        lifecycle.initiate(student, student, pendingTrainer, UUID.randomUUID());
        accepted(activeStudent, activeTrainer);

        mvc.perform(get("/api/v1/coaching/relationships/me").with(jwtFor(student)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[?(@.status == 'ENDED')].counterparty.displayName")
                        .value("Former Trainer"))
                .andExpect(jsonPath("$.items[0].counterparty.email").doesNotExist());

        mvc.perform(get("/api/v1/coaching/relationships/me").with(jwtFor(former)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/coaching/relationships/me").with(jwtFor(pausedTrainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PAUSED"))
                .andExpect(jsonPath("$.items[0].counterparty.displayName").value("History Student"));
        mvc.perform(get("/api/v1/coaching/relationships/me").with(jwtFor(pendingTrainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("PENDING"));
        mvc.perform(get("/api/v1/coaching/relationships/me").with(jwtFor(activeTrainer)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.items[0].counterparty.displayName").value("Active Student"));
    }

    @Test
    void permissionSummaryClassifiesLevelsRevocationExpiryAndMissingScopes() throws Exception {
        UUID student = person("STUDENT", "Permission Student", "permission.student@example.com");
        UUID trainer = person("TRAINER", "Permission Trainer", "permission.trainer@example.com");
        Outcome active = accepted(student, trainer);
        UUID relationshipId = active.relationship().id();

        sharing.grantOrReplace(student, relationshipId, DataScope.FITNESS_GOAL,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, relationshipId, DataScope.WORKOUT_PLAN,
                SharingDecision.ALLOW, DataAccessLevel.CONTRIBUTE, null, null, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, relationshipId, DataScope.BODY_METRICS,
                SharingDecision.ALLOW, DataAccessLevel.MANAGE, null, null, null, null, UUID.randomUUID());
        sharing.grantOrReplace(student, relationshipId, DataScope.WORKOUT_HISTORY,
                SharingDecision.DENY, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());
        var revoked = sharing.grantOrReplace(student, relationshipId, DataScope.NUTRITION_LOGS,
                SharingDecision.ALLOW, DataAccessLevel.VIEW, null, null, null, null, UUID.randomUUID());
        sharing.revoke(student, relationshipId, revoked.id(), revoked.version(), "withdrawn", UUID.randomUUID());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        jdbc.update("""
                INSERT INTO fitness.data_sharing_permissions
                (id,relationship_id,student_id,trainer_id,data_scope,decision,access_level,
                 valid_from,valid_until,granted_by,version,created_at,updated_at)
                VALUES (?,?,?,?,?::fitness.data_scope_code,'ALLOW','VIEW',?,?,?,?,?,?)
                """, UUID.randomUUID(), relationshipId, student, trainer, DataScope.PROGRESS_PHOTOS.name(),
                Timestamp.from(now.minus(2, ChronoUnit.DAYS)), Timestamp.from(now.minus(1, ChronoUnit.DAYS)),
                student, 0L, Timestamp.from(now.minus(2, ChronoUnit.DAYS)),
                Timestamp.from(now.minus(2, ChronoUnit.DAYS)));

        PermissionSummary summary = sharing.summary(student, relationshipId);
        assertThat(item(summary, DataScope.FITNESS_GOAL)).satisfies(item -> {
            assertThat(item.state().name()).isEqualTo("ALLOWED");
            assertThat(item.accessLevel()).isEqualTo(DataAccessLevel.VIEW);
            assertThat(item.permissionId()).isNotNull();
        });
        assertThat(item(summary, DataScope.WORKOUT_PLAN).accessLevel()).isEqualTo(DataAccessLevel.CONTRIBUTE);
        assertThat(item(summary, DataScope.BODY_METRICS).accessLevel()).isEqualTo(DataAccessLevel.MANAGE);
        assertThat(item(summary, DataScope.WORKOUT_HISTORY)).satisfies(item -> {
            assertThat(item.state().name()).isEqualTo("DENIED");
            assertThat(item.decision()).isEqualTo(SharingDecision.DENY);
            assertThat(item.permissionId()).isNotNull();
        });
        assertThat(item(summary, DataScope.NUTRITION_LOGS)).satisfies(item -> {
            assertThat(item.state().name()).isEqualTo("REVOKED");
            assertThat(item.permissionId()).isNull();
            assertThat(item.version()).isNull();
        });
        assertThat(item(summary, DataScope.PROGRESS_PHOTOS).state().name()).isEqualTo("EXPIRED");
        assertThat(item(summary, DataScope.AI_RECOMMENDATIONS).state().name()).isEqualTo("NOT_CONFIGURED");
        assertThat(summary.items()).hasSize(DataScope.values().length);

        lifecycle.relationshipAction(student, relationshipId, "END", 1, "finished", UUID.randomUUID());
        mvc.perform(get("/api/v1/coaching/relationships/{relationshipId}/sharing-permissions/summary",
                        relationshipId).with(jwtFor(trainer)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_RELATIONSHIP_NOT_FOUND"));

        UUID unrelated = person("TRAINER", "Unrelated Trainer", "unrelated.trainer@example.com");
        mvc.perform(get("/api/v1/coaching/relationships/{relationshipId}/sharing-permissions/summary",
                        relationshipId).with(jwtFor(unrelated)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode").value("COACHING_RELATIONSHIP_NOT_FOUND"));
    }

    private PermissionSummaryItem item(PermissionSummary summary, DataScope scope) {
        return summary.items().stream().filter(item -> item.dataScope() == scope).findFirst().orElseThrow();
    }

    private Outcome accepted(UUID student, UUID trainer) {
        Outcome pending = lifecycle.initiate(student, student, trainer, UUID.randomUUID());
        return lifecycle.relationshipAction(trainer, pending.relationship().id(), "ACCEPT",
                0, null, UUID.randomUUID());
    }

    private UUID person(String role, String displayName, String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status,preferred_locale,timezone)
                VALUES (?,?,'test-hash',?,'ACTIVE','vi-VN','Asia/Ho_Chi_Minh')
                """, id, email, displayName);
        jdbc.update("""
                INSERT INTO fitness.user_roles(user_id,role_id,assigned_by,assigned_at)
                SELECT ?,id,?,clock_timestamp() FROM fitness.roles WHERE code=?
                """, id, id, role);
        if (role.equals("STUDENT")) {
            jdbc.update("INSERT INTO fitness.student_profiles(user_id) VALUES (?)", id);
        } else {
            jdbc.update("""
                    INSERT INTO fitness.trainer_profiles
                    (user_id,verification_status,verified_at,is_accepting_students,is_active,activity_status)
                    VALUES (?,'VERIFIED',clock_timestamp(),true,true,'ACTIVE')
                    """, id);
        }
        return id;
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor jwtFor(UUID actor) {
        return jwt().jwt(token -> token.subject(actor.toString()));
    }
}
