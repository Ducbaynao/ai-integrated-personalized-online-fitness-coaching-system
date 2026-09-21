package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;

public interface SubmitTrainerApplicationUseCase {

    TrainerApplication submitApplication(SubmitTrainerApplicationCommand command);
}
