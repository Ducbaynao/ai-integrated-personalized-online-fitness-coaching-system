package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerProfileView;

import java.util.UUID;

public interface GetTrainerProfileUseCase {

    TrainerProfileView getTrainerProfile(UUID userId);
}
