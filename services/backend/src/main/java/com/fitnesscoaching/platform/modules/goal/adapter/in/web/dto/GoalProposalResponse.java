package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.GoalProposal;
import com.fitnesscoaching.platform.modules.goal.domain.ProposalSource;
import com.fitnesscoaching.platform.modules.goal.domain.ProposalStatus;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GoalProposalResponse(
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
        List<GoalProposalObjectiveResponse> objectives,
        List<GoalProposalTargetResponse> targets,
        GoalVersionResponse baseVersion
) {
    public static GoalProposalResponse fromDomain(GoalProposal domain) {
        return new GoalProposalResponse(
                domain.id(),
                domain.studentId(),
                domain.fitnessGoalId(),
                domain.baseGoalVersionId(),
                domain.source(),
                domain.createdBy(),
                domain.proposedTitle(),
                domain.proposedStartDate(),
                domain.proposedTargetDate(),
                domain.proposedDurationDays(),
                domain.reason(),
                domain.status(),
                domain.decidedBy(),
                domain.decidedAt(),
                domain.decisionNote(),
                domain.expiresAt(),
                domain.createdAt(),
                domain.updatedAt(),
                domain.objectives() != null
                        ? domain.objectives().stream().map(GoalProposalObjectiveResponse::fromDomain).toList()
                        : List.of(),
                domain.targets() != null
                        ? domain.targets().stream().map(GoalProposalTargetResponse::fromDomain).toList()
                        : List.of(),
                domain.baseVersion() != null
                        ? GoalVersionResponse.fromDomain(domain.baseVersion())
                        : null
        );
    }
}
