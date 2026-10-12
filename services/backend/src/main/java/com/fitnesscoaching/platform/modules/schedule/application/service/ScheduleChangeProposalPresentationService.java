package com.fitnesscoaching.platform.modules.schedule.application.service;

import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase;
import com.fitnesscoaching.platform.modules.schedule.application.port.in.ScheduleChangeProposalUseCase.Proposal;
import com.fitnesscoaching.platform.modules.user.application.model.UserDisplaySummary;
import com.fitnesscoaching.platform.modules.user.application.port.in.UserDirectoryQuery;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleChangeUseCase;
import com.fitnesscoaching.platform.modules.workout.application.port.in.WorkoutScheduleProposalSourceQuery;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Adds current, nullable display names only after application-level authorization. */
@Service
public class ScheduleChangeProposalPresentationService {
    private final UserDirectoryQuery users;
    private final WorkoutScheduleProposalSourceQuery sources;

    public ScheduleChangeProposalPresentationService(UserDirectoryQuery users,
                                                     WorkoutScheduleProposalSourceQuery sources) {
        this.users = users;
        this.sources = sources;
    }

    public ProposalView present(Proposal proposal) { return presentAll(List.of(proposal)).getFirst(); }
    public PageView present(ScheduleChangeProposalUseCase.Page page) {
        return new PageView(presentAll(page.items()), page.page(), page.size(), page.totalItems(), page.totalPages());
    }
    public CommandView present(ScheduleChangeProposalUseCase.CommandResult result) {
        return new CommandView(present(result.proposal()), result.replayed());
    }
    public DecisionView present(ScheduleChangeProposalUseCase.DecisionResult result) {
        return new DecisionView(present(result.proposal()), result.appliedChange(), result.replayed());
    }

    private List<ProposalView> presentAll(List<Proposal> proposals) {
        if (proposals.isEmpty()) return List.of();
        Map<UUID, UserDisplaySummary> names = users.findDisplaySummaries(
                proposals.stream().map(Proposal::trainerId).distinct().toList());
        Map<UUID, WorkoutScheduleProposalSourceQuery.Source> versions = sources.findByVersionIds(
                proposals.stream().map(Proposal::sourcePlanVersionId).filter(id -> id != null).distinct().toList());
        return proposals.stream().map(p -> {
            var source = versions.get(p.sourcePlanVersionId());
            if (source != null && !source.planId().equals(p.sourcePlanId())) source = null;
            var session = source == null ? null : source.sessions().get(p.sourcePlanSessionId());
            var trainer = names.get(p.trainerId());
            return new ProposalView(p.proposalId(), p.proposalVersion(), p.status(), p.changeType(),
                    p.studentId(), p.trainerId(), trainer == null ? null : trainer.displayName(),
                    p.coachingPeriodId(), p.occurrenceId(), p.observedOccurrenceVersion(),
                    p.oldPlannedStartAt(), p.oldPlannedEndAt(), p.oldTimezone(), p.oldLocalStart(),
                    p.oldLocalEnd(), p.oldStartUtcOffset(), p.oldEndUtcOffset(), p.originalPlannedStartAt(),
                    p.sourcePlanId(), p.sourcePlanVersionId(), p.sourcePlanSessionId(),
                    source == null ? null : source.planName(), session == null ? null : session.name(),
                    p.destination(), p.reason(), p.proposedAt(), p.decidedAt(), p.decidedBy(),
                    p.withdrawnAt(), p.withdrawnBy());
        }).toList();
    }

    public record ProposalView(UUID proposalId, long proposalVersion, String status, String changeType,
                               UUID studentId, UUID trainerId, String trainerDisplayName, UUID coachingPeriodId,
                               UUID occurrenceId, long observedOccurrenceVersion, Instant oldPlannedStartAt,
                               Instant oldPlannedEndAt, String oldTimezone, LocalDateTime oldLocalStart,
                               LocalDateTime oldLocalEnd, String oldStartUtcOffset, String oldEndUtcOffset,
                               Instant originalPlannedStartAt, UUID sourcePlanId, UUID sourcePlanVersionId,
                               UUID sourcePlanSessionId, String sourcePlanName, String sourceSessionName,
                               WorkoutScheduleChangeUseCase.Destination destination, String reason,
                               Instant proposedAt, Instant decidedAt, UUID decidedBy, Instant withdrawnAt,
                               UUID withdrawnBy) {}
    public record PageView(List<ProposalView> items, int page, int size, long totalItems, int totalPages) {}
    public record CommandView(ProposalView proposal, boolean replayed) {}
    public record DecisionView(ProposalView proposal, WorkoutScheduleChangeUseCase.ChangeResult appliedChange,
                               boolean replayed) {}
}
