package com.fitnesscoaching.platform.modules.goal.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.goal.domain.FitnessGoalVersion;
import com.fitnesscoaching.platform.modules.goal.domain.VersionLockReason;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record GoalVersionResponse(
        UUID id,
        int versionNumber,
        LocalDate startDate,
        LocalDate targetDate,
        Integer durationDays,
        Instant effectiveFrom,
        Instant effectiveUntil,
        LocalDate resumeDate,
        String changeReason,
        String changeSummary,
        UUID createdBy,
        UUID sourceProposalId,
        Instant createdAt,
        Instant lockedAt,
        UUID lockedBy,
        VersionLockReason lockReason,
        List<GoalObjectiveResponse> objectives,
        List<GoalTargetResponse> targets
) {
    public static GoalVersionResponse fromDomain(FitnessGoalVersion version) {
        if (version == null) {
            return null;
        }
        return new GoalVersionResponse(
                version.id(),
                version.versionNumber(),
                version.startDate(),
                version.targetDate(),
                version.durationDays(),
                version.effectiveFrom(),
                version.effectiveUntil(),
                version.resumeDate(),
                version.changeReason(),
                version.changeSummary(),
                version.createdBy(),
                version.sourceProposalId(),
                version.createdAt(),
                version.lockedAt(),
                version.lockedBy(),
                version.lockReason(),
                version.objectives() != null ? version.objectives().stream().map(GoalObjectiveResponse::fromDomain).toList() : List.of(),
                version.targets() != null ? version.targets().stream().map(GoalTargetResponse::fromDomain).toList() : List.of()
        );
    }
}
