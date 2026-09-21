package com.fitnesscoaching.platform.modules.trainer.application.port.out;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerApplication;

import java.util.Optional;
import java.util.UUID;

public interface TrainerApplicationPort {

    boolean hasActiveApplication(UUID trainerId);

    Optional<TrainerApplication> findCurrentByTrainerId(UUID trainerId);

    TrainerApplication save(TrainerApplication application);
}
