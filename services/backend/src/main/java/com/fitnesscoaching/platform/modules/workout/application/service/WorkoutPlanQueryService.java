package com.fitnesscoaching.platform.modules.workout.application.service;

import com.fitnesscoaching.platform.common.exception.*;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.exercise.application.port.in.ExerciseReferenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.*;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.out.WorkoutPlanQueryPort;
import com.fitnesscoaching.platform.modules.workout.domain.DecisionOwnerType;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional(readOnly = true)
public class WorkoutPlanQueryService implements WorkoutPlanQueryUseCase {
    private final WorkoutPlanQueryPort store;
    private final CurrentCoachingContextQuery contexts;
    private final WorkoutPlanAccessPolicy access;
    private final ExerciseReferenceQuery exercises;

    public WorkoutPlanQueryService(WorkoutPlanQueryPort store, CurrentCoachingContextQuery contexts,
                                   WorkoutPlanAccessPolicy access, ExerciseReferenceQuery exercises) {
        this.store = store; this.contexts = contexts; this.access = access; this.exercises = exercises;
    }

    @Override public Page<PlanSummary> list(UUID actorId, UUID requestedStudentId, int page, int size) {
        validatePage(actorId, page, size);
        UUID studentId = requestedStudentId == null ? actorId : requestedStudentId;
        boolean student = actorId.equals(studentId);
        CurrentCoachingContextQuery.CurrentCoachingContext current = null;
        if (!student) {
            if (!currentAllowed(actorId, studentId)) return new Page<>(List.of(), page, size, 0, 0);
            current = contexts.findEffectiveContext(studentId, false).orElse(null);
        }
        List<PlanSummary> visible = new ArrayList<>();
        for (PlanSummary raw : store.findByStudent(studentId)) {
            ReadContext readContext = context(raw, actorId, student, current);
            if (readContext == ReadContext.HISTORICAL && !student
                    && !historicalAllowed(actorId, studentId, occurredAt(raw))) continue;
            visible.add(withContext(raw, readContext));
        }
        return page(visible, page, size);
    }

    @Override public PlanDetail current(UUID actorId, UUID requestedStudentId) {
        if (actorId == null) throw validation();
        UUID studentId = requestedStudentId == null ? actorId : requestedStudentId;
        boolean student = actorId.equals(studentId);
        CurrentCoachingContextQuery.CurrentCoachingContext current = null;
        if (!student) {
            if (!currentAllowed(actorId, studentId)) throw concealed();
            current = contexts.findEffectiveContext(studentId, false).orElse(null);
        }
        PlanSummary raw = store.findActiveByStudent(studentId).orElseThrow(WorkoutPlanQueryService::concealed);
        ReadContext readContext = context(raw, actorId, student, current);
        if (!student && readContext == ReadContext.HISTORICAL
                && !historicalAllowed(actorId, studentId, occurredAt(raw))) throw concealed();
        return detail(withContext(raw, readContext), readContext);
    }

    @Override public PlanDetail detail(UUID actorId, UUID planId) {
        if (actorId == null || planId == null) throw validation();
        PlanSummary raw = store.findPlan(planId).orElseThrow(WorkoutPlanQueryService::concealed);
        boolean student = actorId.equals(raw.studentId());
        CurrentCoachingContextQuery.CurrentCoachingContext current = null;
        if (!student) {
            if (!currentAllowed(actorId, raw.studentId())) throw concealed();
            current = contexts.findEffectiveContext(raw.studentId(), false).orElse(null);
        }
        ReadContext readContext = context(raw, actorId, student, current);
        if (!student && readContext == ReadContext.HISTORICAL
                && !historicalAllowed(actorId, raw.studentId(), occurredAt(raw))) throw concealed();
        return detail(withContext(raw, readContext), readContext);
    }

    @Override public Page<VersionSummary> versions(UUID actorId, UUID planId, int page, int size) {
        validatePage(actorId, page, size);
        PlanSummary plan = store.findPlan(planId).orElseThrow(WorkoutPlanQueryService::concealed);
        boolean student = actorId.equals(plan.studentId());
        CurrentCoachingContextQuery.CurrentCoachingContext current = null;
        if (!student) {
            if (!currentAllowed(actorId, plan.studentId())) throw concealed();
            current = contexts.findEffectiveContext(plan.studentId(), false).orElse(null);
        }
        boolean planCurrent = context(plan, actorId, student, current) == ReadContext.CURRENT;
        List<VersionSummary> visible = new ArrayList<>();
        for (VersionSummary version : store.findVersions(planId)) {
            ReadContext readContext = planCurrent && version.current() ? ReadContext.CURRENT : ReadContext.HISTORICAL;
            if (!student && readContext == ReadContext.HISTORICAL
                    && !historicalAllowed(actorId, plan.studentId(), version.effectiveFrom())) continue;
            visible.add(withContext(version, readContext));
        }
        return page(visible, page, size);
    }

    @Override public VersionDetail version(UUID actorId, UUID planId, UUID versionId) {
        if (actorId == null || planId == null || versionId == null) throw validation();
        PlanSummary plan = store.findPlan(planId).orElseThrow(WorkoutPlanQueryService::concealed);
        VersionDetail raw = store.findVersion(planId, versionId).orElseThrow(() -> new WorkoutPlanFailure(
                WorkoutPlanError.WORKOUT_PLAN_VERSION_NOT_FOUND, "Workout plan version not found"));
        boolean student = actorId.equals(plan.studentId());
        CurrentCoachingContextQuery.CurrentCoachingContext current = null;
        if (!student) {
            if (!currentAllowed(actorId, plan.studentId())) throw concealed();
            current = contexts.findEffectiveContext(plan.studentId(), false).orElse(null);
        }
        boolean planCurrent = context(plan, actorId, student, current) == ReadContext.CURRENT;
        ReadContext readContext = planCurrent && raw.version().current()
                ? ReadContext.CURRENT : ReadContext.HISTORICAL;
        if (!student && readContext == ReadContext.HISTORICAL
                && !historicalAllowed(actorId, plan.studentId(), raw.version().effectiveFrom())) throw concealed();
        List<SessionView> sessions = raw.sessions().stream().map(this::resolveExercises).toList();
        return new VersionDetail(withContext(raw.version(), readContext), sessions);
    }

    private PlanDetail detail(PlanSummary plan, ReadContext readContext) {
        VersionSummary current = store.findVersions(plan.id()).stream().filter(VersionSummary::current).findFirst()
                .map(value -> withContext(value, readContext)).orElse(null);
        return new PlanDetail(plan, current);
    }

    private SessionView resolveExercises(SessionView session) {
        List<PrescriptionView> prescriptions = session.prescriptions().stream().map(value -> {
            Optional<ExerciseReferenceQuery.HistoricalReference> found = exercises.resolveHistorical(
                    value.exerciseVariationId());
            ExercisePresentation presentation = found.map(ref -> new ExercisePresentation(ref.variationId(),
                    ref.exerciseId(), ref.exerciseName(), ref.variationName(), ref.state().name(),
                    ref.canonicalExerciseId(), ref.canonicalExerciseName())).orElseGet(() ->
                    new ExercisePresentation(value.exerciseVariationId(), null, null, null,
                            ExerciseReferenceQuery.PresentationState.UNAVAILABLE.name(), null, null));
            return new PrescriptionView(value.id(), value.exerciseVariationId(), value.sequenceNumber(),
                    value.targetSets(), value.targetRepsMin(), value.targetRepsMax(), value.targetLoad(),
                    value.restSeconds(), value.durationSeconds(), value.instructions(), presentation);
        }).toList();
        return new SessionView(session.id(), session.weekNumber(), session.dayNumber(), session.sequenceNumber(),
                session.name(), session.focus(), session.estimatedDurationMinutes(), session.notes(), prescriptions);
    }

    private ReadContext context(PlanSummary plan, UUID actor, boolean student,
                                CurrentCoachingContextQuery.CurrentCoachingContext current) {
        boolean mutableState = plan.status() == WorkoutPlanStatus.DRAFT
                || plan.status() == WorkoutPlanStatus.ACTIVE || plan.status() == WorkoutPlanStatus.PAUSED;
        if (student) return mutableState ? ReadContext.CURRENT : ReadContext.HISTORICAL;
        return mutableState && current != null && current.periodId().equals(plan.coachingPeriodId())
                && plan.decisionOwnerType() == DecisionOwnerType.TRAINER
                && actor.equals(plan.decisionOwnerId()) ? ReadContext.CURRENT : ReadContext.HISTORICAL;
    }

    private boolean currentAllowed(UUID actor, UUID student) {
        try { access.verifyCurrentTrainer(actor, student, DataAccessLevel.VIEW); return true; }
        catch (RuntimeException denied) { if (isConcealable(denied)) return false; throw denied; }
    }

    private boolean historicalAllowed(UUID actor, UUID student, java.time.Instant occurredAt) {
        try { access.verifyHistoricalTrainer(actor, student, occurredAt); return true; }
        catch (RuntimeException denied) { if (isConcealable(denied)) return false; throw denied; }
    }

    private static boolean isConcealable(RuntimeException denied) {
        return denied instanceof CoachingRelationshipRequiredException
                || denied instanceof DataSharingPermissionRequiredException
                || denied instanceof DataSharingAccessLevelInsufficientException
                || denied instanceof TrainerCapabilityUnavailableException
                || denied instanceof TrainerNotEligibleException;
    }

    private static java.time.Instant occurredAt(PlanSummary plan) {
        return plan.currentVersionEffectiveFrom() == null ? plan.createdAt() : plan.currentVersionEffectiveFrom();
    }

    private static PlanSummary withContext(PlanSummary p, ReadContext context) {
        return new PlanSummary(p.id(), p.studentId(), p.fitnessGoalId(), p.coachingPeriodId(), p.name(),
                p.description(), p.source(), p.status(), p.aggregateVersion(), p.decisionOwnerType(),
                p.decisionOwnerId(), p.basedOnPlanId(), p.basedOnPlanVersionId(), p.createdAt(), p.updatedAt(),
                p.archivedAt(), p.currentVersionId(), p.currentVersionNumber(), p.currentVersionEffectiveFrom(),
                p.currentVersionLockedAt(), context);
    }

    private static VersionSummary withContext(VersionSummary v, ReadContext context) {
        return new VersionSummary(v.id(), v.planId(), v.versionNumber(), v.effectiveFrom(), v.effectiveUntil(),
                v.changeLevel(), v.changeReason(), v.changeSummary(), v.createdBy(), v.createdAt(), v.lockedAt(),
                v.current(), context);
    }

    private static <T> Page<T> page(List<T> values, int page, int size) {
        int from = Math.min(page * size, values.size());
        int to = Math.min(from + size, values.size());
        int totalPages = values.isEmpty() ? 0 : (values.size() + size - 1) / size;
        return new Page<>(List.copyOf(values.subList(from, to)), page, size, values.size(), totalPages);
    }

    private static void validatePage(UUID actor, int page, int size) {
        if (actor == null || page < 0 || size < 1 || size > 100) throw validation();
    }
    private static WorkoutPlanFailure concealed() {
        return new WorkoutPlanFailure(WorkoutPlanError.WORKOUT_PLAN_NOT_FOUND, "Workout plan not found");
    }
    private static WorkoutPlanFailure validation() {
        return new WorkoutPlanFailure(WorkoutPlanError.VALIDATION_FAILED, "Invalid workout plan query");
    }
}
