package com.fitnesscoaching.platform.modules.ai.application.port.out;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fitnesscoaching.platform.modules.ai.domain.F05SourceSnapshot;

import java.util.UUID;

public interface F05AiServiceClient {

    String generateRescheduleJson(F05ServiceRequest request);

    record F05ServiceRequest(
            UUID studentId,
            UUID coachingPeriodId,
            String mode,
            UUID occurrenceId,
            long occurrenceVersion,
            UUID planVersionId,
            UUID planSessionId,
            String sessionName,
            String supervisionRequirement,
            String timezone,
            String originalPlannedStartAt,
            String plannedStartAt,
            String plannedEndAt,
            String reason,
            String preferredTimeOfDay,
            String continuityState,
            boolean hasLinkedAppointment,
            UUID appointmentId,
            String appointmentStartAt,
            String appointmentEndAt,
            @JsonIgnore F05SourceSnapshot sourceSnapshot
    ) {
        public F05ServiceRequest(
                UUID studentId,
                UUID coachingPeriodId,
                String mode,
                UUID occurrenceId,
                long occurrenceVersion,
                UUID planVersionId,
                UUID planSessionId,
                String sessionName,
                String supervisionRequirement,
                String timezone,
                String originalPlannedStartAt,
                String plannedStartAt,
                String plannedEndAt,
                String reason,
                String preferredTimeOfDay,
                String continuityState,
                boolean hasLinkedAppointment,
                UUID appointmentId,
                F05SourceSnapshot sourceSnapshot
        ) {
            this(
                    studentId, coachingPeriodId, mode, occurrenceId, occurrenceVersion,
                    planVersionId, planSessionId, sessionName, supervisionRequirement,
                    timezone, originalPlannedStartAt, plannedStartAt, plannedEndAt,
                    reason, preferredTimeOfDay, continuityState, hasLinkedAppointment,
                    appointmentId, null, null, sourceSnapshot
            );
        }
    }
}
