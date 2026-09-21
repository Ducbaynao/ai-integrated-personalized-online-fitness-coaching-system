package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import java.math.BigDecimal;
import java.util.UUID;

public record CreateTrainerProfileCommand(
        UUID userId,
        String publicSlug,
        String bio,
        BigDecimal yearsExperience,
        Boolean acceptingStudents
) {
}
