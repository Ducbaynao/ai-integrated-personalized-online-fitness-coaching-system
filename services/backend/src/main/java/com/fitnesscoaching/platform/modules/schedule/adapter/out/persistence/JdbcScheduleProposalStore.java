package com.fitnesscoaching.platform.modules.schedule.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.Proposal;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.ProposalItem;
import com.fitnesscoaching.platform.modules.schedule.application.port.out.ScheduleProposalStore;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JdbcScheduleProposalStore implements ScheduleProposalStore {
    private final JdbcTemplate jdbc;
    public JdbcScheduleProposalStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    private static Timestamp ts(Instant instant) { return instant == null ? null : Timestamp.from(instant); }
    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    @Override public Instant databaseNow() {
        return jdbc.queryForObject("SELECT clock_timestamp()", Timestamp.class).toInstant();
    }

    private RowMapper<Proposal> mapper() { return (rs, row) -> {
        UUID id = rs.getObject("id", UUID.class);
        List<ProposalItem> items = jdbc.query("""
                SELECT client_item_id,plan_session_id,week_number,day_number,sequence_number,
                       local_start,local_end,start_utc_offset,end_utc_offset
                FROM fitness.workout_schedule_proposal_items WHERE proposal_id=? ORDER BY client_item_id
                """, (item, n) -> new ProposalItem(item.getObject(1, UUID.class), item.getObject(2, UUID.class),
                item.getInt(3), item.getInt(4), item.getInt(5), LocalDateTime.parse(item.getString(6)),
                LocalDateTime.parse(item.getString(7)), item.getString(8), item.getString(9)), id);
        return new Proposal(id, rs.getLong("version"), rs.getString("status"),
                rs.getObject("student_id", UUID.class), rs.getObject("trainer_id", UUID.class),
                rs.getObject("coaching_period_id", UUID.class), rs.getObject("plan_id", UUID.class),
                rs.getObject("source_plan_version_id", UUID.class), rs.getInt("source_plan_version_number"),
                rs.getLong("observed_plan_aggregate_version"), rs.getDate("week_anchor_date").toLocalDate(),
                rs.getString("timezone"), items, instant(rs, "proposed_at"), instant(rs, "decided_at"),
                rs.getObject("decided_by", UUID.class), rs.getString("decision_note"),
                instant(rs, "withdrawn_at"), rs.getObject("withdrawn_by", UUID.class));
    }; }

    @Override public Optional<Proposal> find(UUID proposalId) {
        return jdbc.query("SELECT * FROM fitness.workout_schedule_proposals WHERE id=?", mapper(), proposalId)
                .stream().findFirst();
    }
    @Override public Optional<Proposal> lock(UUID proposalId) {
        return jdbc.query("SELECT * FROM fitness.workout_schedule_proposals WHERE id=? FOR UPDATE", mapper(), proposalId)
                .stream().findFirst();
    }
    @Override public List<Proposal> listStudent(UUID studentId, String status, int limit, long offset) {
        return jdbc.query("""
                SELECT * FROM fitness.workout_schedule_proposals
                WHERE student_id=? AND (CAST(? AS text) IS NULL OR status=?)
                ORDER BY proposed_at DESC,id DESC LIMIT ? OFFSET ?
                """, mapper(), studentId, status, status, limit, offset);
    }
    @Override public long countStudent(UUID studentId, String status) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM fitness.workout_schedule_proposals
                WHERE student_id=? AND (CAST(? AS text) IS NULL OR status=?)
                """, Long.class, studentId, status, status);
    }
    @Override public Optional<Receipt> lockReceipt(UUID actorId, String commandKey) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {
            while (rs.next()) { /* hold until transaction completes */ }
        }, "WORKOUT_SCHEDULE:" + actorId + ":" + commandKey);
        return jdbc.query("""
                SELECT command_name,payload_hash,response_json::text
                FROM fitness.workout_schedule_command_receipts
                WHERE actor_id=? AND command_key=? FOR UPDATE
                """, (rs, n) -> new Receipt(rs.getString(1), rs.getString(2), rs.getString(3)),
                actorId, commandKey).stream().findFirst();
    }
    @Override public void save(Proposal p, String commandKey) {
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_proposals
                (id,student_id,trainer_id,coaching_period_id,plan_id,source_plan_version_id,
                 source_plan_version_number,observed_plan_aggregate_version,week_anchor_date,timezone,
                 status,version,proposed_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,'PENDING',0,?)
                """, p.proposalId(), p.studentId(), p.trainerId(), p.coachingPeriodId(), p.planId(),
                p.sourcePlanVersionId(), p.sourcePlanVersionNumber(), p.observedPlanAggregateVersion(),
                p.weekAnchorDate(), p.timezone(), ts(p.proposedAt()));
        for (ProposalItem item : p.items()) jdbc.update("""
                INSERT INTO fitness.workout_schedule_proposal_items
                (proposal_id,client_item_id,plan_session_id,week_number,day_number,sequence_number,
                 local_start,local_end,start_utc_offset,end_utc_offset)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """, p.proposalId(), item.clientItemId(), item.planSessionId(), item.weekNumber(),
                item.dayNumber(), item.sequenceNumber(), item.localStart().toString(), item.localEnd().toString(),
                item.startUtcOffset(), item.endUtcOffset());
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_proposal_history
                (proposal_id,proposal_version,from_status,to_status,actor_id,command_key,occurred_at)
                VALUES (?,0,NULL,'PENDING',?,?,?)
                """, p.proposalId(), p.trainerId(), commandKey, ts(p.proposedAt()));
    }
    @Override public void transition(Proposal p, String fromStatus, UUID actorId, String commandKey,
                                      Instant at, UUID confirmedBatchId) {
        int changed = jdbc.update("""
                UPDATE fitness.workout_schedule_proposals SET status=?,version=?,decided_at=?,decided_by=?,
                       decision_note=?,withdrawn_at=?,withdrawn_by=?,confirmed_batch_id=?
                WHERE id=? AND status=? AND version=?
                """, p.status(), p.proposalVersion(), ts(p.decidedAt()), p.decidedBy(), p.decisionNote(),
                ts(p.withdrawnAt()), p.withdrawnBy(), confirmedBatchId, p.proposalId(), fromStatus,
                p.proposalVersion() - 1);
        if (changed != 1) throw new WorkoutScheduleFailure("WORKOUT_SCHEDULE_PROPOSAL_VERSION_CONFLICT", 409);
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_proposal_history
                (proposal_id,proposal_version,from_status,to_status,actor_id,command_key,occurred_at)
                VALUES (?,?,?,?,?,?,?)
                """, p.proposalId(), p.proposalVersion(), fromStatus, p.status(), actorId, commandKey, ts(at));
    }
    @Override public void saveReceipt(UUID actorId, String commandKey, String commandName, String payloadHash,
                                       UUID proposalId, UUID batchId, String responseJson, Instant at) {
        jdbc.update("""
                INSERT INTO fitness.workout_schedule_command_receipts
                (actor_id,command_key,command_name,payload_hash,batch_id,proposal_id,response_json,created_at)
                VALUES (?,?,?,?,?,?,?::jsonb,?)
                """, actorId, commandKey, commandName, payloadHash, batchId, proposalId, responseJson, ts(at));
    }
}
