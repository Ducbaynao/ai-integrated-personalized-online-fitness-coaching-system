package com.fitnesscoaching.platform.modules.trainer.domain;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record TrainerApplication(
        UUID id,
        UUID trainerId,
        TrainerVerificationStatus status,
        Instant submittedAt,
        Instant reviewedAt,
        UUID reviewedBy,
        String rejectionReason,
        String reviewNotes,
        String applicantNote,
        List<UUID> certificateIds,
        List<UUID> documentMediaIds,
        Instant createdAt,
        Instant updatedAt
) {
    public TrainerApplication {
        certificateIds = certificateIds != null ? List.copyOf(certificateIds) : List.of();
        documentMediaIds = documentMediaIds != null ? List.copyOf(documentMediaIds) : List.of();
    }

    public TrainerApplication(
            UUID id,
            UUID trainerId,
            TrainerVerificationStatus status,
            Instant submittedAt,
            Instant reviewedAt,
            UUID reviewedBy,
            String rejectionReason,
            String applicantNote,
            List<UUID> certificateIds,
            List<UUID> documentMediaIds,
            Instant createdAt,
            Instant updatedAt
    ) {
        this(id, trainerId, status, submittedAt, reviewedAt, reviewedBy, rejectionReason, null, applicantNote, certificateIds, documentMediaIds, createdAt, updatedAt);
    }
}
