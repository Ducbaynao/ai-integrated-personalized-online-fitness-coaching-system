package com.fitnesscoaching.platform.modules.schedule.adapter.out.persistence;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ConfirmedAppointmentLinkPort;
import com.fitnesscoaching.platform.modules.trainer.application.port.in.TrainerCoachingEligibilityQuery;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Component
public class JdbcConfirmedAppointmentLinkAdapter implements ConfirmedAppointmentLinkPort {
    private final JdbcTemplate jdbc;
    private final TrainerCoachingEligibilityQuery trainers;

    public JdbcConfirmedAppointmentLinkAdapter(JdbcTemplate jdbc, TrainerCoachingEligibilityQuery trainers) {
        this.jdbc = jdbc;
        this.trainers = trainers;
    }

    @Override public boolean lockQualifying(UUID appointmentId, UUID studentId, UUID periodId,
                                            Instant start, Instant end) {
        if (appointmentId == null || studentId == null || periodId == null || start == null || end == null
                || !end.isAfter(start)) return false;
        return !jdbc.query("""
                SELECT a.trainer_id FROM fitness.appointments a
                JOIN fitness.coaching_periods cp ON cp.id=a.coaching_period_id
                WHERE a.id=? AND a.student_id=? AND a.coaching_period_id=?
                  AND a.guarded_v2 AND a.status='CONFIRMED' AND a.planned_workout_id IS NULL
                  AND cp.mode='HUMAN_COACH' AND cp.student_id=a.student_id
                  AND cp.trainer_id=a.trainer_id AND cp.started_at<=clock_timestamp()
                  AND (cp.ended_at IS NULL OR cp.ended_at>clock_timestamp())
                  AND a.start_at<=? AND a.end_at>=?
                FOR UPDATE OF a
                """, (rs, row) -> rs.getObject(1, UUID.class), appointmentId, studentId,
                periodId, Timestamp.from(start), Timestamp.from(end))
                .stream().filter(trainers::canCoach).toList().isEmpty();
    }

    @Override public boolean link(UUID appointmentId, UUID occurrenceId) {
        return jdbc.update("""
                UPDATE fitness.appointments SET planned_workout_id=?,updated_at=clock_timestamp(),version=version+1
                WHERE id=? AND status='CONFIRMED' AND planned_workout_id IS NULL AND guarded_v2
                """, occurrenceId, appointmentId) == 1;
    }

    @Override public Long observeProposalCandidate(UUID appointmentId, UUID studentId, UUID trainerId,
                                                    UUID periodId, Instant start, Instant end) {
        if (appointmentId == null || studentId == null || trainerId == null || periodId == null
                || start == null || end == null || !end.isAfter(start) || !trainers.canCoach(trainerId))
            return null;
        return jdbc.query("""
                SELECT a.version FROM fitness.appointments a
                JOIN fitness.coaching_periods cp ON cp.id=a.coaching_period_id
                WHERE a.id=? AND a.student_id=? AND a.trainer_id=? AND a.coaching_period_id=?
                  AND a.guarded_v2 AND a.status='CONFIRMED' AND a.planned_workout_id IS NULL
                  AND cp.mode='HUMAN_COACH' AND cp.student_id=a.student_id
                  AND cp.trainer_id=a.trainer_id AND cp.started_at<=clock_timestamp()
                  AND (cp.ended_at IS NULL OR cp.ended_at>clock_timestamp())
                  AND a.start_at<=? AND a.end_at>=?
                FOR UPDATE OF a
                """, (rs, row) -> rs.getLong(1), appointmentId, studentId, trainerId, periodId,
                Timestamp.from(start), Timestamp.from(end)).stream().findFirst().orElse(null);
    }

    @Override public boolean lockQualifyingProposal(UUID appointmentId, long observedVersion,
                                                    UUID studentId, UUID trainerId, UUID periodId,
                                                    Instant start, Instant end) {
        Long current = observeProposalCandidate(appointmentId, studentId, trainerId, periodId, start, end);
        return current != null && current == observedVersion;
    }
}
