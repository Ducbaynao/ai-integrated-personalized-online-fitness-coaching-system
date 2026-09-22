package com.fitnesscoaching.platform.modules.trainer.application.port.out;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerProfile;
import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface TrainerProfilePort {

    Optional<TrainerProfile> findByUserId(UUID userId);

    boolean existsByUserId(UUID userId);

    boolean existsByPublicSlug(String publicSlug);

    boolean existsByPublicSlugAndUserIdNot(String publicSlug, UUID userId);

    TrainerProfile save(TrainerProfile profile);

    TrainerProfile update(TrainerProfile profile);

    void updateVerificationStatus(UUID trainerId, TrainerVerificationStatus status, Instant updatedAt);

    int updateVerificationDetails(UUID trainerId, TrainerVerificationStatus status, Instant verifiedAt, UUID verifiedBy, Instant updatedAt);
}
