package com.fitnesscoaching.platform.modules.goal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GoalProposal(
        UUID id,
        UUID studentId,
        UUID fitnessGoalId,
        UUID baseGoalVersionId,
        ProposalSource source,
        UUID createdBy,
        String proposedTitle,
        LocalDate proposedStartDate,
        LocalDate proposedTargetDate,
        Integer proposedDurationDays,
        String reason,
        ProposalStatus status,
        UUID decidedBy,
        Instant decidedAt,
        String decisionNote,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt,
        List<GoalProposalObjective> objectives,
        List<GoalProposalTarget> targets,
        FitnessGoalVersion baseVersion
) {
    public GoalProposal {
        objectives = objectives != null ? List.copyOf(objectives) : List.of();
        targets = targets != null ? List.copyOf(targets) : List.of();
    }
}
