package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.application.model.TrainerPublicPreview;

import java.util.Optional;
import java.util.UUID;

public interface TrainerPublicPreviewQuery {
    Optional<TrainerPublicPreview> findPublicPreview(UUID trainerId);
}
