package com.fitnesscoaching.platform.modules.goal.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record FitnessGoalVersion(
        UUID id,
        UUID fitnessGoalId,
        int versionNumber,
        String title,
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
        List<GoalObjective> objectives,
        List<GoalTarget> targets
) {
    public FitnessGoalVersion {
        objectives = objectives != null ? List.copyOf(objectives) : List.of();
        targets = targets != null ? List.copyOf(targets) : List.of();
    }

    public FitnessGoalVersion(
            UUID id,
            UUID fitnessGoalId,
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
            List<GoalObjective> objectives,
            List<GoalTarget> targets
    ) {
        this(
                id,
                fitnessGoalId,
                versionNumber,
                null,
                startDate,
                targetDate,
                durationDays,
                effectiveFrom,
                effectiveUntil,
                resumeDate,
                changeReason,
                changeSummary,
                createdBy,
                sourceProposalId,
                createdAt,
                lockedAt,
                lockedBy,
                lockReason,
                objectives,
                targets
        );
    }
}
