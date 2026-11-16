package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunLifecycleConflictException;
import com.fitnesscoaching.platform.common.exception.AiRunNotFoundException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F05AiServiceClient.F05ServiceRequest;
import com.fitnesscoaching.platform.modules.ai.domain.AiDecisionAuthority;
import com.fitnesscoaching.platform.modules.ai.domain.F05SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery.Occurrence;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Component
public class F05ContextBuilder {

    private final CurrentCoachingContextQuery coachingContextQuery;
    private final UserAccountStatusQuery accountStatusQuery;
    private final ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;
    private final Clock clock;

    @Autowired
    public F05ContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            UserAccountStatusQuery accountStatusQuery,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            Clock clock
    ) {
        this.coachingContextQuery = coachingContextQuery;
        this.accountStatusQuery = accountStatusQuery;
        this.scheduleCalendarProvider = scheduleCalendarProvider;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public record CoachingContextDetails(
            UUID periodId,
            String mode,
            UUID trainerId
    ) {}

    public record ContextEnvelopeResult(
            F05ServiceRequest serviceRequest,
            F05SourceSnapshot sourceSnapshot,
            AiDecisionAuthority decisionAuthority,
            UUID studentId,
            UUID coachingPeriodId
    ) {}

    public CoachingContextDetails resolveCoachingContext(UUID studentId) {
        var contextOpt = coachingContextQuery.findEffectiveContext(studentId, false);
        if (contextOpt.isPresent()) {
            var ctx = contextOpt.get();
            return new CoachingContextDetails(
                    ctx.periodId(),
                    ctx.mode() != null ? ctx.mode().name() : "SELF_DIRECTED",
                    ctx.trainerId()
            );
        }
        return new CoachingContextDetails(null, "SELF_DIRECTED", null);
    }

    public ContextEnvelopeResult buildContextEnvelope(UUID actorId, WorkoutRescheduleApiRequest requestDto) {
        if (actorId == null) {
            throw new AiRunAccessDeniedException("Authentication required.");
        }
        if (requestDto == null || requestDto.occurrenceId() == null) {
            throw new AiRunValidationException("occurrenceId is required.");
        }

        // 1. Check Actor Account Status
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(actorId).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        WorkoutScheduleCalendarQuery calendarQuery = scheduleCalendarProvider.getIfAvailable();
        if (calendarQuery == null) {
            throw new IllegalStateException("WorkoutScheduleCalendarQuery provider not available");
        }

        // 2. Fetch Occurrence with access check
        Occurrence occurrence;
        try {
            occurrence = calendarQuery.detail(actorId, requestDto.occurrenceId());
        } catch (WorkoutScheduleFailure failure) {
            if ("PLANNED_WORKOUT_NOT_FOUND".equals(failure.code())) {
                throw new AiRunNotFoundException("Planned workout occurrence not found or access denied: " + requestDto.occurrenceId());
            } else if ("WORKOUT_SCHEDULE_ACCESS_DENIED".equals(failure.code())) {
                throw new AiRunAccessDeniedException("Access denied to planned workout occurrence: " + requestDto.occurrenceId());
            }
            throw new AiRunValidationException("Failed to access occurrence: " + failure.getMessage());
        }

        if (occurrence == null) {
            throw new AiRunNotFoundException("Planned workout occurrence not found: " + requestDto.occurrenceId());
        }

        UUID studentId = occurrence.studentId();

        // 3. Resolve Coaching Context and Authority
        CoachingContextDetails coaching = resolveCoachingContext(studentId);
        AiDecisionAuthority decisionAuthority;

        if ("HUMAN_COACH".equals(coaching.mode())) {
            // T03: Unrelated trainer cannot access or request
            if (!actorId.equals(studentId) && (coaching.trainerId() == null || !actorId.equals(coaching.trainerId()))) {
                throw new AiRunAccessDeniedException("Only the student or their assigned coach may access workout rescheduling in HUMAN_COACH mode.");
            }
            // In HUMAN_COACH mode, Trainer holds workout plan decision authority
            decisionAuthority = AiDecisionAuthority.TRAINER;
        } else {
            // SELF_DIRECTED
            if (!actorId.equals(studentId)) {
                throw new AiRunAccessDeniedException("In SELF_DIRECTED mode, only the student may request workout rescheduling.");
            }
            decisionAuthority = AiDecisionAuthority.STUDENT;
        }

        // 4. Verify Occurrence Lifecycle Status and Version
        if (!"SCHEDULED".equals(occurrence.status())) {
            throw new AiRunLifecycleConflictException("Planned workout is in status " + occurrence.status() + " and cannot be rescheduled.");
        }

        if (requestDto.expectedOccurrenceVersion() != null && requestDto.expectedOccurrenceVersion() != occurrence.occurrenceVersion()) {
            throw new AiRunLifecycleConflictException("Occurrence version conflict: expected version "
                    + requestDto.expectedOccurrenceVersion() + " but current is " + occurrence.occurrenceVersion());
        }

        Instant asOf = Instant.now(clock);

        String supervision = (occurrence.sessionSummary() != null && occurrence.sessionSummary().supervisionRequirement() != null)
                ? occurrence.sessionSummary().supervisionRequirement()
                : "SELF_PERFORMABLE";

        UUID planId = occurrence.planSummary() != null ? occurrence.planSummary().planId() : null;
        UUID planVersionId = occurrence.planSummary() != null ? occurrence.planSummary().planVersionId() : null;
        UUID planSessionId = occurrence.sessionSummary() != null ? occurrence.sessionSummary().planSessionId() : null;
        String sessionName = occurrence.sessionSummary() != null ? occurrence.sessionSummary().name() : null;

        F05SourceSnapshot sourceSnapshot = new F05SourceSnapshot(
                studentId,
                coaching.periodId(),
                coaching.mode(),
                coaching.trainerId(),
                occurrence.occurrenceId(),
                occurrence.occurrenceVersion(),
                occurrence.status(),
                planId,
                planVersionId,
                planSessionId,
                sessionName,
                occurrence.plannedStartAt(),
                occurrence.plannedEndAt(),
                occurrence.originalPlannedStartAt(),
                occurrence.timezone(),
                occurrence.localStart(),
                occurrence.localEnd(),
                occurrence.startUtcOffset(),
                occurrence.endUtcOffset(),
                supervision,
                occurrence.linkedAppointmentId(),
                occurrence.linkedAppointmentVersion(),
                TrainingContinuityState.RECENT_CONSISTENT,
                asOf
        );

        F05ServiceRequest serviceRequest = new F05ServiceRequest(
                studentId,
                coaching.periodId(),
                coaching.mode(),
                occurrence.occurrenceId(),
                occurrence.occurrenceVersion(),
                planVersionId,
                planSessionId,
                sessionName,
                supervision,
                occurrence.timezone(),
                occurrence.originalPlannedStartAt() != null ? occurrence.originalPlannedStartAt().toString() : null,
                occurrence.plannedStartAt() != null ? occurrence.plannedStartAt().toString() : asOf.toString(),
                occurrence.plannedEndAt() != null ? occurrence.plannedEndAt().toString() : null,
                requestDto.reason(),
                requestDto.preferredTimeOfDay(),
                TrainingContinuityState.RECENT_CONSISTENT.name(),
                occurrence.linkedAppointmentId() != null,
                occurrence.linkedAppointmentId(),
                sourceSnapshot
        );

        return new ContextEnvelopeResult(serviceRequest, sourceSnapshot, decisionAuthority, studentId, coaching.periodId());
    }
}
