package com.fitnesscoaching.platform;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Objects;

/**
 * Test-only import of a pre-V45 row into an already migrated disposable database.
 * V45 intentionally forbids new unguarded inserts; these fixtures represent
 * historical rows that existed before its insert trigger was installed. Only
 * that V45 trigger is disabled inside this disposable-DB transaction; all
 * earlier FK, lifecycle and link guards stay active.
 */
final class LegacyAppointmentFixture {
    private LegacyAppointmentFixture() {}

    static void insert(JdbcTemplate jdbc, String sql, Object... args) {
        var tx = new TransactionTemplate(new DataSourceTransactionManager(
                Objects.requireNonNull(jdbc.getDataSource())));
        tx.executeWithoutResult(ignored -> {
            jdbc.execute("ALTER TABLE fitness.appointments DISABLE TRIGGER appointment_guarded_v2_insert_ck");
            jdbc.execute((ConnectionCallback<Void>) connection -> {
                var beforeInsert = connection.setSavepoint();
                try {
                    jdbc.update(sql, args);
                } catch (RuntimeException rejected) {
                    // PostgreSQL aborts a transaction after a trigger error. Roll back
                    // only the attempted fixture insert so ENABLE can still run and
                    // the original SQLSTATE remains observable by the caller.
                    connection.rollback(beforeInsert);
                    throw rejected;
                } finally {
                    jdbc.execute("ALTER TABLE fitness.appointments ENABLE TRIGGER appointment_guarded_v2_insert_ck");
                }
                return null;
            });
        });
    }
}
