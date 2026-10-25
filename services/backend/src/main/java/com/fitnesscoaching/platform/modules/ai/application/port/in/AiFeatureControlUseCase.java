package com.fitnesscoaching.platform.modules.ai.application.port.in;

import java.time.Instant;
import java.util.UUID;

public interface AiFeatureControlUseCase {

    String F01_FEATURE_CODE = "F01_GENERAL_KNOWLEDGE";

    record AiFeatureStatusDto(
            String featureCode,
            boolean enabled,
            String description,
            UUID updatedBy,
            Instant updatedAt
    ) {}

    boolean isF01Enabled();

    AiFeatureStatusDto getF01Status();

    AiFeatureStatusDto setF01Enabled(UUID actorId, boolean enabled, String reason);
}
