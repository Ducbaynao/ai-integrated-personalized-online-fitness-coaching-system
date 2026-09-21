package com.fitnesscoaching.platform.modules.trainer.application.port.out;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;

import java.util.Optional;
import java.util.UUID;

public interface TrainerProfilePort {

    Optional<TrainerProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByPublicSlug(String publicSlug);

    boolean existsByPublicSlugAndUserIdNot(String publicSlug, UUID userId);

    TrainerProfile save(TrainerProfile profile);

    TrainerProfile update(TrainerProfile profile);
}
