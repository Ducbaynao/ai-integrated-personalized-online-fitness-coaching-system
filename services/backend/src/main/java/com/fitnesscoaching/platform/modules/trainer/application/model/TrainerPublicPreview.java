package com.fitnesscoaching.platform.modules.trainer.application.model;

import com.fitnesscoaching.platform.modules.trainer.domain.TrainerVerificationStatus;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record TrainerPublicPreview(
        UUID trainerId,
        TrainerVerificationStatus verificationStatus,
        List<String> specialties,
        BigDecimal yearsExperience,
        boolean isAcceptingStudents
) {
    public TrainerPublicPreview {
        specialties = specialties == null ? List.of() : List.copyOf(specialties);
    }
}
