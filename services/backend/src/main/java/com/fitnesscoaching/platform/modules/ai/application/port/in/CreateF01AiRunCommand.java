package com.fitnesscoaching.platform.modules.ai.application.port.in;

import java.util.UUID;

public record CreateF01AiRunCommand(
        String question,
        String language,
        UUID exerciseId,
        UUID correlationId
) {
}
