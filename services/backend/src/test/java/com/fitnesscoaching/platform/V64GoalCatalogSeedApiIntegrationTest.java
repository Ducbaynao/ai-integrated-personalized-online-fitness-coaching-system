package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class V64GoalCatalogSeedApiIntegrationTest {

    private static final List<String> MOBILE_GOAL_TYPE_CODES = List.of(
            "FAT_LOSS",
            "MUSCLE_GAIN",
            "STRENGTH",
            "IMPROVE_FITNESS",
            "MAINTAIN"
    );

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("digital_fitness_v64_api")
            .withUsername("fitness_app")
            .withPassword("testpass123");

    static {
        postgres.start();
    }

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
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
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("TRUNCATE TABLE fitness.audit_logs, fitness.fitness_goal_status_history, " +
                "fitness.goal_targets, fitness.goal_objectives, fitness.fitness_goal_versions, " +
                "fitness.fitness_goals, fitness.student_profiles, fitness.user_roles, fitness.users CASCADE");
        jdbcTemplate.update("DELETE FROM fitness.goal_types WHERE code = 'TEST_INACTIVE_GOAL'");
    }

    private UUID createActiveStudent(String email) {
        UUID userId = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO fitness.users (id, email, password_hash, display_name, phone_number, status, preferred_locale, timezone, created_at, updated_at)
                VALUES (?, ?, ?, 'Seed API Student', '+84901234568', 'ACTIVE'::fitness.account_status, 'vi-VN', 'Asia/Ho_Chi_Minh', now(), now())
                """, userId, email, passwordEncoder.encode("Password123!"));
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles (user_id, role_id, assigned_by, assigned_at)
                SELECT ?, r.id, ?, now() FROM fitness.roles r WHERE r.code = 'STUDENT'
                """, userId, userId);
        jdbcTemplate.update("""
                INSERT INTO fitness.student_profiles (user_id, date_of_birth, gender, training_experience_level, training_experience_months, available_days_per_week, preferred_session_minutes, onboarding_completed_at, created_at, updated_at)
                VALUES (?, '1996-01-10', 'FEMALE'::fitness.gender_code, 'BEGINNER', 6, 3, 45, now(), now(), now())
                """, userId);
        return userId;
    }

    private String tokenFor(UUID userId, String email) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .id(UUID.randomUUID().toString())
                .claim("email", email)
                .claim("roles", List.of("STUDENT"))
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims))
                .getTokenValue();
    }

    @Test
    @DisplayName("Flyway V64 seeded catalog supports all 5 Mobile goalTypeCodes without fixture inserts and rejects unknown/inactive types")
    void createGoal_usingFlywaySeededCatalog_acceptsAllMobileCodesAndRejectsUnknownOrInactive() throws Exception {
        Integer seededCount = jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.goal_types", Integer.class);
        assertThat(seededCount).isEqualTo(7);

        for (String code : MOBILE_GOAL_TYPE_CODES) {
            String email = "seed." + code.toLowerCase() + "@example.com";
            UUID studentId = createActiveStudent(email);
            String token = tokenFor(studentId, email);
            Integer expectedGoalTypeId = jdbcTemplate.queryForObject(
                    "SELECT id FROM fitness.goal_types WHERE code = ?", Integer.class, code);
            assertThat(expectedGoalTypeId).isNotNull();

            String payload = """
                    {
                      "title": "Goal %s",
                      "startDate": "2026-11-26",
                      "durationDays": 60,
                      "activateImmediately": false,
                      "objectives": [
                        {
                          "goalTypeCode": "%s",
                          "priority": "PRIMARY",
                          "sortOrder": 0
                        }
                      ],
                      "targets": []
                    }
                    """.formatted(code, code);

            mockMvc.perform(post("/api/v1/fitness-goals")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.currentVersion.objectives[0].goalTypeCode", is(code)))
                    .andExpect(jsonPath("$.currentVersion.objectives[0].goalTypeId", is(expectedGoalTypeId)));
        }

        UUID validationStudentId = createActiveStudent("seed.validation@example.com");
        String validationToken = tokenFor(validationStudentId, "seed.validation@example.com");

        String unknownPayload = """
                {
                  "title": "Unknown Goal Type",
                  "startDate": "2026-11-26",
                  "durationDays": 30,
                  "activateImmediately": false,
                  "objectives": [
                    {
                      "goalTypeCode": "NON_EXISTENT_TYPE",
                      "priority": "PRIMARY",
                      "sortOrder": 0
                    }
                  ],
                  "targets": []
                }
                """;
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + validationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unknownPayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        jdbcTemplate.update("""
                INSERT INTO fitness.goal_types (code, name, description, is_active)
                VALUES ('TEST_INACTIVE_GOAL', 'Test Inactive', 'Inactive goal type for API validation', false)
                """);
        String inactivePayload = """
                {
                  "title": "Inactive Goal Type",
                  "startDate": "2026-11-26",
                  "durationDays": 30,
                  "activateImmediately": false,
                  "objectives": [
                    {
                      "goalTypeCode": "TEST_INACTIVE_GOAL",
                      "priority": "PRIMARY",
                      "sortOrder": 0
                    }
                  ],
                  "targets": []
                }
                """;
        mockMvc.perform(post("/api/v1/fitness-goals")
                        .header("Authorization", "Bearer " + validationToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(inactivePayload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));
    }
}
