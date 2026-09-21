package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import com.fitnesscoaching.platform.modules.trainer.application.model.PatchField;

import java.math.BigDecimal;
import java.util.UUID;

public record UpdateTrainerProfileCommand(
        UUID userId,
        PatchField<String> publicSlug,
        PatchField<String> bio,
        PatchField<BigDecimal> yearsExperience,
        PatchField<Boolean> acceptingStudents
) {
}
