package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.ChangeResult;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase.Destination;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ScheduleChangeProposalUseCase {
    CommandResult create(Create command);
    Page listMine(UUID studentId, String status, int page, int size);
    Page listForTrainer(UUID trainerId, UUID studentId, String status, int page, int size);
    Proposal detail(UUID actorId, UUID proposalId);
    CommandResult withdraw(Withdraw command);
    DecisionResult decide(Decide command);

    record Create(UUID trainerId, UUID studentId, UUID occurrenceId, long expectedOccurrenceVersion,
                  String changeType, Destination destination, String reason, String commandKey) {}
    record Withdraw(UUID trainerId, UUID proposalId, long expectedProposalVersion, String commandKey) {}
    record Decide(UUID studentId, UUID proposalId, long expectedProposalVersion, String decision,
                  String decisionReason, String commandKey) {}
    record Proposal(UUID proposalId, long proposalVersion, String status, String changeType,
                    UUID studentId, UUID trainerId, UUID coachingPeriodId, UUID occurrenceId,
                    long observedOccurrenceVersion, Instant oldPlannedStartAt, Instant oldPlannedEndAt,
                    String oldTimezone, LocalDateTime oldLocalStart, LocalDateTime oldLocalEnd,
                    String oldStartUtcOffset, String oldEndUtcOffset, Instant originalPlannedStartAt,
                    UUID sourcePlanId, UUID sourcePlanVersionId, UUID sourcePlanSessionId,
                    Destination destination, String reason, Instant proposedAt, Instant decidedAt,
                    UUID decidedBy, Instant withdrawnAt, UUID withdrawnBy) {}
    record CommandResult(Proposal proposal, boolean replayed) {
        public CommandResult asReplay() { return new CommandResult(proposal, true); }
    }
    record DecisionResult(Proposal proposal, ChangeResult appliedChange, boolean replayed) {
        public DecisionResult asReplay() {
            return new DecisionResult(proposal,
                    appliedChange == null ? null : appliedChange.asReplay(), true);
        }
    }
    record Page(List<Proposal> items, int page, int size, long totalItems, int totalPages) {}
}
