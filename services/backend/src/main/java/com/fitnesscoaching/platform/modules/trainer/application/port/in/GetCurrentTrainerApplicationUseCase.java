package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;

import java.util.UUID;

public interface GetCurrentTrainerApplicationUseCase {

    TrainerApplication getCurrentApplication(UUID trainerId);
}
