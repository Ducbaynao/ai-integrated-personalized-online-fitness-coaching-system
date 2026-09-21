package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;

import java.util.UUID;

public interface GetTrainerProfileUseCase {

    TrainerProfile getTrainerProfile(UUID userId);
}
