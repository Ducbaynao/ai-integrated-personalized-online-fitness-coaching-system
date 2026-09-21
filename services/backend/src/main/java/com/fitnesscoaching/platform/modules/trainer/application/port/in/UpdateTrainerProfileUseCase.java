package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerProfileView;

public interface UpdateTrainerProfileUseCase {

    TrainerProfileView updateTrainerProfile(UpdateTrainerProfileCommand command);
}
