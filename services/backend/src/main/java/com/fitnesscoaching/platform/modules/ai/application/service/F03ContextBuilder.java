package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.ChangeType;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetRef;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F03AiServiceClient.BaselinePrescription;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F03AiServiceClient.F03ServiceRequest;
import com.fitnesscoaching.platform.modules.ai.domain.F03SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.auth.domain.AccountStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserAccountStatusQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutExecutionViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PrescriptionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.SessionView;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.VersionDetail;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutExecutionQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class F03ContextBuilder {

    private final CurrentCoachingContextQuery coachingContextQuery;
    private final UserAccountStatusQuery accountStatusQuery;
    private final ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;
    private final ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider;
    private final ObjectProvider<ExerciseReferenceQuery> exerciseReferenceQueryProvider;
    private final ObjectProvider<WorkoutExecutionQueryUseCase> workoutExecutionQueryProvider;
    private final ObjectProvider<WorkoutCompletionEvidenceQuery> completionQueryProvider;
    private final Clock clock;

    public F03ContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            UserAccountStatusQuery accountStatusQuery,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider,
            ObjectProvider<ExerciseReferenceQuery> exerciseReferenceQueryProvider,
            ObjectProvider<WorkoutExecutionQueryUseCase> workoutExecutionQueryProvider,
            ObjectProvider<WorkoutCompletionEvidenceQuery> completionQueryProvider,
            Clock clock
    ) {
        this.coachingContextQuery = coachingContextQuery;
        this.accountStatusQuery = accountStatusQuery;
        this.scheduleCalendarProvider = scheduleCalendarProvider;
        this.workoutPlanQueryProvider = workoutPlanQueryProvider;
        this.exerciseReferenceQueryProvider = exerciseReferenceQueryProvider;
        this.workoutExecutionQueryProvider = workoutExecutionQueryProvider;
        this.completionQueryProvider = completionQueryProvider;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public record CoachingContextDetails(
            UUID periodId,
            String mode,
            UUID trainerId
    ) {}

    public record ContextEnvelopeResult(
            F03ServiceRequest request,
            F03SourceSnapshot sourceSnapshot
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

    public ContextEnvelopeResult buildContextEnvelope(
            UUID studentId,
            UUID actorId,
            WorkoutAdjustmentApiRequest requestDto
    ) {
        if (studentId == null) {
            throw new ApplicationValidationException("studentId cannot be null");
        }
        if (requestDto == null) {
            throw new ApplicationValidationException("WorkoutAdjustmentApiRequest cannot be null");
        }
        UUID effectiveActorId = actorId != null ? actorId : studentId;
        Instant asOf = Instant.now(clock);

        // 1. Verify Actor Account Status
        AccountStatus accountStatus = accountStatusQuery.getAccountStatus(effectiveActorId).orElse(null);
        if (accountStatus != AccountStatus.ACTIVE) {
            throw new AiRunAccessDeniedException("Actor account is not active.");
        }

        // 2. Coaching Authority Check
        CoachingContextDetails coaching = resolveCoachingContext(studentId);
        if ("SELF_DIRECTED".equals(coaching.mode())) {
            if (!effectiveActorId.equals(studentId)) {
                throw new AiRunAccessDeniedException("In SELF_DIRECTED mode, only the student may request a workout adjustment.");
            }
        } else if ("HUMAN_COACH".equals(coaching.mode())) {
            if (coaching.trainerId() == null || !effectiveActorId.equals(coaching.trainerId())) {
                throw new AiRunAccessDeniedException("In HUMAN_COACH mode, only the assigned coach may request a workout adjustment.");
            }
        }

        TargetScope scope = requestDto.scope();
        TargetRef targetRef = requestDto.target();
        if (scope == null || targetRef == null) {
            throw new AiRunValidationException("Target scope and target reference are required.");
        }
        if (scope != targetRef.scope()) {
            throw new AiRunValidationException("Request scope does not match target reference scope.");
        }

        WorkoutPlanQueryUseCase planQuery = workoutPlanQueryProvider.getIfAvailable();
        if (planQuery == null) {
            throw new AiProviderException("Workout plan query service is unavailable.");
        }

        UUID planId;
        Long planAggregateVersion;
        Integer planVersionNumber;
        UUID plannedWorkoutId = null;
        Long occurrenceVersion = null;
        UUID plannedSessionExerciseId = null;
        UUID exercisePrescriptionId = null;
        PrescriptionView targetPrescription = null;

        // 3. Resolve Scope & Baseline Prescriptions
        if (scope == TargetScope.ONE_MATERIALIZED_OCCURRENCE) {
            plannedWorkoutId = targetRef.plannedWorkoutId();
            plannedSessionExerciseId = targetRef.plannedSessionExerciseId();
            if (plannedWorkoutId == null || plannedSessionExerciseId == null) {
                throw new AiRunValidationException("ONE_MATERIALIZED_OCCURRENCE requires plannedWorkoutId and plannedSessionExerciseId.");
            }

            WorkoutScheduleCalendarQuery calendarQuery = scheduleCalendarProvider.getIfAvailable();
            if (calendarQuery == null) {
                throw new AiProviderException("Workout schedule calendar service is unavailable.");
            }

            WorkoutScheduleCalendarQuery.Occurrence occurrence;
            try {
                occurrence = calendarQuery.detail(effectiveActorId, plannedWorkoutId);
            } catch (Exception e) {
                throw new AiRunValidationException("Failed to locate planned workout occurrence: " + plannedWorkoutId, e);
            }
            if (occurrence == null) {
                throw new AiRunValidationException("Planned workout occurrence not found: " + plannedWorkoutId);
            }
            if (!occurrence.studentId().equals(studentId)) {
                throw new AiRunAccessDeniedException("Occurrence does not belong to student: " + studentId);
            }
            if ("COMPLETED".equalsIgnoreCase(occurrence.status())
                    || "CANCELLED".equalsIgnoreCase(occurrence.status())
                    || "SKIPPED".equalsIgnoreCase(occurrence.status())) {
                throw new AiRunValidationException("Cannot adjust occurrence with status: " + occurrence.status());
            }

            occurrenceVersion = occurrence.occurrenceVersion();
            planId = occurrence.planSummary().planId();
            UUID planVersionId = occurrence.planSummary().planVersionId();
            planVersionNumber = occurrence.planSummary().planVersionNumber();

            PlanDetail planDetail = planQuery.detail(effectiveActorId, planId);
            planAggregateVersion = planDetail.plan().aggregateVersion();

            VersionDetail versionDetail = planQuery.version(effectiveActorId, planId, planVersionId);
            for (SessionView session : versionDetail.sessions()) {
                for (PrescriptionView prescription : session.prescriptions()) {
                    if (prescription.id().equals(plannedSessionExerciseId)) {
                        targetPrescription = prescription;
                        break;
                    }
                }
                if (targetPrescription != null) break;
            }

            if (targetPrescription == null) {
                throw new AiRunValidationException("Prescription not found in planned occurrence: " + plannedSessionExerciseId);
            }
        } else {
            // TEMPLATE_OR_FUTURE_OCCURRENCES
            planId = targetRef.planId();
            planVersionNumber = targetRef.planVersionNumber();
            exercisePrescriptionId = targetRef.exercisePrescriptionId();
            if (planId == null || planVersionNumber == null || exercisePrescriptionId == null) {
                throw new AiRunValidationException("TEMPLATE_OR_FUTURE_OCCURRENCES requires planId, planVersionNumber, and exercisePrescriptionId.");
            }

            Optional<PlanDetail> activePlanOpt = planQuery.findActive(effectiveActorId, studentId);
            if (activePlanOpt.isEmpty()) {
                throw new AiRunValidationException("Student has no active workout plan.");
            }
            PlanDetail activePlan = activePlanOpt.get();
            if (!activePlan.plan().id().equals(planId)) {
                throw new AiRunValidationException("Plan " + planId + " is not the active plan for student " + studentId);
            }
            if (activePlan.currentVersion() == null || activePlan.currentVersion().versionNumber() != planVersionNumber) {
                throw new AiRunValidationException("Target plan version " + planVersionNumber + " does not match active version.");
            }

            planAggregateVersion = activePlan.plan().aggregateVersion();
            VersionDetail versionDetail = planQuery.version(effectiveActorId, planId, activePlan.currentVersion().id());
            for (SessionView session : versionDetail.sessions()) {
                for (PrescriptionView prescription : session.prescriptions()) {
                    if (prescription.id().equals(exercisePrescriptionId)) {
                        targetPrescription = prescription;
                        break;
                    }
                }
                if (targetPrescription != null) break;
            }

            if (targetPrescription == null) {
                throw new AiRunValidationException("Prescription not found in active plan: " + exercisePrescriptionId);
            }
        }

        // 4. Extract Baseline Prescription Data
        UUID beforeVariationId = targetPrescription.exerciseVariationId();
        BigDecimal beforeTargetLoad = targetPrescription.targetLoad();
        Integer beforeLoadUnitId = 1; // Standard Unit ID 1 is KG
        String loadUnit = "KG";
        Integer beforeTargetSets = targetPrescription.targetSets();
        Integer beforeTargetRepsMin = targetPrescription.targetRepsMin();
        Integer beforeTargetRepsMax = targetPrescription.targetRepsMax();
        Integer beforeDurationSeconds = targetPrescription.durationSeconds();

        String exerciseName = "Exercise";
        String movementPattern = "GENERAL";
        String equipmentType = "BODYWEIGHT";

        ExerciseReferenceQuery exerciseQuery = exerciseReferenceQueryProvider.getIfAvailable();
        if (exerciseQuery != null) {
            var histOpt = exerciseQuery.resolveHistorical(beforeVariationId);
            if (histOpt.isPresent()) {
                var hist = histOpt.get();
                if (hist.exerciseName() != null && !hist.exerciseName().isBlank()) {
                    exerciseName = hist.exerciseName();
                }
                if (hist.variationName() != null && !hist.variationName().isBlank()) {
                    exerciseName = exerciseName + " (" + hist.variationName() + ")";
                }
            }
        } else if (targetPrescription.exercise() != null) {
            exerciseName = targetPrescription.exercise().exerciseName();
        }

        // 5. Training Continuity Determination
        TrainingContinuityState continuityState = TrainingContinuityState.UNKNOWN_HISTORY;
        WorkoutCompletionEvidenceQuery completionQuery = completionQueryProvider.getIfAvailable();
        if (completionQuery != null) {
            try {
                List<WorkoutCompletionEvidenceQuery.Completion> rawCompletions = completionQuery.completedForStudent(studentId);
                List<WorkoutCompletionEvidenceQuery.Completion> validCompletions = (rawCompletions != null ? rawCompletions : List.<WorkoutCompletionEvidenceQuery.Completion>of())
                        .stream()
                        .filter(c -> {
                            Instant performed = c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt();
                            return performed != null && !performed.isAfter(asOf);
                        })
                        .toList();

                if (validCompletions.isEmpty()) {
                    continuityState = TrainingContinuityState.CONFIRMED_NEW;
                } else {
                    Instant latestEnd = validCompletions.stream()
                            .map(c -> c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt())
                            .filter(Objects::nonNull)
                            .max(Instant::compareTo)
                            .orElse(null);

                    if (latestEnd != null) {
                        LocalDate lastDate = latestEnd.atZone(ZoneOffset.UTC).toLocalDate();
                        LocalDate today = asOf.atZone(ZoneOffset.UTC).toLocalDate();
                        long days = ChronoUnit.DAYS.between(lastDate, today);

                        if (days >= 100) {
                            continuityState = TrainingContinuityState.PROLONGED_INACTIVITY;
                        } else {
                            Instant fourWeeksAgo = asOf.minus(28, ChronoUnit.DAYS);
                            long recentCount = validCompletions.stream()
                                    .map(c -> c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt())
                                    .filter(t -> t != null && !t.isBefore(fourWeeksAgo))
                                    .count();

                            if (days <= 14 && recentCount >= 2) {
                                continuityState = TrainingContinuityState.RECENT_CONSISTENT;
                            } else {
                                continuityState = TrainingContinuityState.UNKNOWN_HISTORY;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // 6. RPE History & Load Increase Evaluation
        boolean allowLoadIncrease = true;
        if (requestDto.allowLoadIncrease() != null && !requestDto.allowLoadIncrease()) {
            allowLoadIncrease = false;
        }
        if (continuityState == TrainingContinuityState.PROLONGED_INACTIVITY
                || continuityState == TrainingContinuityState.CONFIRMED_NEW
                || continuityState == TrainingContinuityState.UNKNOWN_HISTORY) {
            allowLoadIncrease = false;
        }

        // Check recent workout execution logs for RPE evidence
        WorkoutExecutionQueryUseCase executionQuery = workoutExecutionQueryProvider.getIfAvailable();
        boolean hasRpeEvidence = false;
        if (executionQuery != null) {
            try {
                WorkoutExecutionViews.Page page = executionQuery.history(effectiveActorId, studentId, 0, 5);
                if (page != null && page.items() != null && !page.items().isEmpty()) {
                    double rpeSum = 0.0;
                    int rpeCount = 0;
                    for (WorkoutExecutionViews.Detail detail : page.items()) {
                        for (WorkoutExecutionViews.Exercise ex : detail.exercises()) {
                            if (Objects.equals(ex.actualVariationId(), beforeVariationId)
                                    || Objects.equals(ex.prescribedVariationId(), beforeVariationId)) {
                                for (WorkoutExecutionViews.SetView set : ex.sets()) {
                                    if (set.rpe() != null && set.rpe().doubleValue() > 0) {
                                        rpeSum += set.rpe().doubleValue();
                                        rpeCount++;
                                    }
                                }
                            }
                        }
                    }
                    if (rpeCount > 0) {
                        hasRpeEvidence = true;
                        double avgRpe = rpeSum / rpeCount;
                        if (avgRpe > 8.5) {
                            allowLoadIncrease = false; // High strain recorded
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        // If no verified RPE evidence exists in recent history: cannot safely allow load increase
        if (!hasRpeEvidence) {
            allowLoadIncrease = false;
        }

        // 7. Assemble Source Snapshot & Request
        F03SourceSnapshot sourceSnapshot = new F03SourceSnapshot(
                studentId,
                coaching.periodId(),
                coaching.mode(),
                coaching.trainerId(),
                scope,
                planId,
                planAggregateVersion,
                planVersionNumber,
                plannedWorkoutId,
                occurrenceVersion,
                plannedSessionExerciseId,
                exercisePrescriptionId,
                beforeVariationId,
                beforeTargetLoad,
                beforeLoadUnitId,
                beforeTargetSets,
                beforeTargetRepsMin,
                beforeTargetRepsMax,
                beforeDurationSeconds,
                allowLoadIncrease,
                continuityState,
                asOf
        );

        BaselinePrescription baseline = new BaselinePrescription(
                beforeVariationId,
                exerciseName,
                movementPattern,
                equipmentType,
                beforeTargetLoad,
                loadUnit,
                beforeLoadUnitId,
                beforeTargetSets,
                beforeTargetRepsMin,
                beforeTargetRepsMax,
                beforeDurationSeconds
        );

        ChangeType requestedChangeType = requestDto.requestedChangeType() != null
                ? requestDto.requestedChangeType()
                : ChangeType.EXERCISE_SUBSTITUTION;

        List<String> limitations = requestDto.physicalLimitations() != null
                ? new ArrayList<>(requestDto.physicalLimitations())
                : new ArrayList<>();

        List<String> equipment = requestDto.equipmentAvailable() != null
                ? new ArrayList<>(requestDto.equipmentAvailable())
                : new ArrayList<>();

        F03ServiceRequest serviceRequest = new F03ServiceRequest(
                studentId,
                coaching.periodId(),
                coaching.mode(),
                scope,
                targetRef,
                requestedChangeType,
                requestDto.userReason(),
                limitations,
                equipment,
                allowLoadIncrease,
                continuityState,
                baseline,
                sourceSnapshot
        );

        return new ContextEnvelopeResult(serviceRequest, sourceSnapshot);
    }
}
