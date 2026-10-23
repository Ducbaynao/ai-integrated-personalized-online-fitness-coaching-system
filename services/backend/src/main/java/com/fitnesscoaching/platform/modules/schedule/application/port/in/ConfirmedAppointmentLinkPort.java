package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import java.time.Instant;
import java.util.UUID;

/** Schedule-owned Appointment boundary used by Workout's atomic confirmation command. */
public interface ConfirmedAppointmentLinkPort {
    /** Locks one currently unlinked, confirmed Appointment and checks exact period/interval. */
    boolean lockQualifying(UUID appointmentId, UUID studentId, UUID coachingPeriodId,
                           Instant plannedStartAt, Instant plannedEndAt);

    /** Proposal creation observes a qualifying, currently unlinked Appointment's version. */
    Long observeProposalCandidate(UUID appointmentId, UUID studentId, UUID trainerId,
                                  UUID coachingPeriodId, Instant plannedStartAt, Instant plannedEndAt);

    /** ACCEPT must use the exact Appointment version observed when the Trainer proposed. */
    boolean lockQualifyingProposal(UUID appointmentId, long observedVersion, UUID studentId,
                                   UUID trainerId, UUID coachingPeriodId,
                                   Instant plannedStartAt, Instant plannedEndAt);

    /** Links only after the Workout occurrence exists, in the same transaction. */
    boolean link(UUID appointmentId, UUID occurrenceId);
}
