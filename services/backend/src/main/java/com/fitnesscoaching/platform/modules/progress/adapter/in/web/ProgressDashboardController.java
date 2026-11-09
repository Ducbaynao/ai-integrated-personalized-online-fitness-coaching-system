package com.fitnesscoaching.platform.modules.progress.adapter.in.web;

import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.goal.application.port.in.GetCurrentFitnessGoalUseCase;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoal;
import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.measurement.application.port.in.MeasurementProgressQuery;
import com.fitnesscoaching.platform.modules.progress.adapter.in.web.dto.ProgressDashboardResponse;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.student.application.port.in.GetStudentProfileUseCase;
import com.fitnesscoaching.platform.modules.student.domain.StudentProfile;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutCompletionEvidenceQuery;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/progress/summary")
public class ProgressDashboardController {

    private final ProgressEngineQuery progressEngineQuery;
    private final GetCurrentFitnessGoalUseCase currentGoalUseCase;
    private final GetStudentProfileUseCase studentProfileUseCase;
    private final CoachingAuthorityQuery coachingAuthority;
    private final MeasurementProgressQuery measurementQuery;
    private final WorkoutCompletionEvidenceQuery workoutCompletionQuery;

    public ProgressDashboardController(
            ProgressEngineQuery progressEngineQuery,
            GetCurrentFitnessGoalUseCase currentGoalUseCase,
            GetStudentProfileUseCase studentProfileUseCase,
            CoachingAuthorityQuery coachingAuthority,
            MeasurementProgressQuery measurementQuery,
            WorkoutCompletionEvidenceQuery workoutCompletionQuery
    ) {
        this.progressEngineQuery = progressEngineQuery;
        this.currentGoalUseCase = currentGoalUseCase;
        this.studentProfileUseCase = studentProfileUseCase;
        this.coachingAuthority = coachingAuthority;
        this.measurementQuery = measurementQuery;
        this.workoutCompletionQuery = workoutCompletionQuery;
    }

    @GetMapping
    public ResponseEntity<ProgressDashboardResponse> getProgressSummary(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) UUID studentId,
            @RequestParam(required = false, defaultValue = "CURRENT_GOAL") String scope,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false, defaultValue = "Asia/Ho_Chi_Minh") String timezone,
            @RequestParam(required = false) Set<String> metricCodes
    ) {
        UUID actorId = UUID.fromString(jwt.getSubject());
        UUID targetStudentId = studentId != null ? studentId : actorId;
        ZoneId zone = ZoneId.of(timezone != null ? timezone : "Asia/Ho_Chi_Minh");

        String normalizedScope = "LIFETIME".equalsIgnoreCase(scope) ? "LIFETIME" : "CURRENT_GOAL";

        LocalDate effectiveFrom = fromDate;
        LocalDate effectiveTo = toDate != null ? toDate : LocalDate.now(zone);

        boolean isSelf = actorId.equals(targetStudentId);
        boolean canViewGoal = isSelf;
        if (!isSelf) {
            AuthorityDecision goalDecision = coachingAuthority.evaluateCurrent(
                    new AuthorityRequest(actorId, targetStudentId, DataScope.FITNESS_GOAL, DataAccessLevel.VIEW)
            );
            canViewGoal = goalDecision.allowed();
        }

        ProgressDashboardResponse.GoalInfo goalInfo = null;

        if ("CURRENT_GOAL".equalsIgnoreCase(normalizedScope)) {
            if (canViewGoal) {
                Optional<FitnessGoal> activeGoalOpt = currentGoalUseCase.getCurrentFitnessGoal(targetStudentId, true);
                if (activeGoalOpt.isPresent()) {
                    FitnessGoal goal = activeGoalOpt.get();
                    FitnessGoalVersion version = goal.currentVersion();

                    LocalDate goalStart = version != null ? version.startDate() : null;
                    LocalDate goalTarget = version != null ? version.targetDate() : null;

                    if (effectiveFrom == null) {
                        effectiveFrom = goalStart != null ? goalStart : LocalDate.now(zone).minusMonths(1);
                    }

                    List<ProgressDashboardResponse.GoalTargetInfo> targetInfos = List.of();
                    if (version != null && version.targets() != null) {
                        targetInfos = version.targets().stream().map(t -> new ProgressDashboardResponse.GoalTargetInfo(
                                t.metricDefinitionId(),
                                t.metricCode(),
                                t.metricDisplayName(),
                                t.targetValue(),
                                t.targetMinValue(),
                                t.targetMaxValue(),
                                t.unitCode(),
                                t.unitSymbol()
                        )).toList();
                    }

                    goalInfo = new ProgressDashboardResponse.GoalInfo(
                            goal.id(),
                            goal.title(),
                            goal.status() != null ? goal.status().name() : "ACTIVE",
                            goalStart,
                            goalTarget,
                            version != null ? version.durationDays() : null,
                            targetInfos
                    );
                }
            }
            if (effectiveFrom == null) {
                effectiveFrom = LocalDate.now(zone).minusMonths(1);
            }
        } else {
            // LIFETIME scope - resolve the authoritative continuous history start:
            // earliest of student profile creation date, earliest measurement, or earliest workout.
            if (effectiveFrom == null) {
                LocalDate earliestDate = null;
                try {
                    StudentProfile profile = studentProfileUseCase.getStudentProfile(targetStudentId);
                    if (profile != null && profile.createdAt() != null) {
                        earliestDate = profile.createdAt().atZone(zone).toLocalDate();
                    }
                } catch (Exception ignored) {
                }

                // Check earliest measurement observation (respecting access scope bounds if trainer)
                try {
                    Optional<Instant> earliestMeasurement = measurementQuery.getEarliestObservationTime(actorId, targetStudentId);
                    if (earliestMeasurement.isPresent()) {
                        LocalDate mDate = earliestMeasurement.get().atZone(zone).toLocalDate();
                        if (earliestDate == null || mDate.isBefore(earliestDate)) {
                            earliestDate = mDate;
                        }
                    }
                } catch (Exception ignored) {
                }

                // Check earliest workout execution
                try {
                    List<WorkoutCompletionEvidenceQuery.Completion> completions = workoutCompletionQuery.completedForStudent(targetStudentId);
                    if (completions != null && !completions.isEmpty()) {
                        for (var c : completions) {
                            Instant performed = c.performedEndAt() != null ? c.performedEndAt() : c.performedStartAt();
                            if (performed != null) {
                                LocalDate wDate = performed.atZone(zone).toLocalDate();
                                if (earliestDate == null || wDate.isBefore(earliestDate)) {
                                    earliestDate = wDate;
                                }
                            }
                        }
                    }
                } catch (Exception ignored) {
                }

                effectiveFrom = earliestDate != null ? earliestDate : LocalDate.now(zone).minusMonths(1);
            }
        }

        Instant asOf = Instant.now();
        ProgressEngineQuery.ProgressSnapshot snapshot = progressEngineQuery.query(
                actorId, targetStudentId, metricCodes, effectiveFrom, effectiveTo, zone, asOf
        );

        ProgressDashboardResponse response = new ProgressDashboardResponse(
                targetStudentId,
                normalizedScope,
                effectiveFrom,
                effectiveTo,
                zone.getId(),
                asOf,
                goalInfo,
                snapshot.workout(),
                snapshot.measurement(),
                snapshot.continuity(),
                snapshot.historicalLimits(),
                snapshot.compositeWatermark()
        );

        return ResponseEntity.ok(response);
    }
}
