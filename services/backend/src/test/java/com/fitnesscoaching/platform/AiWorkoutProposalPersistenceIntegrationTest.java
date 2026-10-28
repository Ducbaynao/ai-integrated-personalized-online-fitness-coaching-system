package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRecommendationRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendation;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationApplication;
import com.fitnesscoaching.platform.modules.ai.domain.AiRecommendationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class AiWorkoutProposalPersistenceIntegrationTest {

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_f02_test").withUsername("fitness_app").withPassword("testpass123");
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

    @Autowired
    private AiRecommendationRepository recommendationRepository;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID createTestUserAndStudent() {
        UUID userId = UUID.randomUUID();
        jdbc.update("INSERT INTO fitness.users (id, email, password_hash, display_name, status, created_at, updated_at) " +
                "VALUES (?, ?, 'hash', 'Test User', 'ACTIVE', now(), now())", userId, "student-" + userId + "@example.com");
        jdbc.update("INSERT INTO fitness.student_profiles (user_id, created_at, updated_at) " +
                "VALUES (?, now(), now())", userId);
        return userId;
    }

    private UUID createTestRun(UUID userId, boolean evaluationOnly, String purpose) {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
            INSERT INTO fitness.ai_runs (
                id, requested_by, student_id, request_type, status, purpose, evaluation_only,
                input_payload, created_at
            ) VALUES (?, ?, ?, 'F02_WORKOUT_PLAN_PROPOSAL', 'SUCCEEDED', ?, ?, '{}'::jsonb, now())
            """, runId, userId, userId, purpose, evaluationOnly);
        return runId;
    }

    @Test
    @DisplayName("Save and retrieve AiRecommendation in PENDING status")
    void saveAndFindRecommendation_succeeds() {
        UUID studentId = createTestUserAndStudent();
        UUID runId = createTestRun(studentId, false, "BUSINESS_ASSISTANCE");
        UUID recId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(7, ChronoUnit.DAYS).truncatedTo(ChronoUnit.MILLIS);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .aiRunId(runId)
                .studentId(studentId)
                .recommendationType("WORKOUT_PLAN_PROPOSAL")
                .title("1-Week Full Body Starter")
                .explanation("Structured foundation plan")
                .structuredPayload("{\"plan\":{\"name\":\"1-Week Full Body Starter\"}}")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .targetEntityType("WORKOUT_PLAN")
                .expiresAt(expiresAt)
                .build();

        AiRecommendation saved = recommendationRepository.save(rec);
        assertThat(saved.id()).isEqualTo(recId);
        assertThat(saved.status()).isEqualTo(AiRecommendationStatus.PENDING);

        Optional<AiRecommendation> found = recommendationRepository.findById(recId);
        assertThat(found).isPresent();
        assertThat(found.get().title()).isEqualTo("1-Week Full Body Starter");
        assertThat(found.get().decisionAuthority()).isEqualTo(AiDecisionAuthority.STUDENT);
        assertThat(found.get().status()).isEqualTo(AiRecommendationStatus.PENDING);
    }

    @Test
    @DisplayName("Update decision to APPLIED and save AiRecommendationApplication")
    void updateDecisionAndSaveApplication_succeeds() {
        UUID studentId = createTestUserAndStudent();
        UUID runId = createTestRun(studentId, false, "BUSINESS_ASSISTANCE");
        UUID recId = UUID.randomUUID();
        UUID planId = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MILLIS);

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .aiRunId(runId)
                .studentId(studentId)
                .recommendationType("WORKOUT_PLAN_PROPOSAL")
                .title("1-Week Plan")
                .explanation("Details")
                .structuredPayload("{\"key\":\"val\"}")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .expiresAt(now.plus(7, ChronoUnit.DAYS))
                .build();

        recommendationRepository.save(rec);

        // Update decision
        recommendationRepository.updateDecision(
                recId,
                AiRecommendationStatus.APPLIED,
                studentId,
                now,
                "Applied by student",
                "WORKOUT_PLAN",
                planId
        );

        AiRecommendation updated = recommendationRepository.findById(recId).orElseThrow();
        assertThat(updated.status()).isEqualTo(AiRecommendationStatus.APPLIED);
        assertThat(updated.decidedBy()).isEqualTo(studentId);
        assertThat(updated.targetEntityId()).isEqualTo(planId);

        // Save application
        AiRecommendationApplication app = new AiRecommendationApplication(
                UUID.randomUUID(),
                recId,
                studentId,
                "WORKOUT_PLAN",
                planId,
                "{\"commandKey\":\"cmd-test\"}",
                now
        );

        AiRecommendationApplication savedApp = recommendationRepository.saveApplication(app);
        assertThat(savedApp.aiRecommendationId()).isEqualTo(recId);
        assertThat(savedApp.appliedBy()).isEqualTo(studentId);

        Optional<AiRecommendationApplication> foundApp = recommendationRepository.findApplicationByRecommendationId(recId);
        assertThat(foundApp).isPresent();
        assertThat(foundApp.get().resultingEntityId()).isEqualTo(planId);
    }

    @Test
    @DisplayName("Database trigger trg_validate_ai_recommendation_authority enforces human decision & student match")
    void dbTrigger_enforcesAuthorityInvariants() {
        UUID studentId = createTestUserAndStudent();
        UUID otherUserId = createTestUserAndStudent();
        UUID runId = createTestRun(studentId, false, "BUSINESS_ASSISTANCE");
        UUID recId = UUID.randomUUID();

        // 1. Trying to save with status ACCEPTED but mismatching decided_by should fail with DB trigger exception
        AiRecommendation recMismatch = AiRecommendation.builder()
                .id(recId)
                .aiRunId(runId)
                .studentId(studentId)
                .recommendationType("WORKOUT_PLAN_PROPOSAL")
                .title("Plan")
                .explanation("Desc")
                .structuredPayload("{}")
                .status(AiRecommendationStatus.ACCEPTED)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .decidedBy(otherUserId) // Not matching studentId!
                .decidedAt(Instant.now())
                .build();

        assertThatThrownBy(() -> recommendationRepository.save(recMismatch))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("Student authority mismatch");
    }

    @Test
    @DisplayName("Database trigger rejects recommendations from EVALUATION runs")
    void dbTrigger_rejectsEvaluationRuns() {
        UUID studentId = createTestUserAndStudent();
        UUID evalRunId = createTestRun(studentId, true, "EVALUATION");
        UUID recId = UUID.randomUUID();

        AiRecommendation rec = AiRecommendation.builder()
                .id(recId)
                .aiRunId(evalRunId)
                .studentId(studentId)
                .recommendationType("WORKOUT_PLAN_PROPOSAL")
                .title("Plan")
                .explanation("Desc")
                .structuredPayload("{}")
                .status(AiRecommendationStatus.PENDING)
                .decisionAuthority(AiDecisionAuthority.STUDENT)
                .build();

        assertThatThrownBy(() -> recommendationRepository.save(rec))
                .isInstanceOf(org.springframework.dao.DataAccessException.class)
                .hasMessageContaining("Evaluation run cannot create business recommendation");
    }
}
