package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.time.Instant;
import java.util.UUID;

public record TrainerApplicationResponse(
        UUID id,
        UUID trainerId,
        TrainerVerificationStatus status,
        Instant submittedAt,
        Instant reviewedAt,
        String rejectionReason,
        Instant updatedAt
) {
    public static TrainerApplicationResponse fromDomain(TrainerApplication app) {
        if (app == null) {
            return null;
        }
        return new TrainerApplicationResponse(
                app.id(),
                app.trainerId(),
                app.status(),
                app.submittedAt(),
                app.reviewedAt(),
                app.rejectionReason(),
                app.updatedAt()
        );
    }
}
