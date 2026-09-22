package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.util.UUID;

public record GetAdminTrainerApplicationsQuery(
        UUID adminUserId,
        TrainerVerificationStatus status,
        int page,
        int size
) {
}
