package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiProviderException;
import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.common.exception.AiRunValidationException;
import com.fitnesscoaching.platform.common.exception.ApplicationValidationException;
import com.fitnesscoaching.platform.common.exception.FieldErrorDto;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanError;
import com.fitnesscoaching.platform.modules.workout.domain.WorkoutPlanFailure;
import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient.F02ServiceRequest;
import com.fitnesscoaching.platform.modules.ai.domain.F02SourceSnapshot;
import com.fitnesscoaching.platform.modules.ai.domain.TrainingContinuityState;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.GoalStatus;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.student.domain.TrainingExperienceLevel;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Component
public class F02ContextBuilder {

    private final CurrentCoachingContextQuery coachingContextQuery;
    private final ObjectProvider<GetStudentProfileUseCase> studentProfileProvider;
    private final ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider;
    private final ObjectProvider<WorkoutCompletionEvidenceQuery> completionQueryProvider;
    private final ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider;
    private final ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider;
    private final Clock clock;

    public F02ContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            ObjectProvider<GetStudentProfileUseCase> studentProfileProvider,
            ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider,
            ObjectProvider<WorkoutCompletionEvidenceQuery> completionQueryProvider,
            ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider,
            ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider,
            Clock clock
    ) {
        this.coachingContextQuery = coachingContextQuery;
        this.studentProfileProvider = studentProfileProvider;
        this.goalProvider = goalProvider;
        this.completionQueryProvider = completionQueryProvider;
        this.workoutPlanQueryProvider = workoutPlanQueryProvider;
        this.coachingAuthorityQueryProvider = coachingAuthorityQueryProvider;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    public record CoachingContextDetails(
            UUID periodId,
            String mode,
            UUID trainerId
    ) {}

    public record ContextEnvelopeResult(
            F02ServiceRequest request,
            F02SourceSnapshot sourceSnapshot
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
            Map<String, Object> inputPreferences,
            List<UUID> candidateVariationIds
    ) {
        if (studentId == null) {
            throw new ApplicationValidationException("studentId cannot be null");
        }
        UUID effectiveActorId = actorId != null ? actorId : studentId;
        Instant asOf = Instant.now(clock);

        CoachingContextDetails coaching = resolveCoachingContext(studentId);
        List<FieldErrorDto> fieldErrors = new ArrayList<>();

        // 1. Mandatory Student Profile Check (Fail-closed on infrastructure unavailable)
        GetStudentProfileUseCase studentService = studentProfileProvider.getIfAvailable();
        if (studentService == null) {
            throw new AiProviderException("Student profile service is unavailable.");
        }
        StudentProfile profile;
        try {
            profile = studentService.getStudentProfile(studentId);
        } catch (Exception e) {
            throw new AiProviderException("Failed to retrieve student profile for student: " + studentId, e);
        }
        if (profile == null) {
            throw new ApplicationValidationException("Student profile does not exist for student: " + studentId);
        }
        if (profile.trainingExperienceLevel() == null) {
            fieldErrors.add(new FieldErrorDto("trainingExperienceLevel", "REQUIRED",
                    "Student training experience level is required before generating a workout plan proposal."));
        }
        String fitnessLevel = profile.trainingExperienceLevel() != null ? profile.trainingExperienceLevel().name() : null;

        // 2. Mandatory Active Fitness Goal Check
        GetCurrentFitnessGoalUseCase goalService = goalProvider.getIfAvailable();
        if (goalService == null) {
            throw new AiProviderException("Fitness goal service is unavailable.");
        }
        Optional<FitnessGoal> activeGoalOpt;
        try {
            activeGoalOpt = goalService.getCurrentFitnessGoal(studentId, false);
        } catch (Exception e) {
            throw new AiProviderException("Failed to retrieve fitness goal for student: " + studentId, e);
        }
        FitnessGoal activeGoal = null;
        if (activeGoalOpt.isEmpty()) {
            fieldErrors.add(new FieldErrorDto("fitnessGoal", "REQUIRED",
                    "Active fitness goal is required before generating a workout plan proposal."));
        } else {
            activeGoal = activeGoalOpt.get();
            if (activeGoal.status() != GoalStatus.ACTIVE) {
                fieldErrors.add(new FieldErrorDto("fitnessGoal.status", "INVALID_STATUS",
                        "Active fitness goal is required before generating a workout plan proposal (current status: " + activeGoal.status() + ")."));
            }
            if (activeGoal.currentVersion() == null) {
                fieldErrors.add(new FieldErrorDto("fitnessGoal.currentVersion", "REQUIRED",
                        "Active fitness goal must have a current version."));
            }
        }
        String goalType = (activeGoal != null && activeGoal.title() != null && !activeGoal.title().isBlank())
                ? activeGoal.title()
                : "GENERAL_FITNESS";

        // 3. Preferences and Input Readiness Checks
        Map<String, Object> preferences = inputPreferences != null ? new HashMap<>(inputPreferences) : new HashMap<>();

        // Timezone resolution first for date calculations
        ZoneId zoneId = ZoneOffset.UTC;
        if (preferences.get("timezone") != null) {
            try {
                zoneId = ZoneId.of(preferences.get("timezone").toString().trim());
            } catch (Exception ignored) {}
        }

        // Days per week
        validateDaysPerWeek(preferences, profile, fieldErrors);

        // Preferred session minutes
        validateSessionMinutes(preferences, profile, fieldErrors);

        // Equipment selection / bodyweight confirmation
        List<String> equipment = validateEquipment(preferences, fieldErrors);

        // Limitations confirmation (chấn thương / hạn chế thể chất)
        validateLimitations(preferences, fieldErrors);

        // Start date validation
        validateStartDate(preferences, zoneId, asOf, fieldErrors);

        // Missing measurements: unknown, never zero
        if (preferences.containsKey("bodyWeightKg") && preferences.get("bodyWeightKg") != null) {
            if (preferences.get("bodyWeightKg") instanceof Number n && n.doubleValue() <= 0) {
                preferences.remove("bodyWeightKg"); // Treat unknown, never zero
            }
        }

        // 4. Training Continuity Determination
        TrainingContinuityState continuityState;
        Integer daysSinceLastWorkout = null;

        // Check authority to read workout history
        boolean hasReadAccess = true;
        if (!effectiveActorId.equals(studentId)) {
            CoachingAuthorityQuery authorityQuery = coachingAuthorityQueryProvider.getIfAvailable();
            if (authorityQuery != null) {
                var decision = authorityQuery.evaluateCurrent(new AuthorityRequest(
                        effectiveActorId, studentId, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW));
                if (!decision.allowed()) {
                    hasReadAccess = false;
                }
            }
        }

        if (!hasReadAccess) {
            continuityState = TrainingContinuityState.RESTRICTED_HISTORY;
            fieldErrors.add(new FieldErrorDto("trainingContinuity", "RESTRICTED",
                    "Workout history access is restricted for this coach; proposal requires student-confirmed history."));
        } else {
            WorkoutCompletionEvidenceQuery completionQuery = completionQueryProvider.getIfAvailable();
            if (completionQuery == null) {
                // Query service not available / unverified coverage -> UNKNOWN_HISTORY
                continuityState = TrainingContinuityState.UNKNOWN_HISTORY;
            } else {
                List<WorkoutCompletionEvidenceQuery.Completion> rawCompletions;
                try {
                    rawCompletions = completionQuery.completedForStudent(studentId);
                } catch (Exception e) {
                    throw new AiProviderException("Failed to query workout completion evidence for student: " + studentId, e);
                }

                // Filter out future completions (> asOf)
                List<WorkoutCompletionEvidenceQuery.Completion> validCompletions = (rawCompletions != null ? rawCompletions : List.<WorkoutCompletionEvidenceQuery.Completion>of())
                        .stream()
                        .filter(c -> {
                            Instant performed = c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt();
                            return performed != null && !performed.isAfter(asOf);
                        })
                        .toList();

                if (validCompletions.isEmpty()) {
                    boolean isZeroMonths = profile.trainingExperienceMonths() != null
                            && profile.trainingExperienceMonths().compareTo(BigDecimal.ZERO) == 0;
                    if (profile.trainingExperienceLevel() == TrainingExperienceLevel.BEGINNER
                            && isZeroMonths
                            && profile.isOnboardingCompleted()) {
                        continuityState = TrainingContinuityState.CONFIRMED_NEW;
                    } else {
                        continuityState = TrainingContinuityState.UNKNOWN_HISTORY;
                    }
                } else {
                    Instant latestEnd = validCompletions.stream()
                            .map(c -> c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt())
                            .filter(Objects::nonNull)
                            .max(Instant::compareTo)
                            .orElse(null);

                    if (latestEnd != null) {
                        LocalDate lastDate = latestEnd.atZone(zoneId).toLocalDate();
                        LocalDate today = asOf.atZone(zoneId).toLocalDate();
                        long days = ChronoUnit.DAYS.between(lastDate, today);
                        daysSinceLastWorkout = (int) Math.max(0, days);

                        if (daysSinceLastWorkout >= 100) {
                            continuityState = TrainingContinuityState.PROLONGED_INACTIVITY;
                        } else {
                            Instant fourWeeksAgo = asOf.minus(28, ChronoUnit.DAYS);
                            long recentCount = validCompletions.stream()
                                    .map(c -> c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt())
                                    .filter(t -> t != null && !t.isBefore(fourWeeksAgo))
                                    .count();

                            // Approved policy: >= 100 days is PROLONGED_INACTIVITY.
                            // RECENT_CONSISTENT requires active recent evidence: last workout <= 14 days and at least 2 completions in last 28 days.
                            // Otherwise, when evidence is sparse or uncertain, default to safe UNKNOWN_HISTORY.
                            if (daysSinceLastWorkout <= 14 && recentCount >= 2) {
                                continuityState = TrainingContinuityState.RECENT_CONSISTENT;
                            } else {
                                continuityState = TrainingContinuityState.UNKNOWN_HISTORY;
                            }
                        }
                    } else {
                        continuityState = TrainingContinuityState.UNKNOWN_HISTORY;
                    }
                }
            }
        }

        // 5. Fail-fast if there are any field validation errors
        if (!fieldErrors.isEmpty()) {
            throw new AiRunValidationException("Required F02 input parameters missing or invalid.", fieldErrors);
        }

        // 6. Active Workout Plan Inspection (fail-closed if service unavailable, propagate access denied)
        WorkoutPlanQueryUseCase workoutPlanQuery = workoutPlanQueryProvider.getIfAvailable();
        if (workoutPlanQuery == null) {
            throw new AiProviderException("Workout plan query service is unavailable.");
        }
        Optional<PlanDetail> activePlanOpt;
        try {
            activePlanOpt = workoutPlanQuery.findActive(effectiveActorId, studentId);
        } catch (WorkoutPlanFailure e) {
            if (e.error() == WorkoutPlanError.WORKOUT_PLAN_ACCESS_DENIED) {
                throw new AiRunAccessDeniedException("Access denied to workout plans for student: " + studentId, e);
            }
            throw new AiProviderException("Failed to query active workout plan for student: " + studentId, e);
        } catch (AccessDeniedException e) {
            throw new AiRunAccessDeniedException("Access denied to workout plans for student: " + studentId, e);
        } catch (Exception e) {
            throw new AiProviderException("Failed to query active workout plan for student: " + studentId, e);
        }

        UUID activePlanId = activePlanOpt.map(d -> d.plan().id()).orElse(null);
        Long activePlanAggVer = activePlanOpt.map(d -> d.plan().aggregateVersion()).orElse(null);
        boolean hasActivePlan = activePlanOpt.isPresent();

        // 7. Source Snapshot
        F02SourceSnapshot sourceSnapshot = new F02SourceSnapshot(
                activeGoal.id(),
                activeGoal.currentVersion().versionNumber(),
                activePlanId,
                activePlanAggVer,
                coaching.periodId(),
                coaching.mode(),
                coaching.trainerId(),
                continuityState,
                daysSinceLastWorkout,
                asOf
        );

        F02ServiceRequest request = new F02ServiceRequest(
                studentId,
                coaching.periodId(),
                coaching.mode(),
                fitnessLevel,
                goalType,
                equipment,
                preferences,
                candidateVariationIds != null ? candidateVariationIds : List.of(),
                continuityState.name(),
                daysSinceLastWorkout,
                activeGoal.id(),
                activeGoal.currentVersion().versionNumber(),
                hasActivePlan
        );

        return new ContextEnvelopeResult(request, sourceSnapshot);
    }


    private void validateDaysPerWeek(Map<String, Object> preferences, StudentProfile profile, List<FieldErrorDto> fieldErrors) {
        Object val = preferences.get("preferredDaysPerWeek");
        if (val == null) {
            val = preferences.get("daysPerWeek");
        }

        Integer daysPerWeek = null;
        if (val != null) {
            if (val instanceof Number n) {
                daysPerWeek = n.intValue();
            } else if (val instanceof String s && !s.isBlank()) {
                try {
                    daysPerWeek = Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    fieldErrors.add(new FieldErrorDto("preferredDaysPerWeek", "INVALID_TYPE",
                            "preferredDaysPerWeek must be an integer between 1 and 7."));
                    return;
                }
            } else {
                fieldErrors.add(new FieldErrorDto("preferredDaysPerWeek", "INVALID_TYPE",
                        "preferredDaysPerWeek must be an integer between 1 and 7."));
                return;
            }
        } else if (profile.availableDaysPerWeek() != null) {
            daysPerWeek = profile.availableDaysPerWeek();
        }

        if (daysPerWeek == null) {
            fieldErrors.add(new FieldErrorDto("preferredDaysPerWeek", "REQUIRED",
                    "Preferred days per week is required."));
            return;
        }
        if (daysPerWeek < 1 || daysPerWeek > 7) {
            fieldErrors.add(new FieldErrorDto("preferredDaysPerWeek", "INVALID_VALUE",
                    "Preferred days per week must be between 1 and 7 (found: " + daysPerWeek + ")."));
            return;
        }
        preferences.put("preferredDaysPerWeek", daysPerWeek);
        preferences.put("daysPerWeek", daysPerWeek);
    }

    private void validateSessionMinutes(Map<String, Object> preferences, StudentProfile profile, List<FieldErrorDto> fieldErrors) {
        Object val = preferences.get("preferredSessionMinutes");
        if (val == null) {
            val = preferences.get("sessionMinutes");
        }

        Integer sessionMinutes = null;
        if (val != null) {
            if (val instanceof Number n) {
                sessionMinutes = n.intValue();
            } else if (val instanceof String s && !s.isBlank()) {
                try {
                    sessionMinutes = Integer.parseInt(s.trim());
                } catch (NumberFormatException e) {
                    fieldErrors.add(new FieldErrorDto("preferredSessionMinutes", "INVALID_TYPE",
                            "preferredSessionMinutes must be an integer between 15 and 180."));
                    return;
                }
            } else {
                fieldErrors.add(new FieldErrorDto("preferredSessionMinutes", "INVALID_TYPE",
                        "preferredSessionMinutes must be an integer between 15 and 180."));
                return;
            }
        } else if (profile.preferredSessionMinutes() != null) {
            sessionMinutes = profile.preferredSessionMinutes();
        }

        if (sessionMinutes == null) {
            fieldErrors.add(new FieldErrorDto("preferredSessionMinutes", "REQUIRED",
                    "Preferred session minutes is required."));
            return;
        }
        if (sessionMinutes < 15 || sessionMinutes > 180) {
            fieldErrors.add(new FieldErrorDto("preferredSessionMinutes", "INVALID_VALUE",
                    "Preferred session minutes must be between 15 and 180 (found: " + sessionMinutes + ")."));
            return;
        }
        preferences.put("preferredSessionMinutes", sessionMinutes);
    }

    private List<String> validateEquipment(Map<String, Object> preferences, List<FieldErrorDto> fieldErrors) {
        Object equipObj = preferences.get("equipment");
        Object allowBwObj = preferences.get("allowBodyweight");
        Object bwOnlyObj = preferences.get("bodyweightOnly");

        List<String> validEquipment = new ArrayList<>();
        if (equipObj != null) {
            if (equipObj instanceof List<?> list) {
                for (Object item : list) {
                    if (item != null && !item.toString().isBlank()) {
                        validEquipment.add(item.toString().trim());
                    }
                }
            } else if (equipObj instanceof String s && !s.isBlank()) {
                for (String part : s.split(",")) {
                    if (!part.isBlank()) validEquipment.add(part.trim());
                }
            } else {
                fieldErrors.add(new FieldErrorDto("equipment", "INVALID_TYPE",
                        "equipment must be a list of strings."));
                return validEquipment;
            }
        }

        boolean allowBodyweight = false;
        if (allowBwObj != null) {
            if (!(allowBwObj instanceof Boolean) && !"true".equalsIgnoreCase(allowBwObj.toString()) && !"false".equalsIgnoreCase(allowBwObj.toString())) {
                fieldErrors.add(new FieldErrorDto("allowBodyweight", "INVALID_TYPE",
                        "allowBodyweight must be a boolean."));
                return validEquipment;
            }
            allowBodyweight = (allowBwObj instanceof Boolean b) ? b : Boolean.parseBoolean(allowBwObj.toString());
        }
        if (bwOnlyObj != null) {
            if (Boolean.TRUE.equals(bwOnlyObj) || "true".equalsIgnoreCase(bwOnlyObj.toString())) {
                allowBodyweight = true;
            }
        }
        if (validEquipment.contains("BODYWEIGHT") || validEquipment.contains("bodyweight")) {
            allowBodyweight = true;
        }

        if (validEquipment.isEmpty() && !allowBodyweight) {
            fieldErrors.add(new FieldErrorDto("equipment", "REQUIRED",
                    "Equipment selection or bodyweight confirmation is required."));
            return validEquipment;
        }

        if (allowBodyweight && !validEquipment.contains("BODYWEIGHT")) {
            validEquipment.add("BODYWEIGHT");
        }
        preferences.put("equipment", validEquipment);
        preferences.put("allowBodyweight", allowBodyweight);
        return validEquipment;
    }

    private void validateLimitations(Map<String, Object> preferences, List<FieldErrorDto> fieldErrors) {
        Object hasLimObj = preferences.get("hasPhysicalLimitations");
        Object confObj = preferences.get("limitationsConfirmed");
        Object limDescObj = preferences.get("limitationsDescription");
        Object limObj = preferences.get("limitations");
        Object injObj = preferences.get("injuries");

        // 1. If hasPhysicalLimitations is explicitly provided
        if (hasLimObj != null) {
            if (!(hasLimObj instanceof Boolean) && !"true".equalsIgnoreCase(hasLimObj.toString()) && !"false".equalsIgnoreCase(hasLimObj.toString())) {
                fieldErrors.add(new FieldErrorDto("hasPhysicalLimitations", "INVALID_TYPE",
                        "hasPhysicalLimitations must be a boolean."));
                return;
            }
            boolean hasLimitations = (hasLimObj instanceof Boolean b) ? b : Boolean.parseBoolean(hasLimObj.toString());
            if (!hasLimitations) {
                // User explicitly answered and confirmed: NO physical limitations
                preferences.put("hasPhysicalLimitations", false);
                preferences.put("limitationsConfirmed", true);
                return;
            } else {
                // User declared they HAVE physical limitations. Description is required!
                String desc = extractNonBlankString(limDescObj, limObj, injObj);
                if (desc == null) {
                    fieldErrors.add(new FieldErrorDto("limitationsDescription", "REQUIRED",
                            "Description of physical limitations is required when hasPhysicalLimitations is true."));
                    return;
                }
                preferences.put("hasPhysicalLimitations", true);
                preferences.put("limitationsConfirmed", true);
                preferences.put("limitationsDescription", desc);
                return;
            }
        }

        // 2. If limitationsConfirmed is provided without hasPhysicalLimitations
        if (confObj != null) {
            if (!(confObj instanceof Boolean) && !"true".equalsIgnoreCase(confObj.toString()) && !"false".equalsIgnoreCase(confObj.toString())) {
                fieldErrors.add(new FieldErrorDto("limitationsConfirmed", "INVALID_TYPE",
                        "limitationsConfirmed must be a boolean."));
                return;
            }
            boolean isConfirmed = (confObj instanceof Boolean b) ? b : Boolean.parseBoolean(confObj.toString());
            if (!isConfirmed) {
                fieldErrors.add(new FieldErrorDto("limitationsConfirmed", "NOT_CONFIRMED",
                        "Physical limitations status must be explicitly confirmed."));
                return;
            }
            String desc = extractNonBlankString(limDescObj, limObj, injObj);
            preferences.put("limitationsConfirmed", true);
            if (desc != null) {
                preferences.put("hasPhysicalLimitations", true);
                preferences.put("limitationsDescription", desc);
            } else {
                preferences.put("hasPhysicalLimitations", false);
            }
            return;
        }

        // 3. If a direct limitations / injuries string or list is provided
        if (limObj != null || injObj != null || limDescObj != null) {
            String desc = extractNonBlankString(limDescObj, limObj, injObj);
            if (desc != null) {
                boolean isNone = "NONE".equalsIgnoreCase(desc) || "KHONG".equalsIgnoreCase(desc) || "KHÔNG".equalsIgnoreCase(desc);
                preferences.put("limitationsConfirmed", true);
                preferences.put("hasPhysicalLimitations", !isNone);
                preferences.put("limitationsDescription", desc);
                return;
            } else {
                fieldErrors.add(new FieldErrorDto("limitations", "REQUIRED",
                        "Physical limitations declaration cannot be empty."));
                return;
            }
        }

        // 4. Missing completely (medicalClearance alone does not substitute)
        fieldErrors.add(new FieldErrorDto("limitations", "REQUIRED",
                "Physical limitations or health status confirmation is required."));
    }

    private String extractNonBlankString(Object... objects) {
        for (Object obj : objects) {
            if (obj == null) continue;
            if (obj instanceof String s && !s.isBlank()) {
                return s.trim();
            }
            if (obj instanceof List<?> list && !list.isEmpty()) {
                List<String> items = list.stream()
                        .filter(Objects::nonNull)
                        .map(Object::toString)
                        .map(String::trim)
                        .filter(s -> !s.isBlank())
                        .toList();
                if (!items.isEmpty()) {
                    return String.join(", ", items);
                }
            }
        }
        return null;
    }

    private void validateStartDate(Map<String, Object> preferences, ZoneId zoneId, Instant asOf, List<FieldErrorDto> fieldErrors) {
        Object val = preferences.get("startDate");
        if (val == null || (val instanceof String s && s.isBlank())) {
            fieldErrors.add(new FieldErrorDto("startDate", "REQUIRED",
                    "Workout plan start date is required."));
            return;
        }
        if (!(val instanceof String) && !(val instanceof LocalDate)) {
            fieldErrors.add(new FieldErrorDto("startDate", "INVALID_TYPE",
                    "startDate must be a date string (YYYY-MM-DD)."));
            return;
        }

        LocalDate startDate;
        try {
            startDate = (val instanceof LocalDate ld) ? ld : LocalDate.parse(val.toString().trim());
        } catch (Exception e) {
            fieldErrors.add(new FieldErrorDto("startDate", "INVALID_DATE",
                    "Workout plan start date format is invalid: " + val));
            return;
        }

        LocalDate today = asOf.atZone(zoneId).toLocalDate();
        if (startDate.isBefore(today)) {
            fieldErrors.add(new FieldErrorDto("startDate", "PAST_DATE",
                    "Workout plan start date cannot be in the past (today is " + today + ")."));
            return;
        }
        preferences.put("startDate", startDate.toString());
    }

    public F02ServiceRequest buildRequest(
            UUID studentId,
            Map<String, Object> inputPreferences,
            List<UUID> candidateVariationIds
    ) {
        return buildContextEnvelope(studentId, studentId, inputPreferences, candidateVariationIds).request();
    }
}
