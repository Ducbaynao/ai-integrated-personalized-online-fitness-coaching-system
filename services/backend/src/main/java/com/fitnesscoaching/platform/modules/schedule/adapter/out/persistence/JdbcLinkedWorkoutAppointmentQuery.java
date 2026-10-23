package com.fitnesscoaching.platform.modules.schedule.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.LinkedWorkoutAppointmentQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;

@Repository
public class JdbcLinkedWorkoutAppointmentQuery implements LinkedWorkoutAppointmentQuery {
    private final JdbcTemplate jdbc;
    private final TrainerCoachingEligibilityQuery trainers;
    public JdbcLinkedWorkoutAppointmentQuery(JdbcTemplate jdbc, TrainerCoachingEligibilityQuery trainers) {
        this.jdbc = jdbc; this.trainers = trainers;
    }

    @Override public boolean hasAnyLinkedForMutation(UUID occurrenceId) {
        return !jdbc.query("SELECT id FROM fitness.appointments WHERE planned_workout_id=? ORDER BY id FOR SHARE",
                (rs, row) -> rs.getObject(1, UUID.class), occurrenceId).isEmpty();
    }

    @Override public boolean hasAnyLinked(UUID occurrenceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.appointments WHERE planned_workout_id=?)
                """, Boolean.class, occurrenceId));
    }

    @Override public List<Linked> lockLinkedForChange(UUID occurrenceId) {
        return jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,version,status,start_at,end_at
                FROM fitness.appointments WHERE planned_workout_id=? ORDER BY id FOR UPDATE
                """, (rs, row) -> new Linked(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getLong(5),
                rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()), occurrenceId);
    }

    @Override public List<Linked> findActiveLinks(UUID occurrenceId) {
        return jdbc.query("""
                SELECT id,student_id,trainer_id,coaching_period_id,version,status,start_at,end_at
                FROM fitness.appointments WHERE planned_workout_id=? ORDER BY id
                """, (rs, row) -> new Linked(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class),
                rs.getObject(3, UUID.class), rs.getObject(4, UUID.class), rs.getLong(5),
                rs.getString(6), rs.getTimestamp(7).toInstant(), rs.getTimestamp(8).toInstant()), occurrenceId);
    }

    @Override public boolean qualifiesForReplacement(Linked linked, UUID studentId, UUID periodId,
                                                      Instant start, Instant end) {
        if (linked == null || start == null || end == null || !end.isAfter(start)
                || !linked.studentId().equals(studentId) || !java.util.Objects.equals(linked.periodId(), periodId)
                || !"CONFIRMED".equals(linked.status()) || !trainers.canCoach(linked.trainerId())
                || start.isBefore(linked.startAt()) || end.isAfter(linked.endAt())) return false;
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM fitness.appointments a
                  JOIN fitness.coaching_periods cp ON cp.id=a.coaching_period_id
                  WHERE a.id=? AND a.guarded_v2 AND a.version=? AND a.status='CONFIRMED'
                    AND cp.mode='HUMAN_COACH' AND cp.student_id=a.student_id AND cp.trainer_id=a.trainer_id
                    AND cp.started_at<=clock_timestamp() AND (cp.ended_at IS NULL OR cp.ended_at>clock_timestamp()))
                """, Boolean.class, linked.appointmentId(), linked.version()));
    }

    @Override public boolean transfer(Linked linked, UUID predecessorId, UUID successorId, UUID actorId) {
        int changed = jdbc.update("""
                UPDATE fitness.appointments SET planned_workout_id=?,version=version+1,updated_at=clock_timestamp()
                WHERE id=? AND planned_workout_id=? AND version=? AND status='CONFIRMED'
                """, successorId, linked.appointmentId(), predecessorId, linked.version());
        if (changed == 1) event(linked, predecessorId, successorId, actorId, "WORKOUT_LINK_TRANSFERRED");
        return changed == 1;
    }

    @Override public boolean release(Linked linked, UUID occurrenceId, UUID actorId) {
        int changed = jdbc.update("""
                UPDATE fitness.appointments SET planned_workout_id=NULL,version=version+1,updated_at=clock_timestamp()
                WHERE id=? AND planned_workout_id=? AND version=?
                """, linked.appointmentId(), occurrenceId, linked.version());
        if (changed == 1) event(linked, occurrenceId, null, actorId, "WORKOUT_LINK_RELEASED");
        return changed == 1;
    }

    private void event(Linked linked, UUID oldId, UUID newId, UUID actorId, String type) {
        jdbc.update("""
                INSERT INTO fitness.appointment_events_v2
                (id,appointment_id,event_type,actor_id,old_state,new_state)
                VALUES (?,?,?, ?,jsonb_build_object('plannedWorkoutId',?::text,'version',?::bigint),
                       jsonb_build_object('plannedWorkoutId',?::text,'version',?::bigint))
                """, UUID.randomUUID(), linked.appointmentId(), type, actorId, oldId.toString(),
                linked.version(), newId == null ? null : newId.toString(), linked.version() + 1);
    }
}
