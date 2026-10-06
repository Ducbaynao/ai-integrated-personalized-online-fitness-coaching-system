package com.fitnesscoaching.platform.modules.coaching.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.coaching.application.port.out.CoachingStore;
import com.fitnesscoaching.platform.modules.coaching.application.CoachingFailure;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingState.*;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcCoachingStore implements CoachingStore {
    private final JdbcTemplate jdbc;

    public JdbcCoachingStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    private static final RowMapper<Relationship> RELATIONSHIP = (rs, ignored) -> new Relationship(
            rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class),
            rs.getObject("trainer_id", UUID.class), rs.getString("status"),
            rs.getObject("requested_by", UUID.class), instant(rs.getTimestamp("requested_at")),
            instant(rs.getTimestamp("accepted_at")), instant(rs.getTimestamp("started_at")),
            instant(rs.getTimestamp("ended_at")), rs.getLong("version"));
    private static final RowMapper<Resume> RESUME = (rs, ignored) -> new Resume(
            rs.getObject("id", UUID.class), rs.getObject("relationship_id", UUID.class),
            rs.getObject("requested_by", UUID.class), rs.getString("status"), rs.getLong("version"),
            instant(rs.getTimestamp("requested_at")), rs.getObject("decided_by", UUID.class),
            instant(rs.getTimestamp("decided_at")));
    private static final RowMapper<Period> PERIOD = (rs, ignored) -> new Period(
            rs.getObject("id", UUID.class), rs.getObject("student_id", UUID.class), rs.getString("mode"),
            rs.getObject("coaching_relationship_id", UUID.class), rs.getObject("trainer_id", UUID.class),
            instant(rs.getTimestamp("started_at")), instant(rs.getTimestamp("ended_at")));
    private static Instant instant(Timestamp value) { return value == null ? null : value.toInstant(); }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static <T> Optional<T> first(List<T> rows) { return rows.stream().findFirst(); }

    @Override public Instant transitionTime() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    @Override public void lockStudent(UUID studentId) {
        jdbc.query("SELECT user_id FROM fitness.student_profiles WHERE user_id = ? FOR NO KEY UPDATE",
                rs -> { while (rs.next()) { /* row lock held to transaction end */ } }, studentId);
    }

    @Override public Optional<Relationship> relationship(UUID id) {
        return first(jdbc.query("SELECT * FROM fitness.coaching_relationships WHERE id = ?", RELATIONSHIP, id));
    }

    @Override public Optional<Relationship> currentRelationship(UUID studentId) {
        return first(jdbc.query("""
                SELECT * FROM fitness.coaching_relationships
                WHERE student_id = ? AND status IN ('ACTIVE', 'PAUSED')
                ORDER BY started_at DESC, id DESC LIMIT 1
                """, RELATIONSHIP, studentId));
    }

    @Override public boolean pendingPair(UUID studentId, UUID trainerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.coaching_relationships
                WHERE student_id = ? AND trainer_id = ? AND status = 'PENDING')
                """, Boolean.class, studentId, trainerId));
    }

    @Override public boolean currentPair(UUID studentId, UUID trainerId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.coaching_relationships
                WHERE student_id = ? AND trainer_id = ? AND status IN ('PENDING', 'ACTIVE', 'PAUSED'))
                """, Boolean.class, studentId, trainerId));
    }

    @Override public List<Relationship> pending(UUID actorId, boolean incoming, boolean studentCapability,
                                                 boolean trainerCapability, int limit, int offset) {
        String sql = incoming ? """
                SELECT * FROM fitness.coaching_relationships
                WHERE status = 'PENDING' AND requested_by <> ?
                  AND ((? AND student_id = ?) OR (? AND trainer_id = ?))
                ORDER BY requested_at DESC, id DESC LIMIT ? OFFSET ?
                """ : """
                SELECT * FROM fitness.coaching_relationships
                WHERE status = 'PENDING' AND requested_by = ?
                  AND ((? AND student_id = ?) OR (? AND trainer_id = ?))
                ORDER BY requested_at DESC, id DESC LIMIT ? OFFSET ?
                """;
        return jdbc.query(sql, RELATIONSHIP, actorId, studentCapability, actorId,
                trainerCapability, actorId, limit, offset);
    }

    @Override
    public List<Relationship> relationships(UUID actorId, boolean studentCapability,
                                            boolean trainerCapability, boolean trainerEligible,
                                            int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM fitness.coaching_relationships
                WHERE (? AND student_id = ?)
                   OR (? AND trainer_id = ? AND (
                        status = 'PAUSED'::fitness.coaching_relationship_status
                        OR (? AND status IN ('PENDING', 'ACTIVE'))
                   ))
                ORDER BY requested_at DESC, id DESC
                LIMIT ? OFFSET ?
                """, RELATIONSHIP, studentCapability, actorId, trainerCapability, actorId,
                trainerEligible, limit, offset);
    }

    @Override public Relationship create(UUID studentId, UUID trainerId, UUID initiator, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_relationships
                (id, student_id, trainer_id, requested_by, requested_at, status)
                VALUES (?, ?, ?, ?, ?, 'PENDING'::fitness.coaching_relationship_status)
                """, id, studentId, trainerId, initiator, ts(at));
        addHistory(id, null, "PENDING", initiator, null, at);
        return relationship(id).orElseThrow();
    }

    @Override public Relationship transition(Relationship rel, String next, UUID actor, String reason, Instant at) {
        int count = jdbc.update("""
                UPDATE fitness.coaching_relationships
                SET status = ?::fitness.coaching_relationship_status, version = version + 1,
                    accepted_at = CASE WHEN ? = 'ACTIVE' AND accepted_at IS NULL THEN ? ELSE accepted_at END,
                    started_at = CASE WHEN ? = 'ACTIVE' AND started_at IS NULL THEN ? ELSE started_at END,
                    paused_at = CASE WHEN ? = 'PAUSED' THEN ? ELSE paused_at END,
                    ended_at = CASE WHEN ? = 'ENDED' THEN ? ELSE ended_at END,
                    termination_reason = CASE WHEN ? = 'ENDED' THEN ? ELSE termination_reason END
                WHERE id = ? AND version = ? AND status = ?::fitness.coaching_relationship_status
                """, next, next, ts(at), next, ts(at), next, ts(at), next, ts(at), next, reason,
                rel.id(), rel.version(), rel.status());
        if (count != 1) { throw new IllegalStateException("Coaching relationship compare-and-set failed"); }
        addHistory(rel.id(), rel.status(), next, actor, reason, at);
        return relationship(rel.id()).orElseThrow();
    }

    private void addHistory(UUID relId, String before, String after, UUID actor, String reason, Instant at) {
        jdbc.update("""
                INSERT INTO fitness.coaching_relationship_status_history
                (id, relationship_id, from_status, to_status, changed_by, reason, changed_at)
                VALUES (gen_random_uuid(), ?, ?::fitness.coaching_relationship_status,
                        ?::fitness.coaching_relationship_status, ?, ?, ?)
                """, relId, before, after, actor, reason, ts(at));
    }

    @Override public List<History> history(UUID relationshipId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM fitness.coaching_relationship_status_history
                WHERE relationship_id = ? ORDER BY changed_at DESC, id DESC LIMIT ? OFFSET ?
                """, (rs, ignored) -> new History(rs.getObject("id", UUID.class),
                rs.getObject("relationship_id", UUID.class), rs.getString("from_status"),
                rs.getString("to_status"), rs.getObject("changed_by", UUID.class),
                rs.getString("reason"), instant(rs.getTimestamp("changed_at"))), relationshipId, limit, offset);
    }

    @Override public Optional<Period> effectivePeriod(UUID studentId, Instant at) {
        return first(jdbc.query("""
                SELECT * FROM fitness.coaching_periods
                WHERE student_id = ? AND started_at <= ? AND (ended_at IS NULL OR ended_at > ?)
                ORDER BY started_at DESC, id DESC LIMIT 1
                """, PERIOD, studentId, ts(at), ts(at)));
    }

    @Override public List<Period> periods(UUID studentId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM fitness.coaching_periods WHERE student_id = ?
                ORDER BY started_at DESC, id DESC LIMIT ? OFFSET ?
                """, PERIOD, studentId, limit, offset);
    }

    @Override public Instant closePeriod(Period period, Instant at) {
        try {
            List<Timestamp> closed = jdbc.query("""
                    UPDATE fitness.coaching_periods SET ended_at = clock_timestamp()
                    WHERE id = ? AND started_at < ? AND (ended_at IS NULL OR ended_at > ?)
                      AND (ended_at IS NULL OR ended_at > clock_timestamp())
                    RETURNING ended_at
                    """, (rs, ignored) -> rs.getTimestamp("ended_at"), period.id(), ts(at), ts(at));
            if (closed.size() != 1) { throw new CoachingFailure(HttpStatus.CONFLICT, "COACHING_PERIOD_CONFLICT"); }
            return closed.get(0).toInstant();
        } catch (DataAccessException ex) {
            for (Throwable cause = ex; cause != null; cause = cause.getCause()) {
                if (cause instanceof SQLException sql && "PZ001".equals(sql.getSQLState())) {
                    throw new CoachingFailure(HttpStatus.CONFLICT, "COACHING_PERIOD_CONFLICT");
                }
            }
            throw ex;
        }
    }

    @Override public Period openPeriod(UUID studentId, String mode, UUID relationshipId,
                                        UUID trainerId, UUID actor, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_periods
                (id, student_id, mode, coaching_relationship_id, trainer_id, started_at, created_by)
                VALUES (?, ?, ?::fitness.coaching_mode, ?, ?, ?, ?)
                """, id, studentId, mode, relationshipId, trainerId, ts(at), actor);
        return first(jdbc.query("SELECT * FROM fitness.coaching_periods WHERE id = ?", PERIOD, id)).orElseThrow();
    }

    @Override public Optional<Resume> resume(UUID id) {
        return first(jdbc.query("SELECT * FROM fitness.coaching_resume_requests WHERE id = ?", RESUME, id));
    }

    @Override public Optional<Resume> pendingResume(UUID relationshipId) {
        return first(jdbc.query("""
                SELECT * FROM fitness.coaching_resume_requests
                WHERE relationship_id = ? AND status = 'PENDING' ORDER BY requested_at DESC, id DESC LIMIT 1
                """, RESUME, relationshipId));
    }

    @Override public List<Resume> resumeHistory(UUID relationshipId, int limit, int offset) {
        return jdbc.query("""
                SELECT * FROM fitness.coaching_resume_requests
                WHERE relationship_id = ? ORDER BY requested_at DESC, id DESC LIMIT ? OFFSET ?
                """, RESUME, relationshipId, limit, offset);
    }

    @Override public Resume createResume(UUID relationshipId, UUID initiator, String reason, Instant at) {
        UUID id = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO fitness.coaching_resume_requests
                (id, relationship_id, requested_by, reason, requested_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, relationshipId, initiator, reason, ts(at));
        return resume(id).orElseThrow();
    }

    @Override public Resume decideResume(Resume request, String next, UUID actor, Instant at) {
        int count = jdbc.update("""
                UPDATE fitness.coaching_resume_requests
                SET status = ?, version = version + 1, decided_by = ?, decided_at = ?
                WHERE id = ? AND version = ? AND status = 'PENDING'
                """, next, actor, ts(at), request.id(), request.version());
        if (count != 1) { throw new IllegalStateException("Resume request compare-and-set failed"); }
        return resume(request.id()).orElseThrow();
    }

    @Override public Optional<Resume> cancelPendingResume(UUID relationshipId, UUID actor, Instant at) {
        Resume pending = pendingResume(relationshipId).orElse(null);
        if (pending == null) { return Optional.empty(); }
        int count = jdbc.update("""
                UPDATE fitness.coaching_resume_requests
                SET status = 'CANCELLED', version = version + 1, decided_by = ?, decided_at = ?
                WHERE relationship_id = ? AND status = 'PENDING'
                """, actor, ts(at), relationshipId);
        if (count != 1) { throw new IllegalStateException("Resume invalidation compare-and-set failed"); }
        return resume(pending.id());
    }

    @Override public Optional<Receipt> receipt(UUID actor, UUID key) {
        return first(jdbc.query("""
                SELECT command_name, payload_hash, response_json::text FROM fitness.coaching_command_receipts
                WHERE actor_id = ? AND command_key = ?
                """, (rs, ignored) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)), actor, key));
    }

    @Override public void saveReceipt(UUID actor, UUID key, String command, String hash, String json) {
        jdbc.update("""
                INSERT INTO fitness.coaching_command_receipts
                (actor_id, command_key, command_name, payload_hash, response_json)
                VALUES (?, ?, ?, ?, ?::jsonb)
                """, actor, key, command, hash, json);
    }
}
