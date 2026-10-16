package com.fitnesscoaching.platform.modules.nutrition;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class NutritionStrategyController {
    private final NutritionStrategyService strategy;
    private final NutritionTargetDayService days;

    public NutritionStrategyController(NutritionStrategyService strategy, NutritionTargetDayService days) {
        this.strategy = strategy; this.days = days;
    }

    @GetMapping("/nutrition/strategy/goal-types")
    public List<NutritionStrategyService.GoalType> goalTypes() { return strategy.goalTypes(); }

    @GetMapping("/nutrition/strategy/target-nutrients")
    public List<NutritionStrategyService.TargetNutrient> targetNutrients() { return strategy.targetNutrients(); }

    @GetMapping("/nutrition/strategy/me")
    public NutritionStrategyService.State mine(@AuthenticationPrincipal Jwt jwt) {
        return strategy.mine(actor(jwt));
    }

    @PostMapping("/nutrition/strategy/goals")
    public NutritionStrategyService.Goal confirmChange(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangeRequest body) {
        return strategy.confirmChange(actor(jwt), body.toInput());
    }

    @PostMapping("/nutrition/strategy/goals/{goalId}/transitions")
    public NutritionStrategyService.Goal transition(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID goalId, @Valid @RequestBody TransitionRequest body) {
        return strategy.transition(actor(jwt), goalId, new NutritionStrategyService.TransitionInput(
                body.action(), body.expectedGoalVersion(), body.reason(), body.commandKey()));
    }

    @GetMapping("/nutrition/strategy/days/{day}")
    public NutritionTargetDayService.TargetDay preview(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @RequestParam String timezone) {
        return days.preview(actor(jwt), day, timezone);
    }

    @PostMapping("/nutrition/strategy/days/{day}/day-type")
    public NutritionTargetDayService.TargetDay chooseDayType(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @Valid @RequestBody DayTypeRequest body) {
        return days.chooseDayType(actor(jwt), day,
                new NutritionTargetDayService.DayTypeInput(body.timezone(), body.dayType(), body.commandKey()));
    }

    @PostMapping("/nutrition/strategy/days/{day}/overrides")
    public NutritionTargetDayService.TargetDay override(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @Valid @RequestBody OverrideRequest body) {
        return days.upsertOverride(actor(jwt), day, new NutritionTargetDayService.OverrideInput(
                body.timezone(), body.goalVersionId(), body.nutrientId(), body.unitId(),
                body.targetAmount(), body.reason(), body.expectedOverrideVersion(), body.commandKey()));
    }

    @PostMapping("/nutrition/strategy/days/{day}/frozen-target-corrections")
    public NutritionTargetDayService.TargetDay correctFrozenTarget(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day,
            @Valid @RequestBody FrozenCorrectionRequest body) {
        return days.correctFrozen(actor(jwt), day, new NutritionTargetDayService.FrozenCorrectionInput(
                body.timezone(), body.nutrientId(), body.targetAmount(), body.expectedVersion(),
                body.reason(), body.commandKey()));
    }

    @GetMapping("/nutrition/strategy/days/{day}/frozen-target-corrections")
    public List<NutritionTargetDayService.FrozenCorrection> correctionHistory(@AuthenticationPrincipal Jwt jwt,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return days.correctionHistory(actor(jwt), actor(jwt), day);
    }

    @GetMapping("/students/{studentId}/nutrition/strategy/days/{day}/frozen-target-corrections")
    public List<NutritionTargetDayService.FrozenCorrection> trainerCorrectionHistory(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        return days.correctionHistory(actor(jwt), studentId, day);
    }

    @GetMapping("/nutrition/strategy/proposals/me")
    public List<NutritionStrategyService.Proposal> myProposals(@AuthenticationPrincipal Jwt jwt) {
        return strategy.proposalsMine(actor(jwt));
    }

    @GetMapping("/nutrition/strategy/proposals/{proposalId}")
    public NutritionStrategyService.Proposal proposal(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId) {
        return strategy.proposalDetail(actor(jwt), proposalId);
    }

    @PostMapping("/nutrition/strategy/proposals/{proposalId}/decisions")
    public NutritionStrategyService.Proposal decide(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId, @Valid @RequestBody DecisionRequest body) {
        return strategy.decide(actor(jwt), proposalId, new NutritionStrategyService.DecisionInput(
                body.decision(), body.expectedProposalVersion(), body.commandKey()));
    }

    @GetMapping("/students/{studentId}/nutrition/strategy")
    public NutritionStrategyService.State trainer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId) {
        return strategy.forTrainer(actor(jwt), studentId);
    }

    @GetMapping("/students/{studentId}/nutrition/strategy/proposals")
    public List<NutritionStrategyService.Proposal> trainerProposals(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId) {
        return strategy.proposalsForTrainer(actor(jwt), studentId);
    }

    @PostMapping("/students/{studentId}/nutrition/strategy/proposals")
    public ResponseEntity<NutritionStrategyService.Proposal> propose(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId, @Valid @RequestBody ChangeRequest body) {
        return ResponseEntity.status(201).body(strategy.propose(actor(jwt), studentId,
                body.toInput(), body.reason()));
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }

    public record ChangeRequest(@NotBlank String kind, UUID goalId, Long expectedGoalVersion,
            UUID baseVersionId, @NotBlank String title, @NotNull Integer goalTypeId,
            @NotBlank String timezone, @NotNull LocalDate effectiveFrom,
            @NotNull List<NutritionStrategyService.TargetSpec> targets,
            String reason, @NotNull Boolean confirmed, @NotBlank String commandKey) {
        NutritionStrategyService.ChangeInput toInput() {
            return new NutritionStrategyService.ChangeInput(kind, goalId, expectedGoalVersion,
                    baseVersionId, title, goalTypeId, timezone, effectiveFrom, targets, confirmed, commandKey);
        }
    }
    public record TransitionRequest(@NotBlank String action, @NotNull Long expectedGoalVersion,
                                    @NotBlank String reason, @NotBlank String commandKey) {}
    public record DayTypeRequest(@NotBlank String timezone, @NotBlank String dayType,
                                 @NotBlank String commandKey) {}
    public record OverrideRequest(@NotBlank String timezone, @NotNull UUID goalVersionId,
                                  @NotNull Integer nutrientId, @NotNull Integer unitId,
                                  @NotNull BigDecimal targetAmount, @NotBlank String reason,
                                  Long expectedOverrideVersion, @NotBlank String commandKey) {}
    public record FrozenCorrectionRequest(@NotBlank String timezone, @NotNull Integer nutrientId,
                                          @NotNull BigDecimal targetAmount, @NotNull Long expectedVersion,
                                          @NotBlank String reason, @NotBlank String commandKey) {}
    public record DecisionRequest(@NotBlank String decision, @NotNull Long expectedProposalVersion,
                                  @NotBlank String commandKey) {}
}
