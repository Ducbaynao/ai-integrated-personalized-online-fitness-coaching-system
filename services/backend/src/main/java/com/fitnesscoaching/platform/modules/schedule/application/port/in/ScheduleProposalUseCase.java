package com.fitnesscoaching.platform.modules.schedule.application.port.in;

import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmedBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.Item;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ScheduleProposalUseCase {
    CommandResult create(Create command);
    Page listMine(UUID studentId, String status, int page, int size);
    Page listForTrainer(UUID trainerId, UUID studentId, String status, int page, int size);
    Proposal detail(UUID actorId, UUID proposalId);
    CommandResult withdraw(Withdraw command);
    DecisionResult decide(Decide command);

    record Create(UUID trainerId, UUID studentId, UUID planId, UUID sourcePlanVersionId,
                  long expectedPlanAggregateVersion, LocalDate weekAnchorDate, String timezone,
                  List<Item> items, String commandKey) {}
    record Withdraw(UUID trainerId, UUID proposalId, long expectedProposalVersion, String commandKey) {}
    record Decide(UUID studentId, UUID proposalId, long expectedProposalVersion, String decision,
                  String decisionNote, String commandKey) {}
    record ProposalItem(UUID clientItemId, UUID planSessionId, int weekNumber, int dayNumber,
                        int sequenceNumber, LocalDateTime localStart, LocalDateTime localEnd,
                        String startUtcOffset, String endUtcOffset,
                        UUID appointmentId, Long observedAppointmentVersion) {
        public ProposalItem(UUID clientItemId, UUID planSessionId, int weekNumber, int dayNumber,
                            int sequenceNumber, LocalDateTime localStart, LocalDateTime localEnd,
                            String startUtcOffset, String endUtcOffset) {
            this(clientItemId, planSessionId, weekNumber, dayNumber, sequenceNumber,
                    localStart, localEnd, startUtcOffset, endUtcOffset, null, null);
        }
        public Item asWorkoutItem() {
            return new Item(clientItemId, planSessionId, weekNumber, dayNumber, sequenceNumber,
                    localStart, localEnd, startUtcOffset, endUtcOffset,
                    appointmentId, observedAppointmentVersion);
        }
        public static ProposalItem fromWorkout(Item item, Long observedAppointmentVersion) {
            return new ProposalItem(item.clientItemId(), item.planSessionId(), item.weekNumber(),
                    item.dayNumber(), item.sequenceNumber(), item.localStart(), item.localEnd(),
                    item.startUtcOffset(), item.endUtcOffset(), item.appointmentId(),
                    observedAppointmentVersion);
        }
    }
    record Proposal(UUID proposalId, long proposalVersion, String status, UUID studentId, UUID trainerId,
                    UUID coachingPeriodId, UUID planId, UUID sourcePlanVersionId,
                    int sourcePlanVersionNumber, long observedPlanAggregateVersion,
                    LocalDate weekAnchorDate, String timezone, List<ProposalItem> items,
                    Instant proposedAt, Instant decidedAt, UUID decidedBy, String decisionNote,
                    Instant withdrawnAt, UUID withdrawnBy) {}
    record CommandResult(Proposal proposal, boolean replayed) {
        public CommandResult asReplay() { return new CommandResult(proposal, true); }
    }
    record DecisionResult(Proposal proposal, ConfirmedBatch confirmedBatch, boolean replayed) {
        public DecisionResult asReplay() {
            return new DecisionResult(proposal, confirmedBatch == null ? null : confirmedBatch.asReplay(), true);
        }
    }
    record Page(List<Proposal> items, int page, int size, long totalItems, int totalPages) {}
}
