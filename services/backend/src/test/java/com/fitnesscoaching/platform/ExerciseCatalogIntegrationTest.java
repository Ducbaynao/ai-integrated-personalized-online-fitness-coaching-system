package com.fitnesscoaching.platform;

import com.fitnesscoaching.platform.common.config.JwtProperties;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseCatalogQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.out.ExerciseCatalogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ExerciseCatalogIntegrationTest {

    private static final UUID ACTIVE_SQUAT_ID = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID DRAFT_ID = UUID.fromString("10000000-0000-0000-0000-000000000007");
    private static final UUID ARCHIVED_ID = UUID.fromString("10000000-0000-0000-0000-000000000008");
    private static final UUID SECOND_ARCHIVED_ID = UUID.fromString("10000000-0000-0000-0000-000000000009");

    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres")
    )
            .withDatabaseName("digital_fitness")
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
    private JwtEncoder jwtEncoder;

    @Autowired
    private JwtProperties jwtProperties;

    @Autowired
    private ExerciseCatalogRepository repository;

    @BeforeEach
    void setUp() throws SQLException {
        verifyIsolatedDatabase();
        jdbcTemplate.execute("""
                TRUNCATE TABLE fitness.exercise_guidance, fitness.exercise_tag_assignments,
                fitness.exercise_tags, fitness.exercise_media, fitness.exercise_equipment,
                fitness.exercise_muscles, fitness.exercise_variations, fitness.exercises,
                fitness.equipment, fitness.muscle_groups, fitness.exercise_categories,
                fitness.media_files CASCADE
                """);
        insertFixtures();
    }

    @Test
    void studentAndTrainerCanReadOnlyActiveCatalog() throws Exception {
        for (String role : List.of("STUDENT", "TRAINER")) {
            mockMvc.perform(get("/api/v1/exercises")
                            .header("Authorization", bearer(role)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.totalItems", is(6)))
                    .andExpect(jsonPath("$.items", hasSize(6)))
                    .andExpect(jsonPath("$.items[*].code", everyItem(not("SYNTH_DRAFT_HINGE"))))
                    .andExpect(jsonPath("$.items[*].code", everyItem(not("SYNTH_ARCHIVED_CARRY"))))
                    .andExpect(jsonPath("$.items[*].code", everyItem(not("SYNTH_ARCHIVED_STEP"))));
        }
    }

    @Test
    void unauthenticatedAndUnsupportedRoleAreRejected() throws Exception {
        mockMvc.perform(get("/api/v1/exercises"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/api/v1/exercises")
                        .header("Authorization", bearer("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeDetailMapsVariationsAndMissingMediaWithoutStorageDetails() throws Exception {
        mockMvc.perform(get("/api/v1/exercises/{id}", ACTIVE_SQUAT_ID)
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("SYNTH_BODYWEIGHT_SQUAT")))
                .andExpect(jsonPath("$.variations", hasSize(2)))
                .andExpect(jsonPath("$.variations[0].muscles[*].code", hasItem("QUADRICEPS")))
                .andExpect(jsonPath("$.variations[0].equipment[*].code", hasItem("BODYWEIGHT")))
                .andExpect(jsonPath("$.variations[*].media", everyItem(hasSize(0))))
                .andExpect(jsonPath("$.mediaAvailable", is(false)))
                .andExpect(jsonPath("$.bucketName").doesNotExist())
                .andExpect(jsonPath("$.objectKey").doesNotExist());

        var detail = repository.findActiveDetail(ACTIVE_SQUAT_ID).orElseThrow();
        assertThat(detail.variations()).hasSize(2);
        assertThat(detail.variations().getFirst().muscles()).extracting("code")
                .contains("QUADRICEPS", "GLUTES", "CORE");
    }

    @Test
    void nonVisibleAndMissingDetailUseSameNotFoundBehavior() throws Exception {
        for (UUID id : List.of(DRAFT_ID, ARCHIVED_ID, SECOND_ARCHIVED_ID, UUID.randomUUID())) {
            mockMvc.perform(get("/api/v1/exercises/{id}", id)
                            .header("Authorization", bearer("TRAINER")))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.errorCode", is("EXERCISE_NOT_FOUND")));
        }
    }

    @Test
    void searchMatchesExerciseAndVariationTextLiterally() throws Exception {
        mockMvc.perform(get("/api/v1/exercises")
                        .param("query", "bodyweight squat")
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].code", is("SYNTH_BODYWEIGHT_SQUAT")));

        mockMvc.perform(get("/api/v1/exercises")
                        .param("query", "pause")
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].variationCount", is(2)));

        mockMvc.perform(get("/api/v1/exercises")
                        .param("query", "%")
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    @Test
    void filtersEachDimensionAndCombinesOrWithinAndAcrossDimensions() throws Exception {
        assertSingleFilter("categoryCodes", "MOBILITY", "SYNTH_MOBILITY_REACH");
        assertSingleFilter("muscleGroupCodes", "BACK", "SYNTH_ASSISTED_ROW");
        assertSingleFilter("equipmentCodes", "CABLE_MACHINE", "SYNTH_CABLE_PRESS");
        assertSingleFilter("tagCodes", "CARDIO", "SYNTH_STATIONARY_CYCLE");
        assertSingleFilter("difficulties", "INTERMEDIATE", "SYNTH_CABLE_PRESS", "SYNTH_UNILATERAL_LUNGE");
        assertSingleFilter("movementPatterns", "LUNGE", "SYNTH_UNILATERAL_LUNGE");

        mockMvc.perform(get("/api/v1/exercises")
                        .param("categoryCodes", "STRENGTH", "MOBILITY")
                        .param("muscleGroupCodes", "QUADRICEPS", "BACK")
                        .param("equipmentCodes", "BODYWEIGHT", "RESISTANCE_BAND")
                        .header("Authorization", bearer("TRAINER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].code", containsInAnyOrder(
                        "SYNTH_ASSISTED_ROW", "SYNTH_BODYWEIGHT_SQUAT")));
    }

    @Test
    void paginationIsBoundedAndDeterministicallySorted() throws Exception {
        mockMvc.perform(get("/api/v1/exercises")
                        .param("page", "0")
                        .param("size", "2")
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems", is(6)))
                .andExpect(jsonPath("$.totalPages", is(3)))
                .andExpect(jsonPath("$.items[*].code", contains(
                        "SYNTH_ASSISTED_ROW", "SYNTH_BODYWEIGHT_SQUAT")));

        mockMvc.perform(get("/api/v1/exercises")
                        .param("page", "1")
                        .param("size", "2")
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].code", contains(
                        "SYNTH_CABLE_PRESS", "SYNTH_MOBILITY_REACH")));

        mockMvc.perform(get("/api/v1/exercises")
                        .param("size", "101")
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void filterMetadataOnlyContainsValuesUsedByActiveCatalog() throws Exception {
        mockMvc.perform(get("/api/v1/exercises/filter-metadata")
                        .header("Authorization", bearer("TRAINER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories[*].code", containsInAnyOrder("CARDIO", "MOBILITY", "STRENGTH")))
                .andExpect(jsonPath("$.muscleGroups[*].code", hasItem("QUADRICEPS")))
                .andExpect(jsonPath("$.equipment[*].code", hasItem("BODYWEIGHT")))
                .andExpect(jsonPath("$.tags[*].code", hasItem("BEGINNER_FRIENDLY")))
                .andExpect(jsonPath("$.tags[*].code", not(hasItem("HIDDEN_ONLY"))))
                .andExpect(jsonPath("$.difficulties", containsInAnyOrder("BEGINNER", "INTERMEDIATE")))
                .andExpect(jsonPath("$.movementPatterns", hasItem("SQUAT")));
    }

    private void assertSingleFilter(String name, String value, String... expectedCodes) throws Exception {
        mockMvc.perform(get("/api/v1/exercises")
                        .param(name, value)
                        .header("Authorization", bearer("STUDENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].code", containsInAnyOrder(expectedCodes)));
    }

    private String bearer(String role) {
        UUID userId = UUID.randomUUID();
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(jwtProperties.getIssuer())
                .subject(userId.toString())
                .issuedAt(now)
                .expiresAt(now.plus(15, ChronoUnit.MINUTES))
                .id(UUID.randomUUID().toString())
                .claim("email", role.toLowerCase() + "@example.com")
                .claim("roles", List.of(role))
                .build();
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256).build();
        return "Bearer " + jwtEncoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
    }

    private void verifyIsolatedDatabase() throws SQLException {
        try (Connection connection = jdbcTemplate.getDataSource().getConnection()) {
            String actualUrl = connection.getMetaData().getURL();
            int containerPort = postgres.getMappedPort(5432);
            if (!actualUrl.contains(":" + containerPort) || actualUrl.contains(":5433")) {
                throw new IllegalStateException("Refusing to truncate non-isolated database: " + actualUrl);
            }
        }
    }

    private void insertFixtures() {
        jdbcTemplate.execute("""
                INSERT INTO fitness.exercise_categories(code, name) VALUES
                ('STRENGTH','Strength'),('CARDIO','Cardio'),('MOBILITY','Mobility');
                INSERT INTO fitness.muscle_groups(code, name) VALUES
                ('QUADRICEPS','Quadriceps'),('GLUTES','Glutes'),('CORE','Core'),
                ('BACK','Back'),('BICEPS','Biceps'),('CHEST','Chest'),
                ('TRICEPS','Triceps'),('SHOULDERS','Shoulders');
                INSERT INTO fitness.equipment(code, name) VALUES
                ('BODYWEIGHT','Bodyweight'),('RESISTANCE_BAND','Resistance Band'),
                ('PULLUP_BAR','Pull-up Bar'),('CABLE_MACHINE','Cable Machine'),
                ('BENCH','Bench'),('STATIONARY_BIKE','Stationary Bike'),('DUMBBELL','Dumbbell');
                INSERT INTO fitness.exercise_tags(code, name) VALUES
                ('BEGINNER_FRIENDLY','Beginner Friendly'),('BODYWEIGHT','Bodyweight'),
                ('COMPOUND','Compound'),('CARDIO','Cardio'),('MOBILITY','Mobility'),
                ('HIDDEN_ONLY','Hidden Only');
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.exercises(id,code,name,category_id,description,instructions,difficulty,movement_pattern,unilateral,admin_status)
                SELECT x.id::uuid,x.code,x.name,c.id,x.description,x.instructions,x.difficulty,x.pattern,x.unilateral,x.status
                FROM (VALUES
                ('10000000-0000-0000-0000-000000000001','SYNTH_BODYWEIGHT_SQUAT','Synthetic Bodyweight Squat','STRENGTH','Synthetic squat description','Synthetic squat instructions','BEGINNER','SQUAT',false,'ACTIVE'),
                ('10000000-0000-0000-0000-000000000002','SYNTH_ASSISTED_ROW','Synthetic Assisted Row','STRENGTH','Synthetic row description','Synthetic row instructions','BEGINNER','PULL',false,'ACTIVE'),
                ('10000000-0000-0000-0000-000000000003','SYNTH_CABLE_PRESS','Synthetic Cable Press','STRENGTH','Synthetic press description','Synthetic press instructions','INTERMEDIATE','PUSH',false,'ACTIVE'),
                ('10000000-0000-0000-0000-000000000004','SYNTH_MOBILITY_REACH','Synthetic Mobility Reach','MOBILITY','Synthetic mobility description','Synthetic mobility instructions','BEGINNER','MOBILITY',false,'ACTIVE'),
                ('10000000-0000-0000-0000-000000000005','SYNTH_STATIONARY_CYCLE','Synthetic Stationary Cycle','CARDIO','Synthetic cycle description','Synthetic cycle instructions','BEGINNER','LOCOMOTION',false,'ACTIVE'),
                ('10000000-0000-0000-0000-000000000006','SYNTH_UNILATERAL_LUNGE','Synthetic Unilateral Lunge','STRENGTH','Synthetic lunge description','Synthetic lunge instructions','INTERMEDIATE','LUNGE',true,'ACTIVE'),
                ('10000000-0000-0000-0000-000000000007','SYNTH_DRAFT_HINGE','Synthetic Draft Hinge','STRENGTH','Draft','Draft','BEGINNER','HINGE',false,'DRAFT'),
                ('10000000-0000-0000-0000-000000000008','SYNTH_ARCHIVED_CARRY','Synthetic Archived Carry','STRENGTH','Archived','Archived','INTERMEDIATE','CARRY',false,'ARCHIVED'),
                ('10000000-0000-0000-0000-000000000009','SYNTH_ARCHIVED_STEP','Synthetic Archived Step','STRENGTH','Archived','Archived','BEGINNER','LUNGE',true,'ARCHIVED')
                ) x(id,code,name,category_code,description,instructions,difficulty,pattern,unilateral,status)
                JOIN fitness.exercise_categories c ON c.code=x.category_code;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.exercise_variations(id,exercise_id,code,name,description,instructions,difficulty,is_default,is_active)
                SELECT x.id::uuid,e.id,x.code,x.name,'Synthetic variation',x.instructions,x.difficulty,x.is_default,true
                FROM (VALUES
                ('20000000-0000-0000-0000-000000000001','SYNTH_BODYWEIGHT_SQUAT','SYNTH_BODYWEIGHT_SQUAT_STANDARD','Synthetic Bodyweight Squat - Standard','Standard instructions','BEGINNER',true),
                ('20000000-0000-0000-0000-000000000002','SYNTH_BODYWEIGHT_SQUAT','SYNTH_BODYWEIGHT_SQUAT_PAUSE','Synthetic Bodyweight Squat - Pause','Pause instructions','INTERMEDIATE',false),
                ('20000000-0000-0000-0000-000000000003','SYNTH_ASSISTED_ROW','SYNTH_ASSISTED_ROW_BAND','Synthetic Assisted Row - Band','Band instructions','BEGINNER',true),
                ('20000000-0000-0000-0000-000000000004','SYNTH_CABLE_PRESS','SYNTH_CABLE_PRESS_STANDARD','Synthetic Cable Press - Standard','Press instructions','INTERMEDIATE',true),
                ('20000000-0000-0000-0000-000000000005','SYNTH_MOBILITY_REACH','SYNTH_MOBILITY_REACH_STANDARD','Synthetic Mobility Reach - Standard','Reach instructions','BEGINNER',true),
                ('20000000-0000-0000-0000-000000000006','SYNTH_STATIONARY_CYCLE','SYNTH_STATIONARY_CYCLE_STEADY','Synthetic Stationary Cycle - Steady','Cycle instructions','BEGINNER',true),
                ('20000000-0000-0000-0000-000000000007','SYNTH_UNILATERAL_LUNGE','SYNTH_UNILATERAL_LUNGE_DUMBBELL','Synthetic Unilateral Lunge - Dumbbell','Lunge instructions','INTERMEDIATE',true),
                ('20000000-0000-0000-0000-000000000008','SYNTH_DRAFT_HINGE','SYNTH_DRAFT_HINGE_STANDARD','Synthetic Draft Hinge - Standard','Draft instructions','BEGINNER',true),
                ('20000000-0000-0000-0000-000000000009','SYNTH_ARCHIVED_CARRY','SYNTH_ARCHIVED_CARRY_STANDARD','Synthetic Archived Carry - Standard','Archived instructions','INTERMEDIATE',true),
                ('20000000-0000-0000-0000-000000000010','SYNTH_ARCHIVED_STEP','SYNTH_ARCHIVED_STEP_STANDARD','Synthetic Archived Step - Standard','Archived instructions','BEGINNER',true)
                ) x(id,exercise_code,code,name,instructions,difficulty,is_default)
                JOIN fitness.exercises e ON e.code=x.exercise_code;
                """);

        jdbcTemplate.execute("""
                INSERT INTO fitness.exercise_muscles(exercise_variation_id,muscle_group_id,involvement)
                SELECT v.id,mg.id,x.involvement FROM (VALUES
                ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','QUADRICEPS','PRIMARY'),
                ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','GLUTES','PRIMARY'),
                ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','CORE','STABILIZER'),
                ('SYNTH_BODYWEIGHT_SQUAT_PAUSE','QUADRICEPS','PRIMARY'),
                ('SYNTH_ASSISTED_ROW_BAND','BACK','PRIMARY'),
                ('SYNTH_ASSISTED_ROW_BAND','BICEPS','SECONDARY'),
                ('SYNTH_CABLE_PRESS_STANDARD','CHEST','PRIMARY'),
                ('SYNTH_CABLE_PRESS_STANDARD','TRICEPS','SECONDARY'),
                ('SYNTH_MOBILITY_REACH_STANDARD','SHOULDERS','PRIMARY'),
                ('SYNTH_STATIONARY_CYCLE_STEADY','QUADRICEPS','PRIMARY'),
                ('SYNTH_UNILATERAL_LUNGE_DUMBBELL','QUADRICEPS','PRIMARY'),
                ('SYNTH_UNILATERAL_LUNGE_DUMBBELL','GLUTES','PRIMARY')) x(variation_code,muscle_code,involvement)
                JOIN fitness.exercise_variations v ON v.code=x.variation_code
                JOIN fitness.muscle_groups mg ON mg.code=x.muscle_code;

                INSERT INTO fitness.exercise_equipment(exercise_variation_id,equipment_id,requirement)
                SELECT v.id,eq.id,x.requirement FROM (VALUES
                ('SYNTH_BODYWEIGHT_SQUAT_STANDARD','BODYWEIGHT','REQUIRED'),
                ('SYNTH_BODYWEIGHT_SQUAT_PAUSE','BODYWEIGHT','REQUIRED'),
                ('SYNTH_ASSISTED_ROW_BAND','RESISTANCE_BAND','REQUIRED'),
                ('SYNTH_ASSISTED_ROW_BAND','PULLUP_BAR','ALTERNATIVE'),
                ('SYNTH_CABLE_PRESS_STANDARD','CABLE_MACHINE','REQUIRED'),
                ('SYNTH_CABLE_PRESS_STANDARD','BENCH','OPTIONAL'),
                ('SYNTH_MOBILITY_REACH_STANDARD','BODYWEIGHT','REQUIRED'),
                ('SYNTH_STATIONARY_CYCLE_STEADY','STATIONARY_BIKE','REQUIRED'),
                ('SYNTH_UNILATERAL_LUNGE_DUMBBELL','DUMBBELL','REQUIRED')) x(variation_code,equipment_code,requirement)
                JOIN fitness.exercise_variations v ON v.code=x.variation_code
                JOIN fitness.equipment eq ON eq.code=x.equipment_code;

                INSERT INTO fitness.exercise_tag_assignments(exercise_id,exercise_tag_id)
                SELECT e.id,t.id FROM (VALUES
                ('SYNTH_BODYWEIGHT_SQUAT','BEGINNER_FRIENDLY'),('SYNTH_BODYWEIGHT_SQUAT','BODYWEIGHT'),
                ('SYNTH_BODYWEIGHT_SQUAT','COMPOUND'),('SYNTH_ASSISTED_ROW','COMPOUND'),
                ('SYNTH_CABLE_PRESS','COMPOUND'),('SYNTH_MOBILITY_REACH','MOBILITY'),
                ('SYNTH_STATIONARY_CYCLE','CARDIO'),('SYNTH_UNILATERAL_LUNGE','COMPOUND'),
                ('SYNTH_DRAFT_HINGE','HIDDEN_ONLY')) x(exercise_code,tag_code)
                JOIN fitness.exercises e ON e.code=x.exercise_code
                JOIN fitness.exercise_tags t ON t.code=x.tag_code;
                """);
    }
}
