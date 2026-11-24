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
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery.ProgressSnapshot;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressError;
import com.fitnesscoaching.platform.modules.progress.domain.ProgressFailure;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.LinkedWorkoutAppointmentQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutMissedEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery.Occurrence;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutScheduleFailure;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
public class F05ContextBuilder {

    private final CurrentCoachingContextQuery coachingContextQuery;
    private final UserAccountStatusQuery accountStatusQuery;
    private final ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;
    private final Clock clock;
    private final WorkoutMissedEvidenceQuery missedEvidence;
    private final ObjectProvider<ProgressEngineQuery> progressEngineQueryProvider;
    private final ObjectProvider<WorkoutCompletionEvidenceQuery> workoutCompletionQueryProvider;
    private final ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider;
    private final ObjectProvider<GetStudentProfileUseCase> studentProfileUseCaseProvider;
    private final ObjectProvider<LinkedWorkoutAppointmentQuery> linkedAppointmentQueryProvider;

    @Autowired
    public F05ContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            UserAccountStatusQuery accountStatusQuery,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            Clock clock,
            WorkoutMissedEvidenceQuery missedEvidence,
            ObjectProvider<ProgressEngineQuery> progressEngineQueryProvider,
            ObjectProvider<WorkoutCompletionEvidenceQuery> workoutCompletionQueryProvider,
            ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider,
            ObjectProvider<GetStudentProfileUseCase> studentProfileUseCaseProvider,
            @Autowired(required = false) ObjectProvider<LinkedWorkoutAppointmentQuery> linkedAppointmentQueryProvider
    ) {
        this.coachingContextQuery = coachingContextQuery;
        this.accountStatusQuery = accountStatusQuery;
        this.scheduleCalendarProvider = scheduleCalendarProvider;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.missedEvidence = missedEvidence;
        this.progressEngineQueryProvider = progressEngineQueryProvider;
        this.workoutCompletionQueryProvider = workoutCompletionQueryProvider;
        this.coachingAuthorityQueryProvider = coachingAuthorityQueryProvider;
        this.studentProfileUseCaseProvider = studentProfileUseCaseProvider;
        this.linkedAppointmentQueryProvider = linkedAppointmentQueryProvider;
    }

    public F05ContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            UserAccountStatusQuery accountStatusQuery,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            Clock clock,
            WorkoutMissedEvidenceQuery missedEvidence,
            ObjectProvider<ProgressEngineQuery> progressEngineQueryProvider,
            ObjectProvider<WorkoutCompletionEvidenceQuery> workoutCompletionQueryProvider,
            ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider,
            ObjectProvider<GetStudentProfileUseCase> studentProfileUseCaseProvider
    ) {
        this(coachingContextQuery, accountStatusQuery, scheduleCalendarProvider, clock, missedEvidence,
                progressEngineQueryProvider, workoutCompletionQueryProvider, coachingAuthorityQueryProvider,
                studentProfileUseCaseProvider, null);
    }

    public F05ContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            UserAccountStatusQuery accountStatusQuery,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            Clock clock,
            WorkoutMissedEvidenceQuery missedEvidence
    ) {
        this(coachingContextQuery, accountStatusQuery, scheduleCalendarProvider, clock, missedEvidence,
                null, null, null, null, null);
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
        var evidence = missedEvidence.find(occurrence.occurrenceId());
        if (evidence.isEmpty() || evidence.get().occurrenceVersion() != occurrence.occurrenceVersion()
                || !evidence.get().canRescheduleAt(asOf)) {
            throw new AiRunLifecycleConflictException("Workout must be unstarted and either upcoming or past its known end time.");
        }

        String rawSupervision = (occurrence.sessionSummary() != null && occurrence.sessionSummary().supervisionRequirement() != null)
                ? occurrence.sessionSummary().supervisionRequirement()
                : null;
        String supervision = mapSupervisionToAiContract(rawSupervision);

        UUID planId = occurrence.planSummary() != null ? occurrence.planSummary().planId() : null;
        UUID planVersionId = occurrence.planSummary() != null ? occurrence.planSummary().planVersionId() : null;
        UUID planSessionId = occurrence.sessionSummary() != null ? occurrence.sessionSummary().planSessionId() : null;
        String sessionName = occurrence.sessionSummary() != null ? occurrence.sessionSummary().name() : null;

        if (occurrence.timezone() == null || occurrence.timezone().isBlank()) {
            throw new AiRunLifecycleConflictException("Planned workout occurrence is missing required schedule timezone and cannot be rescheduled.");
        }
        String effectiveTimezone = occurrence.timezone();

        ContinuityResolution continuityResolution = resolveTrainingContinuity(actorId, studentId, effectiveTimezone, asOf);
        TrainingContinuityState continuityState = continuityResolution.state();

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
                continuityState,
                continuityResolution.daysSinceLastWorkout(),
                continuityResolution.watermark(),
                asOf
        );

        String appointmentStartAt = null;
        String appointmentEndAt = null;
        if (occurrence.linkedAppointmentId() != null && linkedAppointmentQueryProvider != null) {
            LinkedWorkoutAppointmentQuery linkedQuery = linkedAppointmentQueryProvider.getIfAvailable();
            if (linkedQuery != null) {
                var links = linkedQuery.findActiveLinks(occurrence.occurrenceId());
                if (links != null) {
                    for (var link : links) {
                        if (link.appointmentId().equals(occurrence.linkedAppointmentId()) && "CONFIRMED".equals(link.status())) {
                            if (!Objects.equals(occurrence.linkedAppointmentVersion(), link.version())) {
                                throw new AiRunLifecycleConflictException("Appointment changed while building context; generate a fresh proposal.");
                            }
                            appointmentStartAt = link.startAt() != null ? link.startAt().toString() : null;
                            appointmentEndAt = link.endAt() != null ? link.endAt().toString() : null;
                            break;
                        }
                    }
                }
            }
        }

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
                effectiveTimezone,
                occurrence.originalPlannedStartAt() != null ? occurrence.originalPlannedStartAt().toString() : null,
                occurrence.plannedStartAt() != null ? occurrence.plannedStartAt().toString() : asOf.toString(),
                occurrence.plannedEndAt() != null ? occurrence.plannedEndAt().toString() : null,
                requestDto.reason(),
                requestDto.preferredTimeOfDay(),
                continuityState != null ? continuityState.name() : "UNKNOWN_HISTORY",
                occurrence.linkedAppointmentId() != null,
                occurrence.linkedAppointmentId(),
                appointmentStartAt,
                appointmentEndAt,
                sourceSnapshot
        );

        return new ContextEnvelopeResult(serviceRequest, sourceSnapshot, decisionAuthority, studentId, coaching.periodId());
    }

    public record ContinuityResolution(
            TrainingContinuityState state,
            Integer daysSinceLastWorkout,
            String watermark
    ) {}

    public ContinuityResolution resolveTrainingContinuity(
            UUID actorId,
            UUID studentId,
            String timezoneStr,
            Instant asOf
    ) {
        ZoneId zoneId;
        try {
            zoneId = ZoneId.of(timezoneStr != null ? timezoneStr : "Asia/Ho_Chi_Minh");
        } catch (Exception ex) {
            zoneId = ZoneId.of("Asia/Ho_Chi_Minh");
        }

        // 1. Primary source: ProgressEngineQuery (authoritative, verified signals with composite watermark)
        ProgressEngineQuery progressEngineQuery = progressEngineQueryProvider != null
                ? progressEngineQueryProvider.getIfAvailable() : null;
        if (progressEngineQuery != null) {
            try {
                LocalDate today = asOf.atZone(zoneId).toLocalDate();
                LocalDate fromDate = today.minusDays(28);
                LocalDate toDate = today;
                ProgressSnapshot snapshot = progressEngineQuery.query(
                        actorId,
                        studentId,
                        (java.util.Set<String>) null,
                        fromDate,
                        toDate,
                        zoneId,
                        asOf
                );
                if (snapshot != null && snapshot.continuity() != null && snapshot.continuity().state() != null) {
                    TrainingContinuityState mappedState = switch (snapshot.continuity().state()) {
                        case CONFIRMED_NEW -> TrainingContinuityState.CONFIRMED_NEW;
                        case PROLONGED_INACTIVITY -> TrainingContinuityState.PROLONGED_INACTIVITY;
                        case RECENT_ACTIVITY_OBSERVED -> TrainingContinuityState.RECENT_ACTIVITY_OBSERVED;
                        case RESTRICTED_HISTORY -> TrainingContinuityState.RESTRICTED_HISTORY;
                        case UNKNOWN_HISTORY -> TrainingContinuityState.UNKNOWN_HISTORY;
                    };
                    return new ContinuityResolution(
                            mappedState,
                            snapshot.continuity().daysSinceLastWorkout(),
                            snapshot.compositeWatermark()
                    );
                }
            } catch (ProgressFailure pf) {
                if (pf.error() == ProgressError.PROGRESS_ACCESS_DENIED) {
                    return new ContinuityResolution(TrainingContinuityState.RESTRICTED_HISTORY, null, null);
                }
                return new ContinuityResolution(TrainingContinuityState.UNKNOWN_HISTORY, null, null);
            } catch (Exception ignored) {
                // Fail-safe to fallback resolution below
            }
        }

        // 2. Secondary fallback: WorkoutCompletionEvidenceQuery + CoachingAuthorityQuery + GetStudentProfileUseCase
        WorkoutCompletionEvidenceQuery completionQuery = workoutCompletionQueryProvider != null
                ? workoutCompletionQueryProvider.getIfAvailable() : null;
        if (completionQuery != null) {
            Instant historyFrom = null;
            Instant historyUntil = null;
            if (!actorId.equals(studentId)) {
                CoachingAuthorityQuery authorityQuery = coachingAuthorityQueryProvider != null
                        ? coachingAuthorityQueryProvider.getIfAvailable() : null;
                if (authorityQuery != null) {
                    var decision = authorityQuery.evaluateCurrent(new AuthorityRequest(
                            actorId, studentId, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW));
                    if (decision != null) {
                        if (!decision.allowed()) {
                            return new ContinuityResolution(TrainingContinuityState.RESTRICTED_HISTORY, null, null);
                        }
                        historyFrom = decision.historyFrom();
                        historyUntil = decision.historyUntil();
                        if (historyUntil != null && historyUntil.isBefore(asOf)) {
                            return new ContinuityResolution(TrainingContinuityState.RESTRICTED_HISTORY, null, null);
                        }
                    }
                }
            }

            try {
                final Instant hFrom = historyFrom;
                final Instant effectiveUntil = (historyUntil != null && historyUntil.isBefore(asOf)) ? historyUntil : asOf;
                List<WorkoutCompletionEvidenceQuery.Completion> rawCompletions = completionQuery.completedForStudent(studentId);
                List<WorkoutCompletionEvidenceQuery.Completion> validCompletions = (rawCompletions != null ? rawCompletions : List.<WorkoutCompletionEvidenceQuery.Completion>of())
                        .stream()
                        .filter(c -> {
                            Instant performed = c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt();
                            if (performed == null || performed.isAfter(effectiveUntil)) {
                                return false;
                            }
                            return hFrom == null || !performed.isBefore(hFrom);
                        })
                        .toList();

                if (!validCompletions.isEmpty()) {
                    Instant latestEnd = validCompletions.stream()
                            .map(c -> c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt())
                            .filter(Objects::nonNull)
                            .max(Instant::compareTo)
                            .orElse(null);

                    if (latestEnd != null) {
                        LocalDate lastDate = latestEnd.atZone(zoneId).toLocalDate();
                        LocalDate today = asOf.atZone(zoneId).toLocalDate();
                        long days = ChronoUnit.DAYS.between(lastDate, today);
                        int daysSince = (int) Math.max(0, days);

                        if (daysSince >= 100) {
                            return new ContinuityResolution(TrainingContinuityState.PROLONGED_INACTIVITY, daysSince, null);
                        } else {
                            // Invariant D06: Do not infer RECENT_CONSISTENT without approved specialist threshold
                            return new ContinuityResolution(TrainingContinuityState.RECENT_ACTIVITY_OBSERVED, daysSince, null);
                        }
                    }
                } else {
                    if (historyFrom != null && historyFrom.isAfter(asOf.minus(100, ChronoUnit.DAYS))) {
                        return new ContinuityResolution(TrainingContinuityState.RESTRICTED_HISTORY, null, null);
                    }
                    GetStudentProfileUseCase profileUseCase = studentProfileUseCaseProvider != null
                            ? studentProfileUseCaseProvider.getIfAvailable() : null;
                    if (profileUseCase != null) {
                        try {
                            StudentProfile profile = profileUseCase.getStudentProfile(studentId);
                            if (profile != null) {
                                boolean isZeroMonths = profile.trainingExperienceMonths() != null
                                        && profile.trainingExperienceMonths().compareTo(BigDecimal.ZERO) == 0;
                                boolean isBeginner = profile.trainingExperienceLevel() == TrainingExperienceLevel.BEGINNER;
                                boolean onboardingDoneBeforeAsOf = profile.onboardingCompletedAt() != null
                                        && !profile.onboardingCompletedAt().isAfter(asOf);

                                if (isBeginner && isZeroMonths && onboardingDoneBeforeAsOf) {
                                    return new ContinuityResolution(TrainingContinuityState.CONFIRMED_NEW, null, null);
                                }
                            }
                        } catch (Exception ignored) {}
                    }
                }
            } catch (Exception ignored) {}
        }

        // 3. Fail-closed: missing or unverified history is UNKNOWN_HISTORY, never assumed consistent
        return new ContinuityResolution(TrainingContinuityState.UNKNOWN_HISTORY, null, null);
    }

    private String mapSupervisionToAiContract(String dbSupervision) {
        if (dbSupervision == null) {
            return "SELF_PERFORMABLE";
        }
        return switch (dbSupervision) {
            case "COACH_REQUIRED" -> "COACH_REQUIRED";
            case "COACH_PREFERRED", "COACH_OPTIONAL" -> "COACH_OPTIONAL";
            default -> "SELF_PERFORMABLE";
        };
    }
}
