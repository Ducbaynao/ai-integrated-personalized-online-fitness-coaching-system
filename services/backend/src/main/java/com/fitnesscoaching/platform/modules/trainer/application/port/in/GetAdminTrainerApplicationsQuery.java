package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.util.UUID;

public record GetAdminTrainerApplicationsQuery(
        UUID adminUserId,
        TrainerVerificationStatus status,
        boolean allStatuses,
        int page,
        int size
) {
    public GetAdminTrainerApplicationsQuery(
            UUID adminUserId,
            TrainerVerificationStatus status,
            int page,
            int size
    ) {
        this(adminUserId, status, false, page, size);
    }
}
