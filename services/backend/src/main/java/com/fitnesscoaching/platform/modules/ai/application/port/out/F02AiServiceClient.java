package com.fitnesscoaching.platform.modules.ai.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface F02AiServiceClient {
    String generateProposalJson(F02ServiceRequest request);

    record F02ServiceRequest(
            UUID studentId,
            UUID coachingPeriodId,
            String mode,
            String fitnessLevel,
            String goalType,
            List<String> equipmentAvailable,
            Map<String, Object> preferences,
            List<UUID> candidateVariationIds
    ) {}
}
