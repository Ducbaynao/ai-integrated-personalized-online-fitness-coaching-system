package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope.ActiveGoalContext;
import com.fitnesscoaching.platform.modules.ai.domain.F01InternalContextEnvelope.PersonalContext;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery.CurrentCoachingContext;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserRoleQuery;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Server-owned builder and authority guard for F01 Personalized Context.
 * Enforces least-privilege scoping:
 * - Student can only request personal context for self.
 * - Trainer can only request personal context for an assigned student with active coaching period.
 * - Missing fields remain null/UNKNOWN; never fabricated.
 */
@Component
public class F01PersonalContextBuilder {

    private static final Logger log = LoggerFactory.getLogger(F01PersonalContextBuilder.class);

    private final CurrentCoachingContextQuery coachingContextQuery;
    private final ObjectProvider<GetStudentProfileUseCase> studentProfileProvider;
    private final ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider;
    private final ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider;
    private final ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider;
    private final ObjectProvider<UserRoleQuery> userRoleQueryProvider;

    public F01PersonalContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            ObjectProvider<GetStudentProfileUseCase> studentProfileProvider,
            ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider,
            ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider
    ) {
        this(coachingContextQuery, studentProfileProvider, goalProvider, workoutPlanQueryProvider, null, null);
    }

    public F01PersonalContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            ObjectProvider<GetStudentProfileUseCase> studentProfileProvider,
            ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider,
            ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider,
            ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider
    ) {
        this(coachingContextQuery, studentProfileProvider, goalProvider, workoutPlanQueryProvider, coachingAuthorityQueryProvider, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public F01PersonalContextBuilder(
            @org.springframework.beans.factory.annotation.Autowired(required = false) CurrentCoachingContextQuery coachingContextQuery,
            ObjectProvider<GetStudentProfileUseCase> studentProfileProvider,
            ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider,
            ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider,
            @org.springframework.beans.factory.annotation.Autowired(required = false) ObjectProvider<UserRoleQuery> userRoleQueryProvider
    ) {
        this.coachingContextQuery = coachingContextQuery;
        this.studentProfileProvider = studentProfileProvider;
        this.goalProvider = goalProvider;
        this.workoutPlanQueryProvider = workoutPlanQueryProvider;
        this.coachingAuthorityQueryProvider = coachingAuthorityQueryProvider;
        this.userRoleQueryProvider = userRoleQueryProvider;
    }

    public PersonalContext buildPersonalContext(UUID actorId, String actorRole, UUID requestedStudentId) {
        UUID effectiveStudentId;

        if ("STUDENT".equalsIgnoreCase(actorRole)) {
            // Student is querying: must only query self
            if (requestedStudentId != null && !requestedStudentId.equals(actorId)) {
                log.warn("Student {} attempted to access personalized context of another student {}", actorId, requestedStudentId);
                throw new AiRunAccessDeniedException("Student is not authorized to query another student's personal context");
            }
            effectiveStudentId = actorId;
        } else if ("TRAINER".equalsIgnoreCase(actorRole)) {
            // Trainer is querying: studentId is required and must have active coaching relationship
            if (requestedStudentId == null) {
                throw new AiRunValidationException("studentId is required for Trainer personalized knowledge inquiry");
            }
            effectiveStudentId = requestedStudentId;
            validateTrainerAuthority(actorId, effectiveStudentId);
        } else {
            throw new AiRunAccessDeniedException("Role " + actorRole + " is not authorized for personalized knowledge inquiry");
        }

        // 1. Determine Coaching Mode
        String coachingMode = "SELF_DIRECTED";
        if (coachingContextQuery != null) {
            Optional<CurrentCoachingContext> ctxOpt = coachingContextQuery.findEffectiveContext(effectiveStudentId, false);
            if (ctxOpt.isPresent() && ctxOpt.get().mode() != null) {
                coachingMode = ctxOpt.get().mode().name();
            }
        }

        // 2. Training experience level from StudentProfile
        String trainingExperience = "UNKNOWN";
        if (studentProfileProvider != null && studentProfileProvider.getIfAvailable() != null) {
            try {
                StudentProfile profile = studentProfileProvider.getIfAvailable().getStudentProfile(effectiveStudentId);
                if (profile != null && profile.trainingExperienceLevel() != null) {
                    trainingExperience = profile.trainingExperienceLevel().name();
                }
            } catch (Exception e) {
                log.debug("Student profile not found or unavailable for {}: {}", effectiveStudentId, e.getMessage());
            }
        }

        // Check domain sharing permissions for Trainer accessing Student data (fail closed)
        boolean hasGoalAccess = actorId.equals(effectiveStudentId);
        boolean hasPlanAccess = actorId.equals(effectiveStudentId);
        if (!actorId.equals(effectiveStudentId)) {
            if (coachingAuthorityQueryProvider != null && coachingAuthorityQueryProvider.getIfAvailable() != null) {
                try {
                    CoachingAuthorityQuery authorityQuery = coachingAuthorityQueryProvider.getIfAvailable();
                    var goalDecision = authorityQuery.evaluateCurrent(new AuthorityRequest(
                            actorId, effectiveStudentId, DataScope.FITNESS_GOAL, DataAccessLevel.VIEW));
                    hasGoalAccess = goalDecision != null && goalDecision.allowed();

                    var planDecision = authorityQuery.evaluateCurrent(new AuthorityRequest(
                            actorId, effectiveStudentId, DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW));
                    hasPlanAccess = planDecision != null && planDecision.allowed();
                } catch (Exception e) {
                    log.warn("Failed to evaluate coaching sharing authority for trainer {} on student {}: {}",
                            actorId, effectiveStudentId, e.getMessage());
                    hasGoalAccess = false;
                    hasPlanAccess = false;
                }
            } else {
                log.warn("CoachingAuthorityQuery is unavailable; defaulting to fail-closed for trainer {} on student {}",
                        actorId, effectiveStudentId);
                hasGoalAccess = false;
                hasPlanAccess = false;
            }
        }

        // 3. Active Goal (requires FITNESS_GOAL sharing grant)
        ActiveGoalContext activeGoal = null;
        if (hasGoalAccess && goalProvider != null && goalProvider.getIfAvailable() != null) {
            try {
                Optional<FitnessGoal> goalOpt = goalProvider.getIfAvailable().getCurrentFitnessGoal(effectiveStudentId);
                if (goalOpt.isPresent()) {
                    FitnessGoal goal = goalOpt.get();
                    if (goal.status() == GoalStatus.ACTIVE) {
                        String goalType = goal.title() != null ? goal.title() : "GENERAL_FITNESS";
                        int version = goal.currentVersion() != null ? goal.currentVersion().versionNumber() : 1;
                        String targetSummary = goal.statusReason();
                        activeGoal = new ActiveGoalContext(goalType, targetSummary, version);
                    }
                }
            } catch (Exception e) {
                log.debug("Current fitness goal query failed for {}: {}", effectiveStudentId, e.getMessage());
            }
        }

        // 4. Active Workout Plan name (requires WORKOUT_PLAN sharing grant)
        String activePlanName = null;
        if (hasPlanAccess && workoutPlanQueryProvider != null && workoutPlanQueryProvider.getIfAvailable() != null) {
            try {
                Optional<PlanDetail> planOpt = workoutPlanQueryProvider.getIfAvailable().findActive(actorId, effectiveStudentId);
                if (planOpt.isPresent() && planOpt.get().plan() != null) {
                    activePlanName = planOpt.get().plan().name();
                }
            } catch (Exception e) {
                log.debug("Workout plan query failed for {}: {}", effectiveStudentId, e.getMessage());
            }
        }

        return new PersonalContext(
                effectiveStudentId,
                coachingMode,
                trainingExperience,
                activeGoal,
                null,
                null,
                activePlanName
        );
    }

    public void validateTrainerAuthority(UUID trainerId, UUID studentId) {
        if (userRoleQueryProvider != null && userRoleQueryProvider.getIfAvailable() != null) {
            UserRoleQuery userRoleQuery = userRoleQueryProvider.getIfAvailable();
            if (!userRoleQuery.hasActiveRole(trainerId, "TRAINER")) {
                throw new AiRunAccessDeniedException("Access denied: active trainer role required to access student context");
            }
        }
        if (coachingContextQuery == null) {
            throw new AiRunAccessDeniedException("Coaching context query unavailable");
        }
        Optional<CurrentCoachingContext> ctxOpt = coachingContextQuery.findEffectiveContext(studentId, false);
        if (ctxOpt.isEmpty()) {
            throw new AiRunAccessDeniedException("Trainer lacks active coaching relationship with student: " + studentId);
        }
        CurrentCoachingContext ctx = ctxOpt.get();
        if (ctx.trainerId() == null || !ctx.trainerId().equals(trainerId)) {
            throw new AiRunAccessDeniedException("Trainer is not assigned to student: " + studentId);
        }
        if (ctx.periodId() == null) {
            throw new AiRunAccessDeniedException("No active coaching period for student: " + studentId);
        }
    }

    public void validateGranularSharingAuthority(UUID trainerId, UUID studentId, boolean hadGoalInContext, boolean hadPlanInContext) {
        if (!hadGoalInContext && !hadPlanInContext) {
            return;
        }
        if (coachingAuthorityQueryProvider == null || coachingAuthorityQueryProvider.getIfAvailable() == null) {
            log.warn("CoachingAuthorityQuery unavailable when revalidating granular sharing for trainer {} on student {}", trainerId, studentId);
            throw new AiRunAccessDeniedException("Access denied: coaching authority evaluation unavailable");
        }
        CoachingAuthorityQuery authorityQuery = coachingAuthorityQueryProvider.getIfAvailable();
        try {
            if (hadGoalInContext) {
                var goalDecision = authorityQuery.evaluateCurrent(new AuthorityRequest(
                        trainerId, studentId, DataScope.FITNESS_GOAL, DataAccessLevel.VIEW));
                if (goalDecision == null || !goalDecision.allowed()) {
                    throw new AiRunAccessDeniedException("Access denied: FITNESS_GOAL sharing permission has been revoked or is inactive");
                }
            }
            if (hadPlanInContext) {
                var planDecision = authorityQuery.evaluateCurrent(new AuthorityRequest(
                        trainerId, studentId, DataScope.WORKOUT_PLAN, DataAccessLevel.VIEW));
                if (planDecision == null || !planDecision.allowed()) {
                    throw new AiRunAccessDeniedException("Access denied: WORKOUT_PLAN sharing permission has been revoked or is inactive");
                }
            }
        } catch (AiRunAccessDeniedException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to evaluate granular sharing authority for trainer {} on student {}: {}",
                    trainerId, studentId, e.getMessage());
            throw new AiRunAccessDeniedException("Access denied: error occurred while evaluating sharing permissions");
        }
    }
}
