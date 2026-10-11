package com.fitnesscoaching.platform.modules.schedule.application.service;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleProposalUseCase.Proposal;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleBatchUseCase.ConfirmedBatch;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleProposalSourceQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleProposalSourceQuery.Source;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adds current display data only after the proposal use case has authorized the read. */
@Service
public class ScheduleProposalPresentationService {
    private final UserDirectoryQuery users;
    private final WorkoutScheduleProposalSourceQuery sources;

    public ScheduleProposalPresentationService(UserDirectoryQuery users,
                                               WorkoutScheduleProposalSourceQuery sources) {
        this.users = users;
        this.sources = sources;
    }

    public ProposalView present(Proposal proposal) {
        return presentAll(List.of(proposal)).getFirst();
    }

    public PageView present(ScheduleProposalUseCase.Page page) {
        return new PageView(presentAll(page.items()), page.page(), page.size(),
                page.totalItems(), page.totalPages());
    }

    public CommandView present(ScheduleProposalUseCase.CommandResult result) {
        return new CommandView(present(result.proposal()), result.replayed());
    }

    public DecisionView present(ScheduleProposalUseCase.DecisionResult result) {
        return new DecisionView(present(result.proposal()), result.confirmedBatch(), result.replayed());
    }

    private List<ProposalView> presentAll(List<Proposal> proposals) {
        if (proposals.isEmpty()) return List.of();
        Map<UUID, UserDisplaySummary> names = users.findDisplaySummaries(
                proposals.stream().map(Proposal::trainerId).distinct().toList());
        Map<UUID, Source> versions = sources.findByVersionIds(
                proposals.stream().map(Proposal::sourcePlanVersionId).distinct().toList());
        return proposals.stream().map(p -> {
            Source source = versions.get(p.sourcePlanVersionId());
            if (source != null && !p.planId().equals(source.planId())) source = null;
            String planName = source == null ? null : source.planName();
            Source matchingSource = source;
            List<ItemView> items = p.items().stream().map(item -> {
                var session = matchingSource == null ? null : matchingSource.sessions().get(item.planSessionId());
                return new ItemView(item.clientItemId(), item.planSessionId(), item.weekNumber(),
                        item.dayNumber(), item.sequenceNumber(), item.localStart(), item.localEnd(),
                        item.startUtcOffset(), item.endUtcOffset(),
                        session == null ? null : session.name(), session == null ? null : session.focus());
            }).toList();
            UserDisplaySummary trainer = names.get(p.trainerId());
            return new ProposalView(p.proposalId(), p.proposalVersion(), p.status(), p.studentId(),
                    p.trainerId(), trainer == null ? null : trainer.displayName(), p.coachingPeriodId(),
                    p.planId(), planName, p.sourcePlanVersionId(), p.sourcePlanVersionNumber(),
                    p.observedPlanAggregateVersion(), p.weekAnchorDate(), p.timezone(), items,
                    p.proposedAt(), p.decidedAt(), p.decidedBy(), p.decisionNote(),
                    p.withdrawnAt(), p.withdrawnBy());
        }).toList();
    }

    public record ItemView(UUID clientItemId, UUID planSessionId, int weekNumber, int dayNumber,
                           int sequenceNumber, LocalDateTime localStart, LocalDateTime localEnd,
                           String startUtcOffset, String endUtcOffset,
                           String sourceSessionName, String sourceSessionFocus) {}

    public record ProposalView(UUID proposalId, long proposalVersion, String status,
                               UUID studentId, UUID trainerId, String trainerDisplayName,
                               UUID coachingPeriodId, UUID planId, String sourcePlanName,
                               UUID sourcePlanVersionId, int sourcePlanVersionNumber,
                               long observedPlanAggregateVersion, LocalDate weekAnchorDate,
                               String timezone, List<ItemView> items, Instant proposedAt,
                               Instant decidedAt, UUID decidedBy, String decisionNote,
                               Instant withdrawnAt, UUID withdrawnBy) {}

    public record PageView(List<ProposalView> items, int page, int size,
                           long totalItems, int totalPages) {}
    public record CommandView(ProposalView proposal, boolean replayed) {}
    public record DecisionView(ProposalView proposal, ConfirmedBatch confirmedBatch, boolean replayed) {}
}
