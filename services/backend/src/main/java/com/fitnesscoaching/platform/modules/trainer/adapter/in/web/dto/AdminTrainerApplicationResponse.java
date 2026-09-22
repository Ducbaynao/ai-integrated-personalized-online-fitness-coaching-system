package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record AdminTrainerApplicationResponse(
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
    public static AdminTrainerApplicationResponse fromDomain(TrainerApplication app) {
        if (app == null) {
            return null;
        }
        return new AdminTrainerApplicationResponse(
                app.id(),
                app.trainerId(),
                app.status(),
                app.submittedAt(),
                app.reviewedAt(),
                app.reviewedBy(),
                app.rejectionReason(),
                app.reviewNotes(),
                app.applicantNote(),
                app.certificateIds(),
                app.documentMediaIds(),
                app.createdAt(),
                app.updatedAt()
        );
    }
}
