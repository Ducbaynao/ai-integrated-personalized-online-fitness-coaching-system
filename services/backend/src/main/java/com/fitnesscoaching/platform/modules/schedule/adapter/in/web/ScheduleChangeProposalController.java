package com.fitnesscoaching.platform.modules.schedule.adapter.in.web;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.service.ScheduleChangeProposalPresentationService;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.LocalDateTime;
import java.util.UUID;

@RestController
public class ScheduleChangeProposalController {
    private final ScheduleChangeProposalUseCase proposals;
    private final ScheduleChangeProposalPresentationService presentation;

    public ScheduleChangeProposalController(ScheduleChangeProposalUseCase proposals,
                                            ScheduleChangeProposalPresentationService presentation) {
        this.proposals = proposals;
        this.presentation = presentation;
    }

    @PostMapping("/api/v1/students/{studentId}/planned-workout-change-proposals")
    public ResponseEntity<ScheduleChangeProposalPresentationService.CommandView> create(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId,
            @Valid @RequestBody CreateRequest request) {
        var result = proposals.create(new ScheduleChangeProposalUseCase.Create(actor(jwt), studentId,
                request.occurrenceId(), value(request.expectedOccurrenceVersion()), request.changeType(),
                request.destination() == null ? null : request.destination().toCommand(),
                request.reason(), request.commandKey()));
        return ResponseEntity.created(URI.create("/api/v1/planned-workout-change-proposals/"
                + result.proposal().proposalId())).body(presentation.present(result));
    }

    @GetMapping("/api/v1/students/{studentId}/planned-workout-change-proposals")
    public ScheduleChangeProposalPresentationService.PageView listForTrainer(
            @AuthenticationPrincipal Jwt jwt, @PathVariable UUID studentId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return presentation.present(proposals.listForTrainer(actor(jwt), studentId, status, page, size));
    }

    @GetMapping("/api/v1/planned-workout-change-proposals/me")
    public ScheduleChangeProposalPresentationService.PageView listMine(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return presentation.present(proposals.listMine(actor(jwt), status, page, size));
    }

    @GetMapping("/api/v1/planned-workout-change-proposals/{proposalId}")
    public ScheduleChangeProposalPresentationService.ProposalView detail(@AuthenticationPrincipal Jwt jwt,
                                                                          @PathVariable UUID proposalId) {
        return presentation.present(proposals.detail(actor(jwt), proposalId));
    }

    @PostMapping("/api/v1/planned-workout-change-proposals/{proposalId}/withdraw")
    public ScheduleChangeProposalPresentationService.CommandView withdraw(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId, @Valid @RequestBody WithdrawRequest request) {
        return presentation.present(proposals.withdraw(new ScheduleChangeProposalUseCase.Withdraw(
                actor(jwt), proposalId, value(request.expectedProposalVersion()), request.commandKey())));
    }

    @PostMapping("/api/v1/planned-workout-change-proposals/{proposalId}/decisions")
    public ScheduleChangeProposalPresentationService.DecisionView decide(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID proposalId, @Valid @RequestBody DecideRequest request) {
        return presentation.present(proposals.decide(new ScheduleChangeProposalUseCase.Decide(
                actor(jwt), proposalId, value(request.expectedProposalVersion()), request.decision(),
                request.decisionReason(), request.commandKey())));
    }

    private static UUID actor(Jwt jwt) { return UUID.fromString(jwt.getSubject()); }
    private static long value(Long version) { return version == null ? -1 : version; }

    public record CreateRequest(@NotNull UUID occurrenceId, @NotNull Long expectedOccurrenceVersion,
                                @NotBlank String changeType, @Valid DestinationRequest destination,
                                @Size(max = 1000) String reason, @NotBlank String commandKey) {}
    public record DestinationRequest(@NotNull LocalDateTime localStart, @NotNull LocalDateTime localEnd,
                                     @NotBlank String timezone, @NotBlank String startUtcOffset,
                                     @NotBlank String endUtcOffset) {
        Destination toCommand() {
            return new Destination(localStart, localEnd, timezone, startUtcOffset, endUtcOffset);
        }
    }
    public record WithdrawRequest(@NotNull Long expectedProposalVersion, @NotBlank String commandKey) {}
    public record DecideRequest(@NotNull Long expectedProposalVersion, @NotBlank String decision,
                                @Size(max = 1000) String decisionReason, @NotBlank String commandKey) {}
}
