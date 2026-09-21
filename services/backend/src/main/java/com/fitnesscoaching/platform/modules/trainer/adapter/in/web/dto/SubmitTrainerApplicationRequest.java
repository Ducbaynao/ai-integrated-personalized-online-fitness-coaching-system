package com.fitnesscoaching.platform.modules.trainer.adapter.in.web.dto;

import com.fitnesscoaching.platform.modules.trainer.application.port.in.SubmitTrainerApplicationCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record SubmitTrainerApplicationRequest(
        List<@NotNull(message = "Certificate ID cannot be null") UUID> certificateIds,
        List<@NotNull(message = "Document media ID cannot be null") UUID> documentMediaIds,
        @Size(max = 2000, message = "Applicant note cannot exceed 2000 characters")
        String applicantNote
) {
    public SubmitTrainerApplicationCommand toCommand(UUID trainerId) {
        return new SubmitTrainerApplicationCommand(
                trainerId,
                certificateIds != null ? certificateIds : List.of(),
                documentMediaIds != null ? documentMediaIds : List.of(),
                applicantNote
        );
    }
}
