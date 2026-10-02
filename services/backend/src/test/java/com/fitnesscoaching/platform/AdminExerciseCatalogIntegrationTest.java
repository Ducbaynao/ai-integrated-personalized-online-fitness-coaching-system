package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminExerciseCatalogIntegrationTest {

    private static final UUID ADMIN_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");
    private static final UUID DRAFT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID ACTIVE_ID = UUID.fromString("10000000-0000-0000-0000-000000000002");
    private static final UUID ARCHIVED_ID = UUID.fromString("10000000-0000-0000-0000-000000000003");
    private static final UUID ARCHIVED_TWO_ID = UUID.fromString("10000000-0000-0000-0000-000000000004");
    private static final List<String> MOVEMENT_PATTERNS = List.of(
            "SQUAT", "HINGE", "LUNGE", "PUSH", "PULL", "CARRY", "ROTATION",
            "CORE_STABILITY", "LOCOMOTION", "ISOLATION", "MOBILITY", "BALANCE");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("exercise_admin")
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

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private JwtEncoder jwtEncoder;
    @Autowired private JwtProperties jwtProperties;

    @BeforeEach
    void setUp() throws SQLException {
        verifyIsolatedDatabase();
        jdbcTemplate.execute("""
                TRUNCATE TABLE fitness.audit_logs, fitness.exercise_merge_events,
                fitness.exercise_canonical_mappings, fitness.exercise_guidance,
                fitness.exercise_tag_assignments, fitness.exercise_tags, fitness.exercise_media,
                fitness.exercise_equipment, fitness.exercise_muscles, fitness.exercise_variations,
                fitness.exercises, fitness.equipment, fitness.muscle_groups, fitness.exercise_categories,
                fitness.users CASCADE
                """);
        jdbcTemplate.update("UPDATE fitness.exercise_movement_patterns SET is_active=true");
        jdbcTemplate.update("""
                INSERT INTO fitness.users(id,email,password_hash,display_name,status)
                VALUES (?, 'admin@example.com', 'hash', 'Admin', 'ACTIVE'::fitness.account_status)
                """, ADMIN_ID);
        jdbcTemplate.update("""
                INSERT INTO fitness.user_roles(user_id,role_id)
                SELECT ?, id FROM fitness.roles WHERE code='ADMIN'
                """, ADMIN_ID);
        insertCatalogFixtures();
    }

    @Test
    void authorizationRequiresAuthenticationAdminRoleAndCatalogPermission() throws Exception {
        mockMvc.perform(get("/api/v1/admin/exercises"))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("STUDENT", "TRAINER")) {
            mockMvc.perform(get("/api/v1/admin/exercises").header("Authorization", bearer(UUID.randomUUID(), role)))
                    .andExpect(status().isForbidden());
        }

        jdbcTemplate.update("""
                DELETE FROM fitness.role_permissions rp
                USING fitness.roles r, fitness.permissions p
                WHERE rp.role_id=r.id AND rp.permission_id=p.id
                  AND r.code='ADMIN' AND p.code='CATALOG_MANAGE'
                """);
        try {
            mockMvc.perform(get("/api/v1/admin/exercises").header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
        } finally {
            jdbcTemplate.update("""
                    INSERT INTO fitness.role_permissions(role_id,permission_id)
                    SELECT r.id,p.id FROM fitness.roles r CROSS JOIN fitness.permissions p
                    WHERE r.code='ADMIN' AND p.code='CATALOG_MANAGE'
                    ON CONFLICT DO NOTHING
                    """);
        }
    }

    @Test
    void metadataRequiresAuthorizationAndReturnsOnlyEligibleDeterministicallyOrderedOptions() throws Exception {
        mockMvc.perform(get("/api/v1/admin/exercises/metadata"))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("STUDENT", "TRAINER")) {
            mockMvc.perform(get("/api/v1/admin/exercises/metadata")
                            .header("Authorization", bearer(UUID.randomUUID(), role)))
                    .andExpect(status().isForbidden());
        }

        jdbcTemplate.update("""
                DELETE FROM fitness.role_permissions rp
                USING fitness.roles r, fitness.permissions p
                WHERE rp.role_id=r.id AND rp.permission_id=p.id
                  AND r.code='ADMIN' AND p.code='CATALOG_MANAGE'
                """);
        mockMvc.perform(get("/api/v1/admin/exercises/metadata")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
        jdbcTemplate.update("""
                INSERT INTO fitness.role_permissions(role_id,permission_id)
                SELECT r.id,p.id FROM fitness.roles r CROSS JOIN fitness.permissions p
                WHERE r.code='ADMIN' AND p.code='CATALOG_MANAGE'
                ON CONFLICT DO NOTHING
                """);

        jdbcTemplate.update("INSERT INTO fitness.exercise_categories(code,name) VALUES ('ALPHA_CATEGORY','alpha')");
        jdbcTemplate.update("INSERT INTO fitness.muscle_groups(code,name) VALUES ('ALPHA_MUSCLE','alpha')");
        jdbcTemplate.update("INSERT INTO fitness.equipment(code,name,is_active) VALUES ('HIDDEN_EQUIPMENT','Hidden',false)");
        jdbcTemplate.update("INSERT INTO fitness.exercise_tags(code,name,is_active) VALUES ('HIDDEN_TAG','Hidden',false)");

        mockMvc.perform(get("/api/v1/admin/exercises/metadata")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementPatterns[*].code", hasSize(12)))
                .andExpect(jsonPath("$.movementPatterns[?(@.code == 'BALANCE')]", hasSize(1)))
                .andExpect(jsonPath("$.movementPatterns[?(@.code == 'HINGE')].name", contains("Hip Hinge")))
                .andExpect(jsonPath("$.movementPatterns[?(@.code == 'CARRY')].name", contains("Loaded Carry")));

        jdbcTemplate.update("UPDATE fitness.exercise_movement_patterns SET is_active=false WHERE code='BALANCE'");

        mockMvc.perform(get("/api/v1/admin/exercises/metadata")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[*].code", contains("ALPHA_CATEGORY", "STRENGTH")))
                .andExpect(jsonPath("$.muscleGroups[*].code", contains("ALPHA_MUSCLE", "CORE")))
                .andExpect(jsonPath("$.equipment[*].code", contains("BODYWEIGHT")))
                .andExpect(jsonPath("$.tags[*].code", contains("COMPOUND")))
                .andExpect(jsonPath("$.difficulties", contains("BEGINNER", "INTERMEDIATE", "ADVANCED")))
                .andExpect(jsonPath("$.movementPatterns[*].code", org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.hasItem("BALANCE"))))
                .andExpect(jsonPath("$.movementPatterns[*].code", hasSize(11)));

        jdbcTemplate.update("UPDATE fitness.user_roles SET revoked_at=now() WHERE user_id=?", ADMIN_ID);
        mockMvc.perform(get("/api/v1/admin/exercises/metadata")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCESS_DENIED")));
        jdbcTemplate.update("UPDATE fitness.user_roles SET revoked_at=null WHERE user_id=?", ADMIN_ID);

        jdbcTemplate.update("UPDATE fitness.users SET status='SUSPENDED'::fitness.account_status WHERE id=?", ADMIN_ID);
        mockMvc.perform(get("/api/v1/admin/exercises/metadata")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode", is("ACCOUNT_UNAVAILABLE")));
    }

    @Test
    void metadataReturnsEmptyArraysWhenReferenceGroupsHaveNoEligibleValues() throws Exception {
        jdbcTemplate.execute("""
                TRUNCATE TABLE fitness.exercises, fitness.equipment, fitness.muscle_groups,
                fitness.exercise_categories, fitness.exercise_tags CASCADE
                """);
        jdbcTemplate.update("UPDATE fitness.exercise_movement_patterns SET is_active=false");

        mockMvc.perform(get("/api/v1/admin/exercises/metadata")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories", hasSize(0)))
                .andExpect(jsonPath("$.muscleGroups", hasSize(0)))
                .andExpect(jsonPath("$.equipment", hasSize(0)))
                .andExpect(jsonPath("$.tags", hasSize(0)))
                .andExpect(jsonPath("$.movementPatterns", hasSize(0)))
                .andExpect(jsonPath("$.difficulties", contains("BEGINNER", "INTERMEDIATE", "ADVANCED")));
    }

    @Test
    void draftCreateAndUpdateAcceptEveryActiveMovementPatternNullAndNormalizedCasing() throws Exception {
        for (String movementPattern : MOVEMENT_PATTERNS) {
            mockMvc.perform(post("/api/v1/admin/exercises")
                            .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(completeDraftBody("CREATE_" + movementPattern,
                                    "Create " + movementPattern, movementPattern)))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.movementPattern", is(movementPattern)));
        }

        int expectedVersion = 0;
        for (String movementPattern : MOVEMENT_PATTERNS) {
            mockMvc.perform(put("/api/v1/admin/exercises/{id}", DRAFT_ID)
                            .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"expectedVersion\":" + expectedVersion + ",\"exercise\":" +
                                    completeDraftBody("DRAFT_EXERCISE", "Draft Exercise", movementPattern) + "}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.movementPattern", is(movementPattern)));
            expectedVersion++;
        }

        mockMvc.perform(put("/api/v1/admin/exercises/{id}", DRAFT_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + expectedVersion + ",\"exercise\":" +
                                completeDraftBody("DRAFT_EXERCISE", "Draft Exercise", "squat") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementPattern", is("SQUAT")));
        expectedVersion++;

        String nullableMovementBody = completeDraftBody("DRAFT_EXERCISE", "Draft Exercise", "SQUAT")
                .replace("\"movementPattern\":\"SQUAT\"", "\"movementPattern\":null");
        mockMvc.perform(put("/api/v1/admin/exercises/{id}", DRAFT_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + expectedVersion + ",\"exercise\":" +
                                nullableMovementBody + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementPattern").value(org.hamcrest.Matchers.nullValue()));

        String nullableCreateBody = completeDraftBody("CREATE_NULL_PATTERN", "Create Null Pattern", "SQUAT")
                .replace("\"movementPattern\":\"SQUAT\"", "\"movementPattern\":null");
        mockMvc.perform(post("/api/v1/admin/exercises")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(nullableCreateBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.movementPattern").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void draftMutationRejectsUnknownAndInactiveMovementPatternsButHistoricalDetailRemainsReadable() throws Exception {
        mockMvc.perform(post("/api/v1/admin/exercises")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeDraftBody("UNKNOWN_PATTERN", "Unknown Pattern", "NOT_A_PATTERN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("movementPattern")))
                .andExpect(content().string(not(containsString("foreign key"))))
                .andExpect(content().string(not(containsString("constraint"))));

        jdbcTemplate.update("UPDATE fitness.exercise_movement_patterns SET is_active=false WHERE code='SQUAT'");
        mockMvc.perform(post("/api/v1/admin/exercises")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(completeDraftBody("INACTIVE_PATTERN", "Inactive Pattern", "SQUAT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("movementPattern")));

        mockMvc.perform(put("/api/v1/admin/exercises/{id}", DRAFT_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"exercise\":" +
                                completeDraftBody("DRAFT_EXERCISE", "Draft Exercise", "SQUAT") + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")))
                .andExpect(jsonPath("$.fieldErrors[0].field", is("movementPattern")));

        mockMvc.perform(get("/api/v1/admin/exercises/{id}", ACTIVE_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.movementPattern", is("SQUAT")));
    }

    @Test
    void adminListAndDetailCoverEveryLifecycleWithStablePagination() throws Exception {
        mockMvc.perform(get("/api/v1/admin/exercises")
                        .param("page", "0").param("size", "2")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(4)))
                .andExpect(jsonPath("$.totalPages", is(2)))
                .andExpect(jsonPath("$.items[*].name", contains("Active Exercise", "Archived Exercise")));

        mockMvc.perform(get("/api/v1/admin/exercises")
                        .param("status", "ARCHIVED")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));

        mockMvc.perform(get("/api/v1/admin/exercises/{id}", DRAFT_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.version", is(0)))
                .andExpect(jsonPath("$.deletedAt").doesNotExist());
    }

    @Test
    void createEditActivateArchiveAndAuditUseOptimisticVersioning() throws Exception {
        String createBody = completeDraftBody("CREATED_EXERCISE", "Created Exercise", "SQUAT");
        String response = mockMvc.perform(post("/api/v1/admin/exercises")
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createBody))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andReturn().getResponse().getContentAsString();
        UUID createdId = UUID.fromString(response.substring(response.indexOf("\"id\":\"") + 6,
                response.indexOf("\"", response.indexOf("\"id\":\"") + 6)));

        mockMvc.perform(put("/api/v1/admin/exercises/{id}", createdId)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"exercise\":" +
                                completeDraftBody("CREATED_EXERCISE", "Updated Exercise", "HINGE") + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Updated Exercise")))
                .andExpect(jsonPath("$.version", is(1)));

        mockMvc.perform(post("/api/v1/admin/exercises/{id}/activate", createdId)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.version", is(2)));

        mockMvc.perform(put("/api/v1/admin/exercises/{id}", createdId)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"exercise\":" + createBody + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_LIFECYCLE_CONFLICT")));

        mockMvc.perform(post("/api/v1/admin/exercises/{id}/archive", createdId)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":2,\"reason\":\"Superseded\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ARCHIVED")))
                .andExpect(jsonPath("$.version", is(3)));

        mockMvc.perform(post("/api/v1/admin/exercises/{id}/activate", createdId)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":3}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_LIFECYCLE_CONFLICT")));

        Integer audits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs WHERE target_id = ?", Integer.class, createdId);
        assertThat(audits).isEqualTo(4);
    }

    @Test
    void activationRejectsIncompleteDraftAndStaleVersion() throws Exception {
        mockMvc.perform(post("/api/v1/admin/exercises/{id}/activate", DRAFT_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"expectedVersion\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode", is("VALIDATION_FAILED")));

        mockMvc.perform(put("/api/v1/admin/exercises/{id}", DRAFT_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":9,\"exercise\":" +
                                completeDraftBody("DRAFT_EXERCISE", "Draft Exercise", "SQUAT") + "}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_VERSION_CONFLICT")));
    }

    @Test
    void canonicalReplacementCanSetChangeAndClearButRejectsInvalidTargetsAndCycles() throws Exception {
        mockMvc.perform(put("/api/v1/admin/exercises/{id}/canonical-replacement", ARCHIVED_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"targetExerciseId\":\"" + ACTIVE_ID +
                                "\",\"reason\":\"Duplicate\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalReplacementId", is(ACTIVE_ID.toString())))
                .andExpect(jsonPath("$.version", is(1)));

        mockMvc.perform(put("/api/v1/admin/exercises/{id}/canonical-replacement", ARCHIVED_TWO_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":0,\"targetExerciseId\":\"" + ARCHIVED_ID +
                                "\",\"reason\":\"Chain\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_CANONICAL_CONFLICT")));

        mockMvc.perform(put("/api/v1/admin/exercises/{id}/canonical-replacement", ARCHIVED_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"targetExerciseId\":null,\"reason\":\"Clear\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canonicalReplacementId").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.version", is(2)));

        mockMvc.perform(put("/api/v1/admin/exercises/{id}/canonical-replacement", ARCHIVED_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1,\"targetExerciseId\":null,\"reason\":\"Stale\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_VERSION_CONFLICT")));
    }

    @Test
    void canonicalReplacementPreviewIsAuthorizedReadOnlyAndUsesNullForUnavailableUsage() throws Exception {
        jdbcTemplate.update("""
                INSERT INTO fitness.exercise_canonical_mappings(
                    duplicate_exercise_id, canonical_exercise_id, mapped_by, reason)
                VALUES (?, ?, ?, 'Duplicate')
                """, ARCHIVED_ID, ACTIVE_ID, ADMIN_ID);
        Long versionBefore = jdbcTemplate.queryForObject(
                "SELECT version FROM fitness.exercises WHERE id = ?", Long.class, ARCHIVED_ID);
        Integer auditsBefore = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM fitness.audit_logs", Integer.class);

        mockMvc.perform(get("/api/v1/admin/exercises/{id}/canonical-replacement/preview", ARCHIVED_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sourceExercise.id", is(ARCHIVED_ID.toString())))
                .andExpect(jsonPath("$.sourceExercise.status", is("ARCHIVED")))
                .andExpect(jsonPath("$.expectedVersion", is(versionBefore.intValue())))
                .andExpect(jsonPath("$.currentTarget.id", is(ACTIVE_ID.toString())))
                .andExpect(jsonPath("$.currentTarget.status", is("ACTIVE")))
                .andExpect(jsonPath("$.usageImpact.availability", is("NOT_AVAILABLE")))
                .andExpect(jsonPath("$.usageImpact.count").value(org.hamcrest.Matchers.nullValue()));

        assertThat(jdbcTemplate.queryForObject(
                "SELECT version FROM fitness.exercises WHERE id = ?", Long.class, ARCHIVED_ID))
                .isEqualTo(versionBefore);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fitness.audit_logs", Integer.class))
                .isEqualTo(auditsBefore);

        mockMvc.perform(get("/api/v1/admin/exercises/{id}/canonical-replacement/preview", ACTIVE_ID)
                        .header("Authorization", bearer(ADMIN_ID, "ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_LIFECYCLE_CONFLICT")));
    }

    @Test
    void publicCatalogStillReturnsOnlyActiveExercise() throws Exception {
        mockMvc.perform(get("/api/v1/exercises").header("Authorization", bearer(UUID.randomUUID(), "STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id", is(ACTIVE_ID.toString())));

        mockMvc.perform(get("/api/v1/exercises/{id}", ARCHIVED_ID)
                        .header("Authorization", bearer(UUID.randomUUID(), "TRAINER")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.errorCode", is("EXERCISE_NOT_FOUND")));
    }

    private String completeDraftBody(String code, String name, String movementPattern) {
        return """
                {"code":"%s","name":"%s","categoryCode":"STRENGTH",
                 "description":"Synthetic description","instructions":"Synthetic instructions",
                 "difficulty":"BEGINNER","movementPattern":"%s","unilateral":false,
                 "tagCodes":["COMPOUND"],"variations":[{"code":"%s_STANDARD",
                 "name":"Standard","difficulty":"BEGINNER","defaultVariation":true,"active":true,
                 "muscles":[{"muscleGroupCode":"CORE","involvement":"PRIMARY"}],
                 "equipment":[{"equipmentCode":"BODYWEIGHT","requirement":"REQUIRED"}]}]}
                """.formatted(code, name, movementPattern, code);
    }

    private String bearer(UUID userId, String role) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder().issuer(jwtProperties.getIssuer())
                .subject(userId.toString()).issuedAt(now).expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .id(UUID.randomUUID().toString()).claim("email", role.toLowerCase() + "@example.com")
                .claim("roles", List.of(role)).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(
                JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }

    private void insertCatalogFixtures() {
        jdbcTemplate.execute("""
                INSERT INTO fitness.exercise_categories(code,name) VALUES ('STRENGTH','Strength');
                INSERT INTO fitness.muscle_groups(code,name) VALUES ('CORE','Core');
                INSERT INTO fitness.equipment(code,name) VALUES ('BODYWEIGHT','Bodyweight');
                INSERT INTO fitness.exercise_tags(code,name) VALUES ('COMPOUND','Compound');
                INSERT INTO fitness.exercises(id,code,name,category_id,difficulty,movement_pattern,admin_status)
                SELECT x.id::uuid,x.code,x.name,c.id,x.difficulty,x.pattern,x.status
                FROM (VALUES
                  ('10000000-0000-0000-0000-000000000001','DRAFT_EXERCISE','Draft Exercise',null,null,'DRAFT'),
                  ('10000000-0000-0000-0000-000000000002','ACTIVE_EXERCISE','Active Exercise','BEGINNER','SQUAT','ACTIVE'),
                  ('10000000-0000-0000-0000-000000000003','ARCHIVED_EXERCISE','Archived Exercise','BEGINNER','SQUAT','ARCHIVED'),
                  ('10000000-0000-0000-0000-000000000004','ARCHIVED_TWO','Archived Two','BEGINNER','HINGE','ARCHIVED')
                ) x(id,code,name,difficulty,pattern,status)
                JOIN fitness.exercise_categories c ON c.code='STRENGTH';
                INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,difficulty,is_default,is_active)
                VALUES ('20000000-0000-0000-0000-000000000002',
                        '10000000-0000-0000-0000-000000000002','ACTIVE_STANDARD','Standard','BEGINNER',true,true);
                """);
    }

    private void verifyIsolatedDatabase() throws SQLException {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String actualUrl = connection.getMetaData().getURL();
            if (!actualUrl.contains(":" + postgres.getMappedPort(5432)) || actualUrl.contains(":5433")) {
                throw new IllegalStateException("Refusing to truncate non-isolated database: " + actualUrl);
            }
        }
    }
}
