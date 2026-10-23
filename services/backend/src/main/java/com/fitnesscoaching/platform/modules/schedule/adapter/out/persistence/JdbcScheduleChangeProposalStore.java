package com.fitnesscoaching.platform.modules.schedule.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase.Proposal;
import com.fitnesscoaching.platform.modules.schedule.application.port.out.ScheduleChangeProposalStore;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcScheduleChangeProposalStore implements ScheduleChangeProposalStore {
    private final JdbcTemplate jdbc;
    public JdbcScheduleChangeProposalStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Timestamp ts(Instant value) { return value == null ? null : Timestamp.from(value); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    private static LocalDateTime local(String value) {
        if (value == null) return null;
        try { return LocalDateTime.parse(value); }
        catch (DateTimeParseException legacy) { return null; }
    }
    private RowMapper<Proposal> mapper() { return (rs, row) -> {
        String timezone = rs.getString("destination_timezone");
        Destination destination = timezone == null ? null : new Destination(
                local(rs.getString("destination_local_start")), local(rs.getString("destination_local_end")),
                timezone, rs.getString("destination_start_utc_offset"),
                rs.getString("destination_end_utc_offset"));
        return new Proposal(rs.getObject("id", UUID.class), rs.getLong("version"), rs.getString("status"),
                rs.getString("change_type"), rs.getObject("student_id", UUID.class),
                rs.getObject("trainer_id", UUID.class), rs.getObject("coaching_period_id", UUID.class),
                rs.getObject("occurrence_id", UUID.class), rs.getLong("observed_occurrence_version"),
                instant(rs, "old_planned_start_at"), instant(rs, "old_planned_end_at"),
                rs.getString("old_timezone"), local(rs.getString("old_local_start")),
                local(rs.getString("old_local_end")), rs.getString("old_start_utc_offset"),
                rs.getString("old_end_utc_offset"), instant(rs, "original_planned_start_at"),
                rs.getObject("source_plan_id", UUID.class), rs.getObject("source_plan_version_id", UUID.class),
                rs.getObject("source_plan_session_id", UUID.class), destination, rs.getString("reason"),
                instant(rs, "proposed_at"), instant(rs, "decided_at"),
                rs.getObject("decided_by", UUID.class), instant(rs, "withdrawn_at"),
                rs.getObject("withdrawn_by", UUID.class), rs.getObject("appointment_id", UUID.class),
                (Long) rs.getObject("observed_appointment_version"));
    }; }
    @Override public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }
    @Override public Optional<Proposal> find(UUID id) {
        return jdbc.query("SELECT * FROM fitness.workout_schedule_change_proposals WHERE id=?", mapper(), id)
                .stream().findFirst();
    }
    @Override public Optional<Proposal> lock(UUID id) {
        return jdbc.query("SELECT * FROM fitness.workout_schedule_change_proposals WHERE id=? FOR UPDATE",
                mapper(), id).stream().findFirst();
    }
    @Override public List<Proposal> listStudent(UUID student, String status, int size, long offset) {
        return jdbc.query("""
                SELECT * FROM fitness.workout_schedule_change_proposals
                WHERE student_id=? AND (CAST(? AS text) IS NULL OR status=?)
                ORDER BY proposed_at DESC,id DESC LIMIT ? OFFSET ?
                """, mapper(), student, status, status, size, offset);
    }
    @Override public long countStudent(UUID student, String status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fitness.workout_schedule_change_proposals
                WHERE student_id=? AND (CAST(? AS text) IS NULL OR status=?)
                """, Long.class, student, status, status);
    }
    @Override public List<Proposal> listTrainer(UUID trainer, UUID student, String status, int size, long offset) {
        return jdbc.query("""
                SELECT * FROM fitness.workout_schedule_change_proposals
                WHERE trainer_id=? AND student_id=? AND (CAST(? AS text) IS NULL OR status=?)
                ORDER BY proposed_at DESC,id DESC LIMIT ? OFFSET ?
                """, mapper(), trainer, student, status, status, size, offset);
    }
    @Override public long countTrainer(UUID trainer, UUID student, String status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fitness.workout_schedule_change_proposals
                WHERE trainer_id=? AND student_id=? AND (CAST(? AS text) IS NULL OR status=?)
                """, Long.class, trainer, student, status, status);
    }
    @Override public Optional<Receipt> lockReceipt(UUID actor, String key) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                rs -> { while (rs.next()) {} }, "WORKOUT_CHANGE_PROPOSAL:" + actor + ":" + key);
        return jdbc.query("""
                SELECT command_name,payload_hash,response_json::text
                FROM fitness.workout_schedule_change_proposal_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, row) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                actor, key).stream().findFirst();
    }
    @Override public void save(Proposal p, String key) {
        Destination d = p.destination();
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_change_proposals
                (id,student_id,trainer_id,coaching_period_id,occurrence_id,observed_occurrence_version,
                 change_type,old_planned_start_at,old_planned_end_at,original_planned_start_at,
                 old_timezone,old_local_start,old_local_end,old_start_utc_offset,old_end_utc_offset,
                 source_plan_id,source_plan_version_id,source_plan_session_id,
                 appointment_id,observed_appointment_version,
                 destination_timezone,destination_local_start,destination_local_end,
                 destination_start_utc_offset,destination_end_utc_offset,reason,status,version,proposed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?, 'PENDING',0,?)
                """, p.proposalId(), p.studentId(), p.trainerId(), p.coachingPeriodId(), p.occurrenceId(),
                p.observedOccurrenceVersion(), p.changeType(), ts(p.oldPlannedStartAt()),
                ts(p.oldPlannedEndAt()), ts(p.originalPlannedStartAt()), p.oldTimezone(),
                p.oldLocalStart() == null ? null : p.oldLocalStart().toString(),
                p.oldLocalEnd() == null ? null : p.oldLocalEnd().toString(), p.oldStartUtcOffset(),
                p.oldEndUtcOffset(), p.sourcePlanId(), p.sourcePlanVersionId(), p.sourcePlanSessionId(),
                p.appointmentId(), p.observedAppointmentVersion(),
                d == null ? null : d.timezone(), d == null ? null : d.localStart().toString(),
                d == null ? null : d.localEnd().toString(), d == null ? null : d.startUtcOffset(),
                d == null ? null : d.endUtcOffset(), p.reason(), ts(p.proposedAt()));
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_change_proposal_history
                (proposal_id,proposal_version,from_status,to_status,actor_id,command_key,occurred_at)
                VALUES (?,0,NULL,'PENDING',?,?,?)
                """, p.proposalId(), p.trainerId(), key, ts(p.proposedAt()));
    }
    @Override public void transition(Proposal p, String from, UUID actor, String key,
                                      UUID appliedOccurrence, Instant at) {
        int changed = jdbc.update("""
                UPDATE fitness.workout_schedule_change_proposals
                SET status=?,version=?,decided_at=?,decided_by=?,withdrawn_at=?,withdrawn_by=?,applied_occurrence_id=?
                WHERE id=? AND status=? AND version=?
                """, p.status(), p.proposalVersion(), ts(p.decidedAt()), p.decidedBy(),
                ts(p.withdrawnAt()), p.withdrawnBy(), appliedOccurrence, p.proposalId(), from,
                p.proposalVersion() - 1);
        if (changed != 1) throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_CHANGE_PROPOSAL_VERSION_CONFLICT", 409);
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_change_proposal_history
                (proposal_id,proposal_version,from_status,to_status,actor_id,command_key,occurred_at)
                VALUES (?,?,?,?,?,?,?)
                """, p.proposalId(), p.proposalVersion(), from, p.status(), actor, key, ts(at));
    }
    @Override public void saveReceipt(UUID actor, String key, String name, String hash,
                                      UUID proposalId, String response, Instant at) {
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_change_proposal_receipts
                (actor_id,command_key,command_name,payload_hash,proposal_id,response_json,created_at)
                VALUES (?,?,?,?,?,?::jsonb,?)
                """, actor, key, name, hash, proposalId, response, ts(at));
    }
}
