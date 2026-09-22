package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;

import java.util.UUID;

public interface GetAdminTrainerApplicationDetailUseCase {

    TrainerApplication getApplicationDetail(UUID adminUserId, UUID applicationId);
}
