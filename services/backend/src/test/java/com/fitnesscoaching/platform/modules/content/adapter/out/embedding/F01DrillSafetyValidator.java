package com.fitnesscoaching.platform.modules.content.adapter.out.embedding;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Isolated validation utilities for F01 Reindex/Rollback Drill database safety.
 * This class has NO dependency on Spring, Testcontainers, or Docker, ensuring fast,
 * isolated unit test execution without triggering container lifecycle initialization.
 */
public final class F01DrillSafetyValidator {

    public static final Set<Integer> FORBIDDEN_PORTS = Set.of(5432, 5433);
    private static final Pattern JDBC_URL_PATTERN = Pattern.compile("^jdbc:postgresql://([^:/]+):(\\d+)/([^?]+)(?:\\?.*)?$");

    private F01DrillSafetyValidator() {
        // Utility class
    }

    /**
     * Validates that the provided JDBC URL matches the expected PostgreSQL format,
     * does not target forbidden ports (5432 default, 5433 baseline testbed),
     * and targets an isolated drill database.
     *
     * @param jdbcUrl JDBC URL to validate
     * @throws IllegalArgumentException if jdbcUrl is null or blank
     * @throws IllegalStateException if URL format is invalid, port is forbidden, or database is non-drill/protected
     */
    public static void validateDrillJdbcUrl(String jdbcUrl) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("SAFETY GUARD: DRILL_DATABASE_URL cannot be null or blank");
        }
        Matcher matcher = JDBC_URL_PATTERN.matcher(jdbcUrl.trim());
        if (!matcher.matches()) {
            throw new IllegalStateException("SAFETY GUARD: DRILL_DATABASE_URL does not match expected format jdbc:postgresql://host:port/database! Attempted: " + jdbcUrl);
        }
        int port = Integer.parseInt(matcher.group(2));
        String dbName = matcher.group(3);

        if (FORBIDDEN_PORTS.contains(port)) {
            throw new IllegalStateException("SAFETY GUARD: DRILL_DATABASE_URL targets forbidden port " + port + "! (Port 5432 is default Postgres, port 5433 is baseline testbed). Attempted: " + jdbcUrl);
        }
        if (!dbName.toLowerCase().contains("drill") || "digital_fitness".equalsIgnoreCase(dbName) || "postgres".equalsIgnoreCase(dbName) || "fitness".equalsIgnoreCase(dbName)) {
            throw new IllegalStateException("SAFETY GUARD: DRILL_DATABASE_URL must target an isolated drill database! Attempted DB: " + dbName);
        }
    }

    /**
     * Validates that the active runtime database name is an allowable drill database.
     * Used prior to any fixture cleanup or destructive resets.
     *
     * @param currentDb Name of current database from SELECT current_database()
     * @throws IllegalStateException if database name is non-drill or protected
     */
    public static void validateTargetDatabase(String currentDb) {
        if (currentDb == null || !currentDb.toLowerCase().contains("drill")) {
            throw new IllegalStateException("SAFETY GUARD: Refusing destructive fixture reset on non-drill database: " + currentDb);
        }
        if ("digital_fitness".equalsIgnoreCase(currentDb) || "fitness".equalsIgnoreCase(currentDb) || "postgres".equalsIgnoreCase(currentDb)) {
            throw new IllegalStateException("SAFETY GUARD: Refusing destructive fixture reset on protected database: " + currentDb);
        }
    }
}
