package com.fitnesscoaching.platform.modules.nutrition;

import com.fitnesscoaching.platform.modules.audit.AuditRecord;
import com.fitnesscoaching.platform.modules.audit.AuditService;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.student.application.port.in.StudentCapabilityQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;
import java.util.function.Supplier;

@Component
final class NutritionStrategyCommands {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final AuditService audit;
    private final UserAccountStatusQuery accounts;
    private final StudentCapabilityQuery students;

    NutritionStrategyCommands(JdbcTemplate jdbc, ObjectMapper mapper, AuditService audit,
                              UserAccountStatusQuery accounts, StudentCapabilityQuery students) {
        this.jdbc = jdbc; this.mapper = mapper; this.audit = audit;
        this.accounts = accounts; this.students = students;
    }

    void requireStudent(UUID actor) {
        if (actor == null || accounts.getAccountStatus(actor).orElse(null) != AccountStatus.ACTIVE
                || !students.hasProfile(actor)) throw new NutritionFailure("NUTRITION_ACCESS_DENIED", 403);
    }

    void lockStudent(UUID student) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 707))",
                rs -> { while (rs.next()) { /* held through transaction */ } }, student.toString());
    }

    Instant databaseNow() {
        Timestamp at = jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class);
        if (at == null) throw new IllegalStateException("PostgreSQL clock unavailable");
        return at.toInstant();
    }

    <T> T receipt(UUID actor, String key, String command, Object payload, Class<T> responseType,
                  Supplier<T> work) {
        if (key == null || key.isBlank() || key.length() > 120)
            throw new NutritionFailure("VALIDATION_FAILED", 400);
        // Student-scoped lock precedes this actor-scoped receipt lock for every mutation.
        // A Trainer can command two different Students with the same key concurrently.
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?::text, 709))",
                rs -> { while (rs.next()) { /* held through transaction */ } }, actor.toString());
        String hash = sha(json(payload));
        var rows = jdbc.query("""
                SELECT command_name,payload_hash,response_json::text FROM fitness.nutrition_command_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, n) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)), actor, key);
        if (!rows.isEmpty()) {
            Receipt prior = rows.getFirst();
            if (!prior.command().equals(command) || !prior.hash().equals(hash))
                throw new NutritionFailure("NUTRITION_IDEMPOTENCY_CONFLICT", 409);
            try { return mapper.readValue(prior.response(), responseType); }
            catch (Exception invalid) { throw new IllegalStateException("Invalid nutrition receipt", invalid); }
        }
        T result = work.get();
        jdbc.update("""
                INSERT INTO fitness.nutrition_command_receipts(actor_id,command_key,command_name,payload_hash,response_json)
                VALUES (?,?,?,?,?::jsonb)
                """, actor, key, command, hash, json(result));
        return result;
    }

    void audit(UUID actor, String role, String action, String targetType, UUID targetId, Object detail) {
        audit.recordAudit(AuditRecord.builder().actorUserId(actor).actorRole(role)
                .action(action).targetType(targetType).targetId(targetId)
                .beforeDataJson("{}").afterDataJson(json(detail)).occurredAt(databaseNow()).build());
    }

    String json(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception invalid) { throw new IllegalStateException("Cannot serialize nutrition command", invalid); }
    }

    private static String sha(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    private record Receipt(String command, String hash, String response) {}
}
