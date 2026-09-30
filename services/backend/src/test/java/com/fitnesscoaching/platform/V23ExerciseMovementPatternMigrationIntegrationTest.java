package com.fitnesscoaching.platform;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import com.fitnesscoaching.platform.modules.exercise.domain.ExerciseDifficulty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class V23ExerciseMovementPatternMigrationIntegrationTest {

    @Test
    void v23CreatesVocabularyForeignKeyAndRepeatableDevelopmentSeed() throws IOException {
        try (PostgreSQLContainer<?> postgres = postgres("exercise_v23")) {
            postgres.start();
            DataSource dataSource = dataSource(postgres);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .target("22").load().migrate();
            jdbc.update("INSERT INTO fitness.exercises(code,name,movement_pattern) VALUES ('VALID_PATTERN','Valid','SQUAT')");
            jdbc.update("INSERT INTO fitness.exercises(code,name,movement_pattern) VALUES ('NULL_PATTERN','Null',null)");
            jdbc.update("INSERT INTO fitness.exercises(code,name,movement_pattern) VALUES ('LOWER_PATTERN','Lower','hinge')");

            var result = Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .target("23").load().migrate();

            assertThat(result.success).isTrue();
            assertThat(jdbc.queryForObject(
                    "SELECT movement_pattern FROM fitness.exercises WHERE code='LOWER_PATTERN'", String.class))
                    .isEqualTo("HINGE");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.exercise_movement_patterns",
                    Integer.class)).isEqualTo(12);
            String openApi = Files.readString(Path.of("..", "..", "contracts", "openapi", "openapi.yaml"));
            assertThat(enumValues(openApi, "ExerciseMovementPattern"))
                    .containsExactlyInAnyOrderElementsOf(jdbc.queryForList(
                            "SELECT code FROM fitness.exercise_movement_patterns", String.class));
            assertThat(enumValues(openApi, "ExerciseDifficulty"))
                    .containsExactly(Arrays.stream(ExerciseDifficulty.values()).map(Enum::name).toArray(String[]::new));
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO fitness.exercises(code,name,movement_pattern) VALUES ('BAD_PATTERN','Bad','OTHER')"))
                    .isInstanceOf(DataIntegrityViolationException.class);

            String seed = Files.readString(Path.of("..", "..", "database", "seed", "dev_seed.sql"));
            jdbc.execute(seed);
            jdbc.execute(seed);
            assertThat(jdbc.queryForObject(
                    "SELECT movement_pattern FROM fitness.exercises WHERE code='SYNTH_STATIONARY_CYCLE'",
                    String.class)).isEqualTo("LOCOMOTION");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fitness.exercise_movement_patterns",
                    Integer.class)).isEqualTo(12);
        }
    }

    @Test
    void v23FailsClearlyWhenExistingMovementPatternIsUnknown() {
        try (PostgreSQLContainer<?> postgres = postgres("exercise_v23_unknown")) {
            postgres.start();
            DataSource dataSource = dataSource(postgres);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            Flyway.configure().dataSource(dataSource).schemas("fitness").defaultSchema("fitness")
                    .target("22").load().migrate();
            jdbc.update("INSERT INTO fitness.exercises(code,name,movement_pattern) VALUES ('LEGACY_UNKNOWN','Legacy','OTHER')");

            assertThatThrownBy(() -> Flyway.configure().dataSource(dataSource).schemas("fitness")
                    .defaultSchema("fitness").target("23").load().migrate())
                    .isInstanceOf(FlywayException.class)
                    .hasMessageContaining("V23 cannot migrate unknown Exercise movement patterns: OTHER");
        }
    }

    private PostgreSQLContainer<?> postgres(String databaseName) {
        return new PostgreSQLContainer<>(
                DockerImageName.parse("pgvector/pgvector:pg18").asCompatibleSubstituteFor("postgres"))
                .withDatabaseName(databaseName).withUsername("test_user").withPassword("test_pass");
    }

    private DataSource dataSource(PostgreSQLContainer<?> postgres) {
        return new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private List<String> enumValues(String openApi, String schema) {
        var matcher = Pattern.compile(schema + ":\\R\\s+type: string\\R\\s+enum: \\[(?<values>[^]]+)]")
                .matcher(openApi);
        assertThat(matcher.find()).as("OpenAPI enum %s", schema).isTrue();
        return Arrays.stream(matcher.group("values").split(","))
                .map(String::trim)
                .toList();
    }
}
