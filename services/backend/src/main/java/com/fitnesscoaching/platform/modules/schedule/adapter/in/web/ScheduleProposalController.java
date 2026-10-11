package com.fitnesscoaching.platform.modules.schedule.adapter.in.web;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.service.ScheduleProposalPresentationService;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
public class ScheduleProposalController {
    private final ScheduleProposalUseCase proposals;
    private final ScheduleProposalPresentationService presentation;
    public ScheduleProposalController(ScheduleProposalUseCase proposals,
                                      ScheduleProposalPresentationService presentation) {
        this.proposals = proposals;
        this.presentation = presentation;
    }

    @PostMapping("/api/v1/students/{studentId}/planned-workout-schedule-proposals")
    public ResponseEntity<ScheduleProposalPresentationService.CommandView> create(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId, @Valid @RequestBody CreateRequest request) {
        var result = proposals.create(new ScheduleProposalUseCase.Create(UUID.fromString(jwt.getSubject()),
                studentId, request.planId(), request.sourcePlanVersionId(),
                request.expectedPlanAggregateVersion() == null ? -1 : request.expectedPlanAggregateVersion(),
                request.weekAnchorDate(), request.timezone(),
                request.items().stream().map(ProposalItemInput::toCommand).toList(), request.commandKey()));
        return ResponseEntity.status(201).body(presentation.present(result));
    }

    @GetMapping("/api/v1/students/{studentId}/planned-workout-schedule-proposals")
    public ScheduleProposalPresentationService.PageView listForTrainer(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID studentId, @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return presentation.present(proposals.listForTrainer(
                UUID.fromString(jwt.getSubject()), studentId, status, page, size));
    }

    @GetMapping("/api/v1/planned-workout-schedule-proposals/me")
    public ScheduleProposalPresentationService.PageView listMine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return presentation.present(proposals.listMine(UUID.fromString(jwt.getSubject()), status, page, size));
    }

    @GetMapping("/api/v1/planned-workout-schedule-proposals/{proposalId}")
    public ScheduleProposalPresentationService.ProposalView detail(@AuthenticationPrincipal Jwt jwt,
                                                   @PathVariable UUID proposalId) {
        return presentation.present(proposals.detail(UUID.fromString(jwt.getSubject()), proposalId));
    }

    @PostMapping("/api/v1/planned-workout-schedule-proposals/{proposalId}/withdraw")
    public ScheduleProposalPresentationService.CommandView withdraw(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId, @Valid @RequestBody WithdrawRequest request) {
        return presentation.present(proposals.withdraw(new ScheduleProposalUseCase.Withdraw(
                UUID.fromString(jwt.getSubject()), proposalId,
                request.expectedProposalVersion() == null ? -1 : request.expectedProposalVersion(),
                request.commandKey())));
    }

    @PostMapping("/api/v1/planned-workout-schedule-proposals/{proposalId}/decisions")
    public ScheduleProposalPresentationService.DecisionView decide(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId, @Valid @RequestBody DecisionRequest request) {
        return presentation.present(proposals.decide(new ScheduleProposalUseCase.Decide(UUID.fromString(jwt.getSubject()), proposalId,
                request.expectedProposalVersion() == null ? -1 : request.expectedProposalVersion(),
                request.decision(), request.decisionNote(), request.commandKey())));
    }

    public record CreateRequest(@NotNull UUID planId, @NotNull UUID sourcePlanVersionId,
                                @NotNull Long expectedPlanAggregateVersion, @NotNull LocalDate weekAnchorDate,
                                @NotBlank String timezone, @NotEmpty List<@Valid ProposalItemInput> items,
                                @NotBlank String commandKey) {}
    public record ProposalItemInput(@NotNull UUID clientItemId, @NotNull UUID planSessionId,
                                    int weekNumber, int dayNumber, int sequenceNumber,
                                    @NotNull LocalDateTime localStart, @NotNull LocalDateTime localEnd,
                                    @NotBlank String startUtcOffset, @NotBlank String endUtcOffset) {
        Item toCommand() { return new Item(clientItemId, planSessionId, weekNumber, dayNumber, sequenceNumber,
                localStart, localEnd, startUtcOffset, endUtcOffset); }
    }
    public record WithdrawRequest(@NotNull Long expectedProposalVersion, @NotBlank String commandKey) {}
    public record DecisionRequest(@NotNull Long expectedProposalVersion, @NotBlank String decision,
                                  String decisionNote, @NotBlank String commandKey) {}
}
