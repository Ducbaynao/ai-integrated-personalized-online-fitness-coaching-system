package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;

public interface CreateTrainerProfileUseCase {

    TrainerProfile createTrainerProfile(CreateTrainerProfileCommand command);
}
