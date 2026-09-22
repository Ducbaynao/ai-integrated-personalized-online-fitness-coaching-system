package com.fitnesscoaching.platform.modules.trainer.application.port.out;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TrainerApplicationPort {

    boolean hasActiveApplication(UUID trainerId);

    Optional<TrainerApplication> findCurrentByTrainerId(UUID trainerId);

    TrainerApplication save(TrainerApplication application);

    Optional<TrainerApplication> findById(UUID id);

    Optional<TrainerApplication> findByIdForUpdate(UUID id);

    List<TrainerApplication> findApplications(TrainerVerificationStatus status, long offset, int limit);

    long countApplications(TrainerVerificationStatus status);

    int updateDecision(
            UUID applicationId,
            TrainerVerificationStatus newStatus,
            Instant reviewedAt,
            UUID reviewedBy,
            String rejectionReason,
            String reviewNotes,
            Instant updatedAt
    );

    void recordStatusHistory(
            UUID applicationId,
            TrainerVerificationStatus fromStatus,
            TrainerVerificationStatus toStatus,
            UUID changedBy,
            String reason,
            Instant changedAt
    );
}
