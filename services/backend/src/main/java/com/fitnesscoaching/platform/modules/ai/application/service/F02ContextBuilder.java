package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.modules.ai.application.port.out.F02AiServiceClient.F02ServiceRequest;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CurrentCoachingContextQuery;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Component
public class F02ContextBuilder {

    private final CurrentCoachingContextQuery coachingContextQuery;
    private final org.springframework.beans.factory.ObjectProvider<GetStudentProfileUseCase> studentProfileProvider;
    private final org.springframework.beans.factory.ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider;

    public F02ContextBuilder(
            CurrentCoachingContextQuery coachingContextQuery,
            org.springframework.beans.factory.ObjectProvider<GetStudentProfileUseCase> studentProfileProvider,
            org.springframework.beans.factory.ObjectProvider<GetCurrentFitnessGoalUseCase> goalProvider
    ) {
        this.coachingContextQuery = coachingContextQuery;
        this.studentProfileProvider = studentProfileProvider;
        this.goalProvider = goalProvider;
    }

    public record CoachingContextDetails(
            UUID periodId,
            String mode,
            UUID trainerId
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

    public F02ServiceRequest buildRequest(
            UUID studentId,
            Map<String, Object> inputPreferences,
            List<UUID> candidateVariationIds
    ) {
        CoachingContextDetails coaching = resolveCoachingContext(studentId);

        String fitnessLevel = "BEGINNER";
        List<String> equipment = new ArrayList<>();
        Map<String, Object> preferences = inputPreferences != null ? new HashMap<>(inputPreferences) : new HashMap<>();

        GetStudentProfileUseCase studentService = studentProfileProvider.getIfAvailable();
        if (studentService != null) {
            try {
                StudentProfile profile = studentService.getStudentProfile(studentId);
                if (profile != null && profile.trainingExperienceLevel() != null) {
                    fitnessLevel = profile.trainingExperienceLevel().name();
                }
                if (profile != null && profile.availableDaysPerWeek() != null && !preferences.containsKey("preferredDaysPerWeek")) {
                    preferences.put("preferredDaysPerWeek", profile.availableDaysPerWeek());
                }
            } catch (Exception ignored) {
                // If student profile not yet initialized, fallback to defaults
            }
        }

        String goalType = "GENERAL_FITNESS";
        GetCurrentFitnessGoalUseCase goalService = goalProvider.getIfAvailable();
        if (goalService != null) {
            try {
                Optional<FitnessGoal> activeGoal = goalService.getCurrentFitnessGoal(studentId);
                if (activeGoal.isPresent()) {
                    String title = activeGoal.get().title();
                    if (title != null && !title.isBlank()) {
                        goalType = title;
                    }
                }
            } catch (Exception ignored) {
                // Goal not found or error, fallback to general fitness
            }
        }

        return new F02ServiceRequest(
                studentId,
                coaching.periodId(),
                coaching.mode(),
                fitnessLevel,
                goalType,
                equipment,
                preferences,
                candidateVariationIds != null ? candidateVariationIds : List.of()
        );
    }
}
