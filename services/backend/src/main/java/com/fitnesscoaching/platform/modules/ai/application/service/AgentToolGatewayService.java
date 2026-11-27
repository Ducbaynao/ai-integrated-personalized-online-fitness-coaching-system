package com.fitnesscoaching.platform.modules.ai.application.service;

import com.fitnesscoaching.platform.common.exception.AiRunAccessDeniedException;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.ChangeType;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetRef;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.F03WorkoutAdjustmentPayload.TargetScope;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutAdjustmentApiResponse;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiRequest;
import com.fitnesscoaching.platform.modules.ai.adapter.in.web.dto.WorkoutRescheduleApiResponse;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiRunF01UseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutAdjustmentUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.in.AiWorkoutRescheduleUseCase;
import com.fitnesscoaching.platform.modules.ai.application.port.out.AiRunRepository;
import com.fitnesscoaching.platform.modules.ai.domain.AiRun;
import com.fitnesscoaching.platform.modules.ai.domain.AiRunStatus;
import com.fitnesscoaching.platform.modules.coaching.application.port.in.CoachingAuthorityQuery;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityDecision;
import com.fitnesscoaching.platform.modules.coaching.domain.CoachingSharing.AuthorityRequest;
import com.fitnesscoaching.platform.modules.coaching.domain.DataAccessLevel;
import com.fitnesscoaching.platform.modules.coaching.domain.DataScope;
import com.fitnesscoaching.platform.modules.progress.application.port.in.ProgressEngineQuery;
import com.fitnesscoaching.platform.modules.workout.application.model.WorkoutPlanViews.PlanDetail;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutPlanQueryUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleCalendarQuery;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Server-authorized gateway for internal agent tool calls.
 * Enforces run binding, actor/subject/coaching-authority authorization on every call,
 * and non-authoritative draft-only proposals.
 */
@Service
public class AgentToolGatewayService {

    private final AiRunRepository aiRunRepository;
    private final ObjectProvider<AiRunF01UseCase> aiRunF01UseCaseProvider;
    private final ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider;
    private final ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider;
    private final ObjectProvider<ProgressEngineQuery> progressEngineQueryProvider;
    private final ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider;
    private final ObjectProvider<AiWorkoutAdjustmentUseCase> workoutAdjustmentUseCaseProvider;
    private final ObjectProvider<AiWorkoutRescheduleUseCase> workoutRescheduleUseCaseProvider;
    private final Clock clock;
    private final boolean proposalsEnabled;

    public AgentToolGatewayService(
            AiRunRepository aiRunRepository,
            ObjectProvider<AiRunF01UseCase> aiRunF01UseCaseProvider,
            ObjectProvider<CoachingAuthorityQuery> coachingAuthorityQueryProvider,
            ObjectProvider<WorkoutPlanQueryUseCase> workoutPlanQueryProvider,
            ObjectProvider<ProgressEngineQuery> progressEngineQueryProvider,
            ObjectProvider<WorkoutScheduleCalendarQuery> scheduleCalendarProvider,
            ObjectProvider<AiWorkoutAdjustmentUseCase> workoutAdjustmentUseCaseProvider,
            ObjectProvider<AiWorkoutRescheduleUseCase> workoutRescheduleUseCaseProvider,
            Clock clock,
            @Value("${ai.agent.proposals-enabled:false}") boolean proposalsEnabled
    ) {
        this.aiRunRepository = aiRunRepository;
        this.aiRunF01UseCaseProvider = aiRunF01UseCaseProvider;
        this.coachingAuthorityQueryProvider = coachingAuthorityQueryProvider;
        this.workoutPlanQueryProvider = workoutPlanQueryProvider;
        this.progressEngineQueryProvider = progressEngineQueryProvider;
        this.scheduleCalendarProvider = scheduleCalendarProvider;
        this.workoutAdjustmentUseCaseProvider = workoutAdjustmentUseCaseProvider;
        this.workoutRescheduleUseCaseProvider = workoutRescheduleUseCaseProvider;
        this.clock = clock != null ? clock : Clock.systemUTC();
        this.proposalsEnabled = proposalsEnabled;
    }

    public record ToolCallInput(
            UUID runId,
            UUID actorId,
            String actorRole,
            UUID subjectId,
            Map<String, Object> arguments
    ) {}

    public record ProposalDraftOutput(
            String proposalType,
            UUID recommendationId,
            String status,
            String summary,
            Map<String, Object> payload
    ) {}

    public record ToolCallOutput(
            String status,
            String summary,
            Map<String, Object> data,
            List<String> sourceRefs,
            ProposalDraftOutput proposalDraft
    ) {}

    public ToolCallOutput getCurrentPlan(ToolCallInput input) {
        UUID subjectId = verifyRunAndResolveSubject(input);
        WorkoutPlanQueryUseCase query = workoutPlanQueryProvider.getIfAvailable();
        if (query == null) {
            return new ToolCallOutput("UNKNOWN_DATA", "Workout plan query unavailable.", Map.of(), List.of(), null);
        }
        Optional<PlanDetail> active = query.findActive(input.actorId(), subjectId);
        if (active.isEmpty()) {
            return new ToolCallOutput("NOT_FOUND", "No active workout plan found.", Map.of("activePlan", false), List.of(), null);
        }
        PlanDetail plan = active.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("planId", plan.plan().id().toString());
        data.put("title", plan.plan().name());
        data.put("status", plan.plan().status().name());
        if (plan.currentVersion() != null) {
            data.put("planVersionNumber", plan.currentVersion().versionNumber());
        }
        return new ToolCallOutput(
                "OK",
                "Active plan: " + plan.plan().name() + " (" + plan.plan().status().name() + ")",
                data,
                List.of("workout_plan:" + plan.plan().id()),
                null
        );
    }

    public ToolCallOutput getProgressSummary(ToolCallInput input) {
        UUID subjectId = verifyRunAndResolveSubject(input);
        ProgressEngineQuery query = progressEngineQueryProvider.getIfAvailable();
        if (query == null) {
            return new ToolCallOutput("UNKNOWN_DATA", "Progress engine unavailable.", Map.of(), List.of(), null);
        }
        int windowDays = extractInt(input.arguments(), "windowDays", 14, 1, 90);
        Instant now = Instant.now(clock);
        LocalDate toDate = LocalDate.ofInstant(now, ZoneOffset.UTC);
        LocalDate fromDate = toDate.minusDays(windowDays);

        ProgressEngineQuery.ProgressSnapshot snapshot = query.query(
                input.actorId(), subjectId, fromDate, toDate, ZoneOffset.UTC, now
        );
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("fromDate", fromDate.toString());
        data.put("toDate", toDate.toString());
        data.put("sourceWatermark", snapshot.compositeWatermark());
        data.put("continuityAvailable", snapshot.continuity() != null);
        if (snapshot.continuity() != null) {
            data.put("daysSinceLastWorkout", snapshot.continuity().daysSinceLastWorkout());
            data.put("continuityState", snapshot.continuity().state().name());
        }
        return new ToolCallOutput(
                "OK",
                "Progress window " + fromDate + " to " + toDate + " retrieved.",
                data,
                List.of("progress:" + snapshot.compositeWatermark()),
                null
        );
    }

    public ToolCallOutput getAvailability(ToolCallInput input) {
        UUID subjectId = verifyRunAndResolveSubject(input);
        WorkoutScheduleCalendarQuery query = scheduleCalendarProvider.getIfAvailable();
        if (query == null) {
            return new ToolCallOutput("UNKNOWN_DATA", "Schedule calendar query unavailable.", Map.of(), List.of(), null);
        }
        int days = extractInt(input.arguments(), "days", 7, 1, 30);
        Instant from = Instant.now(clock);
        Instant until = from.plus(days, ChronoUnit.DAYS);

        WorkoutScheduleCalendarQuery.Page page = input.actorId().equals(subjectId)
                ? query.listMine(input.actorId(), from, until, 0, 20)
                : query.listForTrainer(input.actorId(), subjectId, from, until, 0, 20);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("from", from.toString());
        data.put("until", until.toString());
        data.put("scheduledCount", page.items().size());
        return new ToolCallOutput(
                "OK",
                "Found " + page.items().size() + " scheduled occurrences in the next " + days + " days.",
                data,
                List.of("schedule_window:" + from + ".." + until),
                null
        );
    }

    public ToolCallOutput prepareWorkoutAdjustment(ToolCallInput input) {
        if (!proposalsEnabled) {
            return new ToolCallOutput("DENIED", "Proposal tools are disabled by configuration.", Map.of(), List.of(), null);
        }
        UUID subjectId = verifyRunAndResolveSubject(input);
        AiWorkoutAdjustmentUseCase useCase = workoutAdjustmentUseCaseProvider.getIfAvailable();
        if (useCase == null) {
            return new ToolCallOutput("ERROR", "Workout adjustment service unavailable.", Map.of(), List.of(), null);
        }

        String scopeRaw = stringArg(input.arguments(), "scope");
        String reason = stringArg(input.arguments(), "reason");
        if (reason == null) {
            return new ToolCallOutput("ERROR", "reason is required.", Map.of(), List.of(), null);
        }

        TargetRef targetRef;
        try {
            TargetScope scope = scopeRaw != null
                    ? TargetScope.valueOf(scopeRaw)
                    : TargetScope.TEMPLATE_OR_FUTURE_OCCURRENCES;

            if (scope == TargetScope.ONE_MATERIALIZED_OCCURRENCE) {
                UUID plannedWorkoutId = uuidArg(input.arguments(), "plannedWorkoutId");
                UUID plannedSessionExerciseId = uuidArg(input.arguments(), "plannedSessionExerciseId");
                if (plannedWorkoutId == null || plannedSessionExerciseId == null) {
                    return new ToolCallOutput(
                            "ERROR",
                            "ONE_MATERIALIZED_OCCURRENCE requires plannedWorkoutId and plannedSessionExerciseId.",
                            Map.of(), List.of(), null
                    );
                }
                targetRef = new TargetRef(scope, plannedWorkoutId, plannedSessionExerciseId, null, null, null, null);
            } else {
                UUID planId = uuidArg(input.arguments(), "targetPlanId");
                Integer planVersionNumber = nullableIntArg(input.arguments(), "planVersionNumber");
                UUID prescriptionId = uuidArg(input.arguments(), "exercisePrescriptionId");
                if (planId == null || planVersionNumber == null || prescriptionId == null) {
                    return new ToolCallOutput(
                            "ERROR",
                            "TEMPLATE_OR_FUTURE_OCCURRENCES requires targetPlanId, planVersionNumber, and exercisePrescriptionId.",
                            Map.of(), List.of(), null
                    );
                }
                targetRef = new TargetRef(scope, null, null, planId, planVersionNumber, null, prescriptionId);
            }
        } catch (IllegalArgumentException ex) {
            return new ToolCallOutput("ERROR", "Invalid F03 TargetRef arguments: " + ex.getMessage(), Map.of(), List.of(), null);
        }

        WorkoutAdjustmentApiRequest req = new WorkoutAdjustmentApiRequest(
                subjectId,
                "agent-f03-" + input.runId(),
                targetRef.scope(),
                targetRef,
                ChangeType.EXERCISE_SUBSTITUTION,
                reason + ": " + String.valueOf(input.arguments().getOrDefault("requestedChange", "Exercise substitution")),
                List.of(),
                List.of(),
                Boolean.FALSE
        );
        WorkoutAdjustmentApiResponse res = useCase.generateAdjustment(input.actorId(), req);
        String statusStr = res.status() != null ? res.status().name() : "PENDING";
        ProposalDraftOutput draft = new ProposalDraftOutput(
                "F03_WORKOUT_ADJUSTMENT",
                res.recommendationId(),
                statusStr,
                "Bản nháp đề xuất điều chỉnh giáo án đã được tạo (" + statusStr + "), chưa áp dụng và đang chờ người có thẩm quyền xác nhận.",
                Map.of("status", statusStr)
        );
        return new ToolCallOutput(
                "OK",
                draft.summary(),
                Map.of("recommendationId", String.valueOf(res.recommendationId()), "status", statusStr),
                List.of("f03_recommendation:" + res.recommendationId()),
                draft
        );
    }

    public ToolCallOutput prepareReschedule(ToolCallInput input) {
        if (!proposalsEnabled) {
            return new ToolCallOutput("DENIED", "Proposal tools are disabled by configuration.", Map.of(), List.of(), null);
        }
        UUID subjectId = verifyRunAndResolveSubject(input);
        AiWorkoutRescheduleUseCase useCase = workoutRescheduleUseCaseProvider.getIfAvailable();
        if (useCase == null) {
            return new ToolCallOutput("ERROR", "Workout reschedule service unavailable.", Map.of(), List.of(), null);
        }
        UUID occurrenceId = uuidArg(input.arguments(), "occurrenceId");
        String reason = stringArg(input.arguments(), "reason");
        if (occurrenceId == null || reason == null) {
            return new ToolCallOutput("ERROR", "Valid occurrenceId and reason are required.", Map.of(), List.of(), null);
        }
        WorkoutRescheduleApiRequest req = new WorkoutRescheduleApiRequest(
                subjectId,
                occurrenceId,
                null,
                "agent-f05-" + input.runId(),
                reason,
                null
        );
        WorkoutRescheduleApiResponse res = useCase.generateReschedule(input.actorId(), req);
        String statusStr = res.status() != null ? res.status().name() : "PENDING";
        ProposalDraftOutput draft = new ProposalDraftOutput(
                "F05_WORKOUT_RESCHEDULE",
                res.recommendationId(),
                statusStr,
                "Bản nháp đề xuất xếp lại lịch tập đã được tạo (" + statusStr + "), chưa áp dụng và đang chờ người có thẩm quyền xác nhận.",
                Map.of("occurrenceId", occurrenceId.toString(), "status", statusStr)
        );
        return new ToolCallOutput(
                "OK",
                draft.summary(),
                Map.of("recommendationId", String.valueOf(res.recommendationId()), "status", statusStr),
                List.of("f05_recommendation:" + res.recommendationId()),
                draft
        );
    }

    public ToolCallOutput authorizeKnowledgeSearch(ToolCallInput input) {
        verifyRunAndResolveSubject(input);
        return new ToolCallOutput("OK", "Current run authorization verified.", Map.of(), List.of(), null);
    }

    private UUID verifyRunAndResolveSubject(ToolCallInput input) {
        if (input == null || input.runId() == null || input.actorId() == null) {
            throw new AiRunAccessDeniedException("runId and actorId are required.");
        }
        AiRun run = aiRunRepository.findById(input.runId())
                .orElseThrow(() -> new AiRunAccessDeniedException("Unknown AI run binding."));
        if (run.status() == AiRunStatus.CANCELLED || run.isTerminal()) {
            throw new AiRunAccessDeniedException("AI run is already terminal or cancelled.");
        }
        if (run.requestedBy() == null || !run.requestedBy().equals(input.actorId())) {
            throw new AiRunAccessDeniedException("Actor does not match bound AI run.");
        }

        UUID resolvedSubject = input.subjectId() != null ? input.subjectId() : input.actorId();
        if (run.studentId() != null && !run.studentId().equals(resolvedSubject)) {
            throw new AiRunAccessDeniedException("Subject does not match bound AI run.");
        }

        AiRunF01UseCase aiRunUseCase = aiRunF01UseCaseProvider.getIfAvailable();
        if (aiRunUseCase != null) {
            aiRunUseCase.revalidateActorAuthorization(input.actorId());
        }

        if (!input.actorId().equals(resolvedSubject)) {
            CoachingAuthorityQuery authorityQuery = coachingAuthorityQueryProvider.getIfAvailable();
            if (authorityQuery == null) {
                throw new AiRunAccessDeniedException("Coaching authority verification unavailable.");
            }
            AuthorityDecision decision = authorityQuery.evaluateCurrent(
                    new AuthorityRequest(input.actorId(), resolvedSubject, DataScope.WORKOUT_HISTORY, DataAccessLevel.VIEW)
            );
            if (decision == null || !decision.allowed()) {
                throw new AiRunAccessDeniedException("Trainer lacks active authority for target student.");
            }
        }

        return resolvedSubject;
    }

    private static int extractInt(Map<String, Object> args, String key, int def, int min, int max) {
        if (args == null || !args.containsKey(key) || args.get(key) == null) {
            return def;
        }
        Object val = args.get(key);
        int parsed = val instanceof Number n ? n.intValue() : Integer.parseInt(val.toString());
        return Math.min(Math.max(parsed, min), max);
    }

    private static Integer nullableIntArg(Map<String, Object> args, String key) {
        if (args == null || args.get(key) == null) {
            return null;
        }
        Object val = args.get(key);
        return val instanceof Number n ? n.intValue() : Integer.parseInt(val.toString());
    }

    private static UUID uuidArg(Map<String, Object> args, String key) {
        String s = stringArg(args, key);
        if (s == null) {
            return null;
        }
        return UUID.fromString(s);
    }

    private static String stringArg(Map<String, Object> args, String key) {
        if (args == null || args.get(key) == null) {
            return null;
        }
        String s = args.get(key).toString().trim();
        return s.isEmpty() ? null : s;
    }
}
