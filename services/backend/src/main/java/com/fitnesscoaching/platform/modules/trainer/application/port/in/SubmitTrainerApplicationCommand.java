package com.fitnesscoaching.platform.modules.trainer.application.port.in;

import java.util.List;
import java.util.UUID;

public record SubmitTrainerApplicationCommand(
        UUID trainerId,
        List<UUID> certificateIds,
        List<UUID> documentMediaIds,
        String applicantNote
) {
    public SubmitTrainerApplicationCommand {
        certificateIds = certificateIds != null ? List.copyOf(certificateIds) : List.of();
        documentMediaIds = documentMediaIds != null ? List.copyOf(documentMediaIds) : List.of();
    }
}
