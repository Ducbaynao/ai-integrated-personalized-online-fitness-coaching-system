package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit verification for F01 Reindex/Rollback Drill safety guards.
 * Validates structural JDBC parsing and strict boundary enforcement
 * without requiring Docker or a Spring context.
 */
@DisplayName("F01 Drill Safety Guard Unit Test")
class F01DrillSafetyGuardTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5435/digital_fitness_drill",
            "jdbc:postgresql://127.0.0.1:5436/test_drill_db",
            "jdbc:postgresql://host.docker.internal:5440/fitness_drill_app?ssl=false",
            "jdbc:postgresql://192.168.1.10:5499/ci_drill_pgvector"
    })
    @DisplayName("Accepts valid isolated drill JDBC URLs across allowable ports and hosts")
    void acceptsValidIsolatedDrillUrls(String validUrl) {
        assertThatCode(() -> F01ReindexRollbackDrillIntegrationTest.validateDrillJdbcUrl(validUrl))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5432/digital_fitness_drill",
            "jdbc:postgresql://127.0.0.1:5433/digital_fitness_drill",
            "jdbc:postgresql://10.0.0.1:5433/any_drill_db"
    })
    @DisplayName("Strictly rejects forbidden ports (5432 default Postgres, 5433 baseline testbed)")
    void rejectsForbiddenPorts(String forbiddenPortUrl) {
        assertThatThrownBy(() -> F01ReindexRollbackDrillIntegrationTest.validateDrillJdbcUrl(forbiddenPortUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("forbidden port");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:postgresql://localhost:5435/digital_fitness",
            "jdbc:postgresql://localhost:5435/postgres",
            "jdbc:postgresql://localhost:5435/fitness",
            "jdbc:postgresql://localhost:5435/production_data"
    })
    @DisplayName("Strictly rejects non-drill or protected system/production databases")
    void rejectsProtectedOrNonDrillDatabases(String protectedDbUrl) {
        assertThatThrownBy(() -> F01ReindexRollbackDrillIntegrationTest.validateDrillJdbcUrl(protectedDbUrl))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must target an isolated drill database");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "postgresql://localhost:5435/drill",
            "jdbc:mysql://localhost:5435/drill",
            "jdbc:postgresql://localhost/drill",
            "jdbc:postgresql://localhost:notaport/drill",
            "not_even_a_url"
    })
    @DisplayName("Strictly rejects null, blank, or malformed JDBC URLs")
    void rejectsMalformedUrls(String malformedUrl) {
        assertThatThrownBy(() -> F01ReindexRollbackDrillIntegrationTest.validateDrillJdbcUrl(malformedUrl))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("Strictly rejects null input")
    void rejectsNullInput() {
        assertThatThrownBy(() -> F01ReindexRollbackDrillIntegrationTest.validateDrillJdbcUrl(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
