package com.fitnesscoaching.platform.modules.ai.application.port.out;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface AiFeatureFlagRepository {

    record FeatureFlagRecord(
            UUID id,
            String code,
            String description,
            boolean enabled,
            UUID updatedBy,
            Instant updatedAt
    ) {}

    Optional<FeatureFlagRecord> findByCode(String code);

    FeatureFlagRecord setEnabled(String code, String description, boolean enabled, UUID updatedBy);
}
